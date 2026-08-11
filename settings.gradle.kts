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
// The connect flavor's network engine (WP1). A plain project module of THIS build — it
// does not resolve through either included build, so the dependencySubstitution rules
// below are unaffected by it.
include(":feature:ai-remote")

// Hyle Design System (dev.aarso:hyle / dev.aarso:crash-recovery), pulled in via the
// constellation's one sanctioned sharing mechanism (D-A): git submodule + Gradle
// includeBuild dependency substitution. No vendored Hyle source, no registry publish.
// See app/build.gradle.kts for the consumed coordinates and README.md for the AGP-pin
// rationale (D-Q): this repo's own AGP version above must stay in exact lockstep with
// hyle-design-system/gradle/libs.versions.toml's `agp` entry, or Gradle hard-fails
// composite builds with "Using multiple versions of the Android Gradle plugin".
includeBuild("hyle-design-system") {
    // The explicit rule is load-bearing, not decoration. hyle-design-system still contains a
    // :crash-recovery TOMBSTONE that declares the same dev.aarso:crash-recovery coordinate as the
    // real module in shared-libraries (deliberately — see that module's MOVED.md; it exists so
    // consumers who have NOT migrated get an actionable compile error rather than an unresolved
    // dependency). With two composites offering one coordinate, Gradle fails with:
    //
    //   Module version 'dev.aarso:crash-recovery' is not unique in composite: can be provided by
    //   [project :hyle-design-system:crash-recovery, project :shared-libraries:crash-recovery]
    //
    // Declaring ANY explicit substitution for an included build disables AUTOMATIC substitution
    // for that build, so naming :hyle here removes hyle-design-system as a crash-recovery
    // candidate and the coordinate resolves unambiguously from shared-libraries.
    dependencySubstitution {
        substitute(module("dev.aarso:hyle")).using(project(":hyle"))
    }
}

// Shared constellation libraries (dev.aarso:crash-recovery, dev.aarso:cell-shell,
// dev.aarso:search-core), pulled in by
// the same sanctioned mechanism as Hyle (D-A): git submodule + Gradle includeBuild. Gradle
// substitutes those coordinates with this build's projects, so no Maven registry is involved.
//
// crash-recovery MOVED here from hyle-design-system (D-V, superseding D-O): it always had zero
// :hyle dependency, so keeping it inside the design-system repo forced apps that must never
// depend on Hyle to carry the whole Hyle submodule to reach it.
//
// This build pins AGP 8.9.1, identical to hyle-design-system, so both composites agree (D-Q).
includeBuild("shared-libraries")

