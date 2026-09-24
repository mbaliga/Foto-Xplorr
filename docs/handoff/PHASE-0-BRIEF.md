# Foto Xplorr — Phase 0 brief for Claude Code

**Scope:** fix the defects found in the September 2026 audit, and let other apps open photos and videos in Foto Xplorr ("Open with").
**Baseline:** `mbaliga/Foto-Xplorr` `main` @ `5e59d56` (15 Sep 2026).
**Source:** the owner's doc "Foto Xplorr — gap analysis: every format + USB drives". This brief is the executable form of its Phase 0.

**Kickoff line for the owner to paste into Claude Code:**

> Read `docs/handoff/PHASE-0-BRIEF.md` in full, then execute it starting at section 0. Work autonomously to the end. Don't stop to ask me anything: log questions and decisions in the progress file as the brief says.

---

## 0. Read this first

### 0.1 What you are doing

You are working through items P0-01 … P0-19 in a local-first Android gallery: 17 defect groups, the "Open with" feature, and a docs-and-PR step. P0-20 is an optional stretch item.

- You are **not** building new architecture. The catalogue rebuild, USB sources and new decoders are Phase 1+.
- Where a fix would need Phase 1, this brief gives you a bounded Phase 0 version. Build that and nothing larger.

Read these before touching code. They are binding:

1. `docs/TRAPS.md` — 30 invariants, each earned by a real defect. Breaking one is a behaviour you must call out.
2. `docs/RECON.md` and `docs/PRODUCT-AND-ARCHITECTURE.md` — how the app is built.
3. `app/build.gradle.kts` — especially the three offline-flavour gates at the bottom.
4. `scripts/verify.sh` — the only definition of "builds clean".
5. `docs/adr/ADR-007-photo-editing.md` and `docs/adr/ADR-008-video-transcode-pipeline.md`.

### 0.2 Hard rules (never break these)

1. **No new Gradle dependencies.** Everything in this brief can be done with what is already on the classpath: AndroidX, Coil 3, ExifInterface, ML Kit, platform APIs. If you think you need one, don't add it; log it under "Owner questions".
2. **Don't touch the submodules.** No edits in `hyle-design-system/` or `shared-libraries/`, and no pin bumps (TRAPS #2). If a fix seems to need a library change, work around it in the app and log it.
3. **Don't change toolchain versions:** AGP, Gradle, Kotlin, compile/target/min SDK (TRAPS #3).
4. **The offline gates stay intact and green:** `verifyOfflineManifest`, `verifyOfflineRuntimeClasspath`, `verifyOfflineSourceReferences`. Never loosen one to make something pass.
5. **Never open a user's original file for writing** except in the two places this brief names: P0-08 (metadata write) and the existing "Replace original" path in P0-07. Both go through temp-file-then-verify.
6. **No `--` inside XML comments** (TRAPS #19). Parse-check every edited XML with the one-liner in TRAPS #19 before building.
7. **Don't change a characterisation golden** (`CatalogueCharacterisationTest`) unless this brief says that golden may change. For each allowed change, follow the procedure in that test's KDoc (`FX_GOLDENS_PRINT=true`) and state it in the commit message. Only `smart:DUPLICATES` and `smart:ANIMATED` may change in Phase 0. Any other golden diff is a bug in your change.
8. **Don't merge anything.** Work on a branch, push it, open a **draft** PR, stop.
9. **Share and zip paths must never call `MediaStore.setRequireOriginal`** (see P0-02). This rule is what keeps shares from carrying location the OS would otherwise hide.

### 0.3 Environment facts

- There is **no device or emulator.** You can compile, run JVM unit tests (including Robolectric with `@GraphicsMode(NATIVE)`, already used by `MetadataWriterTest` and the render tests), and lint.
- Anything that needs a real device goes into the device checklist (P0-19). Don't claim device behaviour you didn't test.
- Useful commands:
  - One quick build: `./gradlew :app:assembleOfflineDebug`
  - One test class: `./gradlew :app:testOfflineDebugUnitTest --tests "com.fotoxplorr.app.<package>.<Class>"`
  - Everything: `./scripts/verify.sh`. It must end with `VERIFY OK`.
- Git: branch `claude/phase-0-defects` from current `main`. If `main` has moved past `5e59d56`, rebase this plan onto it and note any conflicts with this brief in the progress file.

### 0.4 Working method

Before item 1:

- Copy this brief to `docs/handoff/PHASE-0-BRIEF.md` if it isn't there already.
- Create `docs/handoff/PHASE-0-PROGRESS.md` with:
  - a table: item, status (todo / doing / done / partial / skipped), commit SHA, notes;
  - three headed lists: **Decisions**, **Owner questions**, **Device checks added**.
- Commit both: `P0-00: add Phase 0 brief and progress log`.

For every item, in order:

1. Re-read the files the item names, completely, not just the lines cited. Line numbers are from `5e59d56` and may have drifted.
2. Write or adjust the tests first where the item lists pure logic.
3. Make the change. Keep it minimal and inside the item's scope.
4. Run that item's tests, then `./gradlew :app:lintOfflineDebug`.
5. Update `PHASE-0-PROGRESS.md` and commit in one commit: `P0-xx: <title>`. The body says what changed, why, which tests cover it, and which TRAPS it touches.
6. After every third item, and at the end, run `./scripts/verify.sh`. Fix anything red before moving on.

If your context resets, read `PHASE-0-PROGRESS.md` first and resume from the first item that isn't done.

### 0.5 When something is unclear

- Pick the conservative, fail-closed option: don't share rather than leak, don't write rather than risk corrupting, keep data rather than delete it.
- Log it under **Decisions** and keep going.
- Stop early only if `verify.sh` can't be made green without breaking a rule in 0.2. In that case, finish the progress log and the PR description explaining exactly where you stopped.
- Verify platform behaviour you're unsure of against the AndroidX sources in the Gradle cache or the Android reference docs. Don't assume. Several items tell you exactly what to check.

---

## 1. Work order

| # | Item | Why it's here | Main files |
|---|---|---|---|
| P0-01 | One visibility filter everywhere | Locked, archived and hidden photos leak onto the map, the calendar and the Settings preview | `gallery/GalleryProjection.kt`, `gallery/GalleryScreen.kt`, `gallery/DestinationBrowserScreen.kt`, `gallery/SettingsTabs.kt` |
| P0-02 | Original-access helper; location reading that works on Android 10+ | Places is probably empty on Android 10+; failures get cached as "no location" | new `media/OriginalAccess.kt`, `spatial/GeoMetadataRepository.kt`, `viewer/PhotoDetailRoom.kt` |
| P0-03 | One oriented, bounded bitmap decoder | The editor and shares ignore EXIF rotation; exports get halved | new `media/BitmapDecoding.kt` + 9 call sites |
| P0-04 | Image shares: strip that fails closed, per-item results | Location stripping fails silently for most formats; one bad file kills the batch | `share/SharePreparer.kt`, new `share/MetadataStripper.kt` |
| P0-05 | Video shares: remove location by remuxing | Videos are shared with GPS | new `video/StreamCopyRemuxer.kt`, `moments/ClipExporter.kt`, `share/SharePreparer.kt` |
| P0-06 | Zip export works and follows the share metadata policy | Zip always crashes; it would also ship originals with GPS | `share/ZipExporter.kt`, new `share/FileProviderAuthority.kt` |
| P0-07 | Edited copies keep their metadata; saves land in allowed folders | Edits lose EXIF, XMP and date; saves fail for WhatsApp and Download sources | `editor/EditedCopyWriter.kt`, `editor/EditorScreen.kt`, `video/VideoConversionWriter.kt`, `audio/AudioConversionWriter.kt` |
| P0-08 | Safe metadata writes | Writes go straight into originals; XMP text may be garbled; HEIC and RAW fail only after asking for consent | `metadata/MetadataWriter.kt`, `metadata/XmpPacket.kt`, `FotoXplorrActivity.kt` |
| P0-09 | Scanning and app state survive the Activity | Every start and rotation runs a full scan and rewrites the whole catalogue; the viewer closes on rotation | new `LibraryRuntime.kt`, `media/MediaIndexer.kt`, `media/SqliteMediaRepository.kt`, `AndroidManifest.xml`, `FotoXplorrActivity.kt` |
| P0-10 | Audio permission and selection | The audio library is empty on Android 13+ | `AndroidManifest.xml`, `audio/AndroidAudioMediaStoreScanner.kt`, `FotoXplorrActivity.kt` |
| P0-11 | Video transcode correctness | Portrait output comes out sideways; memory runs out; 1.5× sound is broken; HDR is mishandled | `video/*`, `audio/AudioTranscoder.kt`, `videoeditor/VideoEditRecipe.kt` |
| P0-12 | Recognition: record failures, stop the full reloads | Unreadable files are retried forever; the database reloads every 24 photos | `recognition/RecognitionIndexer.kt`, `recognition/RecognitionStore.kt`, `ai/SimilarityIndexer.kt` |
| P0-13 | Duplicates album keeps one of each | Select all → trash deletes the originals too | `gallery/GalleryProjection.kt`, `curate/ArchiveAdvisor.kt` |
| P0-14 | Real animation detection; the viewer animates | The viewer never animates; the Animated album is wrong | new `formats/AnimationSniffer.kt`, new `formats/AnimationIndex.kt`, `viewer/ViewerScreen.kt`, `gallery/*` |
| P0-15 | `GridIndexMap`; scrubber correct with headers, hidden in masonry | Scrubber, pill and arrow keys are wrong in Timeline mode (TRAPS #5/#9) | new `gallery/GridIndexMap.kt`, `gallery/GalleryScreen.kt`, `gallery/GalleryContent.kt` |
| P0-16 | Search and label fixes | `size:500k` means 500 bytes; `type:raw` misses most RAW; `camera:`/`iso:` match nothing; wrong HDR badge | `search/*`, `formats/MediaFormat.kt`, `viewer/DetailFormatting.kt` |
| P0-17 | Migrate V1 data; remove dead Java | V1 favourites, tags and collections were never migrated | new `organize/LegacyCatalogMigration.kt`, root `*.java` |
| P0-18 | "Open with" (`ACTION_VIEW`) | Other apps, such as the Files app on a USB drive, can't open photos in Foto Xplorr | new `viewer/ExternalViewerActivity.kt`, `AndroidManifest.xml`, `viewer/ViewerScreen.kt` |
| P0-19 | Docs, TRAPS, device checklist, draft PR | Makes the phase reviewable and testable on a phone | `docs/*`, `README.md` |
| P0-20 | *(Stretch — only if everything else is done)* Act as a photo picker | Other apps can pick from Foto Xplorr | new activity or mode |

---

## 2. Work items

Paths are relative to `app/src/main/java/com/fotoxplorr/app/` unless they start with `app/`, `docs/` or the repo root.

---

### P0-01 — One visibility filter everywhere

**Why.**
- `gallery/GalleryScreen.kt:317`: `val spatialAssets = remember(state.assets) { state.assets.filterNot { it.isTrashed } }` drives Places, the map and the spatial scenes. Photos in **locked folders**, **archived** photos, and **sensitive** photos the user chose to hide all appear there.
- `gallery/DestinationBrowserScreen.kt:489–490`: the Calendar gets the unfiltered `state.assets`.
- `gallery/SettingsTabs.kt:91–92`: the Settings preview uses `state.assets.firstOrNull { !it.isVideo }`, which can be a locked photo.

**Do.**
1. In `gallery/GalleryProjection.kt`, add one function that is the single definition of "may be shown right now":
   ```kotlin
   fun browsableAssets(
       assets: List<MediaAsset>,
       archivedIds: Set<MediaId>,
       sensitiveIds: Set<MediaId>,
       lockedFolders: Set<String>,
       unlockedFolders: Set<String>,
       hideSensitive: Boolean,
   ): List<MediaAsset> // not trashed, not archived, isPrivacyVisible(...), and (!hideSensitive || id !in sensitiveIds); keeps input order
   ```
   Re-express `everydayAssets` on top of it. The goldens must not change; if one does, the refactor is wrong.
2. In `GalleryScreen`, split the one list into two:
   - **Index input** (for `geoRepository.indexMissing`): all non-trashed assets. Unlocking a folder must not need a re-index.
   - **Display list** (map, Places, compass, 3D scenes): `browsableAssets(...)`.

   Key the `remember` on every input: assets, archived, sensitive, locked, unlocked, `hideSensitive`.
3. Calendar and Settings preview: use the display list.
4. **Audit every consumer.** Grep `state.assets` and every composable parameter named `assets` in `gallery/`, `spatial/`, `experience/`, `ai/`, `recognition/`, `curate/` and `search/`. Include at least `DestinationBrowserScreen.kt:294, 471, 554–556`, the similarity explorer, the People and Pets strips, and search. Each must either:
   - use a list derived from `browsableAssets`/`everydayAssets`/`destinationAssets`, or
   - carry a one-line comment saying why it may see everything (for example counts in `GalleryInfoRoom`, or indexers).

   Put the audit as a table in the progress log.

**Tests.**
- In `GalleryProjectionV2Test`: a locked-folder asset, an archived asset and a hidden-sensitive asset never appear in `browsableAssets`, and appear again when unlocked, un-archived or un-hidden.
- All existing goldens unchanged.

**Done when:** no screen that draws photos takes an unfiltered list without a written reason, and the tests pass.

**Device check:** lock a folder that has geotagged photos → Places shows none of them. Unlock → they appear.

---

### P0-02 — Original access helper; location reading that works on Android 10+

**Why.**
- Android hides location in photos from scoped-storage apps unless the app holds `ACCESS_MEDIA_LOCATION` **and** reads through `MediaStore.setRequireOriginal(uri)` ([docs](https://developer.android.com/training/data-storage/shared/media), "Location information in media files").
- The app requests the permission but never calls `setRequireOriginal`: `git log -S setRequireOriginal` finds nothing. So on Android 10+:
  - `GeoMetadataRepository.readImageLocation` (`spatial/GeoMetadataRepository.kt:126`) and `readImageExifDetails` (`viewer/PhotoDetailRoom.kt:1081`) probably see **no GPS for any photo**.
  - Every photo is then cached as "no location" forever: failures and absences are both stored as `GeoRow(id, null)` (`:65–69`) and skipped from then on (`:50`).
- Video location comes through `MediaMetadataRetriever`. The docs say that needs no extra permission, so leave it alone.

**Do.**
1. New `media/OriginalAccess.kt`:
   ```kotlin
   /** Uri to READ for location-bearing metadata. Never use for share/zip/export paths. */
   fun Context.uriForLocationRead(uri: Uri): LocationReadUri
   data class LocationReadUri(val uri: Uri, val original: Boolean)
   ```
   - API ≥ 29, `ACCESS_MEDIA_LOCATION` granted, and a MediaStore URI (authority `media`) → `MediaStore.setRequireOriginal(uri)` with `original = true`.
   - Otherwise return the uri unchanged with `original = false`.
   - Callers must catch `UnsupportedOperationException`/`SecurityException` on open, and retry once with the plain uri, `original = false`.
2. `GeoMetadataRepository`:
   - Read images through `uriForLocationRead`.
   - Replace "row or null" with three outcomes: `Located`, `NoLocation` (the file was read and has no GPS) and `Unreadable` (exception, or couldn't open).
   - Persist `Located` always. Persist `NoLocation` only when the read used original access **or** the API level is below 29 (no redaction there). Otherwise treat it as unknown and re-check once permission is granted.
   - Don't persist `Unreadable`. Keep an in-memory attempt counter per id so one pass doesn't retry in a loop.
   - **Schema:** bump `GeoOpenHelper` 1 → 2 additively. Add `checked_with_original INTEGER NOT NULL DEFAULT 0` and `source_revision INTEGER` (`dateModifiedSeconds`). On upgrade, **delete rows that have no location** so they get re-read once with original access. Keep `Located` rows and manual pins.
   - Stop re-reading the whole table after every 32-item batch (`:73`, O(n²)). Keep an in-memory map and apply each batch's delta to the `StateFlow`.
   - Add `pruneTo(allCatalogueIds: Set<MediaId>)`, called with **all** catalogue ids, trashed included. It removes rows for deleted media.
3. Video location parsing (`:151`, `VIDEO_LOCATION_PATTERN` at `:168`): make ISO 6709 parsing a pure function with tests.
   - Accept an optional altitude component.
   - Reject |lat| > 90 and |lon| > 180.
   - Treat exactly `+0.0000+000.0000` as no location.
4. `readImageExifDetails` (`viewer/PhotoDetailRoom.kt`): read through `uriForLocationRead`, and return whether the read **succeeded**. `ImageExifDetails` gets a `readOk: Boolean`.
   - The manual "Set location" picker (`:720–730`) may appear only when `readOk && latitude == null`.
   - A failed read shows "Couldn't read this photo's details", never the picker.
5. **Don't** call the helper from `share/`, `ZipExporter`, `lift/StickerExporter`, `moments/*` or any "send it out" path. Add a KDoc on `uriForLocationRead` saying so. You'll turn this into a TRAPS entry in P0-19.

**Tests.**
- The pure outcome/persistence decision, as a function (outcome, apiLevel, original) → persist?.
- ISO 6709 parsing: at least 8 cases (with/without altitude, southern and western hemispheres, garbage, zeros).
- Migration: Robolectric test that creates a v1 DB with located, null and manual rows, opens it with the new helper, and asserts null rows are gone and the other two stay.

**Device checks:**
- Android 10+ phone with geotagged camera photos: open Places → photos appear. Before this change they probably didn't; say so in the checklist.
- Revoke "Allow access to location in media" in system settings → Places shows nothing new, and nothing is stored as "no location".

---

### P0-03 — One oriented, bounded bitmap decoder

**Why.**
- Nine places decode pixels with `BitmapFactory` and **ignore EXIF orientation**: `grep -rn -i orientation editor/ share/ lift/ recognition/` finds only a tag list in `SharePreparer`.
- Photos stored with a rotation tag, which many cameras write, therefore show **sideways in the editor** and are **saved sideways**. Coil (the grid and viewer) does honour orientation, so the viewer looks right and the bug only shows once you edit or share.
- Every copy also uses power-of-two `inSampleSize`, which lands **below** the target. `editor/EditorScreen.kt:697–722` exports a 9,000 px photo at 4,500 px.

**Do.**
1. New `media/BitmapDecoding.kt` with **one** public entry point:
   ```kotlin
   data class DecodeLimits(val maxLongEdge: Int, val maxPixels: Long)
   suspend fun decodeUpright(context: Context, uri: Uri, limits: DecodeLimits): DecodedBitmap?
   data class DecodedBitmap(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int, val downscaled: Boolean)
   ```
   - The output is **always upright**: EXIF orientation has been applied to the pixels, so callers write `Orientation = 1` whenever they write EXIF.
   - **API ≥ 28:** use `ImageDecoder` with `setTargetSize` for an exact target size, `ALLOCATOR_SOFTWARE`, and mutable when asked.
     - **Check whether `ImageDecoder` already applies EXIF orientation** (read `ImageDecoder`'s KDoc and AOSP `ImageDecoder.cpp`/`SkAndroidCodec` origin handling). Write down what you find in **Decisions** with the source.
     - If it does, don't rotate again. If not, rotate.
     - Try a Robolectric NATIVE test with a JPEG whose Orientation is 6. If Robolectric can't run `ImageDecoder`, say so and add it to the device checklist.
   - **Fallback** (API 26–27, or any `ImageDecoder` exception): `BitmapFactory` bounds pass (stream, not `readBytes()`), then the **largest** power-of-two `inSampleSize` whose result is still ≥ the target. Then `Bitmap.createScaledBitmap` to the exact target. Then apply all 8 EXIF orientations with a `Matrix`; read orientation with `ExifInterface` from a second stream.
   - Pure helpers, `internal` and unit-tested:
     - `targetSize(srcW, srcH, limits): Pair<Int, Int>` (preserves aspect, satisfies both limits, never upscales);
     - `sampleSizeFor(srcW, srcH, targetW, targetH): Int`;
     - `orientationTransform(orientation): OrientationOps`, with a rotation of 0/90/180/270 and a flip flag, for all 8 EXIF values.
2. Replace every private decode helper with `decodeUpright`:
   - `editor/EditorScreen.kt` (preview and full size);
   - `share/SharePreparer.kt` (`decodeBounded`);
   - `share/ShareOptionsSheet.kt` (preview);
   - `viewer/PhotoDetailRoom.kt:983–995` (palette — use a small limit such as 256 px, and stop reading the whole file);
   - `lift/LiftOverlay.kt:332–337`;
   - `recognition/RecognitionIndexer.kt:309–321` (fallback);
   - `ai/SimilarityIndexer.kt:131–142` (the fallback has **no** bound today);
   - `spatial/SpatialOpenGlScene.kt:271–273` and `experience/PhotoWallRenderer.kt:263–265` (fallbacks).

   Keep each call site's existing size budget. Name each as a constant next to the call.
3. Editor export (`MAX_EXPORT_EDGE = 8192`): keep 8192 as the ceiling and add a pixel budget of `64_000_000L`. The result must now be exact: 9,000 × 6,000 → 8,192 × 5,461. When `downscaled` is true, the existing "saved at reduced size" message must state the pixel size it saved at.

**Tests:** pure helper tests (at least 12 cases, all 8 orientations included), plus the Robolectric orientation test if it's feasible.

**Done when:** `grep -rn "BitmapFactory.decode" app/src/main` returns only `media/BitmapDecoding.kt` and places that decode **bounds only** (each with a comment).

**Device check:** a portrait photo from a camera that writes an EXIF rotation, for example a Samsung, opens upright in the editor, saves upright, and shares upright with a frame.

---

### P0-04 — Image shares: strip that fails closed, per-item results

**Why.**
- `share/SharePreparer.kt:170–176`: `stripCommonExif` wraps `ExifInterface.saveAttributes()` in `runCatching` and ignores the result. ExifInterface can only **write** JPEG, PNG and WebP. For HEIC, HEIF, AVIF, DNG, RAW and TIFF the save fails silently and the file goes out unchanged.
- Even for JPEG only 16 tags are cleared. XMP location, IPTC, maker notes, serial numbers, the embedded thumbnail and appended trailers stay. Motion photos carry a whole MP4 after the JPEG, with its own location.
- `items.map { prepareOne(...) }` (`:68`) throws on the first bad item, failing the whole share (`:102`).
- The rendered path (frame or watermark) always writes JPEG, destroying transparency. It uses power-of-two downscaling, and when "keep metadata" is on it copies the orientation tag back onto already-drawn pixels.

**Context you must keep in mind.** On Android 10+ the OS probably already hides photo location from this app's reads, because it never asks for original access (see P0-02). That is not a guarantee: it doesn't apply on Android 8–9, it may not cover every format or metadata block, and it would disappear if someone added `setRequireOriginal` here. So strip explicitly, **and** never read share sources through original access (rule 0.2-9).

**Do.**
1. New `share/MetadataStripper.kt`. It must be pure Kotlin: streams and byte arrays only, no Android imports. Detect the format by **magic bytes**, not MIME:

   | Format | Keep | Drop | Notes |
   |---|---|---|---|
   | **JPEG** (`FF D8 FF`) | SOI, `APP0` (JFIF/JFXX), `APP2` whose identifier is `ICC_PROFILE\0` (all chunks), `APP14` (`Adobe` — needed to decode CMYK/YCCK), DQT, DHT, SOF*, DRI, SOS and entropy-coded data, EOI | Every other `APPn` (`APP1` Exif/XMP/extended XMP, `APP2` MPF, `APP13` IPTC/Photoshop, …) and `COM` | Stop after the **first** image's EOI and drop everything appended after it (Ultra HDR gain map, motion-photo MP4, Samsung trailer). In entropy-coded data, `FF 00` is stuffing and `FF D0–D7` are restart markers, not segment boundaries. Progressive JPEGs have several SOS segments with DHT between them. Handle both. |
   | **PNG** (`89 50 4E 47 0D 0A 1A 0A`) | `IHDR`, `PLTE`, `IDAT`, `IEND`, `tRNS`, `gAMA`, `cHRM`, `sRGB`, `iCCP`, `sBIT`, `pHYs`, `bKGD`, `hIST`, `sPLT`, `acTL`, `fcTL`, `fdAT` | `eXIf`, `tEXt`, `zTXt`, `iTXt`, `tIME`, and any other ancillary chunk | Copy kept chunks byte-for-byte, CRC included. An **unknown critical** chunk (uppercase first letter) → return "unsupported" (fail closed). |
   | **WebP** (`RIFF….WEBP`) | Everything else | `EXIF` and `XMP ` chunks | Clear the VP8X flag bits for EXIF (0x08) and XMP (0x04), and recompute the RIFF size. Buffering is fine; refuse over 64 MB. |
   | **GIF** | Image data, `NETSCAPE2.0`/`ANIMEXTS1.0` application extensions (looping) | Comment extensions (`21 FE`) and every other application extension (XMP lives in `XMP DataXMP`) | Walk the block structure. |
   | **BMP** | Whole file | — | No location metadata; copy as is. |
   | **Anything else** | — | — | Return "unsupported". |

   API sketch: `fun strip(input: InputStream, output: OutputStream): StripResult` where `StripResult` is `Stripped(format)` or `Unsupported(reason)`.
2. `SharePreparer.prepare` returns per-item outcomes: a `sealed interface PreparedItem { Ready(uri, mimeType, note: String?) ; Failed(asset, reason) }`.
   - Share every `Ready`.
   - If some failed, show: "*N* items couldn't be prepared without their location and weren't shared." Name the first, and add "and *k* more" if there are more.
   - If all failed, show an error.
3. The plain path (no frame or watermark), with `stripMetadata = true`:
   - Stream the source through `MetadataStripper`.
   - If the source orientation was not 1, write `Orientation` alone back with `ExifInterface(file)`. JPEG only: ExifInterface can add an APP1 to a JPEG that has none.
   - `Unsupported` (HEIC/AVIF/TIFF/RAW/JXL/PSD/…) → **re-encode**: `decodeUpright` with limits (8192, 48 MP) → JPEG q 95, or PNG if `bitmap.hasAlpha()`. Note: "converted to JPEG to remove its location".
   - Can't decode → `Failed`.
4. **Verify every stripped or re-encoded output before handing it out:**
   - For JPEG/PNG/WebP, `ExifInterface(output).latLong == null`.
   - The output bytes contain none of the ASCII strings `GPSLatitude`, `GPSLongitude`, `exif:GPS`, `LocationShown`, `LocationCreated`.
   - Either check fails → `Failed`. Never hand out an unverified file.
5. The rendered path (frame or watermark):
   - Decode with `decodeUpright`, limits (`MAX_SHARE_EDGE` = 3200, 16 MP). The result is exact, not power-of-two.
   - Encode PNG when the frame is STAMP **or** the source bitmap has alpha; JPEG otherwise.
   - **Never** copy `TAG_ORIENTATION` back (the pixels are upright now). When `stripMetadata = false`, copy only `DATETIME_ORIGINAL`, and write `Orientation = 1`.
6. **Animated images** (GIF, animated WebP/AVIF, APNG — use P0-14's index once it exists; until then GIF and WebP by MIME) and **videos** are never rendered. Strip them (videos: P0-05) and send them without frame or watermark.
   - Log owner question 1: "Should free-tier shares of animations and videos carry the watermark? That needs re-encoding them. Current behaviour: no watermark on those."
7. Share MIME type (`FotoXplorrActivity.kt:696` and `commonShareType` at `:1189`): derive it from the **prepared** items' MIME types — all `image/*` → `image/*`; all `video/*` → `video/*`; otherwise `*/*`. Stop forcing `image/*` whenever a render happened.

**Tests** (pure JVM, synthetic byte arrays built in the test, plus Robolectric where noted):
- **JPEG:** a file with APP0 + APP1 Exif (with GPS) + APP1 XMP + APP2 ICC + APP13 + COM + progressive SOS segments + a trailer after EOI → the output keeps APP0, ICC and scan data byte-identical and ends exactly at the first EOI. A Robolectric NATIVE decode of the output has the same dimensions.
- **Real JPEG:** make one with `Bitmap.compress` (Robolectric NATIVE), add GPS and XMP GPS with ExifInterface, strip, then assert `latLong == null` and the ASCII scan is clean.
- **PNG:** `eXIf` and `iTXt` (XMP) removed; `iCCP` and `acTL` kept; every kept chunk's CRC unchanged; an unknown critical chunk → Unsupported.
- **WebP:** EXIF and XMP removed, VP8X flags cleared, RIFF size correct.
- **GIF:** comment and XMP application extensions removed; NETSCAPE loop kept.
- **Batch:** one undecodable item in a batch of 3 → 2 Ready, 1 Failed.

**Device checks:**
- Share a geotagged JPEG, HEIC and PNG to yourself with the default settings, then check each in an EXIF viewer: no GPS anywhere.
- Share a Samsung or Google motion photo: the received file is a still JPEG.
- A portrait photo with a frame arrives upright.

---

### P0-05 — Video shares: remove location by remuxing

**Why.** `SharePreparer.kt:76–84` excludes videos from processing (`renderable = !asset.isVideo && …`) and copies the bytes as they are, GPS included. The share sheet claims location is removed.

**Do.**
1. Pull `moments/ClipExporter.kt`'s stream-copy core into new `video/StreamCopyRemuxer.kt`:
   ```kotlin
   fun remux(context: Context, source: Uri, target: File, rangeUs: LongRange?, rotationDegrees: Int): RemuxResult
   ```
   - Copy **only** the first video track and the first audio track. Drop every other track: metadata tracks such as Apple `mebx`, timed metadata, subtitles.
   - Call `setOrientationHint(rotationDegrees)`. **Never** call `setLocation`.
   - Keep `ClipExporter`'s keyframe seek, its `muxerBufferFlagsFor` translation (TRAPS #24) and its buffer sizing.
   - `ClipExporter.exportClip` then calls `remux` with its range. Its behaviour must be unchanged.
   - Move `findRotationDegrees` to `video/VideoRotation.kt` so the transcoder (P0-11) can use it too.
2. **Container choice** is a pure function, `containerFor(videoMime, audioMime?): Container?`:
   - MP4 for what `MediaMuxer`'s MPEG-4 output accepts.
   - WebM for VP8/VP9 with Vorbis/Opus.
   - `null` otherwise.
   - **Check the codec × container × API-level table in the `MediaMuxer` reference before coding it** (AV1, HEVC and VP9-in-MP4 differ by API level). Cite it in a comment.
   - Output extension and MIME follow the container.
3. In `SharePreparer`, for videos with `stripMetadata = true` → remux into the share directory. `null` container or a muxer exception → `Failed` with reason "This video's format can't be shared without its location yet — turn off *Remove location* to send the original."
4. Verify: after remuxing, `MediaMetadataRetriever.METADATA_KEY_LOCATION` on the output is null. Otherwise → `Failed`.

**Tests:** `containerFor` truth table; the track-selection function (given a list of track MIME types → which get copied).

**Device check:** share a geotagged phone video → the received file has no location, plays, and is the right way up.

---

### P0-06 — Zip export works and follows the share metadata policy

**Why.**
- `share/ZipExporter.kt:66` asks for authority `"${packageName}.fileprovider"`, but the manifest declares `${applicationId}.files` (`app/src/main/AndroidManifest.xml`, `<provider … android:authorities="${applicationId}.files">`). **Every zip export throws** — after the whole archive has been written.
- A missing source silently becomes an empty entry (`:57–59`).
- The comment says "DEFLATE at level 0", but no level is set.
- Zips also ship originals with GPS, bypassing the default "remove location".

**Do.**
1. New `share/FileProviderAuthority.kt` with `fun fileProviderAuthority(context: Context) = "${context.packageName}.files"`. Use it in **every** `FileProvider.getUriForFile` call: `SharePreparer`, `ZipExporter`, `lift/StickerExporter`, `moments/MomentFrameExporter`, `moments/ClipExporter`.
2. **Test:** a JVM test that parses `app/src/main/AndroidManifest.xml`, finds the `FileProvider` `<provider>`, and asserts its `android:authorities` is `${applicationId}.files`. Model it on the existing `ShareDirectoryTest`.
3. Build zip entries from `SharePreparer`'s per-item preparation, **plain path only**: never a frame or watermark, with `stripMetadata` taken from the saved share preference. Skip `Failed` items and report them the same way as P0-04. Never write an empty entry.
4. `zip.setLevel(Deflater.NO_COMPRESSION)`.
5. Archive name: say "items" instead of "photos" when the selection includes videos.
6. Log owner question 2: "Zip exports carry no watermark (unchanged). Should they?"

**Device check:** select 5 photos (1 HEIC) and 1 video → Zip → share to Files → the archive opens with 6 entries and no GPS.

---

### P0-07 — Edited copies keep their metadata; saves land in allowed folders

**Why.**
- `editor/EditedCopyWriter.kt` `save` (`:34–80`) and `overwrite` (`:95–108`) encode the bitmap and write **no EXIF, XMP or date taken**. A saved copy therefore sorts as "now", and "Replace original" permanently destroys the photo's metadata.
- Saves reuse the source's `RELATIVE_PATH` (`:47–51`; `video/VideoConversionWriter.kt:54–56`). MediaProvider only accepts certain top-level folders per collection, so a copy of a WhatsApp image (`Android/media/com.whatsapp/…`) or a `Download/` file fails to save.

**Do.**
1. New `metadata/ExifCopier.kt`:
   - `COPIED_TAGS`: every androidx `ExifInterface.TAG_*` except those in `EXCLUDED_TAGS`.
   - `EXCLUDED_TAGS`: orientation (written as 1), image width/length, pixel X/Y dimension (written as the new size), every thumbnail tag (`TAG_THUMBNAIL_*`, `TAG_JPEG_INTERCHANGE_FORMAT*`), strip/tile offsets and byte counts, `TAG_MAKER_NOTE` (it contains absolute offsets that break when moved), and `TAG_XMP` (handled separately).
   - **Completeness test:** a unit test that reflects over `ExifInterface::class.java.fields` whose name starts with `TAG_` and asserts every one is in exactly one of the two lists. It fails on an AndroidX upgrade until someone decides about the new tags.
   - XMP: copy the packet. If it contains `tiff:Orientation`, set it to 1 with `XmpPacket`; if that doesn't parse, drop only that property; if the packet doesn't parse at all, copy it as is.
2. **Save flow:**
   1. Encode to a temp file in `cacheDir/edit-staging/`.
   2. Read the source's EXIF and XMP through `uriForLocationRead` (P0-02). The user's own edited copy keeps its GPS.
   3. Apply them to the temp file with `ExifInterface(tempPath)`.
   4. Insert the MediaStore row with `IS_PENDING = 1` and `DATE_TAKEN = source.dateTakenMillis`.
   5. Stream the temp file in and set `IS_PENDING = 0`.
   6. Re-assert `DATE_TAKEN` with an update if the query shows it changed.
   7. Delete the temp file.
3. **Overwrite flow:**
   1. Read the source metadata into memory **before** anything is truncated.
   2. Encode to a temp file and apply the metadata there.
   3. Re-open the temp file and check that it decodes with the expected size (bounds only).
   4. Only then stream it into the original with mode `"wt"`.
4. **Destination.** Add a pure `mediaStoreRelativePath(sourceRelativePath: String?, kind: MediaKind): String`.
   - If the source's top-level folder is allowed for that collection, keep the full path; otherwise use `Pictures/Foto Xplorr/`, `Movies/Foto Xplorr/` or `Music/Foto Xplorr/`.
   - Allowed today: Images → `DCIM`, `Pictures`; Video → `DCIM`, `Movies`, `Pictures`; Audio → `Music`, `Podcasts`, `Audiobooks`, `Ringtones`, `Notifications`, `Alarms`, and `Recordings` on API 31+.
   - **Check these lists against MediaProvider's rules** (the "Primary directory … not allowed" check) and cite the source in a comment.
   - Use it in `EditedCopyWriter`, `VideoConversionWriter` and `AudioConversionWriter`. `StickerExporter` already uses `Pictures/`.
   - `VideoConversionWriter` also sets `DATE_TAKEN` from the source.

**Tests:**
- The `ExifCopier` completeness test.
- A Robolectric NATIVE round trip: source JPEG with GPS, DateTimeOriginal, Make/Model, XMP (`dc:subject`) and Orientation 6 → edited copy through the new flow (using a file path instead of MediaStore) → all copied, Orientation 1, dimensions updated.
- `mediaStoreRelativePath` cases: `DCIM/Camera/`, `Pictures/Screenshots/`, `Download/`, `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/`, `null`, `Movies/`, `Documents/`.

**Device checks:**
- Edit a geotagged camera photo and save a copy → it sits next to the original in the timeline and shows the same camera, date and location.
- Edit a WhatsApp image → the copy saves into `Pictures/Foto Xplorr/`.

---

### P0-08 — Safe metadata writes

**Why.**
- `metadata/MetadataWriter.kt:64` opens the **original** `"rw"` and calls `saveAttributes()`: a full rewrite with no backup. On Android 10+ it may also be writing through a location-redacted view (P0-02).
- XMP is read with `exif.getAttribute(TAG_XMP)` (`:213`). `TAG_XMP` is a BYTE-format tag, so the text may be decoded as ASCII, turning "Zürich" or CJK keywords into U+FFFD on the next save. Medium confidence — **prove or disprove it with a test first.**
- HEIC and RAW writes ask for write consent and only then fail.
- One pending-edit slot (`FotoXplorrActivity.kt:417–420`), while the detail room commits creator and copyright separately (`viewer/PhotoDetailRoom.kt:533–537`), so one edit can be lost.
- "Clear location" removes only EXIF GPS (`MetadataWriter.kt:192–199`) and leaves XMP GPS.

**Do.**
1. **Writable check before consent.** Add `fun isMetadataWritable(asset): Boolean`: JPEG, PNG or WebP, by magic bytes, falling back to MIME. The detail room's edit controls, bulk metadata edits and "Set/Clear location" (file variant) must check it **before** `MediaStore.createWriteRequest`. If it fails, show "Foto Xplorr can't write metadata into *FORMAT* files yet" and don't open the consent screen.
2. **Original access for writes.** On API 29+, if `ACCESS_MEDIA_LOCATION` isn't granted, refuse file metadata writes: "Allow access to photo locations to edit metadata safely". The reason is that writing through a redacted view could erase GPS. Otherwise open through `setRequireOriginal`.
   - **Check what MediaProvider does when a redacted file is opened for writing** (look at MediaProvider's `openFile`/redaction code). Log it under Decisions with the source.
3. **Temp, verify, then write:**
   1. Copy the original's bytes into `cacheDir/metadata-staging/`.
   2. Apply the edit there with `ExifInterface(tempPath)`.
   3. Verify: the temp file decodes (bounds) to the same width and height, and a fresh `ExifInterface(temp)` reads back every edited field.
   4. Stream the temp file into the original with `"wt"`.
   5. Delete the temp file only after success.

   Document the remaining risk (interruption mid-stream) in the KDoc.
4. **UTF-8 XMP.**
   - Write the test first: a Robolectric NATIVE JPEG with a hand-built `APP1` XMP packet containing `dc:subject` "Zürich · 東京 · José". Apply a rating edit. Assert the keyword bytes are unchanged UTF-8.
   - If the test fails on today's code, fix reading: raw bytes, then UTF-8. Check whether ExifInterface 1.4.1 has `getAttributeBytes(TAG_XMP)` (inspect the AAR in the Gradle cache with `javap`); if it doesn't, find the raw-bytes route.
   - Fix writing too: check how `setAttribute(TAG_XMP, String)` encodes, and use the bytes route if it isn't UTF-8.
   - If the test passes on today's code, record that under Decisions and keep the test.
5. **Clear location** also removes XMP location properties through `XmpPacket`: `exif:GPS*`, `Iptc4xmpExt:LocationShown`, `Iptc4xmpExt:LocationCreated`, `photoshop:City/State/Country` only if they came with GPS (don't touch them otherwise; say so in the KDoc). Test it.
6. **One pending edit.** Add `MetadataEdit.merge(other)`: later non-null fields win; keyword sets union unless a field says replace. The Activity keeps one merged pending edit. Edits that arrive while a write is running are queued and merged into the next write. Test `merge`.

**Tests:** the ones listed above, plus the existing `MetadataWriterTest` still passing.

**Device checks:**
- Add a star rating to a Lightroom-exported JPEG with non-English keywords → the keywords survive (check in another app).
- A HEIC's metadata controls are disabled, with the message.

---

### P0-09 — Scanning and app state survive the Activity

**Why.**
- `FotoXplorrActivity.kt:755` sends `scanRequests.trySend(true)`, a **full** scan, every time the composition starts. The manifest declares no `configChanges`, so **every rotation** recreates the Activity, re-creates `SqliteMediaRepository` (`:146`) and runs a full scan.
- A full scan ends in `repository.replaceAll(discovered)` (`media/MediaIndexer.kt:56`), which **deletes every row and re-inserts them all**, even though the batches were already upserted during the scan.
- There is no `rememberSaveable` or ViewModel anywhere, so rotating closes the open photo.
- `LibraryBackgroundWork` makes its own `SqliteMediaRepository`, a second in-memory mirror of the same database (the same failure family as TRAPS #26).
- A partial-access grant, or a MediaStore hiccup, turns into mass deletion from the catalogue (TRAPS #8).

**Do.**
1. New `LibraryRuntime.kt` in the root package: a process-wide singleton, initialised in `FotoXplorrApplication.onCreate`. It owns:
   - the one `SqliteMediaRepository` and the one `MediaIndexer`;
   - the conflated scan-request channel and its consumer loop, running on a process scope (`SupervisorJob() + Dispatchers.Default`);
   - `scanState: StateFlow<ScanState>`. Move `ScanState` so both the runtime and the UI can use it;
   - the `MediaStoreChangeObserver` subscription, registered once when permission is first known to be granted;
   - `fun ensureInitialScan()`, which requests a **full** scan only once per process;
   - `fun requestScan(userRequested: Boolean)`.
2. `FotoXplorrActivity`:
   - Get repository and indexer from the runtime instead of `remember { … }`.
   - Replace the scan `LaunchedEffect` loop with `LaunchedEffect(permissionGranted) { if (permissionGranted) runtime.ensureInitialScan() }` and collect `runtime.scanState`.
   - Every existing `scanRequests.trySend(x)` becomes `runtime.requestScan(x)`.
   - `LibraryBackgroundWork` uses `LibraryRuntime.repository.awaitLoaded()`.
   - Audio keeps its own pipeline in this item, but must not start a full audio scan per composition either. Give it the same once-per-process guard.
3. **Sweep, not rewrite.**
   - Replace `replaceAll` with `removeAllExcept(keep: Set<MediaId>)` in `MediaRepository` and `SqliteMediaRepository`: delete only missing ids, chunked (`SQLITE_BIND_LIMIT`), and update the mirror.
   - Rename the test in `MediaIndexerTest` that pins "a delta never calls replaceAll", keeping its meaning: a delta never sweeps.
   - Update TRAPS #1's wording to the new method name. The invariant stays word for word otherwise.
4. **Sweep guard (TRAPS #8).** Add a pure `SweepPolicy.shouldSweep(catalogueSize, missingCount, userRequested, partialAccess): Boolean`:
   - never while partial media access is active;
   - otherwise, when not user-requested, only if `missingCount ≤ max(200, catalogueSize / 4)`;
   - a user-requested refresh (the shake) may always sweep, except under partial access.

   `MediaIndexer` takes the policy as a constructor parameter. When the guard refuses, log it and leave the rows in place.
5. **Manifest:** on `.FotoXplorrActivity` add `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation|uiMode|density"`. That covers rotation, USB keyboards and hubs, and desktop mode. Check nothing reads configuration-qualified resources at Activity creation that would now go stale (the app is Compose; `LocalConfiguration` updates without recreation). Log what you checked.
6. **Process death:** `rememberSaveable` for the open viewer (the selected asset id as a `Long`, and the viewer list as a `LongArray` of ids, rebuilt from the repository once it has loaded) and for the asset being edited (its id).

**Tests:**
- `SweepPolicy`: at least 6 cases.
- `MediaIndexerTest`:
  - a full pass removes only the missing ids;
  - a guard refusal leaves them in place;
  - a delta pass never sweeps.
- `removeAllExcept` mirror consistency, following the existing `CatalogueMergeTest`.

**Device checks:**
- Open a photo and rotate the phone → the photo stays open, and there's no "Indexing" banner.
- Cold start with 20k+ items → one full scan, then deltas only for the rest of the session.
- Plug in a USB keyboard or hub while viewing → nothing resets.

---

### P0-10 — Audio permission and selection

**Why.**
- `READ_MEDIA_AUDIO` is neither declared nor requested (`FotoXplorrActivity.kt:1176–1184` requests image and video only). On Android 13+ the Audio library shows only files the app created itself.
- `audio/AndroidAudioMediaStoreScanner.kt:156` filters `IS_MUSIC != 0`, which excludes recordings, podcasts and audiobooks, despite the comment above it.

**Do.**
1. Declare `READ_MEDIA_AUDIO`. `READ_EXTERNAL_STORAGE` with `maxSdkVersion=32` already covers older versions. Don't add it to `mediaReadPermissions()`: audio access must never count as "media access granted" for the gallery.
2. Add `hasAudioPermission()`. Request it only when the user opens the Audio library. The empty state shows "Allow access to audio" with a button that launches the request. The audio scan starts only after the grant.
3. **Selection:** exclude ringtones, notifications and alarms instead of requiring music: `IS_RINGTONE = 0 AND IS_NOTIFICATION = 0 AND IS_ALARM = 0`, keeping the delta clause. Update `AndroidAudioMediaStoreScannerSelectionTest` and the comment.

**Device check:** Android 13+ → open Audio → grant → voice recordings and music both appear.

---

### P0-11 — Video transcode correctness

**Why.**
- `video/VideoTranscoder.kt:343–355` (`muxTracks`) never calls `setOrientationHint`, so portrait phone videos come out sideways.
- `video/EncodedTrack.kt` keeps the **entire** compressed output in memory. The limits (10 min at up to 20 Mbps) allow about 1.5 GB, so 4K runs out of memory after a minute or two.
- `video/gl/EglCore.kt:28–35` lacks `EGL_RECORDABLE_ANDROID`.
- `audio/AudioTranscoder.kt:35–36` uses the **input's** sample rate and channel count, not the decoder's output format. HE-AAC (SBR) and parametric stereo decode at a different rate or channel count.
- Speed changes relabel the AAC sample rate (`videoeditor/VideoEditRecipe.kt:75`, `speedAdjustedSampleRate`): 1.5× turns 44,100 into 66,150 and 48,000 into 72,000, which are not valid AAC rates, so exports with sound at 1.5× most likely fail.
- There is no HDR or size pre-check.

**Do.**
1. **Rotation:** read it with `video/VideoRotation.kt` (from P0-05) and call `muxer.setOrientationHint(rotation)` before `start()`.
2. **Spill to disk.** Replace `EncodedTrack.samples: List<EncodedSample>` with a disk-backed `SampleStore`:
   - sample bytes appended to a temp file in `cacheDir/transcode-spill/`;
   - in memory, only parallel primitive arrays (offset `Long`, size `Int`, pts `Long`, flags `Int`), growing like an `ArrayList`;
   - `writeSamples` reads each sample back into one reusable direct `ByteBuffer` sized to the largest sample;
   - the temp file is deleted in `finally`, even on cancellation.

   Before starting, check free space with `StatFs(cacheDir)`: need at least 2 × (max bitrate × duration) + 100 MB, or fail with a clear message.

   Keep the 10-minute cap for now. Update the `EncodedTrack` KDoc and add an **ADR-008 addendum** ("Spill instead of memory, 2026-09").
3. **EGL:** add `EGLExt.EGL_RECORDABLE_ANDROID, 1` to the config attributes. If `eglChooseConfig` finds nothing, retry without it and log.
4. **Pre-flight checks**, before any decoding; each failure is a clear user message:
   - The source is HDR: the track format's `KEY_COLOR_TRANSFER` is `COLOR_TRANSFER_ST2084` or `COLOR_TRANSFER_HLG`, or the profile is an HDR profile. Message: "HDR video conversion isn't supported yet".
   - The chosen AVC encoder's `VideoCapabilities.isSizeSupported(w, h)` is false. Round odd sizes down to even first.
5. **Audio output format:** handle `INFO_OUTPUT_FORMAT_CHANGED` in `decodeToPcm` and take sample rate, channel count and `KEY_PCM_ENCODING` from the decoder's output format. Float PCM → convert to 16-bit.
6. **Speed without invalid sample rates.** Delete `speedAdjustedSampleRate`. Add a pure `resamplePcm16(interleaved: ShortArray, channels: Int, speed: Float): ShortArray`:
   - linear interpolation per channel;
   - output frame count `round(inFrames / speed)`.

   Encode at the **source's own** (valid) sample rate. Pitch still follows speed, like tape, so the behaviour described in ADR-008 is unchanged. Only the mechanism changes; say so in the ADR addendum. Update or replace the tests that covered `speedAdjustedSampleRate`.
7. `AudioConversionWriter` decodes a whole file into memory with no cap. Add a 60-minute cap with a clear message.

**Tests:**
- `SampleStore` round trip: 10k samples of random sizes → same bytes, pts and flags back, and the file is deleted afterwards.
- `resamplePcm16`: length, first and last frames, a constant signal stays constant, and stereo channels don't bleed into each other.
- The pre-flight decision as a pure function: (transfer, profile, w, h, supported?) → ok or reason.

**Device checks:**
- Convert a portrait phone video to MP4 → upright.
- A 3-minute 4K clip converts without a crash.
- 1.5× with sound exports, and the sound plays fast and higher-pitched.
- An HDR video shows the refusal message.

---

### P0-12 — Recognition: record failures, stop the full reloads

**Why.**
- `recognition/RecognitionIndexer.kt:102–107`: an analysis failure increments `failed` and stores **nothing**, so the asset stays pending forever and is retried on every settled scan and every background wake (every 30 minutes, with no charging requirement by default).
- `RecognitionStore.upsert` (`:67–71`) calls `reload()`, a full-table read, after every 24-photo batch. The recognition `StateFlow` is a key of `GalleryScreen`'s projection memo, so each batch re-filters the whole library on the main thread.
- `index()` calls `removeMissing` with **non-trashed images only** (`:59–60`), so trashing a photo deletes its faces, labels and OCR, and restoring it has to redo them. `ai/SimilarityIndexer.kt:50` does the same.

**Do.**
1. `RecognitionOpenHelper`: bump the version by one. The upgrade **from the current version** is additive:
   ```sql
   CREATE TABLE IF NOT EXISTS recognition_failure(
     media_id INTEGER PRIMARY KEY, revision INTEGER NOT NULL,
     attempts INTEGER NOT NULL, last_attempt_ms INTEGER NOT NULL)
   ```
   Keep drop-and-recreate only for versions older than the current one. Update the comment at `:152–156` and `:310–312`.
2. On failure: upsert the failure row with the asset's `recognitionRevision()`, adding one to `attempts` if the revision is the same, otherwise resetting to 1. On success: delete it.
   - `pendingAssets` excludes assets whose failure row has the current revision and `attempts ≥ 3`. A changed file (new revision) is retried.
3. **No full reloads during a pass.** Update the in-memory `RecognitionIndex` incrementally from the upserted rows, if its structure allows it: add a `plus(rows)` and test it against `from(allRows)` for equality. Otherwise throttle `reload()` to at most once every 2 s, plus once at the end of the pass. Log which option you took.
4. `removeMissing` gets **all** catalogue ids, trashed included, in both indexers. Pending selection still skips trashed items.
5. `SimilarityIndexer` and `EmbeddingRepository`: the same failure recording (a table or a column), with the same 3-attempt rule.
6. Log owner question 3: "Background work runs on battery by default (`WorkRules` defaults). Require charging or idle by default? Unchanged for now."

**Tests:**
- Migration keeps existing rows: create a DB at the current version with rows, upgrade, assert the rows are still there.
- Failure bookkeeping: 3 failures → excluded; a revision change → included again.
- `RecognitionIndex.plus` equivalence, if you implemented it.

**Device check:** put a 0-byte `.jpg` in Pictures, run recognition twice → it's attempted at most 3 times in total, and the grid doesn't stutter during a recognition pass.

---

### P0-13 — Duplicates album keeps one of each

**Why.** `gallery/GalleryProjection.kt:244–251`, `duplicateCandidateIds`, flattens **every** member of each group into the album. "Select all → trash" therefore deletes both copies. `curate/ArchiveAdvisor.kt:117–141` uses the same key and calls them "Near-duplicate" although the key is exact size + dimensions + type.

**Do.**
1. `duplicateCandidateIds` returns all members **except one keeper per group**: earliest `dateTakenMillis`, then earliest `dateModifiedSeconds`, then smallest id. The keeper choice must be deterministic.
2. Change the album's subtitle to say these are extra copies and one of each is left out (find the string where smart-album titles and subtitles are defined).
3. `ArchiveAdvisor`: same keeper rule. The reason text becomes "Possible duplicate of *N* other(s)", not "Near-duplicate".
4. Golden: `smart:DUPLICATES` will change. Regenerate **only** that golden, following the test's own procedure, and give the before and after counts in the commit message.

**Tests:** a group of 3 → 2 candidates, and the keeper is the oldest; ties broken by id; ArchiveAdvisor follows the same rule.

---

### P0-14 — Real animation detection; the viewer animates

**Why.**
- `viewer/ViewerScreen.kt:439` calls `MediaImage(asset, …)` without `animate = true`, so GIFs and animated WebP/AVIF show their **first frame** in the full-screen viewer.
- `media/MediaAsset.kt:32–35`, `isAnimated`, is true for **every** WebP and AVIF (static ones included) and false for APNG and HEIF sequences. That drives the Animated album (`GalleryProjection.kt:176`), `is:animated` search (`GalleryScreen.kt:1810`) and grid animation, which wastes a decoder on every static WebP tile.

**Do.**
1. **Viewer:** pass `animate = true` for images the index says are animated, and for GIFs until the index knows. A static image through the animated path must still render: check that Coil's `AnimatedImageDecoder` handles a static WebP, and log the result.
2. New `formats/AnimationSniffer.kt`, pure: `fun sniff(head: ByteArray, mime: String): Boolean?`, where null means unknown.
   - **GIF:** walk the blocks and count image descriptors; ≥ 2 → true. The caller supplies up to 1 MB. If a single frame is seen before the cap → false; cap reached → true (conservative for display).
   - **WebP:** VP8X present and flag 0x02 set.
   - **PNG:** `acTL` before the first `IDAT`.
   - **HEIF/AVIF:** `ftyp` major or compatible brands include `msf1`, `hevs` or `avis`.
3. New `formats/AnimationIndex.kt`:
   - its own small `SQLiteOpenHelper` (`foto_xplorr_traits.db`, v1), table `(media_id PK, date_modified, animated INTEGER)`;
   - `observeAnimatedIds(): StateFlow<Set<MediaId>>`;
   - a `sniffPending(assets)` pass for MIME types GIF, WebP, PNG, AVIF, HEIF and HEIC whose row is missing or has a different `date_modified`. It reads ≤ 64 KB (1 MB for GIF) through the content resolver and publishes in batches.

   Start the pass from `LibraryRuntime` after each completed scan (P0-09).
4. Replace `MediaAsset.isAnimated` everywhere with membership in `animatedIds`:
   - `GalleryProjection`'s ANIMATED album takes an `animatedIds` parameter;
   - search categories;
   - the grid's `loopAnimations` animates only those ids.

   Then delete `isAnimated`.
5. Golden: `smart:ANIMATED` will change. Give the synthetic catalogue a deterministic `animatedIds` rule (for example "all GIFs"), regenerate only that golden, and explain it in the commit.

**Tests:** sniffer cases for all four formats, positive and negative, including a static WebP with VP8X but no animation flag, and an APNG whose `acTL` comes before `IDAT`.

**Device check:** a GIF and an animated WebP animate in the viewer; a static WebP isn't in Animated; an APNG is.

---

### P0-15 — `GridIndexMap`; scrubber correct with headers, hidden in masonry

**Why.** TRAPS #5 and #9 say grid item *n* must be asset *n*, and that nothing may change the grid item count before `GridIndexMap` exists. Timeline mode already breaks that:

- `gallery/GalleryScreen.kt:1051–1067` renders `TimelineScreen(showDateHeaders = true)`, which inserts one header per group (`gallery/GalleryContent.kt:275–303`).
- The edge scrubber (`:1126–1136`), the pill caption (`:1196`, `pillCaption` `:1970`) and arrow-key scrolling (`:762–765`) still treat grid index as asset index. Jumps land on the wrong month, and it gets worse with every day.
- The groups also re-order assets when the sort isn't by date, so even the asset list differs from what's drawn.
- In masonry mode (`fitToTile`) the scrubber still shows and drives a `LazyGridState` the staggered grid never uses.

**Do.**
1. New `gallery/GridIndexMap.kt`, pure:
   ```kotlin
   class GridIndexMap(groupSizes: List<Int>, hasHeaders: Boolean, trailingFooter: Boolean) {
     val itemCount: Int
     fun gridIndexOf(assetIndex: Int): Int
     fun assetIndexAt(gridIndex: Int): Int   // header → first asset of its group; footer → last asset
   }
   ```
2. Hoist the grouping: `GalleryScreen` computes `groups = remember(scrubberAssets, grouping) { timelineGroups(...) }` and passes them into `TimelineScreen`, which must stop computing its own.
   - `renderedAssets = groups.flatMap { it.assets }` is the list for scrubber stops, the pill caption, arrow keys and the list handed to the viewer on open.
   - With headers off, the map is the identity plus the existing footer.
3. **Scrubber:** `itemCount = renderedAssets.size`, `currentIndex = map.assetIndexAt(firstVisibleGridIndex)`, `onScrubTo = { gridState.scrollToItem(map.gridIndexOf(it)) }`. Apply the same mapping to the arrow keys and the pill caption.
4. **Masonry:** hide `EdgeTimelineScrubber` when `fitToTile`. The pill caption reads the staggered grid's own state if it's reachable from `GalleryScreen`; otherwise hide the position text in masonry and log it.
5. Update TRAPS #5 and #9: `GridIndexMap` now exists, and every grid whose item count differs from its asset count must go through it.

**Tests:** `GridIndexMap` with no groups, one group, many groups, footer on and off, and a round trip (`assetIndexAt(gridIndexOf(i)) == i` for all i) over random group sizes. Add a Robolectric render test of Timeline with headers if the existing render-test harness makes it cheap.

**Device check:** Timeline with day headers → scrub to a month in 2023 → the grid lands on that month, and the pill shows that month.

---

### P0-16 — Search and label fixes

**Why.**
- `search/SearchDocument.kt:121–133` `parseByteSize("500k")` returns 500: the `k` is stripped but never multiplied.
- `type:raw` (`:102–103`) matches only dng/raw/arw/cr2/nef MIME types.
- The live search document (`gallery/GalleryScreen.kt:1817–1818`) sets `camera = ""` and `iso = null`, so `camera:x` matches nothing and `-camera:x` matches everything.
- The live path's categories lack screenshot and archived; the more complete `search/GallerySearch.kt` isn't used.
- `viewer/DetailFormatting.kt:141–147` shows "HDR CAPABLE" for every HEIF/AVIF but "STANDARD" for Ultra HDR JPEGs.
- The Kind row shows truncated MIME subtypes such as "X-CANO" (`:53`).
- `formats/MediaFormat.kt` says HEIF decodes back to API 26; decoding really arrived in API 27/28.

**Do.**
1. `parseByteSize`: accept `k`/`kb`, `m`/`mb`, `g`/`gb` (decimal, as today). Tests: `500k`, `1.5m`, `2gb`, `700`, `abc`.
2. `type:raw` → `MediaFormat.classify(mime, name) is MediaFormat.Raw`. Extend `RawVariant` with NRW, 3FR, IIQ, ERF, MRW, X3F, DCR, KDC, MEF, MOS, RWL, SR2, SRF, GPR and CRW (correct vendor names; all `isLikelyDecodable = false`). **Don't** change the existing variants' decodability flags; they're untested on a device.
3. `camera:` and `iso:`: until an EXIF index exists (Phase 2), drop these terms from matching, both positive and negated, and remove them from `SearchSuggestions`. Test that `camera:x` and `-camera:x` both leave the result set unchanged.
4. **One category builder:** make the live path use the same category set as `GallerySearch`, adding screenshot and archived. Delete whichever copy becomes unused.
5. **HDR badge:** "ULTRA HDR" when the photo's XMP (already read by the detail room) declares the gain-map namespace `http://ns.adobe.com/hdr-gain-map/1.0/` (`hdrgm:`). Otherwise no dynamic-range badge. Drop "HDR CAPABLE".
6. **Kind label:** a RAW file → `"<VENDOR> RAW"` from `RawVariant.vendor`. Any other unknown subtype → the file extension in upper case (≤ 5 characters), never a truncated MIME string.
7. Fix the HEIF KDoc claim in `MediaFormat.kt` (API 28 is the dependable floor; see the platform notes in Appendix B).

---

### P0-17 — Migrate V1 data; remove dead Java

**Why.** `CatalogStore.java` (preferences file `"catalog"`) held V1 favourites (`favorite_<id>`), tags (`tags_<id>`) and collections (`collections` + `collection_<name>`). Nothing reads it any more, so anyone upgrading from V1 lost them. `BackupExporter.java`, `MediaRepository.java` and `MediaItem.java` in the root package are unreferenced.

**Do.**
1. New `organize/LegacyCatalogMigration.kt`, run once from `LibraryRuntime` init and guarded by a flag in a new preferences key:
   - favourites → `FavoriteStore.setFavorite`;
   - tags → `LibraryStore.addTag`;
   - collections → `createCollection` (reuse one with the same name), then `addToCollection`.

   Accumulate everything in memory and write each key once (TRAPS #25). Use `LibraryStore.get` (TRAPS #26). Leave the old `"catalog"` preferences untouched as a backup.
2. After moving the key constants into the migration, delete the four legacy Java files **only if** `grep` shows no references. Paste the grep output into the commit body.

**Tests:** a Robolectric test that seeds the `"catalog"` preferences, runs the migration twice, and asserts the result is applied once and correctly.

---

### P0-18 — "Open with" (`ACTION_VIEW`)

**Why.** The manifest has only the launcher entry. A photo tapped in the Files app, including on a **USB drive**, can't be opened in Foto Xplorr. This is the cheapest USB win before Phase 3.

**Do.**
1. New `viewer/ExternalViewerActivity.kt`, `exported="true"`, with the same `configChanges` as P0-09 and this intent filter:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.VIEW" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:scheme="content" android:mimeType="image/*" />
       <data android:scheme="content" android:mimeType="video/*" />
   </intent-filter>
   ```
   First line of `onCreate`: `CrashRecovery.maybeShowRecovery(...)`, as in `FotoXplorrActivity`. Theme with `FotoXplorrTheme`.
2. **Ad-hoc asset:** build a `MediaAsset` from `intent.data`:
   - `DISPLAY_NAME`/`SIZE` from `OpenableColumns`;
   - MIME from `intent.type`, else `contentResolver.getType`;
   - dimensions from a bounds decode (images) or `MediaMetadataRetriever` (video);
   - date from EXIF `DateTimeOriginal`, else now;
   - **id = a negative synthetic value.** Add an `EXTERNAL` convention in `MediaId`'s file. `FavoriteIdCodec` already ignores negative ids.

   Put the pure part (name, MIME and extension resolution) in a tested function.
3. **Catalogue match:** if the URI is a MediaStore URI (authority `media`) whose id is in `LibraryRuntime`'s repository, open **that** asset with all its normal actions instead.
4. **Viewer in external mode:** add `catalogueActions: Boolean = true` to `ViewerScreen`. When false, hide or disable favourite, sensitive, trash, tags, captions, location set/clear, edit, convert and moments export. Keep share (through `SharePreparer`, which works on any readable URI) and "Open with".
   - Never write anything about an external asset to any store (catalogue, geo, recognition, favourites).
5. In-app "Open with" (`FotoXplorrActivity.kt:719`, `openExternally`): add `Intent.EXTRA_EXCLUDE_COMPONENTS` with `ExternalViewerActivity`'s component, so the chooser doesn't offer Foto Xplorr to itself.

**Tests:** a manifest-parsing test (the filter exists, `exported=true`, both MIME types); the ad-hoc asset builder's pure part.

**Device checks:**
- Files app → a USB drive → tap a JPEG → "Open with Foto Xplorr" is offered → it opens, zooms and shares; the catalogue actions are hidden.
- A photo already in the library, opened from another app, shows its favourite state.

---

### P0-19 — Docs, TRAPS, device checklist, draft PR

**Do.**
1. `docs/device-test/phase-0-checklist.md`: every "Device check" from this brief, grouped by item, each a checkbox with the exact steps and the expected result. Include whatever you added to the progress log's "Device checks added".
2. `docs/TRAPS.md`: add new entries in the existing voice (defect earned → rule):
   - **31.** Share, zip and export paths never read through `setRequireOriginal`; location reads always do when permitted.
   - **32.** A metadata read failure is not an absence (geo, detail room, recognition).
   - **33.** One visibility filter (`browsableAssets`) for anything that draws photos.
   - **34.** Pixels come from `decodeUpright`: orientation applied, exact size.
   - **35.** Metadata writes go temp → verify → original, never through a redacted view.
   - **36.** A share output is verified clean or not sent.

   Update #1, #5 and #9 as described in P0-09 and P0-15.
3. `README.md`:
   - correct the format claims (animated support now true in the viewer; list what doesn't decode yet);
   - make the privacy-model paragraph accurately describe share stripping (what's removed, re-encoding for other formats, videos remuxed, fail-closed).
4. The ADR-008 addendum from P0-11.
5. Push `claude/phase-0-defects` and open a **draft** PR titled "Phase 0: defects from the Sept 2026 audit + Open with". The body contains:
   - the item table with commit SHAs;
   - the goldens that changed, and why;
   - the **Decisions** and **Owner questions** lists;
   - a link to the device checklist;
   - the final `verify.sh` summary line.

---

### P0-20 — *(Stretch, only if P0-01…P0-19 are all done and green)* Act as a photo picker

- Handle `ACTION_GET_CONTENT` and `ACTION_PICK` for `image/*` and `video/*`, with `EXTRA_ALLOW_MULTIPLE`, in a picker mode of the main grid that can only select.
- Return the selected items' content URIs in `ClipData` with `FLAG_GRANT_READ_URI_PERMISSION`.
- Offer only items that are browsable (P0-01): nothing locked or hidden.
- Mark every behaviour you couldn't verify, especially whether granting MediaStore URIs to the caller works, as a device check.

---

## 3. Definition of done

- Every item P0-01 … P0-19 is **done**, or **partial** with a written reason in the progress log and the PR.
- `./scripts/verify.sh` ends with `VERIFY OK` on the final commit.
- No new Gradle dependencies. `git diff main -- '*.gradle.kts' gradle/` shows no dependency or version changes.
- No changes under `hyle-design-system/` or `shared-libraries/`.
- Only the `smart:DUPLICATES` and `smart:ANIMATED` goldens changed, each explained.
- The device checklist exists and lists every device-only claim.
- A draft PR is open. Nothing is merged.

## 4. Final report (your last message in the session)

Keep it short:

1. One line per item: done or partial, plus the commit SHA.
2. The goldens that changed.
3. Decisions you took that the owner should review, one line each.
4. Owner questions.
5. The five device checks most likely to fail, in priority order.
6. The draft PR link.

---

## Appendix A — Mapping to the gap-analysis doc's 21 defect rows

The owner tracks status in the gap-analysis doc's "Defects to fix first" table. This is where each row is handled.

| Doc row | Defect | Brief item |
|---|---|---|
| 1 | Shared videos keep GPS | P0-05 |
| 2 | Stripping fails silently for HEIC/AVIF/RAW/TIFF | P0-04 |
| 3 | Locked photos on the map and calendar | P0-01 |
| 4 | Zip export always fails | P0-06 |
| 5 | Edits drop EXIF/XMP/ICC and date | P0-07 |
| 6 | Free-tier share re-render problems | P0-03, P0-04 |
| 7 | Full scan and rewrite on every start and rotation | P0-09 |
| 8 | No audio permission | P0-10 |
| 9 | Video convert: rotation, memory, 1.5× audio | P0-11 |
| 10 | Recognition retries forever and reloads constantly | P0-12 |
| 11 | Duplicates album includes originals | P0-13 |
| 12 | Viewer never animates | P0-14 |
| 13 | Scrubber wrong in Timeline, dead in masonry | P0-15 |
| 14 | Location failures cached forever; no original access | P0-02 |
| 15 | Non-ASCII XMP may be garbled | P0-08 |
| 16 | Metadata written in place; HEIC/RAW consent-then-fail | P0-08 |
| 17 | Animated album wrong | P0-14 |
| 18 | Exports above 8,192 px halved | P0-03 |
| 19 | Converted/edited video saved in disallowed folders; no date | P0-07 |
| 20 | Search and detail-room labels | P0-16 |
| 21 | V1 data never migrated | P0-17 |
| — | Editor and shares ignore EXIF rotation (found while writing this brief) | P0-03 |
| — | "Open with" | P0-18 |

## Appendix B — Platform facts this brief relies on

Check anything marked *verify* before relying on it, and record what you found.

- **Photo location is hidden** from scoped-storage apps unless they hold `ACCESS_MEDIA_LOCATION` and read through `MediaStore.setRequireOriginal()`. Video location via `MediaMetadataRetriever` needs no extra permission. [Android docs: media location](https://developer.android.com/training/data-storage/shared/media).
- **ExifInterface write support** is JPEG, PNG and WebP only. It reads HEIC, AVIF (API 31+), DNG and older RAW formats, but not CR3. [ExifInterface source](https://github.com/androidx/androidx/blob/androidx-main/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java). *Verify* the XMP byte handling (P0-08).
- **HEIF decode** is dependable from API 28 (`ImageDecoder`) and needs an HEVC decoder. AVIF from API 31. APNG decodes as a still frame only. [Supported media formats](https://developer.android.com/media/platform/supported-formats).
- **Ultra HDR JPEG** puts the primary image's gain-map metadata in XMP under `http://ns.adobe.com/hdr-gain-map/1.0/`, with the gain map as a second JPEG after the first EOI (MPF). [Ultra HDR guide](https://developer.android.com/media/grow/ultra-hdr/display).
- **Motion photo format:** a JPEG/HEIC with the video appended, described by an XMP `Container:Directory`. [Motion Photo format](https://developer.android.com/media/platform/motion-photo-format).
- **MediaStore primary-directory rules** for inserts depend on the collection. *Verify* the lists in P0-07 against MediaProvider.
- **`MediaMuxer` codec support** per container and API level: *verify* against the [MediaMuxer reference](https://developer.android.com/reference/android/media/MediaMuxer) (P0-05).
- **AAC valid sampling rates:** 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000 Hz.
