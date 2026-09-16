# Audio playback and tag editing

Background playback (`com.fotoxplorr.app.playback`) and file tag editing
(`com.fotoxplorr.app.audiotags`) for the standalone Audio destination — added on top of the
screen-bound player and read-only tag display that shipped in Phase 5.

## Service lifecycle

`PlaybackService` (`androidx.media3.session.MediaSessionService`) owns the single `ExoPlayer`
instance for the whole app. Every UI surface — `AudioPlayerScreen`, `AudioLibraryScreen`'s
"now playing" mini-bar — connects its own short-lived `MediaController`
(`rememberAudioController()`) to it and releases that controller when the composable leaves
composition; releasing a controller never stops playback, since the controller is a thin, replaceable
mirror of the one real session.

- **Starts foreground** automatically, driven by Media3: the moment the player is actually
  playing, the session's default notification (app icon, title/artist, transport controls) puts
  the service in the foreground. Nothing in this app's code calls `startForeground` directly.
- **Stops itself** when the player reaches `STATE_IDLE` (an explicit stop, or an unrecoverable
  error) or `STATE_ENDED` (the whole queue finished with repeat off) — both mean there is nothing
  left to do. `Player.Listener.onPlaybackStateChanged` only fires on a genuine state *change*, so
  this never fires for the player's starting state at construction time.
- **`onTaskRemoved`** (the task swiped away from recents): if something is actively playing,
  playback continues — matching every mainstream music app, and a real improvement over the
  previous screen-bound player, which stopped the instant its Activity was destroyed regardless of
  what the person intended. If nothing is playing, the service stops immediately: a paused session
  has no notification worth keeping alive and no reason to hold a foreground service open.

## Permissions

This workstream's code needs the following at runtime; **declaring** the `<uses-permission>`
lines and requesting them is owned by a different workstream (see this app's permission request
flow in `FotoXplorrActivity`), so this only records what the playback/tag-editing code assumes is
already granted by the time it runs:

| Permission | API level | When it must be requested |
|---|---|---|
| `FOREGROUND_SERVICE` | all supported (26+) | before starting playback for the first time |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 34+ | same as above — required alongside the generic one on U+ |
| `POST_NOTIFICATIONS` | 33+ | before starting playback; without it, playback still works but the transport notification never appears |
| `WAKE_LOCK` | all supported | manifest-only; `ExoPlayer`'s `WAKE_MODE_LOCAL` (see `PlaybackService`) acquires the lock itself while playing |
| `READ_MEDIA_AUDIO` | 33+ | before the audio scan/library screen runs at all (the same point image/video permissions are requested today) |

None of these are network permissions and none affect the offline flavor's gates.

## Tag editing (`com.fotoxplorr.app.audiotags`)

`AudioTagCodec` reads and writes `AudioTags` (title/artist/album/year/track/genre/cover) across
ID3v2 (MP3), MP4/M4A `ilst`, and FLAC Vorbis comments, entirely as byte manipulation — no Android
API in the codec package itself. `AudioTagWriter` is the Android side: it reads a file's bytes,
calls the codec, and writes the result back through `ContentResolver.openFileDescriptor(uri,
"rwt")` (truncating, since a rewritten tag is very often a different size than the original).

**Consent flow.** `AudioTagWriter.write` does exactly what `MetadataWriter.write` already does for
photo metadata: it attempts the write and lets a `RecoverableSecurityException` (API 29) or a plain
`SecurityException` (API 30+, consent never requested) propagate through the returned `Result`. The
host Activity is expected to wrap it the same way it already wraps `requestMetadataWrite`:

1. On API 30+, call `MediaStore.createWriteRequest(contentResolver, listOf(asset.contentUri))`
   *before* calling `AudioTagWriter.write`, and launch the returned `IntentSender` through
   `ActivityResultContracts.StartIntentSenderForResult`; call `write` only after the user grants it.
2. On API 29, call `write` directly; if the `Result` fails with a `RecoverableSecurityException`,
   launch `error.userAction.actionIntent.intentSender` the same way, then retry `write`.
3. Below API 29, `write` succeeds or fails outright — no consent screen exists to show.

This is the identical three-tier dance `requestRename`/`requestMetadataWrite` already implement;
a fourth, separate implementation was deliberately not written for this feature.

### Known limits

- **A write cannot clear a field.** `AudioTags`' `null` means "leave this field exactly as the file
  already has it" (resolved once, centrally, in `AudioTagCodec.write`, against the file's own
  existing tags) — there is no way to explicitly blank out an existing title, cover, etc. short of
  overwriting it with a different value.
- **A tag rewrite is not a full merge for MP3 or FLAC.** ID3v2 writes always rebuild the tag from
  only the seven fields `AudioTags` models; comments, lyrics, custom `TXXX`/Vorbis fields and
  replay-gain data are not preserved across an edit. FLAC's `VORBIS_COMMENT` block is rebuilt the
  same way (every OTHER metadata block — `SEEKTABLE`, `CUESHEET`, `APPLICATION`, `PICTURE`'s
  container — is preserved byte-for-byte; only the comment fields themselves are not merged).
  MP4/M4A does not have this limit: unrecognised `ilst` items (an `aART`, a `----` freeform atom)
  round-trip untouched, because the box tree parser only needs to understand the handful of item
  types it actually rewrites and treats everything else as an opaque, preserved leaf.
- **No pick-a-new-cover flow.** `AudioTagEditorSheet` shows the file's existing cover read-only;
  there is no image picker wired into it yet.
- **MP4 chunk-offset shifting assumes the rewritten `moov` still fits a 32-bit box size**, which is
  true for anything this app is likely to tag (a `moov` atom carries no audio/video samples) but is
  an explicit, not-yet-hit limit worth naming.

## What was not tested on a real device

None of this — the service's actual foreground/notification behaviour, audio focus handling, or
`onTaskRemoved` under real process death — has run on an emulator or device (none was available in
this environment). Verification here is compile plus unit tests only; a real-device pass is a
worthwhile follow-up before this ships.
