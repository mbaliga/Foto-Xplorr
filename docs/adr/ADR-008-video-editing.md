# ADR-008 — Video editing on Media3 Transformer

**Status: accepted, v1 implemented (Aug 2026).** Owner direction: Foto Xplorr owns image AND
video editing for the constellation (Fylz opens everything else; see Fyl-Manager's
`docs/product/constellation-contract.md`).

## Decision

Video editing is built on **androidx Media3**: ExoPlayer for playback and preview, Transformer
plus the Effects pipeline for export. One pipeline, so what plays is what saves. The
alternatives were FFmpeg-based stacks (`ffmpeg-kit` is archived/unmaintained upstream, LGPL
builds complicate the licence story, and it drags ~20MB of native code into an APK the release
pipeline just fought to shrink) and writing MediaCodec plumbing by hand (ADR-007's "written
rather than adopted" logic does NOT transfer: the photo editor is arithmetic over pixels we
fully understand; a transcode engine is codec negotiation, muxing, A/V sync and per-OEM bug
lore — exactly what AndroidX exists to absorb). Media3 is AndroidX, Apache-2.0, no native code
of our own, no network anything.

## Shape

- `VideoEditPlan` — the edit as data, mirroring `EditRecipe`'s philosophy and conventions
  (`quarterTurns`, `flipHorizontal`): trim, rotate, mirror, speed (0.25×–4×), mute, centred
  aspect crop (1:1, 16:9, 9:16, 4:3). Pure, invariant-checked, unit-tested, including the
  NDC crop geometry Transformer's `Crop` effect consumes and the rotation-before-crop rule.
- `VideoExporter` — Transformer wiring. Writes to app cache, publishes a COMPLETE export to
  MediaStore beside the original (`IS_PENDING` until every byte lands), deletes the cache
  file. Cancel/fail leaves nothing user-visible. Save is always save-a-copy: like
  `EditedCopyWriter`, no code path opens the source for writing.
- `VideoEditorScreen` — trim handles (RangeSlider), rotate/mirror/mute chips, speed chips,
  aspect chips, live preview (trim/speed/mute reconfigure the player; rotation/mirror
  transform the surface; the crop previews as a frame, honestly, since the preview pipeline
  masks rather than crops). Save disabled for an identity plan: a no-op edit must not write a
  second copy.
- The viewer's `VideoPlayer` moved from `android.widget.VideoView` to ExoPlayer — wider
  container/codec coverage (the "every format we can" direction) and the same engine the
  editor previews with.

## Deliberately not in v1

Multi-clip timeline and transitions (Media3 `Composition` supports sequencing; the UI is the
real work), music/audio tracks, keyframes, stabilization (no Media3 support — needs its own
research), auto-captions (feasible later fully on-device with a whisper-class model pack —
would be a category first for FOSS Android; routed through ASOM when that ships), LUT color
filters (the Effects pipeline takes them; the photo editor's look system should drive both),
and lossless edit-list trimming (`experimentalSetMp4EditListTrimEnabled` — promising, still
experimental; adopt when it stabilizes). Sequencing for these lives in
`docs/product/editor-parity-plan.md`.

## Device gate

Nothing in this ADR was run on hardware in the authoring session (no SDK available there).
Before the feature is called done: export matrix on real devices (H.264/H.265 sources, HDR
HLG/HDR10 sources — Transformer tone-maps or preserves per device support and the result must
be LOOKED AT), rotation+crop composition, cancel mid-export, and a Files-visible check that
`IS_PENDING` behaves on API 26–36.
