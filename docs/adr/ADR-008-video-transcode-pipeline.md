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
encodes each track to completion into memory (`EncodedTrack`) and muxes both afterward — mirroring
`ClipExporter`'s own established precedent of copying "the whole video track and then the whole
audio track" rather than interleaving them, since MP4 is an indexed container and only interleaving
matters for progressive-download streaming.

The cost: a whole clip's compressed track sits in memory before any of it reaches disk. This is why
`VideoTranscoder` refuses a source longer than ten minutes (`MAX_TRANSCODE_DURATION_MS`) rather than
risking an `OutOfMemoryError` on a long recording. "Single-clip" is this whole roadmap phase's own
stated scope, not a number picked to make a demo work. A streaming mux (start once both formats are
known, write incrementally after) is the natural next step if that cap proves too tight in practice.

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
- `com.fotoxplorr.app.video.EncodedTrack` / `VideoTranscoder` / `VideoCodecCapability` /
  `VideoConversionWriter`: the video half of the pipeline plus its MediaStore write path.
- `com.fotoxplorr.app.audio.AudioTranscoder` / `PcmTiming`: the audio half.
- `com.fotoxplorr.app.video.muxerBufferFlagsFor`: the `MediaExtractor`→`MediaCodec.BufferInfo` flag
  translation, extracted out of `ClipExporter` (which shared the exact same need and the exact same
  historical bug — `docs/TRAPS.md` #24) so both call sites use one tested implementation rather than
  two independently-written copies of the same easy mistake.

## Reversal

If a genuinely concurrent, streaming mux is ever needed (longer clips, lower peak memory), the
`EncodedTrack` buffering boundary is exactly where that change would land — `VideoTranscoder`'s own
per-track encode functions would start writing to a muxer as they go rather than returning a
buffered value, and nothing else in this design depends on the buffering choice.
