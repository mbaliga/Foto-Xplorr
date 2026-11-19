# ADR-020 — Files from other places (SAF folders, USB drives) in the gallery

**Status:** PROPOSED (owner decision needed; nothing built, nothing run on a device)
**Date:** 11 Nov 2026
**Scope:** how a non-MediaStore asset source feeds the gallery without touching the existing library stores. Design detail: `docs/handoff/PHASE-3-DESIGN.md` section 1.

## Context

Phase 3 adds folders and drives as places photos live (WP3.2, 3.3). The gallery today reads one list: `repository.observeAll()` (`FotoXplorrActivity.kt:249`), a MediaStore mirror. Every id-keyed store (Favorite, Sensitive, Library, PrivateFolder, ratings) assumes a MediaStore `_ID`. Owner rule in force: nothing rewrites or migrates the existing stores or the Room catalogue until a device test. `MediaId` already reserves the sign: real ids are >= 0, the open-with ids are negative (`syntheticNegativeMediaId`, range -1 to -2^31), `Long.MIN_VALUE` is `MediaId.EXTERNAL`. Two persisted id codecs drop negative ids (`FavoriteIdCodec.kt`, `LibraryStore.encodeIds` at `organize/LibraryStore.kt:930`), but `FavoriteStore.persist` still holds them in memory until restart, and `SensitiveIdCodec` (`privacy/SensitiveStore.kt:72`) does NOT filter: a sensitive mark on a negative id would be written to the existing preferences file. So the sign alone is not a safety net; the gate in decision 5 is required.

## Options

A. Write source files into the Room catalogue through `SyncEngine` (`core/index/.../SyncEngine.kt:43`). Rejected for now: it adds rows to `fotoz.db`, which the v2 cut-over (WP1.3) owns.
B. Merge them into the MediaStore mirror (`SqliteMediaRepository`). Rejected: the scanner's sweep (`removeAllExcept`) would delete them.
C. A separate provider and a separate SQLite file, merged into the activity's asset list before every filter. Proposed.

## Proposed decision

1. **New file `fotoz-sources.db`** (Room KMP in `:core:db`, package `...core.db.sources`, no new dependency), with its own tables for sources, assets, walk cursors and preview rows. Not `fotoz.db`, not `fotoz-vectors.db`. Rebuildable from the folders themselves; losing it loses no user data.
2. **Id band.** A source asset's `MediaId` is `-(2^33) - n`, where `n` is the store's never-reused row number for that asset. Disjoint from MediaStore ids (>= 0), open-with ids (>= -2^31) and `EXTERNAL`. Stable across restarts, so views, selection and restore-after-rotation keep working. The negative sign makes the Favorite and Library codecs refuse to store it; `SensitiveIdCodec` would not, so `SourceActionGate` must block that write explicitly (and a test pins every `GalleryActions` write callback).
3. **Merge point.** One pure function `mergeSourceAssets(library, source)` applied where `assets` is defined in `FotoXplorrApp`, before `browsableAssets` and before `GalleryUiState`. Consumers that must stay MediaStore-only (recognition, auto-curation, tags/collections cleanup, plain metadata backup, cloud sync feed) keep the unmerged list.
4. **Folder isolation.** A source asset's `relativePath` is `~src/<sourceRow>/<path inside the tree>` so `folderIdentity` (`gallery/FolderIdentity.kt`) never merges a drive's `DCIM/Camera` with the phone's.
5. **Capabilities.** v1 source assets are view, open, share, copy-out, select and search-by-name only. Favourite, rating, flag, sensitive, tag, collection, archive, trash, rename, move, edit-in-place are disabled (rows hidden, not dead) behind one pure `SourceActionGate`. Ratings and flags for them arrive with the owner's "where ratings live" decision.
6. **Overlap.** A tree on a volume MediaStore already indexes hides items whose volume, relative path, name and size match a MediaStore asset, so internal storage is not shown twice.

## Consequences

The existing stores are never written for a source asset. The Room v2 cut-over is unaffected. A later migration can adopt source assets into `fotoz.db` by locator, because the new store keeps the document id. Cost: two writers of "what exists" until WP1.3 lands; the merge function is the single place that knows.

## Owner decision

Accept 1-6, or choose A (single catalogue, after the cut-over is device-tested, and Phase 3 waits).
