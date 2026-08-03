# Foto Xplorr

Foto Xplorr is a local-first, open-source Android gallery for fast browsing, private organisation, reversible deletion, and future spatial exploration.

## Current Android foundation

- Jetpack Compose gallery with Photos, Albums, Favourites, and Trash sections.
- MediaStore scanning backed by a local SQLite catalogue.
- Progressive indexing and automatic refresh when the device media library changes.
- Search, sorting, adjustable grid density, bulk selection, metadata, sharing, zoom, pan, and swipe navigation.
- SVG, animated GIF/WebP, and video-frame loading through Coil decoder modules.
- Android system trash, restore, and explicit manual permanent deletion on Android 11 and newer.
- Sensitive-photo marking with persistent thumbnail blur.
- Multiple independently password-protected folders with stable path or MediaStore bucket identity.
- Salted PBKDF2-HMAC-SHA256 password verification off the main thread, temporary failed-attempt lockout, automatic relocking, and screenshot blocking while private folders are unlocked.

## Privacy model

Foto Xplorr has no application backend, account, analytics, or mandatory cloud service. The current private-folder feature is an in-app access gate: it does **not** encrypt or relocate the original MediaStore files, so other applications with photo access may still read them. A future encrypted vault must use Android Keystore-backed encryption and app-private storage before it can be described as secure media storage.

## Product principles

- The ordinary gallery remains useful with AI disabled.
- Future AI integrations must be user-controlled: local models, user-configured OpenAI-compatible endpoints, or explicitly selected providers using the user's own credentials.
- File operations are explicit and reversible where Android permits.
- Permanent deletion is never automatic.
- Catalogue metadata does not modify image bytes by default.
- Broad format support is treated as a continuously tested compatibility target, not a claim that every historical or proprietary format can be decoded safely.

## Planned exploration views

1. Chronological timeline and experimental 3D timeline.
2. Map view using embedded photo location metadata.
3. Terrain view using explicit elevation data or a user-provided terrain dataset.
4. Compass view that arranges geolocated clusters by bearing.

## Build and validation

GitHub Actions runs JVM tests and assembles a debug APK for every pull-request update. The workflow uploads the full Gradle build log, unit-test report, and successful debug APK as artifacts.

Local builds require JDK 17, Android SDK 36, and Gradle 9.5:

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

## License

Licensed under the Apache License 2.0. See `LICENSE` and `NOTICE` where present for project and third-party attribution details.
