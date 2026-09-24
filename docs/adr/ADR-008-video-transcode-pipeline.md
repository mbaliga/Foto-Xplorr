# ADR-008 — Video/audio transcode pipeline: native `MediaCodec` only

**Status:** accepted (interim — NOT verified on a real device or emulator)
**Date:** 2026-09-07
**Scoping round:** clarifying questions on the original "feature complete, kill other apps" request
narrowed format-conversion to **native codecs only** — explicitly ruling out an FFmpeg-class
dependency, given the offline flavour's zero-new-network-dependency gates and APK-size/licensing
concerns. Audio scope was confirmed separately: full standalone audio browsing/playback.

## What this is

A decode → (GL) process → encode → mux pipeline built entirely on `android.media.MediaCodec`,
`MediaExtractor`, `MediaMuxer` and `android.opengl`/`EGL14`/`GLES20` — zero third-party
dependencies, zero new Gradle coordinates. It is exercised by one concrete, user-facing capability:
converting any decodable video's picture track to H.264/AVC and its audio track to AAC into a
fresh MP4 (`VideoTranscoder`, `VideoConversionWriter`) — video's own Save As, and a direct answer
to the original request's "save as another format." Speed change, trim, text overlay and music
mixing (the roadmap's own Phase 4) are meant to be built on this same pipeline, not this ADR's
concern.

## Why H.264/AAC/MP4, not a chosen target

Every API 26+ device this app supports is required by Android's own compatibility definition to
carry an H.264 encoder and an AAC encoder — nothing else (VP8/VP9/HEVC encode) is guaranteed at
all. A format picker the way `com.fotoxplorr.app.editor.OutputFormat` offers for photos would need
to discover, per device, which of several targets are even available, and handle a second
container (WebM) whose own audio requirement (Vorbis/Opus, not AAC) is an independent axis of the
same problem. Narrowing to the one target every device can reach is also, not coincidentally, what
a "make this play everywhere" conversion is actually for. Additional targets are a named
follow-up, not an oversight.

## Why a GL bridge, not a buffer copy, for the pixels

A decoder and an encoder each speak buffers or a `Surface` — never each other directly. There is
no OS call connecting a decoder's output surface to an encoder's input surface. Buffer-mode video
decode was considered and rejected: raw YUV buffer layouts vary by device/vendor
(`COLOR_FormatYUV420Flexible` and a long tail of vendor-specific formats), which is exactly the
"format zoo" every real MediaCodec transcoding library (Android's own `bigflake`/`grafika`
reference samples, and every OSS transcoder built since) works around by rendering the decoder's
output as a GL texture onto the encoder's input surface instead — a pass-through shader for this
first pass, and the seam a future filters tool replaces.

Audio does not have this problem — every decoder hands back the same interleaved 16-bit PCM shape
regardless of source codec — so `AudioTranscoder` uses plain `MediaCodec` buffer mode, no GL at
all.

## A limitation stated on purpose: whole-track buffering

`MediaMuxer.start()` cannot be called until every track it will carry has been added, and a track
can only be added once its encoder reports a final `MediaFormat` — an event that arrives only after
encoding has begun. Rather than run the video and audio encoders genuinely concurrently to keep
both "waiting to start" in step (real, but meaningfully more complex, plumbing), this pipeline
encodes each track to completion and muxes both afterward — mirroring `ClipExporter`'s own
established precedent of copying "the whole video track and then the whole audio track" rather than
interleaving them, since MP4 is an indexed container and only interleaving matters for
progressive-download streaming.

**As of the "Spill instead of memory" addendum below, this is no longer a memory limitation** — a
whole clip's compressed track used to sit in memory before any of it reached disk; it now spills to
a temp file instead, and the paragraph that used to justify `MAX_TRANSCODE_DURATION_MS` on OOM
grounds is superseded by that addendum's own reasoning for keeping the cap anyway. The
whole-track-before-muxing SHAPE described above is unchanged and still the reason two tracks are
never interleaved; only where the bytes live changed.

## What has NOT been done, and must be before this ships

**This pipeline has not been run.** The environment this was built in has no Android emulator and
no connected device (`adb devices` reports zero; no `emulator` package is installed; this repo has
no `androidTest` source set at all). Every non-trivial piece — the EGL context/surface plumbing,
the OES texture bridge, the synchronous decode/encode drive loop, the AAC buffer bookkeeping — was
written by close, careful reference to the long-stable (over a decade unchanged), extensively
documented `EGL14`/`GLES20`/`SurfaceTexture`/`MediaCodec` Surface-transcode pattern Android's own
sample repositories use, and everything PURELY logical was pulled out and unit-tested on the JVM
(`PcmTiming`, `MediaSampleFlags`, `ConvertedNameTest`) — but a design that closely follows a known
pattern is not the same claim as "this has been verified to work." Test this on a real device
across a spread of manufacturers before trusting it in production; do not treat a clean compile and
a green `verify.sh` as evidence the transcode itself produces a valid, correctly-timed MP4.

## Package shape

- `com.fotoxplorr.app.video.gl` — `EglCore`, `InputSurface`, `DecoderOutputSurface`,
  `TextureRenderer`: the reusable GL bridge, deliberately isolated so a future filters tool only
  ever needs to touch `TextureRenderer`'s fragment shader.
- `com.fotoxplorr.app.video.EncodedTrack` / `SampleStore` / `VideoTranscoder` /
  `VideoCodecCapability` / `VideoConversionWriter`: the video half of the pipeline plus its
  MediaStore write path. `SampleStore` (2026-09) is the disk-spill sample store both
  `VideoTranscoder` and `AudioTranscoder` build their own `EncodedTrack`s on top of — see the
  "Spill instead of memory" addendum below.
- `com.fotoxplorr.app.audio.AudioTranscoder` / `PcmTiming`: the audio half.
- `com.fotoxplorr.app.video.muxerBufferFlagsFor`: the `MediaExtractor`→`MediaCodec.BufferInfo` flag
  translation, extracted out of `ClipExporter` (which shared the exact same need and the exact same
  historical bug — `docs/TRAPS.md` #24) so both call sites use one tested implementation rather than
  two independently-written copies of the same easy mistake.

## Addendum (2026-09-07) — Phase 4: trim and speed

`VideoEditRecipe` threads a trim range and a speed factor through the same decode/encode loop
described above, landing two of Phase 4's five named tools (trim, speed) on this pipeline. Trim
seeks to the previous keyframe for correct decode state, same as `ClipExporter`, but discards
frames before the exact requested start rather than snapping the whole export to a keyframe
boundary the way `ClipExporter`'s stream-copy has to. Speed change scales presentation timestamps
for video and, at the time this addendum was written, relabelled the encoded audio track's own
declared sample rate — the same samples, called a different rate, which is the tape-speed mechanism
and changes pitch along with tempo rather than preserving it.

**This paragraph's own audio-speed description is now stale** — see the "Spill instead of memory"
addendum below for the mechanism actually in place today (the samples are resampled, not
relabelled); the pitch/tempo BEHAVIOUR this paragraph describes is unchanged, only how it is
produced changed. Text overlay, music replacement and filters remain a named follow-up — see
`VideoEditRecipe`'s own doc for why each needs new pipeline capability of its own rather than a
field on that recipe. The "not verified on a real device" caveat above applies to this addendum
exactly as much as to the rest of the pipeline; nothing about landing more capability on top of it
changes that.

## Addendum (2026-09-23) — Spill instead of memory

A 2026-09 defect audit (`docs/handoff/PHASE-0-BRIEF.md` P0-11) named six correctness gaps in this
pipeline. Fixing them changed enough of the "known limitations" sections above that this addendum
exists to say plainly what moved, rather than leaving the earlier prose to quietly disagree with
the code.

**`EncodedTrack.samples` is now a `SampleStore`, not a `List<EncodedSample>`.** Sample bytes are
appended to a temp file under `cacheDir/transcode-spill/` as they are produced; only four primitive
values per sample (byte offset, size, presentation time, flags) stay in memory, in four parallel
growable arrays. Muxing reads every sample back through ONE reusable direct `ByteBuffer` sized to
the largest sample ever appended, never one allocation per sample. The file is deleted in a
`finally`/`catch` around every track's own lifetime, including on cancellation — see `SampleStore`'s
own KDoc, and `VideoTranscoder.runTranscode`'s `finally` block, for the exact shape this requires
across two independently-encoded tracks. **`MAX_TRANSCODE_DURATION_MS` (ten minutes) is kept even
though the OOM risk that originally motivated it is gone**: "single-clip" is this whole roadmap
phase's own stated scope regardless of memory pressure, and a multi-hour transcode is still a real
amount of wall-clock time and disk churn this first pass was never meant to promise.

**A new free-space pre-flight, `VideoTranscoder.checkFreeSpace`,** reads `StatFs(cacheDir)` before
any decoding starts and refuses with a clear message when there is not room for both tracks' spill
files plus a fixed margin (`requiredSpillBytes`) — spilling to disk trades an `OutOfMemoryError` for
a `no space left on device` one if left unchecked, and neither is the clean refusal a "before you
even start" check gives.

**Speed no longer relabels the sample rate; it resamples the PCM.** The old `speedAdjustedSampleRate`
function is deleted. A new pure `resamplePcm16` (linear interpolation per channel, in
`VideoEditRecipe.kt`) actually resamples the decoded audio, so the AAC encoder is configured at the
SOURCE's own valid sample rate instead of a scaled one that, for common speeds like 1.5×, is not a
sample rate AAC can encode at all (44,100 Hz × 1.5 = 66,150 Hz) — every export with sound at a
non-1× speed most likely failed outright before this fix. Pitch still follows speed, unchanged from
the paragraph above; only the mechanism producing that effect changed.

**`AudioTranscoder.decodeToPcm` now reads the DECODER's actual output format**
(`INFO_OUTPUT_FORMAT_CHANGED`'s own `KEY_SAMPLE_RATE`/`KEY_CHANNEL_COUNT`), not the container's
declared one — HE-AAC (SBR) and parametric stereo sources can decode at a different rate or channel
count than their track's own `MediaFormat` claims, which used to feed the AAC encoder a mismatched
configuration. Float PCM output (`KEY_PCM_ENCODING` = `ENCODING_PCM_FLOAT`, which some decoders
produce) is converted to 16-bit before anything downstream touches it, since `resamplePcm16` and the
AAC encoder's own input both assume 16-bit.

**`EglCore`'s chosen `EGLConfig` now requests `EGL_RECORDABLE_ANDROID`** when a device offers a
config that supports it, falling back to the previous (non-recordable) request and logging when it
doesn't — a small number of GPU drivers use this hint to pick a config their own hardware encode
path can actually consume.

**`AudioConversionWriter` (standalone audio Save As) now has the same duration cap
`VideoTranscoder` already had**, sixty minutes rather than ten: `AudioTranscoder.decodeToPcm`'s
whole-track PCM is still held in memory (audio's own PCM is small enough per minute that spilling it
was judged unnecessary scope for this pass), and nothing previously bounded how long a standalone
source could be before that buffering became a real `OutOfMemoryError` risk of its own.

**A new pre-flight refusal for HDR sources and unsupported encoder sizes.** This app's fixed target
is SDR H.264; re-encoding an HDR source through it would silently wash out or clip highlights rather
than fail cleanly, so `VideoTranscoder` now refuses (checking the source track's own
`KEY_COLOR_TRANSFER` and, where available, its codec profile) before decoding starts. A size the
chosen AVC encoder's own `VideoCapabilities.isSizeSupported` rejects is first rounded down to the
nearest even dimension (`evenSize` — AVC needs even width/height, and a source one pixel off from
convertible only needs that rounding, not a refusal) and re-checked before being refused for real.

The "not verified on a real device" caveat at the top of this document applies to every change in
this addendum exactly as much as to the rest of the pipeline.

## Reversal

If a genuinely concurrent, streaming mux is ever needed (longer clips, less total disk churn), the
`EncodedTrack`/`SampleStore` boundary is exactly where that change would land — `VideoTranscoder`'s
own per-track encode functions would start writing to a muxer as they go rather than spilling to a
temp file and returning a store to read back later, and nothing else in this design depends on that
choice.
