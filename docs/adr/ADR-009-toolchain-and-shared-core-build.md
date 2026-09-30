# ADR-009 — Toolchain for Phase 1: no constellation upgrade; KMP on the pinned toolchain

**Status:** accepted (owner review at the Phase 1 gate)
**Date:** 24 Sep 2026
**Work package:** WP1.1 (master plan §3, Phase 1)
**Supersedes:** WP1.1 as written in `MASTER-PLAN.md` ("toolchain upgrade in lockstep")

## Context

Foto Xplorr consumes `hyle-design-system` and `shared-libraries` (`Shared-Libraries-asoc`) as git submodules through Gradle `includeBuild` (decision D-A). A composite build hard-fails when its builds use different Android Gradle Plugin versions (D-Q, TRAPS #3).

**Current pins, identical in all three repos:**
- AGP 8.9.1
- Kotlin 2.1.0
- Gradle 8.14.3
- Compose BOM 2025.05.01
- Hyle additionally pins KSP `2.1.0-1.0.29` and Room 2.7.1.

These are not Foto Xplorr's to move alone. Hyle and shared-libraries are composite-included by **other apps** too (Fonebrew and others). Bumping them forces every consumer to move its own AGP and Kotlin at the same moment, or break.

**What Phase 1 actually needs from the toolchain:**

| Need | Requirement | Met by current pins? |
|---|---|---|
| Room KMP with Linux native targets | Room ≥ 2.7.0 (linuxArm64 added in 2.7.0-alpha06); **Kotlin ≥ 2.0** (2.7.0-alpha13); Room Gradle plugin AGP ≥ 8.1 (8.4 from 2.8.0) | **Yes.** Hyle already builds Room 2.7.1 on Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / AGP 8.9.1 |
| Kotlin/Native `linuxX64` / `linuxArm64` | Any Kotlin 2.x; host must be x86_64 Linux/macOS | Yes (CI is `ubuntu-latest`) |
| kotlinx-coroutines / serialization / datetime for native | Versions compiled against Kotlin ≤ 2.1 | Yes: coroutines 1.10.x, serialization 1.8.x, datetime 0.6.x |
| Compose Multiplatform Desktop | Newer Kotlin | **Not needed until Phase 9** |

Source for the Room facts: [Room release notes](https://developer.android.com/jetpack/androidx/releases/room).

## Decision

1. **No toolchain upgrade in Phase 1.** Stay on AGP 8.9.1, Kotlin 2.1.0, Gradle 8.14.3 and Compose BOM 2025.05.01. Keep the `kotlin-stdlib` 2.1.0 force-pin in `app/build.gradle.kts`, and apply the same resolution rule to every new module.
2. **Library versions for new core modules** (compatible with Kotlin 2.1.0; record each in `docs/DEPENDENCIES.md`):

   | Library | Version | Notes |
   |---|---|---|
   | Room + Room Gradle plugin | `2.7.x` | Start at `2.7.1` (Hyle's proven pin). Take the newest 2.7 patch only if it builds green on this toolchain. **Never 2.8.x in Phase 1.** |
   | `androidx.sqlite:sqlite-bundled` | the version Room 2.7.x depends on (2.5.x line) | Resolve and record the exact version |
   | KSP | `2.1.0-1.0.29` | Must equal Hyle's |
   | kotlinx-coroutines-core | `1.10.2` | Same as the app |
   | kotlinx-serialization-json | `1.8.x` | Plugin `org.jetbrains.kotlin.plugin.serialization` `2.1.0` |
   | kotlinx-datetime | `0.6.x` | For `commonMain` date logic |
   | okio | `3.9.x` or newer patch compatible with Kotlin 2.1 | Only if a module needs file I/O in `commonMain` |
   | AndroidX Paging | a `paging-common` version with KMP targets, if WP1.5 adopts Paging | **Verify** it supports Kotlin 2.1.0 and a native Linux target before adopting. Otherwise WP1.5 uses a hand-written windowed `Flow` (acceptable) |

   Before committing any version, run `./gradlew :app:dependencies --configuration offlineReleaseRuntimeClasspath`. Confirm Kotlin metadata stays ≤ 2.1 compatible. The stdlib force-pin must not have to be widened to anything else.
3. **Native targets are opt-in by Gradle property.** Declare `linuxX64()` and `linuxArm64()` only when `-Pfotoz.native=true`. Reasons:
   - Kotlin/Native compilers don't run on ARM64 Linux hosts, and the owner builds phone-first (Termux / proot Debian on ARM64).
   - Declaring native targets unconditionally would break those local builds and download a large toolchain on every sync.

   CI turns the property on in a separate job (item 5). `scripts/verify.sh` stays unchanged for local use and gains a `--native` flag that passes the property.
4. **The Android side of new KMP modules uses the classic plugins available on AGP 8.9.1:** `org.jetbrains.kotlin.multiplatform` + `com.android.library` with `androidTarget()`, only for modules that need Android APIs (for example `:core:db`, for Room's Android builder). Pure modules use `jvm()` + (opt-in) native, and the Android app consumes their JVM variant. Don't use `com.android.kotlin.multiplatform.library`: it needs a newer AGP.
5. **CI:** add a job `core-native` to `.github/workflows/android-debug.yml` that runs `./gradlew -Pfotoz.native=true <every native-ready module>:linuxX64Test <every native-ready module>:compileKotlinLinuxArm64`. It can't *run* `linuxArm64` tests on an x86 runner; compiling them is enough in Phase 1. It is **required** once WP1.2 lands.
6. **The constellation-wide upgrade becomes its own project**, not a Foto Xplorr work package. Write `docs/handoff/CONSTELLATION-TOOLCHAIN-UPGRADE.md` (a short plan, no code): which repos include Hyle and shared-libraries, and the target AGP/Kotlin/Gradle/Compose. The order is Hyle → shared-libraries → each consumer, with merge commits and pin bumps. The trigger is Phase 9 (Compose Multiplatform Desktop), or any earlier dependency that needs Kotlin > 2.1.
   - Also list which consumer repos you can see. A mention in docs is enough; don't clone repos you weren't given.

## Consequences

- Phase 1 needs **no changes** in `hyle-design-system` or `shared-libraries` and no submodule pin bumps. That removes the one step that would have needed the owner to merge mid-run.
- Newer Room features (2.8+) are unavailable. Nothing in Phase 1–7 depends on them.
- Native compilation is proven continuously in CI but never on the owner's device builds. That's acceptable until Phase 8.
- The `kotlin-stdlib` force-pin remains a known tension. Some Android dependencies are compiled against a newer stdlib, and every dependency bump must re-check it.

## Rejected alternatives

- **Upgrade all three repos now.** It blocks on other apps' readiness, forces an owner merge mid-run, and buys nothing Phase 1 needs.
- **Decouple from the composite (consume Hyle as published artifacts).** It would free Foto Xplorr's toolchain but reverses decision D-A for the whole constellation. That's an owner-level change; record it in the constellation upgrade plan as an option.
- **SQLDelight instead of Room KMP.** Viable (native Linux driver since 2.0.1), but Hyle and the team already use Room. Room KMP gives the same `BundledSQLiteDriver` SQLite everywhere, and its Kotlin-2.0 floor is already met.
