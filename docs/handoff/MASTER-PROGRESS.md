# Master plan progress log

Plan: `docs/handoff/MASTER-PLAN.md`. Phase 0: done (PR #13).
Current phase: **Phase 1 — Foundation**. Branch: `claude/fotoz-p1-foundation`.

## Accepted ADRs for Phase 1 (they override the plan where they differ)

- **ADR-009 — Toolchain.** No constellation upgrade. Stay on AGP 8.9.1 / Kotlin 2.1.0 / Gradle 8.14.3. Room 2.7.x KMP, KSP 2.1.0-1.0.29. Native targets opt-in via `-Pfotoz.native=true`, plus a CI job `core-native`. WP1.1 is now "set up the KMP build on the pinned toolchain and write CONSTELLATION-TOOLCHAIN-UPGRADE.md". It needs **no** submodule changes.
- **ADR-010 — Module split.** `:core:model`, `:core:formats`, `:core:metadata`, `:core:search`, `:core:organize`, `:core:db` (smoke), and a `:core:index` placeholder. JVM first, native where ready. No `android.*`, `java.*`, `javax.*` or `org.w3c.*` in `commonMain`. One package per commit.
- **ADR-011 — Catalogue v2.** One `fotoz.db` (Room, bundled SQLite, FKs) plus `fotoz-vectors.db`. App-owned `asset_id`; `source` table; source-scoped `folder_key`. A 10-step forward-only, resumable, verified migration. Trash is indexed; only a permanent delete removes rows.

## Owner decisions (24 Sep 2026): apply in WP1.0

1. **Indexing scope:** every indexer (geo, recognition, similarity/embeddings, traits, moments) covers **all assets, including trashed**. Only a permanent delete ("shred") removes indexed data. Display filtering stays in the UI (`browsableAssets`). This reverses the P0-01 scoping of the similarity indexer.
2. **Archived** items are hidden everywhere except the Archived album, in every destination and smart album. Implement in WP1.5 as SQL, with an allowed golden change: list each affected golden in the commit.
3. **Watermark:** off by default for everyone. Keep the `ProEntitlement` seam, but gate nothing with it until the owner chooses a monetization model (open question below).
4. **Zip exports** never carry a watermark (already true; keep it).
5. **Background ML** (recognition, similarity, moments, traits) requires **charging** by default. There's a setting to allow it on battery. Change the `WorkRules` defaults and migrate existing installs only if the user never edited their rules.

## Work packages

| WP | Status | SHAs | Notes |
|---|---|---|---|
| 1.0 Apply owner decisions 1, 3, 5 (2 lands in 1.5) | done | WP1.0 | Similarity indexer now covers all assets incl. trashed via a new `indexInput` field threaded through `SpatialExperience`/`PlacesScreen`; display (`assets`) stays browsable-filtered. Watermark forced off (`resolveWatermark` returns `false` unconditionally); `ProEntitlement` seam kept wired at every call site, unused. `WorkRules.requireCharging` default flipped to `true`; `WorkRulesStore.load()` migrates per-key via `preferences.contains(KEY_REQUIRE_CHARGING)` so only installs that never touched charging specifically pick up the new default. |
| 1.1 KMP build on the pinned toolchain (ADR-009) | done | WP1.1 | New `gradle/libs.versions.toml`: Room 2.7.1 (Hyle's proven pin, not the newer 2.7.2), `sqlite-bundled` 2.5.0 (Room 2.7.1's own line), KSP 2.1.0-1.0.29 (matches Hyle), coroutines 1.10.2 (matches app), serialization-json 1.8.1, datetime 0.6.2, okio 3.9.1 — none consumed by any module yet, catalog only. Root `build.gradle.kts` pre-declares the 4 new plugins (`apply false`). `scripts/verify.sh` gained an additive `--native` flag (`-Pfotoz.native=true`, currently a no-op — no native-ready module exists until WP1.2). `docs/handoff/CONSTELLATION-TOOLCHAIN-UPGRADE.md` and `docs/DEPENDENCIES.md` written. The `core-native` CI job (ADR-009 item 5) is deliberately **not** added yet — it needs a real module's task paths to reference; see Decisions. Verified: `:app:dependencies --configuration offlineReleaseRuntimeClasspath` still resolves `kotlin-stdlib` to 2.1.0 everywhere (no drift), `./scripts/verify.sh --native` green. |
| 1.2 Module split (ADR-010) | done | WP1.2a, WP1.2b, WP1.2c, WP1.2e, WP1.2d | Batch 1: `:core:model` (`MediaId`), `:core:formats` (`MediaFormat`, `RawVariant`, `AnimationSniffer`, `SVG_MIME_TYPE`), `:core:metadata` (`MetadataStripper` behind `ByteSink`, `XmpPacket` in jvmMain). Batch 2: `:core:search` (`SearchQuery`/`SearchDocument`/`SearchSuggestions`, `java.time` ported to kotlinx-datetime). Batch 3: `:core:organize` (`RenamePattern`/`BulkRenamePlanner`, `SweepPolicy`, `ScanPlan`, `GridIndexMap`). Batch 5 (landed out of order, as `WP1.2e`): `:core:index` placeholder (jvm + native, no real content until WP1.4). Batch 4 (`WP1.2d`, landed last): `:core:db` smoke module -- `@Database`+`@ConstructedBy`+`expect RoomDatabaseConstructor`, Room + KSP + `BundledSQLiteDriver` proven end to end (insert/read round-trip test green) on Android and JVM, plus `compileKotlinLinuxX64`/`compileKotlinLinuxArm64` green. Cost real time to land: an apparent Kotlin/KSP2 "JVM leaf target only" compiler bug turned out to be a misdiagnosis of a much simpler root cause (a hand-written `actual object` colliding with the one Room's own KSP processor generates) -- see Decisions for the full story, worth reading before WP1.3 writes the real schema against this same pattern. |
| 1.3 Catalogue v2 + migration (ADR-011) | in progress | WP1.3, WP1.3b, WP1.3c | Schema (`FotozDatabase`, `FotozVectorsDatabase` -- 22 Room entities/DAOs across both files, matching ADR-011 §2's DDL exactly), the migration engine (`MigrationToV2`, all 10 steps of ADR-011 §5 including a real bidirectional catch-up reconciliation, not just the additive pass the ADR's own prose reads as), and the Android glue reading the real seven old stores (`AndroidLegacySource`/`AndroidMigrationEnvironment`/`AndroidProjectionParityCheck` in `:app`) are all done and tested: 9 JVM tests in `:core:db` (schema FK/cascade/raw-PRAGMA-check proofs, migration correctness/kill-resume/catch-up-reconciliation/delete-semantics) plus 13 Robolectric tests in `:app` against the real old SQLite/prefs formats. `WP1.3b`: `LibraryRuntime` now really builds `fotoz.db`/`fotoz-vectors.db` and runs `MigrationToV2.run()` in the background on every process start, against this device's/emulator's real old stores -- proves the whole pipeline end to end, not just against `FakeLegacySource`/Robolectric fixtures. `runCleanupIfDue()` (the step that deletes the six old SQLite files and four prefs files) is deliberately **not** called yet -- see Decisions. `WP1.3c`: the first two store facades, `com.fotoxplorr.core.db.catalogue.CatalogueFavoriteStore`/`CatalogueSensitiveStore`, built and tested in `:core:db` (6 new JVM tests) -- but **not wired into `LibraryRuntime` or the live UI**, and not just waiting on device verification: doing so now would regress favoriting/marking-sensitive for any photo taken after migration's one-time `ASSETS` step ran, since nothing keeps `asset` current until WP1.4's sync engine exists. See Decisions for the full reasoning and the simplified bridge design (no in-memory index after all). |
| 1.4 Sync engine | done (core scope) | WP1.4a, WP1.4b | `:core:index` is real now: `Source` interface (`enumerate`/`open`/`thumbnail`/`capabilities`, MASTER-PLAN.md §2.3), `SyncEngine` (mark-and-sweep, per-volume generation tracking via `SyncToken(version, generation)`, the whole-source OFFLINE/ONLINE-reconnect case), all pure `:core:db`-DAO-driven logic with zero Android dependency -- same shape as `MigrationToV2`. 12 JVM tests cover MASTER-PLAN.md §2.3's acceptance bar verbatim (unplug -> OFFLINE not deleted; a mtime-preserved change is still picked up by the delta; partial access never sweeps) plus TRAPS #1/#8 pinning, FK-id-stability, baseline-row creation, and the version-reset/reconnect edge cases. `folderKey`/`assetRevision`/`newMediaStoreSource`/`baseAssetUser` moved out of `migration/` into `:core:db`'s own root package (`AssetIdentity.kt`) so both `MigrationToV2` and `SyncEngine` compute identical values -- no behaviour change to migration itself. `WP1.4b`: the real Android glue -- `AndroidMediaStoreSource` (per-volume `MediaStore` queries, `getVersion`/`getGeneration`/`GENERATION_MODIFIED` API-level-gated per real, verified platform history: Q/29 for `getExternalVolumeNames`/`getVersion`, R/30 for `getGeneration`/`GENERATION_MODIFIED`) and `AndroidMediaSync` (volume discovery: new/offline/reconnecting sources, matching `MigrationToV2.primarySourceId()`'s exact pre-Q fallback locator). Wired into `LibraryRuntime`: runs once per process start, right after `MigrationToV2.run()`, same "safe because nothing live reads `fotoz.db`'s `asset` table yet" reasoning. Not hooked into the existing `scanRequests`/`MediaStoreChangeObserver` loop yet -- see Decisions. |
| 1.5 Paging and SQL projections | in progress | WP1.5a | `com.fotoxplorr.core.db.gallery.GalleryProjectionDao` (`:core:db`): SQL translations of `GalleryProjection.kt`'s `everydayAssets` and all 11 `SmartAlbum` queries, applying owner decision 2 (archived hidden everywhere except the `ARCHIVED` album itself, with `TRASH` a deliberate exception -- see Decisions), plus a `DUPLICATES` window-function query matching `duplicateCandidateIds`'s exact keeper-selection rule and a folder drill-down query. Plain `LIMIT`/`OFFSET` paging, not yet real Paging 3 or a windowed `Flow`. 15 JVM tests, one per query's distinguishing predicate plus a paging test. **Not wired into the live UI** -- two real gaps versus the Kotlin functions it's meant to replace (locked-folder visibility, free-text search) are deliberately left open in this first increment; see Decisions for the full list of what WP1.5 still needs. |
| 1.6 App architecture (ViewModels, job service, audit log) | surveyed, deferred | | Rotation is already handled by a *different*, pre-existing mechanism (`android:configChanges` on every activity, P0-09) -- Activities are never recreated on rotation at all, so that half of the acceptance bar is already met without ViewModels. The genuine remaining gaps: zero `ViewModel` usage anywhere in the app (only one `rememberSaveable` call site total), so state does not survive process death; `MediaFileOperations.copyToTree` and friends are plain `suspend fun`s in UI-lifecycle-scoped coroutines with no foreground service at all, so a long copy is cancelled, not paused, if the app is backgrounded/killed; `audit_log` (schema-ready, `AuditLogDao` already exists) has zero live callers because permanent delete/"shred" (ADR-011 §6) isn't implemented in the live app yet -- only Trash is. Deliberately not attempted this increment -- see Decisions for why. |
| 1.7 Format registry | in progress | WP1.7a | `com.fotoxplorr.core.formats.FormatSniffer` (`:core:formats`): magic-byte identification from a file's real leading bytes, independent of mime/extension -- 20 new formats (Ico, Avif, JpegXl, Tiff, Psd, Exr, Hdr, Pnm, Jp2, Jxr, Qoi, Dds, Ktx, Icns, Pcx, Xcf, plus HEIF-vs-AVIF ISOBMFF-brand disambiguation) added as new `MediaFormat` cases, each signature checked against a primary/authoritative source before being written down. 52 JVM tests (one per format, plus near-miss/false-positive guards for the trickiest signatures). Deliberately not attempted this increment: the `Capability` matrix and `DecoderProvider` SPI (premature before WP1.10's metadata work and Phase 2's actual decoder packs exist to describe), and the "a black tile is impossible" Robolectric render test (needs `isLikelyDecodable` actually wired into the live gallery-tile composable, which nothing does today -- see Decisions). |
| 1.8 Test infrastructure | in progress | WP1.8a | Macrobenchmark (`benchmarks/`, already fully built, previously enabled only by manually editing `settings.gradle.kts`) is now gated behind `-Pfotoz.benchmarks`, matching `-Pfotoz.native`'s exact pattern: `settings.gradle.kts`'s `include(":benchmarks")` and `:app`'s matching `benchmark` build type are both conditional on the same property, so a plain build's variant matrix is completely unaffected (verified: `:app:compileOfflineDebugKotlin` unaffected without the flag; `:benchmarks:compileBenchmarkSources` and `:app:compileOfflineBenchmarkKotlin` both succeed with it). `androidTest` source set, format corpus + `SOURCES.md`, and the 500k perf baseline regeneration are **not** attempted this increment -- see Decisions. |
| 1.9 Governance checks in CI | in progress | WP1.9a | `scripts/verify-core-purity.sh` (ADR-010 §2's `commonMain` import ban -- no `android.*`/`java.*`/`javax.*`/`org.w3c.*` -- as a fast, always-on grep gate in `verify.sh`, checked before the slow Gradle build). **Fixed a real, previously-silent bug**: `verify.sh --native`'s `NATIVE_TASKS` list had been left empty since WP1.1 (a stale "no native-ready module yet" comment), so `./scripts/verify.sh --native` had never actually compiled or run anything native all session, despite every native-target claim in this log being individually verified by hand via direct `./gradlew ...compileKotlinLinuxX64` calls. Fixing it immediately caught a real, separate bug: `FormatSnifferTest.kt`'s (WP1.7a) comma-containing backtick test names fail Kotlin/Native's stricter symbol-name rules (JVM tolerates them; K/N doesn't) -- fixed by renaming those five tests. The real task list now lives in `scripts/verify-native.sh`, which is both what `verify.sh --native` calls and the new `core-native` CI job's (ADR-009 item 5, "required once WP1.2 lands") own direct entry point -- one definition, not two. Also added `scripts/verify-page-alignment.sh` (TRAPS #16, `zipalign -c -P 16` as a read-only regression guard -- confirmed by hand that AGP 8.9.1 already produces correctly-16KB-aligned debug AND release APKs today, wired into CI's release-APK step) and gated Macrobenchmark behind a Gradle property (folded in as WP1.8a, see that row). Licence scan **not** attempted -- see Decisions. |
| 1.10 Metadata core (XMP sidecars) | in progress | WP1.10a | **Scope corrected against ADR-010 before any code was written -- see Decisions**: MASTER-PLAN.md's own WP1.10 acceptance bar ("TIFF-IFD + XMP parser in commonMain") is superseded by ADR-010's explicit, accepted decision that `XmpPacket` stays jvmMain-only and a native/commonMain XMP parser is Phase-8-prep, "deferred and recorded as an open item" -- not Phase 1 work, and not a gap, since `:app` already consumes `:core:metadata`'s `jvm` target directly today (confirmed: Android has never needed a KMP `android()` target here). `WP1.10a` builds the one genuinely new, not-yet-built piece MASTER-PLAN.md's WP1.10 also names and ADR-010 does NOT defer: XMP sidecar file support. `com.fotoxplorr.core.metadata.SidecarNaming` (`:core:metadata` commonMain) -- pure filename arithmetic for both styles WP1.10 names, darktable (`IMG_1234.CR3` -> `IMG_1234.CR3.xmp`, full name kept) and Lightroom (`IMG_1234.CR3` -> `IMG_1234.xmp`, extension replaced), each verified against primary/community documentation (darktable's own 4.6 user manual) before being encoded, not assumed -- same discipline as WP1.4b/WP1.7a. 8 commonTest tests (also native-green). `com.fotoxplorr.core.metadata.XmpSidecarStore` (jvmMain) -- plain-`File` read/write of a sidecar, reusing `XmpPacket.parse`/`serialize` unchanged (a sidecar's content IS an XMP packet) and mirroring `readXmpAttribute`'s own three-state contract (no sidecar = `empty()`, unparseable sidecar = `null`/skip, parseable sidecar = the real packet). 8 jvmTest tests, including a hand-authored fixture shaped like a real darktable sidecar (`<?xpacket?>` wrapper, multiple namespaces). Deliberately NOT attempted this increment -- see Decisions: the Android/SAF/MediaStore glue to locate or create a sidecar next to a real scoped-storage asset; rating/color-label/flag round-trip to XMP; darktable's numbered "duplicate" sidecars; reconciling two sidecars for one photo (that's `sidecar_state`, ADR-011 §2, explicitly "Phase 3/4"). |
| 8.1 [ADR] UT architecture -- `:ut:bridge` C API (Phase 8, **started ahead of schedule** -- see Decisions) | in progress | WP8.1 | New Kotlin/Native-only module `:ut:bridge` (`ut/bridge/`), `linuxX64`/`linuxArm64`, whose Gradle *project inclusion itself* (not just its targets) is gated behind `-Pfotoz.native=true` in `settings.gradle.kts` -- it has no non-native target at all. Compiles to a real, linked `libfotozcore.so` + generated C header (`:ut:bridge:linkDebugSharedLinuxX64` verified by hand; `nm -D` confirms all 15 exported symbols), matching MASTER-PLAN.md's own coarse C API list exactly: `fotoz_library_open`/`fotoz_library_close`, `fotoz_capabilities`, `fotoz_query_page`, `fotoz_asset_detail`, `fotoz_set_metadata`, `fotoz_sync_start`, `fotoz_ops_execute`, plus `fotoz_status_ok`/`_invalid_handle`/`_bad_request`/`_not_found`/`_not_implemented`/`_io_error`/`_internal_error` and `fotoz_free_string`. Opaque `COpaquePointer` handles (a `StableRef`-wrapped `LibraryHandle`) and JSON byte buffers only (ADR-013 point 2) -- no Kotlin object graph crosses the C boundary. **Real, backed by genuinely native-ready code, not fakes:** `library_open` (Room KMP + `BundledSQLiteDriver`, `:core:db`'s real `FotozDatabase`/ADR-011 schema -- the same construction `LibraryRuntime.kt` uses for Android, via the `jvmNative`-shared `Room.databaseBuilder(name, factory)` overload; forces a real query at open time since Room's own `.build()` doesn't open the connection eagerly, confirmed by this module's own test catching a false-positive open against a missing parent directory); `query_page` (routes to `:core:db`'s real, tested `GalleryProjectionDao` SQL by one of its 13 real query names, plus a narrow real free-text filter -- `:core:search`'s actual `parseSearchQuery()` feeding only bare, non-negated `Word` terms into a `display_name` match, explicitly NOT the app's fuller folder/tag/OCR/label search); `asset_detail` (real `AssetDao` + `AssetUserDao` merge); `set_metadata`'s `sidecar_name`/`strip` sub-ops (real `SidecarNaming`/`MetadataStripper` -- `stripBytes` widened `internal` -> `public` in `:core:metadata` for this cross-module call, same precedent as WP1.2's `isJpeg`/`isPng`/`isWebp`); `capabilities` (a bridge-local feature-matrix stand-in -- see Decisions for why this is NOT "the shared `Capability` model" ADR-013 point 3 names, which does not exist anywhere in this codebase). **Honestly stubbed, a documented sentinel status code returned, never a fake success:** `sync_start` (no native `Source` implementation exists -- WP1.4b's `AndroidMediaStoreSource` is Android-only; invokes its C callback once with the sentinel to prove the function-pointer ABI itself works); `ops_execute` (Fotoz has no Fylz-style operation engine -- journal/staging/recycle -- at any phase, checked, not assumed); `set_metadata`'s XMP field path (`XmpPacket`/`XmpSidecarStore` are jvmMain-only per ADR-010). 20 real tests in `linuxX64Test` (`:ut:bridge:linuxX64Test` green), calling every `@CName` function directly against a real temp-file `fotoz.db`, seeded through the same `baseAssetUser` invariant `MigrationToV2` itself relies on -- not a mock anywhere. `compileKotlinLinuxArm64` green (Tier 2, compile-only, no link attempted). `scripts/verify-native.sh` extended with this module's two tasks; `./scripts/verify.sh` (offline/connect) unaffected -- this module is Kotlin/Native-only and touches no `:app`/`:feature:*` code. No QML/Qt/C++ shell attempted -- out of scope for this WP, per its own brief; see ADR-013's dated addendum. |

## Module readiness (ADR-010 §3.5)

| Module | Packages moved | Native-ready | Blockers |
|---|---|---|---|
| `:core:model` | `MediaId` (moved from `media/MediaAsset.kt`) | Y (`linuxX64Test`/`compileKotlinLinuxArm64` green) | none |
| `:core:formats` | `MediaFormat` (now 25 cases, WP1.7a added 20), `RawVariant`, `AnimationSniffer`, `SVG_MIME_TYPE`, `FormatSniffer` (WP1.7a: magic-byte discovery) | Y (`linuxX64Test`/`compileKotlinLinuxArm64` green) | `GifDecoder`/`GifEncoder`/`GifTrimmer`/`GifFrame`/`Lzw` (`LzwGif`) stayed in `:app` -- not pure, see Decisions |
| `:core:metadata` | `MetadataStripper` (commonMain, behind `ByteSink`), `XmpPacket` (jvmMain), `SidecarNaming` (commonMain, WP1.10a), `XmpSidecarStore` (jvmMain, WP1.10a) | partial (stripper + `SidecarNaming` Y, `linuxX64Test`/`compileKotlinLinuxArm64` green; `XmpPacket`/`XmpSidecarStore` jvmMain-only, per ADR-010) | `readXmpAttribute`/`ExifInterface.xmpAttributeUtf8` stayed in `:app` -- `androidx.exifinterface` is Android-only, see Decisions. The Android-side sidecar file glue (locating/creating a sidecar beside a real MediaStore/SAF asset) also belongs in `:app`, not built yet -- see WP1.10's Decisions. |
| `:core:search` | `SearchQuery.kt` (whole: `SearchField`/`Comparison`/`Term`/`ParsedQuery`/`parseSearchQuery`), `SearchDocument.kt`, `SearchSuggestions.kt` | Y (`linuxX64Test`/`compileKotlinLinuxArm64` green) | `SearchChips.kt` (Compose UI) stayed in `:app`, per ADR-010's own note |
| `:core:organize` | `RenamePattern.kt`, `BulkRenamePlanner.kt` (`java.time` ported to kotlinx-datetime), `SweepPolicy.kt`, `ScanPlan.kt`, `GridIndexMap.kt` | Y (`linuxX64Test`/`compileKotlinLinuxArm64` green) | duplicate-keeper rule stayed in `:app` (`GalleryProjection.kt`/`ArchiveAdvisor.kt`), see Decisions |
| `:core:db` | Real ADR-011 §2 schema (`FotozDatabase`, `FotozVectorsDatabase`, 22 entities/DAOs) + `com.fotoxplorr.core.db.migration.MigrationToV2` (all 10 ADR-011 §5 steps) + `com.fotoxplorr.core.db.catalogue` (`CatalogueFavoriteStore`/`CatalogueSensitiveStore`, the first two ADR-011-Consequences store facades) + `com.fotoxplorr.core.db.gallery.GalleryProjectionDao` (WP1.5a's SQL smart-album/everyday projections) | Y (`compileKotlinLinuxX64`/`compileKotlinLinuxArm64` green) | `androidx.room:room-ktx` is Android-only (pulls `kotlinx-coroutines-android`) -- scoped to `androidMain`, not `commonMain`; `room-runtime` alone covers Flow-returning `@Query` on every target. `fotoz.db`/`fotoz-vectors.db` and `MigrationToV2.run()` are wired into `:app`'s `LibraryRuntime` (WP1.3b); the `catalogue` facades and `gallery` projections are not wired into `LibraryRuntime` or the live UI yet -- see WP1.3's and WP1.5's Decisions respectively. |
| `:core:index` | Real WP1.4 content: `Source`/`SourceEvent`/`SourceItem`/`SyncToken` (`Source.kt`) + `SyncEngine` (mark-and-sweep, generation sync, whole-source offline/reconnect) | Y (`compileKotlinLinuxX64`/`compileKotlinLinuxArm64` green) | Depends on `:core:db` directly (last in the `model ← formats ← metadata ← search/organize ← db ← index` chain) -- `SyncEngine` needs its DAOs/entities the same way `MigrationToV2` does. No `androidTarget()` declared (never has been; not needed -- `:app`'s Android compile resolves this module's `jvm()`/common target directly, confirmed by `:app:compileOfflineDebugKotlin` staying green). `AndroidMediaStoreSource`/`AndroidMediaSync` (the real `Source` implementation + volume-discovery orchestrator) live in `:app` (`app/.../index/`), same split as `AndroidLegacySource`/`MigrationToV2` -- built in WP1.4b and wired into `LibraryRuntime`. |

## Decisions

- **WP1.0 / decision 5, migration scope:** read "migrate existing installs only if the user never edited their rules" as scoped per-key, not per-object. `WorkRules` is one object holding several independent settings; a user who customized an unrelated control (e.g. active hours) never made a decision about charging specifically, so `requireCharging` still migrates to the new default for them. Only an install with an explicit prior write to `requireCharging` itself (`SharedPreferences.contains(KEY_REQUIRE_CHARGING)`) is left alone. If the owner intended the coarser "any edit at all freezes every default" reading, this needs revisiting.
- **WP1.0 / decision 1:** no dedicated unit test added for the trivial `assets` → `indexInput` filter removal itself (deleting a `.filterNot { it.isTrashed }` call); covered instead by the existing `SimilarityExplorerScreen`/spatial composition tests continuing to pass unchanged, since none of them asserted on trash exclusion in the first place.
- **WP1.0 / decision 5, test fallout:** flipping `WorkRules.requireCharging`'s default broke 8 pre-existing pure-logic tests (5 in `WorkRuleEvaluatorTest.kt`, 3 in `WorkRulesTest.kt`) that constructed `WorkRules(...)` without setting `requireCharging`, relying on the old `false` default to isolate other rules under test. Fixed by adding `requireCharging = false` explicitly at each affected call site, matching this codebase's stated testing philosophy (sensible defaults, override only what's under test).
- **WP1.1 / Room patch version:** 2.7.2 exists upstream (2.7.1 is what Hyle has actually proven on this toolchain). Stayed on 2.7.1 per ADR-009's own conservative bias ("newest 2.7 patch only if it builds green on this toolchain") since nothing has built 2.7.2 against this constellation yet. Revisit once `:core:db` (WP1.2/1.3) gives something real to test 2.7.2 against.
- **WP1.1 / `core-native` CI job deferred to WP1.2:** ADR-009 item 5's job runs `./gradlew -Pfotoz.native=true <every native-ready module>:linuxX64Test <every native-ready module>:compileKotlinLinuxArm64` — that needs concrete module task paths that don't exist until WP1.2 creates the first native-ready `:core:*` module. Adding the job now with no real tasks would either no-op uselessly or reference non-existent projects and fail CI. The ADR's own phrasing ("required once WP1.2 lands") reads as consistent with this deferral; `scripts/verify.sh --native` is in place now so WP1.2 only needs to add the job/task list, not any new plumbing.
- **WP1.1 / AndroidX Paging native support:** while researching the catalog, `androidx.paging:paging-common:3.5.1`'s Gradle module metadata shows `linuxX64`/`linuxArm64` API variants — Paging may now have real Kotlin/Native Linux support, which ADR-009 treats as an open question for WP1.5 ("verify it supports Kotlin 2.1.0 and a native Linux target before adopting"). Not adopted here; flagging so WP1.5 checks this rather than defaulting straight to the hand-written windowed `Flow` fallback without looking.
- **WP1.1 / constellation consumers visible to this session:** `Foto-Xplorr` and `Fyl-Manager` are the only Gradle/Kotlin composite consumers of `hyle-design-system`/`shared-libraries` this session can see; `asystemofcells` (Astro/Node) and `Music_Player` (Capacitor/JS) are not Gradle consumers at all. `Fyl-Manager` already pins Kotlin `2.1.20`, not `2.1.0` like the other three repos — a pre-existing divergence (not introduced here) that `docs/handoff/CONSTELLATION-TOOLCHAIN-UPGRADE.md` flags for whoever executes that future bump.
- **WP1.2 / `:core:model` scope narrowed:** ADR-010's `:core:model` row lists `MediaId/AssetId/SourceId, FormatId, Capability, rating/flag/label enums, the query AST types` as its WP1.2 content, worded as "moved, not rewritten". Only `MediaId` already exists as real code; `AssetId`/`SourceId`/`FormatId`/`Capability`/rating-flag-label enums are new catalogue-v2 vocabulary that ADR-011 (WP1.3) specifies concretely. Authoring them now, disconnected from the Room schema that actually defines their shape, risks getting them wrong and redoing them in WP1.3 anyway. `:core:model`'s WP1.2 scope is `MediaId` only; the rest lands in WP1.3 alongside the schema that needs them.
- **WP1.2 / "the query AST types" stayed with `:core:search`, not `:core:model`:** `SearchQuery.kt`'s AST (`Term`, `ParsedQuery`, `SearchField`, `Comparison`) is tightly coupled to the parser's own private helpers in the same file (shared `STOPWORDS`/`MONTHS`, `Term.withNegation`, etc.). Splitting the types out into `:core:model` while leaving the parser in `:core:search` would be a rewrite, not a move, and risks exactly the behaviour drift ADR-010 §3 step 2 guards against. Deferred to the `:core:search` commit, which moves `SearchQuery.kt` whole.
- **WP1.2 / `:core:formats`: GifDecoder/GifEncoder/Lzw/GifFrame/GifTrimmer stayed in `:app`:** ADR-010 conditions their move on being pure ("only if they're pure"). `GifDecoder.kt`/`GifEncoder.kt` use `java.io.ByteArrayOutputStream` and are not pure. Their sole helpers (`LzwGif`, an `internal object`; `GifFrame`, a data class) are used by nothing else, so moving only the helpers into `:core:formats` would fragment one cohesive, tightly-coupled cluster across two modules and force `LzwGif` from `internal` to `public` just to reach back across the module boundary to its only two callers. Kept the whole cluster together in `:app`. `AnimationIndex.kt` (SQLite/ContentValues) was never a candidate; it now imports `AnimationSniffer` from `:core:formats` instead of using same-package access. A new `FormatSniffer` (magic-byte sniffing for 40+ formats) is also listed on this ADR-010 row, but duplicates WP1.7's own explicit scope and acceptance criteria (`docs/handoff/MASTER-PLAN.md` §3) -- left for WP1.7 rather than half-built here.
- **WP1.2 / `:core:metadata`: the ExifInterface bridge stayed in `:app`:** `XmpPacket.kt`'s class itself only used `org.w3c.dom`/`javax.xml`, but the file also carried two free functions (`readXmpAttribute`, `ExifInterface.xmpAttributeUtf8`) bridging it to `androidx.exifinterface.media.ExifInterface`. `androidx.exifinterface` publishes an Android-only artifact (an AAR compiled against `android.*`) that a bare `jvm()` KMP target cannot link against -- this is a real technical constraint, not a style choice. Split the file at that exact boundary: the class moved to `:core:metadata`'s jvmMain, the two bridge functions stayed in `:app` (`com.fotoxplorr.app.metadata`), importing `XmpPacket` from its new home.
- **WP1.2 / cross-module `internal` visibility widened where a moved symbol's only external caller stayed behind:** `MetadataStripper.isJpeg`/`isPng`/`isWebp` were `internal` when `WritableFormat.kt` (`:app`) could reach them via same-module access; `internal` is module-scoped in Kotlin, so the move to `:core:metadata` required widening these three (and only these three -- every other private/internal helper in the file stayed exactly as scoped) to `public`. No behavioural change, since nothing outside this codebase can see the new module's public API anyway.
- **WP1.2 / `MetadataStripper` ported to `ByteSink`, not left on `java.io.OutputStream`:** the object's own body only ever calls `.write(bytes, offset, length)`/`.write(bytes)` on its `output` parameter, so the JVM-only surface was exactly the type, not the logic. A new `fun interface ByteSink` (`core:metadata` commonMain) replaces it; the old public entry point `MetadataStripper.strip(InputStream, OutputStream)` is now a jvmMain **extension function** (`MetadataStripperJvm.kt`) that adapts a real `OutputStream` to `ByteSink` and calls the (still `internal`, same-module) `stripBytes`. Every `:app` call site (`SharePreparer.kt`, `MetadataStripperJpegTest.kt`) needed one added import (`com.fotoxplorr.core.metadata.strip`) alongside the type import, since Kotlin resolves an extension function identically to a member at the call site but still requires it in scope.
- **WP1.2 / commonMain avoided two JVM-leaning stdlib surfaces that turned out not to be common:** (1) `@JvmInline` needs an explicit `import kotlin.jvm.JvmInline` in commonMain -- it is not a default import there the way it is on the JVM target; caught by the native compile (see below), not by the JVM compile, which resolved it via JVM's own default imports and hid the gap. (2) `String(bytes, offset, length, charset)` (the sub-range JVM constructor overload) was avoided in favour of a small hand-written `ByteArray.decodeAsciiSlice` helper in `MetadataStripper`, since every caller there decodes a fixed-length ASCII tag (a PNG chunk type, a WebP FourCC) and a byte-for-byte decode is exact; `.toByteArray(Charsets.X)` and `.decodeToString()` (both confirmed common) covered every other encode/decode site.
- **WP1.2 / test framework choice per moved test:** JUnit4 tests with zero Android/Robolectric dependency and no `java.io` blocker (`MediaFormatTest`, `AnimationSnifferTest`, `MetadataStripperTest`) ported to `kotlin.test` and moved to `commonTest`, so they also run under `-Pfotoz.native=true :core:<m>:linuxX64Test`. The one pitfall: `kotlin.test.assertEquals`/`assertFalse` take the message as a trailing named argument, not JUnit's leading positional one -- two calls in the ported `MediaFormatTest` needed their arguments reordered, not just their imports swapped, or they silently became type errors (a `String` landing in the `expected`/`actual` slot). `XmpPacketTest` (JVM-only content, `org.junit`) moved to `:core:metadata`'s `jvmTest` unchanged, per ADR-010 §3's "JUnit4 tests go to jvmTest unchanged".
- **WP1.2 / `:core:search`: `java.time` ported to kotlinx-datetime, `MonthNames` simplified from ADR-010's suggestion:** `SearchQuery.kt`/`SearchSuggestions.kt` used `LocalDate`/`YearMonth`/`ZoneId`/`Instant`/`ChronoUnit`/`Month.getDisplayName(TextStyle, Locale)`. Mapped `ZoneId`→`TimeZone`, `LocalDate.now(zone)`→`Clock.System.todayIn(zone)`, `.plusDays/.minusMonths/etc.`→`.plus/.minus(n, DateTimeUnit.X)`, `ChronoUnit.DAYS.between`→`LocalDate.daysUntil`, `dayOfWeek.value`→`dayOfWeek.isoDayNumber` (both ISO Monday=1). kotlinx-datetime has no `YearMonth`; added a small private one (`atDay`/`plusMonths`/`displayPhrase`) local to `SearchQuery.kt`. ADR-010 asks for "an injected MonthNames interface (the JVM implementation uses java.time.format.TextStyle)" -- built instead as one `internal object MonthNames` (commonMain) with a hardcoded English table, used on every target including JVM, since this app has never offered any locale but English and a fixed table is verified byte-identical to `java.time.Month`'s English FULL/SHORT names for every value this code passes. No expect/actual split for a locale seam current behaviour has no use for; revisit if real i18n is ever added. All 19 `SearchQueryTest` cases pass unchanged after the port (byte-identical epoch-millis window boundaries, verified against the original zone/today-injected test fixture).
- **WP1.2 / two more Kotlin/Native-only pitfalls, caught by `linuxX64Test` after WP1.2's first batch had already used `--native` successfully:** (1) **commas in backtick-quoted test names are illegal Kotlin/Native symbol characters** (JVM tolerates them freely) -- `Name contains illegal characters: ","` on `compileTestKotlinLinuxX64`. Fixed by renaming every comma-containing `` fun `...` `` test name across `AnimationSnifferTest`, `MetadataStripperTest` and `SearchQueryTest` (11 names) to use `--` or plain conjunctions instead, preserving meaning. Nothing in this codebase's own conventions flagged this in advance; it only surfaces once a test actually cross-compiles for a native target, so WP1.2's remaining native-ready modules should expect the same class of fix. (2) **`String.toByteArray()` (bare, no-arg, or with a `Charsets` argument) does not exist in commonMain at all** -- only `String.encodeToByteArray()` (UTF-8, common) does. This one is easy to miss because the JVM compiler resolves the JVM-only extension silently and gives no warning; only the native compile fails, with a confusing error (`Collection<Byte>.toByteArray()`/`UByteArray.toByteArray()` listed as the "close" candidates it tried instead). Fixed every String-receiver call site in `AnimationSniffer(Test)`/`MetadataStripper(Test)` to `.encodeToByteArray()` -- exact for these call sites since every string involved is 7-bit ASCII, where UTF-8 and ASCII encode identically.
- **WP1.2 / `:core:organize`: the "duplicate-keeper rule" stayed in `:app`, not extracted:** ADR-010 lists this as WP1.2 content, but the concrete rule lives split across `GalleryProjection.duplicateCandidateIds` (`MediaAsset`, ascending on raw `dateTakenMillis`) and `ArchiveAdvisor.bestOfGroup` (`ArchiveCandidate`, descending on a derived `ageMillis`) — two structurally different types, kept in sync today only by a comment on each cross-referencing the other, not by shared code. `GalleryProjection.kt` itself is `java.time`-heavy and explicitly deferred to WP1.5 by ADR-010's own context table; `ArchiveAdvisor.kt`/`curate/` was never listed as a WP1.2 move target at all. Forcing a shared generic comparator now -- before `GalleryProjection.kt` has a natural new home -- would mean extracting a rule out of a file that isn't moving yet, and papering over the real ascending/descending, raw/derived shape mismatch with an awkward sign-flip just to unify two call sites. Left both exactly as they are; revisit once WP1.5 gives `GalleryProjection.kt`'s own move a natural place for this.
- **WP1.2 / `RenamePattern`/`BulkRenamePlanner`: `java.time` ported, `String.format(Locale.US, ...)` was the other JVM-only surface:** `Instant`/`ZoneId` mapped the same way as `:core:search`'s port. The zero-padding of date/counter tokens used `String.format(Locale.US, "%0${width}d", value)` (JVM-only, `java.lang.String.format`) -- replaced with `value.toString().padStart(width, '0')`, exact since every value passed here (year, month, day, hour, minute, second, counter) is always non-negative, so there is no sign for `padStart` to get wrong that `String.format`'s zero-flag would have handled differently. The test fixture's own `"IMG_%05d.jpg".format(it)` (Kotlin's `String.format` extension, also JVM-only despite looking like plain Kotlin) needed the same `padStart` treatment when the test moved to `commonTest`.
- **WP1.2 / lint: `UseTomlInstead` disabled in `:app`'s lint block:** the moment `gradle/libs.versions.toml` exists (WP1.1), Android Lint's `UseTomlInstead` check starts flagging every OTHER hardcoded dependency coordinate in `app/build.gradle.kts` (35 of them, all on lines untouched by Phase 1) as "should use the catalog too". Migrating `:app`'s entire existing dependency block into the catalog is real, unplanned scope no WP asked for. Disabled just that one check (`app/build.gradle.kts`'s `lint {}` block, alongside the existing `DefaultLocale` precedent) rather than either doing that migration or leaving 35 unactionable warnings in every report. `GradleDependency`'s "a newer version exists" notices for the catalog's own deliberately-conservative pins (Room 2.7.1 vs. 2.8.5, etc.) were left alone -- that signal is accurate and already explained in the catalog's own comments and the Decisions above. Lint warning count after this WP1.2 batch: 91 (offline) / 93 (connect), 0 errors -- up from the 70/0 baseline WP1.0/1.1 held, entirely accounted for by the above (new `GradleDependency` notices for 4 new catalog entries, plus lint now also analysing 3 new project modules).

- **WP1.2 / `:core:db`: the real fix was "don't hand-write the `actual object RoomDatabaseConstructor`" -- Room's KSP processor generates it itself:** this cost most of a work session to root-cause because the failure mode pointed the wrong way. Symptom: with a hand-written `actual object SmokeDatabaseConstructor : RoomDatabaseConstructor<SmokeDatabase> { override fun initialize() = SmokeDatabase::class.instantiateImpl() }` in both `androidMain` and a plain `jvm()`/`jvm("desktop")` target (the pattern developer.android.com's own KMP Room guide shows), `androidTarget()` built clean but the JVM leaf target failed deterministically -- reproduced from a clean build dir, so not stale/incremental state -- with `The @ConstructedBy definition must be an 'expect' declaration`, pointing at the platform's own `actual object`. Extensive investigation (this session, plus an independent subagent re-derivation) ruled out: renaming the target from bare `jvm()` to `jvm("desktop")` (matching Google's own `kspDesktop` convention); `ksp.useKSP2=false`; the `kspCommonMainMetadata`/task-ordering wiring; redeclaring `override fun initialize()` on the `expect` object plus `actual override` on both platform actuals (matching a real androidx.room changelog bug-fix note, issue 359631627); Room 2.7.2 (identical failure); Room 2.8.0/2.8.5 (can't even load -- their `room-runtime` commonMain klibs are built with Kotlin 2.2.0, incompatible with this repo's pinned 2.1.0 compiler, per ADR-009); a KSP-only bump (2.1.0-1.0.29 is already the newest KSP release for Kotlin 2.1.0). None of that was the real problem. The actual fix, found by testing `androidTarget()` end-to-end (not just its KSP validation step in isolation) for the first time: compiling `compileDebugKotlinAndroid` with the hand-written actual in place fails with a plain, clear `Redeclaration` error, because Room's KSP processor **also** generates its own `actual object SmokeDatabaseConstructor { actual override fun initialize() = SmokeDatabase_Impl() }` under `build/generated/ksp/<target>/...` for every target that runs its KSP pass against the `expect` -- Android, the plain JVM target, and Kotlin/Native alike. Deleting every hand-written actual (there should be **none** anywhere in this module) fixed both Android and the JVM target identically; the confusing JVM-only "must be an expect declaration" KSP-time error was that same redeclaration collision, just reported through a different, less clear code path than Android's compile-time one. Net result: `:core:db` needs `androidTarget()`, `jvm("desktop")` and (opt-in) `linuxX64()`/`linuxArm64()`, per ADR-010, with **only** the `expect object` in commonMain and zero platform `actual` files -- confirmed with a real insert/read-back round-trip test passing on the JVM (`BundledSQLiteDriver`) and clean compiles on every other target. A related, smaller fix found along the way: `androidx.room:room-ktx` pulls in `kotlinx-coroutines-android` (Android-only), which broke the native compile once it was actually exercised (`No matching variant ... kotlinx-coroutines-android`) -- scoped to `androidMain` only; `room-runtime` alone provides Flow-returning `@Query` support on every KMP target.

- **WP1.3 / schema: `AssetId`/`SourceId`/`FormatId` and `Flag` landed in `:core:model`/`:core:formats`, `Capability` deferred to WP1.7:** ADR-010's original `:core:model` row also lists `Capability` alongside these, but ADR-011's actual binding schema never references it, and ADR-010 itself puts "the capability matrix" in `:core:formats`, which WP1.2's own Decisions already deferred to WP1.7 (format registry) alongside `FormatSniffer`. Authoring `Capability` now, disconnected from the registry it belongs with, risks the same "define it twice, once wrong" problem WP1.2 avoided for `AssetId`/etc. by waiting for ADR-011. `FormatId` is an opaque `value class(String)` in `:core:model` rather than reusing `:core:formats`' `MediaFormat` directly -- the dependency chain (`model ← formats`) runs the wrong way for `:core:model` to reference it, so the `MediaFormat -> FormatId` mapping (`MediaFormat.formatId`, stable ids like `"jpeg"`, `"raw:cr2"`) lives in `:core:formats` instead, alongside the classifier it describes. `rating`/`color_label` stayed plain `Int` columns, not enums: ADR-011 gives `flag`'s exact 3-value meaning (`Flag` enum, `-1/0/1`) but leaves `rating` an unadorned `0..5` range and never specifies a colour set for `color_label` -- both are Phase 4 UI, so there's nothing yet to usefully wrap.
- **WP1.3 / migration engine architecture: `MigrationToV2` is pure `:core:db` commonMain, driven by three injected interfaces (`LegacySource`, `MigrationEnvironment`, `ProjectionParityCheck`), not Android code:** this is what makes ADR-011's own "Tests required (JVM unless noted)" possible at all -- the whole 10-step state machine, including every transformation/reconciliation rule, is exercised by 9 JVM tests in `core/db/src/desktopTest` against a hand-built `FakeLegacySource`, with zero Android/Robolectric dependency. `:app` supplies the real implementations (`AndroidLegacySource` etc., reading the actual six old SQLite files -- ADR-011's own prose says seven, the code and its own store table agree on six -- plus the four asset-keyed prefs files and a live MediaStore volume query), proven against those real formats by 13 Robolectric tests. `legacy_id_map` and `legacy_orphans` (ADR-011 §5 mentions both but neither is in §2's binding DDL block) are real, persisted Room tables here, not literal SQLite `TEMP TABLE`s -- a true temp table is connection-scoped and would silently break resuming a killed migration after the process restarts, which is exactly the "kill-and-resume" behaviour ADR-011's own tests require.
- **WP1.3 / `asset.revision`'s formula, since ADR-011 describes the trigger but not the exact computation:** `dateModifiedMs xor sizeBytes`. `content_hash` is filled lazily (WP1.4), so isn't available at migration time; this app's own file-modification timestamp already changes whenever content does in every case it can observe, and mixing in size is extra sensitivity, not a substitute for a real content hash later.
- **WP1.3 / migration catch-up: real bidirectional reconciliation for favorite/sensitive/archived/tags/locks, additive-only (by design) for the rest -- found and fixed after an independent agent re-derivation caught it:** the first version of `MigrationToV2` (matching a literal reading of ADR-011 §5's "a catch-up pass" prose) only ever *added* what the old stores had gained since the first pass, never removed what they'd lost -- so un-favouriting a photo, removing a tag, or unlocking a folder while migration ran in the background would survive into `fotoz.db` forever, and since VERIFY's projection-parity check would then permanently disagree with the old side, the migration could never complete at all. A second, related gap: `runAssets()` was never re-invoked during catch-up, so a photo taken mid-migration was invisible to `fotoz.db` even after cutover. Both are fixed: the catch-up pass (run once, right before `VERIFY`, using each step's own idempotent upsert/reconcile semantics -- see `run()`'s own comment) now re-runs `ASSETS` (picks up new media rows from its own saved cursor), then fully reconciles (computes desired-vs-actual and both links/enables AND unlinks/disables) `favorite`, `sensitive`, `archived`, tag membership (with origin correction) and folder locks -- the fields a person is realistically going to touch while just using the app during a migration window. Captions, collections, manual geo pins and rejected-auto-tags stay additive-only: lower-frequency edits than a favourite/tag toggle, and reconciling them needs materially more bookkeeping for a rarer edge case -- a scoped limitation, not an oversight (documented in `MigrationToV2.runUserData`'s own doc, including the one gap catch-up still doesn't cover: a tag deleted outright, as opposed to a membership change on a tag that still exists). `ever_unarchived`/`archive_suggestion_rejected` are correctly additive-only regardless -- they're "has this ever happened" records in the app's own real code, never unset. A file *edited or deleted* mid-migration (as opposed to a user-data toggle) is a known, accepted residual gap: WP1.4's sync engine corrects that on its first post-cutover pass, and building real change-tracking into every old store's writers just for this migration's catch-up window was judged out of proportion to how often it would actually matter. `runDerived`'s `Insert`-based orphan recording also double-counted the same orphan on every repeated catch-up pass until `legacy_orphans` got a `(kind, media_id, detail)` unique index and `OnConflictStrategy.IGNORE`. `KeywordDao.link` had to move from `IGNORE` to `REPLACE` for the same reason catch-up needs it to: reconciling an existing tag link's origin (`USER` <-> `AUTO`) on a repeat call requires overwriting, not silently keeping the stale value.
- **WP1.3 / VERIFY's "counts per store, old vs new" (ADR-011 §5 step 8, previously unimplemented):** a store's new count can be legitimately less than its old count (unmapped rows are dropped for rebuildable derived data, tracked per-kind in `MigrationToV2.runDerived`'s own `droppedByKind`; or kept as `legacy_orphans` for the user-authored exceptions) -- so the check isn't exact equality, it's `new <= old - dropped - orphaned`, hard-failing only when a store's new count *exceeds* what that allows, which would mean real duplication, not an expected gap.
- **WP1.3 / initially thought wiring `LibraryRuntime` to trigger real cutover was unsafe before store rewiring -- re-examined, and it's the *other* step, `CLEANUP`, that's actually dangerous:** the first-pass reasoning here was "cutover makes the old stores read-only, so nothing would be able to read anything." On closer look that's not what the code does: `AndroidMigrationEnvironment.markCutoverComplete()` only flips a flag (`MigrationStateStore`); nothing in any of the nine old stores ADR-011's Consequences section lists checks that flag today, since none of them has been rewired yet. Cutover completing is therefore inert -- it changes nothing about the app's current, observable behaviour, because nothing consumes the signal yet. The real destructive step is `CLEANUP` (ADR-011 §5 step 10, `runCleanupIfDue()`): that one really does delete the six old SQLite files and four prefs files, two successful app starts after cutover, and doing that with no store yet reading `fotoz.db` would break the app for real. So `LibraryRuntime` now calls `MigrationToV2.run()` unconditionally on every process start (see `WP1.3b` below) -- safe, and valuable: it's the first time this pipeline runs against a device's/emulator's real data rather than a fixture -- while `runCleanupIfDue()` stays uncalled until store rewiring is ready.
- **WP1.3 / store rewiring's `MediaId <-> AssetId` bridge: not the migration's own `legacy_id_map` (deleted at `CLEANUP`), but a live lookup on the permanent `asset.locator` column, and simpler than first planned -- no in-memory index, no `source_id` join:** confirmed by actually building the two smallest facades, `CatalogueFavoriteStore`/`CatalogueSensitiveStore` (`core/db/.../catalogue/`), replacing the app's `FavoriteStore`/`SensitiveStore` (each ~50 lines, a synchronous `MutableStateFlow` mirror over a SharedPreferences id-set). Every call site passes a `MediaId` (the old MediaStore `_ID`); `legacy_id_map` looked like the obvious bridge to `asset_id` until noticing `CLEANUP` deletes that table, which would turn a rewired store into a ticking bug the moment cleanup runs. `asset.locator` (the original MediaStore id, stored as text) is never deleted, so a locator lookup is a permanent bridge instead. The earlier plan here called for an in-memory `locator -> assetId` index built once, matching this app's other `StateFlow`-mirror stores -- building it revealed that's unnecessary complexity: a plain suspend query (`AssetDao.findByLocatorAnySource`, `MediaIdBridge.kt`'s `resolveAssetId`/`resolveAssetIds`/`resolveMediaIds` extensions) is simpler, always current (no cache-staleness window), and just as cheap against SQLite's own index. It also drops the `source_id` join `findByLocator` needs: Phase 1 has exactly one source kind (`MEDIASTORE_VOLUME`), and this app has never disambiguated `MediaId` by volume anywhere else either -- every scanner already queries MediaStore's merged `VOLUME_EXTERNAL` collection, whose `_ID` values are unique across every physical volume, so treating `locator` as a bare, source-less `MediaId` merely matches an assumption the rest of the app already makes. This makes store rewiring a same-scale change to each store's own internals, not the app-wide `MediaId` type redefinition ADR-011's Consequences section literally describes ("`MediaId` becomes a typealias-compatible wrapper around `asset_id`") -- that full identity switch may still be the eventual destination, but this bridge gets every store working correctly without it.
- **WP1.3 / the two built facades are deliberately *not* wired into `LibraryRuntime` or the live UI yet -- a real regression risk, not just an unverified one:** `asset` only gains rows from `MigrationToV2`'s one-time `ASSETS` step; nothing keeps it current afterwards until WP1.4's sync engine exists. Swapping the live `FavoriteStore`/`SensitiveStore` for the catalogue-backed facades now would mean every photo taken *after* that one-time step silently fails to favourite/mark-sensitive (the facade's lookup finds no matching `asset` row, and both writes no-op by design -- see their own KDoc), which for an actively-used photo app is not an edge case, it is the common case within hours of upgrading. A same-process-start-only "switch to the new store once cutover is complete" design was considered and rejected for the same reason: correct cutover timing does not fix the underlying staleness. The facades stay built-and-tested-but-unused until WP1.4 lands and keeps `asset` current going forward; wiring them into `LibraryRuntime` is that follow-on step, not this one.
- **WP1.4 / `SyncToken` is `(version: String?, generation: Long?)`, not a fully opaque cross-source-kind string:** MASTER-PLAN.md §2.3 describes `Source.enumerate(since: SyncToken?)` as if any source kind could define its own opaque resumption format, but ADR-011 §2's schema already commits to a concrete, two-field shape for the one source kind Phase 1 has (`source.sync_version`/`sync_generation`, i.e. `MediaStore.getVersion`/`getGeneration`). Wrapping those in a generic opaque string would only mean parsing the string back out of itself on every persist/read, for no source kind that exists yet to actually need genuine opacity. `SyncToken` matches the schema directly instead; Phase 3's other source kinds can widen this (or add their own resumption bookkeeping) when they exist.
- **WP1.4 / mark-and-sweep gates on `SourceEvent.Completed.fullyEnumerated`, never on whether `since` was null:** keeps `SyncEngine` correct even for a hypothetical future source whose *delta* pass can still account for its entire current extent (unlikely for MediaStore, plausible for some future source kind) -- and, more importantly right now, keeps the gate a single explicit boolean a test can pin directly, rather than an inference from `since`. TRAPS #1/#8's actual enforcement is the plain `if (fullyEnumerated && !partialAccess)` in `SyncEngine.sync` -- there is no flag a future edit could flip by accident, the same way `MediaIndexer`'s delta branch is structurally `Unit`.
- **WP1.4 / a reconnecting source (`markSourceOnline`) clears its generation baseline and forces a full re-sync, rather than trusting the old `sync_generation` across the gap:** MediaStore preserving generation continuity across a real physical unplug/replug isn't something to bet correctness on (untested, undocumented platform behaviour). Clearing the baseline is conservative but cheap -- Phase 1 libraries are the scale a full per-volume enumeration is fine for -- and it's also what correctly resolves assets `markSourceOffline` bulk-marked `OFFLINE`: found again in the forced full pass -> `ONLINE`; still missing -> `MISSING_CONFIRMED` (ADR-011 §6), never stuck `OFFLINE` forever. This needed widening `AssetDao.idsAndLocatorsForSource`'s mark-and-sweep query from a single `availability` value to a list (`ONLINE` and `OFFLINE` both), since a reconnecting source's sweep must reconcile both.
- **WP1.4 / `folderKey`/`assetRevision`/`newMediaStoreSource`/`baseAssetUser` moved from `core/db/.../migration/` into `core/db/.../AssetIdentity.kt` (package `com.fotoxplorr.core.db`):** `SyncEngine` needs byte-identical logic to `MigrationToV2` for all four (an asset migrated once and later touched by sync must never disagree with itself on its own folder key or revision formula), and `:core:index` depending on `:core:db.migration` for four unrelated pure functions was the wrong shape -- these were never migration-specific to begin with. Pure refactor: no behaviour change, confirmed by `MigrationToV2Test` staying green untouched.
- **WP1.4 / `AndroidMediaStoreSource` and `LibraryRuntime` wiring are the next task, not done in this increment:** `Source`/`SyncEngine` are fully built and tested (12 JVM tests, `FakeSource`), but the real Android glue needs precise `MediaStore.getVersion`/`getGeneration`/`GENERATION_MODIFIED` API-level gating (available from API 29/30 respectively; this constellation's minSdk is 26) verified against current platform documentation before writing it, not from memory -- getting an API-level gate wrong here fails silently on real devices rather than at compile time. Splitting this into its own commit (`WP1.4b`) mirrors WP1.3/WP1.3b's own split for the same reason: a correctness-sensitive Android-glue layer deserves its own review point separate from the pure-Kotlin engine it drives.
- **WP1.4b / verified API levels before writing `AndroidMediaStoreSource`, not from memory:** `MediaStore.getExternalVolumeNames(Context)` and `getVersion(Context, String)` -- API 29 (Q); `getGeneration(Context, String)` and `MediaColumns.GENERATION_MODIFIED` -- API 30 (R), confirmed via current Android reference documentation and the AOSP `MediaStore.java` source, not recalled. This app's minSdk is 26, so `AndroidMediaStoreSource.currentGeneration()` returns null below API 30, which is exactly what forces `SyncEngine` to treat every pass on those devices as a full enumeration (`since.generation == null` in its `since`-derivation) -- no separate low-API code path needed, the existing null-propagation already does the right thing. Below API 29 there's no per-volume enumeration at all; `AndroidMediaSync` falls back to the single locator `"external"`, deliberately matching `MigrationToV2.primarySourceId()`'s own fallback string exactly (not `"external_primary"`) so both land on the same `SourceEntity` row instead of creating two.
- **WP1.4b / per-volume queries reuse `MediaStore.Files.getContentUri(volumeName)`, not the merged `VOLUME_EXTERNAL` collection the old scanner uses:** this is the actual mechanism behind "per-volume generation sync" (MASTER-PLAN.md §2.3) -- querying a specific volume name scopes both the row set and the `GENERATION_MODIFIED` values to that one physical volume. `MediaStore.VOLUME_EXTERNAL` itself is defined as the literal string `"external"`, which is also the pre-Q fallback locator above, so the exact same query code path handles both cases with no special-casing.
- **WP1.4b / the media-type-plus-SVG selection clause is a deliberate, temporary duplicate of `AndroidMediaStoreScanner`'s `buildSelection`, not a shared function:** the old one is shaped around `ScanPlan`'s `DATE_MODIFIED`-bounded delta; the new one needs a `GENERATION_MODIFIED`-bounded one. Forcing a shared abstraction across two scan engines that only coexist for the rest of Phase 1 (WP1.5 deletes `MediaIndexer`/`AndroidMediaStoreScanner`/`ScanPlan` outright per ADR-011 Consequences) would outlive its own usefulness by design. Tested the same way the original is (`AndroidMediaStoreSourceSelectionTest`, a plain JUnit test with no Robolectric -- `MediaStore`'s constants are compile-time-constant fields that resolve under the unit-test `android.jar` stub).
- **WP1.4b / no Robolectric test of `AndroidMediaStoreSource`'s actual `ContentResolver.query` path:** matches this codebase's own existing precedent -- `AndroidMediaStoreScanner` (the old scanner, in production for months) has never had its real cursor-querying/column-mapping logic exercised under Robolectric either, only its `buildSelection` pure function (`AndroidMediaStoreScannerSelectionTest`). Robolectric's `MediaStore`/`ContentResolver` shadow fidelity for the newer, less common generation/version APIs this needs is untested territory this codebase doesn't otherwise rely on; the real integration is proven the same way `MigrationToV2`/`AndroidLegacySource` ultimately are -- by running against this device's/emulator's real data (`WP1.4b`'s own `LibraryRuntime` wiring) and the Phase 1 gate's device checklist, not a Robolectric substitute for it.
- **WP1.4b / `AndroidMediaSync.syncAll()` runs once per process start, right after `MigrationToV2.run()`, not hooked into the existing `scanRequests`/`MediaStoreChangeObserver` loop:** that loop drives the OLD pipeline's live UI data (`SqliteMediaRepository` via `MediaIndexer`); nothing yet reads `fotoz.db`'s `asset` table live (same reasoning as `MigrationToV2.run()`'s own once-per-start wiring, WP1.3b), so a genuinely ongoing, change-triggered sync has no user-visible benefit yet and modifying that already-live, already-tested dispatch loop for it would be a materially riskier change than this increment needs. Hooking `AndroidMediaSync` into that loop (or its replacement) is deferred to whenever a live consumer of `fotoz.db` actually exists -- naturally alongside store rewiring / WP1.5.
- **WP1.4 marked done "core scope": the "downstream indexer job framework" half of MASTER-PLAN.md §2.3 is deliberately deferred, not built:** that item is about geo/recognition/embeddings/traits jobs over `(asset_id, revision)` with bounded retries (the P0-12 pattern, already schema-ready as `IndexFailureEntity`/`IndexFailureDao` -- see the earlier WP1.4 survey). Building the generic retry/bookkeeping logic now would be speculative: no live indexer writes into `fotoz.db`'s recognition/geo/embedding tables yet -- they're only populated once, by `MigrationToV2.runDerived()`, carrying the OLD stores' own results forward. `RecognitionStore`/`EmbeddingRepository`/`GeoMetadataRepository` etc. still run their own hand-rolled P0-12 implementations against their own old SQLite files, same as before WP1.4. Generalising the pattern against `IndexFailureDao` belongs with whichever store-rewiring step first gives one of these indexers a real `fotoz.db`-backed reason to use it -- building it earlier risks getting the shape wrong against an imagined caller instead of a real one, the same trap ADR-010's Decisions already named for `Capability` (deferred to WP1.7) and rating/colour-label enums (deferred to Phase 4).
- **WP1.5 / surveyed `GalleryProjection.kt`/`GridIndexMap`/`CatalogueCharacterisationTest`/`SyntheticCatalogue` in full before writing any SQL, and confirmed archived-filtering is genuinely inconsistent today, not absent:** already correctly excluded from the main Photos timeline, People/Pets/Identity, device-folder album drill-down, Places/map display, and the legacy Calendar screen; leaks into 9 of the 11 smart albums (everything except `ARCHIVED` itself), the `VIDEOS`/`SCREENSHOTS`/`FAVOURITES` destinations (which are those smart albums), the folder-summary cover computation (`buildAlbumSummaries`), and the Collection/Tag drill-down routes. Owner decision 2's own wording ("hidden everywhere except the Archived album, in every destination and smart album") is the scope this increment targets literally: every `SmartAlbum` except `ARCHIVED`, applied in `GalleryProjectionDao`. `buildAlbumSummaries`/Collection/Tag are out of that literal scope (neither a "destination" nor a "smart album" in the enum sense) and are left for a follow-up if the owner wants full consistency there too.
- **WP1.5 / `TRASH` is a deliberate exception to owner decision 2, not an oversight:** an item that's both archived and trashed must still show in Trash so the user can restore or permanently delete it -- hiding it there has real potential for harm (something they trashed becoming unfindable) for no clear benefit, and it matches today's existing behaviour exactly (`smartAlbumAssets`'s `TRASH` branch already doesn't filter on `archivedIds`). `ARCHIVED` needs no exception language at all: it's simply `archived = 1` with no exclusion applied.
- **WP1.5 / `GalleryProjectionDao`'s 11 smart-album + everyday + folder queries are deliberately NOT wired into the live UI, the same "build correct and tested, defer the live cutover" discipline as WP1.3/WP1.4 -- but for materially higher stakes reasons:** (1) ADR-011 Consequences says WP1.5 is where the in-memory `StateFlow<List<MediaAsset>>` mirror is actually *removed* -- cutting over means deleting `MediaIndexer`/`AndroidMediaStoreScanner`/`SqliteMediaRepository`/`ScanPlan` and rewriting large parts of the 2227-line `GalleryScreen.kt`, the single highest-blast-radius change in Phase 1 so far, for a real app with a real user's live photo library; (2) the performance acceptance bar (MASTER-PLAN.md §2.4: cold start, scroll fps, scrubber jump latency) needs a real device to measure -- this sandbox has neither a device nor an emulator (already noted in PR #14's own description); (3) two real functional gaps remain even for the query layer itself -- see below. Building the SQL layer now is still valuable and lower-risk on its own: it's provably correct against a real (in-memory) `FotozDatabase`, independent of when/whether the live cutover happens.
- **WP1.5 / two deliberate, documented gaps in `GalleryProjectionDao` versus the Kotlin functions it's meant to eventually replace:** (1) **locked-folder visibility** (`isPrivacyVisible` in the old code) isn't applied by any query -- it depends on which folders are unlocked *this session*, ephemeral in-memory state with no `folder_lock` column recording it, so it needs its own parameterized join (`folder_key NOT IN (SELECT folder_key FROM folder_lock) OR folder_key IN (:currentlyUnlockedKeys)`) that isn't built yet; (2) **free-text search** (`matchesQuery`/`matchesGallerySearch`) isn't applied either. Neither gap is covered by this increment's own tests, on purpose -- they're not yet claimed to work. A query result from this DAO today can include a locked-folder asset a real gallery screen must never show; do not wire this in without closing that gap first.
- **WP1.5 / real Paging 3 vs. a hand-rolled windowed `Flow` is an open research question this increment doesn't resolve:** flagged already in WP1.1's own Decisions ("`paging-common:3.5.1` shows `linuxX64`/`linuxArm64` API variants... ADR-009 treats as an open question for WP1.5: verify it supports Kotlin 2.1.0 and a native Linux target before adopting"). `GalleryProjectionDao` deliberately returns plain `List<AssetEntity>` with `LIMIT`/`OFFSET` params rather than committing to either mechanism yet -- both can wrap these same, already-correct queries later (Room can even auto-generate a `PagingSource<Int, AssetEntity>` from a `@Query` given the `room-paging` artifact, if that artifact's own KMP story checks out) without redoing the query logic itself. Deciding this is still open work, not resolved here.
- **WP1.5 / `DUPLICATES`'s SQL uses `ROW_NUMBER()`/`COUNT(*) OVER` window functions, matching `duplicateCandidateIds`'s exact keeper-selection rule:** partitioned by `(size_bytes, width, height, mime)`, ordered by `(date_taken_ms, date_modified_ms, asset_id)` ascending -- the first row per partition (`rn = 1`) is the keeper and is excluded; every other row in a partition with more than one member is a duplicate candidate. Confirmed working against `BundledSQLiteDriver` (needs SQLite 3.25+, which it ships well above) via a dedicated 3-member/1-unique/1-archived-excluded test.
- **WP1.5 / what's still outstanding after this increment, for whoever picks WP1.5 back up:** locked-folder SQL join; free-text search; the Paging 3 vs. windowed-Flow decision (and, if Paging 3, wiring `room-paging` or an equivalent `PagingSource`); `timelineGroups`/`GridIndexMap` generalisation for "stacks" and "sections" (MASTER-PLAN.md §2.3 -- neither exists in any form today, per TRAPS #9); `CollectionDao`/tag-drilldown SQL (joining `collection_member`/`asset_keyword` back to full paged `AssetEntity` rows); the actual live-UI cutover in `GalleryScreen.kt`, which also means deleting `MediaIndexer`/`AndroidMediaStoreScanner`/`SqliteMediaRepository`/`ScanPlan`; and the 500k-synthetic performance validation against MASTER-PLAN.md §2.4's budgets, which needs a real device and belongs on the Phase 1 gate's device checklist, not a claim made from this sandbox.
- **WP1.6 / surveyed the current app architecture before deciding how much to build, and deliberately did not attempt the ViewModel/job-service work this increment:** three findings drove this. (1) Rotation -- half of the acceptance bar ("Rotation/process-death tests") -- is **already handled**, just not by ViewModels: every activity declares `android:configChanges="orientation|screenSize|..."` (P0-09), so Android never recreates the Activity on rotation at all; the classic "ViewModel survives rotation" problem this WP exists to solve doesn't apply here the way it does in a stock Compose app. (2) The genuinely open gap is narrower than the WP's title suggests: *process death* specifically (only 1 `rememberSaveable` call site in the entire app -- the viewer's active-asset id -- so everything else resets to defaults if the OS kills the process) and a *foreground service for long operations* (confirmed: `MediaFileOperations.copyToTree` and its siblings are plain `suspend fun`s run in UI-lifecycle-scoped coroutines, with `BackgroundWorkJobService`/`WorkRules` existing only for the unrelated, already-charging-gated ML indexing jobs -- a user-initiated bulk copy today is cancelled, not paused, if the app is backgrounded or killed). (3) `audit_log`'s schema and `AuditLogDao` already exist (ADR-011 §2), but have zero live callers to wire: permanent delete/"shred" (ADR-011 §6, the only event meant to write an audit row) isn't implemented anywhere in the live app yet -- only Trash exists -- so there is nothing real to audit-log yet, the same "don't build ahead of a real caller" situation as WP1.4's deferred job framework.
- **WP1.6 / why deferred rather than attempted at reduced scope, unlike WP1.4's job framework:** WP1.3-1.5 were all tightly sequential, load-bearing steps in the same catalogue-v2 dependency chain (schema -> migration -> sync -> projections), each buildable and testable in isolation without touching live, working UI code. WP1.6's core ask -- a real ViewModel/`SavedStateHandle` architecture and a foreground `Service` with notification/pause/resume/cancel -- has no such isolation: proving process-death survival or "a copy survives leaving the app" requires actually restructuring live screens and a real Android service lifecycle, with genuine regression risk to a working app for a real user, and verifying it properly needs either sophisticated instrumented testing or a real device this sandbox doesn't have (same constraint as WP1.5's performance bar). It is also not load-bearing for WP1.7-1.10 the way WP1.3-1.5 were for each other. Left for a dedicated follow-up rather than a partial, unverified attempt.
- **WP1.7a / magic-byte signatures were each checked against a primary or established reference before being written down, not recalled from memory alone:** `MediaStore`'s own generation/version APIs (WP1.4b) already showed the cost of a wrong platform-detail guess failing silently rather than at compile time; a wrong magic number here has the same shape of risk (misclassifying real files, or a signature that silently never matches anything). JPEG XL confirmed against `libjxl`'s own decoder source and the format's Wikipedia page; PNG/JPEG/GIF/BMP/ICO/ICNS/QOI/XCF/EXR/HDR/PNM/JP2 confirmed against Wikipedia's "List of file signatures"; KTX/DDS confirmed against the KTX specification and cross-referencing sources; JXR and PCX confirmed against independent format documentation. `TGA`, `ORA`, `KRA` and Softimage `PIC` are named in `FormatSniffer`'s own KDoc as deliberately NOT attempted -- no signature reliable or confirmed enough to write down (TGA's only signature is an 18-byte footer, not a header; ORA/KRA need the ZIP's internal `mimetype` entry inspected, not a header check; PIC's signature wasn't independently confirmed).
- **WP1.7a / the `Capability` matrix and `DecoderProvider` SPI (ADR-010's original `:core:model` row, deferred through WP1.2 and WP1.3 to "WP1.7, the format registry") are deferred again, past this increment:** a `Capability` row claiming real "Metadata R/W" support would be premature before WP1.10 (the actual XMP/EXIF/IPTC reader) exists to back that claim, and a `DecoderProvider` SPI describing "platform + packs" decode routes has no real pack to describe yet -- every pack in MASTER-PLAN.md §4/§5 is Phase 2 scope. `MediaFormat.isLikelyDecodable` already carries the one decode-capability signal that's both true today and doesn't depend on anything not yet built; a fuller matrix belongs with whichever of WP1.10 or a Phase 2 pack first gives it something real to describe, the same "don't build ahead of a real caller" reasoning as WP1.4's deferred job framework and WP1.6's deferred audit-log wiring.
- **WP1.7a / "a black tile is impossible" (the Robolectric render-test half of WP1.7's acceptance bar) is not attempted this increment either:** grepped for `isLikelyDecodable` across `:app` and found zero call sites outside an unrelated, differently-named `VideoCodecCapability.isLikelyDecodable` -- meaning the live gallery grid does not check format decodability at all today, for RAW or anything else; an undecodable file's tile currently falls through to Coil's own default broken-image handling, not an "honest placeholder... format badge" per MASTER-PLAN.md §4's own intent. Wiring `MediaFormat.classify`/`FormatSniffer` into the actual tile-rendering composable and proving it with a Roborazzi/Robolectric render test is real, valuable, more contained work than WP1.5/1.6's asks -- but it is still a live-UI change this increment didn't investigate closely enough to make safely; left for a focused follow-up.
- **WP1.8a / Macrobenchmark's own `[OWNER]`-run README already explained precisely why it wasn't wired into the build graph -- this increment only changed *how* it's opted into, not the reasoning:** the module (`benchmarks/`) was already fully built and documented, with manual-edit instructions for enabling it. Converting that to `-Pfotoz.benchmarks` (mirroring `-Pfotoz.native`'s already-established pattern exactly: gate both the `settings.gradle.kts` `include` and `:app`'s matching `benchmark` build type behind the same property) removes the need to hand-edit two files, while preserving the exact same "never present in a plain build" guarantee the original README was written to protect. Verified both states directly: `:app:compileOfflineDebugKotlin` (no property) unaffected; `:benchmarks:compileBenchmarkSources` and `:app:compileOfflineBenchmarkKotlin` (with `-Pfotoz.benchmarks=true`) both succeed. (Two Maven Central 429s during this verification were transient rate-limiting in this sandbox's network path, not a wiring defect -- a third attempt succeeded cleanly.)
- **WP1.8 / the rest of the work package -- `androidTest` source set, format corpus + `SOURCES.md`, 500k perf baseline regeneration -- is deliberately not attempted this increment:** an `androidTest` source set with no concrete instrumented test to put in it would be a speculative scaffold, the exact kind of "code with no real caller yet" this whole session has consistently avoided building ahead of (WP1.4's job framework, WP1.6's audit-log wiring, WP1.7a's `Capability` matrix all deferred for the identical reason). A format corpus needs real sample files sourced and *individually licence-audited* per the acceptance bar itself ("the corpus is licence-audited") -- a content-curation and legal-review task, not a code change, and not one to rush. The 500k perf baseline doesn't need regenerating on THIS branch's account: `GalleryProjection.kt` (what `docs/perf/baseline-jvm.md` measures) is unchanged by every WP landed so far -- WP1.5a's SQL projections are new, additive code in `:core:db`, not a modification to the old Kotlin path -- so the existing baseline is still accurate; regenerating it would reproduce the same numbers at real cost (the baseline's own doc notes a 4 GB heap was needed to run the 500k case at all) for no new information.
- **WP1.9a / the `core-native` CI job wasn't just added, it exposed a real bug in the thing it depends on:** writing the job meant finally populating `scripts/verify.sh --native`'s `NATIVE_TASKS`, which had sat empty since WP1.1 behind a stale comment ("empty until WP1.2 lands the first native-ready module") that nobody updated once WP1.2 actually landed seven of them. Every "native compiles clean" claim logged for WP1.2 through WP1.8 in this file was true, but only because of *manual*, ad hoc `./gradlew ...compileKotlinLinuxX64` calls made alongside the (silently no-op) `--native` flag -- the flag itself verified nothing. Running the real task list for the first time immediately found a second, independent bug: `core/formats/src/commonTest/.../FormatSnifferTest.kt` (WP1.7a, landed just before this) had five backtick-quoted test names containing commas, which JVM tolerates but Kotlin/Native's stricter symbol-name rules reject outright (`compileTestKotlinLinuxX64` hard-failed, not just warned) -- fixed by rewording those five names (commas to `--`). Both fixes are evidence for the check's own value, not incidental cleanup: an opt-in flag that silently verifies nothing is worse than no flag, because every "green" reading is a false one.
- **WP1.9a / `scripts/verify-native.sh` extracted as its own script, not left inline in `verify.sh` or duplicated in the CI YAML:** `verify.sh --native` and the new `core-native` CI job both need the exact same native task list; writing it in two places (a bash array in `verify.sh`, a `./gradlew` line in the YAML) is exactly the kind of drift `FX-002`'s own reasoning ("CI runs the SAME script as local development... the two gates cannot drift apart") already rules out for the rest of this script. One script, two callers.
- **WP1.9a / the 16 KB page-alignment check (TRAPS #16) is a read-only regression guard, not a fix for an existing problem:** checked by hand before writing anything -- `zipalign -c -P 16` against both a freshly built debug APK and a freshly built `offlineRelease` APK reports "Verification successful" on this AGP 8.9.1 / compileSdk 36 pin, with no manual `zipalign -p`/`-P 16` step anywhere in this repo's build or CI today. TRAPS #16 documents a mistake to never repeat (using `-p` if a manual align step is ever added), not a bug this app currently has; `scripts/verify-page-alignment.sh` exists so that stays true going forward, wired into CI right after the release-APK build step it checks.
- **WP1.9 / the licence scan is not attempted this increment:** MASTER-PLAN.md §5's "Banned" list (MobileCLIP(2) weights, InsightFace, EdgeFace, Jina CLIP v2, RMBG, YOLO/AGPL, avif-coder 2.x, x265, glycin JXL loader, ObjectBox, GPL gallery code) is mostly model *weights* and Phase-2-pack integration choices, not resolvable Gradle dependency coordinates -- a real scan of the CURRENT dependency graph would trivially pass today (none of Phase 2's packs are dependencies yet) and would only start catching anything real once WP2.3's actual pack dependencies exist to check. A meaningful implementation needs either a proper licence-report Gradle plugin (evaluating its own dependencies and output format first) or waiting for a real candidate to scan against; building a scan with nothing yet to scan risks the same "code with no real caller" trap this whole session has otherwise avoided.

- **WP1.10 / MASTER-PLAN.md's own acceptance bar for this WP is superseded by ADR-010 where the two conflict, per this branch's own standing instruction that accepted ADRs override the plan -- checked deliberately, before writing any code:** MASTER-PLAN.md §3 asks WP1.10 for a "Shared-Kotlin EXIF/XMP/IPTC reader (TIFF-IFD + XMP parser in **commonMain**)". ADR-010 (WP1.2, accepted) already decided otherwise and says so explicitly, twice: its module table lists `:core:metadata` commonMain scope as `MetadataStripper` + "EXIF tag lists that don't need `ExifInterface`" only, with `XmpPacket` pinned to **jvmMain**; and its Consequences section states outright, "`XmpPacket` stays JVM-only until Phase 8 prep. Choosing a native XML parser... is deferred and recorded as an open item." A full binary TIFF-IFD parser was never in ADR-010's commonMain list at all. This is not a gap Phase 1 needs to close: `:app` (the only Phase 1 platform) has consumed `:core:metadata`'s `jvm` target directly since WP1.2 with no `android()` target ever declared or needed (confirmed again this increment -- see the module readiness table), so `XmpPacket`'s jvmMain placement has never blocked Android. What ADR-010 actually defers to Phase 8 is *portability* (Linux desktop/Ubuntu Touch), not *functionality* Phase 1 needs. `sidecar_state` (ADR-011 §2, `asset_user.sidecar_state`) carries its own explicit "Phase 3/4" marker in the schema comment, confirming ADR-011 independently agrees the DB-tracked bidirectional sidecar SYNC STATE (`IN_SYNC | DIRTY | CONFLICT`) is not Phase 1 scope either -- but that is narrower than "sidecar read/write itself is deferred": nothing in either ADR defers the sidecar FILE mechanics MASTER-PLAN.md's own WP1.10 line separately names, which `MetadataWriter`'s pre-existing class doc already flagged as "tracked as its own follow-up" back when P0-08 landed EXIF/XMP writing. WP1.10a (below) is that follow-up, scoped to exactly what neither ADR defers.
- **WP1.10a / "ratings, labels, keywords, caption and flag round-trip to XMP" (MASTER-PLAN.md's WP1.10 line) is deliberately NOT attempted this increment, for three separable reasons, not one:** (1) caption and keywords already round-trip to XMP today -- `MetadataWriter`/`applyToXmp`/`currentMetadataFrom` (P0-08, unchanged by this increment) already write/read `dc:description`/`dc:subject`, so there is nothing new to build there; a sidecar write path could reuse those exact functions once the Android glue exists (see below), no new XMP-property work needed. (2) `rating` and `color_label` are explicitly out of definition, not just out of scope -- `core/model/Flag.kt`'s own doc comment says plainly that ADR-011 "leaves rating as an unadorned `0..5` range and never specifies a colour set for `color_label` (both are Phase 4 UI... so there is nothing yet to usefully wrap for those two)"; there is no product-defined colour set to round-trip, and inventing one here would be a Phase 4 design decision made by the wrong work package. (3) `flag` (`Flag.REJECT`/`NONE`/`PICK`, `-1`/`0`/`1`) DOES have a defined app-side enum, but researched its real-world XMP mapping before writing any code (same discipline as WP1.4b/WP1.7a) and found a genuine conflict, not just an unverified detail: the only standard, interoperable XMP convention for "rejected" is `xmp:Rating = -1` (Adobe XMP Basic namespace spec, confirmed) -- the SAME property this app already uses for star ratings (`applyToXmp`'s existing `xmp:Rating` write for `MetadataEdit.rating`, 0..5). `asset_user` models `rating` and `flag` as two independent columns, but XMP has exactly one scalar to hold "is this photo picked, rejected, or rated N stars" -- reconciling that (does a flag write clobber a star rating? does `rating` need to start encoding reject too?) is a real product-schema decision, not a code detail, and belongs with whichever work package finally defines `rating`'s Phase 4 UI, not bolted on here as a side effect of sidecar support. Lightroom's own newer "picked" XMP convention (`xmpDM:pick`/`xmpDM:good`, LrC 13.2+) was found too, but only weakly sourced (community/forum reports, not a primary Adobe spec page) and apparently not read by other tools (darktable, digiKam) -- not safe to write down as if verified.
- **WP1.10a / the Android-side sidecar glue -- locating or creating the actual `.xmp` file beside a real MediaStore/SAF-governed asset -- is deliberately not attempted this increment:** `XmpSidecarStore` (this increment) is plain-`java.io.File`-based on purpose, the same "build the safely-isolatable, JVM-testable core; defer the live-platform/device-dependent wiring with written reasoning" split already applied to WP1.4/1.5/1.6/1.7/1.8. Wiring it into `MetadataWriter` for real RAW files means resolving a sibling document next to a scoped-storage asset the app didn't itself pick via SAF, which is either a `MediaStore.createWriteRequest` consent flow or a tree-URI sibling lookup -- both need an `Activity` to show system UI, exactly `MetadataWriter`'s own existing "Write permission" doc's reasoning for why *its* write path already defers to a caller `Activity`, and both are properly verified only against a real device/real scoped-storage behaviour, not this sandbox. Left as a followup alongside WP1.6's already-deferred ViewModel/service work, which the sidecar consent flow would naturally want to share plumbing with (a save action needing to launch a system consent screen and resume afterward).
- **WP1.10a / round-trip tests use hand-authored synthetic fixtures, not a real Lightroom/darktable corpus:** MASTER-PLAN.md's WP1.10 acceptance bar asks for round-trip tests "against Lightroom- and darktable-style sidecars in the corpus" -- the corpus itself was already deferred in WP1.8 (needs licensed sample files this sandbox doesn't have). `XmpSidecarStoreTest`'s fixtures are instead hand-written to match the real shapes both tools are documented to produce (the `<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>` wrapper, `xmp:Rating` as an attribute, `dc:subject` as an `rdf:Bag`) -- the same substitution WP1.7a used for format signatures it couldn't source real sample files for either, verified against documentation rather than skipped.
- **Docs / ADR-013 and V1-RELEASE-BAR.md committed as supplied:** ADR-013 (accepted; supersedes MASTER-PLAN.md Phases 8/9 and ADR-009 §6's Compose Desktop trigger) and `docs/handoff/V1-RELEASE-BAR.md` were committed as supplied by the owner on 25 Sep 2026, as written. Both cite ADR-012 (the catalogue switch), not yet supplied -- expected as a follow-up commit. No code or Phase 1 scope change in this commit.

- **WP8.1 / started ahead of ADR-013/WP8.1's own stated precondition, at the owner's explicit request (30 Sep 2026):** MASTER-PLAN.md's Phase 8 precondition is "P1's KMP core compiles for `linuxArm64`/`linuxX64` headless (WP1.2), **and P7 is released**." Only the first half is true here -- Phase 1 is still "in progress" per this same file's header, Phases 2-7 are not yet even defined (blocked on the owner's own Phase-1b scope decision, per the task tracker), and there is no Android release candidate. The owner asked for this Linux/Ubuntu-Touch groundwork to start anyway, accepting plainly that it is ahead of schedule and cannot be device-verified (ADR-013's own Consequences: "device testing on a real UT phone... is mandatory before the OpenStore release" -- not attempted, not attemptable, in this sandbox). Nothing in WP8.1's row above should be read as Phases 2-7 being done. Full framing lives in ADR-013's own dated addendum (2026-09-30), per this branch's convention of recording an ADR-level decision there rather than only in this progress log.
- **WP8.1 / no shared `Capability` model exists to reuse, despite ADR-013 point 3 assuming one:** checked before writing `fotoz_capabilities()`, not assumed -- grepped the whole repo for a `Capability` class/enum/object and found none. ADR-010's original `:core:model` row named it; WP1.2's own Decisions deferred it ("disconnected from the Room schema... risks getting them wrong"); WP1.3's Decisions deferred it again to WP1.7 ("the format registry"); WP1.7a's own Decisions deferred it a third time ("premature before WP1.10's metadata work and Phase 2's actual decoder packs exist to describe"). It is still deferred. `CapabilitiesDto` (`ut/bridge/.../BridgeJson.kt`) is therefore a bridge-local stand-in -- a plain report of which of `:ut:bridge`'s OWN entry points are real vs. stubbed, not a port of Android's own reporting (there is no Android-side model to match either). Whoever finally builds the real `Capability` matrix should rewire `fotoz_capabilities()` to report through it.
- **WP8.1 / `query_page`'s `ast_json` is a coarse envelope routing to `GalleryProjectionDao`'s real queries, not yet the full `:core:search` AST the name suggests:** `QueryPageRequestDto` carries an `album` name (one of `GalleryProjectionDao`'s 13 real, tested queries -- `SmartAlbum.kt`), plus `hideSensitive`/`showVideos`/`folderKey`/`query`/`pageSize`. `query` IS parsed by the real `:core:search` `parseSearchQuery()` (proving that parser is genuinely wired in, not just referenced), but only its bare, non-negated `Term.Word` terms are applied, as a post-fetch `display_name` substring match -- field terms, numeric comparisons, date terms and negation all parse correctly but are not evaluated. This is deliberately narrower than the app's own real free-text search (`matchesQuery`/`matchesGallerySearch`, folder/tag/OCR/label), which `GalleryProjectionDao` itself does not implement yet either (its own documented WP1.5 gap) -- not a regression, a matched scope.
- **WP8.1 / `MetadataStripper.stripBytes` widened `internal` -> `public`, same precedent as WP1.2's `isJpeg`/`isPng`/`isWebp`:** `fotoz_set_metadata`'s real `strip` sub-operation is `:ut:bridge`'s own, different Gradle module calling into `:core:metadata` -- `internal` is module-scoped, and there is no JVM-style platform wrapper (`MetadataStripperJvm.kt`'s `strip(InputStream, OutputStream)`) for Kotlin/Native to call through instead, since that wrapper is itself jvmMain-only. Widened rather than duplicating the function; no behaviour change.
- **WP8.1 / two real, non-obvious Kotlin/Native-on-this-toolchain findings, discovered by actually compiling and running, not assumed from the JVM:** (1) `Dispatchers.IO` is `internal` on Kotlin/Native's own actual (unlike the JVM, where `:core:db`'s own `desktopTest`/`jvmTest` and `app/.../LibraryRuntime.kt` all use it) -- `fotoz_library_open` uses `Dispatchers.Default` instead. (2) Room's own `.build()` does not open the underlying SQLite connection eagerly on any target -- `fotoz_library_open` originally returned a non-null "handle" even for a path whose parent directory did not exist, caught by this module's own `linuxTest`, not assumed safe; fixed by forcing one real, cheap query (`assetDao().count()`) immediately after `build()` and treating a thrown exception there the same as a build-time failure (closing the half-open database, returning `null`).
- **WP8.1 / the Kotlin multiplatform "default hierarchy template" did not apply to this module, and the fix is explicit `dependsOn` edges, not a workaround:** `:ut:bridge` declares only native targets and needed one intermediate source set (`linuxMain`/`linuxTest`, shared between `linuxX64`/`linuxArm64`) for every `@CName`/`kotlinx.cinterop` file, since those APIs do not resolve from `commonMain` regardless of how many of a module's targets happen to be native. `sourceSets { val linuxMain by getting {...} }` failed outright ("KotlinSourceSet with name 'linuxMain' not found") on this toolchain; created explicitly instead (`by creating` + `dependsOn`/`getByName(...).dependsOn(...)`). One real pitfall along the way: a "test tree" source set cannot `dependsOn` a "main tree" one directly (Gradle: "different Source Set Trees") -- `linuxTest` only needs `dependsOn(commonTest.get())`, since `linuxX64Test`/`linuxArm64Test` already see `linuxMain`'s `internal` declarations through the standard, automatic per-target test/main compilation association, not through the source-set dependency graph.
- **WP8.1 / `:ut:bridge`'s Gradle project inclusion itself, not just its targets, is gated behind `-Pfotoz.native=true`:** every other native-ready module (`:core:model` etc.) declares a non-native target too (`jvm()`, `androidTarget()`), so the module still exists as a Gradle project without the flag -- only its `linuxX64()`/`linuxArm64()` target declarations are conditional. `:ut:bridge` has no non-native target at all (it exists purely to be compiled by Kotlin/Native), so declaring zero targets without the flag would fail Kotlin's multiplatform plugin outright ("Please initialize at least one Kotlin target"). `settings.gradle.kts` gates `include(":ut:bridge")` itself instead, mirroring `:benchmarks`' own conditional-include pattern rather than the `:core:*` modules' unconditional one.
- **WP8.1 / real `.so` link verified by hand, beyond the two required gate tasks:** `:ut:bridge:linkDebugSharedLinuxX64` (not itself part of the `linuxX64Test`/`compileKotlinLinuxArm64` gate) was run directly to confirm `libfotozcore.so` and its generated C header (`libfotozcore_api.h`) actually exist and link -- `nm -D` against the `.so` shows all 15 `fotoz_*` symbols exported as expected. Not wired into `scripts/verify-native.sh` as a standing gate (that script deliberately matches every other module's own `linuxX64Test`/`compileKotlinLinuxArm64` shape, not a link-and-inspect step) -- this was a one-time hand verification that the coarse C API this WP produces is real, addressed directly in this WP's own report rather than left as an unverified claim.
- **WP8.1 / `ops_*` is a single `fotoz_ops_execute` stub, not several named operations:** checked what Phase 1 actually covers under an "ops" concept before writing anything -- `:core:organize` only has planning-stage pure functions (`BulkRenamePlanner`, `SweepPolicy`, `ScanPlan`); the file operations that actually move/copy/delete bytes are plain Android-only `suspend fun`s in `:app` with no journal/staging/recycle semantics of their own (WP1.6's own survey: "a long copy is cancelled, not paused"). Fotoz has no Fylz-style operation engine at any phase. One honest stub covering the whole family, rather than inventing several named operations with nothing real behind any of them.
- **WP8.1 / no QML/Qt/C++ shell attempted, on purpose:** per this WP's own brief, that is a separate, later dispatch needing this WP's exact resulting C API/header shape as its own starting point (now real: `ut/bridge/build/bin/linuxX64/debugShared/libfotozcore_api.h`, regenerated by `:ut:bridge:linkDebugSharedLinuxX64`). Qt 5.15 vs. Qt 6, Lomiri UI Toolkit vs. QtQuick Controls 2, and every Content-Hub/click-packaging question belong to that dispatch, per ADR-013 point 1 and its own dated addendum -- not touched here.

## Owner questions

1. **Monetization model for Fotoz** (the watermark is off meanwhile). Options on file:
   - no paid tier;
   - cosmetic Pro (frames and seals);
   - Fonebrew-style one-time unlock for pro tools (RAW develop, network sources, batch conversion, AI packs), with viewing, sharing and organising free forever and the sideload build fully unlocked.

## Device checks added

- **Phase 1 gate**: `docs/device-test/phase-1-checklist.md` -- every device-only claim from every
  Phase 1 WP, same grouping as `docs/device-test/phase-0-checklist.md`, with ADR-011's migration
  check (upgrade over the owner's real library; every favourite, tag, collection, lock, caption and
  manual pin present) pinned at the top and marked MUST PASS BEFORE MERGE. PR #14's own description
  was rewritten as the Phase 1 gate report, summarising every WP and pointing back to this file's
  entries above for the full reasoning behind each deferral. Phase 2 has not started.
- **Batch 1 (21 Oct 2026)**: `docs/device-test/batch-1-checklist.md` -- every device-only item from the
  owner-feedback batch below (video playback, the filmstrip over a video, subject lift, the curves
  editor, the shrink-to-map transition and the unknown-location plate), grouped by the owner's report
  number, same shape as `phase-0-checklist.md`.

## Post-Phase-1 feature work (30 Sep 2026)

Two owner-requested features, outside the WP numbering above (neither is on `MASTER-PLAN.md`) --
logged here rather than left unrecorded, in the same "what was built / what deviated and why"
shape the WP entries above use.

- **Configurable pinch gesture, part (a): `Year` and `Decade` rungs on the gallery zoom ladder.**
  `GalleryZoomLadder.kt`'s ladder already climbed `Grid(columns) -> Calendar -> MapView`
  (owner: *"zoom out past the sparsest grid and you reach Calendar; further out, Map"*). The
  owner's fuller ask named four temporal levels -- Decade, Year, Month, Day -- but `CalendarScreen`
  turned out to already BE the Month/Day pair fused into one screen (a month's days, with
  prev/next-month arrows for "which month"), confirmed by reading it rather than assumed from the
  brief's own guess. So only `Year` (a grid of 12 months) and `Decade` (a grid of 10 years) were
  missing, and both are now inserted between `Calendar` and `MapView` -- `Map` stays the outermost,
  unchanged rung on purpose, since it is a geographic axis with nothing to do with the owner's
  purely temporal ask. New rung order, dense to sparse: `Grid(min..max) -> Calendar -> Year ->
  Decade -> MapView`. `YearScreen`/`DecadeScreen` (new, in `CalendarScreen.kt` alongside the
  screen they extend) reuse `groupByMonth` as their one data source rather than a second bucketing
  pass, and mirror Calendar's own shape exactly: a paged heading (`NavArrow`s over years/decades
  that actually have photos) and a stamp-framed grid of cells. Tapping a month in Year opens
  Calendar AT that month (`CalendarScreen` gained an `initialMonth: YearMonth? = null` parameter
  for this, defaulting to the old "always the latest month" behaviour for every other caller);
  tapping a year in Decade opens Year at that year, the same way. `GalleryZoomLadderTest` extended
  in place (not duplicated) for the two new rungs' arithmetic; `GalleryScreen.kt`'s `gridActive`
  flag and the view-mode switcher's active-state mapping both updated to treat Year/Decade as
  non-grid (Year/Decade fold into the switcher's "Cal" button, since neither has a quick-select
  button of its own -- they are reached by pinch or by drilling in, not by a named jump).

- **Configurable pinch gesture, part (b): `ViewerPinchMode` in the single-photo viewer.** A new
  `GalleryPreferencesState.viewerPinchMode` (`OPTICAL_ZOOM` default / `PANEL_SHORTCUTS`), persisted
  the same way `accentPalette`/`themeMode` are. `OPTICAL_ZOOM` is `ViewerScreen`'s original pinch
  behaviour, byte-for-byte unchanged. `PANEL_SHORTCUTS` repurposes a two-or-more-finger pinch
  entirely: pinch-in (fingers moving together -- the plain-English reading of "pinch in", and the
  owner's own naming) opens the bottom room (`PhotoDetailRoom`, "this photo's details/info"),
  pinch-out opens the right room (`ViewerActionsRoom`, "what you can do with it" -- which already
  carries Edit among its nine actions, so this reuses the existing "edit/actions panel" the brief
  asked to find rather than inventing a second one). Both rooms are the SAME ones the shell's own
  edge-drag gestures already open (`ViewerSettingsRoom`'s own "GESTURES" caption already documented
  "drag up... for details, in from the right for what you can do with it") -- pinch is a second way
  in, not a new surface. In `PANEL_SHORTCUTS`, `onTransform` is never called for a 2+-finger
  gesture, so pinch cannot also zoom, rotate or pan in that mode; a one-finger drag/page and
  double-tap-to-zoom are untouched by this setting either way (the brief's mutual-exclusivity ask
  was about the PINCH gesture specifically). `detectViewerGestures`' own pinch-direction judgement
  (`resolvePinchDirection`) is factored out pure and unit-tested (`PinchDirectionTest`), mirroring
  how `ZoomLadder.step` is pure and separately tested from the pointer-input loop that feeds it --
  and it reuses `PINCH_STEP_THRESHOLD` from the gallery's own ladder rather than inventing a second
  "how much pinch counts as deliberate" number for the same judgement call. The live dispatch
  itself (the `pointerInput` wiring in `ViewerScreen.kt`) is exercised by code review and the
  targeted unit test above, not by a synthetic-touch Compose test -- this codebase's existing
  gesture code (`detectViewerGestures`'s own predecessor, `snapRotation` aside) has no such test
  today either, and this task did not introduce a new testing pattern to cover only its own
  addition. New setting surfaced in `ViewerSettingsRoom.kt`'s own "GESTURES" section (a
  `RoomRow`/`RoomToggle`, matching that room's existing controls) rather than the gallery's
  `SettingsTabs.kt`: it changes what happens on the screen you are ALREADY on while looking at a
  photo, which is exactly that room's own stated scope.

- **JSON-based theming, with a mandatory plain-text preview.** New `ThemeJson.kt`: a schema
  (`{"version":1,"name":...,"colors":{"light":{...8 keys...},"dark":{...8 keys...}}}`, the eight
  keys being exactly `FotoXplorrTheme`'s own `ColorScheme` slots --
  primary/secondary/tertiary/background/surface/surfaceVariant/onBackground/onSurface), strict
  `#RRGGBB`/`#AARRGGBB` validation with a specific reason for every rejection
  (`ThemeJsonResult.Invalid`, never a thrown exception), and a pure parse/build pipeline
  (`parseThemeJson` -> `ThemeSpec` -> `ThemeColorSet.toColorScheme`) that both the five bundled
  presets and a pasted custom theme now go through identically. This schema is deliberately
  IDENTICAL to the one a sibling agent built for Fylz from the same brief, so a person using both
  apps sees one consistent idea of "a theme file" -- no divergence taken.
  - **The five existing `AccentPalette` presets converted from alpha-derived to concrete hex.**
    `FotoXplorrTheme.kt` used to compute `secondary`/`tertiary` as `accent.copy(alpha =
    0.84f/0.68f)` (dark) or `0.88f/0.72f` (light) -- alpha values the new hex-only schema cannot
    express. Each preset's `secondary`/`tertiary` is now the ORIGINAL accent alpha-blended over
    that mode's own `background` colour, computed once (`primary` and the four other shared
    neutrals were already solid colours and are unchanged) -- e.g. VIOLET dark `secondary` was
    `#CBB4FF` at 84% opacity over `#0D0D10`, now the concrete `#AD99D9`. Recognisably the same
    palette, not a pixel-identical one, per the brief's own bar.
  - **`AccentPalette` gained a sixth value, `CUSTOM`**, rather than introducing a parallel
    "theme source" enum: it is the same "which palette is active" question `AccentPalette` has
    always answered, persisted the exact same way (`GalleryPreferences.setAccentPalette`), and
    `GalleryPreferencesState.customThemeJson` carries the pasted JSON alongside it. Switching to a
    built-in clears `customThemeJson`; `setCustomTheme` is the one path INTO `CUSTOM`, so the two
    fields cannot drift into "CUSTOM selected, but stale JSON from an earlier palette".
    `resolveThemeSpec` falls back to VIOLET if `CUSTOM` is ever selected with empty/unparsable
    JSON (should not happen -- the settings dialog validates before anything is ever saved -- but
    `FotoXplorrTheme` itself never crashes on it regardless).
  - **The mandatory preview**: `SettingsTabs.kt`'s "Accent" row gained a "Custom…" chip
    (`ThemePaletteRow`) opening `CustomThemeJsonDialog` (`GalleryDialogs.kt`), two stages that are
    never skipped -- paste/import (validates through `parseThemeJson` on "Continue", a specific
    inline error on malformed input, never proceeding on one) and then the mandatory preview
    itself: the pasted text, byte-for-byte, in a plain read-only `Text` (not a swatch, not a
    re-serialised copy) set in `FontFamily.Monospace`. Only that stage's own "Apply" persists it;
    "Cancel" on either stage persists nothing. `CustomThemeJsonDialogTest` proves the preview
    shows the EXACT pasted text (deliberately odd whitespace, to catch any accidental
    re-serialisation) via a real Compose interaction test (`performTextInput`/`performClick`),
    not just a unit test of the underlying string.
  - **Not built: a SAF/file-picker "import".** The brief said "paste/import JSON"; a plain
    multi-line text field already supports paste (and copy back out, via `SelectionContainer` on
    the preview) with no extra code, and a real document-picker importer is meaningfully more
    surface (permissions, MIME filtering, a second way for a malformed file to reach the parser)
    for a want the brief did not spell out as its own requirement. If a real file-based import is
    wanted later, it plugs into the exact same `parseThemeJson`/preview pipeline unchanged.

## Design-fidelity round (13 Oct 2026)

The owner supplied five reference screenshots (activity strip with three jobs and with one, the
viewer's filmstrip, the pulled-down details room, the root grid) plus two inline images (the
zoomed-in viewer's navigator; the swivel that reveals the destination list) and asked for a
side-by-side comparison "to the tee", for the viewer to be "buttery smooth and lag free", for
"no crashes", and for each photo's colour palette to be shown and searchable. Three parallel,
worktree-isolated passes, each gated by `./scripts/verify.sh` before merge; the measured
per-screenshot match/differs tables live in `docs/handoff/DESIGN-FIDELITY-GALLERY.md`,
`docs/handoff/DESIGN-FIDELITY-VIEWER.md` and `docs/handoff/COLOUR-SEARCH.md`, and every pass
renders its surfaces to real PNGs under Robolectric NATIVE graphics (`GalleryRenderTest`,
`ViewerRenderTest`) so the comparison is pixels against pixels, not a description against code.

- **Gallery chrome to the references.** `ActivityShade`'s collapsed strip is now the screenshot's
  28dp black strip with one 4dp fill per job on an 8dp track, per-kind glyphs (save / four-arrow
  move / two-page copy), one job drawn full-width without a glyph (as the one-job reference has
  it); `ActivityKind` colours re-measured off the screenshots (`BACKING_UP` #4DFF00, `MOVING`
  #40A0FF, `COPYING` #FFA100 -- was blue -- with distinct hues for scanning/recognising/exporting
  so no two kinds share one). The destination rail is the inline reference's word wheel: 44sp
  bold, one word per 80dp row, white for the current destination with a 16dp square bullet, the
  rest fading by distance (`DestinationWordRail`, app-local because `WordWheelRail`'s type, fade
  and marker are private constants); the 2026-08-14 "covers in the marker gutter" decision is
  superseded by the owner's later reference and recorded as such. The search entry is the root
  grid's 48dp disc at the reference's exact position with a drawn magnifier (`SearchFab`); the
  floating pill stays, shortened to make room (removing it is a product call, not a styling one).
  The grid itself then followed: `GRID_GUTTER` 1dp -> 4dp and 144 x 160 tiles (`GRID_TILE_ASPECT`),
  measured off the root-grid screenshot, replacing the earlier "seamless mosaic" geometry.
  Flagged, not changed: the swivel's visible band (72dp, a shared-shell constant), the pinned
  rail width ellipsizing "Screenshots" on wide windows, the ten destinations including Audio (Audio was removed in batch 2: nine destinations now).
- **Viewer to the references.** The details room moves to the TOP room (pull the photo down and
  it shrinks into its own 80dp map tile; `ViewerSettingsRoom` takes the bottom) -- a deliberate
  deviation from the 2026-08-18 "one geography" principle on the owner's later direction,
  recorded in `ViewerScreen`'s KDoc. (Superseded in batch 2, 21 Oct 2026: one geography again, with
  the viewer's view options on the right and its actions at the bottom.) The room's order is the screenshot's: title and date, camera
  card (its return reverses the 2026-09-01 de-dup pass; the card now owns the capture facts and
  the rows no longer repeat them, so "every fact said once" still holds), the 400 x 400 place
  plate with the photo flown into a bordered tile and pointer, "Add a Caption", Information with
  Show More/Less, Tags. Connect flavour: a one-shot MapLibre `MapSnapshotter` backs the plate only
  after the room has settled open (privacy rule kept: no tile fetch on a swipe); a tap grows a
  full-screen map out of the plate with every located photo as a filled dot in its own dominant
  colour (offline: `StampMapScreen` in one Canvas pass). The filmstrip rests at the reference's
  53 x 58 thumbs on a 74dp band and, while the user drags, bulges to 116dp with the centre thumb
  at 1.7x and drives the focused photo live by nearest-to-centre; a programmatic scroll neither
  bulges nor reports. The zoom navigator is the inline reference's: bottom-left, 82dp long edge,
  translucent light-blue field, solid blue viewport, maths in `ZoomViewport` with no per-frame
  allocation. Crash-case suite (`ViewerEdgeCaseTest`): no EXIF, no GPS, zero dimensions,
  undecodable file, empty roll, video, room opened mid-swipe, rotation under an open room.
  Still differs: typeface (Hyle Grotesk vs the mockup's SF), British "Colour", the mockup's blank
  status-bar band.
- **Colour palettes: persisted, searchable, tappable.** The indexer samples each photo's palette
  (100px decode, median cut) alongside faces/labels/text and stores it in `RecognitionStore`'s new
  `asset_palette` table (DB v4 -> v5; the upgrade keeps every row; a palette-only backfill pass
  samples photos indexed before this without re-running any recogniser, ~10-20ms each). `:core:search`
  gains `colour:` (`color`/`hue`/`palette`), accepting `#rrggbb`/`#rgb` or one of twenty colour
  words, matched by CIE76 ΔE in Lab (hex 14, chromatic words 25, achromatic words 14 -- measured:
  at 25 "silver" swallowed pure white) against swatches holding at least 8% of the photo; Lab is
  precomputed once per photo on the reload thread, so a keystroke converts nothing. The details
  room's palette bar (`PaletteSwatchRow`, one draw call, 48dp targets) reads the stored palette
  first, this process's memo second, and decodes only for a photo the indexer has not reached;
  each swatch searches the library for its colour through the same path as the Search pill; the
  stored dominant colours also feed the expanded map's dots (`RecognitionIndex.dominantColoursByMedia`),
  so no dot ever costs a decode. Native-target compile of the new search code is part of
  `scripts/verify-native.sh` (a first push broke it on comma-bearing test names; fixed).
- **Not on disk:** the two inline reference images (zoom navigator; swivel reveal) were never
  saved by the upload, so those two surfaces were built from the owner's description and should
  be re-checked against the originals once re-attached.

## Rename to Fotoz, and accent theming that reaches everything (13 Oct 2026)

**Owner decision.** The apps are named **Fotoz** and **Fylz**. This repository is Fotoz (it was
Foto Xplorr). The owner also asked that both apps be themeable at least at the level of an accent
colour, and for APK links for both.

### What was built

- **Rename, user-visible text only.** The launcher label now comes from a new
  `res/values/strings.xml` (`app_name` = `Fotoz`) and the manifest points at it, so the recents
  title, Settings > Apps entry, permission dialogs and the "Open with" chooser entry all say Fotoz.
  Every in-app string (settings, about, share sheet, watermark text, empty states, permission
  explainers, support-email subject, crash-recovery label), the zip name offered when sharing
  several items (`Fotoz 3 photos.zip`), the suggested metadata-export file name
  (`fotoz-metadata.json`), the CI artifact names and the release title (`.github/workflows`), the
  README and the docs prose were renamed. Package, `applicationId`, FileProvider authority, class
  and file names, Gradle names, persisted preference keys, database names, the Keystore alias,
  MapLibre layer ids, repository URLs and the `Foto-Xplorr` repo name were left exactly as they
  are. The README gains one line saying Fotoz was previously Foto Xplorr and which identifiers keep
  the old name. Two quoted titles of an external document ("Foto Xplorr -- gap analysis ...") in
  `MASTER-PLAN.md` and `PHASE-0-BRIEF.md` keep the old name because they are quotations.
- **Compatibility for anything written under the old name.** Only one thing is written to the
  device by name and read back: the fallback folders for edited copies and conversions
  (`MediaStoreDestination`). New saves go to `Pictures/Fotoz/`, `Movies/Fotoz/` and
  `Music/Fotoz/`. The old `Pictures|Movies|Music/Foto Xplorr/` folders are never renamed or moved,
  are still indexed (the scanners have no folder filter), and a source already inside one keeps its
  exact path because the top folder is an allowed one, so editing an older copy still saves beside
  it. That last rule is now pinned by a test (`a source already in the legacy Foto Xplorr folder
  keeps its exact path`) and explained in `mediaStoreRelativePath`'s KDoc. The share zip name lives
  in a cache directory that is wiped on every share, the metadata export is imported through a
  document picker with no name filter and no app name in the JSON, so neither needed compat code.
  The watermark text and the recovery label of a crash stored by an older build keep the old text
  where they were already produced; nothing reads them back by name.
- **Accent: the chosen accent now drives every accent-role element.** `toColorScheme` derives the
  five Material slots the schema never carried (`onPrimary`, `primaryContainer`,
  `onPrimaryContainer`, `secondaryContainer`, `onSecondaryContainer`) from the existing eight keys,
  so buttons, the floating action button, selected filter chips, slider tracks, the album and
  Places cover tiles and the selected-tile tick follow the accent instead of Material's baseline
  purple. The theme JSON schema is unchanged, so stored custom themes keep working. The ink drawn
  on an accent is `onPrimary` (white or near-black, whichever reads better) instead of a hard
  `Color.Black`, at eleven pill and bubble sites. The "Show More/Less" link, the live-text
  selection fill, the Hyle field's focus wedge, border and cursor, the connect-flavour Places
  cluster bubbles and the crash-recovery screen's accent now follow the choice too; `MapFocus` no
  longer has a theme-blind default accent.
- **Picker.** It was already reachable (Settings > Appearance > Accent), persisted
  (`GalleryPreferences`) and live (`FotoXplorrTheme`). Gaps closed: the Settings button on the
  Albums, Discover and Library screens opened the Actions room instead of Settings; each accent
  chip now shows a swatch of its colour in the mode being shown; "Custom..." starts from the active
  accent's JSON instead of always Violet; a dead second picker (`GallerySettingsList`, no callers,
  with an inert Custom chip) was deleted; `FotoXplorrTheme` now remembers the resolved scheme
  instead of re-parsing a custom theme's JSON on every recomposition.
- **Fixed on purpose.** Every colour left alone has a one-line comment at its definition saying
  why it is fixed (reference design or semantic): job-kind hues, the activity strip and track
  greys, the error salmon, the rail's white marker, the room's dark neutrals, the zoom navigator's
  blues, the search disc and pill, toggle and stepper neutrals, the widen/narrow/alternative
  marks, the trash red, map pins and compass north, categorical cluster hues, exported share
  frames, and the static window and status-bar colours.
- **Tests.** New `AccentThemeTest`: each bundled accent yields a different primary and a different
  primary container in both modes, no accent-role slot is left on Material's baseline, the ink on
  an accent has contrast of at least 4.5 for every bundled accent in both modes, a running
  composition re-themes when the accent changes, and the persisted accent (bundled or custom) is
  restored into the theme after a restart. `MediaStoreDestinationTest` and `ZipExporterTest` were
  updated for the new names.

### What deviated and why

- The repository has no `CLAUDE.md`; this log and the docs under `docs/` were the conventions
  followed.
- The Grid / Cal / Map / Time view switcher's selected pill is now accent-filled (it was white).
  The audit marked this a judgement call; every other selected pill in the app uses the accent, and
  nothing in the references sets it apart. Revert that one line if the owner wants it neutral.
- The Hyle field's focus state uses one accent for the wedge and the border; the control sheet
  drew the border blue and the wedge violet, and a single user-chosen accent cannot honour a
  two-tone default. The field is a fixed light control, so it reads the light-mode form of the
  accent in every theme mode (`LocalAccentOnLight`).
- `FilmstripScrubber` has no colour literals, so it got no "fixed" comment.
- `XmpPacketTest` still writes "Added by Foto Xplorr" and `ExifCopierTest` a camera Make of
  "Fotoxplorr Cam Co": both are self-contained fixtures, not app strings.
- The Android debug workflow is now titled "Fotoz debug APK" and its artifacts are `fotoz-*`; any
  external script that fetches APKs by the old artifact names (`foto-xplorr-debug`,
  `fotoxplorr-arm64-INSTALL-THIS`) must switch to the new names. Old-named artifacts age out under
  `cleanup-artifacts.yml`.

### Residual, for the owner

- Folder identity is derived from the path, so a user who locked or hid the old
  `Pictures/Foto Xplorr` album has no lock on the new `Pictures/Fotoz` album. Aliasing the two
  keys would change what `PrivateFolderStore` means, so it is left as a separate owner decision.
- The `shared-libraries` submodule still has "Foto Xplorr" in comments and test fixtures; that is a
  separate repository and was not touched.
- The Ubuntu Touch bridge's example data directory in a KDoc still says `fotoxplorr`; the future Qt
  shell should choose `fotoz` when it picks the real directory.

Gate: `./scripts/verify.sh` is green on both flavours (1017 unit tests each, lint, both debug
assemblies, the offline enforcement gates). Nothing under `core/` changed, so
`scripts/verify-native.sh` was not needed.

## Batch 1: bugs and regressions from the owner's feedback (21 Oct 2026)

The owner tried the consolidated build and reported five things (several are quoted in the KDoc of
the code that answers them): **#1** *"Fotoz is not playing videos"*; **#28** *"When I
scrub through the filmstrip and stop on a video, the video player progress bar etc. replaces the
filmstrip so scrubbing through is no longer possible"*; **#12** *"Tap the subject to lift it is not
working. Needs to be richer: tap to select, draw to select, refine selection, etc."*; **#15** *"Edit
doesn't work great - pro tools toggle etc. Curves doesn't show me the curves at all, like Snapseed
does - I want that, not just the presets"*; **#19** *"The transition effect of the photo becoming
smaller and appearing on the map ... is not happening ... not a pane tilt, rather a shrink-to-appear-
on-the-map effect. And the map bg when the location is unknown is weird, and not comforting at all."*

Method: a read-only diagnosis of each report, a critic pass that corrected it (and flagged what stayed
a guess), then one code unit per report on disjoint files, then two wiring units that owned
`ViewerScreen` (video and filmstrip; lift and flight), then this docs unit. Each code unit was
required to compile for both flavours and pass its own tests before committing (the full
`./scripts/verify.sh` gate is the orchestrator's final step and is not recorded here); **no device
or emulator was available, so nothing below has run on a phone.** The device-only items are in
`docs/device-test/batch-1-checklist.md`, and the lessons that bind later work are `docs/TRAPS.md`
#39 to #56.

Commits (oldest first): `8d13ccb` editor (curves, no tiers); `0424f87` video engine; `4c657ab` lift
dead-tap fixes and the actions-room row; `a421009` lift scribble editor; `58133f4` flight and
unknown-location plate; `e058630` filmstrip is roll-level; `2ef7c8c` video wiring; `496defc` lift
wiring; `fa82327` flight wiring.

### Owner decisions applied (not re-asked)

- Video autoplay defaults to **ON** (opening a video starts it; the user's toggle persists), and the
  chrome is shown the first time a video opens.
- The editor's **Simple/Pro tier is removed**: one scrollable tool strip with Curves reachable.
- The shrink flight must not fetch map tiles during a gesture or pre-warm (privacy): the existing
  160 ms settle debounce stays.
- `shared-libraries/` is another repo's submodule and was **not edited**; `ParkStyle` is chosen
  app-side.
- The rooms were **not moved** in this batch. (Later batch.)
- Lift Phase B (a bundled learned model) is **not** in scope.
- Sticker export is a 2048 px PNG with alpha to `Pictures/Fotoz/Stickers`, on a user action only.
- Audio is slated for removal later and was not touched. (Removed in batch 2.)

### What was built, per report

- **#1 Videos.** The old player had no start affordance with the chrome hidden (autoplay off, chrome
  down, a tap only toggled chrome) and **no error handling at all**, so any failure was a black frame
  with a Play glyph that did nothing; the transport keyed on `isPlaying` (false while buffering, so
  Pause was unreachable), the resume position was never saved (the save after the poll loop was
  unreachable, and dispose does not run on process death), `ON_STOP` did not pause, and an external
  clip shared one resume key. Now (`VideoPlayer`, `PlaybackLogic`, `ViewerPlayerFactory`): autoplay on
  and chrome up on the first video; transport from `playWhenReady` and state; an error panel with
  Retry / Open with and plain-language copy; a player built only after 250 ms on screen; decoder
  fallback; audio focus and pause-on-noisy; sidecar subtitles resolved and probed before the first
  prepare, with one silent retry without them; resume saved on pause / `ON_STOP` / loop end / release,
  gated on having played or seeked; key-moment indexing 1.5 s after first ready; PiP armed only while
  playing; `ExternalViewerActivity` declares PiP and gives each external video its own negative id.
  See PRODUCT-AND-ARCHITECTURE §4 "The viewer's moving parts".
- **#28 Filmstrip.** `ViewerChromePolicy.filmstripShown` has no asset: the strip is up over a video;
  `FilmstripScrubber.onScrubbingChanged` (false on dispose) pauses a playing video on touch and holds
  the next one back until the strip settles; the transport lifts by the strip's 74 dp band and its
  second row folds behind "More" on a short window. The slideshow waits for a playing video, and
  Lift being active holds it too.
- **#12 Lift.** Two dead-ends fixed first: taps were mapped through `MediaAsset.width/height` rather
  than the decoded bitmap, and `decodeUpright` could throw uncaught on an unreadable URI (a crash,
  not a no-op). Then the richer editor: select by tap or scribble (with a Background polarity), a
  refine brush, undo/redo by replaying a history, a checkerboard preview, Copy / Share / Save sticker.
  Reachable from an always-present "Lift subject" row in the actions room as well as by long press.
  The overlay now draws its own stage, sits outside the zoom layer, and withdraws the rooms.
- **#15 Editor.** Curves was unreachable because a weightless `LazyRow` starved the "Pro tools" chip
  to zero width in both tiers (TRAPS #44). The tier is gone. Curves is a drawn graph with a histogram
  backdrop, per-channel (RGB, Luma, R, G, B) draggable points (add by touching the curve, drag; remove
  by long-press, double-tap, or dragging off the graph), Reset / Reset all and the named presets as
  chips. A real luma curve was added. Undo/Redo moved into the top bar.
- **#19 Flight and plate.** The flight is an overlay driven by the shell's progress; the unknown
  plate is calm (graticule, ghost pin, "No location yet", "Add a place"); the picker lost its spin and
  gained fixes. See `DESIGN-FIDELITY-VIEWER.md` "Revisions in batch 1" and TRAPS #40, #41.

### What deviated and why

- **Armed start, not a mount gate.** The filmstrip plan wanted the player unmounted while scrubbing;
  the critic showed a page swipe and the details room build players the same way, so the guard moved
  inside `VideoPlayer` (250 ms armed delay; `scrubSuspended` only pauses and holds arming back).
- **Key-moment indexing waits for the first ready state plus 1.5 s**, not just the armed delay, to keep
  retriever decodes off the player's critical path. The "Key moment" pill therefore appears a few
  seconds later than before.
- **Autoplay with a remembered position resumes silently** and hides the "Resume from" chip (with
  autoplay off the chip shows); otherwise default-on autoplay would flash the chip and restart at 0:00.
- **The centre play glyph shows whenever the transport would offer Play** (paused, ended), not only
  before the first play, and is hidden while an idle player is about to autoplay and while the error
  panel is up.
- **Manual Retry after a decoder error keeps subtitles** unless the silent retry already dropped them.
- **`ResumePositionStore` also refuses every negative id**, a defensive guard on top of `VideoPlayer`'s.
- **`ViewerScreen.autoplayVideos` default flipped to true**, so `ExternalViewerActivity` (which does not
  pass it) autoplays too.
- **The position chip uses the shared chrome rule**, not the filmstrip flag (see the viewer design doc).
- **Room withdrawal while lifting waits for the shell to be home**, so a room closed to start the lift
  slides away rather than vanishing; a long press at home withdraws immediately.
- **Flight timing.** It runs over the whole pull, not the critic's geometry-derived late window (the
  flier stays on screen at every progress, tested); the hand-off keeps the real pin opaque under a
  fading flier rather than the critic's complementary alphas (which would dim the plate).
- **The flier is composed only when the photo is at rest** (not zoomed or turned); otherwise the
  room's own in-plate shrink plays. `PlacePlate` reports ROOT bounds while `PhotoDetailRoom` reports
  ROOM-local, de-duplicated bounds (deliberately different semantics, documented on both).
- **Lift: Tap and Draw are one Select tool** (a tap is a one-point scribble) with a Subject/Background
  chip; Refine is the separate add/erase brush. The tap seed is a robust mean of a small disc around
  the tapped pixel (a thin line keeps the line's colour; a hot pixel is ignored). A tap on the
  letterbox is swallowed in the editor instead of dismissing it, to avoid losing work to a stray tap.
  The brush type is `BrushStroke` (not `Stroke`, which clashes with the Compose drawing type).
- **Editor.** `AdjustmentRenderer` was **not** edited: the luma table rides inside `ChannelLuts.luma`.
  `CurveChannel` moved into `ToneCurve.kt` (pure) with its colour as an extension in `CurvesEditor.kt`.
  A tap on empty space far from the curve inserts the point under the finger (bending the curve);
  near the line it inserts on the line. Drag-off removal is armed (red, haptic) and happens on release,
  and can be cancelled by dragging back in. `insertAt` is not bit-identical (neighbouring tangents are
  recomputed; the tests allow up to 8 levels). The autofix offer row is hidden on the Curves tab.
- **Tests.** Every unit added NEW test files; the three shared viewer test files were left alone. No
  Compose/Robolectric test exists for the lift overlay or the whole `EditorScreen` (the former needs
  decode plumbing, the latter `MediaAsset` plumbing): gesture flow, chrome and layout budgets are
  compile-verified and reasoned only.
- **Verification environment.** `captureToImage()` times out under Robolectric here (TRAPS #56);
  the offline source scan is line-based and includes comments (TRAPS #43); a Gradle build needs the
  shared lock when several agents work in one tree.

### Found, not fixed (outside the units that found them)

- `ViewerActionsRoom.ActionRow` makes only the 22 dp icon clickable; the label beside it is dead.
  Relevant to #12's discoverability: tapping the words "Lift subject" does nothing. The fix is one
  `Modifier.clickable` on the row.
- `LiveTextOverlay` consumes every tap and long press on a photo with recognised text while the chrome
  is up, so long-press-to-lift and tap-to-toggle-chrome do nothing there (TRAPS #50).
- `MediaAsset.width/height` may be the un-rotated sensor grid for an EXIF-rotated photo; Lift now uses
  the decoded bitmap, but `LiveTextOverlay`, the minimap and the flight's start rectangle still trust
  the stored size (TRAPS #48). Unverified until logged on a device.
- The rooms' positions in the viewer (details top, actions right, settings bottom) differ from the
  layout in the original brief for #12 (details top, actions bottom, view options right). Not moved in
  this batch by decision; the owner should say which is intended before the rooms batch.
  **Resolved in batch 2 by owner feedback #26:** left navigation, top details, bottom actions, right
  view options, in the gallery and the viewer alike.

### Residual, for the owner

- Whether to schedule lift Phase B (a bundled learned segmentation model) once the licence and size
  trade-off is decided; until then hair, fur, glass and low-contrast edges will be rough.
- The classical solver's honest limits (multi-coloured subject needs a second scribble; a same-colour
  neighbour needs a Background scribble) are in PRODUCT-AND-ARCHITECTURE §5.
- Audio focus is a visible change: starting a video now pauses another app's music (Fotoz has no
  music of its own any more).
- `proguard-rules.pro` has no Media3 section; the release build relies on the library's consumer
  rules (a device smoke test, listed in the checklist).


## Batch 2: audio out, one open-with door, a safer Replace, one room geography (21 Oct 2026)

The owner's second round on the consolidated build. Items quoted in the code that answers them:
**#18** *"The favourite icon should be visible when a photo is being viewed - doesn't need to hide in
the right side menu."*; **#16** *"Once slideshow mode is on, the slideshow controls should be overlayed
in the style of the actions bar at the top."*; **#17** *"When viewing images that have transparency,
we should be able to choose background - the grey white grid, or white or black."*; **#6** *"The help
text is too verbose."* The rest arrived as direction rather than a numbered quote: remove Audio from
the product; the rail should scrub; Settings should take over the page; one toggle look; and **#26**,
the rooms geography (left navigation, top details, bottom actions, right view options, in the gallery
and the viewer alike), which answers the question batch 1 left open.

Method: a read-only design pass (six plans and a critic that corrected them, with a short list of
questions that needed an owner answer and a default for each), then serial code units on this one
branch, each required to compile for both flavours and pass its own tests, then this docs unit.
**No device or emulator was available; nothing here has run on a phone.** The full
`./scripts/verify.sh` gate is the orchestrator's last step and is not recorded here. Device-only
items are in `docs/device-test/batch-2-checklist.md`; lessons that bind later work are
`docs/TRAPS.md` #57 to #67, and #17, #31, #34, #35, #47 were corrected.

Commits (oldest first, grouped): **open-with and audio** `0395dfa` (`ExternalViewerActivity` the single
VIEW target), `fc04c73` `294520a` `8192057` `90d44cb` `e7e7450` (audio removal 1-5), `e1fad2d` (parity
test: 25 projections per privacy view = 9 destinations + 11 smart albums + 4 sorts + the timeline),
`e8c394c` (hidden-photo bypasses closed). **Editor** `dec6a54` `abe8c5c` `0970c68` `3d906e8` `f4b72a2`
`e9ec27a` `88c148a` `5194e55` `f34eeda` `326b48c`. **Video** `e43dcf5` (exporter error mapping and
publish pinned). **Core** `ceb2243` (`AlphaSniffer`). **Rooms and navigation** `4f48c47` (rail scrub)
`02bc6fe` (Settings as a page) `5a13e19` (one toggle) `9d33fbe` (one geography) `6d2c47d` `294d75c`
`7ede90e` `c771edc` `3cd648b` `253f8ba` (the quick strip, library bumps and review fixes). **Viewer**
`0d3362a` (heart) `026a8b1` (transparency) `84e3e4e` (slideshow bar). **Help text** `27e5c35`.

### Owner decisions applied (not re-asked)

- **Audio is removed**, not hidden: the Audio destination, library, player, tag editor and
  audio-to-AAC conversion, the `audio/`, `audiotags/` and `playback/` packages, the media-playback
  service, and `READ_MEDIA_AUDIO` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. `WAKE_LOCK` stays in the merged
  manifest because Media3 contributes it. The old audio database and scan preferences are deleted on
  first launch; user audio files are never touched; a saved default view of Audio opens on Photos.
  Kept: Lens text-to-speech, the tab tick sound, the video player's audio-track menu, the editor's mute.
- **`POST_NOTIFICATIONS`** stays declared and is requested once, on Android 13+, the first time a job
  runs.
- **Open-with:** `ExternalViewerActivity` is the single VIEW target (`content://` only); EDIT and SEND
  stay on `FotoXplorrActivity`; a photo the library hides is never opened from outside (a notice
  instead).
- **Replace the original refuses** rather than loses (metadata it cannot carry, animation, Ultra HDR,
  Motion Photo, transparency, lossless WebP), and writes with a safety copy. **Save As strips
  location** with no opt-in switch. The README HEIC claim was corrected, not the behaviour. No
  `android:largeHeap`, no video free-space pre-flight.
- **One toggle:** `RoomToggle` is `HyleToggle`; disabled is a fade of the live colours; the on state is
  a position, not a hue (the accent-coloured on-state was not done). `RoomStyle.InkFaint` rose to 0.55
  alpha and `InkMuted` to 0.70 so the three ink steps stay ordered.
- **Heart:** in the chrome row, tied to the chrome (so immersive photos hide it), for catalogued photos
  and videos; the actions room keeps its Favourite row. **Transparency:** default checkerboard; a chrome
  button only on photos that have transparency, and a row in the view options.
- **Help text:** one `HelpCopy` object, no onboarding tour; four lines flagged for the owner's or
  counsel's eye (below).
- **Store rename:** Fotoz in the fastlane title and description, the privacy policy and the Play
  Console sheet; contact address and package unchanged.

### What was built, per item

- **Nine destinations; Settings as a page.** `HyleDestination` has nine entries. Settings (nine tabs:
  Appearance, Media, Library, Privacy, Viewer, Data, Background, Pro, About) and Albums, Calendar,
  Discover, Library and Tidy up share `PaneScaffold`; `paneTakenOver` and `paneBackTarget` are the one
  predicate and the one Back order. While a page is up the other rooms' drags and the keyboard
  shortcuts (Escape excepted) are off, the rail leads back out, and background-job progress shows over
  it. Settings chips and options have 48 dp targets. The Connect build's About tab said "no INTERNET
  permission" though its manifest holds INTERNET; it now says what is true for the flavour.
- **The rail scrubs.** The square tracks the finger; the word that rests on the focus line for 140 ms
  is committed; a fling commits where it lands; release only settles and never closes the room (TRAPS
  #61). Selecting a destination now clears `legacyScreen` (a verified bug: a destination screen stayed
  on top of the one just picked; TRAPS #62).
- **One geography.** `ui/RoomGeography.kt` is the table; both screens build slots through `roomSlots`.
  The bottom actions room is two-stage: a quick-action strip at the head on a short pull
  (`ui/QuickActionStrip.kt`), everything on a longer one. That needed changes in `shared-libraries`
  (a navigation-bar-aware bottom edge zone, `bottomPeek`, `hasBottomPeek`, `bottomReserve`, a
  stale-flick guard), pinned here at `1f33d10` (PR #17 there, to be merged first). The viewer passes
  `bottomReserve` so a vertical-first drag on the filmstrip or the video transport scrubs instead of
  opening the room; the quick strip is context-aware in the gallery (browse, selection, trash). Escape
  closes an open room first and every other shortcut is ignored behind it.
- **Open-with.** One VIEW door; `content://media` Uris read as Android reads them; unscanned rows looked
  up in MediaStore and held to the folder rules; stale or foreign `EXTRA_OPEN_MEDIA_ID` dropped; only
  three incoming extras are read (TRAPS #57, #58).
- **Editor safety.** Typed `OverwriteException`; the hazard and metadata gates; the safety copy in
  `filesDir/overwrite-recovery`; a recovery dialog that names the photo, offers to share the copy and
  returns until answered; Save, Cancel and Back locked while a write runs; the write's bookkeeping
  survives recreation; the in-session warnings name the photo. Save As strips and proves it. Exports no
  longer shrink by memory class; they step down on a real out-of-memory error (TRAPS #59, #60, #65).
- **Viewer.** The heart; the slideshow bar with a real pause and a three-second chrome auto-hide (a
  swipe or the bar's Next now goes in order in a shuffled slideshow; shuffle lives only in the
  countdown); the transparency backdrop, detected by `AlphaSniffer` from at most 64 KiB of header,
  cached per photo and modification time.
- **Help text.** `ui/HelpCopy.kt` holds the trimmed copy in three groups (captions at most 60
  characters, hints 80, notes 160), with `HelpCopyTest` holding the ceilings. `BackgroundWorkStatusTest`
  was changed with the strings it pins. Jargon went ("embedding", "tflite", "OpenGL ES").

### What deviated and why

- **The two-stage reveal was planned for later and was done now.** The design pass said to ship the
  geography only and have the owner file the library change; the library work landed during the batch
  instead, so the quick strip exists. A short bottom pull no longer "settles to home or fully open".
- **Rail select no longer closes the room** (owner #5), so the room's close paths are tapping
  the selected word, the parked card, or Back (the pinned rail has none).
- **The viewer chrome row got a swatch button as well as the heart** (transparency, only on see-through
  photos), and the heart's glyph pair is `Outlined.Favorite` / `FavoriteBorder`: the design document
  thought these were the same hollow heart; a render showed `Favorite` is the solid one.
- **The slideshow bar is flush to the top edge and taller by the status-bar inset**
  (`SelectionToolbarTallShape`) instead of a filler strip, with glyph targets of 48 dp rather than the
  mockup's 46. The hide timer keys on the computed auto-hide boolean and a press counter, not the asset
  id (otherwise a 2 to 3 second interval would never let it fold). The video "Resume from" chip now
  sits under the chrome and the bar rather than at a fixed 24 dp.
- **Backdrop is a composable inside the zoom layer**, so the grid's squares scale with the picture (v1).
  An optional `onImageSize` callback gives an SVG its drawing's shape.
- **Help text:** the Connect About statement is new wording the table did not propose; the AI settings
  device line was shortened rather than dropped (it sits behind a details toggle); the Trash row's
  caption was verified against the screen before it was kept.

### Found, not fixed (outside the units that found them)

- **`decodeUpright` returns null on API 26 and 27** (TRAPS #34): its bounds-only `BitmapFactory` pass
  returns null by design and the line ends `?: return null`. The `ImageDecoder` path is fine.
- `ViewerActionsRoom.ActionRow` still makes only the 22 dp icon clickable; `LiveTextOverlay` still
  swallows taps and long presses on a photo with recognised text while the chrome is up (TRAPS #50).
- `EditedCopyWriter`'s class KDoc still says overwrite refuses "a source holding IPTC-IIM or extended
  XMP" (it now also covers PNG text and unknown WebP chunks).
- The grid and filmstrip thumbnails and the shrink-to-map flier do not draw the transparency backdrop.
  A photo that declares an alpha channel but is fully opaque shows the swatch button and the choice
  changes nothing visible (the sniff reports what the file declares).
- The Samsung `MotionPhoto_Data` marker was written from memory of its layout and was not checked
  against a real file.

### Residual, for the owner

- Four copy lines changed meaning-preserving but are legal-ish and want a human read: the offline
  statement, the Places privacy paragraph, the AI-service paragraph and the Pro "nothing is charged
  yet" line (plus the new Connect statement).
- Store rename: the hosted privacy policy at the unchanged URL must be republished with the release
  that drops the audio permission, `versionCode` must go to 5 to match `changelogs/5.txt`, and the
  Play permission and foreground-service declarations must be re-answered without audio.
- The licence is PolyForm Noncommercial 1.0.0, which is source-available and not open source; the
  store text now says so and `play-console.md`'s old "Apache-2.0" line was wrong.
- Whether to build a Restore button for a safety copy (recovery is share-out only today), and whether
  to set `android:largeHeap`.
- Rooms: the live-preview partial-width detents for the right room's view options still need a
  shared-shell change.
