# iOS / iPadOS port: status and decisions (2026-11-17)

**Owner request 2026-11-17:** build for iOS (and macOS, Windows, Ubuntu Touch) on **GitHub Actions**. This overrides the
plan's "proposed line" (iPhone: no-port) as a proposal; the plan's owner *rulings* (native only, no telemetry, `com.asoc.<appname>`
ids, no signing material in the repo, pinned actions) still bind.

## What exists, with evidence class
| Item | Evidence |
|---|---|
| `:ios:core` Kotlin facade: query parse, search, format classify, rename plan (JSON in/out, never throws) | **BUILT-HERE**: 10 + 5 jvm tests green (JDK 17) |
| Opt-in `iosArm64`/`iosSimulatorArm64` targets (`-Pfotoz.ios=true`) on `:core:model/formats/search/organize` and `:ios:core`, XCFramework `FotozCore` | Gradle **configures** here and the tasks exist (`assembleFotozCoreReleaseXCFramework`, `*:iosSimulatorArm64Test`); Apple targets are *disabled on Linux* so nothing Apple was compiled: **CI-LANE-WRITTEN** |
| `FotozKit` SwiftPM package (typed client, library model, UTI->MIME) | **BUILT-HERE**: 16 XCTest cases green on Swift 6.0.3 / Linux |
| Swift<->Kotlin JSON contract (`ios/contract`), asserted from both sides | **BUILT-HERE** (both halves, separately; the real Kotlin framework called from Swift is only in the macOS lane) |
| SwiftUI app: PhotoKit source (limited access), grid, viewer, chips, About, privacy manifest | **CODE-ONLY**: `swiftc -parse` clean (syntax only); never type-checked against SwiftUI/PhotoKit |
| `verify-offline.sh`, `verify-privacy-manifest.py` | **BUILT-HERE** (pass) |
| `.github/workflows/ios.yml` (Linux Swift lane, macOS build/test/unsigned archive, approval-gated signed export, opt-in TestFlight, draft-release upload on `ios-v*` tags) | **CI-LANE-WRITTEN**: YAML parses, all actions SHA-pinned, shell syntax checked; never run |
| Everything on a simulator or iPad/iPhone | **NEEDS-DEVICE-VALIDATION** (`ios/OWNER_VERIFY.md`) |

## Decisions and why
1. **SwiftUI shell over the Kotlin core, not Compose Multiplatform iOS** (departs from PORTING-PLAN Phase 10). Compose MP iOS needs Hyle, cell-shell and crash-recovery to go KMP (F1 to F3, other repos), none of which exists. The core, which is where the app's distinct behaviour (the search language) lives, is already KMP. Honest label: a native port of the *logic*, not of the Compose UI.
2. **Facade is String-in/String-out JSON** so the Objective-C surface is five trivial methods, the whole thing is testable on a JVM, and Kotlin sealed/value types never leak into Swift. Exceptions never cross the boundary.
3. **No `:core:db` / `:core:index` on iOS yet.** Room KMP on iOS at the pinned Room 2.7.1 / sqlite-bundled 2.5.0 is unverified (plan WP1.3), and v1 here needs no persistence: PhotoKit is the source of truth and the index is in memory. `:core:metadata` has jvm-only XMP code and is skipped too. Cost: no persisted tags/collections/FTS5 on iOS (Q22 unresolved).
4. **Offline default on iOS = PhotoKit with network access disabled** (answers plan Q8 for the `offline` scheme in this v1): iCloud-only originals are listed but never fetched.
5. **Library scope = Photos library only** (Q21 open); Files-folder sources are future work.
6. **Toolchain stays on Kotlin 2.1.0** (ADR-009). The plan's OQ-38 (Kotlin 2.2.21 + CMP 1.9.3) targets Compose MP, which this port does not use; revisit with the constellation toolchain upgrade if the first macOS run rejects the newest Xcode.
7. **Bundle id `com.asoc.fotoz`** lives only in `ios/Config/Identity.xcconfig`; the `NAMES.md` row (rule R11) is an owner follow-up outside this repo.
8. **macOS lane is path-filtered** because private-repo macOS minutes are expensive; the Linux lane runs the same Swift tests cheaply.

## Gaps that remain (not hidden)
Recognition (Vision/Core ML), `size:`/EXIF search, Keychain (no keys exist in the offline scheme), BGProcessingTask indexing, maps, Connect flavour, video clean-share, edge-gesture UX, App Store submission, signing, the privacy-manifest required-reason audit, performance on large libraries.

## Next owner actions
See `ios/OWNER_VERIFY.md` (names row, Apple enrolment, environment secrets, submodule token, then read the first macOS run).
