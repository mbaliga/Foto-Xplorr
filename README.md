# Foto Xplorr

Foto Xplorr is a local-first, open-source Android gallery for fast everyday browsing, private organisation, reversible file operations and spatial exploration of a personal media library.

## Android experience

### Browse and view

- Ten destinations on a word-only navigation rail: Pets, People, Identity, Screenshots, Photos, Videos, Favourites, Places, Protected, Audio.
- Day, month or ungrouped chronological timeline with a scrubber.
- A query language over filename, folder, media type, tag, date range, camera and boolean/field operators, plus tuning chips.
- Sort by newest, oldest, name or size; adjustable grid density with aspect-ratio tiles and a zoom ladder.
- Full-screen image viewer (EXIF/XMP details, Live Text selection, subject lift/stickers) and a full-screen video/audio player built on ExoPlayer/Media3: variable speed with pitch preservation, embedded and sidecar subtitle tracks, audio-track selection, gestures (double-tap seek, edge-swipe brightness/volume, A-B loop), resume position, and Picture-in-Picture.
- RAW (best-effort), SVG, and animated GIF/WebP/AVIF support through the available Coil decoders.
- System, light and dark themes with multiple accent palettes; adaptive layout for phones, tablets and foldables with mouse/keyboard support on ChromeOS.

### Edit

- A Snapseed-style photo editor (Simple and Pro tiers): exposure/contrast/highlights/shadows/white balance, HSL by hue band, per-channel draggable curves, drag-handle crop with aspect lock, straighten, spot heal, vignette/sharpen, built-in and user-saved presets, undo/redo with a persisted non-destructive edit history, and auto-fix suggestions.
- Save a copy, Save As (JPEG/PNG/WebP/HEIC where the device has an HEVC encoder) to a chosen destination, or Replace the original — each behind the Android consent Foto Xplorr's own writes require, with EXIF/XMP (including orientation) preserved into the result.
- A video editor (trim, speed, rotate, mirror, mute, aspect crop, colour filters) built on Media3 Transformer, with a live trimmed-range preview and a real export progress/cancel.
- Photographer-grade metadata: read and write EXIF and XMP (rating, creator, copyright, keywords, GPS) with the same consent flow, and batch edits across a selection.

### Audio and video conversion

- A standalone Audio destination: browse, search, and play the device's music/recordings/podcasts library with background playback, lock-screen and notification controls, a persisted library, and ID3v2/MP4/FLAC tag editing.
- Convert a video to H.264/MP4 or an audio file to AAC/M4A as a new file, at a chosen quality.
- The app can also be opened directly on an image, video or audio file from another app (View/Edit/Send).

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

Foto Xplorr has no application backend, account, analytics or mandatory cloud service. Location indexing is local and starts only after the user opens Places and requests it.

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

## License

Licensed under the Apache License 2.0. See `LICENSE` and `NOTICE` where present for project and third-party attribution details.
