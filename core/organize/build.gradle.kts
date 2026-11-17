// :core:organize -- ADR-010 §1. RenamePattern/BulkRenamePlanner (bulk rename), SweepPolicy/ScanPlan
// (sync decision logic later superseded by WP1.4's real sync engine), GridIndexMap (grid/asset
// index mapping). The "duplicate-keeper rule" ADR-010 also lists here stayed out of this module --
// see MASTER-PROGRESS.md's WP1.2 Decisions for why.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    if (providers.gradleProperty("fotoz.native").isPresent) {
        linuxX64()
        linuxArm64()
    }

    // iOS targets are opt-in (-Pfotoz.ios=true) for the same reason as the native ones above:
    // Apple Kotlin/Native targets only compile on a macOS host, so declaring them unconditionally
    // would warn-and-disable on Linux and pull Apple toolchains into a plain sync. The
    // `ios.yml` workflow (macos-latest) turns this on. See ios/README.md.
    if (providers.gradleProperty("fotoz.ios").isPresent) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
