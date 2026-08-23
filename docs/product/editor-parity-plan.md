# Editor parity plan — photo and video, against the 2026 field

**Prepared 23 Aug 2026** from a fresh competitive survey (Google Photos' 2025 editor rebuild +
Reimagine, Samsung One UI 7/8 Generative Edit and Studio, Snapseed 4.x, Lightroom Mobile,
Photoshop Android, CapCut/VN/InShot/YouCut, LumaFusion Android 2.5, Fossify Gallery, Aves,
Open Video Editor). Two questions, per the owner: what reaches parity, and which easy wins put
these editors ahead. Doctrine frames everything: local-first, no backend, no telemetry, no
subscription — which rules out cloud generative parity and rules IN every win that comes from
*not* being freemium adware.

## Where Foto Xplorr's photo editor stands

Already real: non-destructive recipe editor (14 adjustments), tone curve, auto-fix measured
from the image, straighten with horizon detection, crop/rotate/flip, subject lift, full-res
save-as-copy. That is already ahead of every FOSS gallery: Fossify stops at crop/filters,
Aves barely edits at all. The benchmark that matters is **Snapseed 4.x** — free, offline,
no generative cloud: the field's proof that editing depth without a backend is a product.

### Photo parity gaps (P1 = parity with mainstream, in order)

1. **Healing / object removal** — one-tap erase is now consumer table stakes (Google, Samsung,
   Snapseed, Lightroom all ship it). On-device is proven territory: LaMa/MI-GAN-class
   inpainting runs via TFLite/ONNX on ordinary flagships. Ship as a downloadable model pack
   (the app already has the pattern via MediaPipe embeddings), routed through ASOM when it
   ships. This single feature closes the loudest FOSS gap.
2. **Selective/local edits** — brush + linear/radial masks over the existing adjustment set;
   the recipe model extends naturally (a mask per adjustment layer). MediaPipe subject/sky
   segmentation (already a dependency for lift) gives one-tap AI masks.
3. **HSL / color mix** — per-hue hue/sat/lum; with curves already done this is the last
   photographer-grade tonal tool missing.
4. **Perspective correction** — vertical/horizontal keystone beside straighten.
5. **Filters/looks as data + import** — the adjustment engine can express looks; add a preset
   format and **.cube LUT import**, which even Lightroom Mobile lacks — parity item that is
   also a differentiator.
6. **RAW development** — decode via platform `ImageDecoder`/libraw-free path where possible,
   embedded-JPEG fast path elsewhere; full demosaic control is a later phase.

### Photo easy wins (cheap, doctrine-aligned, ahead-of-field)

- **Batch apply a recipe** to a selection (the recipe is data; the gallery has multi-select) —
  Snapseed only regained batch in 4.0, galleries mostly lack it.
- **Recipe history sidecars**: store the recipe beside the copy so "edit again" reopens the
  stack — Lightroom-style non-destructive continuity, no catalog lock-in, plain files.
- **Copy edits / paste edits** between photos (one menu item once recipes serialize).
- **On-device super-resolution / denoise pack** (ESRGAN-lite class) — "Enhance" without cloud.
- The privacy line itself: metadata-stripping share and seal already exist — say it out loud
  in the listing copy; no mainstream editor can.

## Video: v1 shipped with this commit (ADR-008)

Trim, rotate, mirror, speed 0.25–4×, mute, centred aspect crop, live ExoPlayer preview,
Transformer export, save-as-copy beside the original, no-op saves refused. The viewer's
playback engine moved to ExoPlayer with it.

### Video parity gaps (gallery tier first, then editor tier)

1. **Filters/color on video** — the photo editor's adjustment vocabulary compiled to GL
   (Media3 `RgbMatrix`/custom shaders); one look system across both editors.
2. **Music / background audio** — Media3 `Composition` background audio sequence; local files
   only, waveform trim.
3. **Multi-clip joining + transitions** — `EditedMediaItemSequence` supports it today;
   cross-fades via the compositor; the timeline UI is the real work. With this, Foto Xplorr
   becomes the most capable FOSS editor on Android (nothing FOSS has a timeline at all).
4. **Auto-captions, fully on-device** — whisper-class model pack via ASOM; no FOSS editor and
   no gallery-tier editor has offline captions. Category-defining if landed.
5. **Stabilization** — no Media3 support; genuine research item, keep promising nothing.
6. **Lossless quick trim** — adopt Media3's MP4 edit-list trim when it leaves experimental:
   instant, re-encode-free trims beat every freemium editor's export wait.

### Video easy wins (the freemium field's self-inflicted wounds)

Free 4K export (CapCut gates it), no watermark ever (InShot sells its removal; we ALREADY have
an optional user-chosen watermark as a Pro *feature* — the inversion is the story), no ads, no
account, fully offline (CapCut is cloud-entangled ByteDance property; the privacy one-liner
"your video never leaves the device" is unanswerable), HDR preserved or explicitly tone-mapped
(freemium editors mangle HDR; Media3 gives correctness nearly free), and share-sheet "Trim
with Foto Xplorr" so the editor is reachable from any app.

## Order of work

Photo: healing pack → selective edits → HSL → LUT import → batch/copy-paste recipes.
Video: filters-on-video → music → multi-clip+transitions → captions. The two tracks share the
look system (item 1 of each) — build it once. Everything lands behind the existing screenshot
tests plus the ADR-008 device gate, and model-pack features route through ASOM once its
contract exists (`AsomLink` seam, Fylz repo).
