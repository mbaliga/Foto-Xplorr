# Foto Xplorr v2 acceptance specification

This document is the implementation contract for the v2 Android release. A feature counts as complete only when it is reachable in the app, handles empty/error/permission states, preserves user data, and is covered by build or test validation where practical.

## 1. Core library

- Index permitted photos and videos from Android MediaStore.
- Show progressive scan state and refresh automatically when the device library changes.
- Preserve a local SQLite catalogue across process restarts and migrate it without destructive resets.
- Support image, SVG, GIF/animated WebP/AVIF and video presentation through the available platform and Coil decoders.
- Isolate unreadable or unsupported media rather than crashing the entire library.
- Retain video duration, stable folder identity and trash state in the catalogue.

## 2. Navigation and browsing

- Four primary destinations: Photos, Albums, Discover and Library.
- Chronological timeline grouped by day, month or not grouped.
- Adjustable grid density that remains usable on a phone-sized viewport.
- Device folders and virtual collections shown separately.
- Search by media name, type, folder and user tag.
- Sort by newest, oldest, name or size.
- Full-screen viewer with swipe, zoom, pan, video playback, slideshow and details.
- Viewer actions wrap into phone-safe touch-target rows rather than overflowing horizontally.

## 3. Organisation

- Persistent favourites.
- Persistent user tags with add and remove journeys.
- Persistent virtual collections with create, rename, add, remove and delete journeys.
- Archive/unarchive without moving the source file.
- Bulk selection, select-all and multi-item actions.
- Copy to a user-selected Storage Access Framework folder with filename-conflict handling.
- Safe move: copy successfully first, then request Android system-trash consent for the source.
- Rename through Android media-write consent where required.

## 4. Discovery and spatial exploration

- Smart albums for favourites, recent media, videos, screenshots, animated media, large files, possible duplicates, sensitive media, archive, trash and untagged media.
- Duplicate candidates are described as candidates because v2 uses size, dimensions and media type rather than claiming cryptographic equality.
- Counts, covers and empty states for every smart album.
- On-demand local extraction of embedded image and video geolocation metadata.
- Offline coordinate map that does not download map tiles or request the device's current location.
- Orientation-aware compass plot using embedded capture direction when available and geographic bearing otherwise.
- Elevation plot based only on embedded altitude metadata; it is explicitly not represented as a terrain dataset.
- Experimental perspective depth timeline ordered by capture time; it is explicitly labelled as a 2.5D visualisation rather than a full 3D engine.

## 5. Privacy and safety

- Sensitive media can be blurred or hidden from the timeline.
- Multiple folders can be independently password-protected.
- Password derivation runs off the main thread, uses salted PBKDF2-HMAC-SHA256, clears password buffers and rate-limits failures.
- Private content is excluded while locked and the app relocks when backgrounded.
- Screenshots and screen recording are blocked while protected content is unlocked.
- System trash is the default deletion path on Android 11+.
- Permanent deletion is manual-only and always uses Android consent.
- Clean-share creates temporary app-owned copies and removes common EXIF GPS, device, timestamp, unique-ID and comment fields from supported images.
- The current folder lock is explicitly labelled as an in-app gate, not encrypted media storage.

## 6. Interoperability and backup

- Android share sheet for one or many items.
- Open-with and edit-with handoff.
- JSON export/import for collections, tags, archive, favourites and sensitive flags.
- Backup import rejects unsupported schemas and malformed content without silently treating them as valid.
- Clean-share files are scoped through a non-exported FileProvider and stored only in temporary cache storage.

## 7. Customisation and accessibility baseline

- System, light and dark themes.
- Multiple accent palettes.
- Configurable slideshow interval and sensitive-media behaviour.
- Content descriptions for icon-only actions.
- Large text does not require a permanently oversized control panel; actions use app bars, menus, wrapping controls and dialogs.
- RTL-safe layouts and standard Material focus and touch targets.

## 8. Validation

- JVM tests cover projection, timeline grouping, smart albums, duplicate-candidate logic, privacy filtering, selection and metadata ID codecs.
- GitHub Actions runs tests and assembles a debug APK for every pull-request head.
- The workflow uploads a complete Gradle log, unit-test report and successful APK.
- Final v2 handoff records the exact commit, successful workflow run and APK digest.

## Explicit post-v2 work

The following must not be represented as completed v2 functionality: Android Keystore-backed encrypted media-vault storage, face recognition, local multimodal model inference, remote AI-provider integration, downloaded offline map packs, a user-supplied elevation/terrain dataset renderer, and a true interactive 3D scene engine. V2 provides honest local foundations and experimental visualisations without claiming those larger systems already exist.
