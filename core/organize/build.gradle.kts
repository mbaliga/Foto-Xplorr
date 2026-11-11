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

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
