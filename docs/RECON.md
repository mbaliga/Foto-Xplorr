# FX-000 — Repository reconnaissance

**Date:** 10 August 2026 · **Baseline examined:** branch `claude/fotoz-ui-interactions-bxvgbw`
@ `8ae3dca` (= `main` @ `471be2d` + PR #8: viewer top room, notification room, rail marker
icons; submodule `shared-libraries` @ `938ed28` carrying the marker-slot change).

> Plan FX-IMP-002 states its baseline as `main @ 77781fd` — `main` has since advanced by one
> docs commit (`471be2d`, README rewrite, no code), and this branch carries PR #8 on top.
> Nothing in either invalidates the plan.

## ⚠ Findings that change the plan

1. **The similarity embedder is a hidden network feature.** `LocalModelManager` downloads
   MobileNetV3 from `https://storage.googleapis.com/mediapipe-models/...` via OkHttp on
   demand (`ai/LocalModelManager.kt:85–86,215`). The plan treats "local similarity" as
   offline-compatible (FX-012: "Keep local similarity … in offline-compatible modules") —
   it is not, as shipped. The offline build keeps embeddings only via the existing
   side-load path (`installFromUri`, SAF, no network). FX-012's scope grows to cover this.
2. **WP4 is an extension, not a redesign.** `cell-shell`'s `SpatialShell` already exposes
   all four room slots (`left/right/top/bottom` nullable params), both axes in the
   controller, four-way gating in `spatialEdgeDrag`/`returnDrag`, and `RoomEdge.TOP/BOTTOM`
   (`SpatialShell.kt:105–108`, `SpatialMotion.kt`). PR #8's viewer top room already consumes
   the top slot in production. FX-090's audit is answered; FX-091 needs no new edge API.
   Caveats: `VERTICAL_TRAVEL_FRACTION = 0.7` (vertical rooms travel 70% of height), and
   `EdgeTimelineScrubber` is hard-wired right-edge.
3. **The gallery materialises everything.** `SqliteMediaRepository` does one unbounded
   full-table read into a `MutableStateFlow<List<MediaAsset>>` at construction; the
   activity collects it into one Compose state value; the grid receives the full list.
   Mutations (`count()`, `upsert()`, `remove()`) read the in-memory mirror, and
   `GalleryContent` derives grouped/filtered copies per composition. **The 100k / p95
   150 ms target is unreachable as-is; paging is a WP2 ticket and it reworks the
   repository's mirror design, not just the query.**
4. **No re-selection UX for partial media grant.** The permission is declared and
   `hasMediaPermission()` treats a partial grant as granted, but there is no
   "manage selected photos" affordance anywhere — the only recovery is re-running the
   system dialog. FX-004's declaration half is already done; the degraded-mode UX is real
   feature work, flagged as a follow-up ticket (post-WP1), not silently absorbed.
5. **The grid has one non-1:1 item even headerless.** The main grid path appends a
   trailing full-span footer spacer (`GalleryContent.kt:198`), so grid item count is
   `assets.size + 1`. Indices of *assets* still map 1:1 (the spacer is last), so the
   scrubber holds — but any code equating "grid item count" with "asset count" is off by
   one today. Also: `TimelineGrid` with real date headers exists behind
   `showDateHeaders=true` on non-scrubber surfaces; the headerless invariant is pinned
   only by `DestinationBrowserScreen.kt:261` passing `false`.

## The nine questions

### 1. Does the merged manifest declare `INTERNET`?

**Yes — the app's own `src/main` manifest declares it,** plus `ACCESS_NETWORK_STATE`
(`app/src/main/AndroidManifest.xml:18–19`), for the BYOK remote-AI path, the MapLibre map
and the embedder download. No source-tree library manifest (hyle, crash-recovery ×2,
hyle-probe, wallpaper, cell-shell) declares any permission. External AARs (maplibre,
mlkit, okhttp) could contribute more via manifest merge — which is exactly why FX-011's
gate reads the **merged** manifest, not the sources. WP1 is therefore the full job the
plan describes: move both permissions to `src/connect`, and gate on the merged output.

### 2. Where do MapLibre's style and tiles come from?

**Fully remote, nothing bundled.** Style: `https://tiles.openfreemap.org/styles/liberty`
(`RichPhotoMapScreen.kt:515`, applied at `:304`); hillshade DEM tiles:
`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png` (`:526`, added
`:382–391`); vector tiles/sprites/glyphs arrive via the OpenFreeMap style. No `assets/`
dir; `res/raw` holds one `.wav`. → ADR-006 (FX-013) is a genuine product decision.
`SpatialPhotoSceneScreen` (compass/3D scene) is custom OpenGL, no network.

### 3. ML Kit bundled or unbundled?

**Bundled** — `com.google.mlkit:face-detection:16.1.7`, `image-labeling:17.0.9`,
`text-recognition:16.0.1` (`app/build.gradle.kts:127–129`), with an in-repo comment
explaining the choice (~121 MB across 4 ABIs, deliberate price of the offline guarantee).
Recognition (Pets/People/Identity) is offline-safe. **But see finding 1:** MediaPipe
similarity embeddings are not.

### 4. DB schema version and opening mechanism

Four plain `SQLiteOpenHelper`s. **None enable WAL, none enable foreign keys, none
override `onConfigure`.** No Room anywhere.

| Helper | DB | Version | Tables | onUpgrade |
|---|---|---|---|---|
| `CatalogueOpenHelper` (`media/SqliteMediaRepository.kt:72`) | `foto_xplorr_catalogue.db` | **3** | `media` | additive ALTERs |
| `GeoOpenHelper` (`spatial/GeoMetadataRepository.kt`) | `foto_xplorr_geo.db` | 1 | `geo_metadata` | no-op |
| `RecognitionOpenHelper` (`recognition/RecognitionStore.kt`) | `foto_xplorr_recognition.db` | 1 | `asset_recognition`, `face_descriptor` | drop-and-recreate |
| `EmbeddingOpenHelper` (`ai/EmbeddingRepository.kt`) | `foto_xplorr_embeddings.db` | 1 | `embeddings` | no-op |

`LibraryStore`, `FavoriteStore`, `CatalogStore` are SharedPreferences, not databases.
WP2's migration design (FX-021) starts from schema version 3 on the catalogue DB only.

### 5. Does `GalleryScreen` page from the database?

**No — full materialisation** (finding 3 above). Paging is a WP2 ticket.

### 6. cell-shell public API — seam for additional edges?

**All four edges already exist** (finding 2 above). Drag arbitration: the shell's
`spatialEdgeDrag` runs on `PointerEventPass.Initial` and consumes only after winning a
slop race; `EdgeTimelineScrubber` runs on the Main pass, yields via
`awaitFirstDown(requireUnconsumed = true)` + `isConsumed` checks, and abandons
horizontal-slop gestures unconsumed. Four-way arbitration is live in production today
(viewer: top room + horizontal photo-swipe + pinch zoom coexist).

### 7. Exact versions

All three builds agree — the composite constraint holds.

| | Foto-Xplorr | hyle-design-system | shared-libraries |
|---|---|---|---|
| AGP | **8.9.1** (root `build.gradle.kts`; deliberately downgraded from 9.3.1 to match Hyle — comment at `:2–9`) | 8.9.1 (`gradle/libs.versions.toml`) | 8.9.1 |
| Gradle wrapper | 8.14.3 | 8.14.3 | 8.14.3 |
| Kotlin | 2.1.0 | 2.1.0 | 2.1.0 |

Compose BOM `2025.05.01`; compileSdk 36, minSdk 26, targetSdk 36. **Load-bearing:**
`app/build.gradle.kts:75–84` force-pins `kotlin-stdlib` to 2.1.0 against transitive 2.4.0
pulls — any Kotlin bump must revisit that block; any AGP bump must move all three repos
in lockstep.

### 8. `READ_MEDIA_VISUAL_USER_SELECTED` declared? Grid headers?

**Declared** (`app/src/main/AndroidManifest.xml:25`) and requested on 34+. Partial-grant
handling is implicit only (finding 4). Grid: main path is headerless with one trailing
footer spacer (finding 5); no `stickyHeader` anywhere in `gallery/`.

### 9. `buildConfig` build feature?

**Already on** (`app/build.gradle.kts:35`), no `BuildConfig.` reads in app sources yet.
`applicationId` = `namespace` = `com.fotoxplorr.app`; debug adds `.debug` suffix +
`-debug` versionName suffix; versionCode 3, versionName `0.3.0-ai-spatial`. FX-010 keeps
`offline` at the existing IDs and gives `connect` a `.connect` suffix.

## Existing test inventory (feeds FX-003)

18 test files. Directly relevant: `GalleryProjectionTest` (folder identity, search, size
sort, locked/unlocked, trash scoping), `GalleryProjectionV2Test` (timeline grouping,
everyday exclusions, smart-album identification, duplicates, sensitive),
`TimelineStopsTest` (9 golden cases incl. oldest-first + modified-time fallback),
`ScanPlanTest` (7 cases incl. 10-s rewind and `>=`), `MediaIndexerTest` (6 cases incl.
*"a delta scan must never replaceAll"* and watermark advance/hold). FX-003 therefore
**extends** with: fixed-seed 10k-catalogue goldens (count + ordering fingerprint) for all
nine destinations and all eleven smart albums, and out-of-order watermark monotonicity.

Locations the plan guessed wrong: `destinationAssets` lives in
`gallery/DestinationBrowserScreen.kt`; the watermark in `media/PrefsScanWatermark.kt`;
`ScanState` in `FotoXplorrActivity.kt` (UI state mapped from `ScanEvent`).

## CI note (FX-001a)

The Actions blockage reported in the handoff is **not reproducing** — four green runs
observed 2026-08-09 on this repo and Shared-Libraries-asoc (~2–4 min each). See
`docs/ci/UNBLOCK.md` for the recurrence procedure. `scripts/verify.sh` is authoritative
regardless.
