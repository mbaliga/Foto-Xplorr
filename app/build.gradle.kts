import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
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

    // The debug keystore, used to sign RELEASE too. Deliberate, and narrow: the owner installs
    // these builds by sideloading them, and an unsigned release APK cannot be installed at all —
    // it fails at the package installer with no useful message, which reads as "the app is
    // broken" rather than "the build is unsigned". A debug-signed release is installable, is
    // still minified and shrunk, and is obviously not a Play Store artifact (the upload would be
    // rejected for exactly this reason). A real upload key belongs in CI secrets when there is a
    // store listing to upload to; there is not one yet.
    signingConfigs {
        create("sideload") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            // Was absent, and it is half the size win: R8 strips unreachable CODE, but the
            // drawables, layouts and translations pulled in by Compose/Material/ML Kit are
            // RESOURCES and survive minification untouched unless this is on.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    // One APK per ABI instead of one fat APK carrying every native library.
    //
    // This is the other half of the size problem, and on this app it is the bigger half: ML Kit,
    // MediaPipe and the OpenGL scene all ship .so files for four architectures, so a universal
    // APK carries three sets of native code that the installing phone will never load. A real
    // device needs exactly one. `isUniversalApk = true` keeps a fat APK in the output as well,
    // because "which of these four do I want?" is a question a person sideloading should be
    // allowed to skip.
    //
    // OPT-IN via `-PabiSplits`, and that is load-bearing rather than tidiness: AGP's `splits`
    // block is global, not per-build-type, so switching it on unconditionally renamed the DEBUG
    // outputs too (`app-offline-debug.apk` -> `app-offline-arm64-v8a-debug.apk`) and broke every
    // path that referred to them, CI's artifact upload first. Gating it keeps the ordinary
    // debug build a single APK at its historical path, and keeps local builds from paying for
    // four packaging passes nobody asked for.
    splits {
        abi {
            isEnable = providers.gradleProperty("abiSplits").isPresent
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // WP1 (FX-010): one dimension, two flavors. `offline` is the app's identity — the
    // local-first gallery with no network capability at all, enforced by the verifyOffline*
    // gates below, and it keeps the existing applicationIds so the normal device-test path
    // is undisturbed. `connect` carries every network feature (BYOK remote AI, the model
    // download, the street map) and takes a `.connect` suffix so BOTH flavors install
    // side by side — this project's top priority is device testing, and a tester comparing
    // the two builds must not have to uninstall one to see the other.
    flavorDimensions += "connectivity"
    productFlavors {
        create("offline") {
            dimension = "connectivity"
            buildConfigField("boolean", "NETWORK_FEATURES", "false")
        }
        create("connect") {
            dimension = "connectivity"
            applicationIdSuffix = ".connect"
            buildConfigField("boolean", "NETWORK_FEATURES", "true")
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

    testOptions {
        // Roborazzi renders real composables to PNG on the JVM. Needs Android resources on the
        // unit-test classpath, which is off by default.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Roborazzi reads this from the TEST JVM's properties, so a Gradle -D never reaches
            // it: the tests pass and silently write no images.
            it.systemProperty("roborazzi.test.record", "true")
            // The FX-005 perf baseline materialises a 500k-asset synthetic catalogue plus
            // its projection copies; the default 512 MB test heap OOMs before the timing
            // starts. Normal tests never approach this ceiling.
            it.maxHeapSize = "4g"
            // The characterisation goldens and perf baseline are switched by environment
            // variable (a -D on the Gradle CLI stays in the daemon, not the forked test
            // JVM); forward only the two switches so the test environment stays hermetic.
            System.getenv("FX_GOLDENS_PRINT")?.let { v -> it.environment("FX_GOLDENS_PRINT", v) }
            System.getenv("FX_PERF")?.let { v -> it.environment("FX_PERF", v) }
        }
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
    // Screenshot rendering of the real composables, on the JVM, no emulator. Test-only, so it
    // never reaches the runtime classpath the offline gate guards.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.8.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.8.2")

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

    // Native vector maps with clustering, pitch/bearing, hillshade and 3D building
    // extrusions. CONNECT ONLY (WP1): the map fetches its style and tiles from
    // OpenFreeMap/AWS at pan time, and MapLibre carries its own HTTP stack — either fact
    // alone disqualifies it from the offline flavor, whose runtime-classpath gate bans it.
    "connectImplementation"("org.maplibre.gl:android-sdk:13.0.2")

    // The network engine for BYOK providers and the embedder-model download. CONNECT
    // ONLY: this project module is the sole owner of OkHttp; the offline flavor's
    // classpath gate fails the build if either ever reaches it.
    "connectImplementation"(project(":feature:ai-remote"))

    // On-device translation for the "Search inside this photo" card's Translate action
    // (com.fotoxplorr.app.lens). CONNECT ONLY, same reasoning as the two dependencies
    // just above: unlike the bundled face/label/text-recognition models a few lines up
    // in this file, ML Kit Translate has no no-network variant -- it downloads a ~30 MB
    // language model over the network the first time a given language pair is used, which
    // makes it a network-capable library exactly like OkHttp or MapLibre. The offline
    // flavor's com.fotoxplorr.app.lens.TextTranslator implementation
    // (src/offline/java/.../lens/AppTextTranslator.kt) never references this artifact at
    // all -- it reports itself unavailable and the UI hands off to another installed
    // translator app instead -- so declaring this connectImplementation, not
    // implementation, is what keeps it off verifyOfflineRuntimeClasspath's resolved set
    // in the first place, the same way MapLibre and :feature:ai-remote are kept off it.
    "connectImplementation"("com.google.mlkit:translate:17.0.3")

    // Hyle Design System, via git submodule + Gradle includeBuild dependency substitution
    // (settings.gradle.kts) -- the constellation's one sanctioned sharing mechanism (D-A).
    // Coordinates and versions must match hyle-design-system/*/build.gradle.kts exactly for
    // substitution to resolve (group:artifact:version, not just the artifact name).
    implementation("dev.aarso:hyle:0.2.0")
    // Optional, additive, zero-dependency-on-:hyle reliability utility (plain
    // android.widget views): captures a device-only crash and shows a recovery screen on
    // next launch. Wired in FotoXplorrActivity/FotoXplorrApplication.
    implementation("dev.aarso:crash-recovery:1.4.0")
    // The constellation's navigation and motion shell: the fonebrew spatial pattern (rooms
    // parked off the screen edges, the home card lifting and parting to reveal them), the
    // word-wheel rail and the Niagara-style edge scrubber. Shared rather than local because
    // the owner asked for one navigation feel across every app, and two apps each deriving
    // their own version of it is precisely how that feel drifts apart.
    implementation("dev.aarso:cell-shell:0.1.0")

    testImplementation("junit:junit:4.13.2")
    // FX-005 JVM perf baseline only: times the catalogue read against a real SQLite file
    // without a device. android.database.* cannot run on the JVM, so the harness replicates
    // the media-table schema/queries over JDBC. Never shipped — test classpath only.
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ─── WP1 (FX-011): the offline flavor's enforcement gates ────────────────────────────
//
// Three gates, in order of how hard they are to fake. The FIRST two are the real ones:
// the merged manifest is the thing the OS enforces, and the resolved runtime classpath is
// the thing that actually ships. The source scan is a fast-feedback convenience on top.
// This is deliberately NOT an import ban on java.net/android.net — android.net.Uri and
// java.net.URI perform no I/O, and a package-level ban would break every MediaStore and
// SAF call in the app (docs/TRAPS.md #12).

/** Gate 1: no network permission may reach the offline flavor's MERGED manifest — from
 * our own sources or from any library AAR's manifest. */
abstract class VerifyOfflineManifestTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val banned = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.NEARBY_WIFI_DEVICES",
        )
        val hits = banned.filter { text.contains(it) }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Offline flavor's merged manifest declares network permission(s): $hits.\n" +
                    "Something (a library manifest?) merged them in — inspect " +
                    mergedManifest.get().asFile.path,
            )
        }
    }
}

/** Gate 2: no network library may appear on the offline flavor's RESOLVED runtime
 * classpath. Asserted on resolved component ids, not declared dependencies, so a
 * transitive pull fails the same as a declared one. */
abstract class VerifyOfflineClasspathTask : DefaultTask() {
    @get:Input
    abstract val componentIds: ListProperty<String>

    @TaskAction
    fun verify() {
        val bannedPrefixes = listOf(
            "com.squareup.okhttp3:",
            "io.ktor:",
            "io.grpc:",
            "org.maplibre",
            "project :feature:ai-remote",
        )
        // The ONE documented exception, caught by this gate's own first run: the bundled
        // ML Kit stack (com.google.mlkit:vision-internal-vkp, via image-labeling) carries
        // okhttp 3.0.0 internally. Its models run on-device; the client is plumbing it
        // ships regardless. It cannot transmit: the offline flavor's manifest strips
        // INTERNET (src/offline/AndroidManifest.xml), so the OS denies any socket that
        // code could ever open — the permission is the wall, this gate is the tripwire.
        // The allowlist is EXACT (group:name:version): our own OkHttp is 5.x via
        // :feature:ai-remote and still fails this gate if it ever leaks into offline, and
        // an ML Kit bump that changes the smuggled version fails too, forcing a re-read
        // of what changed. Removing the artifact outright (dependency exclude) risks
        // NoClassDefFoundError inside ML Kit on a code path only a device would reveal —
        // worth an [OWNER] experiment, not a blind change from a deviceless session.
        val allowedExact = setOf(
            "com.squareup.okhttp3:okhttp:3.0.0",
        )
        val hits = componentIds.get().filter { id ->
            id !in allowedExact && bannedPrefixes.any { id.startsWith(it) }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Offline flavor's runtime classpath resolved network artifacts:\n" +
                    hits.joinToString("\n") { "  $it" },
            )
        }
    }
}

/** Gate 3: fast feedback — the offline-visible source sets must not reference the
 * network APIs by name. Precisely targeted FQCNs; android.net.Uri, java.net.URI and the
 * URL codecs are deliberately NOT banned (they do no I/O). */
abstract class VerifyOfflineSourcesTask : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val banned = listOf(
            "okhttp3.",
            "io.ktor.client",
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.HttpURLConnection",
            "javax.net.ssl.",
            "android.net.ConnectivityManager",
            "android.net.NetworkRequest",
            ".openConnection(",
            ".openStream(",
        )
        val hits = mutableListOf<String>()
        sources.asFileTree.matching { include("**/*.kt") }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                banned.forEach { token ->
                    if (line.contains(token)) hits += "${file.path}:${index + 1}: $token"
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Offline-visible sources reference network APIs:\n" +
                    hits.joinToString("\n") { "  $it" },
            )
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.flavorName != "offline") return@onVariants
        val cap = variant.name.replaceFirstChar { it.uppercase() }

        tasks.register<VerifyOfflineManifestTask>("verifyOfflineManifest$cap") {
            group = "verification"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }

        tasks.register<VerifyOfflineClasspathTask>("verifyOfflineRuntimeClasspath$cap") {
            group = "verification"
            componentIds.set(
                variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                    val seen = linkedSetOf<String>()
                    fun walk(component: ResolvedComponentResult) {
                        if (!seen.add(component.id.displayName)) return
                        component.dependencies.forEach { dep ->
                            (dep as? ResolvedDependencyResult)?.let { walk(it.selected) }
                        }
                    }
                    walk(root)
                    seen.toList().sorted()
                },
            )
        }
    }
}

tasks.register<VerifyOfflineSourcesTask>("verifyOfflineSourceReferences") {
    group = "verification"
    sources.from("src/main/java", "src/offline/java")
}

// Umbrellas, so verify.sh and CI name one task per gate regardless of variant count.
tasks.register("verifyOfflineManifest") {
    group = "verification"
    dependsOn(tasks.matching { it.name.startsWith("verifyOfflineManifestOffline") })
}
tasks.register("verifyOfflineRuntimeClasspath") {
    group = "verification"
    dependsOn(tasks.matching { it.name.startsWith("verifyOfflineRuntimeClasspathOffline") })
}
