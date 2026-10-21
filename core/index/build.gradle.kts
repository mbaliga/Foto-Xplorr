// :core:index -- ADR-010 §1 / WP1.4. The sync engine: the `Source` interface (MediaStore per-
// volume generation sync in Phase 1, other source kinds from Phase 3), `SyncEngine` (mark-and-
// sweep availability transitions, TRAPS #1/#8), and the vocabulary the downstream indexer job
// framework builds on. `SyncEngine` itself needs `:core:db`'s DAOs/entities directly (it is the
// ongoing, post-cutover counterpart to `:core:db`'s own `MigrationToV2` -- same pure-interface-
// driven, JVM-testable shape), which is why this module sits last in the dependency chain
// (`model ← formats ← metadata ← search/organize ← db ← index`, ADR-010 §2).
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
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:formats"))
            implementation(project(":core:db"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val jvmTest by getting {
            dependencies {
                // SyncEngineTest builds a real (in-memory) FotozDatabase, the same way
                // core/db/src/desktopTest does -- :core:db declares Room as `implementation`, not
                // `api`, so it doesn't leak transitively; this module needs its own direct
                // dependency to call Room.inMemoryDatabaseBuilder itself. Android/native targets
                // don't get this (androidx.sqlite.bundled isn't published for every KMP target --
                // see :core:db's own build.gradle.kts comment on the same point), and nothing here
                // needs it outside tests.
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
    }
}
