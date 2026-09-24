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
| 1.1 KMP build on the pinned toolchain (ADR-009) | todo | | |
| 1.2 Module split (ADR-010) | todo | | |
| 1.3 Catalogue v2 + migration (ADR-011) | todo | | |
| 1.4 Sync engine | todo | | |
| 1.5 Paging and SQL projections | todo | | Includes owner decision 2 |
| 1.6 App architecture (ViewModels, job service, audit log) | todo | | |
| 1.7 Format registry | todo | | |
| 1.8 Test infrastructure | todo | | |
| 1.9 Governance checks in CI | todo | | Includes the ADR-010 `commonMain` import check |
| 1.10 Metadata core (XMP sidecars) | todo | | |

## Module readiness (ADR-010 §3.5)

| Module | Packages moved | Native-ready | Blockers |
|---|---|---|---|

## Decisions

- **WP1.0 / decision 5, migration scope:** read "migrate existing installs only if the user never edited their rules" as scoped per-key, not per-object. `WorkRules` is one object holding several independent settings; a user who customized an unrelated control (e.g. active hours) never made a decision about charging specifically, so `requireCharging` still migrates to the new default for them. Only an install with an explicit prior write to `requireCharging` itself (`SharedPreferences.contains(KEY_REQUIRE_CHARGING)`) is left alone. If the owner intended the coarser "any edit at all freezes every default" reading, this needs revisiting.
- **WP1.0 / decision 1:** no dedicated unit test added for the trivial `assets` → `indexInput` filter removal itself (deleting a `.filterNot { it.isTrashed }` call); covered instead by the existing `SimilarityExplorerScreen`/spatial composition tests continuing to pass unchanged, since none of them asserted on trash exclusion in the first place.
- **WP1.0 / decision 5, test fallout:** flipping `WorkRules.requireCharging`'s default broke 8 pre-existing pure-logic tests (5 in `WorkRuleEvaluatorTest.kt`, 3 in `WorkRulesTest.kt`) that constructed `WorkRules(...)` without setting `requireCharging`, relying on the old `false` default to isolate other rules under test. Fixed by adding `requireCharging = false` explicitly at each affected call site, matching this codebase's stated testing philosophy (sensible defaults, override only what's under test).

## Owner questions

1. **Monetization model for Foto Xplorr** (the watermark is off meanwhile). Options on file:
   - no paid tier;
   - cosmetic Pro (frames and seals);
   - Fonebrew-style one-time unlock for pro tools (RAW develop, network sources, batch conversion, AI packs), with viewing, sharing and organising free forever and the sideload build fully unlocked.

## Device checks added
