# Batch 8 device test checklist

Every device-only claim from batch 8, grouped per tool, in the shape of `batch-7-checklist.md`: the colour-vision
viewer and check, the editor hub, the palette builder, animated pictures (GIF, APNG, MP4), the reel maker, the mood
board and Chroma ID. See `docs/handoff/MASTER-PROGRESS.md` (Batch 8), `docs/adr/ADR-017-camera-and-soundtrack.md` and
TRAPS #99 to #125. **Nothing in this batch has run on a device.** Every number below that is not marked "measured" is a
guess. Unit tests and Robolectric proved wiring, arithmetic on synthetic data and file effects; Roborazzi runs in record
mode, so no screenshot test proves how anything looks. **None of the lines below is claimed as verified.**

Suggested devices: a current phone on Android 15 or 16; one on Android 13 or 14; a **low-end phone with 3 to 4 GB** (time,
memory, the first rung of every size ladder to fail); a **tablet** (the editor hub and the board); one on **Android 8 to 10**
(minSdk 26; folders, pending rows and `decodeUpright` are unverified there); and a phone with **no camera or no flash**
if you can borrow one. Check **both flavours** where a line says so. Prepare: a real library with phone photos of faces,
skies, foliage and skin; a 12 MP and a 48 MP photo; a HEIC photo; a portrait phone video (1080p), a 4K clip, an HDR clip,
a clip with and without sound, and a clip with a recorded place; GIFs and APNGs from the web, other apps and video
converters; animated WebP from `gif2webp` and from Chrome; a photo in a locked folder; a photo marked sensitive; photos
with GPS; MP3, M4A, FLAC, OGG and WAV files and a text file renamed `.mp3`; a printed colour chart or a paint-colour card
and a plain white card; a tungsten bulb, daylight, an LED lamp.

## 0. Known before you start

- [ ] **Android 8.0 / 8.1:** `decodeUpright` returns null on API 26 and 27 (PRODUCT-AND-ARCHITECTURE, Known limits), so
      every tool that reads a library photo through `env.decode` (reel, GIF/video from photos, mood board, palette
      from photos, panorama, wall) cannot read photos there. Treat them as unverified on those releases. The colour-vision
      viewer reads through `decodeUpright` too.
- [ ] **Android 8 to 9 writers:** the MediaStore writers (reel, animations, board, panorama) insert without `RELATIVE_PATH`
      and without `IS_PENDING`, so the file lands in the default folder and the row is visible while the bytes are copied;
      a kill mid-copy can leave a truncated file nothing removes (unverified).
- [ ] Every time and memory figure here is a host-JVM figure or arithmetic; a phone is guessed at 3 to 6 times slower for
      the integer loops (GIF/APNG encode, recolour). Replace the guesses with what you see.
- [ ] Both flavours: the Experimental page shows six new cards (Make a reel, Make a GIF or video, Mood board, Palette
      builder under Make; Chroma ID and Colour vision check under Explore) and the two new privacy lines (camera, sound).
      The Offline flavour merged manifest has `CAMERA` and the three optional camera features, no `INTERNET`, no audio
      permission.

## 1. Colour vision (viewer filter, tap to name, patterns, corrected copy, the check)

### 1a. Is it right?
- [ ] **Against a known colour chart on screen.** Show a standard test image (an Ishihara-free colour chart, a rainbow,
      Macbeth-style patches, a red/green/blue ramp) in the viewer and compare `Hard to see red / green / blue` and
      `Show how it looks` with a trusted reference render of the same image (DaltonLens or Coblis). The matrices are
      Machado 2009 in linear light; the authors' own page was never compared directly.
- [ ] Compare `Help me tell colours apart` on skin tones, foliage and sunsets at strength 100 percent for red and green:
      acceptable, or garish? The 0.7 / 0.7 gains were checked numerically, not by eye.
- [ ] The photo is first drawn through the 4x5 colour-matrix preview and replaced by the exact per-pixel picture (about
      0.3 to 1 s on a 2048 px decode, unmeasured). Look for a visible shift at the swap on dark or saturated photos (the
      preview is off by up to about 30 steps even on muted colours, more for the daltonize filter). If it jars, hold the
      original until the exact picture is ready.
- [ ] The exact picture is capped at 2048 px / 3 MP: judge how soft a pinch-zoom looks with a filter on; raise
      `VIEW_LIMITS` if memory allows.
- [ ] Drag the strength slider and press the stepper quickly: the picture settles on the last step (120 ms debounce, the
      stale picture of the same type kept meanwhile) without flashing back to the original.

### 1b. Tap to name
- [ ] Tap to name colours at several zoom levels and with the photo turned a quarter turn: the name is of the colour
      under the finger, the swatch matches, the label never sits under the status bar, nav bar or an edge zone, and a tap
      on the black margins brings the chrome up.
- [ ] Judge the names on real colours, especially blues and purples and the low-saturation `Seems ...` cases; edit
      `VisionCopy.COLOUR_TABLE` (about 162 hand-written names) where a name feels wrong.

### 1c. Patterns (hatching)
- [ ] The six hatch strokes at the 8 px period on a real phone at 3x thumbnail size and over zoom: diagonal vs backward
      diagonal, horizontal vs vertical must be tellable; adjust period or stroke if not.
- [ ] The coarse region edges (the colour index is 256 px) look acceptable; black or white ink reads on light and dark
      regions; patterns do not drift against the photo under pinch and rotate (they share the zoom layer).

### 1d. Swiping, rotation, reopening
- [ ] Swipe the filmstrip with a filter, names and patterns on: the choice stays, the previous photo's colours never flash
      under the new photo's name, memory does not grow. Rotate the phone: the choice survives. Close and reopen the viewer:
      it is back to Off (by design).

### 1e. Corrected copy
- [ ] Save a corrected copy of a 12 MP+ photo: the shade shows a job with progress and Cancel; a cancel at several moments
      leaves no extra photo; the new `-edited.jpg` appears beside the original (the notice says it will show soon; check
      whether it shows without a rescan); the original is untouched; open the copy and compare its colours with the
      on-screen view. Repeat on a nearly full phone. **The copy carries the original's location** (editor writer): confirm
      or change (TRAPS #103).
- [ ] The chip over the photo (bottom end, above the filmstrip), the notice and the name label at large font scale and in
      TalkBack: each at least 48 dp and read as a live region; they must not cover the zoom minimap or the transparency and
      select-text chips.
- [ ] The group is long: it scrolls inside the right room on a short phone, and the slider's drag does not open a room from
      the right edge. A video shows one sentence and no controls.

### 1f. The check (seven dot pictures)
- [ ] On a real screen at arm's length a normal eye reads each number on all three axes; the control is easy; with night
      mode or a blue-light filter on, the blue-yellow pictures degrade (expected; the screen warns). The grey `#7F7F7F`
      square behind the dots is untested. Judge the dot plates, the control plate and the plate wording against
      someone who knows they are colour-blind, if you can. No clinical claim is made or possible.

## 2. Editor hub (hub-and-ring)

- [ ] **On a phone (360 dp):** the five *Used most* tools plus five ring groups fit with 48 dp targets (the centre and a group
      page are capped at the controls height and scroll if they do not fit);
      the centre with two rows of pills + ring + auto-fix chips on the home page, and header + control + compact ring
      with a tool open; the preview keeps close to half of the screen. Curves is the tightest (header 48 + graph +
      channel tabs + 48 ring). `HUB_CHROME_DP = 104` and `MIN_TOOL_CAP_DP = 120` are estimates.
- [ ] The arc: do the five ring buttons read as a smile cupping the centre, is 14 dp of depth enough or too much, does
      the middle button sit where a one-handed thumb rests?
- [ ] **On a tablet and in landscape:** the grouped list shows (no ring), every tool present, a deep tool is one scroll
      and one tap; width 600 dp and up, under 300 dp, height under 560 dp and landscape all switch to the list. Rotate mid-tool: the switch is
      clean, and a group page restored from portrait does not leave a hidden Back step.
- [ ] **Large fonts:** at 130 percent the labels `Colour`, `Detail`, `Effects` must not ellipsise in a fifth of 328 dp;
      above 130 percent the list shows. At 200 percent the largest group (Light, 4 front + 3 deeper) fits with More
      expanded.
- [ ] TalkBack: centre tools first, then the ring; each ring button says `Light, 3 more inside, tab, selected / not
      selected`; the region announces its page title when it changes; focus after tapping a tool is not lost; `reset`
      next to a slider reads `Reset Exposure`.
- [ ] Selected group is recognisable without colour (filled pill + bold + underline; a bar under the icon in the compact
      ring); the same for the bold aspect, HSL band and preset chips. Check in greyscale or with the colour-vision filter
      from section 1.
- [ ] Touch: every pill, Back, More, chip, TextButton and the slider row (the `reset` row keeps a constant 48 dp height) is 48
      dp tall, and so are the Curves channel tabs and shortcut chips and the Save-current-preset button (the panels' chips
      and buttons are by modifier only). A thumb can hit the compact ring while a slider is mid-drag without nudging the slider.
- [ ] System Back steps: tool, More, group page, leave the editor. The visible Back button on a group page leaves the page in
      one tap even with More open. With the Save sheet or the
      Continue-editing dialog open Back is still theirs. The gesture-navigation back swipe from the screen edge near the
      ring and near the crop handles.
- [ ] Process death: kill the app with a tool open on a group page; expect the same tool and page, the same use counts, a
      recipe offered back through Continue editing (the recipe and the crop aspect and heal radius are not in the hub's
      saved state).
- [ ] Feel of the learning: does *one use = the first change in an opening* and *25 percent plus half a use* give a calm
      centre? Is Rotate 90 and Flip needing open-then-tap (two taps instead of one) acceptable? Same for Reset all and
      Revert to original.
- [ ] Hardware keyboard / D-pad focus order and Escape were not looked at.

## 3. Palette builder

- [ ] Touch feel and spacing at 360 dp and at a large font scale (stepper rows are four 56 dp buttons, buttons wrap in a
      `FlowRow`, the `+15` text may clip at big font). Swatch tiles 56 dp, selected tick and padlock discs, hex under the
      tile, wrap on a narrow phone.
- [ ] Contrast grid: cells shrink to 24 dp at 12 colours; the `Aa` (11 sp) and the 11 dp tick and cross disc are legible
      and tick vs cross can be told apart without colour.
- [ ] The colour-blind verdict on real swatch pairs (CIE76 10 is a rule of thumb): do flagged pairs really blur, do
      unflagged ones really not? The six-line cap with `And more.`
- [ ] Merge threshold (12 Lab dE) on real multi-photo sets: it neither folds clearly different colours nor leaves
      obvious duplicates.
- [ ] **Recolour on a real photo:** default strength 100 / keep-shading 0.7 may look posterised or flat; check the strength
      and shading steps give a pleasing range; no seams across the 1 MP bands (the maths proves none, the bitmap seam is
      unproven); the 4096 px / 12.6 MP cap and the time on a mid-range phone are guesses (single-threaded). The copy
      appears next to the original named `<stem>-edited`, the shade shows progress and Cancel, and **it carries the
      original's EXIF including location** (TRAPS #103). The 480 px preview matches the saved copy visually.
- [ ] **Palette export imports into GIMP:** export a `.gpl` and load it in GIMP, Krita and Inkscape; paste the CSS into a
      page. The exact text is unit-tested against the documented format; nothing has been loaded by those programs.
      Receiving apps (notes, mail, a file manager's save-as) show the text and subject sensibly.
- [ ] Swatch card PNG: title baseline, label fit (text shrinks to 90 percent of the block width), hex legibility at 240 to
      4096 px, opened in a receiving app after the share sheet; the FileProvider grant via `ClipData` on a chooser from the
      activity context (`LocalContext` may not be an Activity in some hosts: `FLAG_ACTIVITY_NEW_TASK` is added then).
- [ ] Back: the unsaved-palette hold and the three-Back path from the recolour page; Back with the host's handler and a
      shell room open over the builder (TRAPS #78) was only tested with a stand-in dispatcher.
- [ ] TalkBack: swatch radio announcement (`Colour 2 of 5, #AABBCC, locked, picked`), live regions, stepper descriptions,
      the grid's checkbox cells, the contrast cells (n*(n-1) nodes at 12 colours).
- [ ] Process death mid-edit restores palette, locks, undo and name; the saved list is re-read on open; a recolour in
      progress is gone with no file. Soft keyboard over the name and hex fields (IME Done commits the name; focus loss
      commits it).

## 4. Animated pictures: GIF, APNG, MP4

### 4a. Output opens in other apps, and the sizes
- [ ] **GIF / APNG / MP4 open in other apps.** Play the produced files in Chrome and Firefox on Android and desktop,
      Google Photos, Messages, Gmail, WhatsApp and iOS Safari and Messages: partial delta frames, the transparent index,
      disposal 1 and 2, the 2 cs minimum delay and the loop count. Only the JDK GIF reader and this package's own decoder
      have read the files; **no APNG viewer has**. A plain viewer shows frame 1 of an APNG (saved as `.png`). Does Fotoz's
      own viewer play the saved `.gif` and `.png`, and how is the saved file listed (`-animation.png`; an APNG saved on Android 8 or 9, with no folder, is offered for remaking by
      that name)?
- [ ] **Size estimates.** For 3, 10 and 30 photos at GIF, APNG and video, compare `About X MB, could be Y to Z MB` with the
      saved file's real size (the planner was fitted on synthetic scenes; video is a bitrate guess of 0.15 bit per pixel
      with a 400 kbit/s floor). The 4 MiB large-GIF warning threshold: does it feel right on real photos with the
      4-frame fade? Replace the planner's rates with real ones.
- [ ] Time and memory: 30 frames at 480x360 took 0.3 to 1.1 s on the host (video-like noise 1.1 s); a phone may take
      several seconds (guess 3 to 6x): progress and Cancel on a mid-range phone. 100+ frames at 480x360 (about 69 MB held
      as a sequence); APNG of 120 frames is 83 MB held (the 30 percent heap rule steps it down first); the GIF palette
      sample holds 8 frames. The step-down message appears and the result still looks acceptable.

### 4b. Decode, and other devices
- [ ] **GIF decode on Android 8/9.** Open real GIFs and APNGs from the web, other apps and video converters through the
      source (interlaced, local tables, odd disposal, 16-bit and palette APNG, hidden default image, large canvases; the
      source refuses over 4 MP and over 32 MB) on an Android 8 and a 9 phone. Remember `decodeUpright` is null on 26/27 for
      photos, so a slideshow of photos cannot be tried there; GIF/APNG/WebP decode uses no platform decoder except one
      still-WebP frame through `BitmapFactory`.
- [ ] Animated WebP from real encoders (cwebp, gif2webp, Chrome): lossy with ALPH, lossless, blend and dispose flags, odd
      offsets, and the device's own `BitmapFactory` accepting the stand-alone VP8X file the reader builds (only
      Robolectric's native decoder has seen it). 10 ms durations against a browser.
- [ ] Media3 image-sequence to H.264: exactly `ticks`-many frames per image item (duration rounded up so a tick is not
      lost); 300 image items in a sane time; odd photo sizes come out even-sided; the real file size against the bitrate
      guess; no audio track in the file.
- [ ] Whether the platform accepts `RELATIVE_PATH Pictures/Fotoz/Animations/` for a *video* row on Android 10 to 14, and
      what happens on 8 and 9.
- [ ] Video clip sampling with `OPTION_CLOSEST`: speed per frame (it decodes forward from the key frame, unmeasured),
      memory at 4K, frames near the end of the file (null frames are held up to 5 times, then the source gives up), a
      portrait phone video (frames upright, not sideways), a 180-degree video (cannot be detected; may come out upside
      down), HDR and variable-frame-rate clips.
- [ ] Saved edits on stills (`Use my edits`): crops, straighten and heals applied at the animation's reduced size look
      right; a file changed since the edit was saved is not given the old edit; the switch is hidden when no edit is saved.
- [ ] Cross-fade in gamma versus linear light on real photos; letterbox bars versus crop for mixed-shape stills.

### 4c. The screen
- [ ] Whole flow on a real library: tick 12 photos, reorder with the buttons, set times, fade, Save to GIF / APNG / MP4,
      watch progress, Cancel while reading frames, while the video encodes, while the file is copied: no row, no staging
      file, none after a kill. Open the result from the screen (the new row may not be in `env.assets` yet: `It will show in
      your library soon`; confirm the rescan time). Leaving mid-save stops the save.
- [ ] Layout at 320 dp and at a large font scale: 48 dp buttons and the four-button stepper rows; the three source choices
      in one row with `A video clip` wrapping; the strip (76 dp cells, inset 60 dp each side so about three show on 360 dp)
      scrolls and its first and last cell are reachable; text never clipped.
- [ ] Edge zones: a fling that starts on the strip scrolls it and does not open a room; a vertical scroll near the top or
      bottom edge opens the details/actions room (accepted, TRAPS #72) and pages end clear of the bottom zone.
- [ ] Preview player: right speed (1 s holds, 60 ms fades), loops, pauses in the background and does not jump on return,
      starts paused when `Remove animations` is on and moves after Play; preview time for 24 frames of a 4000x3000 photo set,
      a 20 s 4K clip (unmeasured) and a big GIF; the `small and rough` caption matches what is seen.
- [ ] TalkBack: reading order on each step, a selected cell reads `selected`, the grid's tick state reads as a check box with
      its number badge not read twice, On/Off switches read as switches, live regions speak politely and not on every frame,
      the preview does not chatter. A video part: start/end steppers on 1080p and 4K, a portrait clip, a container with no
      length (end limit falls back to 10 minutes), video thumbnails in the list.
- [ ] Back: the held first Back with picks and with a running save, a shell room over the open screen, Back in the preview
      while it plays. Process death on each step reopens the step, photos in order with times, video part and file type; a
      running save does not (no row left). Lock a folder holding a ticked photo while the screen is open: it drops out and
      the next Save or preview refuses.

## 5. Reel maker

- [ ] **The layered render is the part most likely to need work and has never run.** Export a reel with cross-fades and
      slides on a real phone and play it: black frames or a flash at the start or end of a hand-over, a wrong stacking order
      (incoming picture hidden), a fade that dips to black mid-way, a slide that leaves a gap or overlaps, the first or last
      item missing, output shorter than `project.totalDurationMs`, pictures out of sync. The fallback ladder (720, then plain
      cuts, then 480) covers failures; a wrong-looking *success* has no fallback, and `Plain cuts` is the up-front switch.
- [ ] Ken Burns: slow and smooth at 16:9, 9:16 and 1:1; zoom in really closes in; pans move the way the buttons say, y not
      mirrored; no wobble at the first and last frame; a panorama-shaped photo in a tall frame (a thin moving window, soft
      because staging is capped at 3072 px). Does the library feed time-varying effects per frame for an image, and does the
      progress bar move at all (it may be unavailable and sit at 20 percent)?
- [ ] Every item's duration is exact: a 3 s still, a trimmed clip, the gap lengths; the total against the project.
- [ ] **Reel export with and without a soundtrack.** Without: the file has no audio track and clips are silent. With: pick
      MP3, M4A/AAC, FLAC, OGG and WAV through the system picker; the soundtrack ends with the video and its fade lands in
      the last seconds; a soundtrack shorter than the reel really stops early with its fade; the start-offset stepper does
      what the export does. The `Make the reel without sound?` question for a text file renamed `.mp3` and for a file
      whose access was revoked after a restart; the persistable grant surviving a restart for a reel saved earlier, and the
      `may need picking again` note when a provider refuses it. The 0.6 music level under clip sound (a guess): is the music
      audible under speech? Nothing is ever played inside Fotoz.
- [ ] Clip sound off by default: a clip with sound plays silently; with the switch on its sound is present and in sync; a
      clip with no audio track, and a still-only reel with the switch on, must not fail.
- [ ] **A long video clip** (a 10 to 15 minute 1080p or a 4K clip, trimmed to a few seconds and untrimmed): time, memory,
      heat, and that the export survives the app going to the background (the existing `JobForegroundService`). HDR (HLG)
      clips in a layered reel are tone-mapped to SDR before the compositor: colour right, no failure. A different-shaped
      clip in original-shape mode is letterboxed by the library (its bars are see-through during a fade or slide).
- [ ] Encoder limits: 1080p on a low-end phone (the rung that fails first), 1080 portrait (encoded rotated) plays upright,
      bitrates 8 / 5 / 2.5 Mbps look right, how long a 30 s and a 3 min reel take, battery and heat; 100 photos staged at up
      to 3072 px (cache size; the hour-old sweep); a 15-minute project (the cap) is untested.
- [ ] **No place in the file.** Open a saved reel made from a clip that has a recorded place, in another player and with a
      metadata tool: no place, no creation location. (`ReelMp4Check` passes on the real muxer output; the muxer drop has
      only been tested with a fake muxer.)
- [ ] Writer: `Movies/Fotoz/Reels/` accepted by the real MediaProvider for a video row, the pending row hides on Android 10+
      and appears with the right date after the scan, `-2`/`-3` naming, a cancel leaves no row; Android 8/9 unverified; a
      process kill mid-copy can leave a truncated file.
- [ ] Title card typography: fonts, wrapping of a long place name, right-to-left text, the white-or-near-black text colour on
      the eight swatches (Cream and White take dark text); the preview card text size is a rough match to the exported card.
- [ ] The editor: preview smoothness at 60 Hz with 3 to 4 pictures and a long reel, and opening a panel does not stutter it;
      sizes come from the listed width/height until Coil reports the real one (a photo stored sideways may pan the wrong axis
      for a moment); the preview starts paused with `Remove animations`, pauses in the background and resumes in place; the
      strip with 76 dp cells, a thumb scroll starting just inside the inset (does a drag there still reach a room?), selected
      border plus check, `Photo 2, 3 s, selected` in TalkBack, 200 percent font scale, right-to-left; TalkBack through the
      whole flow (month switches announce `March 2026, 12 items`, Shorter/Longer announce the new time, progress and Cancel).
- [ ] The draft: it works from dates, favourites and order only (no sharpness, similarity or places), so it may keep a
      blurred shot or near-twins: is the first cut a useful start on a real library? 30 min / 1 km, 3 s per photo, 4 s clips
      and the blur floor 40 were not measured; time to draft 20,000+ photos (no progress bar). Title-card date formats in
      the person's locale (tested in English only).
- [ ] Autosave: a kill during an edit loses at most the last second and keeps the old file whole; a 300-item reel comes back
      as the list after a kill. `Watch it` hands the new `Movies/Fotoz/Reels` file to a player (`FLAG_GRANT_READ_URI_PERMISSION`).

## 6. Mood board

- [ ] **Memory with 30 layers** (and with 100): a board of 30 photo layers (12 MP each) with masks and blend modes: preview
      latency, export at Original, 4096, 2048 and 1024, the OOM ladder against a real heap (down to 1536 px; only a fake
      `OutOfMemoryError` has run), a 64 MP export, a masked layer larger than the 16 MP group cap (drawn softer), several
      subject layers in one export (each replays its marks).
- [ ] Drag, pinch and twist with real fingers: the picture keeps up (it is redrawn on a background thread a beat behind;
      the frame and guides are exact), a twist is easy with thumb and finger, the 15 degree snap and the 8 dp snap pull and
      the 10 dp touch margin feel right, a drag from the board's inset never reaches the shell's edge zone.
- [ ] Edges: a rotated photo layer's edge (drawn through a shader rect) and rounded-corner, oval and polygon mask edges at
      export size for jaggies or a dark fringe. Text layers: wrapping, bold, alignment and clipping use `StaticLayout` with the
      phone's font; `TextBox.height` is an estimate, so a box may clip a last line; typing in the Add and Board panels with the
      keyboard (does it cover the field, does the panel scroll to it).
- [ ] Blend modes on a real photo: multiply, screen and overlay at opacity below 100 percent; the API 26 to 28 multiply
      fallback (layer on white) has only run on Robolectric SDK 28.
- [ ] Subject mask on real photos: time the re-cut on every preview (512 px classical geodesic solve); hair, glass and
      low-contrast edges will look rough; the edge can differ from the Lift screen.
- [ ] Layout: the board is inset 60 dp left and right but not top or bottom; on a 360 dp phone it is about 240 dp wide at
      most (acceptable?); panel height (40 percent of the window, 220 to 420 dp, scrolls); six tool chips with a large font
      (one-line labels may clip); the title bar with name, Undo, Redo and Export on a narrow phone.
- [ ] Back: panel, tap-the-points step, photo picker, then the list; the first Back during a save is held with a line, the
      second leaves and stops the save; with a shell room open over the board (stand-in only so far).
- [ ] Export on a real library: `Pictures/Fotoz/Boards` with `IS_PENDING` on Android 10+, a clash gets `-2`, a transparent
      board is a PNG that really has alpha (open it), a JPEG has no location, progress and Cancel in the shade, a kill
      mid-save leaves no file; API 26 to 28 can leave a truncated file.
- [ ] Protection: lock a folder or mark a photo sensitive while a board with it is open: the layer shows `Photo missing`
      in Layers, the preview draws the grey placeholder, Save is refused naming it, and a lock during a long save means
      nothing is written. Photos moved or restored are found again by name, size and date.
- [ ] Process death with a board open and a panel up: board, pick and panel return, undo history does not; a board over 200k
      characters comes back on the saved-boards list and opens from its autosaved file (the last second of edits is lost). TalkBack: tool chips as tabs, steppers
      announce their value, layer rows say picked / locked / missing, swatches say names and hex, the board says its layer
      count; every drag has a button equivalent.

## 7. Chroma ID

Use a printed colour chart or a paint card with known values, and a plain white card (printer paper).

- [ ] **Against a real colour card under three lights.** For 6 to 10 swatches (including a saturated red, a mid green, a
      pale yellow, a mid grey, a dark brown) take readings **with and without the white card** under **tungsten, daylight and
      an LED lamp**, in a bright room and a dim one. Record the shown hex, the three nearest names and the dE to the card's
      value (CIEDE2000). Synthetic frames gave a mean dE of 0.4 to 0.8 with the card; without one the shade is recovered to a mean
      of about 0.3 with a neutral LED when lightness is set aside, and the shown colour is at a fixed middle lightness. A real
      phone is expected to be noticeably worse. With the card: is it within 3 to 6? Without: is the hue usable, and does the
      screen say `Approximate`, show no lightness word and say `How light or dark it is was not measured.`?
- [ ] **Linearity.** Shoot a grey card at three torch distances and check flash-minus-ambient scales by 1/d^2. How badly do the
      phone's tone curve and local tone mapping break the method? Is raw (DNG) worth building?
- [ ] Does the phone's torch stay constant between the two frames (no pre-flash or ramp), and can the camera hold exposure,
      ISO and white balance for both? After `lockLight`, are `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY` and
      `COLOR_CORRECTION_GAINS` equal for both frames? How often does `LOCK_SLIPPED` (2 percent) trip? Check separately on a
      `MANUAL_SENSOR` phone and a phone with AE lock only. Some phones refuse a torch with `AE_MODE_OFF`.
- [ ] Does the torch settle within 8 frames and 350 ms; is the flash frame clearly brighter than the ambient one at 10 to
      15 cm; is the torch bright enough for the signal threshold in a normal room? Does the lock chosen under the torch leave
      the flash frame unclipped on a white card at 10 cm and the ambient frame not too noisy in a dim room? Autofocus with the
      torch on in a dark room.
- [ ] Is `LENS_FOCUS_DISTANCE` accurate at 10 to 40 cm; does the live `move closer / farther` hint follow the phone? Is the
      `hold about N cm` target (10 cm bright room, 15 otherwise) right? The bright-room threshold (exposure seconds times ISO
      below 2) is a guess. Is the 1.5x card/surface distance limit workable by hand?
- [ ] Gyroscope thresholds (steady below 0.15 rad/s, `moved` above 0.35 smoothed) refuse real handheld shots too often, or
      let blurry ones through? A phone without a gyroscope shows no steadiness line. How big a central region survives a
      handheld shot (the measured box is the middle 40 by 40 percent)?
- [ ] Glare: the `not adding up` refusal fires on glossy paper and screens, and matte surfaces do not trip it. The live
      washed-out warning appears before a measurement would be refused as clipped and clears when stepping back.
- [ ] The real LED colour: measure the no-card error against a swatch book for the actual phone (the table assumes 15 to 30
      percent LED tint). Is the BT.601 full-range YUV conversion right on real phones?
- [ ] **Permission and lifecycle.** The screen opens with the reason sentence and an Allow button; the dialog comes from the
      button (not at app start). After one refusal Ask again shows the dialog again; after `don't ask again` only Settings
      is offered; granting in Settings and coming back opens the
      camera. Pause (home, recents, a phone call, the permission dialog) closes the camera and unregisters the gyroscope;
      coming back reopens; the system camera app can use the camera right after leaving; turning the phone releases the camera, reopens it for the new orientation and drops a kept
      card. The preview is upright in portrait, landscape and reverse landscape (the white box sits over
      what is measured).
- [ ] After a measurement the opaque swatch box covers the live view on every phone (a `SurfaceView` can punch through or
      sit above a Compose sibling) and the camera keeps running behind it so `Measure again` is instant; the swatch matches
      the card's colour. Opening Past readings stops the camera and Back reopens it within about a second; the colour
      survives; rotating on the result keeps the colour.
- [ ] The result card layout: the preview shrinks to 30 percent and the card takes 70 with a scrolling column; the whole card
      (swatch, hex, RGB, Lab, LCh, names, accuracy line, three buttons, Measure again, Past readings, privacy line) reaches
      the bottom and stays clear of the nav bar at 100 percent and at a large font scale. The three-button row in each
      history line (Copy hex, Compare, Save) wraps cleanly and stays 48 dp tall.
- [ ] TalkBack: guidance lines, progress and result in a sensible order; the swatch carries a description; polite live
      regions (`Copied #HEX.`, `Added to <palette>.`, `All readings deleted.`); step headings read as headings; history row
      buttons read `Copy #HEX` / `Compare #HEX ...` / `Save #HEX ...`. Date and time on a history row in a non-English locale.
- [ ] Copy hex puts `#RRGGBB` on the system clipboard (Android 13+ shows its own toast too). Save to a palette, then open the
      Palette builder: the colour is at the end of the palette and the new `Chroma ID` palette shows with two colours.
- [ ] The `Approximate` caution and the accuracy sentence read as one thought and not as repeating each other. A phone with no
      flash: the `no flash` sentence and the room-only button, with the ambient note shown. A phone with no camera at all: the
      Chroma ID card is dimmed on the Experimental page.

## 8. Cross-cutting

- [ ] Every new screen at 200 percent font and in TalkBack; every target at least 48 dp; selected state not by colour only
      (check with the colour-vision filter); a button for every drag.
- [ ] Process death on each tool, per its section; the Experimental host restores `reel`, `animate`, `board`, `palette` and
      `colourcheck`, and does **not** restore `chroma`.
- [ ] Offline flavour: nothing in these tools opens a connection (a logging proxy shows no traffic while using all six).
- [ ] Protected folder and sensitive photos: none of the tools shows them; locking a folder while a tool is open refuses the
      next save, preview or export with the tool's own sentence.
- [ ] The Experimental page shows the two new privacy lines and they are true for the build in your hand.

## 9. After the audit fixes

Added after each tool was audited, fixed and reviewed. **None of these has been run; every line is unverified until it is.**
They check the fixes above, not new features. Where a line repeats a figure, it is a guess.

### 9a. Colour vision

- [ ] Time the exact filter on the 3 MP viewer picture and on the 12 MP copy on a low-end phone (`encode8` is now a binary
      search over step edges; nothing has been timed), and the Preview-to-Exact swap on dark and saturated photos.
- [ ] Corrected copy of a photo in a locked folder: `DCIM/Private` lands beside the original and stays hidden; a locked
      Download or WhatsApp folder is refused, the save row is greyed with `This photo cannot be copied from its folder.` (does
      it read as one line on a small phone?). Also try Android 8 and 9, where no folder is set and a locked folder is refused.
- [ ] Open a photo from the Archive album, turn on a colour filter, tap Save a corrected copy: it saves and shows in the
      gallery. A trashed photo: if it cannot be decoded from the trash, the person sees `The photo could not be read.`
- [ ] Lock the folder again while a copy is running: the job fails with `This photo cannot be copied.` and leaves no
      MediaStore row. Mark the photo sensitive and swipe away mid-copy: the copy is refused with the same sentence.
- [ ] Corrected copy: check in the gallery details that no location is present; a transparent PNG stays transparent; a WebP
      stays WebP; a HEIC gives JPEG; the names are `-edited.png` and so on.
- [ ] Daltonize on a landscape with foliage and on a face: does the in-gamut correction still help, with no flat channel? No
      person has judged whether it helps.
- [ ] Rough view: open an animated GIF, an SVG and a RAW with a filter on, and read the chip `rough view` and the room
      caption. Open a HEIC the device cannot decode and read the `Photo not readable` chip and the room line.
- [ ] Zoom to 6x on a 12 MP photo with a filter on and off: the exact picture is a 2048 px / 3 MP decode and will look softer.
- [ ] Pattern overlay at 1x and 4x zoom: do the 8 px-period strokes read as distinct patterns (lines, dots, crosses) or as one
      texture?
- [ ] TalkBack: turn Name colours on and read `Main colours: ...` in the room; a double-tap on the photo names only the
      centre point.
- [ ] Start a copy and turn the filter off from the chip: the Cancel button and the saved or failed notice must still appear.
- [ ] Plates on real people on a few screens: whether a typical eye ever gets Hard, how often a lookalike slip happens, and
      whether `You saw every number on this screen.` reads as a clearance next to `A pass does not rule out a mild
      difference.`

### 9b. Editor hub

- [ ] Heal: drag Spot size away from the default, tap `reset`, tap the photo: no crash, and the radius is the default.
- [ ] Exposure and every other slider: the first drag away from 0 does not move the slider or resize the photo; every slider
      row is 48 dp tall at rest (check HSL and Straighten still look acceptable).
- [ ] Curves on a 640 dp phone and at 1.3x font: how small the photo gets with header, tabs, chips, graph and compact ring;
      decide whether the graph should shrink.
- [ ] A 5-inch phone at 1.3x font: the Light group page with More open and the centre (home) page, with and without auto-fix
      chips; they should scroll inside the cap and not squeeze the photo past usable. Is a 640 dp phone with three-button
      navigation on the right side of the 560 dp line?
- [ ] Split-screen portrait (about 360 x 500 dp): the list layout shows and every tool is reachable.
- [ ] List layout at large font: scroll to Curves or Vignette, open it, press Back: the list is at the same place.
- [ ] Group page with More open: the labelled Back leaves the page in one tap; the system Back steps More, then the page.
- [ ] Press Reset all twice, then open Crop and straighten: it is still deeper, not on the front page.
- [ ] Curves tabs and chips with TalkBack: a tab says selected, chips read as buttons, targets are 48 dp, the selected state
      is visible in greyscale (bold).

### 9c. Palette builder

- [ ] TalkBack on the contrast grid: each cell reads once as `Colour a on colour b, x to 1, Reads well / Hard to read`, with no
      separate `Aa` and no bare header digits. On the swatch strip: the swatches are announced as radio buttons in one group.
- [ ] Phone set to Arabic or Persian: share a palette as GIMP text, paste it into GIMP, Inkscape and Krita, and confirm the
      numbers import.
- [ ] Grey swatch on the Edit page: the Hue buttons look disabled, the caption `A grey has no hue.` shows and is spoken, and
      Saturation raises the grey into a colour. After a Saturation step down to 0, where does focus land?
- [ ] Make two palettes from photos in a row and save each without renaming: the Saved list shows `My palette` and `My palette
      2`, and the name field shows the new name. Delete a row and check where focus lands.
- [ ] Name a palette `Mood ===`, background the app and kill the process from the system, then return: the draft and its undo
      list are back. Swipe the app away from recents instead: the draft is gone (saved state only).
- [ ] A 12-colour complementary or triad harmony from a very dark or very light colour: no two swatches look identical.
- [ ] Real recolour of a 12 MP photo: how long it takes (unmeasured).

### 9d. Animated pictures

- [ ] A video whose first frame is missing at t=0 (edit list, first sync frame after 0): the clip animation is made with a
      later first frame instead of `The video could not be read.` Does a mid-clip gap now jump back to a key frame
      (`OPTION_CLOSEST_SYNC`) instead of repeating the held frame?
- [ ] Android 8 and 9 (unscaled read) with a 4K clip on a low-memory phone: `There is not enough memory to make this.` appears
      at once, not after several step-downs. Does a job that would have fit after one smaller step now fail early?
- [ ] Kill the app mid video-animation save (swipe away), reopen, start any animation: `animate-staging/frames-*` is gone
      once older than an hour (adb or Settings > storage).
- [ ] A real GIF whose last frame has a short row of data: the remade animation keeps all frames and the note reads as
      damaged, not as a cut.
- [ ] A 12-photo faded slideshow in the preview, and a 40-photo one as a GIF at the 120-frame cap: every photo shows for its
      full hold; fades become cuts where frames were dropped.
- [ ] Android 8 and 9: an APNG saved by this app (default folder) appears under Remake / files.
- [ ] Cancel an animation of a video clip right at the open moment: no leaked retriever (logcat shows no
      `MediaMetadataRetriever` finalizer warning).

### 9e. Reel maker

- [ ] Kill the app mid-export, reopen the reel maker, open the Make panel: the `job-*` folder under `cache/reel-staging` is
      gone once an hour old (`adb shell run-as`); a live export is not touched when the panel is reopened during it.
- [ ] A truncated or corrupt audio file that passes the `MediaExtractor` probe: the export fails once with the parse error and
      the sound question appears, with no second render. Repeat with a truncated clip and no sound file: `A photo, clip or
      sound file could not be read.` With both a clip and a sound, which file does the question blame?
- [ ] A library with RAW or DNG files: the draft and the picker do not offer them; a GIF as a still exports as its first
      frame. On Android 8 and 9, a HEIC (and below Android 12 an AVIF): is it still offered and does it fail the whole video?
- [ ] Replace and remove a sound file, then delete a reel: the persisted grant count (`dumpsys` or
      `ContentResolver.getPersistedUriPermissions`) drops, and the backup folder grant is still there. Kill the app within a
      second of replacing a sound file: does the saved reel still name a file it can read?
- [ ] A sound shorter than the reel: `The sound ends before the video does.` shows and the fade lands at the file end. A
      clip with no audio track plus `keep the clips' sound`: the result line matches the MP4.
- [ ] An Original-shape reel whose first photo is stored sideways with an EXIF turn: compare the editor preview frame with the
      exported video's orientation.
- [ ] A reel name with an emoji at character 60 and at character 100: the saved file name in `Movies/Fotoz/Reels` shows no
      replacement character.

### 9f. Mood board

- [ ] Add a portrait phone photo (EXIF-rotated) and a landscape one: the boxes are the right shape, nothing is squeezed, the
      export matches the screen; add 20 or more photos and note how long Add takes to close the picker. Tap Add photo,
      Back, Add photo again during the read: the old add does not land in the new picker.
- [ ] Open an old board that has a portrait photo: it shows centre-cropped, not squeezed, in its landscape box.
- [ ] Open a saved board, wait, go back: its place in the list does not jump to the top. Open a board from a newer build or
      with a damaged layer, change nothing, leave, reopen: the file is unchanged. Do the same after a process kill with
      the board open.
- [ ] Kill the process with the editor open and the library not yet loaded: after restore the photos replace the grey
      placeholders without an edit. Lock a photo's folder with the board open: the preview shows a placeholder for it.
- [ ] Text in capitals, bold, CJK and emoji at several sizes in a fresh text box: no clipped last line on screen or in the
      saved file; the selection frame may be shorter than the text.
- [ ] On an Android 8 or 9 device: a Multiply layer on a transparent background shows in the preview and the PNG.
- [ ] Colours panel: start Find colours, press Back out of the board, start New board: the Colours panel is empty.
- [ ] A two-finger pinch on a layer sitting at 13 or 17 degrees: its angle does not jump to 15; a deliberate twist still
      snaps near 15, 30 and 45.
- [ ] Tap just outside a layer with a soft edge: it is selected. TalkBack reads the turn buttons (`-15°` and so on) as before.
- [ ] A photo layer scaled very small near the export edge, locked after the render starts: is it still refused?

### 9g. Chroma ID

- [ ] No card: measure black cloth and white paper in the same room. Both come out as the same middle-lightness swatch with a
      similar shade, with no lightness word and `How light or dark it is was not measured.` on the card; the hue is still
      right; the nearest name is grey, never Black or White.
- [ ] After a no-card reading, press Measure again: the preview is not near-black in a dim room, and the distance hint,
      washed-out warning and bright-room flag react again within about a second.
- [ ] Same dark surface read with and without a card, then Compare: it says too close to call, not `more vivid`. A dark surface in
      a no-flash room reading: the strength word matches a no-card reading of it.
- [ ] Home, screen-off or Back in the middle of a measurement, 20 times: no crash (logcat for `IllegalStateException` on
      `chroma-camera`) and the system camera app opens right away. Back out quickly while the camera is opening: it does not
      stay `in use`.
- [ ] Per phone, log `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`, the tone-map modes and the available result keys. On an
      uncalibrated phone: no live distance hint, and a card reading carries `Assumes the card and the surface were the same
      distance away.` On an approximate phone: a card at 10 cm and a surface at 13 cm is refused.
- [ ] Read `TONEMAP_MODE` (and the curve) from the capture results: is the sRGB preset really applied while `CONTROL_MODE` is
      AUTO and the torch is on? Measure a grey card at three torch distances and check flash-minus-ambient follows 1/d^2. Does
      asking for fast noise reduction or edge off make any request fail on any phone?
- [ ] A phone with `CONTROL_AWB_LOCK_AVAILABLE` false and no `MANUAL_SENSOR`: it is refused at open with the new sentence, or
      (if it reports gains) does `LOCK_SLIPPED` fire?
- [ ] Rotate the phone (0 to 180 degrees, portrait to landscape) with the tool open: the camera restarts, a kept card is
      dropped (the stopped note), and the preview is upright and not stretched. Rotate on the result card: the colour
      stays. Rotate during a measurement: it cancels cleanly.
- [ ] Permission: a fresh install shows the reason and the Allow button before any dialog. Deny once: Ask again shows the
      dialog again. Deny again or choose `don't ask again`: only Settings is offered, and granting there then returning opens
      the camera.
- [ ] Delete all readings from the history, then Back to measuring: no colour is left on screen, in Compare or in the palette
      save view.
- [ ] A very dark surface (black cloth) in a dim room with the torch on: is it refused as too dark, or read without a light
      bias? Does the flash patch sit above code 8?
- [ ] A grey card (0.18) and tinted paper used as the card: the result still reads `With card` and is off, as the notes say;
      decide whether a reflectance setting is wanted.
- [ ] Save a no-card colour to a palette: the palette holds it at the fixed middle lightness with nothing on the palette
      page saying so.
