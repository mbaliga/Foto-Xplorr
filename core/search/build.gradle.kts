// :core:search -- ADR-010 §1. Query tokenizer/parser/AST, the document matcher, the suggestions
// engine. Depends on :core:model (MediaId) and :core:formats (MediaFormat, for `type:raw`).
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
            implementation(project(":core:model"))
            implementation(project(":core:formats"))
            // api, not implementation: parseSearchQuery/alternativesFor/expansionSuggestions all
            // take a TimeZone (and parseSearchQuery a LocalDate) with a default value, so every
            // caller needs these types resolvable even when it never names them explicitly.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
