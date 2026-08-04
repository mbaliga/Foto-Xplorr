import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fotoxplorr.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fotoxplorr.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-ai-spatial"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // Locale-dependent String.format is used throughout for EXIF/size readouts that are
        // numeric-only; the app has no translations, so this is noise rather than a defect.
        disable += "DefaultLocale"
    }
}

// Without this, Kotlin infers its jvmTarget from the JDK running Gradle (21 on the current
// toolchain) while AGP compiles Java at 17 from `compileOptions` above, and the build fails
// with "Inconsistent JVM-target compatibility detected for tasks 'compileDebugJavaWithJavac'
// (17) and 'compileDebugKotlin' (21)". Pinning Kotlin to the same 17 makes the build
// independent of whichever JDK happens to run it.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// One or more dependencies below (candidates: mediapipe tasks-vision, coil3, or the
// maplibre SDK) transitively pull org.jetbrains.kotlin:kotlin-stdlib:2.4.0, and Gradle's
// default highest-version-wins resolution picks that over the 2.1.0 this build's Kotlin
// compiler plugin (matched to hyle-design-system's pin, D-Q) can read -- the compiler
// then fails with "Module was compiled with an incompatible version of Kotlin. The
// binary version of its metadata is 2.4.0, expected version is 2.1.0." Force every
// kotlin-stdlib variant back to the compiler's own version until this app deliberately
// upgrades its whole Kotlin toolchain (this is the same failure mode and fix an earlier
// pre-implementation review documented for this app's Fyl-Manager sibling, though that
// repo has not actually hit it -- its dependency set doesn't happen to pull a newer stdlib).
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:2.1.0",
            "org.jetbrains.kotlin:kotlin-stdlib-common:2.1.0",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.0",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.0",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Already on the classpath transitively; declared explicitly because this module now uses
    // its KTX helpers directly (SQLiteDatabase.transaction, Bitmap.get).
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-gif:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    implementation("io.coil-kt.coil3:coil-video:3.5.0")

    // On-device image embeddings. The model is installed into app-private storage on demand.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // On-device recognition for the Pets / People / Identity destinations. These are the
    // *bundled* ML Kit artifacts on purpose: they ship their models inside the APK, so they
    // need no Play Services, no first-use download and -- critically for this app's
    // local-first posture -- no network at all. Nothing here can reach a remote endpoint,
    // which is why personal photos can be run through it while the BYOK remote-AI path in
    // com.fotoxplorr.app.ai stays strictly opt-in and strictly separate.
    //
    // COST, measured on the debug APK: the bundled native libraries total ~121 MB across all
    // four ABIs (~30 MB for a single ABI, which is what a device actually installs from an
    // app bundle or ABI split). The unbundled `*-play-services` variants of these three
    // artifacts would remove that entirely, but they require Google Play Services and a
    // first-use model download over the network -- which would break the offline, local-first
    // guarantee these destinations are built on. The size is the deliberate price of that
    // guarantee; if it ever needs cutting, dropping text-recognition (the largest single
    // contributor, ~11 MB/ABI) would cost only the Identity destination.
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Native vector maps with clustering, pitch/bearing, hillshade and 3D building extrusions.
    implementation("org.maplibre.gl:android-sdk:13.0.2")

    // Provider-key connections. No logging interceptor is included so secrets cannot be logged.
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
    implementation("com.squareup.okhttp3:okhttp")

    // Hyle Design System, via git submodule + Gradle includeBuild dependency substitution
    // (settings.gradle.kts) -- the constellation's one sanctioned sharing mechanism (D-A).
    // Coordinates and versions must match hyle-design-system/*/build.gradle.kts exactly for
    // substitution to resolve (group:artifact:version, not just the artifact name).
    implementation("dev.aarso:hyle:0.2.0")
    // Optional, additive, zero-dependency-on-:hyle reliability utility (plain
    // android.widget views): captures a device-only crash and shows a recovery screen on
    // next launch. Wired in FotoXplorrActivity/FotoXplorrApplication.
    implementation("dev.aarso:crash-recovery:1.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
