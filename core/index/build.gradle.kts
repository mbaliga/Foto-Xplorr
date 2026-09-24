// :core:index -- ADR-010 §1. Placeholder module only in WP1.2; the real content (sync engine
// interfaces: `Source`, MediaStore per-volume generation sync, availability states, indexer job
// framework) lands in WP1.4. Declared now so the module exists in the dependency chain
// (`model ← formats ← metadata ← search/organize ← db ← index`, ADR-010 §2) and settings.gradle.kts
// registration doesn't have to happen twice.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    // Native targets are opt-in only (ADR-009 §3) -- see :core:model's build.gradle.kts for why.
    if (providers.gradleProperty("fotoz.native").isPresent) {
        linuxX64()
        linuxArm64()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
