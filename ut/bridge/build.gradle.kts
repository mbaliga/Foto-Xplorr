// :ut:bridge -- MASTER-PLAN.md Phase 8 / WP8.1, ADR-013 point 2. Kotlin/Native `linuxArm64`/
// `linuxX64` C API over the shared core, compiled into `libfotozcore.so`. This is Phase-8-prep
// work started ahead of its own stated precondition ("P7 released") -- see this repo's
// docs/handoff/MASTER-PROGRESS.md WP8.1 row and Decisions for the full reasoning, and
// docs/adr/ADR-013-linux-strategy.md's dated addendum.
//
// Unlike every `:core:*` module, this one declares NO non-native target at all -- it exists
// purely to be compiled by Kotlin/Native for the future `:ut:app` Qt/QML shell (a separate, later
// dispatch; see that ADR addendum) to link against. That is also why its Gradle project
// inclusion itself is gated in settings.gradle.kts, not just its targets the way `:core:*`
// modules gate theirs -- see that file's own comment.
//
// `linuxArm64` is Tier 2 upstream (ADR-013 Consequences: "device testing on a real UT phone... is
// mandatory before the OpenStore release") -- this module only ever compiles it
// (`compileKotlinLinuxArm64`), never links or runs it, matching every other native-ready
// `:core:*` module's own `scripts/verify-native.sh` entry.
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    linuxX64 {
        binaries {
            // DEBUG only, deliberately -- this WP's job is to prove the C API links and works
            // (scripts/verify-native.sh, plus this module's own linuxX64Test), not to produce a
            // release-optimized artifact for a Qt shell that doesn't exist yet (that is the next,
            // separate dispatch this ADR addendum hands off to).
            sharedLib(listOf(NativeBuildType.DEBUG)) {
                baseName = "fotozcore"
            }
        }
    }
    // Compile-only (Tier 2, see above): no `binaries {}` block, so there is no link/run task for
    // this target at all -- exactly the same shape every other native-ready `:core:*` module's
    // `linuxArm64()` declaration already has.
    linuxArm64()

    sourceSets {
        // Not relying on Kotlin's "default hierarchy template" to create `linuxMain`/`linuxTest`
        // automatically -- created explicitly instead, so this module's build does not depend on
        // that template being active for this project (it wasn't, on this toolchain: `by getting`
        // failed with "KotlinSourceSet with name 'linuxMain' not found"). This is the one code-
        // sharing source set every `@CName`/`kotlinx.cinterop` file in this module lives in --
        // those APIs are native-platform-only and do not resolve from `commonMain`.
        val linuxMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(project(":core:model"))
                implementation(project(":core:formats"))
                implementation(project(":core:metadata"))
                implementation(project(":core:search"))
                implementation(project(":core:db"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // Room KMP + the bundled SQLite driver, ADR-013 point 2 ("gives the same
                // fotoz.db on every platform") -- this module opens :core:db's own
                // `FotozDatabase` directly, the same way app/.../LibraryRuntime.kt does for
                // Android and :core:db's own desktopTest does for the JVM.
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        getByName("linuxX64Main").dependsOn(linuxMain)
        getByName("linuxArm64Main").dependsOn(linuxMain)

        // No `dependsOn(linuxMain)` here -- Kotlin's source-set model rejects a "test tree"
        // source set depending on a "main tree" one directly ("different Source Set Trees").
        // `linuxX64Test`/`linuxArm64Test` already see `linuxMain`'s `internal` declarations
        // through the standard, automatic per-target test/main compilation association -- this
        // source set only needs to add `kotlin("test")` once for both targets.
        val linuxTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("linuxX64Test").dependsOn(linuxTest)
        getByName("linuxArm64Test").dependsOn(linuxTest)
    }
}
