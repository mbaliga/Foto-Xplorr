# Foto Xplorr v2 acceptance specification

This document is the implementation contract for the v2 Android release. A feature counts as complete only when it is reachable in the app, handles empty/error/permission states, preserves user data, and is covered by build or test validation where practical.

## 1. Core library

- Index permitted photos and videos from Android MediaStore.
- Show progressive scan state and refresh automatically when the device library changes.
- Preserve a local SQLite catalogue across process restarts and migrate it without destructive resets.
- Support image, SVG, GIF/animated WebP/AVIF and video presentation through the available platform/Coil decoders.
- Isolate unreadable or unsupported media rather than crashing the entire library.

## 2. Navigation and browsing

- Four primary destinations: Photos, Albums, Discover and Library.
- Chronological timeline grouped by day, month or not grouped.
- Adjustable grid density that remains usable on a phone-sized viewport.
- Device folders and virtual collections shown separately.
- Search by media name, type, folder and user tag.
- Sort by newest, oldest, name or size.
- Full-screen viewer with swipe, zoom, pan, video playback, slideshow and details.

## 3. Organisation

- Persistent favourites.
- Persistent user tags.
- Persistent virtual collections with create, rename, add, remove and delete journeys.
- Archive/unarchive without moving the source file.
- Bulk selection, select-all and multi-item actions.
- Copy to a user-selected Storage Access Framework folder.
- Safe move: copy successfully first, then request Android system-trash consent for the source.
- Rename through Android media-write consent where required.

## 4. Discovery

- Smart albums for favourites, recent media, videos, screenshots, animated media, large files, possible duplicates, sensitive media, archive, trash and untagged media.
- Duplicate candidates must be described as candidates unless byte-level or cryptographic equality has been established.
- Counts, covers and empty states for every smart album.

## 5. Privacy and safety

- Sensitive media can be blurred or hidden from the timeline.
- Multiple folders can be independently password-protected.
- Password derivation runs off the main thread, uses salted PBKDF2-HMAC-SHA256, clears password buffers and rate-limits failures.
- Private content is excluded while locked and the app relocks when backgrounded.
- Screenshots/screen recording are blocked while protected content is unlocked.
- System trash is the default deletion path on Android 11+.
- Permanent deletion is manual-only and always uses Android consent.
- Clean-share creates temporary copies and removes common EXIF GPS, device, timestamp and comment fields from supported images.
- The current folder lock is explicitly labelled as an in-app gate, not encrypted storage.

## 6. Interoperability and backup

- Android share sheet for one or many items.
- Open-with and edit-with handoff.
- JSON export/import for collections, tags, archive, favourites and sensitive flags.
- Backup import must reject unsupported schemas and malformed content without destroying current metadata.

## 7. Customisation and accessibility baseline

- System, light and dark themes.
- Multiple accent palettes.
- Configurable slideshow interval and sensitive-media behaviour.
- Content descriptions for icon-only actions.
- Large text must not require a permanently oversized control panel; actions use app bars, menus and dialogs.
- RTL-safe layouts and standard Material focus/touch targets.

## 8. Validation

- JVM tests cover pure projection, grouping, duplicate-candidate and metadata-codec logic.
- GitHub Actions runs tests and assembles a debug APK for every pull-request head.
- Final v2 handoff includes the exact commit, successful workflow run and APK artifact.

## Explicit post-v2 research

The following are not to be represented as complete v2 functionality until their own evidence and test plans exist: encrypted media-vault storage, face recognition, local multimodal model inference, remote AI-provider integration, offline map packs, terrain rendering, and a true 3D spatial timeline. These may be prototyped separately without weakening the conventional gallery release.
