# Batch 1 device test checklist

Every device-only claim from the 21 Oct 2026 owner-feedback batch (see `docs/handoff/MASTER-PROGRESS.md`,
"Batch 1: bugs and regressions"), grouped by the owner's report number, in the same shape as
`docs/device-test/phase-0-checklist.md`. This sandbox has no device or emulator, so none of these is
claimed as verified: each section names the JVM/Robolectric tests that cover its pure logic, and the
boxes below are for the things those tests cannot see (real decoders, a real finger, process death,
the system's audio focus, PiP and chooser behaviour, how a thing *feels*). Check each box against a
real phone, on **both flavours** where a line says so, before relying on the corresponding fix.

The reports being answered, verbatim: **#1** "Fotoz is not playing videos". **#28** "When I scrub
through the filmstrip and stop on a video, the video player progress bar etc. replaces the filmstrip so
scrubbing through is no longer possible." **#12** "Tap the subject to lift it is not working. Needs to
be richer: tap to select, draw to select, refine selection, etc." **#15** "Edit doesn't work great -
pro tools toggle etc. Curves doesn't show me the curves at all, like Snapseed does - I want that, not
just the presets." **#19** "The transition effect of the photo becoming smaller and appearing on the
map in its info section is not happening ... not a pane tilt, rather a shrink-to-appear-on-the-map
effect. And the map bg when the location is unknown is weird, and not comforting at all."

Where a line says a claim is "inferred", it comes from reading Media3 or Compose behaviour, not from
a run; the check is also a test of that inference.

## #1 — Fotoz plays videos

Sandbox coverage: `PlaybackLogicTest` (transport rule, error mapping and wording, resume policy,
retry-without-subtitles, collapse rule), `VideoPlayerStartTest`, `ViewerPlayerFactoryTest`,
`ResumePositionStoreTest`, `ExternalAssetIdentityTest`, `ExternalViewerActivityManifestTest`,
`SidecarSubtitlesTest` and the video cases in `ViewerIntegrationTest`. None of them decodes a frame.

### Starting, pausing, failing

- [ ] Open a catalogued H.264 clip with default settings → it starts by itself (autoplay is now on),
      with the chrome already up. **Pause works while it is still buffering** (before the first
      frame), and after pausing a tap on the centre glyph resumes it. A tap on the glyph or the
      resume chip must NOT also toggle the chrome.
- [ ] Turn autoplay off in the viewer's view options room (right edge), open a clip → it waits at 0:00 behind the
      centre glyph; the setting survives a restart.
- [ ] A 4K HEVC 10-bit clip, a Dolby Vision clip and an MKV → each either plays (decoder fallback)
      or shows the error panel with the right wording ("This device can't play this video's format",
      "This device couldn't decode the video", …). **Open with** hands the file to another app;
      **Retry** re-prepares (it is offered only where trying again can help).
- [ ] A clip whose file was deleted behind the app's back, and one whose read permission was revoked →
      "This video can't be found…" / "Fotoz doesn't have permission to read this video." with no
      Retry for the first. Never a black rectangle with a Play glyph that does nothing.
- [ ] A video with a sidecar `.srt`/`.vtt` in the same folder, including one deleted or unreadable
      since the last scan → the video still plays (a silent retry without subtitles); subtitles
      appear when the file is readable. (The claim that a bad sidecar is a *fatal* error is inferred
      from Media3's behaviour, so this is also the check that the retry was needed at all.)

### Resume

- [ ] Watch 30 s, press Home (process alive), reopen → with autoplay on it resumes silently at about
      0:30 (no "Resume from" chip); with autoplay off the "Resume from 0:30" chip shows and seeks.
- [ ] Watch 30 s, then swipe the app away / kill the process, reopen → same result. This is the
      `ON_STOP` save (TRAPS #42); a save that only ran on dispose would lose it.
- [ ] Works for a clip whose MediaStore duration is 0 (the chip seeks, the scrubber responds once the
      player knows the duration).
- [ ] Open a clip you have a remembered position for and leave without touching anything → the
      remembered position is still there next time (an open-and-leave must not store 0:00).
- [ ] External (open-with) clips never show a resume chip and never write a position; two *different*
      external videos do not share a position.

### Lifecycle, audio, picture-in-picture

- [ ] Home / screen-off stops the audio. Entering picture-in-picture keeps playing; closing the PiP
      window with its X stops it; expanding it back keeps playing. Re-check on API 26-27, where some
      OEMs deliver `ON_STOP` around PiP entry.
- [ ] Close the viewer mid-playback, then press Home → the app must NOT enter picture-in-picture onto
      the gallery (auto-enter is disarmed when the viewer goes).
- [ ] Audio focus (a visible change): start a video while **another app's** music is playing (a
      music or podcast app; Fotoz has no player of its own any more) → that music pauses. An incoming call or a navigation prompt pauses the video. Unplugging headphones pauses
      it instead of playing on the speaker.
- [ ] The screen stays on during playback and times out normally when paused or ended.
- [ ] The PiP button is absent on a device without the PiP feature, and on `ExternalViewerActivity`
      (open-with) it works and auto-enter works too.
- [ ] No position chip and no filmstrip inside the PiP window; they return on exit.

### Surrounding behaviour

- [ ] Opening a video: the chrome (position chip, transport, filmstrip) is up with no tap; a tap
      hides/shows it; paging to a second video after hiding the chrome keeps it hidden. Opening on a
      photo and paging to the first video brings the chrome up that one time.
- [ ] Slideshow with a video in the roll → it waits while the clip plays, then advances one interval
      after the clip ends (or is paused). It does not advance while the lift editor is open.
- [ ] Key moments: on a catalogued clip the "Key moment" pill appears a few seconds after playback has
      started (the indexer waits 1.5 s after the first ready state), and not at all for a video that
      was never played. Playback is not slower to start than before.
- [ ] The video editor's preview still plays (it now comes from the same player factory, so it takes
      audio focus and uses decoder fallback).
- [ ] **Release (minified) build smoke test** of playback, subtitles and PiP: `proguard-rules.pro` has
      no Media3 section, and Media3's consumer rules are believed (not shown) to be sufficient.

## #28 — The filmstrip stays up over a video

Sandbox coverage: `ViewerChromePolicyTest` (the strip takes no asset), `FilmstripGeometryTest`, and
`ViewerIntegrationTest` (a video opens with the strip along the bottom; scrubbing across a video keeps
the strip alive and keeps reporting past it; the scrubbing flag is false when the strip leaves
composition). It cannot judge stutter or the feel of the drag.

- [ ] Scrub the filmstrip from a photo, across several adjacent videos, onto a photo without lifting
      your finger → no frozen frame or decoder error, **the strip never disappears**, nothing
      stutters, there are no audio blips, and only poster thumbnails show while you pass the videos.
- [ ] Stop on a video → it starts about 250 ms later (autoplay on). Touch the strip again while it
      plays → it pauses at once and the transport dims; the next video does not start until the strip
      has settled.
- [ ] The video transport (two rows plus the "Key moment" pill) sits **above** the filmstrip band,
      overlaps nothing, and the strip stays on top and scrubbable.
- [ ] 360 dp-tall landscape with the filmstrip over a video → the second transport row (speed,
      subtitles, audio, A-B loop, PiP) folds behind the "More controls" toggle once the stacked chrome
      would take more than about 40% of the height, and the picture is not mostly covered.
- [ ] While the strip is dragged the transport dims; the 42 dp upward bulge reads as being in front
      of it.
- [ ] Toggle the chrome off mid-fling on the strip → the video is not left paused for good (the
      scrubbing flag clears when the strip leaves composition).

## #12 — Lift subject

Sandbox coverage: `LiftMappingTest` (tap mapping through the decoded bitmap, failure wording,
eligibility), `SubjectSegmentationTest`, `GeodesicSegmenterTest`, `SelectionSessionTest` (history
replay, undo/redo), `StickerExporterCacheTest`, `StickerFileNameTest`, and the lift cases in
`ViewerIntegrationTest` (the row exists for photos and not videos; choosing it withdraws the rooms).
Only synthetic scenes were segmented; the overlay itself has no Compose test.

### It works where the tap lands

- [ ] A portrait JPEG with an EXIF rotation (a Samsung camera photo), a HEIC and a PNG with alpha:
      open Lift, tap the subject → the selection lands where you tapped (not dismissed, not offset).
      Compare with Live Text alignment on the same photo (TRAPS #48 says Live Text may be offset for a
      rotated photo; this tells us whether it is).
- [ ] An unreadable or permission-revoked photo → "Couldn't open this photo for lifting" with Close;
      no crash. A very large photo on a low-RAM device → "too large to lift", not a crash.

### The editor

- [ ] Real-photo quality of tap and scribble selection (people, pets, a mug on a table, a busy
      background), and the latency of each stroke and each undo on a mid-range phone (the JVM does a
      512 x 384 solve in roughly 130-200 ms). A multi-coloured subject needs a second scribble; a
      same-coloured neighbour needs a Background scribble; hair and glass are expected to be rough.
- [ ] Scribbling near the four screen edges does not drag a room open, and does not flip the page or
      zoom the viewer: the overlay consumes all its own touches.
- [ ] Back: in the editor, Back closes Lift (not the viewer); on the result screen, Back returns to the
      editor. Check the ordering against the shell's and the map's back handlers.
- [ ] The checkerboard renders (an `ImageShader` tile) in the Preview toggle and on the result card, on
      light and dark system themes.

### Getting in and getting out

- [ ] The "Lift subject" row in the actions room starts the editor from a closed-room state and from a
      zoomed or rotated photo (the editor itself must not be zoomed or rotated), and the room that
      was open slides away rather than vanishing.
- [ ] A long press on a photo with no recognised text starts Lift. A long press on a photo **with**
      recognised text while the chrome is up does nothing today (TRAPS #50); the row is the way in.
- [ ] Tap the *words* of an actions-room row (not the icon) → expected to do nothing today (a known,
      unfixed defect: only the 22 dp icon is clickable).
- [ ] Copy → paste into a messaging app or notes keeps transparency; Share opens the chooser; **Save
      sticker** appears in Photos under `Pictures/Fotoz/Stickers` (Android 10+), at most 2048 px on
      the long edge, with alpha. Copy and Share alone must not add anything to the gallery.

## #15 — Editor and Curves

Sandbox coverage: `ToneCurveTest`, `CurveEditingTest`, `CurveHitTest`, `CurvePresetsTest`,
`ToneHistogramTest`, `AdjustmentsTest`, `EditRecipeJsonTest`, and `CurvesEditorGestureTest` (drag,
long-press, double-tap and drag-off under Robolectric). There is no full-`EditorScreen` test, so the
layout budget below is reasoned and compiled, not seen.

- [ ] Open the editor → the tool strip scrolls, **Curves is the third chip and reachable on a 360 dp
      wide phone**, and there is no "Pro tools" chip anywhere. Undo, Redo, Cancel and Save fit in the
      top bar with the photo name ellipsised.
- [ ] Curves gesture feel: the 24 dp touch radius, touch slop, the 400 ms double-tap window and the
      system long-press timeout feel right under a thumb. A new point added by touching the line does
      not jump; dragging does not feel offset from the finger; the end points move vertically only.
- [ ] Android's edge-back gesture near the far left and far right of the graph → points at the
      extremes can still be grabbed without triggering Back (the graph has 16 dp of handle padding).
- [ ] Removal: drag a point more than 32 dp off the graph → it turns red with a haptic and goes on
      release; dragging back in disarms it. A long press removes it, also with a haptic. Confirm the
      two haptic constants used (`LongPress`, `TextHandleMove`) are actually perceptible.
- [ ] Vertical budget: on a 640 dp-tall phone and in landscape, the preview above the Curves tab stays
      usable (the graph is capped at min(200 dp, 30% of screen height, panel width), never below
      120 dp; other tabs' controls are capped at 40% of screen height and scroll).
- [ ] Reduced-size drag preview: while a point is dragged the photo renders from a 640 px copy and
      re-renders at full preview size on release → no visible flicker or size pop on release, and
      dragging is fluid on a 2048 px source.
- [ ] The histogram backdrop looks right on a real photo (per-channel tint for R, G and B; luma for RGB
      and Luma), and a photo with clipped shadows is not flattened by the end-bin damping.
- [ ] The Luma tab: brightening a saturated photo keeps the colours natural (no hue shift or clipping
      artefacts at high boosts), and the exported full-size result matches the preview.
- [ ] Process death: kill the app with the editor open on the Curves tab with the Luma channel
      selected → on return the tab and channel are restored (the recipe itself comes back through the
      existing "Continue editing" dialog).

## #19 — The photo shrinks into the map pin; the unknown-location plate

Sandbox coverage: `PlaceMorphTest`, `PlaceFlightGeometryTest` (the flier stays on screen at every
progress; hand-off has no gap and no double-opaque), `PhotoToPinFlightTest` (frames captured through
Roborazzi; the hand-off agrees to about 4 px), `PlaceUnknownPlateTest` (including that a failed
location read never offers "Add a place" or the picker), `LocationPickerTest`, and the flight cases in
`ViewerIntegrationTest`. The connect flavour's map snapshot and expanded map are compiled, not
rendered, under Robolectric.

### The flight

- [ ] **Both flavours.** Pull the details room down slowly with a finger, and use the pinch-in
      shortcut, on a landscape photo, a portrait photo, a screenshot with no GPS (it lands on the
      ghost pin) and a GPS photo → the photo visibly **shrinks and lands as the pin**, and it reads as
      a shrink-to-map, not a pane tilt. Reverse it by dragging back and by pressing Back; hold the
      finger still mid-pull and nothing moves.
- [ ] The hand-off seam at the last few percent of the pull: no flash, no dimming of the plate, no
      size or position jump between the flier and the room's real pin.
- [ ] Close the details room from a scrolled position: if the pin is out of reach there is no flier and
      the pin fades in place; if it is still fully visible the flier flies from its scrolled position.
- [ ] Cold image cache: the flier's image is a second request for the same photo → it does not flash
      empty while the real pin is still hidden. For a video the flier uses the poster.
- [ ] Rotated-EXIF portrait: the flier starts exactly over the displayed photo. If it starts cropped,
      zoomed or the wrong shape, the stored dimensions need the orientation applied (TRAPS #48).
- [ ] A zoomed or rotated photo, then pull the details room → no flier; the in-plate shrink plays; no
      flash and no double pin.
- [ ] **Connect flavour.** The map tile arrives only after the room has settled (about 160 ms at rest),
      never during the gesture and never before the pull begins.
- [ ] A video: pull the details room down over it → the home card slides flat (no swivel) while the
      video plays; the flier draws the poster, not the player surface; the surface follows the scaled,
      translated parked card without tearing or a black rectangle; the audio keeps playing.

### The unknown-location plate and the picker

- [ ] On a real device the graticule contrast and the ghost-pin dimming are legible but calm on an
      OLED screen; the "Add a place" target feels right; the offline flavour looks right on the
      graticule alone.
- [ ] A hand-placed location shows "Placed by you · Change" under the plate; **Change** reopens the
      picker, and "Remove this location" and "Also write into the photo file" are reachable again.
- [ ] LocationPicker: a tap places the pin; dragging the pin (starting within about 36 dp of it)
      fine-tunes it; a thumb-scroll of the room over the grid still scrolls; the keyboard and the
      coordinate fields behave (typing "17." stays "17."); Android's write-consent dialog appears
      once per placement, not once per drag frame.
- [ ] A photo whose location could not be *read* (revoke "Allow access to location in media") never
      offers "Add a place" or the picker.

> **Note, 21 Oct 2026.** This checklist was written when the viewer's actions were on the right edge
> and its settings at the bottom. Since batch 2 the geography is: top details, bottom actions (a
> short pull shows the quick strip), right view options. Where a step above says "the actions room"
> it now means the bottom room, and the viewer's old "settings room" is the view options room on the
> right. The steps for those rooms are in `batch-2-checklist.md`.

## Across the viewer

- [ ] Open a room over a **playing** video, in the 3D swivel (the actions room from the bottom, the view
      options room from the right: the rooms moved in batch 2): the video surface follows the rotation and clip acceptably, or it
      does not (unverified). If it shows artefacts, the options are a texture-view layout (costs HDR
      fidelity) or pausing while a room is open; neither was done blind.
- [ ] Repeat the opening-a-video, filmstrip-over-a-video and pull-down-to-the-map checks above once on
      each flavour (offline and connect). Only connect has a map snapshot behind the pin; offline must
      look right on the graticule alone.
