# Macrobenchmark module (FX-005) — `[OWNER]`-run, opt-in via a Gradle property

This module measures on a **physical device** what the JVM baseline
(`docs/perf/baseline-jvm.md`) cannot: cold startup, grid scroll jank, and the room-open
transition.

It is **not** included in `settings.gradle.kts` by default (WP1.8: `-Pfotoz.benchmarks`
gates both `settings.gradle.kts`'s `include(":benchmarks")` and `:app`'s matching
`benchmark` build type, the same pattern `-Pfotoz.native` already uses for the native
Kotlin/Native targets). This repo is routinely built in environments with no device (CI,
cloud sessions), and a test module that can never run there would be pure configuration
cost otherwise — plus one more AGP surface, and two more `:app` variants (offline/connect
× the new build type) nobody builds, to keep in lockstep with the 8.9.1 pin. The code
lives here so the numbers can be taken the day someone with a phone wants them, not so
they can be claimed before that.

## Enabling

1. Pass the property on every Gradle invocation that needs this module or the `benchmark`
   build type, e.g. in `gradle.properties`, `~/.gradle/gradle.properties`, or `-P` on the
   command line:

   ```
   fotoz.benchmarks=true
   ```

2. Connect a device (physical hardware — an emulator's numbers are noise), then:

   ```bash
   ./gradlew :benchmarks:connectedBenchmarkAndroidTest -Pfotoz.benchmarks=true
   ```

3. Record the startup/frame metrics into `docs/perf/baseline-jvm.md` under a new
   "Device" section, with the device model and OS build.

Benchmarks run against the `benchmark` build type (minified like release, signed with the
same debug-backed `sideload` config `:app`'s own `release` build type uses, `debuggable
false` — Macrobenchmark refuses debuggable targets because their numbers lie).
