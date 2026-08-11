# Macrobenchmark module (FX-005) — `[OWNER]`-run, deliberately not in the build graph

This module measures on a **physical device** what the JVM baseline
(`docs/perf/baseline-jvm.md`) cannot: cold startup, grid scroll jank, and the room-open
transition.

It is **not** included in `settings.gradle.kts` on purpose. This repo is routinely built
in environments with no device (CI, cloud sessions), and a test module that can never run
there would be pure configuration cost — plus one more AGP surface to keep in lockstep
with the 8.9.1 pin. The code lives here so the numbers can be taken the day someone with
a phone wants them, not so they can be claimed before that.

## Enabling

1. Add to `settings.gradle.kts` (after the `include(":app")` line):

   ```kotlin
   include(":benchmarks")
   ```

   and give `:app` the matching build type (kept out of `app/build.gradle.kts` for now so
   the everyday variant matrix stays small — with the offline/connect flavors it would
   otherwise mint two more variants nobody builds):

   ```kotlin
   create("benchmark") {
       initWith(getByName("release"))
       signingConfig = signingConfigs.getByName("debug")
       matchingFallbacks += listOf("release")
   }
   ```

2. Connect a device (physical hardware — an emulator's numbers are noise), then:

   ```bash
   ./gradlew :benchmarks:connectedBenchmarkAndroidTest
   ```

3. Record the startup/frame metrics into `docs/perf/baseline-jvm.md` under a new
   "Device" section, with the device model and OS build.

Benchmarks run against the `benchmark` build type (minified like release, signed with the
debug key, `debuggable false` — Macrobenchmark refuses debuggable targets because their
numbers lie).
