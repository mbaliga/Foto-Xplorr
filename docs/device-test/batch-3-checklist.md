# Batch 3 device test checklist

Every device-only claim from the third owner-feedback batch of 21 Oct 2026 (see
`docs/handoff/MASTER-PROGRESS.md`, "Batch 3"), in the same shape as `batch-2-checklist.md`. This
sandbox has no device or emulator, so **none of these is claimed as verified**: each section says what
the JVM and Robolectric tests cover, and the boxes are for what those cannot see (a real finger, a
real fling, real insets, the GPU, the system chooser, process death, how a thing *feels*). Robolectric
reports zero system-bar insets and drives synthetic touches, so every "clears the navigation bar" and
every "feels right" line below is reasoned and rendered, not seen. Check each box on a real phone, on
**both flavours** where a line says so, before relying on the corresponding change.

The owner items being answered: **#21** one continuous pinch grid, then postage-stamp months, "still a
long and continuous scroll". **#25** *"Timeline should be allowed to be toggled on/off and the
placement decided - can be left or right with a vertical scroll, or top and bottom with a horizontal
scroll ... photos top to bottom or bottom to top or left to right or right to left ... the photo grid
needs to move to make space for it ... group and separate the photos by month."* **#27** the round
grid/map toggle and time as a filter. **#3 and #14** what a long press does.

Suggested devices: a current phone on gesture navigation, one on three-button navigation (a 48 dp
bar), a small phone (about 640 dp tall, 360 dp wide), a phone with a camera cutout, a tablet or
ChromeOS window for the pinned rail, and a keyboard or mouse for the Ctrl lines. Use a prepared
library of **about 22,000 photos spanning 10 years or more** for the scroll and fling lines (see
`fastlane/metadata/android/en-US/images/README.md` for the small set), plus a handful of photos with
no capture date. Install the **Connect** build for every line marked Connect; everything else is
the Offline build.

## 1. The zoom grid: pinch across every rung

Sandbox coverage: `GridZoomTest` (the accumulator both ways, closed ends, no flicker, masonry and
picker limits, wrap, legacy 7 to 6), `GalleryPreferencesGridZoomTest` (default 3, persistence, clamp,
migration, reading never writes), `ZoomGridRenderTest` (a synthetic two-finger spread and pinch
reaches the zoom callbacks at rungs 1, 3, 6 and Stamps), `KeyboardShortcutsTest` (Ctrl plus and
minus), `TimelineRunsTest`.

- [ ] Pinch **out** (fingers together) one rung at a time from 1 column through 6 and on to **Stamps**,
      then **spread** all the way back to 1, on a real phone. Each step should feel deliberate (a
      quarter change in finger spread per rung was chosen, not tuned) and never skip two rungs on a
      small movement or flicker between two when the fingers rest.
- [ ] Pinch **in from Stamps** straight to 6 columns and on down: it must work (the detector is on the
      one grid, so no rung lacks a pinch). Pinching further out past Stamps or further in past 1 does
      nothing, and reversing at once moves at once.
- [ ] **Anchor on a rung change.** Scroll so a photo is at the top, change the rung each way, and the
      same photo (or its row) stays at the top. Specifically, scroll Stamps until a **month header is
      the top line**, pinch to 6 columns with the timeline off (flat grid): the first photo of that
      month must stay at the top, not land several screens later (the audit fix; unit-tested in
      `ZoomAnchorTest` only). Repeat Stamps to day-grouped columns and back.
- [ ] Verify the re-anchor lands before the first frame of the new layout, with no one-frame flash at
      the wrong place.
- [ ] **Stamps look.** The dotted perforated edge (one dashed stroke with round caps) reads as round
      holes on a real GPU, the paper margin is legible, and a stamp (~36 dp) is tappable with a thumb.
      Headings read "September 2026, 143 items" in TalkBack. A sensitive stamp is blurred when Blur
      sensitive is on.
- [ ] **22k scroll.** Fling the whole library at 3 columns, at 6 columns and at Stamps: no dropped-frame
      judder you can see, thumbnails arrive, memory stays sane. Fling **Stamps** end to end in one go.
      (Run the macrobenchmark at 3 columns and at Stamps if you can: `-Pfotoz.benchmarks`.)
- [ ] Ctrl plus the mouse wheel, Ctrl and plus, Ctrl and minus (hardware keyboard or touchpad) step the
      rungs; a bare plus or minus does nothing; arrow keys step by the columns across at Stamps
      (approximate under month headers).
- [ ] The density pill steps 1 to 6 then Stamps and wraps; TalkBack says "3 columns" or "Stamps" as its
      state. After a pinch the pill, the view options room's **Zoom** row and Settings > Appearance
      **Grid zoom** all agree. Kill and relaunch: the rung is the one the last pinch settled on.
- [ ] Upgrade path: an install that had 7 columns stored opens at 6 columns (not Stamps); 2 to 6 are
      unchanged.
- [ ] Settings > Media **Fill the tile** off (masonry): pinch steps 1 to 6 and never shows Stamps,
      headers, the strip or the pill's position; the Zoom stepper stops at 6.
- [ ] The photo picker (from another app) opens with at most 6 columns and no Stamps.
- [ ] Pinching with a tile's hold-preview up (a finger already held on a tile): no stuck preview.

## 2. Long press does

Sandbox coverage: `LongPressResolverTest` (setting by selection matrix, Nothing inert while selecting,
Trash fallback, legacy mapping, accessibility labels), `GalleryPreferencesGridLongPressTest`,
`TileLongPressGestureTest` (16 compose tests through the real grid: each setting's outcome, selection
overrides it, the setting is read at gesture time, tap uses the latest `onOpen`), `PaneRenderTest`.

- [ ] Settings > Media **Long press does**, under each of the six settings, hold a tile on a real thumb:
      Quick preview shows the preview after the system long-press timeout and removes it on release;
      Select toggles it and starts a selection; Favourite toggles the heart; Share opens the system
      sheet; Edit opens the photo editor (the video editor for a video); Nothing does nothing. Try each
      on a normal tile and on a **Stamps** stamp.
- [ ] One light haptic tick on every outcome except Nothing (and none if the system touch-feedback
      setting is off); not annoying while scrolling.
- [ ] With a selection running, a hold on another tile toggles it under every setting except Nothing.
- [ ] Hold-to-Edit while an earlier edit is still saving: "Finish saving your edit first" appears and
      the editor does not switch.
- [ ] In the Trash, a Favourite, Share or Edit setting behaves as the preview.
- [ ] Upgrade path: an install with the old "Peek on long press" off shows **Select** preselected; one
      with it on (or never touched) shows **Quick preview**.
- [ ] Sort or filter, then tap a tile: the viewer pages through the **new** order (the stale-closure
      fix).
- [ ] Photo picker: a hold behaves as a tap.
- [ ] TalkBack: a tile reads its photo name; double-tap opens; the actions menu or long-click offers the
      configured action (Select when the setting is the preview; absent for Nothing).

## 3. The map: disc, rail word, Back

Sandbox coverage: `MapModeTest` (rail words with Map before Places, `railChoice`, the toggle and range
rules), `MapToggleFabTest`, `GalleryMapModeTest` (disc toggles grid and map, Back leaves the map, Back
order with the time panel, range survives grid-map-grid), `StampMapEmbeddedTest` (stamps do not move as
the range narrows; embedded has no `BackHandler`), `PhotoMapPaneTest`, `PaneBackTargetTest`.

- [ ] The round **map disc** at the bottom left toggles to the map and back ("Show map" / "Show grid" in
      TalkBack). On a 360 dp phone the disc, the pill (readout, time button, density button) and the
      search disc do not collide, and the disc clears the navigation bar. On a tablet with a pinned or
      collapsed rail the disc and pill are inset by the rail's width.
- [ ] The rail has a **Map** word between Favourites and Places, on the pull-out rail and the pinned
      rail. Choosing it shows the map of the **current view** (the album
      you were in, the search, the range). Choosing a destination word leaves the map. From **Places**
      or **Protected**, choosing Map lands on Photos; neither offers the disc.
- [ ] **Back** order: with the map up, one Back returns to the grid (route and destination kept); with
      the time panel up over the map, the first Back closes the panel; a search typed over the map goes
      before the map; inside an album, Back from the grid leaves the album. Escape follows the same
      order.
- [ ] A pin or stamp opened from the map goes to the viewer; Back from it lands on the **grid** (the map
      flag is session-only, by decision) with the time range intact. Decide whether that is acceptable.
- [ ] Process death with the map open: relaunch lands on the grid, range restored.
- [ ] **Offline:** the stamp map embedded in the browser: pins stay still as the range changes, the
      empty state and the place poster rise above the pill and the discs, thinning at 22k photos looks
      sane, the pill reads "N located" or "N of M located".
- [ ] **Connect:** the street map appears with its attribution and logo (bottom left) and the compass
      clear of the disc and pill and still legible; dragging the range slider and releasing does not
      stutter the map. With the shell's swivel (a rail peek), the MapLibre surface may draw untransformed:
      if so, `MapLibreMapOptions.textureMode(true)` is the fix and was not applied. The top-right map
      toggle and compass spacing (`EMBEDDED_UPPER_CHROME_DP`, left over from the removed switcher) look
      right.

## 4. Connect: the street map never builds from a scrub

Sandbox coverage: `RailTurningTest` and the `mapPaneSettled` rule (pure decisions only); no test mounts
a MapLibre view or watches the network.

- [ ] **Pull-out rail:** open the left room on the Connect build, scrub the wheel onto **Map**, rest
      there, then slide off to another word. With a network monitor (or the device's data-usage
      counter) running, no style or tile request is made. Release on Map and close the room: the map
      appears once the shell is at rest and then stays when a room is opened over it.
- [ ] **Pinned rail** (a wide window): put a finger on the wheel, rest on Map long enough to commit, hold,
      then slide off to another word: **no** street-map style or tile requests. Lift on Map: the map
      appears after the wheel settles. **Tap** the Map word: the map appears at once.
- [ ] The Offline build never makes a request in any of the above (it has no network permission).

## 5. The time range

Sandbox coverage: `TimeRangeTest` (month snapping, year rollover, DST in New York, swapped handles,
half-open membership, saver round trip), `TimeRangeSearchTest` (range intersects search and a date
phrase; a null range leaves all nine destinations identical), `TimeRangeBarTest`, `GalleryMapModeTest`.

- [ ] The pill's time button opens the panel: year chips, a month slider, **All time** and a close
      button. The slider is usable on a real thumb (month rounding, handles crossing) and the range is
      applied on release.
- [ ] The chip shows the range and the count (for example "Mar 2023 – Sep 2024 · <count>"), has a 48 dp
      clear button, and narrows the grid and the map together. It sits clear of the bottom 56 dp pull
      zone and the quick-strip hint.
- [ ] The range survives a destination or album change, opening a photo and Back, and process death; it
      does not survive a relaunch. It is hidden on Places and Protected.
- [ ] Open the panel, then long-press a tile with the setting on Select: the panel hides and **one Back**
      clears the selection. Open the panel, then switch to Settings from the pinned rail: Back closes
      Settings (not swallowed by the hidden panel).
- [ ] Select all and Trash with a range on act on the range-filtered list while the chip is hidden by
      the selection bar (accepted; the count text does not mention the range). Judge whether that is
      acceptable.
- [ ] A library with no dated photos shows "Nothing in this view has a date to filter on."

## 6. Back from a photo lands where you left

Sandbox coverage: `GalleryViewerRoundTripTest` (a search survives the round trip when the gallery is
held, and does not without the holder). It asserts the search text only, not scroll or destination.

- [ ] Open a photo from a non-default destination, inside an album route, after scrolling far down and
      typing a search: Back from the viewer lands on the same destination, route, search text and
      scroll position.
- [ ] Open the editor from the viewer, save or cancel: Back to the grid still lands in the same place.
- [ ] The viewer's "Search inside this photo" still closes the viewer and lands on the grid with the
      seeded query (route reset to Root).
- [ ] Kill the process while a search is active and relaunch: the search text and selection are
      restored (previously wiped); the grid is at the top.
- [ ] The zoom rung is the stored one; the map is closed; with a **Oldest first** order the scroll
      position is not yanked to the end after the round trip (the anchor token is saved).

## 7. The timeline strip: on, off and on each edge

Sandbox coverage: `ScrubberEdgeTest` (library: edge shape, label pitch, track side, label and bubble
placement, clamping, index mapping unchanged), `TimelineLayoutTest` (`stripShouldShow`, reserve, lift,
route-bar drop, selection rule), `GalleryTimelineStripTest` (a Left and a Right strip coexist with the
rail and view options swipes; scrub reaches the grid), `GalleryPreferencesTimelineTest`,
`TimelineStopsTest`, `PaneRenderTest`.

- [ ] Settings > Library > **Timeline** off: the plain mosaic uses the whole width with no gutter and no
      column headers; on again, the photos make room.
- [ ] **Timeline edge** Left, Right, Top and Bottom each look right: the track on the correct side,
      labels not overlapping, the bubble opening inward and not clipped at the ends, a 44 dp gutter with
      **no photo under the strip**, and the quiet 0.6 alpha acceptable. The owner once called a permanent
      stripe "cluttered": this build makes it permanent while the timeline is on, by decision, so judge
      it.
- [ ] **Gesture navigation:** a Bottom strip clears the gesture bar; the pill, search disc, map disc and
      time-range chip all sit above it (lifted 44 dp). A Left strip does not trigger Android's back swipe
      while scrubbing (the library requests gesture exclusion for it).
- [ ] **Three-button navigation (48 dp bar):** the Bottom strip sits above the bar and nothing is hidden.
- [ ] Rooms still open: from the phone's very edge, a horizontal drag opens the rail with a **Left**
      strip and the view options with a **Right** strip (the drag must start at the edge, on the
      strip); on a tablet with the **pinned rail** a Left strip sits to the right of the rail, not
      over it.
- [ ] With a **Top** or **Bottom** strip, the strip's first and last 56 dp lose a horizontal drag to the
      side rooms (expected, accepted), and a vertical drag that starts on the strip opens the details
      or actions room. Confirm that feels acceptable.
- [ ] With a **Top** strip: the notification shade (`ActivityShade`) is drawn inside the grid area, below
      the strip, whereas the shell's top zone assumes it is at the screen top. Check the shade pull and
      the details room pull still work. The album or collection bar drops 44 dp (but not while the
      search field is open).
- [ ] **Selection:** a Left or Right strip stays and scrubs while selecting; a Top or Bottom strip hides
      under the selection toolbars; the grid does not jump when a selection starts.
- [ ] Switch the sort to **Name** or **Size**: the strip and the gutter disappear and the column
      headers go; back to a date order, they return. Masonry has no strip, gutter or headers.
- [ ] Labels follow where each month really starts (a busy month's end is where it is, not evenly
      spaced). The haptic tick fires at month boundaries and is not buzzing.
- [ ] A scrub on a Left or Right strip reaches the same photos on a **sideways** grid by fraction (a
      mismatched pairing that works); a Top or Bottom strip scrubs a sideways grid left to right.

## 8. Order and the latest end

Sandbox coverage: `TimelineLayoutTest` (`anchorIndex`, `anchorReady`, `anchorToken`,
`timelineSortLabel`), `GalleryScrollAxisTest`, `GalleryTimelineStripTest`.

- [ ] The view options room and Settings > Library > Sort read **Latest at top** (Newest), **Latest at
      bottom** (Oldest), **By name** and **Largest first**; on a sideways grid they read Latest at
      **left** and **right**.
- [ ] **Newest** opens at the top (the latest). **Oldest** ("Latest at bottom") opens on the **newest
      photo, flush at the end** on a real 22k library, after each of: a destination change, entering and
      leaving an album route, a sort change, and re-choosing the current rail word.
- [ ] Oldest does **not** yank the scroll during or after the first scan, or after the viewer round
      trip. First install: the grid sits at the start until the scan finishes, then jumps to the end.
- [ ] Returning from an album to the root grid loses the root's previous scroll position and re-opens
      at the latest end (a behaviour change; judge it). A short library under Oldest hugs the top of the
      screen, not the bottom.

## 9. Month headers on the columns

Sandbox coverage: `TimelineRunsTest` (runs, distinct keys, every scrubber stop opens a run),
`ZoomAnchorTest` (headers differ), `ZoomGridRenderTest`, `PaneRenderTest`.

- [ ] With Timeline on and **Group timeline by** Month, Day and None on rungs 1 to 6: Month and Day show
      a header row above each stretch of photos and None shows none. A fresh install groups by month; an
      install that stored Day still shows day headers.
- [ ] Pinch between **Stamps** (always month headers) and the day-grouped columns keeps the photo at the
      top (the unit test proves the index maths only).
- [ ] The edge scrubber lands on the **header row** of a month (the stop targets the header above its
      first photo), at Stamps and on the columns.
- [ ] Headers cost no visible jank at 22k photos.

## 10. The sideways grid (scroll direction Horizontal)

Sandbox coverage: `ScrollAxisTest` (`gridScrollsHorizontally`, `arrowKeyStep`, `pairsNaturally`),
`GalleryPreferencesScrollAxisTest`, `GalleryScrollAxisTest`, `ScrollAxisRoomsTest`,
`ZoomGridRenderTest` (a horizontal render; headers span all rows; `scrollToItem(last)` clamps flush).
Robolectric is not a real fling or offset.

- [ ] Settings > Library > **Scroll direction** Horizontal (and the flip row in the view options room):
      photos run down a column, then on to the next column to the right, and the grid scrolls sideways.
      Tile proportions look right at 1, 3 and 6 rows (at 1 row the tile is a full-height, 0.9-aspect
      tile).
- [ ] Month headers show as narrow 36 dp columns with the title reading bottom to top and the count at
      the top; legible, and a long month name is not clipped at 6 rows on a short phone.
- [ ] The bottom 88 dp band: the pill and both discs sit in the empty band under the bottom row and cover
      no tiles, including with a navigation bar (the band does not add the inset) and with a Bottom
      strip (the pill lifts 44 dp).
- [ ] **Edge zones.** A horizontal drag that **starts** in the first or last 56 dp of the screen opens the
      rail or the view options instead of scrolling the grid (accepted); a drag that starts further in
      and runs out is unaffected. Is that tolerable, or should the sideways grid be inset?
- [ ] Pinch across all rungs on the sideways grid, including pinching up to **Stamps** (it flips to a
      vertical Stamps sheet) and back (it returns to sideways), and the scroll position stays near the
      same photo on each flip.
- [ ] **Latest at left / right:** Newest opens at the left edge; Oldest opens flush at the right end.
- [ ] Hardware keyboard on a sideways grid: Left and Right jump a column (the rows count), Up and Down
      move one photo.
- [ ] The album or collection bar overlays the top row of the sideways grid without hiding the first
      column awkwardly. The photo picker is unaffected (it uses the plain grid).
- [ ] RTL layout: the grid flows right to left, so "Latest at left" is wrong there. Not handled; confirm
      and decide.

## 11. Undated photos

- [ ] Photos with no capture date (only a modified time): they
      sort together at one end, and a month with undated photos may head **two** runs (two headers with
      the same name). The grid does not crash (header keys carry the run index), the strip's labels still
      land on a header, and the time range counts them by modified time. Judge whether the double header
      is acceptable.
- [ ] A photo with no usable date at all (a timeline time of zero) never appears inside a time range
      and does not stretch the month slider back to 1970.

## 12. Accessibility

- [ ] TalkBack on the grid: tiles read their name, double-tap opens, headers read as headings with the
      count, and the zoom has a screen-reader route (the density pill and the Zoom stepper announce the
      rung). Ctrl plus and minus are not the only way.
- [ ] TalkBack on the strip: it is reachable and scrubbable, or the grid is still fully usable without it.
- [ ] The map disc, the time chip, its clear button and the panel's close button read sensibly and have
      at least 48 dp targets.
- [ ] Settings rows ("Timeline", "Timeline edge", "Scroll direction", "Long press does") read their
      state; the view options room's timeline group reads each edge as selected or not.
- [ ] Large font scale on the small phone: the pill, chip and Settings rows do not clip.

## 13. Store listing and release

- [ ] Before upload: `versionCode` 5 matches `changelogs/5.txt` (which now covers batch 3 too and is
      within the store's 500 characters); `shared-libraries` PR #17 (pin `18fc20e`) is merged first with
      a merge commit (TRAPS #2); `docs/store/play-console.md` still names the old pin `1f33d10`.
- [ ] The store description's Browse lines (timeline on any edge, the zoomable grid, the map) match what
      the installed build does. No store screenshots exist yet (`images/README.md`); when they are shot,
      the first one is the grid with the timeline on and a rung that shows the month headers.
- [ ] Have the owner read the new Settings and room wording (MASTER-PROGRESS, "Batch 3", residual), and
      the Connect network statement now that the street map is one tap from the grid.
