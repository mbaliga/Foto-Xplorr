# Foto Xplorr

Foto Xplorr is a local-first, open-source Android gallery for fast everyday browsing, private organisation, reversible file operations and spatial exploration of a personal media library.

## V2 Android experience

### Browse and view

- Four primary destinations: Photos, Albums, Discover and Library.
- Day, month or ungrouped chronological timeline.
- Search by filename, folder, media type and user tag.
- Sort by newest, oldest, name or size and adjust the grid from 2–7 columns.
- Full-screen image viewer with swipe, zoom, pan, metadata and slideshow.
- In-app video playback with duration metadata.
- SVG and animated GIF/WebP/AVIF support through the available Coil decoders.
- System, light and dark themes with multiple accent palettes.

### Organise

- Persistent favourites, sensitive flags, archive state and user tags.
- Device folders and virtual collections are shown separately.
- Create, rename and delete collections and add or remove media without moving source files.
- Bulk selection, select-all, multi-share and multi-item organisation actions.
- Copy media to a user-selected Storage Access Framework folder.
- Safe move copies first and then asks Android to move the original to system trash.
- Rename uses Android media-write consent when required.

### Discover and explore

- Smart albums for favourites, recent media, videos, screenshots, animated media, large files, possible duplicates, sensitive media, archive, trash and untagged media.
- On-demand local extraction of embedded image and video location metadata.
- Offline coordinate map with no map-tile download and no current-location permission.
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

Local builds require JDK 17, Android SDK 36 and Gradle 9.5:

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The exact v2 acceptance boundary is documented in [`docs/v2-acceptance.md`](docs/v2-acceptance.md).

## Post-v2 work

Encrypted media-vault storage, face recognition, local or remote AI inference, downloaded offline map packs, user-supplied terrain datasets and a true interactive 3D scene engine require separate security, performance and licensing work. They are not represented as completed v2 features.

## License

Licensed under the Apache License 2.0. See `LICENSE` and `NOTICE` where present for project and third-party attribution details.
