# Fotoz

Fotoz is a local-first, source-available Android gallery for fast everyday browsing, private organisation, reversible file operations and spatial exploration of a personal media library.

Fotoz was previously called Foto Xplorr; the repository name, the `com.fotoxplorr.app` package and some on-device store names keep the old name.

## Android experience

### Browse and view

- Nine destinations on a word-only navigation rail: Pets, People, Identity, Screenshots, Photos, Videos, Favourites, Places, Protected. (Fotoz no longer has an Audio destination: it is a photo and video app.)
- Four rooms, one geography, in the gallery and the viewer alike: **left** is where you can go (the rail), **top** is details (the view's counts, or the open photo's facts), **bottom** is actions (pull up a little for a strip of quick actions, further for all of them) and **right** is view options (columns and sort in the gallery; filmstrip, slideshow, autoplay and the like in the viewer). Settings is not a room: it takes over the page, with a back chevron, from the rail's Settings row.
- Day, month or ungrouped chronological timeline with a scrubber.
- A query language over filename, folder, media type, tag, date range, camera and boolean/field operators, plus tuning chips.
- Sort by newest, oldest, name or size; adjustable grid density with aspect-ratio tiles and a zoom ladder.
- Full-screen image viewer (EXIF/XMP details, Live Text selection, subject lift with tap-or-draw selection, a refine brush, undo/redo and sticker export, and a photo that shrinks into its map pin as the details pull down) and a full-screen video player built on ExoPlayer/Media3: videos start by themselves (a setting), the filmstrip stays available over a video, variable speed with pitch preservation, embedded and sidecar subtitle tracks, audio-track selection, gestures (double-tap seek, edge-swipe brightness/volume, A-B loop), resume position, a clear error panel with Retry and Open with when a video cannot be played, and Picture-in-Picture.
- A favourite heart in the viewer's top row (filled or outlined, for photos and videos in the library, shown while the controls are), a slideshow bar on the same black coved shape as the gallery's selection toolbar (previous, play or pause, next; a real pause, and the controls fold away after three seconds of a running slideshow), and a choice of what shows behind a transparent photo: the grey-and-white grid (the default), white or black, from a button that appears only on photos that have transparency and from the viewer's view options. Transparency is read from the file's own header, not guessed from its type.
- SVG support, and real animated playback for GIF, animated WebP and animated AVIF/HEIF — detected
  by inspecting each file's own container (GIF frame count, WebP's `VP8X` flag, PNG's `acTL`/`IDAT`
  ordering, HEIF/AVIF's `ftyp` brand family), not guessed from MIME type alone, so a static
  WebP/AVIF/PNG never plays as if it were animated.
- RAW formats other than Adobe DNG (Canon, Nikon, Sony, Fujifilm, Olympus, Panasonic, Sigma,
  Leica and others) are recognised and searchable (`type:raw`) but not decoded for full-resolution
  viewing yet. HEIC/HEIF still-frame decode is only reliable from Android 9 (API 28) onward; it can
  fail outright on Android 8.0/8.1.
- System, light and dark themes with multiple accent palettes; adaptive layout for phones, tablets and foldables with mouse/keyboard support on ChromeOS.

### Edit

- A Snapseed-style photo editor with one scrollable tool strip: exposure/contrast/highlights/shadows/white balance, HSL by hue band, a drawn curves editor (a histogram behind a curve with draggable points, for RGB, luma and each colour channel), drag-handle crop with aspect lock, straighten, spot heal, vignette/sharpen, built-in and user-saved presets, undo/redo with a persisted non-destructive edit history, and auto-fix suggestions.
- Three ways to save. **Save a copy** writes a new file beside the original. **Save As** (JPEG, PNG, WebP, or HEIC where the device has an HEVC encoder) writes to a destination you choose, and strips location from the file (the GPS tags and the XMP location fields, place names included), re-reading the result to check; the metadata panel's "Clear location" is narrower and leaves place names. **Replace the original** overwrites the file in place, behind Android's own consent for that photo.
- Copies in JPEG, PNG and WebP carry the original's EXIF and XMP (camera facts, date, orientation, and location for a copy beside the original). **HEIC copies are pixels only**: EXIF and XMP are not carried into a HEIC the editor writes.
- Replace the original is best effort, not atomic (Android has no replace-in-place for a library photo), so it is guarded. It is refused, and Save a copy offered instead, when replacing would lose something: an animation (animated WebP or APNG), an Ultra HDR gain map, a Motion Photo's video, a depth map or other embedded pictures, transparency or lossless quality a WebP re-encode would destroy, or metadata Fotoz cannot carry over (IPTC captions and keywords, extended XMP, PNG text such as a generation prompt, other WebP chunks, or a location Android redacted because photo-location access is off). Before writing, Fotoz makes a private safety copy of the original inside its own storage; it puts the original back if the write fails, and deletes the safety copy once the new file reads back correctly. If even that fails, or the app is killed mid-write, the next start names the photo and offers to share the safety copy so you can put it back with any app. This lowers the risk; it does not remove it, and a crash at the wrong moment can still leave a damaged file.
- A video editor (trim, speed, rotate, mirror, mute, aspect crop, colour filters) built on Media3 Transformer, with a live trimmed-range preview and a real export progress/cancel.
- Photographer-grade metadata: read and write EXIF and XMP (rating, creator, copyright, keywords, GPS) with the same consent flow, and batch edits across a selection.

### Video conversion and Open with

- Convert a video to H.264/MP4 as a new file, at a chosen quality (Media3 Transformer; progress and Cancel in the activity strip).
- Fotoz appears once in another app's "Open with" list for a photo or video (`content://` only, never `file://`), and accepts images sent to it to edit and images or videos shared to it. A photo that Fotoz itself hides or locks (a locked folder, archive, trash, or sensitive photos you chose to hide) is never opened from outside: you get a short notice instead. A file from another app opens in the viewer without the favourite, tag, caption or location actions, since it is not in your library.

### Organise

- Persistent favourites, sensitive flags, archive state and user tags; on-device recognition (no network) groups Pets, People and Identity documents.
- Device folders and virtual collections are shown separately.
- Create, rename and delete collections and add or remove media without moving source files.
- Bulk selection, select-all, multi-share and multi-item organisation actions; long-running jobs (conversion, export, copy/move) show progress and can be cancelled.
- Copy media to a user-selected Storage Access Framework folder.
- Safe move copies first and then asks Android to move the original to system trash.
- Rename uses Android media-write consent when required.

### Discover and explore

- Smart albums for favourites, recent media, videos, screenshots, animated media, large files, possible duplicates, sensitive media, archive, trash and untagged media, plus smart categories (flora/fauna/architecture/groups).
- On-demand local extraction of embedded image and video location metadata, with a manual pin for photos that carry none.
- Offline coordinate map with no map-tile download and no current-location permission (the `connect` flavor adds a live street map).
- Orientation-aware compass exploration.
- Metadata-derived elevation view.
- Experimental perspective depth timeline ordered by capture time.

### Privacy and deletion safety

- Sensitive media can be blurred or hidden from the main timeline.
- Multiple independently password-protected folders with stable path or MediaStore bucket identity.
- Salted PBKDF2-HMAC-SHA256 password verification off the main thread, temporary failed-attempt lockout and password-buffer clearing.
- Automatic relocking and screenshot/screen-recording blocking while protected content is unlocked.
- Android system trash and restore on Android 11 and newer.
- Permanent deletion is manual-only and always uses Android consent.
- Metadata-clean sharing creates temporary copies and removes common EXIF location, device, timestamp, unique-ID and comment fields from supported images.

## Privacy model

Fotoz has no application backend, account, analytics or mandatory cloud service. Location indexing is local and starts only after the user opens Places and requests it.

Sharing strips metadata before a file ever leaves the app, and every stripped output is re-checked
clean before it is handed to the receiving app — never trusted on the strength of the stripping
logic alone. JPEG, PNG, WebP, GIF and BMP are parsed directly and only the location, device,
timestamp, unique-ID and comment fields are removed from their own byte structure; a format the
parser does not recognise (HEIC/HEIF, AVIF, DNG and other RAW, TIFF) is instead decoded and
re-encoded as a fresh JPEG, which drops all of its original metadata as a side effect of being a new
file. Video is never copied byte-for-byte: sharing remuxes it into a new container without ever
writing a location, re-verifying the result has none. A share this app cannot confirm is clean —
an unsupported video codec, a corrupt or truncated source — fails with an explanatory error instead
of silently sending the original with its metadata intact.

Another app cannot use Fotoz to see what Fotoz hides: an open-with or share request that names a photo in a locked folder, the archive, the trash or the hidden-sensitive set is refused with a short notice, however the photo's address is spelled, and the photo picker never offers them. While a Replace the original is running, Fotoz keeps a copy of that one photo in its own private storage so it can put the original back; it is deleted when the write is verified, or kept until you have answered a notice about it (then deleted after 7 days, or 30 if the original could not be put back), and never leaves the app unless you choose to share it.

The current private-folder feature is an **in-app access gate**. It does not encrypt or relocate original MediaStore files, so other applications that have photo access may still read them. It must not be described as an encrypted vault.

The offline map is a coordinate visualisation, the elevation view uses only embedded metadata, and the depth timeline is an experimental 2.5D presentation. They are not downloaded map packs, terrain data or a full 3D engine.

## Metadata backup

Collections, tags, archive state, favourites and sensitive flags can be exported to and imported from a portable JSON file. Original media bytes are not included in this metadata backup.

## Build and validation

GitHub Actions runs JVM tests and assembles a debug APK for every pull-request update. The workflow uploads the complete Gradle build log, unit-test report and successful APK.

This repo consumes the [Hyle Design System](https://github.com/mbaliga/hyle-design-system) as a git
submodule (`hyle-design-system/`) plus a Gradle `includeBuild`, so a fresh clone needs the submodule
initialised, and its Android Gradle Plugin version is pinned to match Hyle's own AGP version exactly
(currently 8.9.1 — Gradle composite builds hard-fail if the two drift apart):

```bash
git clone --recurse-submodules https://github.com/mbaliga/foto-xplorr
# or, if already cloned without --recurse-submodules:
git submodule update --init

./scripts/verify.sh
```

It also consumes the constellation's shared libraries (`dev.aarso:cell-shell`, the spatial rooms
shell, and `dev.aarso:crash-recovery`) as a second submodule, `shared-libraries/`, from
[Shared-Libraries-asoc](https://github.com/mbaliga/Shared-Libraries-asoc), again through a Gradle
`includeBuild`. The pin on this branch (`1f33d10`, which adds the two-stage bottom room the actions
rooms use) lives on that repo's `claude/friendly-lovelace-06dito` branch, in its PR #17, which must
be merged **first**, with a merge commit and not a squash (`docs/TRAPS.md` #2), or this app's pin
points at a commit that no longer exists.

`scripts/verify.sh` is the one definition of "builds clean" — CI runs the same script.
It builds, tests and lints **both flavors** and runs the offline enforcement gates.

The app ships in two flavors on one `connectivity` dimension: **`offline`** (the app's
identity — no network permission, no network library on the classpath, enforced by
`verifyOfflineManifest` / `verifyOfflineRuntimeClasspath` at build time; installs as the
historical `com.fotoxplorr.app[.debug]`) and **`connect`** (adds BYOK remote AI, the
similarity-model download, and the OpenFreeMap street map; installs alongside as
`….connect`). For a single quick build: `./gradlew :app:assembleOfflineDebug`.

Local builds require JDK 17, Android SDK 36, and the checked-in Gradle wrapper (8.14.3, matching
`hyle-design-system`'s own wrapper — avoid running a system-installed `gradle` of a different
version against this project, since AGP 8.9.1 needs to stay paired with a compatible Gradle).

`docs/v2-acceptance.md` records the acceptance criteria for the original v2 milestone, which the
app has long since grown past; `docs/PRODUCT-AND-ARCHITECTURE.md` is the living description of
what exists today, and `docs/adr/` holds the individual architecture decisions since.

## Known gaps

Encrypted media-vault storage (private folders are an in-app access gate, not encryption),
downloaded offline map packs, user-supplied terrain datasets, and a true interactive 3D scene
engine are not implemented. On-device face/pet/identity recognition and BYOK remote-AI assistance
(the `connect` flavor only) are implemented; see `docs/PRODUCT-AND-ARCHITECTURE.md` for what each
covers and does not.

## Licence

Source-available, free for noncommercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). If you make money with it, you need a commercial licence: see [COMMERCIAL-LICENSE.md](COMMERCIAL-LICENSE.md).
