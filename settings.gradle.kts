pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FotoXplorr"
include(":app")

// Hyle Design System (dev.aarso:hyle / dev.aarso:crash-recovery), pulled in via the
// constellation's one sanctioned sharing mechanism (D-A): git submodule + Gradle
// includeBuild dependency substitution. No vendored Hyle source, no registry publish.
// See app/build.gradle.kts for the consumed coordinates and README.md for the AGP-pin
// rationale (D-Q): this repo's own AGP version above must stay in exact lockstep with
// hyle-design-system/gradle/libs.versions.toml's `agp` entry, or Gradle hard-fails
// composite builds with "Using multiple versions of the Android Gradle plugin".
includeBuild("hyle-design-system")
