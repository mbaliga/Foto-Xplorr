# Privacy and security baseline

## 1. Trust boundaries

Foto Xlorr handles four high-risk classes of input:

1. untrusted image files and metadata;
2. user-authorized storage locations and destructive operations;
3. local model files and native model runtimes;
4. user-configured network endpoints and credentials.

“On device” does not automatically mean safe. A malicious image or model can exploit a parser, exhaust memory, or poison derived metadata. A local backup can expose an entire photo history if it is weakly encrypted. The architecture treats these as security boundaries, not ordinary helper libraries.

## 2. Data inventory

Potentially sensitive data includes:

- original media and thumbnails;
- dates, locations, altitude, camera direction, faces, OCR text, and captions;
- tags, albums, groups, hidden/favorite state, and user corrections;
- content hashes and similarity embeddings;
- model files and model outputs;
- remote endpoint configuration and API keys;
- backup manifests and restore history.

The application should maintain a machine-readable data inventory as these tables and caches are implemented.

## 3. Default privacy posture

- `android:allowBackup="false"` prevents automatic platform backup of app-private data by default.
- No analytics, ad, crash-upload, account, or telemetry SDK is part of the baseline.
- No broad all-files permission is requested.
- Media location and device location are requested only when a spatial feature needs them and after contextual explanation.
- No map tile, reverse-geocoding, AI, or update endpoint is contacted by entering the gallery.
- Logs use synthetic identifiers and redact paths, coordinates, prompts, keys, and response bodies in release builds.

## 4. Image-decoder threats

Threats:

- memory corruption in native codecs;
- decompression bombs and huge declared dimensions;
- excessive frame/page counts;
- recursive or external references in vector/document formats;
- CPU hangs;
- malformed metadata causing parser confusion;
- thumbnail/full-image mismatch.

Controls:

- validate signatures and dimensions before full allocation;
- cap pixels, decoded bytes, frames/pages, recursion, and elapsed time;
- isolate native decoding from the main process;
- give the codec process no network access and no general storage traversal;
- pass a read-only file descriptor for one request;
- cancel stale viewport work;
- fuzz native and structural parsers;
- preserve decoder/version provenance for incident triage.

## 5. File-operation threats

Threats:

- deleting the wrong item after a stale catalog lookup;
- losing data during cross-provider move;
- overwriting name conflicts;
- reporting success after partial failure;
- acting on a URI whose grant was revoked or whose content changed.

Controls:

- resolve and fingerprint sources at planning and immediately before execution;
- preview every bulk operation and conflict policy;
- stream copy, close successfully, and verify before source deletion;
- use Android confirmation and trash APIs where applicable;
- return per-item results;
- record a local operation journal without storing sensitive content;
- never map virtual grouping to a physical move.

## 6. AI and model threats

Threats:

- malicious or incompatible model files;
- unbounded memory/thermal use;
- prompt or image disclosure to a misconfigured endpoint;
- API key leakage through logs, backups, screenshots, or exported settings;
- model output silently mutating the library;
- embeddings or captions exposing private content.

Controls:

- validate model architecture, size, checksums, and capability before loading;
- show projected storage/RAM constraints and fail closed on incompatibility;
- isolate local native runtime where practical;
- make remote providers opt-in and identify destination per task;
- use TLS validation and disallow cleartext by default;
- wrap stored credentials with an Android Keystore-backed key;
- exclude credentials from logs, clipboard defaults, exports, and backups;
- treat all model output as an assertion with provenance and review state;
- prohibit AI providers from invoking file mutations directly.

Android Keystore protects stored credential material but cannot guarantee that a key is never present in process memory while a request is made. Documentation must not overclaim this boundary.

## 7. Catalog encryption

The Android app sandbox is the baseline. If encrypted-at-rest catalog mode is implemented:

- generate a random database key;
- wrap it with Android Keystore;
- use an established database encryption implementation;
- define device-lock changes, biometric invalidation, recovery, and migration behavior;
- ensure previews, temporary files, journals, and search indexes follow the same policy.

A lock screen that leaves unencrypted thumbnails or embeddings in caches is not an encrypted gallery.

## 8. Portable encrypted backups

A portable backup cannot rely only on a non-exportable Android Keystore key. It needs separate recovery material such as a passphrase or recovery key.

Requirements:

- use audited primitives and an established implementation;
- memory-hard passphrase derivation where supported;
- authenticated streaming/chunk encryption;
- unique nonce/key material per archive and chunk according to the chosen construction;
- authenticated manifest and file hashes;
- interrupted-write detection;
- verification before success;
- restore preview before activation;
- no credentials, API keys, Keystore material, or transient caches;
- documented format version and migration policy.

The exact archive format is deliberately not locked here. It needs a dedicated cryptographic ADR and review; inventing a bespoke container in application code is unacceptable.

## 9. Location privacy

- Location remains local unless the user invokes a disclosed network feature.
- Reverse geocoding is offline or explicitly networked; it is never a silent convenience call.
- Map/terrain package source and request behavior are shown before download.
- Export/share warns when original files contain location metadata.
- Users can hide spatial views, remove catalog location assertions, or create redacted copies without modifying originals by default.

## 10. Supply chain

- Pin build actions by reviewed major versions initially and move to commit pinning for hardened releases.
- Generate an SBOM for release artifacts.
- Track native codec/model-runtime CVEs and upstream versions.
- Keep public core and any future private distribution material separate; no private URLs, keys, test photos, or proprietary fixtures enter this repository.
- CI secrets are unavailable to untrusted fork pull requests.
- Third-party sample images require provenance and redistribution permission.

## 11. Security release gates

A release is blocked by:

- critical/high unresolved vulnerability in a reachable parser/runtime;
- unreviewed native codec;
- plaintext credential persistence;
- backup that has not passed clean-device restore and tamper tests;
- destructive operation without per-item failure accounting;
- undocumented outbound request;
- missing license or required third-party notice;
- privacy documentation that does not match actual runtime behavior.
