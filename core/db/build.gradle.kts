// :core:db -- ADR-010 §1. Created empty but compiling in WP1.2 (Room plugin, KSP wiring, a
// trivial @Database smoke test); the real catalogue v2 schema (ADR-011) fills this in during
// WP1.3. Uses the classic com.android.library + org.jetbrains.kotlin.multiplatform plugin pair,
// not com.android.kotlin.multiplatform.library, per ADR-009 §2 item 4 (that newer plugin needs an
// AGP this constellation isn't on).
//
// No hand-written `actual object ... RoomDatabaseConstructor` anywhere in this module, on any
// target -- Room's KSP processor generates it itself from the `expect` declaration in
// SmokeDatabase.kt. See that file's own doc comment and MASTER-PROGRESS.md's Decisions for why
// (a real, non-obvious Room/KSP gotcha this WP1.2 batch cost significant time to root-cause).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.fotoxplorr.core.db"
    compileSdk = 36

    defaultConfig {
        // Matches :app's own minSdk exactly (app/build.gradle.kts) -- a library's minSdk is a
        // floor the consuming app must also meet, so this must never exceed it.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    if (providers.gradleProperty("fotoz.native").isPresent) {
        linuxX64()
        linuxArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            // room-ktx pulls in kotlinx-coroutines-android (an Android-only artifact) --
            // Android-specific, not commonMain: room-runtime alone covers Flow-returning @Query
            // functions on every KMP target. Confirmed by the native compile otherwise failing to
            // resolve kotlinx-coroutines-android for linuxX64/linuxArm64.
            implementation(libs.androidx.room.ktx)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

dependencies {
    // Room's KSP processor runs once per target that has Room-annotated commonMain/platform
    // sources to generate against.
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
    if (providers.gradleProperty("fotoz.native").isPresent) {
        // With android AND native targets both declared, Kotlin splits out a real commonMain
        // metadata compilation, and Room's KSP processor needs to run against it explicitly
        // (kspCommonMainMetadata) before either per-target pass, or each one only sees its own
        // resolved actual and Room's validator rejects the reference as "not an expect
        // declaration". Android-only (no native enabled) doesn't hit this: with a single leaf
        // target, there's no separate metadata compilation to run KSP against in the first
        // place, and kspAndroid alone resolves commonMain's expect correctly on its own --
        // confirmed by this module's own kspDebugKotlinAndroid task passing without any of this
        // extra wiring.
        add("kspCommonMainMetadata", libs.androidx.room.compiler)
        add("kspLinuxX64", libs.androidx.room.compiler)
        add("kspLinuxArm64", libs.androidx.room.compiler)
        tasks.matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }.configureEach {
            dependsOn("kspCommonMainKotlinMetadata")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}
