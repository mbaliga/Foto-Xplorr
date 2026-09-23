# Foto Xplorr — master plan: the ultimate gallery, Android → Ubuntu Touch → Linux

**Written:** 24 Sep 2026.
**Baseline:** `main` @ `5e59d56`, plus the Phase 0 branch (`claude/fylz-fotoz-complete-y60pfw`, P0-01…P0-08 done).
**Companion files:**
- `docs/handoff/PHASE-0-BRIEF.md` (finish it first)
- the owner's doc "Foto Xplorr — gap analysis: every format + USB drives"

**Kickoff line for the owner to paste into Claude Code:**

> Read `docs/handoff/MASTER-PLAN.md` in full. Finish Phase 0 from `docs/handoff/PHASE-0-BRIEF.md` first, then execute this plan phase by phase, starting at section 0. Work autonomously within a phase; stop at each phase gate as the plan says.

---

## 0. How to execute this plan (for Claude Code)

### 0.1 Shape of the work

- The plan has **phases** (P1 … P9). Each phase has **work packages** (WPs) with IDs like `WP2.3`.
- **Work autonomously inside a phase.**
- **Stop at every phase gate** (end of each phase). There, open the draft PR, write the gate report (0.6), and end the session. The owner runs the device checklist and starts the next phase.
- WPs marked **[ADR]** begin by writing an Architecture Decision Record in `docs/adr/` (`ADR-0NN-<slug>.md`: context, options, decision, consequences). Commit it, then implement it. If the ADR concludes that the plan is wrong, record why in the ADR and follow the ADR. The owner reviews ADRs at the gate.
- WPs marked **[DEVICE]** have acceptance criteria only a phone can prove. Implement them, then add the checks to the phase's device checklist. Never claim them as verified.
- **Branches:** one per phase, `claude/fotoz-p<N>-<slug>`, from `main` after the previous phase merged. If your harness forces a fixed branch name, use it and note it in the progress log. One commit per WP (or per coherent sub-step): `WP<n.m>: <title>`. Open a **draft** PR per phase. Never merge.
- **Progress log:** `docs/handoff/MASTER-PROGRESS.md`.
  - A table: WP, status, SHAs, notes.
  - Sections: **Decisions**, **Owner questions**, **Device checks added**, **Deviations from plan**.
  - Update it in every commit. After a context reset, read it first and resume.

### 0.2 Standing rules (all phases)

All rules in `PHASE-0-BRIEF.md` §0.2 still apply, and `docs/TRAPS.md` binds every change. On top of them:

1. **Licensing.**
   - Allowed: Apache-2.0, MIT, BSD, ISC, zlib, MPL-2.0 (file-level), and public domain.
   - **Allowed only as a separately loaded, replaceable shared library, with its source offer documented:** LGPL, and CDDL (LibRaw).
   - **Never:** GPL/AGPL code or dependencies, and **non-commercial/research model weights**. That rules out Apple MobileCLIP(2), InsightFace/buffalo_l, EdgeFace, Jina CLIP v2, RMBG-1.4/2.0, YOLOv8/11 (AGPL), and any `-NC` Creative Commons licence.
   - Don't copy code from Fossify, lomiri-gallery-app, digiKam, Immich, PhotoPrism, Loupe, gThumb or Shotwell (all GPL/AGPL). Aves is BSD-3: code reuse is allowed with attribution in `NOTICE`.
2. **Dependency governance.**
   - Every new Gradle dependency needs:
     - a row in `docs/DEPENDENCIES.md` (coordinate, version, licence, why, flavours, size, network-capable yes/no);
     - passing offline gates.
   - Every ML model needs a row in `docs/MODEL_LICENSES.md` (name, source URL, weights licence, training-data note, size, SHA-256) **before** it is used.
   - WP1.9 adds a CI check that fails when either file is out of date.
3. **Flavours.**
   - `offline` never gets a network library or INTERNET. Model packs reach it only by sideload/SAF import, never by download.
   - `connect` may download packs and talk to network sources.
   - If an ML Kit / Play-services-bound feature is ever needed, it goes in a third `play` flavour. Log the need; don't create that flavour unasked.
4. **Data safety.**
   - No code path deletes or overwrites a user's original except an explicit, user-confirmed action.
   - Destructive file operations go through a trash (system trash, or the app trash on SAF/USB).
   - Writes use temp → verify → replace.
5. **Every feature ships complete.** This follows `docs/competitor-parity.md` §"Product rule". A feature counts as present only with:
   - empty, error and permission states;
   - reversibility;
   - TalkBack labels;
   - keyboard/mouse support;
   - 100k-item behaviour.

   A placeholder menu item does not count.
6. **Performance budgets are acceptance criteria** (section 2.4). A WP that regresses a budget isn't done.
7. **Toolchain.**
   - Any AGP/Gradle/Kotlin bump moves `hyle-design-system` and `shared-libraries` in lockstep (TRAPS #2/#3).
   - Only WP1.1 does this.
   - Submodule changes go through their own repos with merge commits, never squash.

### 0.3 Environment facts

- The session has no device or emulator. JVM tests, including Robolectric NATIVE graphics, and lint are available. Everything else goes on the device checklist.
- Build hosts are x86_64. Kotlin/Native `linuxArm64` cross-compiles from there (Phase 8).
- The owner's test device is a **RedMagic 11 Pro** (Android 16, 24 GB RAM, 1 TB storage). Set budgets for a mid-range phone, not only this one.

### 0.4 Test strategy (applies everywhere)

- **Pure logic in `commonMain`** gets unit tests: query parsing, projections, rename planner, sync diff, format sniffing, metadata parsing.
- **Android glue** gets Robolectric tests where feasible.
- **Instrumented tests** (`androidTest`) are introduced in WP1.8. They run only on owner devices or an emulator the owner provides. Write them anyway.
- **Format corpus** (WP1.8): `testdata/formats/` with small, licence-clean sample files for every supported format. Each file's source and licence goes in `testdata/formats/SOURCES.md`. Use raw.pixls.us files (CC0) for RAW; skip anything without a clear licence.
- **Characterisation goldens** stay the regression oracle. They change only per the test's own procedure, with the reason in the commit.
- **Performance tests:** the synthetic catalogue at 10k, 100k and 500k (`docs/perf/baseline-jvm.md` method), plus the Macrobenchmark module (`benchmarks/`) for device numbers.

### 0.5 When unsure

- Choose the conservative option: keep data, fail closed, keep the offline guarantee.
- Log it under Decisions and continue.
- Stop early only if a standing rule would have to be broken.

### 0.6 Gate report (end of each phase; also the PR description)

1. WP table: status and SHAs.
2. Budgets: measured vs target.
3. Goldens changed, and why.
4. ADRs written, with one-line summaries.
5. Decisions and owner questions.
6. Device checklist link, with the top-10 checks most likely to fail.
7. The final `./scripts/verify.sh` summary line.

---

## 1. Product definition

### 1.1 North star

> The fastest, most capable and most private gallery on any phone. It opens every image format and every storage location. It finds anything by describing it. It never loses a file or leaks a location. It works fully offline, and the same library travels to Linux phones and desktops.

### 1.2 Principles (tie-breakers when WPs conflict)

1. **Local-first and offline by default.** The network is opt-in, per source, and visible.
2. **The user's files are sacred.** Non-destructive by default, reversible always, verified writes.
3. **Honest capability.** Every format and feature says what it can and can't do on this device, the same way everywhere.
4. **Speed is a feature.** Budgets in 2.4 are product requirements.
5. **Portable metadata.** Ratings, keywords, captions and edits live in standard XMP/sidecars, not only in the app database.
6. **One core, many shells.** Logic lives in the shared Kotlin core. Platform code is thin.

### 1.3 Feature map

The IDs are referenced by WPs. **M** = must-have (parity with the best apps); **D** = differentiator.

| Area | Features | Tier |
|---|---|---|
| Formats | Every platform format; RAW (preview + full develop); JXL, AVIF/HEIC everywhere, TIFF (multi-page), PSD/PSB, EXR/HDR, TGA, QOI, JP2, JXR, DDS, ORA/KRA, ICNS, SVG, APNG, animated WebP/AVIF/HEIF; motion photos, Live Photos, bursts, photo spheres, stereo/spatial | M/D |
| Viewing | Instant viewer; tiled deep zoom to 1:1+; HDR/gain-map display; wide colour; histogram; clipping and focus-peaking overlays; compare/survey with synced zoom; culling mode; slideshow; external display | M/D |
| Preview | Long-press peek; hover preview and hover-scrub video on mouse/DeX; space-bar Quick Look; always-open preview pane (two-pane) | D |
| Browse | Pinch-zoom densities (year/month/day/grid 2–20); justified layout; masonry; accurate date scrubber at 500k; folders; nested albums; select by date header | M |
| Organise | Favourites, 0–5 ratings, flags, colour labels (all to XMP); hierarchical keywords; smart albums (rules); stacks/bursts; events/trips; people (opt-in, on-device); pets; places; screenshots/documents/receipts/IDs; duplicates and near-duplicates with keep-best merge; similar; trash retention | M |
| Select and bulk | Drag-range, shift-range and marquee selection; select all in a group; bulk queue with progress, pause and undo; rule-based rename (Bridge/Figma-class); batch metadata; date/time shift; batch convert/resize/export presets; move/copy with conflict resolution | M/D |
| Search | A: token bar with chips and autocomplete (Finder-style). B: advanced filter builder, saved searches = smart albums. C: natural language (semantic CLIP-family plus a query-to-filters parser); OCR text; colour; "more like this"; camera/lens/ISO | M/D |
| Utilities | Storage dashboard (by type, source and age); cleanup cards (blurry, screenshots, large videos, duplicates, already backed up); Recents (added, opened, edited); by-type browsing (RAW, HEIC, video…); per-source storage panel | M/D |
| Sources | Phone (MediaStore); any SAF folder; SD/USB drives with offline previews; camera import (MTP/PTP) with ingest rules; backup to drive with verification; connect flavour: SMB, SFTP, WebDAV/Nextcloud, Immich, S3; cloud via SAF providers; "Open with", picker, share-receive | M/D |
| Spatial | Places map (connect: vector map; offline: stamp map); compass/orientation view; 3D photo wall; photo spheres (360°) **with ambient audio**; 3D/parallax from depth; stereo/spatial photo viewing | D |
| Editing | Non-destructive pipeline with history and virtual copies; copy/paste edits; crop/perspective/straighten; light/colour/curves/HSL/LUTs; healing; object erase; subject lift → cutout/sticker ("instant alpha"); background replace; masks by segmentation; markup/text; RAW develop; video editor (trim, speed, crop, text, music) | M/D |
| Create and share | Memories and "on this day"; slideshow/video from selection or prompt; collage; share with location stripped (done in P0); shared-album export to folder; cast (connect) | M/D |
| Privacy and security | Encrypted vault (keys in Keystore, biometric); on-device AI only; per-source network indicator; audit log of destructive operations | M/D |
| Platforms | Android phone/tablet/foldable/DeX/TV; Ubuntu Touch (Lomiri/QML); Linux desktop (Flatpak) | M |

### 1.4 Status of the owner's dipstick items (24 Sep 2026) → where they land

| Ask | Today | Plan |
|---|---|---|
| All image types, RAW | Platform formats only; RAW = embedded preview at best | P2 (WP2.1–2.4) |
| Quick preview, autoplay, hover | Long-press peek, grid loop, video autoplay; no hover; viewer doesn't animate | P0 (P0-14), P2 (WP2.9–2.10) |
| Multi-select, bulk move, rule rename | Multi-select and bulk actions exist; rename tokens are basic | P4 (WP4.5–4.8) |
| Three-tier search | Token language only; suggestion UI unwired; no builder; no semantic search | P5 |
| Preview pane, cleanup, recents, by type | Smart albums Recent/Large/Duplicates; Tidy-up archives | P2 (WP2.10), P4 (WP4.10–4.11) |
| Other locations | Phone only | P3 |
| Compass view | Built, reachable via Places; never device-verified; GPS fix pending merge (P0-02) | Verify at the P1 gate; polish in WP2.13 |
| 3D photos | 3D arrangement of flat photos only | WP2.14 |
| Photo spheres with audio | None | WP2.13 |
| Editing, instant alpha, stickers | Basic editor; colour-based Lift and sticker export | P6 |

---

## 2. Target architecture

### 2.1 Modules (after Phase 1)

```text
:core:model        commonMain  — MediaAsset, SourceId, AssetId, Fingerprint, FormatId, Capability, Rating/Label/Flag, Query AST
:core:db           commonMain  — Room KMP (BundledSQLiteDriver), schema, DAOs, migrations, FTS5
:core:index        commonMain  — sync engine: Source interface, diff/mark-and-sweep, availability, jobs
:core:formats      commonMain  — magic-byte sniffer, capability matrix, DecoderProvider interface
:core:metadata     commonMain  — EXIF/XMP/IPTC read (shared Kotlin), XMP sidecar read/write, MetadataStripper (from P0-04)
:core:search       commonMain  — tokenizer/parser (from search/), filter AST → SQL, suggestion engine, NL rules parser
:core:organize     commonMain  — smart-album rules, stacks, duplicates, rename planner (from fileops/), events
:core:ml-api       commonMain  — interfaces: Embedder, FaceEmbedder, Segmenter, Inpainter, QueryParser, OCR; model-pack manifest
:platform:android  androidMain — MediaStore/SAF/USB/MTP sources, Coil decoders, ONNX/LiteRT runtimes, WorkManager-free job service
:app               android     — Compose UI (today's app, slimmed to UI + DI)
:feature:ai-remote android     — existing connect-only module
:ut:bridge         linuxArm64/linuxX64 (Kotlin/Native) — C API over the core (Phase 8)
:ut:app            C++/QML (Qt 5.15, Lomiri UI Toolkit) — Clickable project (Phase 8)
:desktop:app       JVM (Compose Multiplatform Desktop) — Flatpak (Phase 9)
```

The dependency direction is strictly `app → platform → core`. `core` has no Android imports: WP1.9 adds a lint/Gradle check.

### 2.2 Catalogue v2 (WP1.3)

- **`sources`**
  - Columns: `id`, `kind` (`MEDIASTORE_VOLUME`, `SAF_TREE`, `USB_SAF`, `MTP_DEVICE`, `SMB`, `SFTP`, `WEBDAV`, `IMMICH`, `S3`, `LINUX_DIR`, `UT_CONTENT`), `display_name`, `root_locator`, `volume_uuid`, `state` (`ONLINE` / `OFFLINE` / `REVOKED` / `ERROR`), `last_full_scan_ms`, `sync_token` (e.g. MediaStore version + generation), `preview_policy` (`NONE` / `THUMBS` / `THUMBS_PREVIEWS`), `flags`.
- **`assets`**
  - Identity: `asset_id` (app-owned UUID/long), `source_id`, `locator` (MediaStore id, document id, path, remote id), `display_name`, `mime`, `format_id`.
  - Size and dates: `size_bytes`, `width`, `height`, `orientation`, `duration_ms`, `date_taken_ms`, `date_modified_ms`, `date_added_ms`.
  - Folder: `folder_key` (source-scoped, fixes the cross-volume merge), `relative_path`.
  - Matching: `fingerprint` (size + first/last 64 KB xxHash64), `content_hash` (full, lazy), `xmp_document_id`.
  - State: `availability` (`ONLINE` / `OFFLINE` / `MISSING_CONFIRMED` / `UNREADABLE`), `trashed`, `revision`.
- **User metadata** (keyed by `asset_id`, foreign keys ON via `onConfigure`, TRAPS #10–11): `favorite`, `rating`, `flag`, `color_label`, `caption`, `keywords` (hierarchical, `keyword` + `asset_keyword`), `collections`, `archived`, `sensitive`, `hidden_in_vault`, `edits` (recipe JSON + version), `sidecar_state`.
- **Derived tables:**
  - `exif_index` (camera, lens, ISO, aperture, shutter, focal, GPS, altitude, direction);
  - `geo` (from the P0-02 repository);
  - `recognition` (faces, labels, OCR text);
  - `embeddings` (vector BLOB + model id);
  - `traits` (animated, HDR, motion photo, panorama, depth, stereo, burst id);
  - `fts` (FTS5 over name, caption, keywords, OCR text, place names).
- **Migration:** a one-shot migration from the v3 catalogue plus SharePreferences stores, keyed by MediaStore id → `asset_id`. It follows the FX-IMP-002 phases: shadow-write, compare against goldens, cut over, **forward-only** (TRAPS #15), and a resumable `migration_progress` table. The V1 `CatalogStore` data is handled in P0-17.

### 2.3 Sync engine (WP1.4)

- A `Source` interface with `enumerate(since: SyncToken?): Flow<SourceEvent>`, `open(locator)`, `thumbnail(locator, size)` and `capabilities`.
- MediaStore uses **per-volume `getGeneration` / `GENERATION_MODIFIED`** and `getVersion` for resets, not `DATE_MODIFIED`.
- SAF uses a resumable breadth-first `DocumentsContract` walk with a full projection.
- **Mark-and-sweep only over a completely enumerated source** (TRAPS #8). An absent source means `OFFLINE`, never deleted. Partial media access means no sweep.
- Downstream indexers (geo, recognition, embeddings, traits) are jobs over `(asset_id, revision)`. They record failures with bounded retries (the P0-12 pattern) and never treat "unreadable" as "absent".

### 2.4 Performance budgets

Measured on a mid-range phone (the Pixel 7a class) unless noted. The RedMagic numbers must be at least this good.

| Metric | Budget |
|---|---|
| Cold start to first grid frame, 100k library | ≤ 900 ms |
| Grid scroll | No dropped frames at 120 Hz on the RedMagic; ≥ 58 fps mid-range |
| Date-scrubber jump anywhere in 500k | ≤ 100 ms to the correct month |
| Open photo in viewer (JPEG 12 MP) | ≤ 150 ms to a sharp screen-size image |
| Deep zoom to 1:1 tile | ≤ 250 ms |
| Token search, 100k | ≤ 100 ms per keystroke (p95) |
| Semantic search, 100k embeddings | ≤ 400 ms (p95) |
| Delta sync after one new photo | ≤ 1 s to appear |
| Full MediaStore sync, 100k, subsequent launch | 0 full rewrites; only changed rows touched |
| Memory, grid at 100k | No full-library `List` in the UI layer; paged |

---

## 3. Phases and work packages

### Phase 0 — finish the defect brief (in progress)

Complete P0-09 … P0-19 from `PHASE-0-BRIEF.md`, open its PR, and stop at its gate. **Nothing below starts until the owner merges Phase 0.**

---

### Phase 1 — Foundation: shared core, catalogue v2, sync, paging

**Goal:** one catalogue that can hold any source, sync correctly, page at 500k, and compile for Android and (headless) Linux.

| WP | Title | Content | Acceptance |
|---|---|---|---|
| 1.1 [ADR] | Toolchain upgrade in lockstep | Kotlin (to a version Room KMP 2.8 and Compose MP support), AGP, Gradle, Compose BOM, and the removal of the `kotlin-stdlib` force-pin in `app/build.gradle.kts`, across Foto-Xplorr, hyle-design-system and shared-libraries. Submodule PRs are merge commits; bump the pins after they merge (TRAPS #2). | All three repos green; offline gates green; goldens unchanged |
| 1.2 [ADR] | Module split to KMP | Create the `:core:*` modules from 2.1. Move the pure code: search/, fileops/ planners, formats/, metadata/XmpPacket + MetadataStripper, gallery projections, ScanPlan → `commonMain`. Add `linuxX64` + `linuxArm64` targets **now**, headless, compiled in CI, so native-incompatible dependencies fail early. | `./gradlew :core:search:linuxX64Test` passes; the Android app is unchanged behaviourally; goldens unchanged |
| 1.3 [ADR] | Catalogue v2 schema + migration | Room KMP with `BundledSQLiteDriver` (same SQLite and FTS5 everywhere). Schema per 2.2. Migration shadow → verify → cut over, resumable. Stores move off SharedPreferences (Favorite, Sensitive, Library, PrivateFolder), with an `integrity_check` + `foreign_key_check` after COMMIT (TRAPS #11). | Migration test on a synthetic 100k catalogue with favourites, tags and collections: 0 lost; goldens identical through the new DB; kill-mid-migration resume test |
| 1.4 | Sync engine | Per 2.3. MediaStore source with generation sync per volume; availability states; indexer job framework with failure bookkeeping. Replaces `MediaIndexer`/`ScanPlan` behaviour while keeping TRAPS #1/#8 as tests. | Unplug-simulation test: an SD volume's rows go OFFLINE, not deleted; mtime-preserved files are picked up by the delta; partial access never sweeps |
| 1.5 | Paging and projections in SQL | Replace full materialisation: Paging 3 (or a windowed Flow) over Room; smart albums, destinations and sorts as SQL/views; `GridIndexMap` (from P0-15) generalised for headers, stacks and sections. | 500k synthetic: budgets 2.4 met on the JVM baseline (≤ 10% of the table), no `List<MediaAsset>` of the full library in UI code (grep check in CI) |
| 1.6 | App architecture | ViewModels + `SavedStateHandle` per screen. `LibraryRuntime` (P0-09) becomes the DI root. Foreground `JobService`/`Service` for long operations with notification, pause, resume and cancel. An audit log table for destructive operations. | Rotation/process-death tests; a long copy survives leaving the app |
| 1.7 | Format registry | `:core:formats`: magic-byte sniffing for 40+ formats (section 4), a `Capability` matrix, and a `DecoderProvider` SPI. Android provider = platform + packs (P2). The grid draws an honest placeholder with a format badge for anything undecodable. | Sniffer tests over the corpus; a black tile is impossible (Robolectric render test) |
| 1.8 | Test infrastructure | `androidTest` source set; format corpus + `SOURCES.md`; Macrobenchmark wired behind a Gradle property; perf baseline regenerated at 500k. | Instrumented tests compile; the corpus is licence-audited |
| 1.9 | Governance checks in CI | `DEPENDENCIES.md` + `MODEL_LICENSES.md` checks; "no Android import in core" check; licence scan of the resolved classpath (fails on GPL/AGPL); 16 KB page-alignment check for `.so` files (TRAPS #16). | CI fails on a deliberately bad test dependency (then remove it) |
| 1.10 | Metadata core | Shared-Kotlin EXIF/XMP/IPTC reader (TIFF-IFD + XMP parser in commonMain). XMP sidecar read/write (`IMG.CR3.xmp` darktable style and `IMG.xmp` Lightroom style; write the configured style). Ratings, labels, keywords, caption and flag round-trip to XMP. Android keeps ExifInterface where it's better, behind the interface. | Round-trip tests against Lightroom- and darktable-style sidecars in the corpus |

**Gate P1:** the owner verifies on device:
- upgrade from the current app with no lost favourites, tags, collections, locks or recognition;
- 100k scroll and scrub budgets;
- the compass view and Places now show GPS (P0-02 merged).

---

### Phase 2 — Viewing, formats, previews, spatial

| WP | Title | Content | Acceptance |
|---|---|---|---|
| 2.1 | Platform format completion | ImageDecoder paths for HEIF (28+), AVIF (31+), HEIF sequences (30+), animated AVIF; Ultra HDR gain maps (34+); DNG via the platform. `DecoderProvider` reports by probing (`ImageDecoder.isMimeTypeSupported` where available). | Corpus decode tests on device [DEVICE] |
| 2.2 | RAW tier 1: previews for all RAW | Bundle **piex** (Apache-2.0, NDK) to extract the largest embedded preview for ARW, CR2, CR3, DNG, NEF, NRW, ORF, PEF, RAF, RW2, SRW; fallback ExifInterface thumbnail. Coil `Fetcher`/`Decoder` for RAW previews; the viewer offers "Preview / Develop". Extend `RawVariant` (3FR, IIQ, ERF, MRW, X3F, DCR, KDC, MEF, MOS, RWL, SR2, SRF, GPR, CRW). | Every RAW in the corpus shows a preview; grid thumbnails ≤ 60 ms each on device [DEVICE] |
| 2.3 | Decoder packs | Each pack is a Gradle module and a runtime `DecoderProvider`, loaded only if present:<br>• **JXL**: `io.github.awxkee:jxl-coder` + `jxl-coder-coil` (Apache-2.0/BSD-3).<br>• **AVIF/HEIC backport**: `io.github.awxkee:avif-coder` **3.x** + `avif-coder-coil`. **Never 2.x** (GPL x265, LGPL libheif).<br>• **TIFF** multi-page: Android-TiffBitmapFactory (MIT) or TiffRenderer (Apache-2.0).<br>• **stb_image** NDK module: PSD composite, TGA, HDR, PNM, PIC.<br>• **tinyexr** (EXR, tone-mapped), **OpenJPEG** (JP2), **jxrlib** (JXR), **qoi.h**, **dds-ktx + bcdec**.<br>• **ORA/KRA** via `mergedimage.png`; **ICNS/PCX** in Kotlin.<br>• **APNG** via APNG4Android (Apache-2.0).<br>• **XCF**: honest placeholder with the embedded thumbnail if present. | Every corpus format decodes or shows its documented placeholder; `DEPENDENCIES.md` updated; APK size delta recorded per pack |
| 2.4 [ADR] | RAW tier 2: full develop | LibRaw 0.22 (CDDL-1.0 option), as a separate `.so` with a source offer, in a `raw-develop` pack. Demosaic → linear → the edit pipeline (P6). Decide in the ADR: build LibRaw ourselves vs the AndroidLibRaw wrapper; memory strategy (tiles, half-size preview). | CR3/NEF/ARW develop to full resolution without OOM on device [DEVICE] |
| 2.5 | Discovery beyond MediaStore | Magic-byte identification for files MediaStore labels `NONE` (CR3, EXR, TGA, QOI…) when they come from SAF sources (P3) or Linux dirs. MediaStore-only users see an explainer card: "Some formats are only found in folders you add". | Test: a SAF-sourced CR3 is catalogued |
| 2.6 | Tiled deep zoom viewer | Region decoding (`BitmapRegionDecoder`, or pack decoders' region APIs), a tile cache, and a max zoom tied to native pixels (≥ 2× 1:1). Evaluate Telephoto (licence and dependencies first) vs in-house; ADR if adopted. Double-tap cycles fit → 1:1 → fit. Minimap already exists. | 100 MP image pans at 60 fps; 1:1 sharp [DEVICE] |
| 2.7 | HDR and colour | `COLOR_MODE_HDR` for gain-map images; `COLOR_MODE_WIDE_COLOR` for P3; `setDesiredHdrHeadroom` (35+); "ULTRA HDR" badge from XMP `hdrgm` (P0-16). Histogram + clipping overlay + focus peaking in the viewer's pro overlay. | Ultra HDR photo visibly brighter highlights on HDR screens [DEVICE] |
| 2.8 | Composite media | Motion photos (Google XMP Container + legacy MicroVideo + Samsung SEF): press/hover to play, export still or video. Live Photo pairing (HEIC+MOV by ContentIdentifier). Burst detection (BurstUUID, naming, time) → stacks (via `GridIndexMap`). Stereo MPO and spatial HEIC: side-by-side / cross-eye / anaglyph / wiggle modes. | Corpus motion photo plays; burst stacks collapse and expand |
| 2.9 | Animation and video playback | Viewer animation controls (play/pause/step/speed). **Media3 ExoPlayer** replaces `VideoView`: subtitles (embedded + sidecar SRT/VTT/ASS), audio track choice, speed, frame step, loop, HDR, AVI/FLV/Ogg. Check its POM for offline gates (no OkHttp; `DefaultHttpDataSource` unused in offline). Reuse the unmerged `claude/fotoz-continue` Media3 work where it fits. | Offline gates green; MKV with subtitles plays [DEVICE] |
| 2.10 | Quick preview everywhere | Long-press peek (exists; add actions); **mouse hover** on the grid: enlarge overlay after 400 ms, **hover-scrub video** (x-position → frame, cached keyframe strip); **space-bar Quick Look** with arrow keys; **preview pane**: an always-open two-pane layout option (grid + large preview + info) on ≥ 600 dp, DeX and tablets, with a toggle in the pill and `P` shortcut. | Hover and space bar work with a Bluetooth mouse/keyboard; two-pane on a foldable/DeX [DEVICE] |
| 2.11 | Browse layouts | Pinch across levels: year → month → day → grid 2–20 columns. Justified layout. Masonry keeps its scrubber via `GridIndexMap`. Select-all in a date header. | 500k budgets hold in every layout |
| 2.12 | Compare, survey, culling | Compare (2-up, synced zoom/pan), survey (N-up), culling mode (single-key P/X/U flags, 0–5, 6–9 colour labels, auto-advance), filmstrip, loupe. All keyboard-driven. | 500 photos culled with the keyboard only [DEVICE] |
| 2.13 | Photo spheres + ambient audio; compass polish | 360° viewer (GLES equirectangular sphere; GPano `CroppedArea*`/`FullPano*`; gyro look-around; 360° video). **Sphere audio:** attach an audio file (record or pick) to a sphere as a sidecar `<name>.fotoz-audio.m4a` plus an XMP pointer (`fotoz:AmbientAudio`). Play it looped, optionally spatialised by yaw (simple stereo pan). Import Google "Sound & Shot"-style embedded audio if present (investigate; ADR if a format exists). Compass view: verify with P0-02 data, heading confidence, a "measured vs derived" legend. | A sphere with audio plays in sync with look-around [DEVICE] |
| 2.14 | 3D photos | View: depth-map JPEGs (Google Dynamic Depth / GDepth XMP), portrait depth, stereo → parallax with gyro tilt. **Create:** "Make 3D" from any 2D photo with a monocular depth model. **Depth Anything V2 Small is Apache-2.0; its larger variants are non-commercial — Small only; verify and register in `MODEL_LICENSES.md`.** Onnx/LiteRT; export as a short parallax video or a depth JPEG. | Depth photo parallax on tilt; 2D → 3D in ≤ 3 s on device [DEVICE] |
| 2.15 | Slideshow and external display | Slideshow v2 (transitions, music from the audio library, Ken Burns); cast to a second display (Presentation API) and DeX. The connect flavour gets DLNA (UPnPCast, MIT) and Chromecast via CASTV2 (verify the licence) in WP3.12. | Slideshow on an HDMI display [DEVICE] |

**Gate P2:** a device format run over the whole corpus; the viewer and preview budgets.

---

### Phase 3 — Every storage location

| WP | Title | Content | Acceptance |
|---|---|---|---|
| 3.1 | "Open with", picker, share-receive | P0-18 plus `GET_CONTENT`/`PICK` (browsable items only) and `SEND`/`SEND_MULTIPLE` → "Save to library" (copy into `Pictures/Foto Xplorr/`) or "View". | Files app on USB → opens; another app picks from Foto Xplorr [DEVICE] |
| 3.2 | SAF folder sources | Add any folder as a source (`OPEN_DOCUMENT_TREE`, persisted grant). Fast indexer: one `DocumentsContract` child query per directory with a full projection, breadth-first, resumable, `.nomedia` / ignore rules, magic-byte sniffing (WP2.5). | 50k-file folder indexes resumably; revoked grant → state `REVOKED`, nothing deleted |
| 3.3 | Drive lifecycle | Detect drives: `StorageManager` + `registerStorageVolumeCallback`, `USB_DEVICE_ATTACHED/DETACHED`, ExternalStorageProvider root observer. Identify by FS UUID. A "Drive connected — add to library?" card → `createOpenDocumentTreeIntent`. Per-drive settings: include/exclude, preview policy, index on connect. | Plug and unplug cycles never delete; the same drive is recognised after a replug [DEVICE] |
| 3.4 | Offline previews | A persistent per-source thumbnail/preview store (outside Coil's LRU): 256 px WebP thumbnails, optional screen-size previews; storage cost shown per drive. Offline assets stay browsable, greyed, with "Plug in *drive*". | Unplugged drive browsable; its size shown in the storage panel [DEVICE] |
| 3.5 | File operations on any source | A `FileOps` engine in core with per-source implementations:<br>• copy (stream);<br>• move (native within source; copy + verify + delete across);<br>• rename;<br>• delete to trash (system trash on MediaStore; `.fotoxplorr-trash/` + manifest on SAF/USB, 30-day expiry, restore).<br>Checksum verify, temp → rename, resume after an unplug, conflict dialog (skip, replace, keep both, compare side-by-side, apply to all), and results written next to the original. | Pull the USB drive mid-copy → no partial files left; resume works [DEVICE] |
| 3.6 | Camera and card import (ingest) | MTP/PTP via `android.mtp.MtpDevice` (the AOSP Gallery2 `ingest` package, Apache-2.0, is a reference) plus SD/USB cards. Ingest rules: destination template (`{yyyy}/{yyyy-MM-dd}`), rename rule (WP4.6), metadata template (WP4.7), skip duplicates by fingerprint, **optional second destination** (backup-on-ingest), verify, then "delete from card after verify". | Import 500 photos from a camera with a second copy on USB [DEVICE] |
| 3.7 | Back up to drive | An incremental mirror of originals + XMP sidecars + `fotoxplorr-manifest.json` (fingerprints, hashes, catalogue metadata keyed by fingerprint). Verification report; restore/merge from a manifest on another device. Replaces the MediaStore-ID JSON backup. | A restore on a fresh install re-attaches ratings and keywords by fingerprint |
| 3.8 | Annotations travel | Write-through of ratings, labels, keywords and captions to sidecars (per-source policy: embed when the format is writable and the user allows it, else sidecar). Read foreign sidecars (Lightroom, darktable, digiKam) on index. | Round-trip with a Lightroom-exported folder |
| 3.9 | Network sources (connect flavour only) | Plug-ins behind the `Source` interface:<br>• **SMB**: smbj (Apache-2.0).<br>• **SFTP**: sshj (Apache-2.0).<br>• **WebDAV/Nextcloud**: a small PROPFIND client on the connect module's OkHttp, plus Nextcloud OCS where needed.<br>• **Immich**: REST via a client generated from its OpenAPI spec. No Immich code.<br>• **S3**: SigV4 minimal client or MinIO SDK (Apache-2.0).<br>Credentials in `EncryptedSecretStore`. Offline previews per source. Background sync on Wi-Fi with the existing `WorkRules`. | Offline classpath gate still green; each source indexes a 5k-photo test share [DEVICE] |
| 3.10 | Cloud via SAF providers | Google Drive / OneDrive / Dropbox through their DocumentsProviders (no SDKs). Tree sources where the provider supports them; else "import selected". Document provider differences in the UI. | OneDrive folder indexes as a source [DEVICE] |
| 3.11 | Storage panel | Per source: online/offline, file counts, bytes, preview cache size, last sync, errors, "protected on N places" (from the backup manifest; Mylio-style). | — |
| 3.12 | Cast (connect) | DLNA via UPnPCast (MIT) + a small local HTTP server (Ktor CIO or NanoHTTPD); Chromecast via CASTV2 without GMS (verify the library licence first). | Photo and video cast to a DLNA TV [DEVICE] |
| 3.13 | Optional: All-files build | For IzzyOnDroid/F-Droid only: a flavour dimension adding `MANAGE_EXTERNAL_STORAGE` for direct file walks and USB `/mnt/media_rw` access. Play must never get it. ADR first; do it only if the owner approves at the gate. | — |

**Gate P3:** a USB drive with 20k mixed files: index, unplug, browse offline, replug, bulk-copy with a yank test; camera ingest.

---

### Phase 4 — Organise, select, bulk, utilities

| WP | Title | Content |
|---|---|---|
| 4.1 | Ratings, flags, colour labels | 0–5 stars, pick/reject flags, 5 colour labels. Keyboard shortcuts (culling mode). Written to XMP (WP3.8). Filterable everywhere. |
| 4.2 | Hierarchical keywords | `Places|India|Hyderabad` style; synonyms; import/export Lightroom keyword lists; the auto-tagger writes into a separate "suggested" namespace. |
| 4.3 | Smart albums v2 | Rule builder (any field × operator × value, AND/OR groups, nested), live counts, pinned to the rail. Saved searches (P5) are smart albums. |
| 4.4 | Stacks, events, trips | Bursts and RAW+JPEG pairs stack (user can stack/unstack). Events by time gaps + place; trips by distance from home (home inferred on-device, user-confirmable). |
| 4.5 | Selection model | Drag-to-select range (touch), shift-click range, Ctrl-click toggle, marquee (mouse), select-all-in-group, invert, select-by-filter. Selection survives scrolling at 500k (id sets, not index lists) and rotation. |
| 4.6 | Rule-based rename (Bridge + Figma class) | A rule stack with live preview on the actual selection:<br>• Text; Current name (with case transforms);<br>• Sequence number (start, step, padding, ascending/descending);<br>• Sequence letter;<br>• Date/time from EXIF or file (any pattern);<br>• Metadata fields (camera, lens, ISO, rating, keyword, place, person);<br>• Find & replace, plain or **regex** with `$1…$n`, `$&`, `` $` ``, `$'` (Figma);<br>• Change extension case; keep the original name in XMP (`xmpMM:PreservedFileName`, Bridge).<br>Presets. Conflict handling. Undo (the reverse plan is stored). Extends `fileops/RenamePattern`/`BulkRenamePlanner`. |
| 4.7 | Batch metadata | Stamp caption/creator/copyright/keywords/location onto a selection with variables (Photo Mechanic-style templates); date/time shift (offset or set, timezone fix, per-camera); location from a GPX track (on-device). |
| 4.8 | Batch convert and export | Presets: format (JPEG/PNG/WebP/HEIC/AVIF/JXL), resize (long edge, MP, percent), quality, metadata policy (keep/strip location/strip all), rename rule, watermark/frame, colour space, output source. Queue with progress; runs in the job service. |
| 4.9 | Bulk operation queue | One queue UI for copy/move/rename/convert/metadata/delete: progress, pause, cancel, per-item errors, retry failed, undo where possible; notification with actions. |
| 4.10 | Storage utilities | Dashboard by type, source, age and size. Cleanup cards: duplicates (exact by hash, near by pHash + embedding, **keep best** using sharpness/aesthetic/resolution **and merge metadata** into the keeper), blurry (Laplacian), screenshots older than N, large videos (with a compress option via the transcoder), "already backed up" (manifest-verified), unsupported/corrupt files. Everything → trash, with a summary of space reclaimed. |
| 4.11 | Recents and by-type | Recently added, opened, edited and shared (local history table, never leaves the device); by-type browsing (RAW, HEIC, JXL, video codec, animated, panorama, depth, motion) from `traits` + `format_id`. |
| 4.12 | People and pets (opt-in) | On-device **YuNet (Apache-2.0) + SFace (Apache-2.0)** on ONNX Runtime. Note the SFace training-data question in `MODEL_LICENSES.md`. Incremental DBSCAN on cosine; merge/split/name/hide; the face data is biometric, so it's off by default with a clear consent screen and a "delete all face data" button. Pets via the existing labeler + embedding clusters. |
| 4.13 | Documents and screenshots | Categories: receipts, IDs, notes, whiteboards, screenshots (ML labels + OCR + layout heuristics). Multilingual; replace the English-only screenshot substring. |

**Gate P4:** bulk rename 1,000 files with regex and undo it; the duplicate cleanup keeps the best copy and merges metadata; the people opt-in flow on device.

---

### Phase 5 — Search (three tiers)

| WP | Title | Content |
|---|---|---|
| 5.1 | Search index | FTS5 over name, caption, keywords, OCR text, place names, people names and camera/lens (from `exif_index`). A query AST (`:core:search`) → SQL compiler. |
| 5.2 | Tier A: token bar | Wire the existing `SearchQueryChips`/`SearchSuggestions` (unused today). As you type: suggestions grouped by kind (tag, person, place, album, type, camera, date phrase, "contains text", "exclude…"); `Tab`/`Enter` turns a suggestion into a chip; chips are editable, negatable and removable; recent searches. Works with the keyboard alone. Finder/Spotlight feel. |
| 5.3 | Tier B: advanced builder | A "Filters" panel shows every field up front: date range, type/format, source, folder, rating/flag/label, people, places (map lasso), camera/lens/ISO/aperture/shutter/focal, size/dimensions/orientation, traits (HDR, motion, panorama, depth, animated), colour. Conditions with AND/OR/NOT groups; live count. **Save as smart album.** The builder and the token bar are two views of the same AST: edits in one show in the other. |
| 5.4 | Tier C: natural language | Three layers, all on-device:<br>1. **Rules parser**: dates, places (gazetteer + library places), people names, types and numbers → AST; handles "photos of Riya at the beach last summer without screenshots".<br>2. **Semantic retrieval**: **SigLIP2 base-patch16-256 (Apache-2.0)** image embeddings indexed in the background (the existing `WorkRules`), text tower at query time; brute-force cosine over a memory-mapped fp16 matrix (≤ 400 ms at 100k); the rules-AST filters are applied first. Fallback model OpenCLIP ViT-B/32 (MIT) for smaller packs. **Never MobileCLIP (research-only weights).**<br>3. **Optional LLM query parser pack**: Qwen3-0.6B or Gemma 4 E2B (Apache-2.0; verify the licence file) on **LiteRT-LM**, constrained JSON → AST, for complex phrasing. Packs are imported (offline) or downloaded (connect).<br>The existing MediaPipe embedder/similarity path migrates onto this. |
| 5.5 | OCR everywhere | Multilingual OCR: bundled ML Kit v2 scripts (Latin, Chinese, Devanagari, Japanese, Korean) in the current flavours, with **Tesseract (Apache-2.0) as the FOSS path** for an F-Droid build. Live Text search + copy (exists) over all scripts. |
| 5.6 | Colour and similar | Colour search (dominant palettes, already extracted by `PaletteExtractor`, → a hue/saturation index; colour-picker chip); "More like this" (embedding kNN); "Same place", "Same day" and "Same person" quick filters from the viewer. |
| 5.7 | Search quality harness | A labelled on-device test set (the owner's consented sample plus the synthetic corpus) with precision@10 for semantic queries; latency budgets in CI (JVM) and Macrobenchmark (device). |

**Gate P5:** 30 scripted queries across the three tiers on the owner's library; latency budgets.

---

### Phase 6 — Editing and creation

| WP | Title | Content |
|---|---|---|
| 6.1 [ADR] | Edit pipeline v2 | GPU pipeline (AGSL RuntimeShader on API 33+, GLES fallback) over the non-destructive `EditRecipe`; full-resolution tiled export; 16-bit/linear path for RAW and HDR; gain map preserved; history, snapshots, virtual copies; copy/paste and sync edits across a selection; the recipe stored in the DB + an XMP sidecar (`fotoz:EditRecipe`). |
| 6.2 | Geometry | Crop (free drag + presets), straighten, perspective/keystone (4-point + auto upright from line detection), lens-distortion profile hooks. |
| 6.3 | Tone and colour | HSL/colour mixer, split-toning, LUT import (`.cube`, 3D LUT in shader), vignette, grain, clarity/texture/dehaze, white balance picker. Filter presets. |
| 6.4 | Healing and erase | Merge and upgrade spot healing from `claude/fotoz-continue`; object erase with **MI-GAN (MIT)** inpainting (LaMa Apache-2.0 as the high-quality option in a pack); brush + tap-to-select. |
| 6.5 | Instant alpha / subject lift v2 | Replace flood-fill Lift with learned segmentation: **BiRefNet-lite (MIT) or U²-Netp (Apache-2.0)** for automatic subject cutout; **EfficientSAM-Ti / MobileSAM (Apache-2.0)** or MediaPipe Interactive Segmenter for tap-to-select. Outputs: sticker (WebP/PNG, the existing exporter), cutout layer, background replace/blur, masks for local adjustments. **Never RMBG (non-commercial).** |
| 6.6 | Sticker maker | Cutout → outline/shadow styles, animated sticker from a motion photo or video segment (animated WebP), share to messaging apps (image/webp), a sticker library album. |
| 6.7 | Markup and text | Pen/highlighter/shapes/arrows/text/blur-redact; editable layers until flattened on export. |
| 6.8 | Portrait and depth | Adjustable background blur using the depth model (WP2.14) or segmentation; focus-point change for depth photos. |
| 6.9 | RAW develop UI | Uses WP2.4: profile, exposure, highlights/shadows recovery, noise reduction (simple), sharpening. |
| 6.10 | Video editor v2 | Move to Media3 Transformer where it beats the native pipeline (check against ADR-008 and decide in an ADR): trim/split/merge, crop/rotate, speed with pitch-preserve option, text overlays, music track, filters (shared LUT shader), HDR→SDR tone map, export presets. |
| 6.11 | Memories and creations | "On this day", trips, people highlights (on-device); slideshow/video from a selection or a prompt (the rules parser + semantic search picks the photos); collage templates; export as video. |
| 6.12 | AI edit assistant (optional pack) | "Remove the glare", "make it warmer" → recipe operations via the LLM pack (P5) mapping to deterministic tools only. No generative pixels unless a permissive model is approved in an ADR. |

**Gate P6:** an edit round-trip preserves metadata and gain maps; erase/lift quality review by the owner [DEVICE].

---

### Phase 7 — Privacy, accessibility, polish, release

| WP | Title | Content |
|---|---|---|
| 7.1 [ADR] | Encrypted vault | Real encryption (not the current gate): files moved into app-private storage encrypted with AES-GCM streaming (Tink, Apache-2.0; check it pulls no network deps) with keys in Android Keystore; biometric + PIN; decoy/panic option; export out of the vault; honest copy about what's protected. |
| 7.2 | Privacy surfaces | A per-source network indicator; a "what left this device" log (shares, casts, remote AI); remote-AI egress prompt per request (connect). |
| 7.3 | Accessibility | TalkBack order and labels everywhere, AI alt-text (on-device caption model; the existing `CaptionGenerator` upgraded), large text, reduced motion, high contrast; the colour-only states rule (TRAPS #14). An automated a11y check in render tests. |
| 7.4 | Internationalisation | Extract strings; RTL; locale-aware dates and numbers (fix the `DefaultLocale` lint suppression); at least EN + HI + one RTL language to prove the pipeline. |
| 7.5 | Form factors | Tablet/foldable layouts (two-pane default), DeX/desktop windowing, keyboard shortcut sheet (`?`), drag-and-drop in and out (content URIs), Android TV/screensaver mode, widgets (memories, a random favourite). |
| 7.6 | Performance and power pass | Macrobenchmark budgets on the owner device + a mid-range device; background indexing respects `WorkRules` (charging/idle defaults per owner decision); a thermal-aware ML scheduler. |
| 7.7 | Release engineering | A real upload key in CI secrets; reproducible builds; F-Droid/IzzyOnDroid metadata (the `claude/default-store-listings-u991hc` branch has a start); Play flavour decision (ML Kit, All-files); a changelog; crash-recovery wiring checked. |

**Gate P7:** release candidate on the owner's device and one other Android device.

---

### Phase 8 — Ubuntu Touch (Lomiri, Qt 5.15 QML)

Precondition: P1's KMP core compiles for `linuxArm64`/`linuxX64` headless (WP1.2), and P7 is released.

| WP | Title | Content |
|---|---|---|
| 8.1 [ADR] | UT architecture | Kotlin/Native `linuxArm64` shared library `libfotozcore.so` with a **coarse C API**: `library_open`, `sync_start(source, callback)`, `query_page(ast_json, cursor) → json/flatbuffer`, `asset_detail`, `set_metadata`, `ops_*`, `capabilities`. Opaque handles and byte buffers only; events through C callbacks marshalled onto the Qt thread by a C++ bridge (`QObject` + `QAbstractListModel`). Room KMP with `BundledSQLiteDriver` on linux. Note K/N `linuxArm64` is Tier 2 (upstream tests don't run): our own device test plan must cover it. |
| 8.2 | Build and packaging | Clickable project (`ut/`), framework `ubuntu-touch-24.04-2.x`, arm64 (+ amd64 for desktop testing), CMake builder linking the K/N `.so`; CI job cross-compiles on x86_64; OpenStore metadata. |
| 8.3 | Confinement and sources | AppArmor policy groups: `content_exchange`, `content_exchange_source`, `picture_files_read`/`video_files_read` (**reserved: requires OpenStore review**; include a justification text), `networking` only for a connect build. `LINUX_DIR` source for `~/Pictures` and `~/Videos` with inotify; **Content Hub** import (pictures/videos) and registration as a **source** (other apps pick from Foto Xplorr) and **share** target. SD/USB only if review allows (else Content Hub import). |
| 8.4 | QML UI | Lomiri UI Toolkit (Qt 5.15): grid (`GridView` over the bridge model), thumbnails via `lomiri-thumbnailer` `image://thumbnailer/`, viewer with pinch zoom, date scrubber, info panel, search bar (tier A/B via the shared AST), selection + bulk ops, albums/smart albums, settings. The rooms/edge-gesture model adapted to Lomiri conventions (bottom edge = Lomiri's). The Hyle look via QML styling. |
| 8.5 | Decoding on UT | QImageReader (JPEG/PNG/WebP/GIF/TIFF where the plugin exists), KImageFormats if in the framework, else bundled **libheif (LGPL, dynamic) + libde265** for HEIC/AVIF (no x265), libjxl (BSD) for JXL, piex for RAW previews. Report through the shared `Capability` model. |
| 8.6 | Feature scope for UT v1 | Browse, view, search A+B, organise (ratings/keywords/albums), bulk rename/move within allowed dirs, share via Content Hub, metadata sidecars. Defer: editing beyond crop/rotate, ML (evaluate ONNX Runtime aarch64 in v2), network sources (v2). |
| 8.7 | Qt6 readiness | Keep QML Qt6-compatible where cheap; plan the port for UT 26.04 (Qt6 app API stability target). |

**Gate P8:** runs on a real UT device (the owner supplies one, e.g. a Pixel 3a / Volla); OpenStore review submitted.

---

### Phase 9 — Linux desktop

| WP | Title | Content |
|---|---|---|
| 9.1 [ADR] | Desktop shell choice confirmation | Default: **Compose Multiplatform Desktop (JVM)** reusing the Android Compose UI and the JVM core. Alternative: the P8 Qt/QML shell on Qt6 if it proves better. Decide with a spike measuring memory, startup and Wayland behaviour (Compose runs via XWayland; HiDPI quirks). |
| 9.2 | Sources and integration | `LINUX_DIR` sources via the **XDG FileChooser portal** (folder selection) and the **Documents portal** for persistent access (call portals over D-Bus; AWT dialogs aren't portal-aware); inotify with a periodic rescan fallback; removable drives via udisks2/GIO; MTP/SMB via GVfs mounts treated as import sources; OpenURI portal for "open with"; freedesktop thumbnail cache read (write our own). |
| 9.3 | Decoding on desktop | Skia (via Coil 3) for common formats; libheif/libjxl/LibRaw via JNA/FFM, or **libglycin** (MPL-2.0/LGPL) as the sandboxed loader layer. Avoid its GPL JXL loader: use libjxl directly. |
| 9.4 | Desktop UX | Multi-window, menubar, full keyboard/mouse (hover previews, Quick Look, marquee), drag-and-drop with the file manager, a two-pane default, culling workflow at desktop speed. |
| 9.5 | Packaging | Flatpak (Flathub) with `--filesystem=xdg-pictures:ro`/`xdg-videos:ro` + portals; optional Snap with `removable-media`. ML runtimes: ONNX Runtime x64/arm64. |
| 9.6 | Library portability | Open a drive or folder with a Foto Xplorr backup manifest (WP3.7) and see the same ratings, keywords and edits as on the phone. |

**Gate P9:** Flathub submission; the same library opened on the phone and the desktop shows identical metadata.

---

## 4. Format support matrix (target)

"Pack" means an optional module (WP2.3) the app works without. The decode route on Android is shown; UT and Linux follow WP8.5/9.3.

| Format | Discover | Decode (Android) | Animate | Metadata R/W | Notes |
|---|---|---|---|---|---|
| JPEG, PNG, WebP, GIF, BMP, WBMP, ICO | MediaStore/SAF | Platform | GIF/WebP | R/W (JPEG/PNG/WebP), R others | Ultra HDR (34+) |
| HEIC/HEIF | MediaStore | Platform 28+; pack avif-coder 3.x below | Sequences 30+ | R + sidecar | HEIC Ultra HDR on 36 |
| AVIF | MediaStore | Platform 31+; pack below | Animated via pack/36 | R + sidecar | |
| JPEG XL | SAF (maybe MediaStore) | Pack jxl-coder | Yes | Sidecar | |
| RAW (15+ types incl. CR3) | MediaStore (not CR3) + SAF | piex preview; LibRaw develop (pack) | — | R + sidecar | Never write into RAW |
| DNG | MediaStore | Platform/piex; LibRaw | — | R + sidecar | |
| TIFF (multi-page) | MediaStore | Pack TIFF | Pages | R + sidecar | |
| PSD (composite), TGA, HDR, PNM, PIC | SAF (PSD maybe MediaStore) | Pack stb_image | — | Sidecar | PSB: placeholder |
| EXR | SAF | Pack tinyexr + tone-map | — | Sidecar | |
| JPEG 2000 / JXR / QOI / DDS / KTX | SAF | Packs | — | Sidecar | |
| ORA / KRA | SAF | Unzip mergedimage.png | — | Sidecar | |
| ICNS / PCX | SAF | Kotlin | — | — | |
| SVG / SVGZ | MediaStore/SAF | coil-svg (+gzip) | — | — | |
| APNG | MediaStore | APNG4Android | Yes | R | |
| XCF | SAF | Placeholder (+thumbnail) | — | — | No permissive decoder |
| Motion / Live photos | MediaStore | Primary + embedded video | Play | R | WP2.8 |
| Photo spheres / 360 video | MediaStore | GLES sphere | — | GPano R | + ambient audio (WP2.13) |
| Depth / stereo / spatial | MediaStore | Parallax/stereo modes | — | GDepth R | WP2.14 |
| Video: MP4/MOV/MKV/WebM/3GP/TS/AVI/FLV/Ogg | MediaStore/SAF | Media3 | — | R (location, dates) | WP2.9 |

---

## 5. Model and dependency register (seed for `MODEL_LICENSES.md` / `DEPENDENCIES.md`)

Verify every row against its actual licence file before use, and record the SHA-256.

| Purpose | Pick | Licence | Offline | Notes |
|---|---|---|---|---|
| Inference runtime | ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android`) | MIT | Yes | Primary |
| Inference runtime | LiteRT (`com.google.ai.edge.litert`) / LiteRT-LM | Apache-2.0 | Yes | GPU/NPU; LLM |
| Semantic search | SigLIP2 base-patch16-256 | Apache-2.0 | Yes | Pack; fallback OpenCLIP ViT-B/32 (MIT) |
| Query LLM | Qwen3-0.6B / Gemma 4 E2B | Apache-2.0 | Yes | Optional pack |
| Faces | YuNet + SFace (OpenCV Zoo) | Apache-2.0 | Yes | Training-data caveat; opt-in |
| Segmentation | BiRefNet-lite / U²-Netp; EfficientSAM / MobileSAM; MediaPipe Interactive Segmenter | MIT / Apache-2.0 | Yes | |
| Inpainting | MI-GAN; LaMa | MIT; Apache-2.0 | Yes | |
| Depth | Depth Anything V2 **Small** | Apache-2.0 (Small only) | Yes | Larger variants are non-commercial |
| Aesthetics | NIMA MobileNet / LAION aesthetic head | Apache-2.0 / MIT | Yes | |
| OCR | ML Kit v2 bundled scripts; Tesseract | Proprietary, free to use / Apache-2.0 | Yes | Tesseract for F-Droid |
| RAW preview | piex | Apache-2.0 | Yes | NDK |
| RAW develop | LibRaw 0.22 | CDDL-1.0 (chosen) | Yes | Separate `.so` + source offer |
| JXL | awxkee jxl-coder (+coil) | Apache-2.0/BSD-3 | Yes | |
| AVIF/HEIC backport | awxkee avif-coder **3.x** (+coil) | Apache-2.0/BSD-3 (verify; repo LICENSE says MIT) | Yes | Never 2.x |
| TIFF | Android-TiffBitmapFactory / TiffRenderer | MIT / Apache-2.0 | Yes | |
| Misc decoders | stb_image, tinyexr, OpenJPEG, jxrlib, qoi, dds-ktx, bcdec, APNG4Android | MIT/PD/BSD/Apache | Yes | |
| Video | Media3 ExoPlayer/Transformer | Apache-2.0 | Yes (no HTTP data source in offline) | |
| SMB / SFTP | smbj / sshj | Apache-2.0 | Connect only | |
| DLNA | UPnPCast | MIT | Connect only | |
| Vault crypto | Tink | Apache-2.0 | Yes (verify no network deps) | |
| **Banned** | MobileCLIP(2) weights, InsightFace, EdgeFace, Jina CLIP v2, RMBG, YOLO (AGPL), avif-coder 2.x, x265, glycin JXL loader, ObjectBox, GPL gallery code | — | — | — |

---

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Toolchain lockstep with Hyle/shared-libraries blocks WP1.1 | Do it first, in its own PRs; the owner merges the submodule repos |
| Catalogue migration loses user data | Shadow-write + golden comparison + forward-only resumable migration + a pre-migration JSON export kept on device |
| APK size balloons with packs and models | Everything optional is a pack; per-ABI splits; size recorded per pack in `DEPENDENCIES.md` |
| Model licences (weights vs code) | `MODEL_LICENSES.md` + CI gate; banned list above |
| Face data is biometric (GDPR/BIPA) | Opt-in, on-device, deletable, excluded from backups unless chosen |
| K/N `linuxArm64` Tier 2 | Headless CI from Phase 1; device tests at the P8 gate |
| UT reserved policy groups refused | Content Hub import path works without them; document the UX difference |
| Compose Desktop on Wayland | XWayland acceptable for v1; the Qt shell is the fallback (WP9.1) |
| HEVC patents when shipping a decoder | Prefer the platform decoders; pack decoders only where the platform lacks them; note in the ADR |
| Scope size | The phase gates are hard stops; each phase ships value on its own |

---

## 7. What the owner does at each gate

1. Run `docs/device-test/phase-<N>-checklist.md` on the RedMagic (and a second device when stated).
2. Answer the owner questions in `MASTER-PROGRESS.md`.
3. Review the ADRs. Architecture ADRs (WP1.1–1.3, 2.4, 6.1, 7.1, 8.1, 9.1) deserve an Opus review session.
4. Merge the phase PR, then start the next phase with the kickoff line.
