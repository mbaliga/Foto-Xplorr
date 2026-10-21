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

### Ten destinations

The primary information axis. Each is a lens over the same catalogue, not a separate store —
except Audio, which has no `MediaAsset` behind it at all (see `audio/`'s own module doc):

| Destination | What it shows |
|---|---|
| **Pets** | Photos where the on-device classifier found a cat, dog or other pet |
| **People** | Photos with detected faces, grouped into clusters |
| **Identity** | Photos heuristically identified as identity documents |
| **Screenshots** | Screenshots, detected by path and dimensions |
| **Photos** | All still images |
| **Videos** | All video |
| **Favourites** | User-marked favourites |
| **Places** | A map of geotagged media |
| **Protected** | Password-protected folders |
| **Audio** | Standalone audio files (music, recordings, podcasts) from `MediaStore.Audio` |

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

- **Places** — a native vector map (MapLibre) with clustering, pitch/bearing, hillshade and 3D
  building extrusions, populated from geotags read locally by `GeoMetadataRepository`.
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

The grid is *home*. The destinations rail and settings are **rooms** parked off the left and right
edges. Dragging from an edge lifts the grid, shrinks it slightly, rounds its corners and parts it
to reveal the room behind — the grid stays alive and visible the whole time, and dragging it back
is the way out.

A room is **not a back-stack entry**. Switching to one pushes nothing; Back closes it.

- **Left edge → destinations.** A word wheel: room names as large text, the focused row brightest
  and heaviest, neighbours dimming progressively with distance. The fade *is* the position
  indicator, so there is no scrollbar and no selection box. A bullet travels between rows rather
  than teleporting.
- **Right edge → settings.** Two depths on one surface: the compact panel, and every setting
  behind "All settings…". Both render in the app's own dark theme.
- **Top edge → reserved.** Nothing there yet, deliberately. The pull-down space belongs to the
  top-room reveal, so no other gesture may claim it.

Drags are applied **1:1 with the finger** and are reversible mid-flight. Only release animates, on
a 320ms eased curve with **no spring** — a room is a place you arrive at, not something that
bounces into position.

### The edge timeline scrubber

A narrow strip down the right side of the grid. Gliding a finger down it sweeps the entire
library, with a bubble naming the month under the thumb and a haptic tick at each month boundary.
This is what makes a 21,000-item continuous mosaic navigable.

Its labels are placed at the y their own item index maps to, so the strip tells the truth about
where a busy month ends — spacing them evenly would look tidier and lie. It claims a touch only
after the finger passes touch slop vertically, so a tap near the edge does nothing and a sideways
drag still reaches the shell.

### Refresh is a shake

Not a pull. The pull-down space is reserved, so refresh moved off the touch plane entirely: a
deliberate shake needs no affordance, no instructional copy, and competes with no scroll. Three
distinct acceleration peaks inside a 900ms window, with a cooldown so one enthusiastic shake
cannot fire twice.

### The floating pill

A dark rounded pill at the bottom: search on the left, a position readout in the middle
("March 2024 · 412 of 21526"), grid density on the right. The middle is a *readout*, not a
control — the edge scrubber sets position, and two position controls that can disagree about
where you are is worse than either alone.

### Selection

Long-press enters selection mode, which replaces the header with a selection bar carrying the
bulk actions: share, favourite, archive, mark sensitive, clean-share, copy, move, rename, tag,
add to collection, trash.

### The viewer

The photo (or video) is *home* and the same room mechanics apply, with the viewer's own geography:
**pull the photo down** for the details room (title, camera card, the place plate, caption,
information, tags), the **right edge** for the actions room (share, edit, lift subject, favourite,
trash and the rest), the **bottom** for the viewer's settings. The viewer is the one screen that
departs from the gallery's room layout; the reasoning is in
[`handoff/DESIGN-FIDELITY-VIEWER.md`](handoff/DESIGN-FIDELITY-VIEWER.md).

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
├── Destination            ← the primary axis; nine lenses, no back-stack
│   └── Route              ← drill-down beneath a destination
│       ├── Device album
│       ├── Collection
│       ├── Smart album
│       └── Tag
│
├── Rooms                  ← navigation surfaces, not content
│   ├── LEFT   Destinations rail
│   ├── RIGHT  Settings → All settings
│   └── TOP    (reserved)
│
└── Overlays               ← full-surface, above everything
    ├── Viewer             ← single asset, with filmstrip and detail
    ├── Legacy screens     ← Albums / Discover / Library
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
| `gallery/` | The browsing surface: `GalleryScreen`, `GalleryProjection` (pure filtering/sorting), `DestinationBrowserScreen`, `TimelineStops`, preferences, dialogs |
| `viewer/` | Single-asset viewing: the photo and its three rooms, the roll-level filmstrip, `ViewerChromePolicy` (what is drawn when), the shrink-to-map flight (`PhotoToPinFlight`, `PlaceMorph`), detail formatting, and the Media3 video player (`VideoPlayer`, `PlaybackLogic`, `ViewerPlayerFactory`, `ResumePositionStore`) |
| `recognition/` | On-device ML: face detection and clustering, pet classification, identity heuristics |
| `spatial/` | Geo metadata, the MapLibre Places map, OpenGL photo scenes |
| `experience/` | The 3D photo wall and its renderer/orientation controller |
| `ai/` | Optional BYOK remote providers, encrypted secret storage, local similarity |
| `fileops/` | Media file operations and metadata-clean export |
| `privacy/` | Sensitive-asset and password-protected-folder stores |
| `favorites/`, `organize/` | Favourites; collections, tags and archive state |
| `hyle/` | Design-system bridge, `ActivityShade` (background job/status surface), the floating pill |
| `editor/` | The photo editor, one scrollable tool strip with no tiers (Light, Colour, Curves, Presets, Detail, HSL, Crop, Rotate, Heal): adjustments, a drawn tone-curve editor with a histogram backdrop and a luma curve, HSL, crop, spot heal, presets, undo history, persisted recipes, `EditedCopyWriter` (Save/Save As/Overwrite) |
| `metadata/` | EXIF/XMP read and write: `MetadataWriter`, `XmpPacket`, `MetadataEdit` |
| `video/` | Video export via Media3 Transformer: `VideoExporter`, `VideoConversionWriter`, export options and codec capability |
| `videoeditor/` | The video editor screen and its `VideoEditPlan` (trim/speed/rotate/mirror/crop/mute/filters) |
| `audio/` | Standalone audio: MediaStore scanning, `SqliteAudioRepository`, the library and player screens |
| `audiotags/` | Pure ID3v2/MP4/FLAC tag codecs plus the Android-side tag writer |
| `playback/` | `PlaybackService` (`MediaSessionService`) and the Compose audio-playback controller |
| `jobs/` | `JobRunner` and `JobForegroundService`: progress-tracked, cancellable background work (conversion, export, copy/move) surfaced through `ActivityShade` |
| `shell/` | `AppStateViewModel` — navigation and pending-consent state that survives rotation and process death |
| `openwith/` | Handling this app being opened on an external file (View/Edit/Send) |
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
becoming noisy. Two behaviours are visible to the person: starting a video now pauses the app's own
music, and an incoming call or navigation prompt pauses the video.

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

### Privacy boundaries, as code

- **No Internet permission is needed for the core app.** Recognition is bundled models; the map is
  a native vector renderer; the catalogue is local SQLite.
- **`FLAG_SECURE`** is set on the window whenever a protected folder is unlocked, and cleared on
  dispose.
- **Protected folders lock on `ON_STOP`**, and if the viewer was showing a protected asset its
  contents are cleared too — so the app cannot resume displaying something that should be locked.
- **AI keys** live in `EncryptedSecretStore`; the OkHttp client deliberately ships **no logging
  interceptor**.

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

- **The edge scrubber assumes a headerless grid.** Grid item *n* is asset *n*. Both surfaces that
  show the scrubber render headerless grids; a grid that drew date headers would insert items and
  shift everything below each one.
- **Trash operations require Android 11+.** On older versions the app declines rather than
  deleting media without the platform confirmation dialog.
- **Remote AI is unverified against every provider.** The four provider shapes are implemented;
  only the ones the user configures are exercised.
- **The Media3-based playback/export/editing pipeline (ADR-008 rev. 2) and the audio playback
  service have not been run on a real device or emulator** — this environment has neither. They
  are compile- and unit-test-verified only; see the device checklist each ADR names before
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
