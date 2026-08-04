plugins {
    // AGP is pinned to EXACTLY hyle-design-system's own AGP version (see that repo's
    // gradle/libs.versions.toml `agp` entry, currently 8.9.1). Gradle composite builds
    // (settings.gradle.kts's includeBuild("hyle-design-system")) hard-fail with "Using
    // multiple versions of the Android Gradle plugin ... is not allowed" if this drifts
    // from Hyle's own pin -- verify Hyle's actual pin before bumping either side (D-Q).
    // Downgraded from 9.3.1 for this reason; re-verify Kotlin/Compose-compiler
    // compatibility with the target compileSdk if bumping back up later.
    id("com.android.application") version "8.9.1" apply false
    // Kotlin Android plugin: not previously declared here -- this build relied on
    // AGP 9.x's built-in Kotlin support (no separate Kotlin Gradle plugin needed). AGP
    // 8.9.1 predates that feature, so both the Kotlin/Android compiler plugin and the
    // Compose compiler plugin now need to be applied explicitly. Version matches
    // hyle-design-system's own `kotlin` catalog entry (2.1.0) for consistency across
    // the composite build.
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
