# ADR-007 — Photo editing: written in-house, not adopted

**Status:** accepted (interim, reversible)
**Date:** 2026-08-14
**Updated:** 2026-09-16 — full-resolution export, drag crop, per-channel curves, HSL, presets,
undo/redo, persisted recipes, spot heal and export controls landed; see "Where this stands now"
below. The decision and the licence/dependency analysis are unchanged from the original text.
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

- `EditRecipe` — the edit as **data**, never pixels. Non-destructive by construction. Holds
  `adjustments` (colour/tone/HSL/detail), `crop`, rotation/flip/straighten, and `heals` (spot
  healing, see below).
- `CropRect` / `AspectPreset` / `CropInteraction.kt` (in `EditRecipe.kt` and `CropInteraction.kt`)
  — normalised crop rects, aspect presets, and the pure per-handle drag maths an interactive crop
  box needs (`CropRect.dragged`), so a crop survives the preview→export resolution change and a
  drag never has to know a bitmap's actual pixel dimensions.
- `Adjustments` — the colour/tone/HSL/detail stack, compiled to per-channel lookup tables plus a
  few genuinely per-pixel passes (`applyColour`, `applyHsl`, `applyVignette`,
  `applyUnsharpMask`/`applyDenoise`). `HslAdjustments` (`Hsl.kt`) adds eight hue-band
  hue/saturation/luminance controls on top of the original tone/colour fields.
- `SpotHeal` — classic best-patch spot healing (ported from a prior branch's PR #9, unit-tested
  there and again here): scans a ring of candidate donors, scores them against the blemish's own
  clean context band, clones the best one in with a feathered edge. Deterministic, pure `IntArray`
  arithmetic, no model and no network.
- `EditRenderer` — recipe → bitmap. Fixed order: orient/flip, straighten (auto-cropping the
  exposed corners), the user's own crop, colour (LUTs, HSL, vignette, denoise, sharpen/clarity),
  heals last (in the final image's own coordinates).
- `EditedCopyWriter` — writes a **new** file by default (`save`), in the source's own format or an
  explicit `OutputFormat` (JPEG/PNG/WebP/HEIC) and quality the user picked. `saveTo` writes to an
  arbitrary SAF destination picked via `ActivityResultContracts.CreateDocument`, hosted inside
  `EditorScreen`. `overwrite` is the one place this class *does* open the original for writing,
  gated on the same per-file consent dance `FotoXplorrActivity` already runs for rename and
  metadata, and only for a source already in one of the three `Bitmap`-encodable formats — see
  `overwriteFormatFor`. All three preserve the source's own EXIF/XMP camera facts and GPS (a
  documented tag whitelist, `EXIF_PRESERVED_TAGS` in `ExifOrientation.kt`) and reset orientation to
  upright, since the pixels themselves are already re-oriented on decode.
- `RecipeStore` — autosaves the in-progress recipe per photo (JSON, keyed by `MediaId` +
  `dateModifiedSeconds`, under `filesDir/edits/`), offered back as "Continue editing / Start over"
  the next time the same photo (same bytes) is opened here, with an explicit "Revert to original".
- `PresetStore` / `BuiltInPreset` — named looks (partial recipes: `Adjustments` only, never crop or
  rotation), built-in (Vivid/Warm/Cool/Fade/Mono/Portrait) and user-saved (JSON under
  `filesDir/presets/`, one file per preset).
- `EditHistory` — an in-session undo/redo stack over `EditRecipe` snapshots, committed on a
  settle-debounce so a slider drag produces one entry, not one per frame.
- `EditorScreen` — the UI, split into a Simple tier (fewer, coarser sliders, plus Crop/Rotate/
  Presets) and an on-demand Pro tier (the rest, plus Curves, HSL, Detail's full slider set, and
  Heal) — see `EditorTier`. A press-and-hold on the preview shows the untouched original for
  comparison.

### Where this stands now

- **Save exports at full resolution**, capped by a memory-derived ceiling
  (`maxExportEdge`, driven by `ActivityManager.memoryClass`) rather than the fixed 8192px ceiling
  alone — a phone with a small heap gets a smaller safe export instead of being killed mid-save.
  The export controls also let a user choose a smaller long edge explicitly (Original/4096/2048/
  1024) and a JPEG/WebP/HEIC quality (50–100).
- **The crop tool is a real drag box**: 8 handles, drag-to-move, aspect lock (the same presets as
  before, now also constraining the drag), a rule-of-thirds grid while dragging. Straighten still
  applies before the crop, unchanged.
- **Curves are draggable**, per RGB/R/G/B channel, on top of the original named-shape presets —
  `CurvesEditor` reads and writes `ToneCurve.withPoint`/`withoutPointAt`, the same monotone-Hermite
  curve `ToneCurveTest` already covered before any UI could set the per-channel fields at all.
- **HSL, presets, undo/redo, persisted recipes and spot healing** are implemented as described
  above under Shape.
- **Denoise** (`Adjustments.denoise`) is implemented as a simple blend between the original and a
  small-radius box blur of itself — a stated simplification, not a true bilateral or median
  filter; see `applyDenoise`'s own doc for the reasoning and the honest cost (some edge softness
  at the top of the slider, in exchange for a pass cheap enough to run at export resolution).
- `EditRecipe.toColorMatrix()` no longer exists — the colour pipeline is `Adjustments`' own LUTs
  and per-pixel passes, all pure `IntArray` functions, all unit-tested on the JVM (no
  `android.graphics.ColorMatrix` anywhere in this module).
- `Intent.ACTION_EDIT` survives as "Open with", so a user who prefers a real editor still has one.
  Note that on the offline flavour this hands the file to an arbitrary installed app — which is
  the user's explicit choice, made per use, rather than something the app does on their behalf.
- HEIC export uses `androidx.heifwriter` (API 28+, and only when a real HEVC encoder is present —
  see `isHeicExportSupported`), the one new dependency this ADR's original decision did not
  anticipate. It needed `tools:overrideLibrary` in the manifest (the library's own minSdk is 28;
  this app's stays 26) — the same escape hatch already used for `dev.aarso:hyle`, and gated at
  every call site so no code path below API 28 can reach it regardless.

### Reversal

Curves, healing and HSL all landed on plain `IntArray` arithmetic — no GL needed after all. If the
editor grows past what that can serve at export resolution in reasonable time — real-time
selective masks, generative inpainting beyond what `SpotHeal`'s best-patch search can do — the
next step is `android-gpuimage` (Apache-2.0, no network) or the app's existing hand-written GLES
pipeline, not a GPL gallery. `EditRecipe` is deliberately renderer-agnostic so that swap does not
touch the UI or the writer.
