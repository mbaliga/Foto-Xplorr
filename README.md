# Foto Xlorr

Foto Xlorr is a local-first, open-source Android gallery for fast browsing, spatial exploration, and user-controlled AI.

The project deliberately has no Foto Xlorr account, backend, telemetry service, or proprietary backup cloud. The ordinary gallery must remain useful with AI and network access disabled.

## Current baseline

The first Android shell now provides:

- Android 10+ application skeleton using Jetpack Compose.
- Full-library and Android selected-photo permission flows.
- `MediaStore` scanning of recent images without broad all-files access.
- Adaptive photo grid and chronological timeline.
- Full-screen platform-decoded image viewing.
- Autoplay for platform-supported animated drawables, including GIFs.
- Explicit placeholders and math foundations for map and compass modes.
- CI for unit tests, lint, and debug APK assembly.

This is an initialization baseline, not a finished gallery. File operations, durable indexing, extended codecs, encrypted backups, spatial rendering, and AI providers are still milestones.

## Product principles

- **Local first:** no account, upload, telemetry, or mandatory cloud dependency.
- **Useful without AI:** scanning, viewing, organizing, and file management are independent of models.
- **User-supplied AI:** local GGUF, OpenAI-compatible endpoints, and opt-in cloud providers use user-selected models and credentials.
- **No hidden mutation:** tags, groups, captions, and model output remain in the local catalog or sidecars unless the user explicitly writes metadata into a file.
- **No proprietary sync:** Syncthing and similar tools work with normal user-selected folders and portable backups.
- **Honest spatial data:** a photo without a reliable location, altitude, or direction is not presented as if those values were known.
- **Reversible operations:** destructive and bulk file actions require clear previews, platform confirmation where required, and recovery support where feasible.

## Planned views

1. Grid, folders, albums, groups, and search.
2. Two-dimensional timeline and an experimental 3D timeline.
3. Offline-capable map view using embedded photo location metadata.
4. Terrain view using photo elevation plus a user-selected or explicitly downloaded elevation dataset.
5. Compass view that rotates geolocated photo clusters around a chosen anchor using device orientation sensors.

## Image format policy

Android does not natively decode every image format, and no credible application can promise support for every historical, proprietary, encrypted, malformed, or undocumented image. Foto Xlorr treats broad format support as a continuously tested compatibility program:

1. Platform decoders for common formats.
2. Dedicated managed decoders for formats such as SVG.
3. Isolated native codec modules for additional formats.
4. A safe metadata-only or external-open fallback when rendering is unavailable.

See [`docs/format-support.md`](docs/format-support.md).

## Build

Prerequisites:

- JDK 17
- Android SDK platform 36 and build tools 36.0.0
- Gradle 9.5.0, until the repository gains a checked-in wrapper

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is produced under `app/build/outputs/apk/debug/`.

## Documentation

- [Product specification](docs/product-spec.md)
- [Architecture](docs/architecture.md)
- [Build plan](docs/build-plan.md)
- [Format support](docs/format-support.md)
- [Privacy and security](docs/privacy-security.md)
- [Technical references](docs/references.md)

## Naming

The repository is provisioned as `Foto-Xplorr`. The current in-app display name is **Foto Xlorr**. Rename the repository only after links, package naming, and release identity are intentionally locked.

## License blocker

The intended project is open source, but a license has not yet been selected. Until a license file is committed, the repository is publicly visible source, not a properly licensed open-source release. No release should be tagged before the project license and third-party codec policy are decided.
