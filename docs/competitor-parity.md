# Competitor parity register

This register prevents Foto Xplorr from copying only competitors' visible home screens. Every capability must be discoverable through search, command palette, contextual actions, settings, or documented menus even when it is not promoted in the primary navigation.

## Competitor families reviewed

- Platform galleries: Google Photos, Samsung Gallery, Xiaomi/HyperOS Gallery, Motorola/Pixel gallery experiences.
- Privacy and open-source galleries: Aves/LibreGallery, Fossify Gallery, Simple Gallery derivatives.
- Power-user catalogues: F-Stop Media Gallery, PhotoMap Gallery, Piktures, Gallery Elite and comparable Android products.
- Desktop references for later ports: digiKam, Shotwell, GNOME Photos/Loupe, KDE Gwenview, Apple Photos, Windows Photos and Adobe Lightroom.

## Required parity domains

### Discovery and browsing

Grid density, justified/masonry/list views, folders, albums, nested albums, smart albums, calendar, day/month/year timeline, map, globe/terrain, camera/lens/type/location filters, screenshots/documents/receipts/selfies categories, favourites, recent, imports, downloads, hidden items, archive, trash, random/slideshow, cast/display, search history and saved searches.

### Viewer and playback

Fast full-resolution decode, progressive loading, pan/zoom, rotate, crop-to-screen, compare, filmstrip, details panel, histogram, colour profile, EXIF/IPTC/XMP, motion photo/live-photo playback, burst stacks, GIF/APNG/animated WebP/AVIF autoplay controls, video playback, subtitles where relevant, loop, mute and external-open.

### Organisation

Physical move/copy/rename, virtual collections, nested tags, ratings, labels, descriptions, people/pet/place/object/event grouping, stacks, bursts, duplicates, near-duplicates, similar images, screenshots and document grouping, manual ordering, cover selection, exclusion rules, folder ignore rules and bulk operations.

### Editing

Rotate/flip, crop/aspect/straighten, exposure/contrast/highlights/shadows/temperature/tint/saturation, curves/levels where supported, filters, markup, text, draw, blur/redact, perspective/document correction, object removal, background removal, portrait blur, remaster/upscale, date/time/location correction, metadata stripping, resize, format conversion, export presets and non-destructive recipes.

### Privacy and safety

Hidden albums, app lock, biometric unlock, decoy/secure space option, encrypted local catalogue, encrypted portable backup, metadata-removal sharing, sensitive-content controls, screenshot blocking option, no telemetry, clear cloud egress prompts, trash retention, undo and destructive-operation audit.

### Sharing and interoperability

Android share sheet, link-free local sharing, Nearby/Quick Share handoff, print, wallpaper/contact-photo assignment, cast, open-with, SAF roots, removable media, Syncthing-compatible sidecars, WebDAV/S3-compatible user-mounted roots later, import/export catalogue, XMP/JSON sidecars and checksummed backup manifests.

### Spatial and temporal exploration

2D map, offline map packs, clustering, trip paths, altitude charts, terrain/elevation, 3D timeline, compass bearing view, capture-heading confidence, manual spatial constellations, photosphere-style exploration and explicit separation of measured, derived and inferred placement.

### AI and automation

Offline OCR, embeddings, semantic search, captioning, tag suggestions, scene/object classification, face clustering opt-in, duplicate and quality scoring, best-shot selection, document/receipt detection, trip/moment grouping, natural-language queries, local model capability discovery, GGUF/multimodal projector support, OpenAI-compatible endpoints and per-provider privacy warnings.

### Reliability and accessibility

Incremental/resumable scans, MediaStore and SAF reconciliation, moved-file identity, corrupted-file isolation, huge-library pagination, low-memory mode, decoder sandboxing, colour-management tests, TalkBack, keyboard/D-pad, large text, reduced motion, high contrast, RTL, orientation/foldable/tablet layouts and deterministic backup restore tests.

## Product rule

A capability counts as present only when its complete user journey, permissions, empty/error states, reversibility, accessibility and large-library behaviour are implemented and tested. A placeholder menu item does not count.

## Research caveat

Competitor behaviour changes continuously and differs by device, region, subscription and OS build. This register is a living verification artefact. Each implementation issue must cite the tested app/version or primary documentation and record whether parity is exact, adapted, intentionally rejected or superseded.
