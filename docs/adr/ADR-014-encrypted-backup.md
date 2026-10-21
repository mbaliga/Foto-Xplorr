# ADR-014 — Encrypted backup: format, keys and what the cipher layer promises

**Status:** accepted (crypto core; the planner, writer, restore and UI are later work units)
**Date:** 21 Oct 2026
**Scope of this record:** the wire format v1 and the cipher layer under `app/.../backup/`
(`BackupFormat.kt`, `BackupKeys.kt`, `ChunkedAead.kt`, `TinkChunkedAead.kt`, `BackupStreams.kt`).
How files are chosen, packed, resumed and restored is described only as far as the format
constrains it.

## Context

The only backup today is a plain JSON export of tags and collections. A person who loses the
phone loses every photo. The owner asked for a backup of the photos themselves, written to a
folder they choose (an SD card, a USB drive, a folder a cloud app syncs). The folder is outside
Fotoz's control, and it will hold photos that are protected, sensitive or geotagged, so what is
written there must be unreadable without a secret only the person holds.

## Threat model

**Defended against**

- Someone who obtains the destination copy: a lost SD card, a stolen drive, a cloud account that
  is breached or subpoenaed, a cloud provider scanning its own storage. They must learn nothing
  about the contents: not photos, not file names, not folder names, not tags. They can see how
  many files the set has, how large each is, and when each was written.
- Accidental damage: a flipped bit, a half-copied file, a card removed mid-write. Damage must be
  detected, never silently restored as a corrupt photo.
- Hostile or malformed files placed in the folder: a header that asks for ten billion KDF
  iterations, a version from the future, a chunk pasted in from another backup. These are
  refused with a typed error, before any expensive work where possible.

**Not defended against**

- A malicious or compromised phone. While a backup runs the passphrase and the key are in
  memory and the photos are in the clear. Zeroing the key arrays is best effort, nothing more.
- A weak passphrase. PBKDF2 is GPU-friendly; a stolen set can be attacked offline for as long as
  the attacker likes. The interface enforces a 12-character minimum and says so; it cannot make
  a guessable passphrase strong.
- Traffic analysis of the folder (sizes, count, timestamps), as above.
- Rollback to an older manifest generation. See "Accepted weaknesses".

## Decision

### No passphrase recovery, by design

The passphrase is never stored: not in preferences, not in the Keystore, not in a journal. There
is no recovery question, no hint store, no escrow. Forget it and the set cannot be opened by
anyone, including us. This is the owner's decision, and it is why scheduled and automatic
encrypted backups do not exist: they would need the key kept somewhere. Every consequence is
stated in the passphrase dialog, which later units pin in `HelpCopyTest`.

### Layout of a backup set (informative here, built later)

A set is a folder holding `manifest-NNNN.fzbm` files (generation numbers, each a complete
snapshot as JSON lines) and `pack-NNNN.fzbp` files (immutable once closed, about 1 GiB, hard cap
below 4 GiB so FAT32 and exFAT cards work). File names carry no information about the content.
Resume state is derived from the destination: each closed pack has an encrypted index file
beside it, so no plaintext journal exists on the phone. The highest generation that
authenticates in full wins, so a half-written manifest is harmless and no atomic rename on the
storage framework is needed. Backups are additive: nothing is ever deleted from the set.

### File format v1

Every file of a set has the same shape: a 36-byte plaintext header, then one Tink streaming-AEAD
stream.

```
offset  size  field
0       8     magic            "FOTZBKP\0"
8       2     version          u16 big-endian, 1
10      1     kind             1 = pack, 2 = manifest, 3 = pack index
11      1     kdfId            1 = PBKDF2-HMAC-SHA256
12      4     iterations       u32, accepted range 100,000 .. 10,000,000 (default 600,000)
16      16    setSalt          random per set, identical in every file of the set
32      4     segmentSize     u32, ciphertext bytes per segment, accepted 128 .. 8 MiB (default 1 MiB)
36      ...   Tink AES-GCM-HKDF stream (below)
```

The reader validates magic, version, kind, kdfId and both ranges *before* it derives a key, and
a failure is a typed `BackupException` (`NotABackup`, `UnsupportedVersion`, `UnsupportedKdf`,
`KdfOutOfRange`, `BadSegmentSize`). A hostile file can therefore not make the phone run a huge
KDF, and a future version is refused, not misread.

### Keys

1. The passphrase is normalised to Unicode NFC before it is used. Without this a backup typed on
   one keyboard (decomposed accents) fails on another (precomposed). PBKDF2 then takes the
   characters as UTF-8, which is what both the JDK and Android's BouncyCastle provider do; the
   golden vector pins that on the JVM, and `BackupKeys.providerMatchesGoldenVector()` lets a
   device prove it before it writes a first file (a provider that encoded differently would make
   a backup that opens only on the phone that wrote it). The vector's passphrase is non-ASCII and
   typed in decomposed form.
2. `master = PBKDF2-HMAC-SHA256(passphrase, setSalt, iterations, 32 bytes)`, computed once per
   run and reused for every file of the set that shares `setSalt` and `iterations`.
3. Per file, Tink derives the AES-256-GCM key as `HKDF-SHA256(ikm = master, salt = a fresh
   random 32-byte salt written in the stream header, info = the 36 header bytes)`.

PBKDF2 was chosen because it is the one vetted password KDF available on every Android version
the app supports (minSdk 26) without a new native library or a large provider. Argon2 and scrypt
would add a dependency with ABI-specific code to an APK that already carries three sets of ML
libraries; they are not needed for a personal gallery and are explicitly out of scope. The
iteration count lives in the header, so a later version can raise it without a format change.

### Chunking

The body is the stream format of Tink's `AesGcmHkdfStreaming` (AES-256-GCM, HKDF-SHA256, as
documented by Tink), with these parameters: 32-byte master as input key material, SHA-256, a
32-byte derived key, `segmentSize` ciphertext bytes per segment.

- Stream header: 1 length byte (40), the 32-byte HKDF salt, a 7-byte nonce prefix.
- Segment `i` is sealed with the nonce `prefix || u32 i || u8 isLast`. There is no per-segment
  AAD; the header is bound through the HKDF `info`.
- The first segment starts after the stream header, so it carries `segmentSize - 40 - 16`
  plaintext bytes; every later one carries `segmentSize - 16`. Each segment ends in a 16-byte tag.
- The last segment always exists and is flagged final. Empty plaintext is a single empty final
  segment (the file is 36 + 40 + 16 = 92 bytes).

So truncation at a segment boundary (the new last segment was not flagged final), an appended
segment, swapped segments, and a segment taken from another file (different salt, different key)
all fail authentication. The reader releases no plaintext of a segment before its tag verifies,
so memory is bounded by `segmentSize`.

One property is worth stating: the first segment carries its own stream header, so a file's
whole first segment replaced by another file's (same set, same passphrase) is a valid first
segment. The splice is caught at the second segment, and the pack index and manifest, which
record each pack's length and every entry's SHA-256, catch it again at verify time.

### What the header authenticates

All 36 header bytes are the HKDF `info`. Change any bit of the magic, version, kind, KDF
parameters, set salt or segment size and the derived key changes, so the first segment fails
(or, for a field the reader range-checks, the file is refused earlier). Consequences:

- A *kind* swap fails: a manifest cannot be presented as a pack index.
- A *set* swap fails: files carry the set salt, the set salt feeds PBKDF2, so a manifest from
  set A does not open under set B's key even with the same passphrase. This is asserted in a
  test. Within one set a swap between two files of the same kind is not a cryptographic error;
  the manifest names its packs and carries their lengths, which is where that is checked.
- The *iteration count* cannot be lowered by an attacker without breaking authentication.

A wrong passphrase and a damaged first segment are indistinguishable to GCM. The cipher layer
reports `WrongPassphraseOrDamaged` for both and the interface says exactly that ("wrong
passphrase, or the backup is damaged"). Later damage is `Damaged`.

### Versioning

`version` is 1. A reader refuses any other value before deriving a key. A change to the header
layout, the segment layout or the key schedule is a new version number; additive changes inside
a manifest's JSON lines (new fields, ignored by old readers) are not.

### Why Tink, and what the fallback is

The first design hand-built a STREAM-style construction on `javax.crypto`: counter nonce,
final-chunk flag in the AAD, a hand-written HKDF-Expand. Those are standard constructions, but
composing them is still rolling our own protocol, and each has a known trap (a GCM `Cipher`
throws or, worse, repeats a nonce if it is reused after `doFinal` without a fresh IV; a stream
without a final flag is truncatable). Tink's `AesGcmHkdfStreaming` is a reviewed, widely
deployed implementation of exactly that construction, so the format builds on it and the only
code of ours between the passphrase and the cipher is `Normalizer`, `SecretKeyFactory` and a
36-byte header.

The cipher sits behind one small interface, `ChunkedAead` (`encrypt(sink, ...)`,
`decrypt(source, ...)`, `encryptedSize(...)`), so the choice is reversible. If Tink ever has to
go, the fallback is the standard-JCA chunked AES-256-GCM construction with a fresh cipher init
and per-chunk nonce, the final-chunk flag in the AAD, the NFC-normalised UTF-8 passphrase, the
set salt bound into the AAD and the KDF bounds enforced before the KDF; the golden vector would
be regenerated for it. Nothing here invents a primitive.

The golden vector (`BackupFormatTest`) was produced by an independent implementation
(`app/src/test/resources/backup/golden_vector.py`, Python `hashlib` plus `cryptography`), written
from the format above and checked against Tink's output for eight sizes before it was used. The
Kotlin test decrypts the Python-made file with Tink, which is the proof that the format is
implementable outside this codebase.

### Dependency check (run 21 Oct 2026, before the dependency was committed)

- `com.google.crypto.tink:tink-android:1.22.0` (released June 2026). It carries a *relocated*
  protobuf (`com.google.crypto.tink.shaded.protobuf`) and depends on `androidx.annotation-jvm`,
  `jsr305`, `gson` and `error_prone_annotations`. It does **not** depend on `protobuf-javalite`,
  so there is no clash with MediaPipe's `protobuf-javalite:4.26.1`; the resolved offline and
  connect runtime classpaths show that artifact once, unchanged.
- The offline runtime classpath gains `tink-android`, `gson:2.13.2` (new) and lifts
  `error_prone_annotations` from 2.28.0 to 2.41.0 (annotations only). None is a network artifact.
- `verifyOfflineManifest`, `verifyOfflineRuntimeClasspath` and `verifyOfflineSourceReferences`
  pass with the dependency in place, and both flavours compile. No `.so` is added (Tink is pure
  Java), so the 16 KB page-alignment check is unaffected.
- Size: the jar is 3.3 MB before shrinking and R8 removes everything unreachable; this code uses
  only the streaming AEAD (no keyset, registry or protobuf path). The release APK delta is **not
  measured**; check it on the next release build. The estimate is under 1 MB.
- R8 / release: `:app:minifyOfflineRelease` succeeds twice. With no caller yet, R8 removes the whole
  backup package and Tink with it, which proves only that nothing breaks the build; so the check
  was repeated with a temporary `-keep class com.fotoxplorr.app.backup.**` (removed again), which
  forces R8 to process the Tink streaming classes (about 350 mapped names): no missing-class
  error, no new warning. Running the minified code was not possible without a device. The jar
  ships `META-INF/proguard/protobuf.pro` as a consumer rule and the streaming AEAD path uses
  neither reflection nor protobuf, so no keep rule is added; `proguard-rules.pro` already keeps
  `com.google.protobuf.**` for MediaPipe. The first unit that calls the backup code should
  re-run a minified build and one backup round trip on a device.

## Accepted weaknesses

- **Rollback is undetectable.** An attacker with write access to the folder can delete the newest
  manifest generation and leave an older, valid one. The set then looks like an earlier backup.
  Nothing in the format can prevent this without a counter stored somewhere the attacker cannot
  write, and the threat model does not include that attacker. Accepted.
- File counts, sizes and write times are visible.
- Brute force of a weak passphrase, as above.
- No in-place key change. Changing the passphrase means writing a new set.

## Consequences

- The wire format is frozen once a first backup ships. Version 1 can be extended only inside the
  manifest's JSON.
- Every later unit (planner, pack writer, restore, UI) talks to `BackupStreams` and
  `BackupKeys` and never to Tink.
- `uriForLocationRead` gains a sanctioned caller in the backup writer (original GPS goes into the
  encrypted pack); that is recorded in TRAPS #31 by the unit that adds the caller.
- The privacy policy line that says there is no backup becomes false once the feature ships, and
  is updated by the docs unit.
