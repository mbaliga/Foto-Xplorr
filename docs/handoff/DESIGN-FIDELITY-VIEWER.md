# Design fidelity: the viewer (filmstrip, details room, map plate, zoom minimap)

Branch: the Phase 1 foundation branch (worked in an isolated worktree, merged by the owner).
References: the owner's screenshots `5.png` (viewer + filmstrip) and `6.png` (details room), both
440 x 956 px, so 1 px = 1 dp. The renders under `app/build/renders/viewer-*.png` are 2x (xhdpi), so
1 dp of the reference = 2 px there; every comparison below was made after a 2:1 box downscale.

This file records every judgment call, every reversal of a previously documented decision, what
could not be matched and why. The master progress row is the owner's to add at merge.

**Revised 21 Oct 2026 (owner-feedback batch 1).** Three things described below changed after the
owner's later reports: the filmstrip no longer disappears over a video (section D), the photo's
flight into the map pin is a separate overlay rather than a shrink inside the plate (section C), and
the no-location plate is a calm graticule with a ghost pin rather than plain background (section C).
The original text is kept where it records a decision and marked **[superseded]** where the code no
longer does it; "Revisions in batch 1" at the end says what replaced it and why.

**Revised again 21 Oct 2026 (batch 2).** The viewer's rooms moved: section A's documented deviation
is retired (one geography, with actions at the bottom and view options on the right), and the chrome
row gained a favourite heart and a transparency button while the slideshow gained a bar. "Revisions in
batch 2" at the end says what changed.

## What changed, surface by surface

### A. Details is the pull-DOWN room (a documented deviation from the app's geography)

**[Superseded 21 Oct 2026, batch 2: the deviation is retired. The viewer now has the gallery's
geography (details top, actions bottom, view options right); the viewer's old "settings" room is its
view-options room and the actions room moved from the right edge to the bottom. Everything below
about the *details* room being the top room still holds. See "Revisions in batch 2".]**

`ViewerScreen`'s rooms are now `top = PhotoDetailRoom` (reveal = `shell.vProgress`, POSITIVE, which
is `SpatialMotion`'s sign for a TOP room) and `bottom = ViewerSettingsRoom`. The pinch shortcut
maps pinch-IN to `RoomEdge.TOP`. The GESTURES caption in `ViewerSettingsRoom`, the sentence in
`StampMapScreen`'s empty state and the KDoc of `GalleryInfoRoom` all say "drag the photo down"
now; the stale "NEGATED reveal" comment in `ViewerScreen` is rewritten for the positive case.

**Deviation.** `GalleryScreen` records the owner's rule of one geography for the whole app (TOP =
settings, BOTTOM = what-this-is, owner 2026-08-18). The viewer now breaks it on the owner's own
later direction (the reference opens the details by pulling the photo down). The gallery's rooms
are untouched; the viewer is the one screen that deviates, and `ViewerScreen`'s KDoc says so.

### B. The details room, in the reference's order

`PhotoDetailRoom` is: title + date -> camera card -> map plate -> caption -> Information (with a
Show More / Show Less toggle) -> Tags -> then the Fotoz-only blocks the reference does not draw
(library marks, the OCR "Search inside this photo" card, rating / creator / copyright). Nothing
reachable before is deleted; the extra blocks moved below the reference's content.

**Reversal: the camera card is back.** The room's old KDoc recorded a de-duplication pass
(owner 2026-09-01) that removed the card because every fact on it was also a labelled row. The
reference puts the card back, so the division of labour was redrawn rather than the rule dropped:
the *card* owns the capture facts (camera, format chip, flash glyph, lens line, dimensions line,
dynamic-range chip, ISO / focal / bias / aperture / shutter strip) and states absences itself
("Unknown camera", a dash per cell, a dashed lens line); the Information rows no longer repeat any
of them. "Every fact is said once" still holds -- what changed is which surface says each fact.
`CameraCardFacts` is the pure resolver, pinned by `CameraCardFactsTest`.

Other judgment calls in the room:

- **Show More / Show Less.** The reference frame shows three rows under "Show Less". A button
  labelled with its *action* reads "Show More" over three rows and "Show Less" over all of them,
  so the room defaults to collapsed (the reference's three rows: Modified, Colour space, Colour
  profile) with "Show More". The render therefore says "Show More" where the reference says
  "Show Less" -- the one deliberate text difference in that frame. Expanded adds Aspect, Duration
  (video), Album, Where, Location (the coordinate line, moved off the plate -- the reference's
  plate carries no caption) and the Palette row.
- **"Colour space" / "Colour profile"** keep the room's British spelling ("Favourite" is on the
  same screen); the reference says "Color". One letter, kept consistent with the rest of the app.
- **Colour profile is a real row now**: `ImageExifDetails.colorProfile` comes from a bounds-only
  `BitmapFactory` pass (`inJustDecodeBounds`, no pixels -- TRAPS 34 is about pixels), which names
  sRGB exactly as the reference does ("sRGB IEC61966-2.1").
- **The exposure strip shows the 35 mm-equivalent focal length** (`TAG_FOCAL_LENGTH_IN_35MM_FILM`,
  new on `ImageExifDetails`) and falls back to the real one. The reference strip says "24 mm" under
  a "13 mm" lens line, which is exactly that distinction.
- **The STANDARD chip.** The reference shows "STANDARD" on an iPhone HEIF. P0-16's decision stands:
  this app cannot verify "standard" for a HEIF/AVIF (an Apple HDR HEIC carries its gain map in a
  form the XMP check does not see), so `dynamicRangeBadge` now claims STANDARD only for a JPEG
  with no gain-map namespace -- a checkable negative -- and draws no chip otherwise. The render
  uses the reference's HEIF, so it shows the HEIF chip and no STANDARD chip. A JPEG shows both.
- **Caption** is the reference's centred "Add a Caption"; tapping it opens the field, which keeps
  the previous commit-on-blur / commit-on-dispose dance. **Tags** always shows its header and the
  "Add Tags…" placeholder; with `onAddTag` wired (new on `ViewerScreen`, wired in
  `FotoXplorrActivity` to `LibraryStore.addTag`) tapping it opens a field.
- **The cloud glyph** beside the title (local / in-trash state) predates this round and stays; the
  reference has no such glyph. It sits to the right of the title and moves nothing.
- The room pads for the status bar (`statusBarsPadding`) because the shell consumes no insets; the
  reference's top 48 dp are blank, so in the render (no status bar) the title lands at the
  reference's y. On a device the whole room sits below the status bar, which is the only sane
  behaviour on Android and the only difference from the frame.

### C. The map plate and the flight

`PlacePlate` is the reference's 400 x 400 rounded square (radius 12), and `PlacePin` is the 80 dp
tile with a 2 dp white border, 8 dp corners and a 14 x 7 pointer, the GROUP centred on the location
(measured: the reference centres tile + pointer, not the pointer's tip). The pin is shared with both
expanded maps so the tile a map highlights is the tile the plate flew the photo into.

**[Superseded 21 Oct 2026: this in-plate flight is now only the fallback; see "Revisions in batch
1", B.]** The flight: `PlaceMorph.heroScale` starts the photo at 92% of the plate's width (4.6x the pin on a
400 dp plate) and smoothsteps it to 1x by 85% reveal; the white border fades in with the plate;
the pointer appears only in the last stretch (`pointerAlpha`, from 1.25x down to 1x) because a
pointer hanging mid-photo read as a stray mark in the mid-flight render. The room's text slides up
18 dp as it fades in (`textOffsetDp`), on the same curve as its alpha, read inside `graphicsLayer`
so a drag re-draws it without recomposing.

**Privacy kept, design kept.** During the drag the plate is still the local graticule: nothing is
fetched because a finger moved. Only once the room is fully open AND settled -- `openRoom == TOP`
held for 160 ms, the controller cannot say whether a finger is down so a short debounce stands in
for "released" -- does the connect flavour's `PlateMapBackdrop` ask MapLibre's `MapSnapshotter`
for ONE static image (OpenFreeMap "dark", zoom 15.4, no logo, attribution printed on the plate),
fades it in over 420 ms and caches it per photo (`PlateMapSnapshots`, LRU 8, keyed on the photo and
verified against the coordinate). The offline flavour's `PlateMapBackdrop` draws nothing: the
graticule look, no network -- the `PhotoMapEntry` seam again, one call, two source sets.

**[Superseded 21 Oct 2026: the owner found this plate "weird, and not comforting at all"; see
"Revisions in batch 1", C.]** **No location** draws the plate's space as plain background with the one explanatory sentence
(owner's words); the picker still appears when a location may be placed by hand; a failed EXIF
read still refuses the picker (P0-02).

**Tap -> the map fills the screen.** `PlacePlate` reports its root bounds; `ViewerScreen` grows a
full-screen overlay out of exactly that rectangle (scale + translate about the top-left corner,
corners rounding from 12 dp to 0, 360 ms eased, no overshoot) and Back shrinks it back. The overlay
hosts the flavour's `ExpandedPhotoMap`: connect = `RichPhotoMapScreen` in a new focus mode (camera
on the photo at street zoom, dark style, no clustering, every located photo a filled strokeless dot
coloured from a data-driven expression, the photo itself drawn as the `PlacePin` riding the map via
a camera-move listener, search / filters hidden); offline = `StampMapScreen` in focus mode (every
located photo a filled dot drawn in one Canvas pass, the photo its tile). Dot colours come from
`MapFocus.colorFor`: the photo's dominant palette colour when `PaletteMemo` already holds it, the
accent otherwise -- never a decode per dot. `PaletteRow` writes into the memo; a later task
persists palettes and can replace the memo with the store.

**MapLibre on the JVM.** Neither `MapSnapshotter` nor `MapView` has a renderer under Robolectric
(native library). The render test substitutes `ViewerFixtures.mapStandIn` in the exact slot the
snapshot fills (ground `#344459`, roads `#445D7C`, water `#1D3373`, parks, all sampled from 6.png),
and the expand/collapse crash test runs only under the offline flavour (`assumeFalse(NETWORK_FEATURES)`).
The connect snapshot and the connect expanded map are therefore compiled and reviewed, not
rendered: they need a device.

### D. The filmstrip drives the photo live, and bulges while dragged

*(Revised 21 Oct 2026: the strip is also drawn over a video. It used to be hidden for a video; see
"Revisions in batch 1", A.)*

`FilmstripScrubber` rests at the reference's geometry (`FilmstripGeometry`: 53 x 58 dp thumbnails,
1 dp gaps, a 74 dp band = 58 + 16 under them, the current photo centred, NO frame, NO dimming, NO
magnification at rest -- 5.png has none of those three, so the previous viewfinder frame, the
selection dim and the resting loupe are gone).

- **Live drive.** `nearestToCentre` runs in a `derivedStateOf` over the list's layout info -- re-
  evaluated per scroll frame, emitted only when the index changes -- and `onSelect` fires the
  moment the nearest thumbnail changes under a USER scroll. The host swaps the large photo once per
  thumbnail; Coil decodes it asynchronously as before. The echo guard (`selfReported`) is kept.
- **User scroll, not any scroll.** `isScrollInProgress` is also true while the strip animates
  itself to the host's photo (on open, after a page swipe, after a tap on a far thumbnail). Gated
  on `interactionSource.collectIsDraggedAsState()` plus "the fling that drag threw", so a
  programmatic scroll neither bulges nor reports the thumbnails it passes. (The first version did
  both; the bulged render came out flat because the opening `scrollToItem` had bulged and un-bulged
  the strip before the capture.)
- **Bulge.** An `Animatable` rises to 1 on user scroll (180 ms) and falls on stop (260 ms). The
  band's height is read in a `layout {}` lambda (74 -> 116 dp, bottom edge fixed, so the extra
  height is headroom above the thumbnails), the loupe's peak (1x -> 1.7x) and radius (82 dp) in each
  thumbnail's `graphicsLayer`, scaling about bottom-centre so the bulge is all upward. Nothing
  draws outside its own bounds, so nothing is clipped. Draw order: the thumbnail nearest the
  centre on top (`zIndex` keyed to `centreIndex`, which changes once per thumbnail, never per frame).
- A tap on a thumbnail now scrolls it to the centre explicitly; the old version relied on the
  host's echo, which the echo guard then skipped, so a tapped thumbnail was never centred.

### E. The zoom minimap

`ZoomMinimap` is the reference's navigator: bottom-left, 18 dp from the left and 16 dp above the
bottom (plus the navigation bar), an 82 dp box following the image's aspect, filled `#9DB8FF` at
55% with NO border, and a solid `#5A8BFF` rectangle for the visible viewport. The maths moved to
`ZoomViewport.fractions` (pure, `ZoomViewportTest`) and is evaluated inside `drawBehind` from the
live scale / offset providers, so a pan re-draws it with no recomposition. It shows only while
zoomed (`scale > 1.05`). **Judgment call:** it is no longer gated on the chrome being visible --
the reference shows it over the bare zoomed photo, and a navigator that only appears when the
chrome does is one nobody finds while zoomed.

## Render-by-render comparison (1 dp = 1 px of the reference; renders downscaled 2:1)

### `viewer-3-details-open.png` vs 6.png

| Element | Reference (y, px) | Render (y, px) | Note |
|---|---|---|---|
| Title "IMG_0767", cap height | 49..62 (14) | 49..62 (14) | 19 sp Medium, white; x = 21 both |
| Date line cap | 79..90 (12) | 79..90 (12) | 16 sp, `#EBEBEB` (ref peak 239) |
| Raised panel `#09090A` | 67..243, x 2..437 | 67..243, x 2..437 | |
| Camera card | 108..235, x 20..419 | 108..235, x 20..419 | header 36 dp `#1A1A1D`, body `#111113` |
| "Apple iPhone 17 Pro" cap | 121..130 | 121..130 | 14 sp Medium, `#E8E8E8` |
| HEIF chip | x 349..383, y 116..134 | x 350..384, y 115..134 | bg `#212023`, text `#CAC8CD` |
| Flash glyph | x 392..413, y 116..135 | x 393..413, y 114..135 | ring `#6A696E` |
| Lens line cap | 155..164 | 155..164 | 14 sp `#626165` |
| Dimensions line | 179..188 | 179..188 | |
| Card divider | 205..206 | 205..206 | 2 dp `#18171A` |
| Exposure strip text | 217..224 | 217..224 | cells at x 27 / 121 / 216 / 297 / 378 both |
| STANDARD chip | present | absent | P0-16, see above (JPEG shows it) |
| Map plate | 254..653, x 20..419 | 254..653, x 20..419 | radius 12 |
| Pin tile (white border) | x 180..259, y 410..489 | x 180..259, y 410..488 | 80 dp, 2 dp border |
| Pointer | 490..496 (14 x 7) | 489..495 | |
| "Add a Caption" cap | 676..688 | 676..688 | 17 sp Medium, centred on x = 220 |
| "Information" cap | 727..738 | 727..738 | 16 sp Bold `#DDDDDD` |
| "Show Less" / "Show More" | 729..738, right at 417 | 730..739, right at 417 | text differs by design |
| Hairlines | 750 / 777.5 / 805 | 750 / 777 / 804.5 | 1 dp `#141414`, x 22..418 |
| Row labels / values cap | 759..768 | 759..768 | 13 sp / 13 sp Medium, `#949695` / `#DCDCDC` |
| "Tags" cap | 851..862 | 851..862 | 16 sp Bold |
| "Add Tags…" cap | 878..888 | 878..887 | 15 sp `#474A4A` |

What still differs: the typeface (Hyle Grotesk, not SF Pro -- glyph widths differ by a few px per
word, e.g. "Information" 85 px vs 95 px), the STANDARD chip, "Show More", the cloud glyph, the
spelling of Colour, the Modified value's format (`DetailFormatting.dateLine`'s own, not Apple's
"Monday, August 3, 2026 at 10:47 PM"), and of course the map itself (a stand-in on the JVM).

### `viewer-1-filmstrip.png` vs 5.png

| Element | Reference | Render |
|---|---|---|
| Photo (square), letterboxed | y 258..697, full width | y 258..697, full width |
| Thumbnail band | y 882..939, 16 dp black below | y 882..939, 16 dp below |
| Thumbnail width / gap / pitch | 53 / 1 / 54 | 53 / 1 / 54 |
| Current thumbnail | centred on x = 220 | centred on x = 220 |

Differences: the shell's own chrome -- the edge peek pills (top, bottom, right) and the position
chip ("5 / 9" + close) -- which the iOS mockup has none of and which predate this round; and the
photo subject (synthetic fixture).

### `viewer-2-filmstrip-dragging.png`

The band at 116 dp with the centre thumbnail at 1.7x growing upward, neighbours eased by the
raised-cosine loupe, bottom edge fixed, nearest-to-centre on top. No reference frame exists for
this state; the owner's words were "bulge up as I swipe through it".

### `viewer-4-details-mid.png`

Reveal 0.5: the photo at ~2.8x its pin, border at 42%, no pointer yet, the room's text at ~2%
alpha and 17 dp below its resting place. No reference frame exists for mid-flight. (Since 21 Oct 2026
this is the *fallback* path: the room is rendered with `flightActive` off, as it is for a zoomed or
turned photo or a pin scrolled out of reach. In the shipped flow the overlay flier does this work;
its frames are probed by `PhotoToPinFlightTest` into `app/build/renders/flight-probe-*.png`.)

### `viewer-5-details-no-location.png`

**[Superseded 21 Oct 2026.]** The plate's space was background with the sentence; everything else
as in frame 3. The room now draws `PlaceUnknownPlate` there (a still graticule, a dimmed ghost pin,
"No location yet" and the sentence), so this render changed with the code. No reference frame exists
for this state, and it has not been compared against anything; `PlaceUnknownPlateTest` also writes
`app/build/renders/place-unknown-add.png` and `place-picker-empty.png`.

### `viewer-6-minimap.png`

Double-tap zoom (2.5x): the navigator at x 18 dp, 16 dp above the bottom, 82 x 82 (square photo),
field `#9DB8FF` @ 55%, viewport solid `#5A8BFF` -- a tall narrow rectangle, as the owner described
the reference. The reference image itself was not on disk; this follows the brief's description.

## Tests

New: `FilmstripGeometryTest`, `ZoomViewportTest`, `CameraCardFactsTest`, `ViewerEdgeCaseTest`
(Robolectric: no EXIF / no GPS / zero dimensions, a failed EXIF read, NaN and out-of-range
coordinates, an undecodable file, a video, every reveal a finger can stop at, an empty / shrinking
roll, live reporting under a real drag, the minimap at zero size, a room pulled open mid-page-swipe
then rotated then zoomed, a video with an empty roll, plate tap -> expanded map -> Back (offline),
the stamp map in focus mode with 2,000 dots and a focus the index does not know),
`ViewerRenderTest` (the six renders). Extended: `PlaceMorphTest`, `DetailFormattingTest`.

`ViewerFixtures` makes Coil decode `file://` PNGs synchronously under Robolectric (an ImageLoader
whose dispatchers are the caller's), which is what puts real pixels in these renders where earlier
render tests had empty tiles. The fixtures are synthetic portraits; the repo carries no photos.

Batch 1 (21 Oct 2026) added `ViewerChromePolicyTest`, `ViewerIntegrationTest` (filmstrip over a
video, scrubbing across videos, the lift row and its withdrawn rooms, the flier at every state),
`PhotoToPinFlightTest`, `PlaceFlightGeometryTest`, `PlaceUnknownPlateTest`, `LocationPickerTest`, and
extended `PlaceMorphTest`. The three shared files (`ViewerEdgeCaseTest`, `ViewerRenderTest`, `ViewerFixtures`) were
deliberately not appended to (their last edit predates the batch); the integration unit reported
`ViewerEdgeCaseTest` and `ViewerRenderTest` passing unchanged, including the existing video test,
now under autoplay-on and chrome-up-on-open.

Test counts from the original round: 912 offline unit tests before; 960 per flavour after (every new test runs under both
flavours' test tasks; the expand/collapse test is skipped under connect by assumption, so connect
reports 1 skipped). `./scripts/verify.sh` green on both flavours: tests, lint, assemble, and the
three offline gates.

## Housekeeping

- `shared-libraries` (cell-shell) is untouched; the shell's API was used as is.
- No model-identifier text in code, comments or docs.
- `MASTER-PROGRESS.md` is deliberately not edited (the owner adds the row at merge). *(The batch 1
  section of 21 Oct 2026 was added to it separately.)*

## Revisions in batch 1 (21 Oct 2026)

The owner's later reports (#28 the filmstrip over a video, #19 the transition to the map and the
unknown-location plate) reversed or replaced parts of the round above. Nothing here has run on a
device; `docs/device-test/batch-1-checklist.md` lists what only a phone can settle, and
`docs/TRAPS.md` #39 to #41 record the lessons.

### A. The filmstrip stays up over a video (owner #28)

Before: the strip was drawn only under `!asset.isVideo`. Because it drives the viewer live, scrubbing
onto a video removed it from under the finger, cancelled the drag, and put the video's transport in
its place. Now `ViewerChromePolicy.filmstripShown` takes no asset (chrome up, not in picture-in-
picture, the preference, a roll larger than one, not lifting), so the strip is identical for a photo
and a video, and its geometry and bulge (section D above) are unchanged.

- The video transport lifts by `FilmstripGeometry.REST_BAND_DP` (74 dp) when the strip is shown
  (`bottomInset`). The 116 dp bulge is *not* reserved: while the strip is touched the transport dims
  and the video pauses, so the taller band is allowed to cover it. On a short landscape window
  (about 360 dp tall) the second transport row (speed, subtitles, audio, A-B loop, PiP) folds behind a
  "More controls" toggle once the fixed chrome would exceed 40% of the height
  (`shouldCollapseSecondRow`); it only engages when there is an inset, so default usage is unchanged.
- Touching the strip pauses a playing video; the next one does not start until the strip settles.
  The player is not even built until the video has been on screen 250 ms without a finger on the
  strip, so a scrub past several videos decodes nothing.
- The chrome (position chip, transport, strip) is up when the viewer opens on a video, and the first
  time a video is reached when it opened on a photo; after that the person's tap decides, and paging
  to a second video after hiding the chrome keeps it hidden. (Owner decision: a video opened into a
  bare black screen with no visible way to start it is what read as "Fotoz is not playing videos".)
- **Deviation.** The brief said to use the filmstrip's flag for the position chip too. Taken literally
  that hides the chip whenever the filmstrip *preference* is off, so the chip uses the shared
  `chromeShown` rule (chrome up, not in picture-in-picture, not lifting) and the strip adds the
  preference and the roll size. Both give way to the lift editor.
- Neither chip nor strip is drawn inside the picture-in-picture window.

### B. The photo shrinks into the map pin (owner #19)

Before: the "flight" was a second copy of the photo shrinking *inside* the plate, timed to a window of
the drag in which the room's slide (1:1 from above) had the plate still off screen, so it played
where nobody could see. The owner: *"not a pane tilt, rather a shrink-to-appear-on-the-map effect."*

Now `PhotoToPinFlight` is one copy of the photo drawn as an overlay above the shell. It starts on the
parked card at `v = 0`, shrinks and travels over the whole pull (`PlaceMorph.flightT`, smoothstep)
and lands on the pin tile's rectangle, which the room reports once and the overlay then derives
analytically for every `v`. It scales uniformly and narrows a crop window to the pin's square rather
than squashing the photo. At `v = 0.96` the room's real pin, already opaque underneath, is revealed
as the flier fades (opaque under fading, not a cross-fade). The card parks with a flat slide while the
top room is pulled (`ParkStyle` chosen app-side, TRAPS #41), because the flier follows a rectangle.

- **When it does not fly.** A zoomed or turned photo has no honest resting rectangle to lift off from
  (`photoAtRest` false), so the room plays its own in-plate shrink as before. A pin scrolled out of
  reach (`PlaceMorph.canFly` false) fades in place. For a video the flier draws the poster, never
  the live player surface.
- **Privacy kept.** The overlay draws only the photo. No tile is fetched or pre-warmed, and the
  connect flavour's single snapshot still waits for the room to settle (160 ms).
- **Deviations from the plan.** The flight runs over the whole pull rather than a late window
  computed from the geometry, because as its own overlay the shrink is visible from the first pixel
  and a late start would have sent the flier off the bottom with the parked card (the flier stays on
  screen at every `v`, tested). The start rectangle is `PlaceMorph.fitRect` over the *stored*
  dimensions, the same assumption the minimap and Live Text make; an EXIF-rotated photo whose stored
  size is the un-rotated one would start from a rectangle of the wrong shape (a device check).
- **Not matched.** No reference frame exists for any state of the flight; it is judged against the
  owner's words only.

### C. The unknown-location plate (owner #19, second half)

Before: plain background and a sentence, and (in the picker) a spinning world the owner had asked for
on 2026-08-18. The owner on the result: *"weird, and not comforting at all."* The spin is removed for
good (its KDoc says so).

Now `PlaceUnknownPlate` is a still, low-contrast graticule at a neutral coordinate (it carries no
information, so there is nothing to get wrong), a dimmed ghost pin (a 35% dark overlay with no pointer
input, so the flier and the room's pin can be identical at hand-off), the words "No location yet" and
the one explanatory sentence, and an "Add a place" target only where a place may be set by hand. The
flier lands dimmed in that same state. It has to look right on the graticule alone because the offline
flavour never has a map behind it.

- **P0-02 still holds**, now tested at room level: a photo whose location could not be *read* never
  offers "Add a place" or the picker.
- **A dead-UI gap closed.** After a hand-placed location the picker used to be unreachable (the
  drawable branch won), so "Remove this location" and "Also write into the photo file" could not be
  reached. The picker now stays up while editing and a hand-placed plate shows "Placed by you · Change".
- **Picker fixes found on the way.** The picker used to draw a pin at 0N 0E, was unclipped, and was
  1.6:1 inside a 1:1 room; it now draws a pin only for a real location, is clipped, and the pin drags
  only if the touch starts within 36 dp of it (otherwise a drag is left to scroll the room). Typing
  "17." was rewritten to "17.0" under the caret, and a single typed coordinate produced a (lat, 0)
  location; both fixed.


## Revisions in batch 2 (21 Oct 2026)

Nothing here has run on a device; `docs/device-test/batch-2-checklist.md` lists what only a phone can
settle, and `docs/TRAPS.md` #63 records the geography as a rule.

### A. The deviation in section A is gone: one geography (owner #26)

Before: details top, actions on the right, settings at the bottom in the viewer, against the gallery's
settings on top, actions on the right and details at the bottom. The owner's answer: left navigation,
top details, bottom actions, right view options, everywhere. `ViewerScreen` now builds its rooms with
`roomSlots(details = PhotoDetailRoom, actions = ViewerActionsRoom, viewOptions = ViewerSettingsRoom)`
and the viewer has no left room. The pinch shortcuts follow: pinch in opens the details (top), pinch
out opens the actions (bottom). `ParkStyle` (TRAPS #41) is unchanged: `SLIDE` while the top room is
pulled, `SWIVEL` otherwise, which is the bottom actions room's case too.

- **The actions room is two-stage.** A short pull up from the bottom stops on a strip of quick actions
  (Favourite, Share, Edit, Trash; fewer for an externally opened photo or below API 30); a longer one
  shows every action. The strip is the head of the room, under the shell's 72 dp band.
- **The bottom pull zone is lifted above the filmstrip (74 dp) and, for a video with the chrome up, the
  transport (96 dp; 50 when its second row is folded)**, via the shell's `bottomReserve`, so a
  vertical-first drag that starts on the scrubber scrubs and does not open the room. The pull zone is
  the 56 dp band above the navigation bar. Pulling up from ON the filmstrip or transport does not open
  the actions.
- **The view-options room** keeps the old settings room's content (filmstrip, blur, keep awake, the
  slideshow's seconds and shuffle, loop animations, autoplay, pinch panels) and adds "Transparent
  photos". Its GESTURES note now reads, in effect, *drag the photo down for its details, up from the
  bottom for what you can do with it, in from the right for these view options*; the lift hints name
  no edge.
- The actions room's rows are unchanged and still only the icon is clickable (the known defect);
  the slideshow row now reads "Stop slideshow", since pause and resume live in the bar.

### B. The favourite heart in the chrome row (owner #18)

The chrome row is Close, the position pill, the heart. The heart is at least 48 dp, filled when the
photo is a favourite and outlined when not (`Outlined.Favorite` against `FavoriteBorder`: the design
note assumed these were the same hollow heart, and a render showed they are not), with a small pop on
turning on and a TalkBack state description. It is drawn only while the chrome is, and only for a photo
the library holds (`ViewerChromePolicy.favouriteShown`: `catalogueActions` and not `isExternal`); it
works on videos. Not matched: no reference frame exists for it. Check that it does not collide with
the video "Resume from" chip, which now sits beneath the chrome row.

### C. The slideshow bar (owner #16)

Previous, play or pause, next, on a black bar in the shape of the gallery's selection toolbar
(`SelectionToolbarShape`, here a taller `SelectionToolbarTallShape` that keeps the same coves and
lengthens the straight sides by the status-bar inset, so the bar is flush to the top edge and its
glyphs sit below the status icons). Targets are 48 dp (the toolbar's pitch is 46). The chip row drops
by the bar's height while it is up (`chipRowDropDp`) so they stack. Pause is real (a paused flag the
countdown honours); the chrome folds away after three seconds of a running slideshow and comes back on
a tap; neither happens while paused or over a video, whose transport and the bar stay up. This is the
least certain item of the batch: the cove against a real status bar, the stacked height on a 360 dp
phone and in landscape, and the light theme's status icons over black are all unverified on a device.

### D. A backdrop behind transparent photos (owner #17)

`TransparencyBackdrop` is the first child of the zoom layer, so it pans, zooms and turns with the
picture and fills the picture's own rectangle (an SVG's comes from its decoded size). Choices: the grid
(default), white, black (`TransparencyBackground`, persisted in `GalleryPreferences`, read by both
activities). The chrome swatch button appears only while the current photo declares transparency
(`AlphaSniffer` on the first 64 KiB, read off the main thread and cached per photo and modification
time; an unknown answer is opaque), and the choice is also a row in the view options. Not drawn behind
thumbnails or the shrink-to-map flier. A file that declares alpha but is opaque shows the button and
no visible change.

### Tests added in batch 2

New files: `ViewerRoomGeographyTest` (which room each edge opens, the pinch shortcuts, the captions),
`ViewerActionsRoomPeekTest`, `ViewerBottomReserveTest` and `ViewerBottomReserveFoldedTest` (the quick
strip and the pull zone above the filmstrip and transport), `ViewerHeartTest`,
`ViewerTransparencyPolicyTest`, `TransparencyBackdropTest` (the backdrop, rendered in each mode),
`BitmapDecodingAlphaTest` (the `ImageDecoder` path keeps alpha), `ViewerSettingsRoomTransparencyTest`,
`GalleryPreferencesTransparencyBackgroundTest`, `ViewerSlideshowPolicyTest`, `ViewerSlideshowBarTest`,
`SlideshowBarRenderTest` (frames under `app/build/renders/`) and `SlideshowPausedStateTest`.
Extended: `ViewerChromePolicyTest`, `ViewerIntegrationTest`. The render baselines are untracked build
output, regenerated on each run; the shared `ViewerEdgeCaseTest`, `ViewerRenderTest` and
`ViewerFixtures` were not edited. The BitmapFactory (API 26-27) alpha path is not tested: it cannot
pass until the `decodeUpright` defect in TRAPS #34 is fixed.
