# Phase 3 design: every storage location (the safe subset)

Date 11 Nov 2026. Base `cf4ccec` (PRs #17/#18 merged). Design only; no source file was changed. Nothing here has run on a
device. Plan text: `docs/handoff/MASTER-PLAN.md` Phase 3 (WP3.1 to 3.13). Decisions for the owner are drafted as
`docs/adr/ADR-020` to `ADR-023` (all PROPOSED).

Standing limits that shape every choice below (batch 9/10 rules, still in force): nothing rewrites or migrates the existing
library stores (Room catalogue and its v2 cut-over, the SharedPreferences stores for Favorite/Sensitive/Library/PrivateFolder,
SQL paging UI, shred/audit log); new stores in new files are fine; zero new Gradle dependencies, no native code; the Offline
flavor has no network code (`verifyOfflineSourceReferences` reads comments, TRAPS #43/#133); protected/sensitive photos only
through the browsable list (TRAPS #33); pixels through `decodeUpright` (TRAPS #34); everything must be verifiable on the JVM,
the rest becomes deviceChecks.

## 1. A new kind of asset source feeding the existing gallery

### 1.1 What the gallery reads today

- One list. `FotoXplorrActivity.kt:249`: `val assets by repository.observeAll()`, where `repository` is
  `LibraryRuntime.repository` (`SqliteMediaRepository`, a MediaStore mirror; `LibraryRuntime.kt`, scanner in
  `media/AndroidMediaStoreScanner.kt`). The scan sweeps what the scanner did not see (`MediaRepository.removeAllExcept`,
  `media/MediaScanner.kt`), so anything injected into that repository would be deleted by the next full scan.
- `assets` goes into `GalleryUiState(assets = assets, ...)` (`FotoXplorrActivity.kt:1687`). Inside `GalleryScreen` the root
  view is `destinationAssets` and every route's list is built from `state.assets`; the grid draws `viewAssets`
  (`gallery/GalleryScreen.kt:819`). Layout, `GridIndexMap`, the rail, selection and composite stacks all key off that list.
- The one visibility rule is `browsableAssets` (`gallery/GalleryProjection.kt:94`): not trashed, id not archived, folder
  not locked (`isPrivacyVisible`, `GalleryProjection.kt:356`, keyed by `folderIdentity(asset).key`), not sensitive when
  hide-sensitive is on. It is applied in about ten places: `GalleryScreen.kt:409`, `:683`, `GalleryContent.kt:816`,
  `DestinationBrowserScreen.kt:511`, `SettingsTabs.kt:149`, `PhotoPickerActivity.kt:218`, `SyncModel.kt:86`,
  `ExternalViewerActivity.kt:292`, and `browsableNow` in the activity (`FotoXplorrActivity.kt:373`).
- The activity uses `assets` for much more than display (all line numbers `FotoXplorrActivity.kt`): incoming open-with
  lookup `:399-413`; viewer restore `:547`, `:598`; **`libraryStore.removeMissingMedia(assets ids - affected)` after a
  permanent delete `:649`** (a collection or tag entry whose id is not in the list is purged); the plain metadata backup
  `photos = { assets }` `:1132`; the recognition pass and auto-curation `:1300-1305`; the picker's library `:1507`;
  the cloud-sync feed `SyncLibraryFeed(assets, ...)` `:488`.

### 1.2 The minimal seam

Add an additive provider and merge once, at the line that defines `assets` (`:249`), before anything else sees it:

```
val libraryAssets by repository.observeAll()...          // unchanged: MediaStore only
val sourceAssets  by SourcesRuntime.get(ctx).assets()...  // NEW: from fotoz-sources.db
val assets = remember(libraryAssets, sourceAssets) { mergeSourceAssets(libraryAssets, sourceAssets) }
```

- `assets` (merged) feeds display and lookups: `GalleryUiState.assets` `:1687`, open-with/restore `:399-413 :547 :598`,
  the picker library `:1507` only if the owner wants sources offered to other apps (default no, see Q10).
- `libraryAssets` (MediaStore only) keeps feeding the consumers that key into existing stores or MediaStore ids:
  `removeMissingMedia` `:649` (otherwise an offline source's ids vanish from the list and its entries would be purged),
  the metadata backup `:1132`, recognition and auto-curation `:1300-1305`, `SyncLibraryFeed` `:488`.
- `mergeSourceAssets` is pure: concatenates, drops a source item that duplicates a MediaStore asset (1.5), and guards the
  id band (an incoming MediaStore id that falls in the source band is a bug and the source item loses).
- Only one work unit (U3) may touch `FotoXplorrActivity.kt`, `GalleryScreen.kt` and `MediaAsset.kt`. Everything else is a new package.
- `GalleryScreen.kt:436` shows the permission empty state when `!state.permissionGranted`. A person who denied media
  access but added a folder would see nothing: U3 must treat "has at least one source" as enough to draw the grid.

### 1.3 Ids that cannot collide

- MediaStore `_ID` is >= 0. Open-with assets use `syntheticNegativeMediaId` (`openwith/ExternalAssetLoader.kt:63`, range
  -1 to -2^31). `MediaId.EXTERNAL` is `Long.MIN_VALUE` and `NO_MEDIA_ID = -1` (`FotoXplorrActivity.kt:202`).
- Proposed band: `id = -(2^33) - n`, `n` = the never-reused row number of the asset in `fotoz-sources.db` (autoincrement,
  not a hash, so there is no collision to reason about). Stable across restarts and rescans (the row is looked up by
  `(source, document id)`), which the viewer's restore-by-id, selection sets and `LazyGrid` keys need.
- What a negative id does to the existing stores (checked): `FavoriteIdCodec.encode/decode` drop ids < 0
  (`favorites/FavoriteIdCodec.kt`), `LibraryStore.encodeIds/decodeIds` drop them (`organize/LibraryStore.kt:930-939`),
  `ResumePositionStore` refuses them (`viewer/ResumePositionStore.kt:65`). **`SensitiveIdCodec` does not**
  (`privacy/SensitiveStore.kt:72-78`), and `FavoriteStore.persist` keeps them in memory until restart
  (`favorites/FavoriteStore.kt:44-50`). So a favourite toggled on a source photo "works" until the app restarts, and a
  sensitive mark would be written into the existing prefs file. The sign is not a safety net; the action gate (1.6) is.
- `PrivateFolderStore` keys are folder-key strings, not ids (see 1.4).

### 1.4 Folders, protected folders, sensitive photos (TRAPS #33)

- `folderIdentity` (`gallery/FolderIdentity.kt`) builds the key from `relativePath` first, lowercased, ignoring volume.
  A drive's `DCIM/Camera` would merge into the phone's `DCIM/Camera` album (the cross-volume merge ADR-011 names). The
  mapper therefore writes `relativePath = "~src/<sourceRow>/<path inside the tree>"` and `bucketName = leaf folder name`;
  key becomes `path:~src/3/dcim/camera`.
- Because the merge happens before `browsableAssets`, a source asset goes through exactly the same filter as any other
  (TRAPS #33 holds with no new code path): trashed (always false for a source in v1), archived (cannot be set, see 1.6),
  locked folder (works: the key above is a normal key; locking a source folder from the Albums UI writes one string into
  the existing `PrivateFolderStore`, as any lock does, and fails closed), sensitive (cannot be set in v1).
- New fail-closed rule for U3's test list: a locked source folder is absent from every list the existing tests enumerate
  (grid, map, calendar, spatial, picker, sync feed), and a source asset is never added to a list that bypasses
  `browsableAssets`. Add a test that scans `GalleryActions` write callbacks for source ids.
- Open question for the owner (Q5): whole-source privacy ("hide this drive") would be stored in the new file, not in
  `PrivateFolderStore`, if wanted; not needed for v1.

### 1.5 Do not show the same photo twice

A tree on internal storage or an SD card is also indexed by MediaStore. `SourceOverlapFilter` (pure) drops a source item
when a MediaStore asset has the same volume, relative path, display name and size. The volume of a tree comes from its
document id (`primary:DCIM/Camera`, `<uuid>:Pictures`), mapped with the existing `SafParentResolver.providerVolumeId`
(`metadata/SidecarDirectories.kt`, whose inverse is already used for sidecars). USB OTG volumes are normally not in
MediaStore, so nothing is hidden there. Under Android 14 partial media access MediaStore lacks files, so the filter only
hides what it can match; a source is never hidden "because the volume is covered".

### 1.6 Thumbnails, decoding, EXIF, orientation for a non-MediaStore uri

All of it is `ContentResolver` streams, so a SAF document uri works unchanged:
- Tiles: `media/MediaImage.kt:62-100` builds a Coil request from `asset.contentUri`; cache key is
  `uri@dateModifiedSeconds` (`mediaImageCacheKey`), so a source asset needs a real `dateModifiedSeconds` (from
  `COLUMN_LAST_MODIFIED`). RAW and simple formats take the in-house routes (`DecodeRoutes.forTile`).
- Upright pixels: `decodeUpright(context, uri, limits)` (`media/BitmapDecoding.kt:66`) is stream-based and format-by-name,
  nothing MediaStore-specific. EXIF/GPS reads: `uriForLocationRead` returns a non-MediaStore uri unchanged
  (`media/OriginalAccess.kt:38`), and SAF does not redact GPS, so the Place section and the map work.
- What a SAF listing does NOT give: width, height, orientation, date taken, duration. The indexer's first pass stores
  name, size, mtime, mime; a second bounded "enrichment" pass reads the head of the file (EXIF via `ExifInterface` from a
  stream, `MediaMetadataRetriever` for video) with retries recorded and "unreadable" never treated as "absent" (§2.3 of the
  plan). Until enriched, `width`/`height` are 0 and `aspectRatio` falls back to 1 (`MediaAsset.kt`), `dateTakenMillis`
  falls back to mtime. Justified layout is wrong for unenriched items; say so in the UI state, not silently.
- Slow providers (USB, cloud apps): full-file decode for each grid tile is the cost. Fast path: `DocumentsContract.getDocumentThumbnail`
  when the document sets `FLAG_SUPPORTS_THUMBNAIL`; for remote-backed providers (`FLAG_VIRTUAL_DOCUMENT`, authority not
  `com.android.externalstorage.documents`) skip the sniff and enrichment unless the person asks (WP3.10). Do not rely on
  Coil's disk cache as the offline thumbnail store: `FotoXplorrApplication.kt` configures one, but Coil's disk cache is
  written by network fetchers and the comment claiming it caches decoded content-uri tiles is unverified. WP3.4 needs its own store.

### 1.7 Favourites, ratings, trash, edit, share for a source asset (v1)

Disabled until the store decisions are made (owner Q on "where ratings live", batch 10 residual). Done by one pure
`SourceActionGate` wrapped around the `GalleryActions` lambdas at `FotoXplorrActivity.kt:1740-1780` and the viewer's
`catalogueActions`/`isExternal` gates (`viewer/ViewerActionsRoom.kt:146`, `ViewerScreen.kt:1268`). The viewer already hides
catalogue actions for `isExternal` assets (43 references); reusing `isExternal = true` for source assets would be the
cheapest start, but it also removes share/export (`ViewerScreen.kt:1314` `mayExport`) and the viewer restore
(`FotoXplorrActivity.kt:340` filters external ids out of `savedViewerAssetIds`). Recommended: a defaulted trailing field on
`MediaAsset` (`val sourceRef: String? = null`, the same shape `isExternal` was added with) and gate on that.

| Action | v1 for a source asset | Why |
|---|---|---|
| View, open, filmstrip, zoom, info, map, search by name/folder | yes | stream-based |
| Select, share (as a content uri copy), copy out to a folder or Pictures | yes | read-only; copy goes through the file-ops engine (U7) |
| Favourite, sensitive, tag, collection, archive | hidden | writes the existing stores (1.3) |
| Rating, flag, label | hidden; later to XMP sidecars via `SidecarService` | owner store decision; `SidecarService` write needs a tree grant that the source already holds |
| Trash, delete, rename, move | hidden until U7 | MediaStore requests (`FotoXplorrActivity.kt:675-677`) throw on a non-MediaStore uri |
| Edit | opens the editor as a copy only (new file, never in place) | rule: new files never overwrite originals |
| Recognition, similarity, moments, palette index | not run | their stores key by MediaId and are slow on USB |
| Backup, cloud sync | excluded in v1 | feeds stay on `libraryAssets` |

## 2. Work packages 3.1 to 3.13: status and the safe part

"Safe" = pure core plus one Android seam plus JVM tests, no dependency, no existing store.

| WP | Status today | Safe to build now | Needs a dependency / ADR | Touches existing stores (defer) | Device only |
|---|---|---|---|---|---|
| 3.1 open-with, picker, share-receive | Built in P0-18/20: VIEW `ExternalViewerActivity` (`AndroidManifest.xml:223-228`), EDIT and SEND (single) on the main activity (`:194-204`), GET_CONTENT/PICK `PhotoPickerActivity` over `browsableAssets` (`:239-256`, `PhotoPickerActivity.kt:218`, honours `ALLOW_MULTIPLE` `:86`). `classifyIncomingIntent` already handles SEND_MULTIPLE (`openwith/IncomingIntentRoute.kt:62`) | SEND_MULTIPLE manifest filter (missing); "Save to library" (copy into `Pictures/Fotoz/` via a MediaStore insert with `IS_PENDING`, never overwriting) and "View" choice; tests of the route and the writer | none | none (new MediaStore rows only) | Files app on USB opens in Fotoz; another app picks from Fotoz |
| 3.2 SAF folder sources | Not built. `sync/SafTreeFileSystem.kt` and `metadata/SidecarDirectories.kt` talk to trees but resolve by path name or by known parent; `backup/SafBackupDestination` does one child query with `SafListing.settle` (EXTRA_LOADING retry, `SafBackupDestination.kt:198`). Persisted-grant code exists in `sync/SyncAndroid.kt:24,224` and `gallery/CullSidecarRoute.kt` | Pure BFS walker with resumable cursor, ignore rules, cycle and depth caps, magic-byte sniff via `FormatSniffer` over a `ByteSource`; lister over `DocumentsContract` (one query per directory, full projection, reuse `SafListing`); grant registry (REVOKED detection, count vs the platform cap); source store (U1) | none | none (new file) | 50k-file tree on a real provider; revoked grant on a real device |
| 3.3 Drive lifecycle | Not built. Only `SafBackupDestination.kt:154` touches `StorageManager` (volume label) | Pure drive registry (identity by FS UUID taken from the tree document id, replug matching, states ONLINE/OFFLINE/REVOKED never delete); runtime `StorageManager.registerStorageVolumeCallback` and mount broadcasts; "Drive connected, add it?" card via `createOpenDocumentTreeIntent` | none (USB_DEVICE manifest filter is a manifest edit, no dependency) | none | plug/unplug cycles; same drive recognised after replug |
| 3.4 Offline previews | Not built | `PreviewStore` policy (file naming by asset+revision, 256 px WebP, per-source byte cap, eviction, size report) and writer; browsable greyed offline tile state | none | none | unplugged drive browsable; storage panel numbers |
| 3.5 File operations | Partial: `fileops/MediaFileOperations.kt` (rename, bulk rename, `copyToTree`), `SafeDocumentWrite` (verify + `.bak`) for sidecars, additive backup writer. No conflict engine, no `.fotoz-trash`, no resume journal | Engine in `:core:organize` + `FileAccess` seam for File/SAF: copy via temp then rename, verify, conflict policy, trash manifest, expiry arithmetic (ADR-022) | none | MediaStore trash path stays as is | pull the drive mid-copy; provider rename-on-clash |
| 3.6 Camera and card ingest | Not built (no `MtpDevice` use) | Pure ingest planner (destination template, rename, skip by fingerprint, second destination, verify, delete-after-verify gate); card as an SAF source needs nothing more | none for `android.mtp.MtpDevice` (platform API) | none | MTP/PTP, 500 photos, USB permissions |
| 3.7 Back up to drive | Largely built: encrypted packs, manifest, verifier, restore, identity match (`backup/`, ADR-014, TRAPS #75). Identity is name+size+dateTaken+path (`IdRemap.kt`), not a fingerprint; no sidecars in the set; no "protected on N places" | Optional later: add XMP sidecars and a fingerprint section as additive manifest sections (`BackupSections`); verification-report copy | none | the plain metadata backup is MediaStore-id based (do not change) | restore on a fresh install |
| 3.8 Annotations travel | Codec, policy and `SidecarService` built in batch 10 (`core/metadata`, `metadata/`); ratings are in memory unless the user enables sidecars | Walker records sibling `.xmp` names it already sees in the same directory listing (free); read-only display of foreign ratings for source assets | none | write-through persistent ratings (owner Q) | round-trip with a Lightroom folder |
| 3.9 Network sources | Not built. `sync/` WebDAV and S3 clients exist but are write-only (`SyncProvider` has stat/list/put) | After U4, Connect only: add a ranged `get` to the transport and WebDAV/S3 `Source` adapters; tests with a fake transport as in `sync/` | smbj, sshj, MinIO SDK: ADR-021, recommend not now. Immich: hand-written REST client, no dependency, needs a server | none | each protocol against a real server |
| 3.10 Cloud via SAF providers | Falls out of 3.2 | Provider traits (virtual documents, no thumbnails, slow open) and "import selected" through `OpenDocument` multiple; UI copy per provider | none | none | OneDrive/Drive/Dropbox trees |
| 3.11 Storage panel | Not built | Pure summary model from the source store (counts, bytes, preview size, last scan, errors) and a Settings page; "protected on N places" waits for 3.7 sections | none | none | real numbers |
| 3.12 Cast | Not built | nothing safe (needs a server and discovery) | UPnPCast, Ktor/NanoHTTPD: ADR-021, recommend not now | none | DLNA TV |
| 3.13 All-files build | Not built | nothing | ADR-023, recommend not now | none | n/a |

## 3. Build plan: eight work units

Shape of the code (all in new packages; existing files touched only where listed):
- Pure cores: `:core:index` (`com.fotoxplorr.core.index.sources.*`, commonMain, tests in a new `commonTest`) for source
  identity, folder keys, walker, overlap, drive registry; `:core:organize` (`com.fotoxplorr.core.organize.fileops.*` and
  `.ingest.*`, commonMain + commonTest) for the engine and ingest planner. Both already compile for Kotlin/Native; rules from
  TRAPS #134: no `java.*` in commonMain (`scripts/verify-core-purity.sh`), no deprecated common API (`concatToString()`,
  `lowercase()`), no `, . : ; / \ < > [` in commonTest backtick names, JVM-only things in `jvmTest`. Native cannot be run on
  the agents' machines: say "CI core-native is the only check".
- Store: a third Room KMP database `FotozSourcesDatabase` (`fotoz-sources.db`) in `:core:db`, package
  `com.fotoxplorr.core.db.sources`, built next to the other two (`LibraryRuntime.kt:105-109` is the pattern, but built from the
  new `SourcesRuntime`, not by editing `LibraryRuntime`). Tables: `src_source` (id, kind, root locator, tree uri, volume
  uuid, display name, state, traits, last scan, error), `src_asset` (autoincrement id, source id, document id, parent,
  name, mime, size, mtime, width, height, orientation, date taken, duration, enrichment state/attempts, availability),
  `src_walk` (resumable cursor: pending directories, counters, started-at), `src_preview` (asset id, revision, bytes).
  In-memory JVM tests as `core/db/src/desktopTest` and `core/index/src/jvmTest/.../SyncEngineTest.kt` do. KSP exports the
  schema JSON (new file under `core/db/schemas/`). It does NOT reuse `SyncEngine` (that writes `fotoz.db`'s `asset` table);
  it reuses the `Source` interface vocabulary (`SourceItem`, `SourceEvent`, `SourceCapabilities`) and the same
  mark-and-sweep rule (TRAPS #8): sweep only after a completed full walk, never on partial access, an absent source is OFFLINE.
- Jobs: scans run as `JobRunner` jobs (`ActivityKind.SCANNING`, `jobs/JobRunner.kt`), foreground service already maps SCANNING
  to data sync; background re-scan on connect follows `WorkRules` (charging default). No new job service.
- Copy: one `SourcesCopy` object per feature, one short sentence each; 48 dp targets; a non-drag path for every drag; every
  new Settings row via `SettingsRow` with an icon (TRAPS #133).

### Units

| Unit | Scope | Owns | May touch minimally | Size | Start |
|---|---|---|---|---|---|
| U1 Source model and store | `SourceAssetId` band, `SourceFolderKey`, `SourceItem` to row mapping, overlap filter, state machine (ONLINE/OFFLINE/REVOKED/ERROR, deletions forbidden), `FotozSourcesDatabase` + DAOs, mark-and-sweep for a source | `core/index/.../sources/{ids,model,overlap}`, `core/db/.../db/sources/**`, `core/db/schemas/*Sources*` | `core/db/build.gradle.kts` only if KSP needs the new class listed (it should not) | M | first |
| U2 SAF walker and lister | Pure BFS walker with cursor, ignore rules (`.nomedia`, `.fotoz-trash`, `Android/`, `.thumbnails`, `$RECYCLE.BIN`, `System Volume Information`, hidden dirs by option), cycle/depth/entry caps, sibling-sidecar names, sniff; Android lister over `DocumentsContract` (one query per directory, `SafListing.settle` for EXTRA_LOADING/ERROR); grant registry (take, list, detect revoke, count against the platform limit) | `core/index/.../sources/walk/**`, `app/.../sources/saf/**` | none | M-L | first (needs only the interface contract in 1.2 and the row shapes; integrates in U4) |
| U7 File-ops engine | Plans, conflict policy, copy temp-verify-rename, resume journal, trash manifest + expiry arithmetic, `FileAccess` for File/SAF with failure-injecting fakes | `core/organize/.../fileops/**`, `app/.../fileops2/` (new package, NOT `fileops/MediaFileOperations.kt`) | none | L | first |
| U8a Ingest planner | Destination template parser, rename rule hook, skip-by-fingerprint, second destination, verify, delete-after-verify gate; `MtpIngestSource` skeleton behind an interface (unverified) | `core/organize/.../ingest/**`, `app/.../ingest/**` | none | M | first (reuses U7's hash and `FileAccess` interfaces: agree the two signatures first, else after U7 core) |
| U3 Gallery seam | `SourceAssets` provider interface + `mergeSourceAssets`, mapper `SourceRow -> MediaAsset`, `sourceRef` field, `SourceActionGate`, activity wiring (1.2), permission empty-state rule, offline/greyed state flag | `app/.../sources/gallery/**`, tests | **the only owner of** `FotoXplorrActivity.kt` (lines 249, 488, 649, 1132, 1300-1305, 1507, 1687, 1740-1780), `GalleryScreen.kt:436`, `media/MediaAsset.kt` | M | after U1 (needs the id band and row shape) |
| U4 Sources UI and indexing job | `SourcesRuntime`, add folder (`OpenDocumentTree`, pattern at `SettingsServices.kt:62`, `takePersistableUriPermission` pattern at `SyncAndroid.kt:24`), list/rescan/remove (removing forgets the grant, never touches files), index job (walk, then enrichment pass), states and errors, Settings page | `app/.../sources/{runtime,ui,job}/**`, `SourcesCopy` | `gallery/SettingsServices.kt` / `SettingsTabs.kt`: one row each, `ServicesHub` entry | L | after U1, U2, U3 |
| U5 Offline previews | `PreviewStore` (policy pure, writer Android), 256 px WebP, per-source cap, eviction, size report, greyed tile for offline assets | `core/index/.../sources/preview/**`, `app/.../sources/previews/**` | `media/MediaImage.kt` tile branch for the greyed/"Plug in drive" state (single hook; coordinate with U3) | M | after U1 and U3 |
| U6 Drives and storage panel | Drive registry (FS UUID identity, replug matching, never delete), `StorageManager` callback + broadcasts, "Drive connected" card, per-drive settings (include, preview policy, index on connect), storage panel (3.11) | `core/index/.../sources/drive/**`, `app/.../sources/{drives,storage}/**` | USB attach filter in `AndroidManifest.xml` (one activity filter + `res/xml` device filter) | M | after U1 and U4 |
| U9 (after owner Q) 3.1 rest | SEND_MULTIPLE filter, "Save to library" and "View" | `app/.../openwith/` additions, `AndroidManifest.xml` | `FotoXplorrActivity.kt` incoming-intent section (serial after U3) | S | after U3 |

Parallelism:
- Wave A in parallel (disjoint ownership): U1, U2, U7, U8a. U2 and U8a agree interfaces with U1/U7 by the signatures in this
  document first; if not agreed, U2 and U8a start on their pure cores and integrate after.
- Wave B: U3 after U1. Then U4 (after U2, U3), U5 (after U3), U6 (after U4) can overlap, with U4/U5/U6 writing different
  packages. Settings-tab and manifest edits are the only shared files: U4 first, U6 rebases onto it.
- Wave C: U9; optional Connect-only WebDAV/S3 read sources (WP3.9, no dependency) after U4 as their own unit.
- Strictly serial: U1 before U3; U3 before anything that edits the activity; U4 after U2+U3.

### Acceptance tests (JVM) per unit

- **U1:** id band: 100k allocated ids are unique, all < -2^33, never in [-2^31, Long.MAX], stable after reopening the db;
  folder key isolation (a drive `DCIM/Camera` and a MediaStore `DCIM/Camera` give different `folderIdentity` keys);
  overlap filter (same volume/path/name/size hidden, different size kept, unmatched volume kept); state machine never produces a
  delete from OFFLINE/REVOKED/ERROR; sweep happens only for a completed full walk and never when the walk failed or was
  cancelled (TRAPS #8); Room in-memory round trip incl. cursor persistence.
- **U2:** a fake tree of 50k files in nested directories walks breadth-first, resumes from a cursor saved at 10%, 50%, 99%
  and yields the same set as an uninterrupted walk; a cycle (a document that lists its ancestor) terminates; `.nomedia`
  hides a directory's media, `.fotoz-trash` and `Android/` are skipped; a lister that throws or reports EXTRA_ERROR yields
  Failed, never an empty "complete" (empty vs failed lead to opposite decisions); a revoked grant maps to REVOKED and deletes
  nothing; magic-byte mismatch (a `.jpg` that is a PNG, a renamed RAW) classifies by bytes; timing of the 50k walk is
  measured and recorded (JVM, not a device number). Robolectric: the `DocumentsContract` lister against a registered fake
  provider (cursor columns, EXTRA_LOADING retry).
- **U7:** property-style tests with a failure-injecting `FileAccess`: kill at every step of copy (before, during, after temp
  write, before rename) leaves no partial target and the next run finishes or cleans; verify mismatch deletes only the temp;
  `keep both` names never collide; `replace` sends the old file to the trash first and the manifest restores it; cross-source
  move deletes the original only after the copy verified and then only into the trash; expiry arithmetic across DST/year
  boundaries (kotlinx-datetime); the engine never calls delete on an original (a recording fake asserts this).
- **U8a:** `{yyyy}/{yyyy-MM-dd}` expansion with a fixed zone, illegal characters sanitised, duplicates skipped by fingerprint,
  a second destination failure does not mark the card file deletable, delete-after-verify offered only when both verify.
- **U3:** `mergeSourceAssets` order, overlap, band guard; `browsableAssets` over a merged list hides a source asset in a locked
  folder and shows it when unlocked; a test that every `GalleryActions` write callback gated by `SourceActionGate` ignores
  source ids and never reaches Favorite/Sensitive/Library stores (use the in-memory stores' `observe()`); the Robolectric
  smoke test that the activity still builds `GalleryUiState` from a merged list; existing characterisation goldens unchanged
  (a change is a bug, the merge adds nothing when there are no sources).
- **U4:** add/remove/rescan state machine against a fake grant registry and fake lister; removing a source forgets the grant and
  the rows and calls no delete on files; the enrichment pass respects its byte cap and records attempts; copy test (one short
  sentence each) and the icon-per-row test.
- **U5:** names, cap and eviction (least recently used first, never below one preview per asset on a fresh cap), size report
  equals the sum of files, an offline asset resolves to its preview and a missing preview to the labelled placeholder
  (`FormatPlaceholder`, not blank).
- **U6:** drive registry: same FS UUID after unplug/replug maps to the same source; a different drive with the same label is a
  new source; unplug marks OFFLINE and nothing else changes; storage summary sums.
- **Offline gates for every unit:** `:app:verifyOfflineSourceReferences`, `verifyOfflineManifest`, `verifyOfflineRuntimeClasspath`,
  both flavors compile; no network token in any new file (also in comments). All SAF code is network-free and lives in
  `src/main`. Connect-only protocol work (WP3.9) goes to `src/connect` with a same-FQN stub in `src/offline`.

### deviceChecks to carry (not provable here)

SAF walk on real providers (external storage, USB, Drive/OneDrive/Dropbox apps) incl. 50k files and a revoked grant; the
persisted-grant cap in practice; provider thumbnails (`FLAG_SUPPORTS_THUMBNAIL`); first-scroll speed over USB; storage-volume
callback and USB attach on Android 11 to 16; same-drive recognition after replug; yank test mid-copy; provider rename-on-clash;
`.fotoz-trash` visibility in other apps; MTP/PTP ingest of 500 photos; Files-app open-with from USB; another app picking from
Fotoz; greyed offline tiles; storage panel numbers; behaviour with Android 14 partial media access plus a folder source.

## 4. Risks

1. **Silent loss of user marks.** A missed gate writes a source id into an existing store; `SensitiveIdCodec` would persist it
   (1.3). Mitigation: the gate plus a test enumerating every write callback; `libraryAssets` for store-keyed consumers.
2. **The purge hazard.** `removeMissingMedia(assets ids - affected)` (`FotoXplorrActivity.kt:649`) would drop tags/collections
   for ids not in the list. Source ids are never stored (codec), but the call must stay on `libraryAssets`.
3. **Duplicates and wrong merges.** Covered by the folder prefix and the overlap filter; the overlap rule is heuristic.
4. **Slow tiles on USB/cloud.** Full decodes per tile; thumbnails API where offered; enrichment must be bounded and lazy.
5. **Persisted grant cap.** The platform limits persisted tree grants per app (documented as 128 before Android 11, 512 after;
   verify on device). The backup, sync, sidecar and cull features share the pool; U2's registry reports the count and
   refuses a new source with a plain message near the cap.
6. **Grant semantics.** A grant can be revoked by the system or the user; ordinary "provider unavailable" must not be read as
   REVOKED. A drive that is not mounted is OFFLINE, not ERROR. Never sweep from a partial or failed walk (TRAPS #8).
7. **Second writer of "what exists".** Until WP1.3 is device-tested there are two catalogues (`fotoz.db` shadow and
   `fotoz-sources.db`); the locator column keeps a later adoption possible. Do not let U1 import `SyncEngine`.
8. **Job lifetime.** A long walk under the activity's `JobRunner` dies with the process; the cursor makes it resumable, but
   there is no automatic resume until the owner approves background scanning (`WorkRules`).
9. **Kotlin/Native strictness (TRAPS #134)** and **lint NewApi in lambdas (TRAPS #135)**: `StorageManager` and
   `DocumentsContract` calls need SDK checks in the same lambda; minSdk is 26, and `StorageManager.registerStorageVolumeCallback` is API 30, so below that use the mount broadcasts.
10. **Manifest changes** (SEND_MULTIPLE, USB attach) widen the exported surface; keep `SafeIntentExtras` for any new intent
    and content:// only (`openwith/IncomingIntentRoute.kt`).
11. **Privacy copy.** Store text still says "no cloud, no sync" (batch 9 residual); sources add "folders on other drives"
    but no network, so the Offline flavor claim holds.

## 5. Owner decisions (question, recommended answer)

1. Accept the new `fotoz-sources.db` and negative id band instead of waiting for the Room cut-over (ADR-020)? **Yes.**
2. Source photos in v1: view, share, copy out only, with favourites/ratings/trash hidden? **Yes**; ratings arrive with the
   "where ratings live" decision (batch 10 residual, still open: memory, opt-in sidecars, or a catalogue column).
3. Hide source items that duplicate a MediaStore photo on the same volume? **Yes, automatically, with a count the person can read.**
4. Include source photos in recognition, similarity, backup and cloud sync? **No for now.** Revisit after the device test.
5. May a source folder be protected with the existing folder lock (writes a key into `PrivateFolderStore`)? **Yes, it is the
   same user action as today and fails closed;** a whole-drive hide later, stored in the new file.
6. Offer source photos to other apps through GET_CONTENT/PICK? **No for now** (the picker stays MediaStore-only; grants
   would have to be passed on).
7. App trash on SAF/USB: visible `.fotoz-trash/` with `.nomedia`, no automatic purge in v1 (ADR-022)? **Yes.**
8. Copy-in from share-receive: ask "Save to library or View", View as the default? **Yes.**
9. Dependencies for SMB/SFTP/MinIO/UPnP/Ktor/NanoHTTPD (ADR-021)? **None now.** WebDAV and S3 read sources on the existing
   transport; Immich by hand later; SMB through the system Files app until then.
10. All-files build (ADR-023)? **No**, unless the device test shows SAF listing is unusable.
11. Default storage budget for offline previews per source (256 px WebP, about 20 KB each; 20k photos is about 400 MB)?
    **Cap each source at 1 GB by default, shown in the storage panel, with a "Clear previews" button.**
12. Background indexing when a drive is connected: ask each time, or follow `WorkRules` (charging, unmetered not needed)?
    **Ask on first connect, then per-drive "index on connect", default off.**

## 6. Gate P3, reduced to what the JVM can show

JVM-shown (record the numbers in the gate report):
- A synthetic 20k mixed-file tree indexes with a resumable cursor, equals the uninterrupted result after resume at any point,
  and the walk time is reported.
- Unplug cycles (source goes OFFLINE then ONLINE) never delete a row or a file; REVOKED and ERROR delete nothing; only a
  completed full walk sweeps.
- Offline browse: assets stay in the merged list, flagged unavailable, with a preview or a labelled placeholder.
- Replug recognition: the same FS UUID maps to the same source id and the same asset ids.
- Bulk copy yank test: a fake `FileAccess` that fails at every step leaves no partial file, the next run completes, no original
  deleted, results written next to the originals.
- Ingest plan: template, duplicates by fingerprint, second destination, delete-after-verify gate.
- TRAPS #33: a locked source folder appears in no list; the action gate keeps source ids out of the existing stores.
- Characterisation goldens unchanged; offline gates green; core-native job green (CI only).

Device only (carried to `docs/device-test/phase-3-checklist.md` by the units): the real 20k USB drive, real plug/unplug,
the yank test on hardware, the camera ingest, Files-app open-with from USB, another app using the picker, provider quirks,
scroll smoothness and tile latency over USB.
