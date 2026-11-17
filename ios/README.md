# Fotoz for iOS / iPadOS

A **native SwiftUI app over the shared Kotlin core**. It is *not* a port of the Android Compose UI:
Hyle, cell-shell and crash-recovery are Android-only libraries today, so Compose Multiplatform on iOS
is not viable (this deviates from `docs/handoff/PORTING-PLAN.md` section 4 / Phase 10; reasons in
`docs/handoff/IOS-PORT.md`). What *is* shared is the logic: query parsing and matching
(`:core:search`), format classification and capabilities (`:core:formats`), and collision-safe rename
planning (`:core:organize`).

**Status: CODE-ONLY on the Apple side.** Nothing here has been compiled with Xcode or run on a
simulator or device. See `OWNER_VERIFY.md`. Bundle id (`com.asoc.fotoz`) is a placeholder pending the
constellation's `NAMES.md` row.

```
ios/
  core/            Gradle module :ios:core  Kotlin facade (JSON in/out) -> FotozCore.xcframework
  FotozKit/        SwiftPM package          typed client, library model, UTI->MIME (Foundation only)
  Fotoz/           SwiftUI app target       PhotoKit source, grid, viewer, search chips, About
  FotozTests/      XCTest                   real-framework smoke test against the shared contract
  contract/        *.json                   Swift<->Kotlin wire contract, asserted from BOTH sides
  Config/Identity.xcconfig                  the ONE place the bundle id / version / team live
  project.yml                               XcodeGen spec (the .xcodeproj is generated, not committed)
  scripts/                                  offline gate, privacy-manifest lint, Xcode/simulator pickers
```

## What the app does (v1 scope)
Shows the Photos library (read-only; full or **limited** access), grouped by month, with a viewer
for photos and videos and a details sheet. Search uses the same parser as Android
(`folder:holiday type:video August 2025`, `not`, `or`, ranges) and shows the parsed **chips** so the
query is legible. Searchable on iOS: name, album, date, type, favourites, screenshots, Live Photos.
**Not available on iOS yet:** recognition labels/text/colour (no on-device recognition index),
`size:` (PhotoKit has no public file-size API), EXIF fields, editing, maps, sync, Connect flavour.

## Offline identity
iOS has no INTERNET permission to remove, so the proof is source-level: `scripts/verify-offline.sh`
fails on any networking API/framework, URL literal, ATS/background key, network library in the
Kotlin facade, or a PhotoKit request that could reach iCloud. Every PhotoKit request sets
`isNetworkAccessAllowed = false`; photos whose originals are only in iCloud show an "iCloud" mark
and are never downloaded. **Telemetry exception (named, per owner ruling):** if installed through
TestFlight or the App Store, *Apple* collects crash reports/analytics per the device's own setting;
Fotoz adds nothing. This is stated in the app's About screen.

## Build (macOS only; CI does this on `macos-latest`)
```bash
git submodule update --init --recursive
./gradlew -Pfotoz.ios=true :ios:core:assembleFotozCoreReleaseXCFramework   # -> ios/core/build/XCFrameworks/release/
brew install xcodegen && cd ios && xcodegen generate
xcodebuild test -project Fotoz.xcodeproj -scheme Fotoz -destination 'platform=iOS Simulator,name=<an iPhone>' CODE_SIGNING_ALLOWED=NO
```
Runs anywhere (also in the normal gate): `./gradlew :ios:core:jvmTest` and
`cd ios/FotozKit && swift test` (Linux or macOS; Swift 5.9+), `ios/scripts/verify-offline.sh`,
`python3 -I ios/scripts/verify-privacy-manifest.py`.

CI: `.github/workflows/ios.yml` (Linux Swift tests + gates; macOS simulator build/test + unsigned
archive; approval-gated signed export, optional TestFlight). Signing material is only ever a GitHub
*environment* secret (`apple-signing`), never in the repo.

## Changing the Swift<->Kotlin contract
Edit `ios/core/.../FotozCore.kt`, bump `API_VERSION` if shapes change, then
`FOTOZ_REGEN_CONTRACT=1 ./gradlew :ios:core:jvmTest`, review the `ios/contract` diff (that diff *is*
the API change), update `FotozKit/Sources/FotozKit/CoreClient.swift` (`expectedAPIVersion`, DTOs).
`swift test` and `ContractFixturesTest` then agree on the same bytes.
