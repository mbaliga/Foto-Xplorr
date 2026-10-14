# ADR-011 — Catalogue v2: one database, app-owned asset identity, sources, and a forward-only migration

**Status:** accepted (owner review at the Phase 1 gate)
**Date:** 24 Sep 2026
**Work package:** WP1.3 (master plan §2.2); feeds WP1.4 (sync) and WP1.5 (paging)
**Depends on:** ADR-009 (Room 2.7.x with `BundledSQLiteDriver`), ADR-010 (`:core:db`)

## Context: what exists today

Branch `claude/fylz-fotoz-complete-y60pfw`, after Phase 0. There are seven SQLite files and eight SharedPreferences stores, and all user data is keyed by the MediaStore `_ID` (`MediaId`).

| Store | Kind | Key | Holds |
|---|---|---|---|
| `foto_xplorr_catalogue.db` v3, table `media` | SQLite | MediaStore `_ID` | The catalogue mirror |
| `foto_xplorr_geo.db` v3, `geo_metadata` | SQLite | media_id | Location, manual pins, `checked_with_original`, revision |
| `foto_xplorr_recognition.db` v4 | SQLite | media_id | `asset_recognition`, `face_descriptor` (vectors), text blocks, failures |
| `foto_xplorr_embeddings.db` v2 | SQLite | media_id + model sha | Similarity vectors, failures |
| `foto_xplorr_traits.db` v1 | SQLite | media_id + date_modified | Animated trait |
| `foto_xplorr_moments.db` v2 | SQLite | media_id | Key moments, scanned set, feedback |
| `foto_xplorr_favorites` | Prefs | id set | Favourites |
| `foto_xplorr_sensitive` | Prefs | id set | Sensitive flags |
| `foto_xplorr_library` | Prefs | id sets / maps | Collections, tags, auto-tags, captions, archive, and curation memory (see `LibraryStore` KEY_* constants) |
| `foto_xplorr_private_folders` | Prefs | folder key (`path:<relpath>` / `bucket-id:` / `bucket-name:`) | Locked folders, PBKDF2 salts and hashes |

Other preferences stores (gallery preferences, work rules, AI providers, encrypted secrets, pro, scan watermarks, legacy-migration flag) aren't asset-keyed and **stay as they are**.

Known consequences of the MediaStore-ID design: see the gap-analysis doc §2 and TRAPS #1, #8, #26.

## Decision

### 1. One Room database file for the catalogue and its derived data

The file is `fotoz.db` (Room 2.7.x, `BundledSQLiteDriver`, WAL on). Foreign keys are enabled in the driver's connection setup — the Room equivalent of `onConfigure` (TRAPS #10); verify that Room's KMP builder applies `PRAGMA foreign_keys=ON` per connection, and add a test.

Similarity vectors go in a **second file**, `fotoz-vectors.db`. It's large and rebuildable, it stays out of any catalogue backup, and it's joined by `asset_id` in application code. That file is explicitly without foreign keys.

### 2. Schema v1 of `fotoz.db`

- The complete DDL is authored as Room entities.
- Export schemas (`room { schemaDirectory("$projectDir/schemas") }`) and commit them.
- Table and column names below are binding. Types are SQLite affinities.

```sql
-- Where files live. One row per MediaStore volume in Phase 1; SAF, USB and network sources arrive in Phase 3.
CREATE TABLE source (
  source_id        INTEGER PRIMARY KEY,
  kind             TEXT NOT NULL,          -- MEDIASTORE_VOLUME | SAF_TREE | USB_SAF | MTP_DEVICE | SMB | SFTP | WEBDAV | IMMICH | S3 | LINUX_DIR | UT_CONTENT
  root_locator     TEXT NOT NULL,          -- MediaStore volume name ('external_primary', '<uuid>'); tree URI; host path …
  volume_uuid      TEXT,
  display_name     TEXT NOT NULL,
  state            TEXT NOT NULL,          -- ONLINE | OFFLINE | REVOKED | ERROR
  sync_version     TEXT,                   -- MediaStore.getVersion(volume)
  sync_generation  INTEGER,                -- MediaStore.getGeneration(volume) at the last complete pass
  last_full_scan_ms INTEGER,
  preview_policy   TEXT NOT NULL DEFAULT 'NONE',
  flags            INTEGER NOT NULL DEFAULT 0,
  UNIQUE(kind, root_locator)
);

-- One row per file the app knows about, in any state.
CREATE TABLE asset (
  asset_id         INTEGER PRIMARY KEY,    -- app-owned; never reused (AUTOINCREMENT)
  source_id        INTEGER NOT NULL REFERENCES source(source_id) ON DELETE RESTRICT,
  locator          TEXT NOT NULL,          -- MediaStore _ID as text; document id; path; remote id
  content_uri      TEXT,                   -- cached openable URI (MediaStore content URI)
  display_name     TEXT NOT NULL,
  mime             TEXT NOT NULL,
  format_id        TEXT NOT NULL,          -- from :core:formats (sniffed where possible)
  size_bytes       INTEGER NOT NULL,
  width            INTEGER NOT NULL,
  height           INTEGER NOT NULL,
  orientation      INTEGER NOT NULL DEFAULT 0,
  duration_ms      INTEGER NOT NULL DEFAULT 0,
  date_taken_ms    INTEGER NOT NULL,
  date_modified_ms INTEGER NOT NULL,
  date_added_ms    INTEGER,
  relative_path    TEXT,
  folder_key       TEXT NOT NULL,          -- source-scoped: see §4
  bucket_id        INTEGER,
  bucket_name      TEXT,
  fingerprint      TEXT,                   -- size + xxHash64(first 64 KiB) + xxHash64(last 64 KiB); filled lazily (WP1.4)
  content_hash     TEXT,                   -- full hash, lazy, only when needed (duplicates, backup)
  xmp_document_id  TEXT,
  availability     TEXT NOT NULL,          -- ONLINE | OFFLINE | MISSING_CONFIRMED | UNREADABLE
  trashed          INTEGER NOT NULL DEFAULT 0,
  trashed_at_ms    INTEGER,
  ms_generation_modified INTEGER,          -- MediaStore GENERATION_MODIFIED at the last read
  revision         INTEGER NOT NULL,       -- bumps whenever size/date_modified/content changes; derived-data key
  UNIQUE(source_id, locator)
);
CREATE INDEX asset_timeline   ON asset(date_taken_ms DESC, date_modified_ms DESC, asset_id DESC);
CREATE INDEX asset_folder     ON asset(folder_key, date_taken_ms DESC);
CREATE INDEX asset_fingerprint ON asset(fingerprint);
CREATE INDEX asset_state      ON asset(availability, trashed);

-- The user's own data. Always keyed by asset_id. ON DELETE CASCADE applies only on permanent delete (§6).
CREATE TABLE asset_user (
  asset_id    INTEGER PRIMARY KEY REFERENCES asset(asset_id) ON DELETE CASCADE,
  favorite    INTEGER NOT NULL DEFAULT 0,
  rating      INTEGER NOT NULL DEFAULT 0,  -- 0..5 (Phase 4 UI; column exists now)
  flag        INTEGER NOT NULL DEFAULT 0,  -- -1 reject, 0 none, 1 pick
  color_label INTEGER NOT NULL DEFAULT 0,
  archived    INTEGER NOT NULL DEFAULT 0,
  ever_unarchived INTEGER NOT NULL DEFAULT 0,
  sensitive   INTEGER NOT NULL DEFAULT 0,
  caption     TEXT,
  caption_is_machine INTEGER NOT NULL DEFAULT 0,
  caption_machine_suppressed INTEGER NOT NULL DEFAULT 0,
  archive_suggestion_rejected INTEGER NOT NULL DEFAULT 0,
  sidecar_state TEXT                      -- Phase 3/4: IN_SYNC | DIRTY | CONFLICT
);

CREATE TABLE keyword (
  keyword_id INTEGER PRIMARY KEY,
  parent_id  INTEGER REFERENCES keyword(keyword_id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  UNIQUE(parent_id, name)                 -- today's flat tags become root-level keywords
);
CREATE TABLE asset_keyword (
  asset_id   INTEGER NOT NULL REFERENCES asset(asset_id) ON DELETE CASCADE,
  keyword_id INTEGER NOT NULL REFERENCES keyword(keyword_id) ON DELETE CASCADE,
  origin     TEXT NOT NULL,               -- USER | AUTO
  PRIMARY KEY(asset_id, keyword_id)
);
CREATE TABLE asset_keyword_rejection (   -- "auto-tagger may not re-add this"
  asset_id INTEGER NOT NULL REFERENCES asset(asset_id) ON DELETE CASCADE,
  keyword_id INTEGER NOT NULL REFERENCES keyword(keyword_id) ON DELETE CASCADE,
  PRIMARY KEY(asset_id, keyword_id)
);

CREATE TABLE collection (
  collection_id TEXT PRIMARY KEY,         -- keep today's ids
  name TEXT NOT NULL, created_ms INTEGER NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE collection_member (
  collection_id TEXT NOT NULL REFERENCES collection(collection_id) ON DELETE CASCADE,
  asset_id INTEGER NOT NULL REFERENCES asset(asset_id) ON DELETE CASCADE,
  position INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(collection_id, asset_id)
);

CREATE TABLE folder_lock (                -- replaces the private-folders prefs
  folder_key TEXT PRIMARY KEY,            -- source-scoped key (§4) OR a legacy key (§5.4)
  salt BLOB NOT NULL, hash BLOB NOT NULL, iterations INTEGER NOT NULL,
  legacy_key INTEGER NOT NULL DEFAULT 0
);

-- Derived data. Always keyed (asset_id, revision). Rebuildable. Dropped only on permanent delete.
CREATE TABLE geo (
  asset_id INTEGER PRIMARY KEY REFERENCES asset(asset_id) ON DELETE CASCADE,
  revision INTEGER, has_location INTEGER NOT NULL, latitude REAL, longitude REAL,
  altitude REAL, direction REAL, manual INTEGER NOT NULL DEFAULT 0,
  checked_with_original INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE recognition (
  asset_id INTEGER PRIMARY KEY REFERENCES asset(asset_id) ON DELETE CASCADE,
  revision INTEGER NOT NULL, face_count INTEGER NOT NULL, pet_verdict TEXT NOT NULL,
  identity_verdict TEXT NOT NULL, labels TEXT NOT NULL, categories TEXT NOT NULL,
  caption TEXT NOT NULL, hashtags TEXT NOT NULL
);
CREATE TABLE face (
  asset_id INTEGER NOT NULL REFERENCES asset(asset_id) ON DELETE CASCADE,
  face_index INTEGER NOT NULL, relative_area REAL NOT NULL, vector BLOB NOT NULL,
  PRIMARY KEY(asset_id, face_index)
);
CREATE TABLE text_block (
  asset_id INTEGER NOT NULL REFERENCES asset(asset_id) ON DELETE CASCADE,
  block_index INTEGER NOT NULL, text TEXT NOT NULL,
  left REAL NOT NULL, top REAL NOT NULL, right REAL NOT NULL, bottom REAL NOT NULL,
  PRIMARY KEY(asset_id, block_index)
);
CREATE TABLE trait (
  asset_id INTEGER PRIMARY KEY REFERENCES asset(asset_id) ON DELETE CASCADE,
  revision INTEGER NOT NULL,
  animated INTEGER, hdr_gainmap INTEGER, motion_photo INTEGER, panorama INTEGER,
  depth INTEGER, stereo INTEGER, burst_id TEXT           -- NULL = unknown; only animated is filled in Phase 1
);
CREATE TABLE video_moment (
  asset_id INTEGER NOT NULL REFERENCES asset(asset_id) ON DELETE CASCADE,
  position_ms INTEGER NOT NULL, source TEXT NOT NULL, confidence REAL NOT NULL, label TEXT NOT NULL,
  PRIMARY KEY(asset_id, position_ms)
);
CREATE TABLE video_moment_scan (asset_id INTEGER PRIMARY KEY REFERENCES asset(asset_id) ON DELETE CASCADE, revision INTEGER NOT NULL);
CREATE TABLE video_moment_feedback ( /* carry today's moment_feedback columns, re-keyed to asset_id */ );

-- One failure table for every derived indexer (replaces the per-DB failure tables).
CREATE TABLE index_failure (
  asset_id INTEGER NOT NULL REFERENCES asset(asset_id) ON DELETE CASCADE,
  indexer  TEXT NOT NULL,                 -- GEO | RECOGNITION | EMBEDDING:<model sha> | TRAIT | MOMENTS
  revision INTEGER NOT NULL, attempts INTEGER NOT NULL, last_attempt_ms INTEGER NOT NULL,
  PRIMARY KEY(asset_id, indexer)
);

-- Destructive-operation audit log (WP1.6 writes it; the schema lands here).
CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY, at_ms INTEGER NOT NULL, action TEXT NOT NULL,
  asset_count INTEGER NOT NULL, detail TEXT
);

-- Migration bookkeeping (§5).
CREATE TABLE migration_progress (
  step TEXT PRIMARY KEY, state TEXT NOT NULL, cursor TEXT, updated_ms INTEGER NOT NULL, detail TEXT
);
```

**Full-text search.** An FTS5 table over name, caption, keywords, OCR text and place names is **not** created in WP1.3. It belongs to WP5.1. Record that `BundledSQLiteDriver` gives FTS5 on every platform, which is why Room with the bundled driver was chosen.

**`fotoz-vectors.db`:** `embedding(asset_id INTEGER, model_sha TEXT, revision INTEGER, vector BLOB, signature INTEGER, x REAL, y REAL, PRIMARY KEY(asset_id, model_sha))`, with the same indexes as today.

### 3. Identity

- `asset_id` is app-owned and stable for the life of a catalogue row.
- For MediaStore assets, the natural key is `(source_id of the volume, locator = MediaStore _ID)`.
- The scanner must now read `MediaStore.MediaColumns.VOLUME_NAME` (API 29+). On API 26–28 it uses the single volume `external`.
- A file that moves between volumes (a new MediaStore `_ID`) is matched by `fingerprint` in WP1.4, and its user data is re-attached. That is WP1.4's job; the schema supports it.

### 4. `folder_key` becomes source-scoped

The format is `<source_id>:path:<lowercased normalized relative path>`. Fallbacks are `<source_id>:bucket-id:<id>` and then `<source_id>:bucket-name:<name>`, mirroring today's `gallery/FolderIdentity.kt` precedence. This fixes the cross-volume merge.

The same `DCIM/Camera` on two volumes becomes two albums. The UI shows the volume name on albums only when two albums would otherwise have the same name.

### 5. Migration: forward-only, resumable, verified (TRAPS #15)

The runner is `MigrationToV2` in `:core:db`, with Android glue in `:app`. It runs once, in the background, driven by `LibraryRuntime`, with a progress notification. **The UI keeps working on the old stores until cutover.** Steps are recorded in `migration_progress`, and each is idempotent.

1. **`EXPORT`**
   - Write a pre-migration JSON export of every asset-keyed user store (the favourites, sensitive, library and private-folder preferences, plus manual geo pins) into `filesDir/migration/pre-v2-<timestamp>.json`. Keep it for 90 days.
   - Also **copy** (not move) the seven old DB files into `filesDir/migration/pre-v2/`. The old files stay in place until the cleanup step.
2. **`SOURCES`**: create one `source` row per MediaStore volume seen in the old `media` table and reported by `MediaStore.getExternalVolumeNames()`.
3. **`ASSETS`**: copy every old `media` row to `asset` in batches of 2,000.
   - `locator = old id`; `folder_key` recomputed per §4.
   - `availability = ONLINE` if the row's volume is attached, else `OFFLINE`.
   - `revision` derived from size + date_modified; `format_id` from the classifier.
   - Record the cursor after each batch.
   - Old rows whose volume can't be determined (API 26–28, or null) go to the primary source.
4. **`ID_MAP`**: a temp table `legacy_id_map(media_id PRIMARY KEY, asset_id)`, filled from step 3.
5. **`USER_DATA`**: favourites, sensitive, archive, ever-unarchived, captions (user and machine, suppressed), tags → root keywords (`origin = USER` or `AUTO` per today's auto-tag sets), keyword rejections, archive-suggestion rejections, collections and members.
   - Also fold in MediaStore's own `IS_FAVORITE` (API 30+) as a favourite.
   - Ids with no `legacy_id_map` entry are **kept** in a `legacy_orphans` table, not dropped; the gate report counts them.
6. **`LOCKS`**: every private-folder entry becomes a `folder_lock` row with `legacy_key = 1` under its **old** key. The privacy filter treats a legacy key as matching that relative path on **every** source. That preserves today's behaviour and can't unlock anything.
   - The user can later re-lock per source. The re-lock UI is Phase 4, not Phase 1.
7. **`DERIVED`**: geo, recognition, faces, text, traits, moments (+scanned, +feedback), failures and embeddings, all re-keyed through the ID map.
   - Rows for unmapped ids are dropped; they're rebuildable.
   - Manual geo pins are user data: unmapped ones go to `legacy_orphans` too.
8. **`VERIFY`**. Run all of these and record the results in `migration_progress.detail`:
   - `PRAGMA integrity_check` and `PRAGMA foreign_key_check`, after COMMIT, outside any transaction (TRAPS #11);
   - counts per store, old vs new;
   - **projection parity**: every destination and smart album computed from the old in-memory data vs from `fotoz.db` for the live library, with count and order fingerprint. This is `CatalogueCharacterisationTest`'s `describe()` reused on real data.

   Any mismatch stops the migration before cutover, keeps the old stores authoritative, and surfaces an error card with a "send details" export (local file, no network).
9. **`CUTOVER`**: flip a flag, and `LibraryRuntime` switches every reader and writer to `fotoz.db`. From here the old stores are read-only and never written again: forward-only.
10. **`CLEANUP`**: only on the **second** successful app start after cutover, delete the old DB files and asset-keyed preferences **from their live locations**. The `migration/` copies stay for 90 days.

The user can keep using the app during steps 1–8. Writes made to the old stores during migration are caught by re-running the affected steps once more, right before `VERIFY`: a "catch-up pass" keyed on a `last_write_ms` the old stores stamp from WP1.3 on.

### 6. Delete semantics: owner decision (a), 24 Sep 2026

- **Trash is not deletion.** Trashed assets keep `asset`, `asset_user` and every derived row. They are indexed like any other asset, including geo, recognition, similarity, traits and moments. Only the UI decides what trash views show.
- **Permanent delete ("shred")** is the only event that removes an `asset` row: confirmed system delete, app-trash expiry on SAF/USB, or the owner-confirmed "delete forever". `ON DELETE CASCADE` then removes user and derived data, and one `audit_log` row is written.
- **`MISSING_CONFIRMED`** (WP1.4: a complete enumeration of an online source didn't find the file, and the fingerprint didn't match elsewhere) keeps all rows. They're hidden from normal views and appear in a "Missing" view (Phase 3 UI). They're purged only after 30 days, or on the user's confirmation.
- **`OFFLINE`** assets keep everything, indefinitely.

## Consequences

- Every asset-keyed store in the app is rewired to DAOs over `fotoz.db`: `FavoriteStore`, `SensitiveStore`, `LibraryStore`, `PrivateFolderStore`, `GeoMetadataRepository`, `RecognitionStore`, `EmbeddingRepository`, `AnimationIndex` and `VideoMomentStore`. Their public APIs may stay as facades during Phase 1 to keep the UI diff small; `MediaId` becomes a typealias-compatible wrapper around `asset_id`.
- The in-memory `StateFlow<List<MediaAsset>>` mirror survives WP1.3. WP1.5 removes it.
- The metadata backup JSON (Settings) switches to v2: `asset_id` + fingerprint + (source, locator), never bare MediaStore ids. The importer still accepts v1 files, mapping ids through the current `legacy_id_map`, with a warning that v1 backups only restore correctly on the phone they came from.

## Tests required (JVM unless noted)

- A synthetic 100k old-format fixture (reuse `SyntheticCatalogue`) with favourites, tags, auto-tags, captions, collections, archive, locks, geo, recognition and embeddings. Migrate it, then assert: zero lost user data, orphan accounting correct, projection parity for every destination and smart album, and FK check clean.
- **Kill-and-resume**: interrupt at each step boundary and mid-batch, resume, and get the same end state.
- **Catch-up**: writes to the old stores between `USER_DATA` and `VERIFY` end up in v2.
- **Delete semantics**: trash keeps derived rows; permanent delete cascades; `MISSING_CONFIRMED` keeps rows.
- **Device check (must pass before the Phase 1 merge):** upgrade over the owner's real library and compare counts in Settings → Library info before and after. Every favourite, tag, collection, lock, caption and manual pin must be present.
