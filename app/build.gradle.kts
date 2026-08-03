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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

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
    implementation("dev.aarso:crash-recovery:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
