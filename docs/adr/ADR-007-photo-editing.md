# ADR-007 — Photo editing: written in-house, not adopted

**Status:** accepted (interim, reversible)
**Date:** 2026-08-14
**Owner request:** *"I think some basic photo editing is required. I know Snapseed is free, is it
open-source as well? If yes, get me their features. If not, build in editing features from some
open-source repo."*

## The direct answer

**Snapseed is not open source.** It is proprietary freeware owned by Google, acquired with Nik
Software in 2012. When the desktop Nik Collection was sold to DxO in October 2017, Google
explicitly *retained* Snapseed. No source has ever been released. It is usable as a **feature
spec** and nothing more.

For reference, its toolset is: Tune Image, Details, Curves, White Balance, Crop, Rotate,
Perspective, Expand, Selective, Brush, Healing, HDR Scape, Glamour Glow, Tonal Contrast, Drama,
Vintage, Grainy Film, Retrolux, Grunge, Black & White, Noir, Portrait, Head Pose, Lens Blur,
Vignette, Double Exposure, Text, Frames. That is many years of work by a specialist team; it is a
direction, not a scope.

## Why not adopt a library

Two hard constraints in this repo disqualify most of the field before taste enters into it.

**1. The offline flavour's gates.** `verifyOfflineManifest` fails the build if any network
permission reaches the offline merged manifest, and `verifyOfflineRuntimeClasspath` fails it if a
networking library reaches the offline runtime classpath. The allowlist holds exactly one entry,
by exact coordinate. There is no escape hatch for the classpath gate.

**2. Licence.** Foto Xplorr is not a GPL application. A GPL-3.0 dependency would make it one.

| Candidate | Licence | Verdict |
|---|---|---|
| **uCrop** (Yalantis) | Apache-2.0 | **Disqualified.** Declares `com.squareup.okhttp3:okhttp` and uses it in `BitmapLoadTask`. Hard-fails the offline classpath gate. |
| **Fossify Gallery** / Simple-Gallery | GPL-3.0, no "or later" | **Disqualified.** Cannot take a line of it. |
| **ImageToolbox** (T8RIN) | Apache-2.0 | Excellent, but publishes **no Maven artifacts** — source quarry only, and it is a whole application. |
| **android-gpuimage** | Apache-2.0 | Viable for filters, but it is a GL pipeline we do not need for v1, and this app has already been bitten once by device-specific GL driver behaviour. |
| **vanniktech/android-image-cropper** | Apache-2.0 | Genuinely viable for crop. Depends on AppCompat, which this app does **not** have — its `AppCompatActivity` would crash. Usable only as `CropImageView` inside an `AndroidView`. |
| **AOSP Gallery2 FilterShow** | Apache-2.0 | Not a library, but its representation-stack *design* is the right one and is what `EditRecipe` is modelled on. |

## Decision

Write a small editor in-house, using `android.graphics` only.

Crop, rotate, flip and brightness/contrast/saturation/warmth need no GL at all: a `Matrix` and a
`ColorMatrixColorFilter` on a hardware-accelerated `Canvas` cover every one of them. That adds
**zero dependencies**, so both offline gates stay trivially satisfied and no licence question
arises.

### Shape

- `EditRecipe` — the edit as **data**, never pixels. Non-destructive by construction.
- `CropGeometry` (in `EditRecipe.kt`) — normalised crop rects and aspect presets, so a crop
  survives the preview→export resolution change.
- `EditRenderer` — recipe → bitmap. Fixed order: orient, crop, colour.
- `EditedCopyWriter` — writes a **new** file. There is no code path in it that opens the original
  for writing, and that is the point: a photo library is often the only copy of an irreplaceable
  image.
- `EditorScreen` — the UI.

### Consequences and honest limits

- **Save currently exports at preview resolution**, not full resolution. Full-resolution export
  needs a second decode and a memory budget for it, and is the next step. This is a real
  limitation, not a rounding error, and it is why this ADR is marked interim.
- **The crop tool offers presets only.** Dragging the crop box directly is not implemented.
- `EditRecipe.toColorMatrix()` is **not unit-tested**: `android.graphics.ColorMatrix` throws
  "not mocked" under JVM unit tests, and this module has neither Robolectric nor an instrumented
  suite. Its coefficients are a look and belong on the device checklist.
- `Intent.ACTION_EDIT` survives as "Open with", so a user who prefers a real editor still has one.
  Note that on the offline flavour this hands the file to an arbitrary installed app — which is
  the user's explicit choice, made per use, rather than something the app does on their behalf.

### Reversal

If the editor grows past what the 2D canvas can serve — curves, healing, selective masks — the
next step is `android-gpuimage` (Apache-2.0, no network) or the app's existing hand-written GLES
pipeline, not a GPL gallery. `EditRecipe` is deliberately renderer-agnostic so that swap does not
touch the UI or the writer.
