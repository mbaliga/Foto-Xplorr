# ADR-010 — Module split: a KMP-shaped shared core, JVM first, native as each module becomes ready

**Status:** accepted (owner review at the Phase 1 gate)
**Date:** 24 Sep 2026
**Work package:** WP1.2 (master plan §2.1, Phase 1)
**Depends on:** ADR-009

## Context

Today everything lives in `:app` (plus `:feature:ai-remote`), about 55k lines.

The logic worth sharing with Ubuntu Touch (Phase 8) and Linux (Phase 9) is partly pure already, and partly tied to JVM-only APIs:

| Code (branch `claude/fylz-fotoz-complete-y60pfw`) | JVM/Android imports | Portability |
|---|---|---|
| `formats/AnimationSniffer.kt`, `formats/MediaFormat.kt`, `gallery/GridIndexMap.kt`, `media/SweepPolicy.kt`, `media/ScanPlan.kt` | none | Native-ready now |
| `share/MetadataStripper.kt` | `java.io.InputStream/OutputStream` | Needs a byte-source abstraction (okio `Source`/`Sink`, or its own `ByteReader`) |
| `search/SearchQuery.kt`, `SearchDocument.kt`, `SearchSuggestions.kt` | `java.time`, `java.util.Locale` | Port to kotlinx-datetime; inject a month-name formatter |
| `fileops/RenamePattern.kt`, `fileops/BulkRenamePlanner.kt` | `java.time.ZoneId` | Port to kotlinx-datetime |
| `gallery/GalleryProjection.kt` | `java.time`, `DateTimeFormatter`, `Locale` | Moves in WP1.5 as SQL + a pure projection layer |
| `metadata/XmpPacket.kt` | `org.w3c.dom`, `javax.xml`, `ExifInterface` | JVM-only for now; the native XMP parser is a Phase 8 prep task |
| `recognition/FaceClustering.kt`, `curate/*` heuristics | mostly pure | Check file by file |

`search/SearchChips.kt` is Compose UI and stays in `:app`.

## Decision

### 1. Modules created in WP1.2

Each is a Gradle KMP project under `core/`, registered in `settings.gradle.kts`.

| Module | Targets | Contents (moved, not rewritten) | Native-ready at the end of WP1.2 |
|---|---|---|---|
| `:core:model` | jvm + native* | `MediaId`/`AssetId`/`SourceId`, `FormatId`, `Capability`, rating/flag/label enums, the query AST types | yes |
| `:core:formats` | jvm + native* | `MediaFormat`, `RawVariant`, `AnimationSniffer`, a new `FormatSniffer` (magic bytes), the capability matrix. `GifDecoder`/`GifEncoder`/`Lzw` only if they're pure | yes |
| `:core:metadata` | jvm + native* | `MetadataStripper` (behind a small `ByteSource`/`ByteSink` interface in `commonMain`, with JVM adapters for streams); `XmpPacket` in **`jvmMain`**; EXIF tag lists that don't need `ExifInterface` | partial (stripper yes, XMP no) |
| `:core:search` | jvm + native* | Query tokenizer/parser/AST, suggestions engine, `parseByteSize`, type/category matching. Date logic on kotlinx-datetime; month names through an injected `MonthNames` interface (the JVM implementation uses `java.time.format.TextStyle`) | yes |
| `:core:organize` | jvm + native* | `RenamePattern`, `BulkRenamePlanner` (kotlinx-datetime), duplicate-keeper rule, `SweepPolicy`, `ScanPlan`, `GridIndexMap` | yes |
| `:core:db` | android + jvm + native* | Created **empty but compiling** in WP1.2 (Room plugin, KSP wiring, a trivial `@Database` smoke test on the JVM). Filled in by WP1.3 | yes (smoke) |
| `:core:index` | jvm + native* | Created in WP1.4 (sync engine interfaces). Placeholder module only in WP1.2 | — |
| `:core:ml-api` | jvm + native* | Interfaces only (`Embedder`, `FaceEmbedder`, `Segmenter`, `Inpainter`, `QueryParser`, `OcrEngine`, model-pack manifest). Created when first needed (Phase 5), not in WP1.2 | — |

`*` = native targets are declared only with `-Pfotoz.native=true` (ADR-009 §3).

### 2. Dependency rules, enforced in WP1.9

- `:app` → `:core:*` only. `:core:*` never depends on `:app`, `:feature:*`, Hyle or shared-libraries.
- `:core:model` depends on nothing but the Kotlin stdlib. Other core modules may depend on `:core:model` and on each other without cycles, in the order `model ← formats ← metadata ← search/organize ← db ← index`.
- **`commonMain` must not import `android.*`, `java.*`, `javax.*` or `org.w3c.*`.** Enforce it with a Gradle check task (a source scan, in the same style as `verifyOfflineSourceReferences`) wired into `verify.sh`. JVM-only code goes in `jvmMain` or `androidMain`, never in `commonMain`.
- Offline gates: the core modules are on the offline runtime classpath, so they must add no network library. The existing `verifyOfflineRuntimeClasspath` covers them automatically. Confirm it after the split.

### 3. How to move code (the WP1.2 procedure)

1. **Move one package per commit**: `git mv` into the module, fix the package declarations (keep package names `com.fotoxplorr.core.<module>...` and update imports in `:app`), and move its tests with it.
   - JUnit4 tests go to `jvmTest` unchanged.
   - Pure tests that can become `kotlin.test` move to `commonTest`, so they also run on `linuxX64`.
2. Port `java.time` → kotlinx-datetime **inside the move commit for that package**. Behaviour must stay byte-identical: run the package's own tests plus `CatalogueCharacterisationTest` (whose goldens must not change) before committing.
3. Robolectric-dependent tests stay in `:app`.
4. After each move: `./gradlew :app:testOfflineDebugUnitTest` plus the module's `jvmTest`, then `-Pfotoz.native=true :core:<m>:linuxX64Test` if the module is native-ready.
5. Keep a table in `MASTER-PROGRESS.md`: module, packages moved, native-ready (Y/N), blockers.

### 4. Out of scope for WP1.2

- Room entities: WP1.3.
- The sync engine: WP1.4.
- Moving `GalleryProjection`: WP1.5 decides its SQL form.
- Any UI code.
- A C API: Phase 8.

## Consequences

- The app's behaviour doesn't change. The goldens and the 977 existing tests are the proof.
- Build times go up somewhat with more Gradle projects; native builds only run in the CI job.
- `XmpPacket` stays JVM-only until Phase 8 prep. Choosing a native XML parser — a permissive KMP XML library such as pdvrieze `xmlutil` (verify its licence), or a minimal hand-written XMP parser — is deferred and recorded as an open item.

## Rejected alternatives

- **Everything in one big `:core` module.** Faster to set up, but it hides platform leaks and makes native readiness all-or-nothing.
- **`commonMain`-first rewrite of everything now.** Too much risk to behaviour for no Phase 1–7 benefit. Native readiness is only needed by Phase 8.
