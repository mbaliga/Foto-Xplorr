# Foto Xplorr — product and architecture

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

Foto Xplorr does not browse `MediaStore` live. It maintains its **own catalogue** in a local
SQLite database (`SqliteMediaRepository`), populated by scanning `MediaStore.Files`. That
indirection is what makes the rest of the app possible — favourites, tags, collections, archive
state and privacy flags are all keyed to catalogue rows and would have nowhere to live otherwise.

Scanning is **incremental**. A full scan runs on first launch and on explicit refresh; everything
else is a delta against a persisted watermark. See §4 for why this matters more than it sounds.

### Nine destinations

The primary information axis. Each is a lens over the same catalogue, not a separate store:

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

Foto Xplorr uses the **fonebrew spatial pattern**, shared across the constellation via
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
| `viewer/` | Single-asset viewing, filmstrip scrubber, detail formatting, video playback |
| `recognition/` | On-device ML: face detection and clustering, pet classification, identity heuristics |
| `spatial/` | Geo metadata, the MapLibre Places map, OpenGL photo scenes |
| `experience/` | The 3D photo wall and its renderer/orientation controller |
| `ai/` | Optional BYOK remote providers, encrypted secret storage, local similarity |
| `fileops/` | Media file operations and metadata-clean export |
| `privacy/` | Sensitive-asset and password-protected-folder stores |
| `favorites/`, `organize/` | Favourites; collections, tags and archive state |
| `hyle/` | Design-system bridge, the alert banner, the floating pill |

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

- **The top room is reserved but not built.** Pulling down does nothing yet. That is deliberate —
  the direction was to keep the gesture unclaimed, not to fill it.
- **The edge scrubber assumes a headerless grid.** Grid item *n* is asset *n*. Both surfaces that
  show the scrubber render headerless grids; a grid that drew date headers would insert items and
  shift everything below each one.
- **Trash operations require Android 11+.** On older versions the app declines rather than
  deleting media without the platform confirmation dialog.
- **Remote AI is unverified against every provider.** The four provider shapes are implemented;
  only the ones the user configures are exercised.
