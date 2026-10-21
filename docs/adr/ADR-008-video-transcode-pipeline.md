# ADR-008 — Video/audio pipeline: androidx.media3 (revision 2)

**Status:** accepted — supersedes this ADR's own revision 1 ("native `MediaCodec` only, no
Media3") outright, and folds in the separate `ADR-008-video-editing.md` written on
`origin/claude/fotoz-continue` rather than keeping two ADR-008s.
**Date:** 2026-09-16
**Still true of this revision:** not verified on a real device or emulator (this environment has
neither). What changed is *why* that caveat matters less: revision 1's pipeline had never run
either, and static review of it turned up defects a device pass would likely have caught anyway
(see "Reversal" below) — Media3 is the long-stable, widely-deployed library Android's own
ecosystem runs this exact class of problem through, not a first attempt at solving it from
scratch.

## Reversal

Revision 1 forbade any dependency for this feature, in `docs/adr/ADR-008-video-transcode-pipeline.md`
itself and in a comment on `viewer/VideoPlayer.kt`. Both restrictions are lifted. The owner has
approved adopting **androidx.media3**: ExoPlayer for playback, Transformer + Effect for exports and
the video editor. This is a full reversal, not an addition alongside the old pipeline — the
hand-written `MediaCodec`/`EGL` pipeline is deleted (see "What was deleted" below), not kept as a
fallback.

## Why: the concrete bugs, found by static review before any of this ran

Revision 1's pipeline was reviewed line by line against what it would actually do once it reached a
device, without a device to check it on. Every item below is a defect in the DESIGN, not a
hypothetical:

1. **Whole-track buffering risks OOM well inside the feature's own stated scope.** `EncodedTrack`
   held an entire compressed track in memory before muxing (see revision 1's own "limitation stated
   on purpose"). Ten minutes of 1080p H.264 is roughly 466 MB of compressed video alone — against a
   192–512 MB Java heap on a typical API 26+ device, that is an `OutOfMemoryError`, not a slow path.
   The real ceiling before OOM was closer to two minutes, not the ten the code's own constant
   claimed.
2. **1.5x/2x speed change would fail at `encoder.configure`, not just produce bad audio.** The
   "relabel the sample rate" tape-speed trick asked the AAC encoder to accept
   `originalRate * speedFactor` — 66150 Hz, 88200 Hz or 96000 Hz for a 44100 Hz source at 1.5x/2x.
   None of those are valid AAC sample rates on Android's encoder; `MediaCodec.configure` throws.
   This was not a quality compromise, it was a crash on two of the feature's five advertised speed
   presets.
3. **Source rotation was never read.** A portrait video's `MediaFormat` carries its rotation as
   metadata (`KEY_ROTATION`), not as transposed pixel dimensions — the decoded buffer is still
   landscape-shaped. Nothing in the GL bridge read or applied that value, so every portrait source
   would export squashed or sideways.
4. **Encoder/EGL resources leaked on a setup failure partway through.** The GL bridge's
   construction path had no `try`/`finally` release ordering that guaranteed a partially-built
   `EglCore`/`InputSurface`/encoder was torn down if a LATER step in the same setup threw — a
   failure on one video could leak a codec instance and starve every transcode after it in the same
   process.
5. **`EGL_RECORDABLE_ANDROID` was missing from the EGL config.** Without it, `eglCreateWindowSurface`
   against an encoder's input `Surface` is permitted to fail on stricter vendor GL drivers — this is
   a documented, load-bearing attribute for exactly this GL-to-`MediaCodec` bridge pattern, and its
   absence is the kind of gap that passes on one device and fails on another for a reason nobody can
   see from the stack trace.
6. **An HDR (HLG/PQ) source would export washed out.** The pass-through shader had no tone-mapping
   step at all; an HLG source's transfer function would be reinterpreted as SDR gamma verbatim.
7. **`AudioTranscoder` never read the decoder's actual output format.** HE-AAC (SBR) doubles its
   sample rate between the format the container declares and what the decoder actually outputs;
   code that trusted the declared format instead of `INFO_OUTPUT_FORMAT_CHANGED` would silently
   mislabel — and therefore mistime — every HE-AAC source.

Media3 fixes all seven by construction: Transformer streams to a file rather than buffering a whole
track, its speed-change path is Sonic-based pitch-preserving time-stretch (no invalid sample rate is
ever asked for), `ScaleAndRotateTransformation`/the frame processor pipeline reads and applies
source rotation, its EGL/codec lifecycle is one of the most exercised code paths in the Android
media ecosystem, `Composition.setHdrMode` drives real tone-mapping, and ExoPlayer's own decoder
wiring reads the negotiated output format rather than trusting the container's declared one.

### What `main` fixed in place before this revision landed (P0-11)

This revision branched from a `main` that still had revision 1's pipeline as-written. While it was
in review, `main` patched six of the defects above in place (the 2026-09 defect audit's P0-11,
`docs/handoff/PHASE-0-BRIEF.md`): a disk-backed `SampleStore` so `EncodedTrack` no longer held a
whole track in memory (item 1), `resamplePcm16` instead of relabelling the sample rate (item 2),
`MediaMuxer.setOrientationHint` from the source's rotation (item 3), `EGL_RECORDABLE_ANDROID` with a
fallback (item 5), an HDR pre-flight refusal (item 6), and reading the decoder's actual output
format (item 7). Item 4 (the setup-failure resource leak) was not touched. That does not change this
decision — those patches were as unverified on hardware as the code they patched, and the video
editor needs Transformer's effect pipeline regardless — but it does mean they are deleted with the
pipeline rather than carried over. Two behaviours that came with them have **no Transformer
counterpart yet** and are recorded here so they are a conscious gap rather than a silent one:

- **Free-space pre-flight** (`StatFs` check against `requiredSpillBytes`). Transformer writes the
  finished file to `cacheDir` and `VideoExporter` then copies it into MediaStore, so an export
  transiently needs roughly twice the output size free, and a full disk surfaces as an ordinary
  export failure (`VideoExporter.exportMessage`) rather than a refusal up front.
- **The ten-minute `MAX_TRANSCODE_DURATION_MS` cap.** It existed to bound the whole-track memory
  buffer, then was kept for "single-clip scope" reasons. Transformer streams, so nothing bounds
  clip length now.

The remaining P0-11 behaviours are replaced rather than dropped. HDR sources are no longer refused:
`Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` tone-maps them, and a device that cannot
reports that as a specific error. The even-dimension rounding and `isSizeSupported` check
(`evenSize`, `avcEncoderSupportsSize`) are left to the encoder factory's own fallback, which picks a
supported size and bitrate instead of failing the export.

## What was deleted

- `com.fotoxplorr.app.video.VideoTranscoder` and the whole `com.fotoxplorr.app.video.gl` package
  (`EglCore`, `InputSurface`, `DecoderOutputSurface`, `TextureRenderer`) — the hand-written
  decode-GL-encode bridge.
- `com.fotoxplorr.app.audio.AudioTranscoder` and `com.fotoxplorr.app.audio.PcmTiming` — the
  hand-written buffer-mode AAC re-encode loop.
- `com.fotoxplorr.app.video.EncodedTrack` and its disk-spilling `SampleStore` (the track-buffering
  currency both of the above produced) once nothing else referenced it.
- Their now-dead, pinned unit tests: `SampleStoreTest`, `RequiredSpillBytesTest`,
  `AudioTranscoderTest`, `VideoCodecCapabilityTest` (which covered only the HDR and `evenSize`
  helpers), and `VideoEditRecipeTest` — whose original revision asserted that a 44100 Hz source doubles to a
  literal 88200 Hz label at 2x speed (exactly the invalid-sample-rate behaviour item 2 above
  describes, pinned as if it were correct) and whose P0-11 revision pinned `resamplePcm16`.
- `com.fotoxplorr.app.videoeditor.VideoEditRecipe` (trim/speed only, tape-speed pitch, and the
  P0-11 `resamplePcm16`), replaced by `VideoEditPlan` (below).

**Kept:** `com.fotoxplorr.app.video.MediaSampleFlags` (`muxerBufferFlagsFor`) — still the one tested
translation `com.fotoxplorr.app.moments.ClipExporter`'s stream-copy path uses (see `docs/TRAPS.md`
#24) and unrelated to the transcode pipeline itself — `StreamCopyRemuxer` (P0-05's location-free
video share) uses it too, alongside `Container` and `VideoRotation`. `VideoCodecCapability`, repurposed to check any
requested codec's encoder availability (`deviceSupportsVideoEncoder`), not just the fixed H.264/AAC
target revision 1 had; its P0-11 HDR and size helpers went with the pipeline they guarded.

## What Media3 now covers

- **Playback** (`viewer/VideoPlayer.kt`): ExoPlayer replaces `android.widget.VideoView`. Beyond
  parity (play/pause/seek/mute/key-moments/clip-share), this gains speed presets with a
  pitch-preserve toggle, embedded and sidecar (`.srt`/`.vtt`/`.ass`/`.ssa`) subtitle selection, audio
  track selection, double-tap seek, edge-swipe brightness/volume, hold-to-2x, an A-B loop, a
  per-video resume position, picture-in-picture, and a `VideoTrackInfo` readout.
- **Export** (`video/VideoExporter.kt`, on Transformer): trim, Sonic pitch-preserving speed, rotate,
  mirror, a centred aspect crop, mute, H.264/HEVC codec choice (checked against
  `deviceSupportsVideoEncoder` first), Original/1080p/720p quality presets
  (`Presentation.createForShortSide` + a requested `VideoEncoderSettings` bitrate, with encoder
  fallback enabled), and HDR-to-SDR tone-mapping requested unconditionally (a no-op for an
  already-SDR source; see that file's own doc for why). Publishes to MediaStore with `IS_PENDING`,
  a validated `RELATIVE_PATH` (the shared `mediaStoreRelativePath` rule in
  `media/MediaStoreDestination.kt`, P0-07: a source under `DCIM/`/`Pictures/`/`Movies/` keeps its
  own folder, anything else falls back to `Movies/Fotoz/`, and a source already inside a pre-rename
  `Movies/Foto Xplorr/` folder stays there), `DATE_TAKEN` carried over from the source,
  delete-on-failure, `onProgress` polled every 250ms, and
  cooperative cancellation that cancels the `Transformer` and deletes the temp file. Catches
  `Throwable`, not just `ExportException` — the one weakness the Media3 prototype this was ported
  from (`origin/claude/fotoz-continue`) still had: a publish failure escaping past a narrower catch
  and crashing the caller instead of surfacing as a normal `Result.failure`.
- **`VideoConversionWriter.convertToH264Mp4(asset)`** and **`AudioConversionWriter.convertToAac(asset)`**
  keep their exact signatures and now delegate to Transformer; the video writer gained an
  options+progress overload, the audio writer gained a bitrate parameter and the same shared
  `mediaStoreRelativePath` rule for its own collection (`Music/`, `Podcasts/`, `Audiobooks/`,
  `Ringtones/`, `Alarms/`, `Notifications/`, and `Recordings/` from API 31, falling back to
  `Music/Fotoz/`).
- **Video editor** (`videoeditor/`): `VideoEditPlan` (ported from the prototype's `VideoEditPlan`,
  extended with brightness/contrast/saturation and mapped onto `VideoExportOptions` for export) is
  invariant-checked, rotation-saveable via a `rememberSaveable` `listSaver`, and drives a live
  ExoPlayer preview (trim/speed/mute are live; rotation/mirror transform the surface; crop and
  filters are shown honestly as a frame overlay / not live-previewed rather than pretending to be
  pixel-accurate). Export reuses `VideoExporter` entirely, so the plain conversion path and the
  editor share every hardening fix above rather than each carrying its own Transformer wiring. A
  duration poll that could spin forever on an unplayable source (ten minutes, `while (true)`, no
  exit) is now bounded to a fixed number of attempts before giving up. The prototype's rotation
  preview scale (`1f / fit`) enlarged a portrait source instead of shrinking a landscape one to fit
  a portrait viewport; this fixes the same bug by guarding the zero-height/zero-fit case and
  keeping the intent (shrink, not enlarge) explicit.

## Why the offline gates still hold

`media3-exoplayer` and `media3-common` declare `android.permission.ACCESS_NETWORK_STATE` in their
own manifests (for adaptive-streaming bandwidth signals this app never exercises — every source
here is a local `content://` file). `app/src/offline/AndroidManifest.xml` already strips it with
`tools:node="remove"`, so `verifyOfflineManifest` passes on the MERGED manifest, which is the thing
the OS actually enforces (`docs/TRAPS.md` #12). No Media3 artifact appears on
`verifyOfflineRuntimeClasspath`'s banned-prefix list (`okhttp3`, `ktor`, `grpc`, `maplibre`,
`:feature:ai-remote`) — Media3 carries no networking library of its own. `verifyOfflineSourceReferences`'s
FQCN denylist is untouched by this change. All three gates were re-run after adding the dependency
block and after every other change in this revision; the report accompanying this ADR states the
concrete result.

## Deliberately not in this revision

Multi-clip timelines/transitions, music/audio-track replacement in the editor (Media3's
`Composition`/`EditedMediaItemSequence` supports the mechanics; the picker UI and audio-sequence
sync are real, separate work not attempted here), LUT-style colour lookup filters (the brightness/
contrast/saturation sliders plus four named presets cover v1; a LUT system should be shared with the
photo editor's own look system rather than invented twice), stabilization (no Media3 support),
auto-captions, and lossless edit-list trimming (`experimentalSetMp4EditListTrimEnabled` — still
experimental).

## Remaining owner device checks

Nothing in this revision has been run on hardware. Before calling the feature done: an export
matrix across real devices (H.264 and HEVC sources and targets; HLG/PQ HDR sources — the tone-map
result must be LOOKED AT, not just "did not throw"), rotation+crop composition on an actual portrait
recording, cancel-mid-export leaving nothing behind, a Files-visible check that `IS_PENDING`
behaves correctly on the API 26–36 spread this app supports, subtitle sidecar discovery against a
real SAF/MediaStore layout, picture-in-picture entry/exit across the same spread (auto-enter is
API 31+ only by construction), and the brightness/volume edge-swipe gesture's coexistence with
`ViewerScreen`'s own pinch/pan/page gesture arbitration under real touch input rather than reasoned
about from the gesture-detection source alone (see `viewer/VideoPlayer.kt`'s own doc for that
reasoning).
