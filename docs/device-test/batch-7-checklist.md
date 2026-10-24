# Batch 7 device test checklist

Every device-only claim from the panorama utility (owner item **#24**, **Experimental > Make > Stitch a
panorama**), in the same shape as `batch-6-checklist.md`. See `docs/handoff/MASTER-PROGRESS.md` (Batch 7) and
TRAPS #89 to #98. This sandbox has no device and **no real photograph**: every figure so far is from procedural
scenes (1/f noise, hard edges, gradients, with gain, bias, noise, vignetting, roll, shear, jitter, a moving blob
and a parallax layer), Robolectric proved composition and file effects, and Roborazzi runs in record mode, so no
screenshot test proves how anything looks. **None of these is claimed as verified.**

Suggested devices: a current phone on Android 15 or 16, one on Android 13 or 14, a **low-end phone with 3 to 4 GB**
(for time and memory), and, if you can, one on Android 8 to 10 (minSdk 26; the writer's folder and `decodeUpright`
are unverified there). Check **both flavours** where a line says so. Prepare real handheld sweeps (see section 1),
a photo with no focal-length tag (a screenshot, or a tag-stripped copy), a 48 MP photo, a HEIC photo, a
portrait-held sweep, a photo in a protected folder, a photo marked sensitive, and photos with GPS.

## 0. Known before you start

- [ ] Android 8.0 / 8.1: `decodeUpright` returns null on API 26 and 27 (PRODUCT-AND-ARCHITECTURE, Known limits),
      so the panorama cannot read photos there. Treat it as unverified on those releases.
- [ ] Android 8 to 9: the writer inserts without `RELATIVE_PATH`, so the file lands in the default Pictures
      folder, not `Pictures/Fotoz/Panoramas` (unverified).
- [ ] Every time and memory figure in sections 2 and 3 is a host-JVM figure or arithmetic; a phone is guessed at
      2 to 4 times slower. Replace the guesses with what you see.

## 1. The go / no-go: three real handheld sweeps

Compare each with the phone's own panorama mode, same scene, same spot. For each pair of neighbours record the
job's `Offset.score`, `overlapFraction` and `confident` (a debug log or a temporary print), whether the join
visibly matches, and the exposure `gains`, `clamped` and `worstMismatch`.

- [ ] **Standing still, turning the phone**, 5 to 8 photos with about a third of overlap, a landscape or a street.
      Look at every seam at 100 percent zoom: ghosting, a brightness step, straight lines that bend, a hairline.
      It must look no worse than the phone's own panorama mode, or the utility does not ship.
- [ ] **A walking person** crossing one overlap. Expected: either a ghost there, or "Photo N doesn't overlap
      enough." (the synthetic limit: a figure over about a fifth of a 25 percent overlap refuses about 4 pairs in 18).
      Note which, and whether a refusal was a join that would have been right.
- [ ] **Indoors**, low light, auto exposure on. The synthetic limit is that a scalar gain cannot undo an exposure
      offset (10 to 26 percent of a step left); look for visible steps at the seams.
- [ ] Real pairs should score well above the 0.55 trust line (synthetic true joins scored 0.61 to 0.99). If real
      true joins score 0.4 to 0.6, the line or a block-median score needs retuning.
- [ ] **A flat-sky sweep** (blue sky, a plain wall): it must refuse with a sentence, never join wrongly.
- [ ] **A rotated phone** (portrait-held, then landscape-held, in one selection): refused with the mixed sentence.
- [ ] **Roll**: hold the phone with about 1 degree of roll; synthetic error is about 5 to 8 px at working size and
      about +-2.6 px of ghosting over a 300 px overlap. Is it acceptable?
- [ ] **Near objects** (a post or a person 2 m away against a far scene): expect ghosting; the app does not warn.

## 2. Photos, EXIF and the lens

- [ ] Focal length on real EXIF from the main, ultra-wide and tele cameras, a digitally zoomed photo (does its
      tag follow the zoom?) and one with no `FocalLengthIn35mmFilm`: the last must save with "Some photos did not
      say their lens, so straight lines may look curved." A tag wrong by 10 percent is not noticed by the app and
      costs 13 to 15 grey levels of seam error on synthetic scenes; check straight lines (a horizon, a building).
- [ ] A selection mixing the 0.5x and 1x cameras is refused as mixed, not stitched.
- [ ] `decodeUpright` through `ExperimentEnv.decode` on 12 MP and 48 MP photos, HEIC, wide-gamut and HDR: is the
      size at the requested canvas scale honoured, how long does each take, do the colours survive
      `Bitmap.getPixels`? Portrait-oriented EXIF comes out upright.
- [ ] The saved file has no GPS, make, model or XMP (check with an EXIF viewer), and opens in this app's viewer at
      8192 px wide and in the system gallery and Google Photos. The date shown in the gallery is the first photo's.

## 3. Time and memory

- [ ] Time per pair on the 1200 px working size (host: 150 to 200 ms; guess for a phone: 0.5 to 1.5 s) and a full
      12-photo sweep including the grey decode; `ExposureGain.measureAll` for 12 photos at 65 percent overlap
      (up to about 21 pairs).
- [ ] A full 8-photo job on a mid-range phone: small reads, 7 alignments, compose (host 1 to 2 s at 24 MP),
      mask and crop (host 0.04 to 0.45 s), JPEG compress, the staged copy into MediaStore.
- [ ] **A 12 x 12 MP stress run** on the low-end phone: peak memory with a 24 MP Bitmap canvas, two decoded
      photos of about 45 MB each as int arrays, four worker scratch pairs, the crop mask (24 MB) and, at publish,
      the cropped copy (about two canvases of native memory; arithmetic gives about 145 MB heap plus 96 MB
      native, unmeasured). Does the OOM ladder fire and restart on a fresh canvas, and does it say "Saved smaller,
      to fit the memory."? Is an out-of-memory in `Bitmap.createBitmap` reported as an error or a native kill?
- [ ] `Bitmap.getPixels` / `setPixels` from four threads on disjoint rectangles under the canvas lock: any stall?
- [ ] A wide sweep saves at 8192 px wide (the editor's edge ceiling), a few megapixels tall, not at 24 MP; the
      "About W by H pixels." line after Preview matches the saved file within about 3 percent.

## 4. The screen

- [ ] Experimental > Make > "Stitch a panorama". The picker grid on a library of thousands (scroll cost,
      selection badges, the 12-photo cap dimming the rest); Next waits for two photos.
- [ ] The arranging strip at real phone widths (about two photos visible between the 60 dp side insets): can a
      finger scroll it from near an edge, and does an edge drag still open a shell room?
- [ ] TalkBack reads "Photo 2, name", "Move photo 2 left", "Remove photo 2" and the picker cells; the selected
      cell announces "selected" but not its number (known). A large font scale with three 48 dp buttons under
      each card. Move left / right and Remove are the only reorder path; check they are enough.
- [ ] Preview on a real sweep: time to the picture, the progress bar, whether the preview is a fair guide to the
      saved seams (alignment is paid again on Save), the wording and the Remove button when a photo does not join.
- [ ] Back steps from arranging to choosing before the experiment closes. Rotate and background mid-selection,
      then kill the process: the step, photos and order return, the preview does not. Rotate during a save: it
      continues. Leave Experimental during a save: it stops, with "Leaving this screen stops the save."
- [ ] After Save, "Open in the gallery" appears once the gallery lists the row; before that, only "It will show
      in the gallery soon." How long does the gallery take?
- [ ] The activity shade and the notification show a moving bar and a Cancel; the duplicate
      "Joining a panorama finished." notice next to the screen's "Panorama saved." is acceptable or not.

## 5. Protection and cancel

- [ ] A photo in a protected folder, or flagged sensitive with Blur sensitive on, is not offered, and one that
      becomes protected while a Preview or Save runs ends with "A protected photo cannot be joined." and
      **nothing** in the gallery. Lock the folder during the last draw and during the copy.
- [ ] **Cancel mid-export** in each phase: during the small reads, during alignment, during the draw, during the
      JPEG compress, and in the middle of the MediaStore copy. No row in the gallery, no file in
      `Pictures/Fotoz/Panoramas`, no leftover in `cache/panorama-staging`, and it stops within about a second
      (an in-flight decode finishes first).
- [ ] Kill the process mid-save: no visible row (a pending row is hidden), and the next panorama deletes any
      staging file older than an hour.
- [ ] MediaStore: collision naming (`-panorama`, `-panorama-2`), a scanner rewriting `DATE_TAKEN`, the new album
      showing in other galleries, and Android 8 to 10 behaviour.
- [ ] The Offline build has no network permission and the Connect build behaves the same (the panorama has no
      network code; `verifyOfflineSourceReferences` passed).

## 6. Seams, blend and crop at 100 percent

- [ ] The 32-column cosine feather on real skies and walls; what 80 percent overlap (many layers, blended in
      sequence) looks like on a real sweep.
- [ ] A seam where the camera auto-exposed differently (sky against shadow): the feather only fades, so any
      remaining band means the gain did not equalise the frames. Also check white balance (not corrected) and
      vignetting at the seam (not compensated).
- [ ] The crop leaves no black or transparent edge pixel (zoom into all four corners). A sweep with 20 px of
      vertical wander loses about that many rows top and bottom; the cylinder's curved edges cost about 50 to 90
      of 490 rows (about 18 percent at the edge photos of a 78 degree sweep).
- [ ] The 0.25 drift warning against real 6 to 12 frame sweeps: how much vertical spread do they actually show,
      and does `Layout.isolated` name the right photo when an unrelated shot is inserted?
