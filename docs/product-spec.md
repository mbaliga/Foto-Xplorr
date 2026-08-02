# Foto Xlorr product specification

Status: implementation baseline

## 1. Product definition

Foto Xlorr is a private, local-first Android gallery that combines dependable file and media management with optional spatial and AI-assisted exploration.

The application owns a **catalog**, not the user's media. Original files remain in Android shared storage, a Storage Access Framework tree selected by the user, or another user-managed location. Foto Xlorr stores derived metadata, organization, and model output separately unless the user explicitly requests a file or sidecar write.

## 2. Non-negotiable principles

1. No Foto Xlorr backend, account, hosted backup, mandatory telemetry, or silent outbound request.
2. All ordinary gallery and file-management features work with networking disabled.
3. Cloud AI is off by default and requires explicit provider setup, a user-supplied credential, and per-operation disclosure.
4. Local AI is optional and may never block indexing, viewing, organizing, or export.
5. The app does not request `MANAGE_EXTERNAL_STORAGE` for routine operation.
6. Spatial placement shows confidence and provenance; it does not invent coordinates, elevation, or camera direction.
7. File mutations are explicit, previewable, and confirmed through Android when the platform requires consent.
8. The catalog format and backup format are documented and portable.

## 3. Core concepts

### Physical file

The byte stream addressed by a `content://` URI or a persisted Storage Access Framework document URI. Moving, copying, renaming, trashing, restoring, and deleting act on physical files.

### Asset

Foto Xlorr's catalog record for one physical file. It contains source identity, timestamps, dimensions, format, hashes, extracted metadata, and references to thumbnails or previews.

### Folder

A storage location. Moving an asset to a folder changes its physical location.

### Album

A virtual, ordered collection. Adding an asset to an album does not move the physical file.

### Group

A named or rule-based relationship among assets, independent of folders and albums. Examples: burst, panorama source set, before/after, trip, project, duplicate set, or a manually assembled cluster. An asset may belong to several groups.

### Stack

A group with one representative asset and collapsed presentation in dense views. Typical uses are bursts, edited variants, or near-duplicates.

### Tag

A many-to-many catalog label. Tags remain local by default and can optionally be exported to XMP sidecars in a later milestone.

### Derived assertion

An extracted or model-generated fact such as “river,” “sunset,” or “likely Hyderabad.” Every assertion records its source, model/tool identity, timestamp, confidence where meaningful, and whether the user confirmed or rejected it.

## 4. Primary user journeys

### First run and indexing

1. Explain local-only behavior before requesting access.
2. Let the user grant full photo access or Android's selected-photo access.
3. Show accessible photos immediately from `MediaStore`.
4. Build the durable catalog incrementally without blocking browsing.
5. Offer optional folder roots through the Storage Access Framework for formats or locations not exposed by `MediaStore`.
6. Show indexing progress, errors, skipped files, and retry controls without implying inaccessible media was scanned.

### Browse and inspect

- Switch among adaptive grid, folder, album, group, timeline, map, terrain, and compass modes.
- Pinch or choose density presets where the view supports it.
- Open an asset into a viewer with zoom, pan, animation controls, details, metadata provenance, share, and file actions.
- Preserve browsing position and selection across rotation and process recreation.

### Organize without moving

- Add tags.
- Add assets to one or more albums.
- Create manual groups or stacks.
- Accept, edit, or reject suggested groups.
- Choose a group cover and order.
- Export catalog organization to documented sidecars or a backup, without modifying originals by default.

### Manage physical files

- Copy or move to a user-authorized destination.
- Rename where the provider permits.
- Trash through `MediaStore` or provider capabilities.
- Restore from the system/provider trash where supported.
- Permanently delete only after explicit confirmation.
- Detect conflicts, insufficient storage, partial completion, and provider errors; never report success for failed items.

### Spatial exploration

- Map: cluster photos by location on an offline-capable 2D map.
- Terrain: place photos relative to a terrain mesh generated from an explicit elevation source.
- Compass: rotate bearing-based clusters as the user pans the phone around a chosen anchor.
- Timeline: arrange photos through time; an experimental 3D presentation must remain an alternate visualization, not the only way to browse.

### AI setup and use

- Add a local GGUF model and, for vision models that require it, the matching multimodal projection file.
- Add an OpenAI-compatible endpoint with base URL, model identifier, and user key.
- Add an explicitly supported cloud provider only after a network and data disclosure.
- Select which tasks a provider may perform: captions, embeddings, semantic search, tag suggestions, group suggestions, OCR, or duplicate explanation.
- Review proposed changes before bulk applying them.

### Backup and restore

- Select a local or SAF destination.
- Create a portable encrypted catalog backup, with optional sidecars, thumbnails, and original media.
- Verify the archive before reporting success.
- Restore into a preview and resolve conflicts before changing the active catalog.
- Let Syncthing or another external tool synchronize the selected backup/sidecar folder; Foto Xlorr does not control the external sync tool.

## 5. View requirements

### Grid

Required for the first usable release. It must remain smooth with large libraries, support variable density, preserve position, and expose selection without hiding media behind decorative motion.

### Timeline

The canonical timeline is two-dimensional and grouped by date. A 3D timeline is an optional renderer over the same query and selection model. It may not duplicate business logic or become required for accessibility.

### Map

The default map must support offline operation after the user imports or downloads map data. No tile server is contacted silently. Photos with missing or low-confidence coordinates are shown separately.

### Terrain

Terrain is a custom visualization layer, not merely a tilted street map. It requires:

- a documented elevation dataset and license;
- explicit download/import;
- coordinate reference handling;
- level-of-detail and memory limits;
- altitude provenance: embedded EXIF, corrected DEM sample, inferred, or unknown;
- a flat-map fallback on unsupported devices.

### Compass

The compass view uses Android rotation-vector sensors to determine device orientation and bearing math to place geotagged clusters around an anchor. It is a sensor-relative spherical browser, not persistent world-anchored AR.

Valid anchors:

- current device location, after permission;
- a user-selected point on the map;
- one selected photo location;
- the centroid of a selected group.

An image can be placed by bearing when both anchor and target coordinates are known. EXIF camera direction, when present and reliable, may affect card orientation but is not required for bearing from the anchor.

## 6. Image and animation behavior

- GIF and other supported animated formats can autoplay in the viewer.
- Autoplay is configurable and respects battery-saving and reduced-motion settings.
- Dense grids use still previews by default; optional visible-item animation must be bounded and cancellable.
- Unsupported files still appear with metadata and a clear decoder status when their source is accessible.
- Decoder crashes or resource exhaustion must not crash the main process once native codec modules are introduced.

## 7. Accessibility and minimalism

Minimalism means fewer persistent controls and clear hierarchy, not hidden capability.

- Every gesture has a discoverable control alternative.
- Spatial views have list/grid equivalents.
- Motion-heavy views support reduced motion.
- Touch targets, contrast, screen-reader labels, and keyboard/switch navigation follow Android guidance.
- The app remains usable in portrait, landscape, large-screen, and foldable layouts.

## 8. First release boundary

The first implementation milestone in this repository is intentionally narrower:

- permission-aware `MediaStore` scan;
- adaptive grid;
- chronological timeline;
- platform image viewer and animated drawable playback;
- CI and architecture contracts.

A credible user-facing v1 additionally requires durable indexing, pagination, content observation, zoomable viewer, core file operations, tags/albums/groups, decoder registry, backup/restore, and at least the non-terrain map/compass foundations. The current shell is not marketed as v1.

## 9. Explicit non-goals

- Hosting user photos.
- Operating a Foto Xlorr sync server.
- Replacing a file manager for arbitrary non-media files.
- Claiming universal decoding of every file called an image.
- Inferring precise real-world placement without sufficient metadata.
- Making ARCore, Google services, a cloud map, or an AI provider mandatory.
- Modifying originals merely to keep the catalog organized.

## 10. Decisions still requiring owner approval

- Open-source license.
- Final public product and repository spelling.
- Whether cloud-provider adapters ship in the core app or as separately distributed modules.
- Which native codecs are acceptable after license, security, size, and maintenance review.
- The portable encrypted-backup container and audited implementation.
- Default map/terrain data sources and their redistribution terms.
