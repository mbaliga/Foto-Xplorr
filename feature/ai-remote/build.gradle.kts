import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The connect flavor's network engine (WP1, FX-012). This module is the ONLY place in the
// constellation of this app where OkHttp may appear: the offline flavor never depends on
// it, which verifyOfflineRuntimeClasspath enforces from the app side. Keep it leaf-like —
// it must never depend on :app, and it exposes wire-protocol primitives, not app types.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fotoxplorr.feature.airemote"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // No logging interceptor, same reasoning as before the extraction: secrets must not
    // be loggable by construction.
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
    implementation("com.squareup.okhttp3:okhttp")
}
