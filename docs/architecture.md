# Foto Xlorr architecture

Status: proposed implementation contract

## 1. Architectural shape

Foto Xlorr uses a local-first, layered Android architecture with strict boundaries around storage mutation, untrusted decoding, networked AI, and spatial rendering.

```text
UI and navigation
  -> use cases and policy
    -> catalog/query interfaces
      -> MediaStore source
      -> SAF source adapters
      -> local catalog database
    -> decoder registry
      -> Android platform decoder
      -> managed format modules
      -> isolated native codec service
    -> spatial engine
      -> metadata and confidence
      -> bearing/orientation engine
      -> map renderer
      -> terrain renderer
    -> AI provider boundary
      -> local model runtime
      -> user-configured OpenAI-compatible endpoint
      -> opt-in cloud adapters
    -> backup/export boundary
```

The UI never directly performs a destructive storage operation, invokes an arbitrary model endpoint, or calls a native decoder.

## 2. Proposed modules

The initial shell remains a single `app` module to keep bootstrap cost low. Split only when a boundary has working code and tests.

| Module | Responsibility |
| --- | --- |
| `:app` | Dependency assembly, navigation, permissions, top-level state |
| `:core:model` | Stable domain identifiers and immutable catalog models |
| `:core:database` | Room catalog, migrations, transactional repositories |
| `:core:storage` | `MediaStore`, SAF, file operations, change observation |
| `:core:decoding` | Decoder API, capability probing, preview requests |
| `:codec:*` | Optional managed/native format implementations |
| `:feature:library` | Grid, folders, albums, selection |
| `:feature:viewer` | Full-screen viewing, zoom, animation, details |
| `:feature:organize` | Tags, groups, stacks, albums |
| `:feature:spatial` | Map and compass UI, spatial query model |
| `:renderer:terrain` | Terrain mesh and 3D scene rendering |
| `:core:ai` | Provider contract, job policy, provenance |
| `:ai:local-gguf` | Local GGUF runtime adapter |
| `:ai:openai-compatible` | User-configured HTTP adapter |
| `:core:backup` | Portable export, verification, restore preview |

## 3. Media sources

### MediaStore source

Use `MediaStore` for shared photos that Android indexes. It provides efficient platform thumbnails, collection change notifications, and platform-mediated mutation requests.

The current shell reads a bounded recent set. The durable implementation must add:

- keyset or platform-supported pagination;
- `ContentObserver` invalidation;
- background reconciliation;
- generation/version tracking where available;
- cancellation and backpressure;
- partial-access state;
- duplicate-safe upserts.

### Storage Access Framework source

A user-selected SAF tree is necessary for:

- media in nonstandard folders or document providers;
- formats that `MediaStore` does not classify as images;
- portable sidecar/backup directories;
- Syncthing-managed folders selected by the user.

Persist URI grants and surface revoked access. Do not recursively rescan every tree on every launch. Store provider document IDs and traversal checkpoints, while treating provider metadata as mutable and fallible.

### Asset identity

A `MediaStore` numeric ID is not a permanent global identity. Proposed identity layers:

1. source ID: source type + volume/tree + document/media ID;
2. source fingerprint: size, modified time, dimensions, and provider generation when available;
3. lazy content hash for deduplication, backup verification, and move reconciliation.

Do not hash an entire large library on first launch. Hash on demand, while idle/charging, or when a feature requires it.

## 4. Catalog

Use Room for catalog data and migrations. The catalog stores references and derived state, not original image bytes.

Core tables should cover:

- sources and grants;
- assets and source fingerprints;
- extracted metadata with provenance;
- tags and asset-tag joins;
- albums and ordered membership;
- groups, group type, representative, and membership;
- hashes and duplicate relationships;
- generated previews;
- AI providers, models, jobs, and assertions;
- backup manifests and verification records;
- user corrections and rejected suggestions.

Database writes use transactions. Long scans stage batches and expose progress. A failed scan may not erase the last known-good catalog.

## 5. File operations

All mutations pass through a `FileOperationCoordinator` that returns a plan before execution.

```kotlin
interface FileOperationCoordinator {
    suspend fun plan(request: FileOperationRequest): FileOperationPlan
    suspend fun execute(approvedPlan: FileOperationPlan): FileOperationResult
}
```

A plan records source, destination, conflicts, required free space, provider capabilities, Android consent requirements, and rollback/recovery limits.

- Use platform trash/delete/write requests for `MediaStore` items where required.
- Use `DocumentsContract`/provider operations for SAF items.
- Copy uses streamed I/O, cancellation, byte progress, fsync/close error handling, and post-copy verification before optional source deletion.
- Move across providers is copy + verified delete, never assumed atomic.
- Partial success is a first-class result with per-item status.
- “Group” and “album” never call physical move.

## 6. Decoder registry

```kotlin
interface ImageDecoderPlugin {
    val id: String
    fun probe(header: ByteArray, declaredMimeType: String?, name: String?): ProbeResult
    suspend fun decode(request: DecodeRequest): DecodeResult
}
```

Selection is based on validated signatures and capability probes, not filename extension alone.

Order:

1. Android `ImageDecoder`/platform thumbnail APIs.
2. Managed libraries for formats such as SVG.
3. Optional native codec service for broader formats.
4. Metadata-only or external-open fallback.

Native decoding is high risk because image parsers process attacker-controlled bytes. Native codecs should run in an isolated service process with bounded dimensions, decoded-byte budgets, timeouts, cancellation, and no network or storage permissions beyond the specific file descriptor supplied for a request.

A decoder result records implementation, version, whether animation and color profile were preserved, warnings, and fallback behavior.

## 7. Thumbnail and preview pipeline

- Use `ContentResolver.loadThumbnail` for fast `MediaStore` tiles where possible.
- Generate missing previews through the decoder registry.
- Cache only derived images in app-private storage.
- Key cache entries by asset fingerprint, requested size, crop mode, decoder version, and color-space policy.
- Bound memory and disk caches; never decode full-resolution images for grid tiles.
- Cancel work when cells leave the viewport.
- Animated grid previews are opt-in and restricted to visible items.

## 8. Spatial data model

```text
SpatialObservation
- latitude / longitude / altitude / direction
- source: EXIF, MediaStore, DEM, user, model
- accuracy or confidence
- timestamp
- transform/correction history
```

Coordinates read through Android may be redacted unless the app obtains the appropriate access and the source exposes them. Spatial extraction is therefore a separate, explainable step.

### Compass engine

Inputs:

- anchor coordinate;
- target photo/group coordinate;
- device rotation vector;
- display rotation;
- optional geomagnetic correction and smoothing.

Outputs:

- target bearing;
- signed relative bearing to the current azimuth;
- angular elevation when altitude data is sufficient;
- visibility and clustering state.

Sensor callbacks feed a lifecycle-aware flow. Filtering must reduce jitter without making the interface lag. The UI pauses sensor work when not visible.

### Map engine

A map renderer is an adapter. The catalog and spatial queries must not depend on a particular tile vendor. Offline packages are imported or downloaded only after explicit user action, with source and license recorded.

### Terrain engine

Terrain is a dedicated renderer consuming a bounded elevation tile/mesh interface. It is not coupled to whether the 2D map library supports a terrain style.

Required fallbacks:

- no elevation data: flat map;
- insufficient GPU/memory: simplified mesh or 2D map;
- missing photo altitude: ground-clamped marker with unknown-altitude indicator;
- conflicting altitude sources: selectable source and provenance.

## 9. AI provider boundary

```kotlin
interface AiProvider {
    val capabilities: Set<AiCapability>
    suspend fun validate(): ProviderValidation
    fun run(request: AiRequest): Flow<AiEvent>
}
```

Policy sits above providers:

- network use is denied unless the configured provider is networked and enabled;
- original image bytes are sent only for a task that requires them and after disclosure;
- thumbnails or extracted text are preferred when sufficient;
- jobs are cancellable and obey battery/thermal/storage constraints;
- model outputs are proposals until the user or an explicit rule applies them.

### Local GGUF

GGUF is a container, not a guarantee of compatibility. The runtime must validate architecture, quantization, context, memory requirement, and task support. Image understanding generally needs a compatible multimodal model plus its projection component; a text-only GGUF cannot inspect photos.

### OpenAI-compatible and cloud providers

Store base URL, model ID, and provider configuration locally. Wrap credentials with an Android Keystore-backed key. Do not route requests through a Foto Xlorr proxy. Redact credentials from logs, crash reports, exports, and backups. Certificate validation remains enabled; arbitrary cleartext endpoints are not enabled by default.

## 10. Backup architecture

There are two distinct protections:

1. **Active catalog protection:** app-private database, optionally SQLCipher-encrypted with a random key wrapped by Android Keystore.
2. **Portable backup:** a user-selected, independently encrypted archive that can be restored on another device with a passphrase or recovery material.

Android Keystore alone cannot make a portable archive because its key normally cannot leave the device. Do not invent a custom encryption scheme. Select an audited implementation and format in a dedicated ADR before shipping encrypted backup.

A backup manifest records schema version, file list, hashes, included content classes, encryption parameters, creation state, and verification result. Credentials, Keystore keys, transient caches, and provider tokens are excluded.

## 11. Network boundary

The base app declares no network need for scanning or browsing. Features that can use networking must identify themselves:

- optional map/terrain package download;
- explicitly configured remote AI provider;
- optional update or dependency metadata if later approved.

No feature may silently fall back from offline to online.

## 12. Process and failure isolation

- Main process: UI, catalog orchestration, platform thumbnails.
- Isolated codec process: untrusted native parsing.
- Optional local model process/service: bounded model memory and lifecycle.
- WorkManager: resumable indexing, hashing, preview generation, backup, and local AI jobs.

Out-of-memory, cancellation, codec crash, provider timeout, revoked URI access, and low storage are expected states with explicit recovery paths.

## 13. Testing strategy

- Pure unit tests for bearing math, clustering, queries, policies, and backup manifests.
- Instrumented tests for permissions, partial access, `MediaStore` mutations, SAF providers, process recreation, and database migration.
- Golden and corpus tests for every decoder.
- Fuzzing and sanitizer builds for native codecs.
- Macrobenchmarks with small, large, animated, high-resolution, and malformed libraries.
- Device matrix covering API 29 through target SDK, low-memory hardware, tablets/foldables, and devices without a usable rotation-vector sensor.

## 14. Current repository delta

The checked-in shell implements the first vertical slice only: permission state, bounded recent `MediaStore` query, platform thumbnails, grid/timeline UI, platform viewer, animated drawable playback, and bearing math tests. It intentionally does not claim the future module boundaries are already implemented.
