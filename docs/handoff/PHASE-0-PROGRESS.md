# Phase 0 progress log

Baseline: `main` @ `5e59d56`. Branch: `claude/fylz-fotoz-complete-y60pfw` (see note below).

**Branch note:** the brief specifies branching as `claude/phase-0-defects` from `main`. This run's
harness assigns a fixed shared branch, `claude/fylz-fotoz-complete-y60pfw`, across this repo and
four others; a different branch name would leave the work unpushable through the harness's PR flow.
The branch already exists at the `main` baseline, so this is a naming substitution only — every
other instruction in the brief (one commit per item, draft PR, etc.) is followed as written.

## Items

| Item | Status | Commit SHA | Notes |
|---|---|---|---|
| P0-00 | done | 406d41a | brief + progress log |
| P0-01 | done | (this commit) | `browsableAssets` added; Places/map, Calendar, Settings preview, People strip and Collections fixed — see audit table below |
| P0-02 | todo | | |
| P0-03 | todo | | |
| P0-04 | todo | | |
| P0-05 | todo | | |
| P0-06 | todo | | |
| P0-07 | todo | | |
| P0-08 | todo | | |
| P0-09 | todo | | |
| P0-10 | todo | | |
| P0-11 | todo | | |
| P0-12 | todo | | |
| P0-13 | todo | | |
| P0-14 | todo | | |
| P0-15 | todo | | |
| P0-16 | todo | | |
| P0-17 | todo | | |
| P0-18 | todo | | |
| P0-19 | todo | | |
| P0-20 | todo | stretch — only if everything else is done | |

## P0-01 consumer audit

`browsableAssets(assets, archivedIds, sensitiveIds, lockedFolders, unlockedFolders, hideSensitive)`
added to `gallery/GalleryProjection.kt` as the single "may be shown right now" rule; `everydayAssets`
re-expressed on top of it (same predicates, different order of application — `.filter` preserves
input order regardless, so this is not expected to move any golden, and none did on manual review).

| Consumer | Symbol | Before | Fix |
|---|---|---|---|
| `gallery/GalleryScreen.kt:317` | `spatialAssets` (fed Places/map/compass/3D scenes AND `geoRepository.indexMissing`) | `state.assets.filterNot { it.isTrashed }` for both | Split into `spatialIndexInput` (unchanged — an indexer must see locked/archived items so unlocking never needs a re-index) and `spatialDisplayAssets` (now `browsableAssets(...)`) for what's actually drawn |
| `gallery/DestinationBrowserScreen.kt` `CalendarScreen` call | Calendar | `state.assets` (raw) | `calendarAssets`, a `remember`ed `browsableAssets(...)` |
| `gallery/SettingsTabs.kt` `sampleAsset` | Settings preview photo | `state.assets.firstOrNull { !it.isVideo }` | `browsableAssets(...).firstOrNull { !it.isVideo }` |
| `gallery/DestinationBrowserScreen.kt` `PeopleStrip` call | People-strip cover thumbnails | `assets = state.assets` (raw) even though the grid right below it in the same composable already receives the filtered `assets` parameter | Now passes the same already-filtered `assets` parameter — a real bug found during the audit, not just the three cases the brief named |
| `gallery/GalleryScreen.kt` `BrowserRoute.Collection` branch | Opening a collection | Checked membership + not-trashed only; never checked locked folders at all | Added `asset.isPrivacyVisible(lockedFolders, unlockedFolders)` — archived/sensitive items stay visible in a collection deliberately (a collection is a curated list, not the timeline), but a locked folder is never optional. Another bug found during the audit. |
| `gallery/DestinationBrowserScreen.kt` `destinationAssets()` | Every rail destination (Photos/Videos/Pets/People/Identity/Favourites/Screenshots) | Already routes through `everydayAssets`/`smartAlbumAssets`, both privacy-safe | No change needed |
| `gallery/GalleryScreen.kt` `assetsForAlbum`/`smartAlbumAssets` call sites (device albums, smart albums, Discover summaries) | — | Already privacy-safe (`isPrivacyVisible` applied inside `GalleryProjection.kt`) | No change needed |
| `gallery/GalleryScreen.kt` `BrowserRoute.Tag` branch | Opening a `#tag` view | Checked tag membership + not-trashed only; missing the same locked-folder check `Collection` (directly above it) had just been given | Added the identical `asset.isPrivacyVisible(lockedFolders, unlockedFolders)` check — same reasoning as `Collection`. Found by the background audit as a likely missed spot in the same edit. |
| `gallery/GalleryContent.kt` `AlbumsScreen` | Device-folder grid's own filtering was fine (trashed + archived, plus already-correct `cover = null` locked-folder handling), but the **Collections** section's cover lookup read the raw, unfiltered `assets` parameter directly | `AlbumsScreen` now takes `sensitiveIds`/`hideSensitive`, builds a `browsable` list via `browsableAssets(...)`, and the collection cover lookup (`collection.mediaIds.firstNotNullOfOrNull { ... }`) reads from `browsable` instead of raw `assets`. Device-folder `available` is left as its own (trashed+archived+video-pref only) list, since locked folders are already handled per-album and a collection's own archived/sensitive members are still meant to show (matching the `Collection` route's own rule) — only the *cover thumbnail* needed the fix, so a locked folder's photo can never surface as a collection's cover art. Found by the background audit. |
| `gallery/DestinationBrowserScreen.kt` `LIBRARY` `trashCount` | Library screen trash count | `state.assets.count { it.isTrashed }` | Left as-is with a one-line comment: a count, not a content display — a locked folder's trashed items still count toward "how many things are in Trash" |
| `gallery/DestinationBrowserScreen.kt` `TIDY_UP` `archiveReviewItems` | Archive-suggestion review queue | Already filters `isPrivacyVisible` (pre-existing, documented at the call site) | No change |
| `gallery/DestinationBrowserScreen.kt` `DISCOVER` `smartAlbumSummaries` | Smart-album counts/covers | Needs the full set to compute each album's own definition correctly (e.g. the Trash/Archived albums show exactly those); privacy-filtered internally per album via `smartAlbumAssets` | No change |
| `spatial/GeoMetadataRepository.kt` `indexMissing`/`clearAndReindex`, `recognition/RecognitionIndexer.kt` `index`, `recognition/RecognitionStore.kt` `pendingAssets`, `curate/AutoCurationPass.kt` `run`, `LibraryBackgroundWork.kt`, `FotoXplorrActivity.kt`'s recognition/auto-curation triggers | Background indexers/passes | Deliberately unfiltered beyond their own video/trash checks (and, for `AutoCurationPass`, an explicit locked-folder skip documented in its own KDoc) | No change — textbook "indexer must see everything so unlocking never needs a re-index" case |
| `spatial/PlacesScreen.kt`, `SpatialComposition.kt`, `SpatialPhotoLayout.kt`, `SpatialPhotoSceneScreen.kt`, `StampMapScreen.kt`, `experience/OpenGlPhotoScene.kt`, `PhotoSceneModels.kt`, `GalleryPreviewScreen.kt`, `ai/SimilarityExplorerScreen.kt` | Places/map, 3D scenes, photo wall, similarity map | All read `LocalSpatialExperience.assets`, i.e. the `spatialDisplayAssets` fixed above | Already fixed transitively; several have redundant local `!isTrashed` filters on top, harmless |
| `viewer/FilmstripScrubber.kt`, `gallery/GalleryContent.kt` `TimelineScreen`/`MediaGridScreen`, `TimelineStops.kt`, `gallery/GalleryActionsRoom.kt`, `GalleryDialogs.kt`, `fileops/MediaFileOperations.kt`, `metadata/MetadataWriter.kt`, `experience/PhotoSceneModels.kt` | Various pure display/selection components | Pure pass-throughs or explicit-selection operations, no filtering of their own | No change needed — correctness depends on (now-fixed) callers |
| `gallery/GalleryInfoRoom.kt` `state.assets.size` under "Everything" | Library-wide total count | Deliberately raw | No change — a total-count display, the audit's own worked example of a legitimate exception |
| `search/GallerySearch.kt` | — | Confirmed dead code — zero call sites anywhere in the app; live search goes through `MediaAsset.matchesGallerySearch` in `GalleryScreen.kt`, always applied on top of an already-filtered list | No change; flagged so nobody assumes this file is live |

Two items were surfaced as **product decisions, not bugs**, and are left as-is pending an owner call:

- `ai/SimilarityIndexer.kt`/`ai/EmbeddingRepository.kt`'s only caller (`SimilarityExplorerScreen`) now feeds them the filtered `spatialDisplayAssets` list, so — unlike the other indexers in this app, which deliberately index locked/archived/sensitive content — the similarity index is scoped to currently-browsable photos only. Unlocking a folder or turning off "hide sensitive" will need a fresh manual "Index library" run to pick those photos up. This may be an intentional trade-off for a manual, user-triggered index; logged rather than changed.
- `smartAlbumAssets` (`GalleryProjection.kt`, pre-existing, not part of this item's scope) does not exclude archived items for any bucket except its own `ARCHIVED` bucket, so the `Videos`/`Screenshots`/`Favourites`/`Recent` etc. destinations can still show archived photos. `browsableAssets` and `smartAlbumAssets` are therefore not fully equivalent by design; flagging since a future reader might assume otherwise.

Audit performed by a background research pass across `spatial/`, `experience/`, `ai/`, `recognition/`,
`curate/`, `search/` plus the files named directly in the brief; the two real bugs it found
(`BrowserRoute.Tag`, `AlbumsScreen`'s Collections cover) are fixed in this same commit.

## Decisions

- Branch name substitution (above), forced by the harness's fixed per-repo branch assignment.

## Owner questions

1. (P0-01) The similarity index (`ai/SimilarityIndexer.kt`) is now scoped to currently-browsable
   photos only, since its one caller feeds it the same filtered list the map/3D scenes use.
   Unlocking a folder or turning off "hide sensitive" will need a fresh manual "Index library" run
   before those photos show up in the similarity map — unlike the geo and recognition indexers,
   which pre-index everything. Intentional trade-off, or should it index everything like the others?
2. (P0-01) `smartAlbumAssets` only excludes archived items for its own `ARCHIVED` bucket, so the
   `Videos`/`Screenshots`/`Favourites`/`Recent` smart albums and destinations can still show
   archived photos. Pre-existing behaviour, out of this item's scope to change — flagging in case
   it should be reconciled with `browsableAssets` in a later phase.

## Device checks added

(populated as items land; consolidated into `docs/device-test/phase-0-checklist.md` at P0-19)
