// :core:model -- ADR-010 §1. Foundational, dependency-free vocabulary shared by every other
// :core:* module and eventually :ut:bridge (Phase 8) and :desktop:app (Phase 9). Depends on
// nothing but the Kotlin stdlib (ADR-010 §2): never add a dependency here without checking
// whether it belongs in a module further out in the `model ← formats ← metadata ←
// search/organize ← db ← index` chain instead.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    // Native targets are opt-in only (ADR-009 §3): Kotlin/Native compilers don't run on the
    // owner's ARM64 Linux dev host, and declaring these unconditionally would break that build
    // and download a large toolchain on every sync. CI turns this on in the `core-native` job
    // (deferred to WP1.2's own module-scaffold commit, once there's a real task path to run).
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
