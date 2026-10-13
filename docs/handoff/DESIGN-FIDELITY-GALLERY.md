# Design fidelity: the gallery's chrome (Oct 2026)

Two surfaces reworked to the owner's reference screenshots, plus the root grid's search entry.
Everything here was measured off the screenshots with Pillow (they are 440 x 956 px, which is
the render qualifier `w440dp-h956dp`, so one px is one dp) and verified by rendering the real
composables to PNG on the JVM (`GalleryRenderTest`, `app/build/renders/gallery-*.png`) and
measuring those the same way. Nothing below was eyeballed.

Branch: `claude/fotoz-p1-foundation` (worktree branch, merged by the owner). Shared libraries
(`shared-libraries/cell-shell`, `hyle-design-system`) untouched.

## A. Activity shade, collapsed state (`3.webp`, `4.webp`)

What the screenshots draw, in dp, and what the strip now draws:

| | reference | built |
|---|---|---|
| strip | black, 28 tall | black, 28 tall (`COLLAPSED_HEIGHT` 14 → 28; `shadeHeight` and the shell's `topReserve` move together, as the model's own doc requires) |
| track | 8 tall at y 10..17, pill, grey lightening top→bottom `#383838`→`#4D4D4D` | same; one remembered `Brush.verticalGradient` per track |
| fill | 4 tall at y 12..15, pill, inset 2 inside the track, width = progress of the inner track | same; `animateFloatAsState`, read in the draw lambda |
| three jobs | 12 in from each edge, slots on a 148 pitch = 120 wide + 28 gap; per slot `[track 104][4][glyph 12]` | same |
| one job | one track the full width, **no glyph** | same — the one-job screenshot has no mark, so the colour alone names the job |
| glyphs | white, 12 px, vertically centred on the track; floppy / four-arrow cross / two pages | traced as 16-unit `ImageVector`s with the ink in the middle 12 (`ActivityGlyphs.kt`), drawn at 16dp in a 12dp slot |
| colours | save `#4DFF00`, move `#40A0FF`, copy `#FFA100` | `BACKING_UP` `#4DFF00`, `MOVING` `#40A0FF`, `COPYING` `#FFA100` |

Measured match, render over reference (mean absolute RGB difference across the 440 x 28 strip):
**4.05/255** for the three-job strip, **2.49/255** for the one-job strip. Residuals:

- Three-job: every slot in the render sits **1 px left** of the screenshot (track 12..115 vs
  12..116, glyph 120..131 vs 121..132 for the first slot; the third slot's glyph is exact). The
  screenshot's own slot ends land on 133 / 281 / 429, i.e. its inset is 12.5 — a sub-pixel the
  webp downscale produced, not a dp the layout can express.
- One-job: the screenshot's track runs 10..430 (inset 10) where the three-job one runs 12..428
  (inset 12). The two references disagree by 2 px; the three-job one is the richer source and
  sets the constant, so the one-job strip is **2 px narrower at each end** than `4.webp`. The
  fill's end lands on the same pixel (174) because the test fixture's fraction was chosen for it.
- The `move` glyph's shafts are 4 units wide: at 12 px the reference's cross is a solid centre
  with four corner heads, and that is what renders. The `copy` back-page outline is a crisp 1 px
  line where the screenshot's is a blurred one straddling two columns.
- `MOVING`'s blue: the task brief's sampled `#539CE3` is an anti-aliased edge pixel. The fill's
  interior rows read `(62..65, 159..160, 255)` in `3.webp`, so `#40A0FF` is the colour.

Kind colours, one colour = one meaning (nothing shares a hue, nothing borrows the error salmon
`#FFB4AB`, the track grey or the glyph white): `SCANNING` cyan `#00E5FF`, `RECOGNISING` magenta
`#E040FB`, `EXPORTING` yellow `#FFE600` — hues between the three reference hues, not next to any.
Their glyphs are Material's `Sync`, `Face` and `Upload`; no reference draws them.

Judgment calls:

- A failed job with an unknown total draws a full run of the error colour: "stopped at an
  unknown point" has no honest width, and an empty grey track reads as nothing running.
- The expanded hero's bar uses the same track (8dp, inset fill) so there is one track design,
  not a reference one and a leftover one.
- Collapsed slots are keyed by activity id, so a job finishing hands nothing to the job that
  shifts into its index — the per-slot progress animation stays with its own job.

## B. Navigation rail (the swivel reference)

The swivel reference was not on disk at the time of this work (the owner was asked to re-send
it; nothing new appeared in the images directory). It was built from the brief's reading of it:
black ground; the destinations as large bold words, ~44sp, left edge at x≈48, row pitch ~80dp;
the current word pure white with a ~16dp filled square to its left; the others dimmed by their
distance from it (±1 `#9A9A9A`, ±2 `#707070`, ±3 `#4A4A4A`, ±4 `#2E2E2E`); no covers.

Measured in `gallery-4-rail-open.png`: pitch 80.0dp; word boxes start at 48 (glyph ink at
49–51 with side bearings); greys per row exactly `#2E2E2E`, `#4A4A4A`, `#707070`, `#9A9A9A`,
`#FFFFFF`, `#9A9A9A`, `#707070`, `#4A4A4A`, `#2E2E2E`, `#2E2E2E`; bullet a 16 x 16 square at
x 16..32, centred on the word's cap height (446.5 vs 446.75). Unverified against pixels, because
there are none yet: letter-spacing (set to −0.5sp, the shared rail's), the bullet's exact gap to
the word (16, a 16-grid: 16 | bullet | 16 | word), and the typeface — Hyle Grotesk Bold, which the
owner's 2026-08-18 direction ("Only use Hyle Fonts") requires and the shared rail ignores.

**The shared `WordWheelRail` could not be restyled through its parameters.** Its type (34sp on a
56dp pitch, bold only on the focus line), its fade (0.62 − 0.1·d, floor 0.22) and its marker box
are private constants. So the rail is now `DestinationWordRail` (`gallery/DestinationWordRail.kt`),
an app-local re-cut of the same wheel; `shared-libraries` is untouched. The wheel's behaviour is
kept because the owner's reference video set it and a still cannot unset it: the run turns under
the finger, the fade is read off the fractional scroll so it moves continuously, release snaps to
the nearest row and selects it, and the marker is pinned to its row and travels on a selection
change (420ms, lagging the 320ms settle, stretching along its path). Per-frame values — each
row's alpha, the marker's offset/alpha/stretch — are read in `graphicsLayer`/`offset` lambdas;
with every word bold there is no per-frame text re-layout at all (the shared rail re-lays-out as
its weight steps). Nothing scroll-derived is read in composition, so there is no `derivedStateOf`
to add.

**Covers → bullet: a reversal of the 2026-08-14 decision.** The owner then replaced the rail's
bullet with each destination's own covers in the marker gutter ("the few images are indication
enough… I would rather have them on the left and for them to do the movement dance"). The
2026-10 reference draws the bullet and no covers; a reference is a later decision than a
direction, so this follows it. `RailThumbnailCascade` and the `RAIL_MARKER_*` constants are
deleted (nothing else used them), and `DestinationRailPanel` no longer takes `GalleryUiState`
(it only ever took it to fetch covers); `PersistentNavRail` in `GalleryScreen.kt` dropped the
matching `galleryState` parameter. The dance survives: the square still travels across the rows.

Not changed, flagged: `PINNED_RAIL_WIDTH` (260dp, the wide-window pinned rail in
`GalleryScreen.kt`) was sized for 34sp words; at 44sp "Screenshots" (~266dp of ink) will
ellipsize inside it. The phone's pull-out room (368dp wide) fits every word.

### The swivel: shared-shell geometry versus the reference

Rendered through the real `SpatialShell` in `ParkStyle.SWIVEL` (`gallery-4-rail-open.png` at
full open, `gallery-5-rail-mid-reveal.png` at `hProgress` 0.5). Measured at full open:

- The parked card's visible band is **72dp** (x 368..440) — `BAND_DP` in `SpatialShell.kt`,
  private. The brief's reading of the reference is **~60dp**.
- The card is drawn at scale 0.9 (`PARK_SCALE_DROP`), hinged on its visible (left) edge and
  turned 22° (`PARK_SWIVEL_DEG`) to face the room, camera at 3 card widths
  (`PARK_CAMERA_DISTANCE_CARDS`). So the hinge edge of the band is **860dp tall** (y 48..908 —
  measured 843.5 at x = 370 because the 20dp park corner rounds it) and the screen edge, 72dp
  nearer the camera, is **~878dp tall** (y 38.5..917): the outer edge IS the taller one, as the
  reference has it, but by 2%, not to full height. The brief reads the reference as the near edge
  being full-height, which would need no shrink (scale 1) and/or a much stronger perspective.
- The shell also draws things the reference does not: a 1dp accent ring around the card
  (`PARK_RING_ALPHA` 0.6), a 4 x 48dp accent grip pill 34dp in from the band edge, and a 62% black
  scrim over the card (`PARK_SCRIM_ALPHA`) that dims the thumbnails the reference shows plainly.

All of these are `SpatialMotion` contract constants ("an app that quietly tunes these has broken
the thing this module exists to guarantee") or private shell constants. Not forked, not tuned;
listed here for the owner to decide at the constellation level once the reference is on disk.

## C. Root grid chrome (`7.webp`)

The reference shows the grid and one control: a black 48dp disc at the bottom right (x 368..416,
y 888..936 → 24 in from the right, 20 up from the bottom) with a white magnifier (a 23px ring in
a 2.5px stroke, a 3px stub handle to the south-east; glyph bbox 381..404 x 901..924). Built as
`SearchFab` (`hyle/SearchFab.kt`), drawn rather than Material's `Search` (whose ring is two
thirds of its box with a long handle). Measured in `gallery-6-grid-root.png`: disc 368..415 x
888..935, glyph bbox **381..404 x 901..924** — exact.

Judgment call, the pill: `FloatingPillControl` (caption readout + density toggle, owner-directed
on 2026-08) is not in `7.webp`. Removing it is a product decision outside "search entry
placement/shape", so it stays, minus its search button (one entry, not two). It now stops 12dp
short of the disc (`end = 24 + 48 + 12`), sits on the disc's baseline (bottom 20) and is 48dp tall
to share its centre line (was 52 with a 10dp margin). The disc overlaps the right-edge timeline
scrubber's 44dp strip by 20dp; it is drawn above it, as the reference places it.

Residuals: the reference's grid is three columns of 144 x 160 tiles on a **4dp** gutter; the
app's `TimelineScreen`/`MediaGridScreen` use `GRID_GUTTER = 1.dp` and square tiles. Grid content,
not chrome — not changed. (`ReferenceGridStandIn` in the render test uses the reference's
geometry so the chrome comparison is like for like.)

## Tests

- `render/GalleryRenderTest.kt` (new, 6 renders): `gallery-1-shade-three`, `gallery-2-shade-one`,
  `gallery-3-shade-notification`, `gallery-4-rail-open`, `gallery-5-rail-mid-reveal`,
  `gallery-6-grid-root`.
- `hyle/ActivityShadeSurfaceTest.kt` (new, Robolectric, NATIVE graphics so the Canvas lambdas run):
  zero / one / three / eight jobs in every state (eight shows "and 4 more"), a failed job and one
  with no total, a job finishing mid-animation, 24 state changes at 16ms, tap cycling.
- `gallery/DestinationWordRailTest.kt` (new, pure): the stops are the reference greys, symmetry,
  the floor to 40 rows, monotonic, fractional interpolation, marker stretch.
- `gallery/DestinationWordRailComposeTest.kt` (new, Robolectric): all ten words, selection at
  the first / middle / last row, unknown id, empty list, tap selects, a selection change from one
  end to the other interrupted mid-flight.
- `hyle/ActivityShadeTest.kt` unchanged and green (it reads the constants symbolically).
