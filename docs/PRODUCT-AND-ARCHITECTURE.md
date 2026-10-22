# Fotoz — product and architecture

A local-first photo and video gallery for Android. Everything it does by default happens on the
device: the library is indexed from `MediaStore` into a local catalogue, recognition runs on
bundled ML Kit models, and no image ever leaves the phone unless the user explicitly shares it.
The one network-capable feature (remote AI) is opt-in, off by default, and requires the user to
supply their own provider key.

- **Package**: `com.fotoxplorr.app` (debug builds: `com.fotoxplorr.app.debug`)
- **UI**: Jetpack Compose, Material 3 for controls, `dev.aarso:hyle` for design tokens
- **Navigation**: `dev.aarso:cell-shell` — the constellation's shared spatial shell
- **Reliability**: `dev.aarso:crash-recovery` — device-only crash capture and recovery screen

---

## 1. Features

### The library

Fotoz does not browse `MediaStore` live. It maintains its **own catalogue** in a local
SQLite database (`SqliteMediaRepository`), populated by scanning `MediaStore.Files`. That
indirection is what makes the rest of the app possible — favourites, tags, collections, archive
state and privacy flags are all keyed to catalogue rows and would have nowhere to live otherwise.

Scanning is **incremental**. A full scan runs on first launch and on explicit refresh; everything
else is a delta against a persisted watermark. See §4 for why this matters more than it sounds.

### Nine destinations

The primary information axis. Each is a lens over the same catalogue, not a separate store
(`HyleDestination`). There used to be a tenth, Audio, with its own library, player and tag
editor; it was removed by owner decision in October 2026, and with it the audio permission and
the media-playback service. Fotoz is a photo and video app. A saved default view of Audio opens
on Photos, and the old audio database and scan preferences are deleted on first launch
(`LegacyAudioCleanup`); a user's audio files are never touched.

| Destination | What it shows |
|---|---|
| **Pets** | Photos where the on-device classifier found a cat, dog or other pet |
| **People** | Photos with detected faces, grouped into clusters |
| **Identity** | Photos heuristically identified as identity documents |
| **Screenshots** | Screenshots, detected by path and dimensions |
| **Photos** | All still images |
| **Videos** | All video |
| **Favourites** | User-marked favourites |
| **Experimental** | The extras page (`experimental/`): compass view, 3D wall, gallery preview, look-alike grouping, AI keys. The enum constant is still `PLACES`; only the label changed |
| **Protected** | Password-protected folders |

The map is not a tenth destination. It is a **mode of the browser**: a black disc at the bottom
left (`MapToggleFab`, "Show map" / "Show grid") and an extra **Map** word on the rail, placed before
Experimental, swap the grid for the same photos on a map, with the album, search and time range you
were in (`gallery/MapMode.kt`). Neither Experimental nor Protected offers the disc or the Map word's
effect: choosing Map from either falls back to Photos. So the rail has nine destination words plus Map.

### Smart albums and organisation

Beneath the destinations sit **smart albums** — Favorites, Recent, Videos, Screenshots, Animated,
Large files, Duplicates, Sensitive, Archived, Trash, Untagged — plus user-created **collections**
and free-form **tags** (`LibraryStore`). Device folders are browsable as albums, and any of them
can be **password-protected**: a protected folder is hidden from every listing until unlocked, and
the window sets `FLAG_SECURE` while one is open so it cannot be screenshotted or appear in the
recents thumbnail.

### On-device recognition

`RecognitionIndexer` runs ML Kit face detection, a pet classifier and identity-document
heuristics over the catalogue, storing results in `RecognitionStore`. It is **incremental by
construction** — it only visits assets whose stored result is missing or stale — and it is
bundled-model only, so it has no network path at all.

Face descriptors are clustered locally (`FaceClustering`) to produce the People strip.

### Media operations

Trash, restore, permanent delete, rename, copy-to-folder and move-to-folder. Every destructive
operation on Android 11+ goes through the platform's own confirmation dialog
(`MediaStore.createTrashRequest` / `createDeleteRequest` / `createWriteRequest`) — the app never
deletes media behind the user's back, and on older versions it refuses rather than working around
the absence of that dialog.

**Metadata-clean sharing** (`CleanShareExporter`) makes copies with common EXIF stripped, so
sharing a photo does not silently share where it was taken.

### Spatial and 3D experiences

- **Map** — in Map mode, a native vector map (MapLibre, Connect build) with clustering, pitch/bearing,
  hillshade and 3D building extrusions, or the tile-free stamp map (Offline build), populated from
  geotags read locally by `GeoMetadataRepository`.
- **Experimental** — a hub of cards (`experimental/`) that opens one full-screen experiment at a time.
  The host owns the open id (saved when the entry is `restorable`), Back, and the pane ink; a new
  experiment is one `ExperimentEntry` in `builtInExperiments`, and reads photos only through
  `ExperimentEnv` (the already-filtered list, re-checked at export).
- **Photo scenes** — OpenGL-rendered 3D arrangements of the library (`experience/`, `spatial/`),
  including an orientation-driven photo wall.

### Optional remote AI (off by default)

A bring-your-own-key path supporting OpenAI Responses, OpenAI-compatible chat, Anthropic Messages
and Gemini. Keys are held in an `EncryptedSecretStore`; no logging interceptor is installed on the
HTTP client, so a key cannot be logged. Providers are disabled until the user enables them
individually. The local similarity explorer (`SimilarityIndexer`, `EmbeddingRepository`) is
separate and stays on-device.

### Backup

Local metadata export/import as JSON — collections, tags, favourites and sensitivity flags. It is
the app's own organisational layer, not the photos.

---

## 2. Interaction patterns

Fotoz uses the **fonebrew spatial pattern**, shared across the constellation via
`dev.aarso:cell-shell`. The full specification is in [`fonebrew-navigation.md`](fonebrew-navigation.md);
what follows is how this app applies it.

### Rooms, not screens

The grid is *home*. Four rooms are parked off its edges. Dragging from an edge lifts the grid,
shrinks it slightly, rounds its corners and parts it to reveal the room behind — the grid stays
alive and visible the whole time, and dragging it back is the way out.

A room is **not a back-stack entry**. Switching to one pushes nothing; Back closes it.

**One geography, in the gallery and the viewer alike** (`ui/RoomGeography.kt`; owner, batch 2
feedback #26):

| Edge | Room | Gallery | Viewer |
|---|---|---|---|
| **Left** | Navigation: where you can go | The destination rail (nine destinations plus a Map word) and a Settings row | none: the viewer has no left room (Close or Back returns to the grid) |
| **Top** | Details: what this is | `GalleryInfoRoom`: counts for the current view, and the library | `PhotoDetailRoom`: the photo's facts, camera card, place plate, caption, tags |
| **Bottom** | Actions: what you can do | Select photos, New collection, Rescan the library, and the selection's actions | Favourite, Slideshow, Share, Edit, Lift subject, Open with, Rename, Convert, Mark sensitive, Move to trash |
| **Right** | View options: how it is shown | Zoom (1 to 6 columns, then Stamps), scroll direction, the sort worded as where the latest photo sits, the timeline on or off and its edge, Videos, Blur or Hide sensitive | Filmstrip, blur, keep awake, transparent-photo background, slideshow seconds and shuffle, loop animations, autoplay, pinch opens panels |

Why it is arranged so: view options sit at the side because you want to watch their effect,
whereas an action does not need the content in view; actions tilt up from the bottom because that
is the better motion for something you do once and leave. Both screens build their shell slots
through `roomSlots` and open rooms through `RoomGeography`'s constants, and the edge decides which
system bar a room clears (`clearSystemBarsFor`), so moving a kind moves it everywhere.

- **The bottom room is two-stage.** A short pull stops on a strip of quick actions
  (`QuickActionStrip`, the room's head; `RoomGeography.ActionsPeek`); a longer pull, or a flick
  up from the strip, opens every action. Flick down or tap the card to close from either stage.
  The shell adds its own 72 dp head band and the navigation bar's height; the app's strip is just
  its own 72 dp. The gallery's strip depends on context: browsing is Select, New collection,
  Refresh; with a selection it is Share, Collection, Favourite (Unfavourite when every chosen
  photo already is one) and Trash; in the trash, Restore and Delete forever. The viewer's is
  Favourite, Share, Edit and Trash, and only what the photo allows (an externally opened photo
  gets Share and Edit). On a window too short for it (split-screen, landscape at a large font)
  the room is single-stage and shows only the list. The two-stage reveal lives in
  `shared-libraries/cell-shell` (a separate repository; see "Build composition").
- **Left edge → destinations.** A word wheel: room names as large text, the focused row brightest
  and heaviest, neighbours dimming progressively with distance. The fade *is* the position
  indicator, so there is no scrollbar and no selection box. Scrubbing previews the word under the
  focus line and commits it after a short pause (140 ms) while the finger is down; release only
  settles. Tapping the selected word, tapping the parked card, or Back closes the room: selecting
  a destination does not.
- **Settings is a page, not a room.** Choosing it (the rail's Settings row, the pinned rail, or
  the Library card) swings the room closed and puts Settings where the photos were, in
  `PaneScaffold` (black ground, white ink, a back chevron, 20 dp gutters), with nine tabs:
  Appearance, Media, Library, Privacy, Viewer, Data, Background, Pro and About. Albums, Calendar,
  Discover, Library and Tidy up use the same scaffold. While a page is up, the top, bottom and
  right drags are disabled (navigation only) and Back, Escape and the rail lead out of it in one
  order (`paneBackTarget`: room, Settings, a destination screen, the time-range panel, selection,
  search, the map, the route).
- **The top edge is the details room.** The top 56 dp strip is still handed back to the
  notification shade (`topReserve`) at the root grid, where a pull there expands the activity
  strip instead.
- **Pinch shortcuts** (a viewer setting, off by default): pinch in opens the details, pinch out
  opens all the actions. By default pinch zooms the photo; turning the shortcuts on turns pinch zoom off.

Drags are applied **1:1 with the finger** and are reversible mid-flight. Only release animates, on
a 320ms eased curve with **no spring** — a room is a place you arrive at, not something that
bounces into position.

### One zoomable grid

The gallery is a single grid with seven zoom **rungs** (`adaptive/GridZoom.kt`, `gallery/ZoomGrid.kt`).
Rungs 1 to 6 are that many columns of photo tiles; rung 7 is **Stamps**, a sheet of tiny perforated
stamps (about 36 dp, 8 to 24 across by window width) under a heading per month. It is one continuous
scroll at every rung, and a pinch works at every rung in both directions: spread to step towards 1,
pinch to step towards Stamps, one rung per 25% change in finger spread, with the ends closed (there
is no Year or Decade view past Stamps). Ctrl plus the mouse wheel, Ctrl and plus or minus, and the
density pill (a stepper that wraps round the rungs) reach the same rungs. The live rung is held in
screen state and **stored only when a pinch settles** (or a button or key is used), under the
preference `grid_zoom_rung`; a stored column count from before the rungs is read once as the default
(2 to 6 keep their meaning, 7 becomes 6, so nobody's grid changes structure on update). The photo
picker clamps to 1 to 6 and a masonry grid ("Fill the tile" off) steps through 1 to 6 vertically,
with no headers and no stamps.

The earlier Grid / Calendar / Year / Decade / Time view switcher and its zoom ladder are gone.
`CalendarScreen` survives only as a pane reached from Settings.

The grid keeps its place across a rung change by the key of the first visible photo. A change
that drops the month headers while a header is the top line (Stamps to columns without day headers)
would otherwise land many photos later, so `ZoomGrid` re-anchors to the run's first photo
(`reanchoredGridIndex`, TRAPS #70).

### The timeline strip

The edge scrubber is now an option with a place. **Timeline** is a switch (Settings > Library, and a
TIMELINE group in the view options room), default on, and its **edge** is Left, Right (the default),
Top or Bottom. Gliding a finger along it sweeps the whole library, with a bubble naming the month
under the thumb and a haptic tick at each month boundary: this is what makes a 21,000-item mosaic
navigable. Where the strip sits, which way the photos scroll, and the order they are in are three
separate choices (owner #25).

- **A permanent 44 dp gutter.** While the strip is part of the screen the grid is padded by its
  thickness on that side, so photos never sit under it. This reverses an earlier decision, recorded
  in a since-replaced comment in `GalleryScreen`, that the strip would be a transient overlay because a permanent
  stripe was cluttered; the owner asked for the grid to make room. The strip rests at a constant
  60% alpha. `stripShouldShow` is the one decision for strip and gutter alike: timeline on, a date
  order (Newest or Oldest), not masonry, not the map or a pane, and a view with stops. Name and Size
  orders hide it, because a month is not a place in those lists.
- **The strip lives in the grid's own box**, so a Left strip clears the pinned navigation rail. A
  Bottom strip sits above the navigation inset and lifts the pill, the two discs and the time-range
  column by 44 dp; a Top strip drops the album bar (not while the search field is up). During a
  selection the strip is hidden on every edge (`stripDrawn`): the selection controls are drawn over the
  whole screen, not inset by the gutter, and would cover a stop or the track's end; the gutter stays
  so the grid does not jump.
- **Labels are placed at the position their own item index maps to**, so the strip tells the truth
  about where a busy month ends; spacing them evenly would look tidier and lie. The strip claims a
  touch only after the finger passes touch slop along its own axis, so a tap does nothing and a drag
  across it still reaches the shell.
- **Month headers on the columns.** On rungs 1 to 6 a header row appears above each stretch of
  photos only while the timeline is on, the order is Newest or Oldest, the grid is not masonry and
  the Settings choice **Group timeline by** is not None (Day or Month). A fresh install groups by
  month; an install that stored Day keeps Day. The Stamps rung always groups by month and is flat for
  Name and Size.
- **Order is wording over the existing sort.** Newest reads "Latest at top" and Oldest "Latest at
  bottom" ("left" and "right" on a sideways grid); By name and Largest first are unchanged. Oldest
  is not a reversed layout: the list is ascending and the grid **opens at its last item**, on a
  (destination, route, sort) change and when a rail word is chosen (even the current one). It waits
  for the first scan to finish for Oldest only. A short library under Oldest sits at the top of the
  screen, not the bottom.

### Scroll direction

**Scroll direction** (Settings > Library and a row in the view options room; default Vertical,
preference `scroll_axis`) turns the column rungs on their side: a `LazyHorizontalGrid` whose rung is
the number of **rows**, each month a narrow 36 dp full-height header column with its title reading
upward, and tiles as wide as their row's height allows. The Stamps rung and masonry stay vertical
whatever the setting says (the setting is kept and the next column rung is sideways again), and a
sideways grid keeps an 88 dp band along its bottom for the pill and discs. Arrow keys swap their
strides on a sideways grid. Nothing pairs the strip with the axis automatically (a Top or Bottom
strip suits a sideways grid, and the caption says so), and any strip scrubs any grid. The shell's
56 dp left and right edge zones claim a horizontal drag that *starts* in them for the rail and view
options rooms, so a sideways grid cannot begin a scroll in its first or last 56 dp; this is accepted
and is a device check. Right-to-left layouts are not handled.

### Refresh is a shake

Not a pull. The pull-down space belongs to the details room, so refresh moved off the touch plane
entirely: a deliberate shake needs no affordance, no instructional copy, and competes with no
scroll. (The actions room's Rescan row and quick-strip Refresh cell do the same job.) Three
distinct acceleration peaks inside a 900ms window, with a cooldown so one enthusiastic shake
cannot fire twice.

### The floating pill

A dark rounded pill at the bottom, between two black discs: the map toggle at the left and search
at the right. The pill holds a position readout in the middle ("March 2024 · 412 of 21526"), a time
button that opens the time-range panel, and the density stepper (which wraps round the zoom rungs
and announces "3 columns" or "Stamps"). The middle is a *readout*, not a control — the edge scrubber
sets position, and two position controls that can disagree about where you are is worse than either
alone. On the map the readout reads "N located" and the density stepper is left out.

### The map, and the time range

With the map up the browser shows the **current view** on a map: the destination, route, search and
time range, privacy-filtered. The offline build draws the stamp map embedded in the browser; the
Connect build draws the street map, but only once the browser has been at rest with every room
closed and the rail not turning, and it keeps it from then on (TRAPS #71). `mapOpen` is session-only:
a cold start lands on the grid, and Back from a photo opened on the map lands on the grid.

The **time range** narrows the grid and the map together ("time acts as a filter", owner #27). It is
month-granular and judged on the same date the headers and the scrubber use (date taken, else
modified). An always-visible chip shows the range and the count with a 48 dp clear button; the panel
offers year chips and a month slider applied on release. It is held across the viewer round trip and
process death but not across launches, is not reset by a destination or route change, and is hidden
on Experimental and Protected, where it does not apply. A selection's count text does not mention it.

### Selection

What a long press on a tile does is a setting (Settings > Media, **Long press does**): Quick
preview (the default, held while the finger is down), Select, Favourite, Share, Edit or Nothing
(`GridLongPress`, `LongPressResolver`). While a selection is active a hold always toggles the tile,
except under Nothing, which stays inert; in the Trash, Favourite, Share and Edit fall back to the
preview, and no setting offers Trash. An install that had turned "Peek on long press" off starts on
Select. The setting is read when the gesture completes, not captured when the tile appears. Tiles
announce Open (or "Add or remove from selection") and a long-click in the configured action's words
to TalkBack.

A selection starts from the actions room's Select photos, from a hold under the Select setting, or
from Ctrl+A. While it runs the header gives way to a selection bar carrying the bulk actions: share, favourite, archive, mark sensitive,
clean-share, copy, move, rename, tag, add to collection, trash.

### The viewer

**Back from a photo lands where you left.** The gallery is held across the viewer
(`SaveableStateProvider` in `FotoXplorrActivity`), so Back returns to the same destination, route,
search, selection, time range and grid position; before this it landed on the default destination at
the top of the grid (the scroll position comes from `rememberLazyGridState`'s saver and is checked
by reading, not on a device). The zoom rung, the open map and sheet visibility are not held (the rung is a
preference, the map is session-only). Rotation was never a loss path.

The photo (or video) is *home* and the same room mechanics apply, with the same geography as the
gallery (see the table in §2): **pull the photo down** for the details room (title, camera card,
the place plate, caption, information, tags), **pull up from the bottom** for the actions room (a
short pull for the quick strip of Favourite, Share, Edit and Trash; further for share, edit, lift
subject, favourite, trash and the rest), and the **right edge** for the view options (the viewer's
former "settings" room). Until October 2026 the viewer was the one screen that departed from the
gallery's layout; that deviation is retired, and `handoff/DESIGN-FIDELITY-VIEWER.md` records how.

- **The chrome row is Close, the position pill and the favourite heart.** The heart (at least
  48 dp, filled when it is a favourite and outlined when it is not, with the state also in its
  accessibility description) shows only while the chrome is up and the photo is one the library
  holds (`ViewerChromePolicy.favouriteShown`: `catalogueActions` and not `isExternal`). It works on
  videos. The actions room keeps its own Favourite row.
- **The slideshow has a bar** (owner #16): previous, play or pause, next, on a black coved bar in the
  shape of the gallery's selection toolbar, hung from the top edge above the chrome row
  (`SlideshowBar`; whether it shows is `slideshowBarShown`). Pause is real: `AppStateViewModel`
  keeps a `slideshowPaused` flag, `slideshowMayAdvance` honours it, and ending a slideshow clears
  it. During a running slideshow the chrome folds away after three seconds
  (`slideshowChromeMayAutoHide`) and a tap brings it back; it does not auto-hide while paused or
  over a video. A swipe or the bar's Next always moves to the next photo in order; shuffling
  happens only when the countdown advances. The actions room's row now reads "Stop slideshow".
- **Transparent photos get a backdrop** (owner #17): the grey-and-white grid by default, or white
  or black. The choice is a persisted preference (`GalleryPreferences.viewerTransparencyBackground`,
  wired in both `FotoXplorrActivity` and `ExternalViewerActivity`), changed from a swatch button
  in the chrome row that exists only while the photo on view has transparency, and from the
  "Transparent photos" row of the view options. Whether a photo has transparency is decided by
  `core:formats` `AlphaSniffer` on at most the first 64 KiB of the file (read on `Dispatchers.IO`,
  cached per photo and modification time); an unknown answer counts as opaque. It reports what the
  file declares, so a fully opaque PNG with an alpha channel still shows the button. The backdrop
  is drawn inside the zoom layer, behind the picture, so it pans, zooms and turns with it (the
  grid's squares scale too). Not drawn: grid and filmstrip thumbnails, and the shrink-to-map flier.

- **Pulling the details room down shrinks the photo and lands it as the pin on the map** in the
  place block — a shrink-to-appear, explicitly not a tilt of the pane. While that room is pulled the
  photo's card slides flat; every other room keeps the swivel. A photo with no location lands,
  dimmed, on a still graticule with a ghost pin and the words "No location yet" (with "Add a place"
  where a place may be set by hand); a photo whose location could not be *read* never offers the
  picker. No map tile is fetched during the gesture: the connect flavour asks for one static
  snapshot only after the room has settled open, and the offline flavour draws no map at all.
- **The filmstrip is roll-level navigation.** It is shown for videos exactly as for photos, so
  scrubbing onto a video never takes it away from under the finger. It drives the photo live while
  dragged, and touching it pauses a playing video; the next video does not start until the strip has
  settled.
- **A video opens ready to play.** Autoplay is ON by default (the setting persists) and the chrome —
  the position chip, the transport, the filmstrip — is up the first time a video opens in a viewer
  session; after that a tap shows or hides it. A slideshow waits for a playing video and counts its
  interval from when the clip ends or is paused.
- **Lift subject** (actions room, or a long press on a photo): the photo opens in a full-screen
  editor. Tap or draw on the subject to select it, flip the polarity to scribble on the background,
  refine the edge with an add/erase brush, undo and redo, preview on a checkerboard, then copy,
  share, or save a sticker. Saving writes a 2048 px (never upscaled) PNG with alpha to
  `Pictures/Fotoz/Stickers` and happens only from the Save sticker button.

---

## 3. Information architecture

```text
Library (the catalogue)
│
├── Destination            ← the primary axis; nine lenses, no back-stack (the Map is a mode, not a tenth)
│   └── Route              ← drill-down beneath a destination
│       ├── Device album
│       ├── Collection
│       ├── Smart album
│       └── Tag
│
├── Rooms                  ← navigation surfaces, not content
│   ├── LEFT   Navigation: the destinations rail
│   ├── TOP    Details: the view's counts, or the open photo's facts
│   ├── BOTTOM Actions: a quick strip, then every action
│   └── RIGHT  View options: zoom, order, timeline and scroll direction, or the viewer's switches
│
├── Panes                  ← take over the place the photos are
│   └── Settings (nine tabs), Albums, Calendar, Discover, Library, Tidy up
│
└── Overlays               ← full-surface, above everything
    ├── Viewer             ← single asset, with filmstrip and detail
    └── Dialogs            ← password, rename, tag, collection picker
```

Two rules hold this together:

1. **Destinations are not routes.** Switching destination replaces what the grid shows and pushes
   nothing. Only drilling *into* something (an album, a collection, a tag) creates a route with a
   Back affordance.
2. **State is layered, not duplicated.** An asset has exactly one catalogue row. Favourite,
   sensitive, archived, tagged, collected and protected are all *flags or joins* against that row,
   held in separate stores. No destination owns assets.

---

## 4. Software architecture

### Module map

| Package | Responsibility |
|---|---|
| `media/` | The catalogue: `MediaAsset`, `MediaRepository`, `SqliteMediaRepository`, `AndroidMediaStoreScanner`, `MediaIndexer`, `ScanPlan`, `PrefsScanWatermark`, `MediaStoreChangeObserver` |
| `gallery/` | The browsing surface: `GalleryScreen`, `GalleryProjection` (pure filtering/sorting), `DestinationBrowserScreen`, `ZoomGrid` (the one grid), `TimelineStops`/`TimelineRuns`/`TimelineLayout` (strip, gutter, headers), `TimeRange`, `MapMode`, `LongPressResolver`, preferences, dialogs |
| `viewer/` | Single-asset viewing: the photo and its three rooms, the roll-level filmstrip, `ViewerChromePolicy` (what is drawn when), the slideshow bar (`SlideshowBar`), the transparent-photo backdrop (`TransparencyBackdrop`), the shrink-to-map flight (`PhotoToPinFlight`, `PlaceMorph`), detail formatting, the Media3 video player (`VideoPlayer`, `PlaybackLogic`, `ViewerPlayerFactory`, `ResumePositionStore`), and `ExternalViewerActivity` (the single VIEW target) |
| `ui/` | Shared UI vocabulary: `RoomGeography` (which edge holds which room), `RoomStyle`/`PaneScaffold` (the black room and page style), `QuickActionStrip`, and `HelpCopy`, the one home of the app's trimmed help and privacy text (length ceilings pinned by `HelpCopyTest`) |
| `core/*` | Pure Kotlin modules shared with other targets (`model`, `formats`, `metadata`, `search`, `organize`, `db`, `index`). `core:formats` holds the byte sniffers `AnimationSniffer` and `AlphaSniffer`; commonMain stays free of Android and `java.*` imports (`scripts/verify-core-purity.sh`) |
| `picker/` | `PhotoPickerActivity`: other apps' GET_CONTENT / PICK, over the same `browsableAssets` filter as every screen |
| `recognition/` | On-device ML: face detection and clustering, pet classification, identity heuristics |
| `spatial/` | Geo metadata, the MapLibre street map (Connect) and stamp map, OpenGL photo scenes |
| `experimental/` | The Experimental page: `ExperimentEntry` list, host (open id, Back), `ExperimentEnv`, shell-edge insets |
| `experience/` | The 3D photo wall and its renderer/orientation controller |
| `ai/` | Optional BYOK remote providers, encrypted secret storage, local similarity |
| `fileops/` | Media file operations and metadata-clean export |
| `privacy/` | Sensitive-asset and password-protected-folder stores |
| `favorites/`, `organize/` | Favourites; collections, tags and archive state |
| `hyle/` | Design-system bridge, `ActivityShade` (background job/status surface), the floating pill |
| `editor/` | The photo editor, one scrollable tool strip with no tiers (Light, Colour, Curves, Presets, Detail, HSL, Crop, Rotate, Heal): adjustments, a drawn tone-curve editor with a histogram backdrop and a luma curve, HSL, crop, spot heal, presets, undo history, persisted recipes, `EditedCopyWriter` (Save/Save As/Overwrite) and `OverwriteGuard` (what Replace refuses, the safety copy, `OverwriteRecovery`) |
| `metadata/` | EXIF/XMP read and write: `MetadataWriter`, `XmpPacket`, `MetadataEdit` |
| `video/` | Video export via Media3 Transformer: `VideoExporter`, `VideoConversionWriter`, export options and codec capability. (The audio conversion writer and `AudioTranscoder` were retired with the audio feature.) |
| `videoeditor/` | The video editor screen and its `VideoEditPlan` (trim/speed/rotate/mirror/crop/mute/filters) |
| `jobs/` | `JobRunner` and `JobForegroundService`: progress-tracked, cancellable background work (conversion, export, copy/move) surfaced through `ActivityShade`; the `POST_NOTIFICATIONS` request is made once, the first time a job runs |
| `shell/` | `AppStateViewModel` — navigation and pending-consent state that survives rotation and process death |
| `openwith/` | Classifying an incoming Edit or Share intent (`content://` only) and the hidden-photo guard for anything another app asks to open (`OpenRequestLookup`, `classifyMediaStoreUri`); View itself is `viewer/ExternalViewerActivity` |
| `share/` | Share preparation, frames, watermarking, ZIP export |
| `moments/` | Key-moment detection and short clip/frame export |
| `lift/`, `lens/` | Subject lift: a scribble-to-select editor (tap or draw to select, a Background polarity, a refine brush, undo/redo, checkerboard preview) and sticker export; the lens explorer tile |
| `curate/`, `background/` | Auto-curation and archive suggestions; the background work scheduler |

### Data flow

```text
MediaStore ──scan──▶ MediaIndexer ──▶ SqliteMediaRepository ──Flow──▶ GalleryScreen
     ▲                    ▲                                              │
     │                    │                                       GalleryProjection
MediaStoreChangeObserver  │                                       (pure filter/sort)
     │                    │                                              │
     └──debounce 800ms──▶ scanRequests: Channel(CONFLATED)               ▼
                                                              destination / route views
```

### The incremental-scan design

This is the part of the architecture most worth understanding, because getting it wrong was a
destructive live bug: taking a screenshot sent "Indexing 3456 of 21526" back to zero, and under
churn the scan could never finish.

Three separate mechanisms are now load-bearing:

1. **`ScanPlan`** — a pure decision type (`Full` vs `Delta(sinceSeconds)`). It is pure so the
   decision is unit-testable without a device. The watermark **rewinds 10 seconds and compares
   `>=`**, because `DATE_MODIFIED` is second-granular: a file written in the same second as the
   last scan would otherwise be missed forever.
2. **`PrefsScanWatermark`** — monotonic. It never moves backwards, so a partial scan cannot widen
   into a permanent gap.
3. **Rescans are requests on a conflated `Channel`**, not a `LaunchedEffect` key. The old code
   keyed the effect on a generation counter, so every MediaStore notification *cancelled the
   running scan and restarted it*. Requests arriving mid-scan now collapse into one follow-up
   pass. The change observer is debounced 800ms, because one screenshot emits several
   notifications (insert, thumbnail, metadata).

One invariant guards the whole thing: **a delta pass never calls `replaceAll`.** That single call
would delete the entire untouched library, and it is commented as such at the call site.

### Concurrency and state

Compose state is hoisted into `FotoXplorrApp`; the repository exposes `Flow`s collected with
`collectAsStateWithLifecycle`. Long work runs in `viewModelScope`-equivalent coroutine scopes on
`Dispatchers.IO`. Recognition is guarded on a **generation counter** rather than the asset list,
so a recomposition never restarts a pass that already finished.

### The viewer's moving parts

`ViewerScreen` used to hold its "what is drawn, and when" rules as inline boolean expressions, which
is how the filmstrip came to be gated on the type of the photo. They now live as pure functions in
`ViewerChromePolicy` (pinned by `ViewerChromePolicyTest`): `chromeShown`, `filmstripShown` (takes no
asset on purpose — TRAPS #39), `videoBottomInsetDp`, `initialChromeVisible`/`revealChromeForVideo`,
`slideshowMayAdvance`, `longPressStartsLift`, `roomsWithdrawn`, `flightActive`, `photoAtRest`,
`placeKnown`. The screen reads as a list of decisions.

**Video playback** is Media3 ExoPlayer, built in one place (`buildViewerPlayer`, shared with the video
editor's preview): decoder fallback on, the movie audio content type with audio focus, pause on
becoming noisy. Two behaviours are visible to the person: starting a video now pauses another
app's music, and an incoming call or navigation prompt pauses the video.

- *Armed, not mounted.* `VideoPlayer` is only a poster until it has been on screen 250 ms with no
  finger on the filmstrip; only then does the private `ArmedVideoPlayer` build a player, look up
  sidecar subtitles, prepare and (later) index key moments. The poster stays until the first
  rendered frame, so there is no black flash. A scrub or page swipe past a video builds no player.
- *Transport is intent* (`playWhenReady` plus the playback state, `transportShowsPlay`), not
  `isPlaying`, so Pause works while buffering (TRAPS #46).
- *Errors are surfaced.* A `PlaybackException` becomes a panel with plain-language copy and Retry /
  Open with as appropriate (`playbackFailureFor`); a sidecar subtitle that cannot be read is dropped
  before the first prepare, and a fatal error with sidecars attached is retried once without them
  (TRAPS #45).
- *Resume.* Written on pause, `ON_STOP`, the end of the poll loop and release; only once the person
  has played or seeked (`resumeWriteFor`); never for a file the app does not own (negative ids,
  `isExternal`). With autoplay on and a remembered position the clip resumes silently from it and the
  "Resume from" chip is not shown; with autoplay off it waits at 0:00 behind the chip.
- *Key moments.* The indexer starts 1.5 s after the player first reaches `STATE_READY`, so the
  metadata retriever never competes with the first-frame decode; the "Key moment" pill therefore
  appears a few seconds later than it used to, and a video that never plays is never indexed.
- *Lifecycle.* `ON_STOP` pauses unless the activity is in picture-in-picture; PiP auto-enter is
  armed only while playing and disarmed on dispose; the PiP button is hidden on a device without the
  feature; the screen is kept on only while playback is active. `ExternalViewerActivity` (open-with)
  declares `supportsPictureInPicture` and gives each external video its own synthetic negative id.
- `PlayerView` stays a `SurfaceView`. Whether it follows the 3D swivel acceptably is unverified (a
  device check); if it does not, the options are a texture-view layout (which costs HDR fidelity) or
  pausing while a room is open. Neither was done blind.

**The shrink-to-map flight** (`PhotoToPinFlight`, `PlaceMorph`) is an overlay above the shell whose
every frame is a pure function of the shell's vertical progress (TRAPS #40), aimed at the pin tile's
rectangle that `PhotoDetailRoom` reports, with `ParkStyle` chosen app-side (TRAPS #41). It runs only
when the photo is at rest (not zoomed or turned) and the pin is fully on screen in the room;
otherwise the room plays its own in-plate shrink or fades the pin in place.

**Lift** derives its selection by replaying a history of operations (`SelectionSession`: solver hints
and direct paint strokes, in image-normalised coordinates, brush radius a fraction of the long edge),
never by editing a stored mask, so undo and redo are exact with no bitmap snapshots. A replay solves
all hint strokes together at a 512 px long edge (`GeodesicSegmenter`, on `Dispatchers.Default`,
cancelled by the next stroke), applies the paint strokes in order so corrections are sticky, then
feathers. Export re-decodes at up to 2048 px and upsamples the mask. Copy and Share write to
`cacheDir/lift-stickers` (pruned of files older than 24 h whenever the editor opens); only Save
sticker writes to the gallery.

**The curves editor** keeps its maths pure and JVM-tested: `ToneCurve` (monotone-Hermite, with
index-based `moveTo`/`insertAt`/`removeAt`), `CurveHit` (grab and drag-out hit-testing), `ToneHistogram`
(the backdrop), `CurvePresets`. The luma curve travels inside `ChannelLuts.luma` (null when
identity), so `AdjustmentRenderer` and the export path apply it without a change.

### Saving an edit, and how honest "Replace the original" is

`EditedCopyWriter` has three outputs, and they do not promise the same things.

- **Save a copy** (`save`) inserts a new MediaStore row beside the original and never opens the
  original for writing. It copies the source's EXIF and XMP into the result (JPEG, PNG, WebP), keeping
  location. A HEIC output is pixels only (`MetadataCopy.SKIPPED_HEIC`): `ExifInterface` cannot write
  into it, and `HeifWriter.addExifData` is the unbuilt fix.
- **Save As** (`saveTo`) writes to a Storage Access Framework destination the user picked. It strips
  location (every GPS tag, XMP `exif:GPS*`, the IPTC-ext `LocationShown` / `LocationCreated`
  structures, `photoshop:City` / `State` / `Country`, `Iptc4xmpCore:Location` / `CountryCode`; an
  unparseable XMP packet is dropped) and then re-reads the staged file with `carriesLocation` and
  fails the save if any remains. The metadata panel's "Clear location" is deliberately narrower and
  leaves place names.
- **Replace the original** (`overwrite`) truncates and rewrites the existing file. MediaStore has no
  atomic replace, so this is **best effort**, built so that a failure is survivable rather than
  impossible. In order: (1) it is refused (`OverwriteException.Refused`, original untouched) for a
  format with no in-place path (HEIC, GIF, RAW), for hazards the re-encode would destroy
  (`OverwriteHazard`: an animated WebP or APNG, an Ultra HDR gain map, a Motion Photo's video, a
  depth map or other embedded images, transparency a WebP re-encode flattens on API 30+, lossless
  WebP, or a file that cannot be read for the scan), and unless the whole source metadata was carried
  over (`MetadataCopy.COPIED`). The metadata gate refuses IPTC-IIM, extended XMP, PNG text with real
  content (a prompt, a comment; colour profiles and boilerplate keywords are fine) and other WebP
  chunks, and a view Android redacted (API 29+ without photo-location access). MakerNote and the
  embedded thumbnail are deliberately not gated, since nearly every camera file has them. The JPEG
  scans look at the first 256 KiB (and the last 64 KiB for Motion Photos), which can miss later
  metadata; a PNG or WebP that fills that window is walked end to end. The guard fails open for
  variants nobody taught it, so it lowers the risk and does not close it. (2) It copies the
  original's bytes into `filesDir/overwrite-recovery` (private, and not the cache, because the
  system clears the cache exactly when storage is tight), flushes them, and writes a small sidecar
  naming the photo. (3) It writes, flushes and reads back. (4) On any failure after the truncate it
  writes the copy back and reports `FailedAfterWrite(restored, recoveryFile)`. (5) On success, or on
  a restore that read back at the right size, it deletes the copy.
- **If that fails too** (the process is killed mid-write, or the put-back itself fails) the copy is
  kept, and the next start shows a dialog that names the photo and offers *Share the copy of
  <name>* (staged through the outgoing-share FileProvider root, so any app can put it back), OK, and
  Delete copies. It comes back on every start until answered; a tap outside is not an answer. A copy
  is deleted 7 days after OK (30 for a `.bak`, the one kept after a failed put-back), or at once on
  Delete. There is no Restore button: recovery is share-out only, and the app does not claim more.
- **While a replace is running** the editor's Save, Cancel and Back are off, an Edit or Share intent
  for another photo is answered with "Finish saving your edit first", and
  `AppStateViewModel.launchWrite` keeps the code that records the outcome alive across activity
  recreation (the manifest also stops locale, font scale and colour-mode changes recreating it).
  When the original may be damaged the banner offers only Save a copy.
- **Export size.** An export starts at the user's cap or 8192 px / 64 MP and steps down 8192, 4096,
  2048, 1536 only after a real `OutOfMemoryError`, saying "at reduced size". A replace uses one rung
  so an out-of-memory error writes nothing. `android:largeHeap` is not set (an owner decision left
  open).

### Privacy boundaries, as code

- **No Internet permission is needed for the core app.** Recognition is bundled models; the map is
  a native vector renderer; the catalogue is local SQLite.
- **`FLAG_SECURE`** is set on the window whenever a protected folder is unlocked, and cleared on
  dispose.
- **Protected folders lock on `ON_STOP`**, and if the viewer was showing a protected asset its
  contents are cleared too — so the app cannot resume displaying something that should be locked.
- **AI keys** live in `EncryptedSecretStore`; the OkHttp client deliberately ships **no logging
  interceptor**.
- **Another app cannot reach what the library hides.** `ExternalViewerActivity` is the single VIEW
  target (`content://` only; `FotoXplorrActivity` keeps only EDIT and SEND filters, and the SEND
  filter carries no scheme because the Uri is in `EXTRA_STREAM`, so `classifyIncomingIntent` checks
  the scheme itself). A `content://media` Uri is read the way Android reads it
  (`classifyMediaStoreUri`: percent-decoded, an optional `<userId>@` authority prefix, the last
  numeric segment is the row id; a photo-picker Uri is not a row; a media Uri with no row id is
  refused). A catalogued photo redirects into the full viewer; one the catalogue has not scanned yet
  is looked up in MediaStore and held to the same folder rules. Anything `browsableAssets` hides
  gets a notice ("That photo is hidden or locked in Fotoz, so it was not opened.") and is never shown
  as an external file. Incoming intents expose only three extras (open-media-id, allow-multiple,
  stream); the rest are dropped unread, so a hostile parcelable cannot crash the activity.

### Testing

The pure parts are extracted precisely so they can be tested without an emulator:
`GalleryProjection` (filtering, sorting, smart albums), `ScanPlan`, `MediaIndexer` (with a
recording scanner and fake watermark), `TimelineStops`, `FaceClustering`, `PetClassifier`,
`IdentityDocumentHeuristics`, the ID codecs and the alert-banner copy.

CI runs `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. `lintDebug` is in that list
deliberately — it was once absent, which is how a lint error sat on a branch unnoticed.

### Build composition

`dev.aarso:hyle`, `dev.aarso:crash-recovery` and `dev.aarso:cell-shell` are consumed as **git
submodules + Gradle `includeBuild` dependency substitution** — the constellation's one sanctioned
sharing mechanism, with no Maven registry involved.

`shared-libraries/` (crash-recovery and cell-shell) is another repository's submodule
(`mbaliga/Shared-Libraries-asoc`) and is pinned at `18fc20e`, on that repo's
`claude/friendly-lovelace-06dito` branch (its PR #17). That branch adds what the actions room needs:
a navigation-bar-aware bottom edge zone, the optional two-stage bottom peek, `bottomReserve` (so a
vertical-first drag on the filmstrip or video transport scrubs instead of opening the room) and a
stale-flick guard (pin `1f33d10`), and the timeline strip's edge (`ScrubberEdge`: Left, Right, Top or
Bottom, default Right; pin `18fc20e`). **PR #17 must be merged first, with a merge commit (TRAPS #2), and only then this
app's PR**, or a fresh `clone --recurse-submodules` cannot find the pinned commit.

The explicit `dependencySubstitution` on the Hyle include in `settings.gradle.kts` is
**load-bearing, not cosmetic**: Hyle still carries a `:crash-recovery` tombstone declaring the same
coordinate as the real module, so two composites offer one coordinate and Gradle refuses to
resolve it. Declaring any explicit substitution for an included build disables *automatic*
substitution for it, which removes the ambiguity. The reasoning is inline so nobody deletes the
rule as redundant.

AGP and Gradle versions must stay in lockstep with `hyle-design-system` or composite builds
hard-fail.

---

## 5. Known limits

- **The scrubber, pill and arrow keys never assume grid item *n* is asset *n*.** The grid now draws
  month headers (always at Stamps, and on the columns while the timeline is on), so every grid index
  goes through `GridIndexMap` (`core/organize`, TRAPS #5). Masonry keeps a staggered state the screen
  cannot reach, so it has no strip, headers or pill position. Undated photos sort together at one end
  (`sortAssets` orders by date taken, the strip and headers by date taken else modified), so a month
  can head two runs.
- **Trash operations require Android 11+.** On older versions the app declines rather than
  deleting media without the platform confirmation dialog.
- **Remote AI is unverified against every provider.** The four provider shapes are implemented;
  only the ones the user configures are exercised.
- **The Media3-based playback/export/editing pipeline (ADR-008 rev. 2) has not been run on a real
  device or emulator** — this environment has neither. It is compile- and unit-test-verified only; see the device checklist each ADR names before
  treating them as production-proven. (The in-viewer player has since been rebuilt; its own
  checks are in `docs/device-test/batch-1-checklist.md`. A release build's Media3 behaviour is a
  smoke test too: `proguard-rules.pro` has no Media3 section and relies on the library's consumer
  rules.)
- **The photo editor's tools are unit-tested on their pure geometry/colour math, and the curves
  editor's gesture wiring is also tested** (curve maths, hit-testing, the histogram and the
  drag/long-press/double-tap handling under Robolectric: `CurveHitTest`, `CurveEditingTest`,
  `CurvesEditorGestureTest`). **Gesture feel is device-only**: the touch radius, the slop and
  double-tap timings, the edge-back gesture near the graph's ends and dragging a crop handle have not
  been exercised on a touchscreen, and no full-`EditorScreen` Robolectric test exists, so the editor's
  layout budget on a small or landscape phone is reasoned and compiled, not seen.
- **The video player, the filmstrip over a video and the shrink-to-map flight were rewritten or added
  in the 21 Oct 2026 batch and have not been run on a device.** The pure rules are unit-tested
  (`PlaybackLogicTest`, `ViewerChromePolicyTest`, `PlaceFlightGeometryTest`) and the screen-level
  wiring is exercised under Robolectric (`ViewerIntegrationTest`, `PhotoToPinFlightTest`,
  `PlaceUnknownPlateTest`), but real decoders, a 4K HEVC or Dolby Vision clip, process death, audio
  focus, picture-in-picture and the feel of the flight at finger speed are only in
  `docs/device-test/batch-1-checklist.md`. That a bad sidecar subtitle is a *fatal* error is inferred
  from Media3's behaviour, not observed.
- **Subject lift is a classical, colour-and-edge solver, not a learned model.** A single tap selects
  one colour region, so a multi-coloured subject (a shirt and a face) needs a second scribble; a
  neighbour sharing the subject's colour with no edge between them needs a Background scribble; hair,
  fur, glass and low-contrast edges need a learned segmentation model, which is deliberately not
  scheduled (it needs an owner decision on the model's licence and size). Only synthetic scenes have
  been tested, so real-photo quality is a device check.
- **Rotated-EXIF photos may misalign two overlays.** `LiveTextOverlay` and the shrink-to-map flight's
  start rectangle are fitted from `MediaAsset.width`/`height`, which may be the sensor's un-rotated
  grid. Lift was fixed to use the decoded bitmap's own size (TRAPS #48); these two were not, and
  could not be measured here.
- **Two defects found and not fixed in the 21 Oct 2026 batch** (both outside the units that found
  them): in `ViewerActionsRoom` only the 22 dp icon of an action row is clickable — tapping the words
  ("Lift subject", "Share", any of them) does nothing; and `LiveTextOverlay` consumes every tap and
  long press on a photo with recognised text while the chrome is up, which also blocks
  long-press-to-lift and tap-to-toggle-chrome there (TRAPS #50).
- **Batch 2 (October 2026: nine destinations, the rooms geography, the Settings page, the viewer
  heart, slideshow bar and transparency backdrop, the trimmed help text, the overwrite safety net,
  the single open-with entry point) has not run on a device either.** The pure rules are
  unit-tested and the screens are exercised under Robolectric (and rendered), but gesture feel, the
  insets of the moved rooms under a camera cutout or three-button navigation, the cove against a
  real status bar, TalkBack, a real fling on the rail, and every mid-write failure of Replace are in
  `docs/device-test/batch-2-checklist.md`.
- **Batch 3 (October 2026: the one zoomable grid and Stamps, the timeline strip on any edge with a
  gutter and month headers, the sideways grid, the map toggle and time range, the long-press choice,
  Back from a photo landing where you left) has not run on a device either.** The pure rules are
  unit-tested and the screens are exercised under Robolectric, but the pinch feel, the Stamps
  perforation and its scroll at 22,000 photos, the strip on all four edges under gesture and
  three-button navigation, the sideways grid's edge zones, the Connect build's street map and the
  rail-scrub no-fetch guarantee, and TalkBack are in `docs/device-test/batch-3-checklist.md`.
- **`decodeUpright` returns null on API 26 and 27, found and not fixed.** The `BitmapFactory`
  fallback (used below API 28; `ImageDecoder` is used from 28) does its bounds-only pass with
  `BitmapFactory.decodeStream`, which always returns null for a bounds-only decode, and the line
  ends in `?: return null`. Every caller of `decodeUpright` (the editor, lift, share frames and the
  rest) therefore gets nothing on those two releases. The fix is to
  use the options' `outWidth`/`outHeight` and not the return value; it is outside the units that found
  it. Until it is fixed, treat stills as unverified on Android 8.0 and 8.1.
- **Opening a `content://media` photo from another app needs MediaStore access.** Under Android
  14's "selected photos" access, a photo Fotoz cannot see in MediaStore is refused ("could not be
  checked against your library") rather than opened, because the hidden-photo rules cannot be
  applied to a row nobody can describe. This is a deliberate trade, and its feel is a device check.
