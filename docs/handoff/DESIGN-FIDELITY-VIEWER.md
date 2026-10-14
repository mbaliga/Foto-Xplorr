# Design fidelity: the viewer (filmstrip, details room, map plate, zoom minimap)

Branch: the Phase 1 foundation branch (worked in an isolated worktree, merged by the owner).
References: the owner's screenshots `5.png` (viewer + filmstrip) and `6.png` (details room), both
440 x 956 px, so 1 px = 1 dp. The renders under `app/build/renders/viewer-*.png` are 2x (xhdpi), so
1 dp of the reference = 2 px there; every comparison below was made after a 2:1 box downscale.

This file records every judgment call, every reversal of a previously documented decision, what
could not be matched and why. The master progress row is the owner's to add at merge.

## What changed, surface by surface

### A. Details is the pull-DOWN room (a documented deviation from the app's geography)

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

The flight: `PlaceMorph.heroScale` starts the photo at 92% of the plate's width (4.6x the pin on a
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

**No location** draws the plate's space as plain background with the one explanatory sentence
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
alpha and 17 dp below its resting place. No reference frame exists for mid-flight.

### `viewer-5-details-no-location.png`

The plate's space is background with the sentence; everything else as in frame 3.

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

Test counts: 912 offline unit tests before; 960 per flavour after (every new test runs under both
flavours' test tasks; the expand/collapse test is skipped under connect by assumption, so connect
reports 1 skipped). `./scripts/verify.sh` green on both flavours: tests, lint, assemble, and the
three offline gates.

## Housekeeping

- `shared-libraries` (cell-shell) is untouched; the shell's API was used as is.
- No model-identifier text in code, comments or docs.
- `MASTER-PROGRESS.md` is deliberately not edited (the owner adds the row at merge).
