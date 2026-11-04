# Dependencies

Every new Gradle dependency gets a row here before it lands (`MASTER-PLAN.md` §0.2 standing rule
2), and every ML model gets a row in `docs/MODEL_LICENSES.md` before use. `scripts/verify-governance-docs.sh` (run by
`scripts/verify.sh`, so by CI) fails when a coordinate declared in `gradle/libs.versions.toml` or in a module build
file has no row here, or when a model, font or sound file has no row in `MODEL_LICENSES.md`; and
`scripts/verify-licences.sh` scans the licences of both flavors' resolved runtime classpaths (see the last section).

Rows below cover what WP1.1 (ADR-009) put in `gradle/libs.versions.toml` for the upcoming
`:core:*` KMP modules (ADR-010, WP1.2 onward). None of them are consumed by any module yet —
the catalog exists so WP1.2+ reference one pinned version each, not a copy-pasted literal per
module. "Flavours" below therefore currently reads "none (unused)" throughout; update each row's
Flavours column to `offline` / `connect` / both the first time a module actually depends on it.

| Coordinate | Version | Licence | Why | Flavours | Size | Network-capable |
|---|---|---|---|---|---|---|
| `androidx.room:room-runtime` | 2.7.1 | Apache-2.0 | Catalogue v2 (ADR-011): Room KMP with `BundledSQLiteDriver` gives the same SQLite + FTS5 build on Android and headless Linux. Pinned to Hyle's own proven version on this exact toolchain (ADR-009), not the newer 2.7.2 — nothing has built 2.7.2 against this constellation yet. | none (unused) | ~0.6 MB (Android variant, `room-runtime-android`); root artifact is KMP metadata only | No |
| `androidx.room:room-ktx` | 2.7.1 | Apache-2.0 | Coroutines/Flow extensions for Room queries. | none (unused) | negligible (extension functions only) | No |
| `androidx.room:room-compiler` | 2.7.1 | Apache-2.0 | KSP annotation processor that generates Room's DAO implementations. Build-time only — never ships in an APK. | none (unused) | ~1.6 MB (compiler artifact; not shipped) | No |
| `androidx.sqlite:sqlite-bundled` | 2.5.0 | Apache-2.0 | The bundled (non-system) SQLite driver Room KMP needs for identical SQLite behaviour on Android and Kotlin/Native Linux. Exact line `room-runtime:2.7.1`'s own POM declares. | none (unused) | ~3.4 MB (Android variant; bundles a native SQLite build) | No |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | `commonMain` coroutines/Flow for the new `:core:*` modules. Same version the app already uses for `kotlinx-coroutines-android` (ADR-009 decision 2) — one coroutines version across the whole build. | none (unused in `:core:*` yet; the app itself already depends on the platform artifact `kotlinx-coroutines-android` at this version) | ~1.5 MB (JVM artifact) | No |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.8.1 | Apache-2.0 | JSON (de)serialization for `commonMain` — edit-recipe JSON, model-pack manifests, sync tokens. Requires the `org.jetbrains.kotlin.plugin.serialization` compiler plugin (declared in the catalog, applied nowhere yet). | none (unused) | ~0.3 MB (JVM artifact) | No |
| `org.jetbrains.kotlinx:kotlinx-datetime` | 0.6.2 | Apache-2.0 | `commonMain` date/time (`date_taken`, sync tokens, migration timestamps) without pulling `java.time` into non-JVM targets. The 0.6.x line specifically per ADR-009; 0.7.x/0.8.x exist upstream and are a deliberate non-adoption for Phase 1. | none (unused) | ~0.7 MB (JVM artifact) | No |
| `com.squareup.okio:okio` | 3.9.1 | Apache-2.0 | `commonMain` file I/O abstraction, if/when a module needs it (most likely WP1.10's XMP sidecar writer) — not adopted by anything yet. | none (unused) | ~0.4 MB (JVM artifact) | No |

## Toolchain-level pins (not Gradle dependencies, but governed the same way)

| Coordinate | Version | Why |
|---|---|---|
| `com.google.devtools.ksp` (Gradle plugin) | 2.1.0-1.0.29 | Must equal `hyle-design-system`'s own KSP pin exactly (ADR-009 decision 2) — KSP versions are tied to a specific Kotlin compiler version, and Hyle is already proven on this one. |

## Compile-only declarations (no new artifact in any resolved classpath)

| Module | Coordinate | Version | Why |
|---|---|---|---|
| `:feature:ai-remote` | `org.maplibre.gl:android-sdk` (`compileOnly`) | 13.0.2 | `MapRequestCap` passes an OkHttp client to the map library's `HttpRequestUtil` so offline region downloads keep to the tile provider's limit of 4 requests in flight. The app cannot name an OkHttp type, so the wrapper lives in the module that owns OkHttp. The same artifact is already `connectImplementation` in `:app`; this line adds nothing to either flavour's runtime classpath. See ADR-015. |

## Considered and not added

The panorama utility (`pano/`, owner item #24) adds no row: it is pure Kotlin with no new dependency, no native
code and no manifest change. OpenCV was measured and rejected for it: its Maven artifact is about 117.7 MB as an
AAR, which adds about 24.8 MB to each installed arm64 APK (the native libraries are stored uncompressed) and
about 141 MB to the universal APK (184 MB measured, about 325 MB with it), and it has no `Stitcher` binding (the
Java classes stop at `calib3d`, `features2d` and `imgproc`; the C++ stitching module is compiled in but nothing
calls it from Kotlin), so it would still need our own blender and exposure code, or a JNI layer the repo has no
NDK build for.

Batch 8 (colour vision, the editor hub, the palette builder, animated pictures, the reel maker, the mood board and
Chroma ID) adds **no row and no Gradle dependency**. Everything is pure Kotlin or platform API: `java.util.zip`
(`Deflater`, `Inflater`, `CRC32`) for the PNG/APNG/GIF work, `android.hardware.camera2` and `SensorManager` for Chroma
ID, `BitmapFactory` for one still-WebP frame, and Media3 Transformer and its muxer, which the app already depends on
(`media3-muxer` arrives as an API dependency of `media3-transformer`). CameraX was considered and not added; the reasons
are in `docs/adr/ADR-017-camera-and-soundtrack.md`. The `CAMERA` permission and three optional camera `uses-feature`
lines are manifest changes, not dependencies.

Batch 9 (bundled world basemap and map-file import, cloud sync, the Services hub and Nooz catalogue, People, the timeline rail) adds **no row and no Gradle dependency**; no Gradle file changed. Sync's HTTP uses the OkHttp already in `feature:ai-remote`, its XML and JSON readers are hand-written, and OAuth uses the system browser, not a library. The world-map part: The world data in
`app/src/main/assets/basemap/world.fxmap` (about 220 KB, 1:50m land, lakes, country borders and the 1,000 largest
populated places) is built by `scripts/gen_basemap.py` from **Natural Earth** (naturalearthdata.com), which is in the
**public domain**: no attribution is required and the About screen credits it anyway. The generator is deterministic
(same input zips, same bytes), uses only the Python standard library, and its docstring documents the file format
the Kotlin reader (`basemap/BasemapCodec.kt`) decodes. Imported map files are raster MBTiles read with the
platform's `android.database.sqlite`; PMTiles and vector MBTiles are refused.

## App, media, ML and test dependencies (declared in `app/build.gradle.kts`, `feature/*`, `benchmarks/`)

Added by WP1.9: the table at the top covers only what WP1.1 put in the catalog, but the app's own dependencies had
no rows. Versions are the resolved ones from the release runtime classpath (`reportRuntimeLicences`) unless a row
says otherwise. "Size" is the artifact in Gradle's cache, not its contribution to the APK (that depends on R8,
resource shrinking and ABI splits, and is measured on the built APK; the ML Kit and MediaPipe native libraries are
the large part, about 121 MB across four ABIs per the comment in `app/build.gradle.kts`, not re-measured here).
Licences are read from each artifact's POM (or its parent) in Gradle's cache; a row says "unverified" where no POM
was available. Test-only rows are in the second table.

| Coordinate | Version | Licence | Why | Flavours | Size | Network-capable |
|---|---|---|---|---|---|---|
| `androidx.activity:activity-compose` | 1.10.1 | The Apache Software License, Version 2.0 | `ComponentActivity` + `setContent`, back handling and activity-result launchers for Compose. | both | AAR 1.1 MB | No |
| `androidx.core:core-ktx` | 1.16.0 | The Apache Software License, Version 2.0 | Kotlin extensions over AndroidX core (`ContextCompat`, `FileProvider` helpers). | both | AAR 176 KB | No |
| `androidx.compose:compose-bom` | 2025.05.01 | The Apache Software License, Version 2.0 | Bill of materials: aligns the Compose artifact versions below (the resolved versions are higher where another dependency asks for more). | both | BOM only (POM) | No |
| `androidx.compose.foundation:foundation` | BOM (resolves to 1.11.2) | The Apache Software License, Version 2.0 | Lazy lists/grids, gestures, `Canvas`: the whole UI's base. | both | KMP root; code is in the `-android` variant (not measured) | No |
| `androidx.compose.material3:material3` | BOM (resolves to 1.3.2) | The Apache Software License, Version 2.0 | Material 3 components used under the Hyle theme. | both | KMP root; code in the `-android` variant (not measured) | No |
| `androidx.compose.material:material-icons-extended` | BOM (resolves to 1.7.8) | The Apache Software License, Version 2.0 | The icon set for the UI (a very large set of vector icons). | both | KMP root; the `-android` AAR is large before shrinking (not measured here) | No |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.1 (resolves to 2.9.4) | The Apache Software License, Version 2.0 | `lifecycleScope` / `repeatOnLifecycle`. | both | Small (not measured) | No |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.1 (resolves to 2.9.4) | The Apache Software License, Version 2.0 | `collectAsStateWithLifecycle`. | both | Small (not measured) | No |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.1 (resolves to 2.9.4) | The Apache Software License, Version 2.0 | `viewModel()` in Compose: state survives rotation (WS-D). | both | Small (not measured) | No |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.9.1 (resolves to 2.9.4) | The Apache Software License, Version 2.0 | ViewModel + `SavedStateHandle` extensions (state across process death). | both | JAR about 5.7 KB | No |
| `androidx.lifecycle:lifecycle-service` | 2.9.1 (resolves to 2.9.4) | The Apache Software License, Version 2.0 | `LifecycleService` for the foreground job service. | both | AAR about 9.7 KB | No |
| `androidx.documentfile:documentfile` | 1.1.0 | The Apache Software License, Version 2.0 | `DocumentFile` over Storage Access Framework trees (folder pickers, sync folder). | both | AAR about 17 KB | No |
| `androidx.exifinterface:exifinterface` | 1.4.1 (resolves to 1.4.2) | The Apache Software License, Version 2.0 | Reads and writes EXIF/XMP in JPEG/PNG/WebP/HEIC (metadata editor, upright decode). | both | AAR about 74 KB | No |
| `androidx.heifwriter:heifwriter` | 1.0.0 | The Apache Software License, Version 2.0 | HEIC export (`HeifWriter`, platform codec). | both | AAR about 32 KB | No |
| `androidx.media3:media3-exoplayer` | 1.9.0 | The Apache Software License, Version 2.0 | Video playback in the viewer and the editor preview. Its manifest declares `ACCESS_NETWORK_STATE`, stripped by `src/offline/AndroidManifest.xml` (ADR-008); every source here is a local `content://` file. | both | AAR 1.7 MB | Network-capable code present (adaptive streaming), unused; Offline manifest removes the permission |
| `androidx.media3:media3-ui` | 1.9.0 | The Apache Software License, Version 2.0 | `PlayerView` controls. | both | AAR about 420 KB | No |
| `androidx.media3:media3-transformer` | 1.9.0 | The Apache Software License, Version 2.0 | Video export: trim, speed, rotate, crop, mute, HDR tone-mapping (ADR-008); its muxer arrives as a dependency. | both | AAR about 400 KB | No |
| `androidx.media3:media3-effect` | 1.9.0 | The Apache Software License, Version 2.0 | GL effects for Transformer exports. | both | AAR about 315 KB | No |
| `androidx.media3:media3-common` | 1.9.0 | The Apache Software License, Version 2.0 | Shared Media3 types. | both | AAR about 575 KB | No |
| `io.coil-kt.coil3:coil-compose` | 3.5.0 | The Apache License, Version 2.0 | Image loading for the grids and viewer (`AsyncImage`). | both | KMP root; code in the `-android` variant (not measured) | No: no `coil-network` artifact is on either classpath (checked in the resolved lists) |
| `io.coil-kt.coil3:coil-gif` | 3.5.0 | The Apache License, Version 2.0 | Animated GIF/WebP decoding in the grids and viewer. | both | AAR about 39 KB | No |
| `io.coil-kt.coil3:coil-svg` | 3.5.0 | The Apache License, Version 2.0 | SVG decoding. | both | KMP root; code in the `-android` variant (not measured) | No |
| `io.coil-kt.coil3:coil-video` | 3.5.0 | The Apache License, Version 2.0 | Video frame thumbnails. | both | AAR about 21 KB | No |
| `com.google.crypto.tink:tink-android` | 1.22.0 | Apache License, Version 2.0 | Streaming AEAD for the encrypted backup (ADR-014), behind one small interface (`backup/ChunkedAead.kt`). Pure JVM, no Play Services. | both | JAR 3.3 MB | No |
| `com.google.mediapipe:tasks-vision` | 0.10.35 | The Apache Software License, Version 2.0 | Runs the MobileNet embedder for "find look-alike photos". Bundles no model (see `MODEL_LICENSES.md`); ships native libraries. | both | AAR 227 KB of Java; the native libraries are the cost (measured in the APK, not here) | No |
| `com.google.mlkit:face-detection` | 16.1.7 | ML Kit Terms of Service (proprietary Google terms) | Bundled on-device face detection for People. The bundled artifacts need no Play Services app or download; they do declare `com.google.android.gms:play-services-*` libraries as dependencies (client code, same terms). | both | AAR 17.7 MB (includes native code); models 4.3 MB uncompressed | Not by itself; the bundled stack carries an internal OkHttp 3.0.0 that the Offline gate allow-lists (the manifest has no INTERNET) |
| `com.google.mlkit:image-labeling` | 17.0.9 | ML Kit Terms of Service (proprietary Google terms) | Bundled on-device image labels (Pets, scene). | both | AAR 2.5 MB; model 3.0 MB uncompressed | Same as above (vision-internal-vkp brings OkHttp 3.0.0) |
| `com.google.mlkit:text-recognition` | 16.0.1 | ML Kit Terms of Service (proprietary Google terms) | Bundled on-device text recognition (Identity, "Search inside this photo"). | both | AAR 1.4 MB; models 1.5 MB uncompressed | Same as above |
| `com.google.mlkit:translate` | 17.0.3 | ML Kit Terms of Service (proprietary Google terms) | On-device translation; ML Kit downloads a language pack on first use of a pair. | connect | AAR 27.7 MB | Yes: downloads language packs; Connect only |
| `org.maplibre.gl:android-sdk` | 13.0.2 | BSD (per the POM; `BSD-2-Clause` URL) | Street-level vector map, clustering, hillshade, 3D buildings. Declared `connectImplementation`, and `compileOnly` in `:feature:ai-remote` (see the table above). | connect | not measured here | Yes: fetches style and tiles; Connect only |
| `com.squareup.okhttp3:okhttp` | 5.3.0 (via the BOM below) | The Apache Software License, Version 2.0 | The one HTTP engine, owned by `:feature:ai-remote`: BYOK providers, cloud sync, model download. NOTE: the Offline classpath resolves a different `okhttp:3.0.0`, carried inside the bundled ML Kit stack, allow-listed exactly by `verifyOfflineRuntimeClasspath`. | connect | not measured here | Yes; Connect only |
| `com.squareup.okhttp3:okhttp-bom` | 5.3.0 | The Apache Software License, Version 2.0 | Aligns the OkHttp artifacts to one version (`platform(...)` in `:feature:ai-remote`). | connect | BOM only (POM) | n/a (build-time alignment) |
| `com.squareup.okio:okio` | 3.17.0 resolved (catalog entry above is 3.9.1) | The Apache Software License, Version 2.0 | Resolved as a dependency of OkHttp/Coil; the catalog row above is the `:core:*` entry for the same group:name. | both (transitive) | KMP root; code in the `-jvm` variant (not measured) | No |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 | Main-dispatcher coroutines for the app and `:feature:ai-remote` (the catalog row above is the `commonMain` core artifact at the same version). | both | AAR about 18 KB | No |
| `dev.aarso:hyle` | 0.2.0 | Apache-2.0 for the code (Hyle repository `LICENSE` and `NOTICE`, read); its fonts are OFL, see `MODEL_LICENSES.md` | Hyle Design System: theme, tokens, components. A Gradle composite build via the git submodule, not a Maven artifact. | both | built from source | No |
| `dev.aarso:crash-recovery` | 1.4.0 | Apache-2.0 (Shared-Libraries repository root `LICENSE`, head read) | Captures a device-only crash and shows a recovery screen on next launch. Composite build from the `shared-libraries` submodule. | both | built from source | No |
| `dev.aarso:cell-shell` | 0.1.0 | Apache-2.0 (Shared-Libraries repository root `LICENSE`, head read) | The constellation's navigation and motion shell (rooms, word wheel, edge scrubber). Composite build from the `shared-libraries` submodule. | both | built from source | No |

### Test, debug and benchmark only (never in a release APK)

| Coordinate | Version | Licence | Why | Network-capable |
|---|---|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 | Unit-test runner. | No |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | Apache-2.0 | Deterministic dispatcher for `JobRunner` tests. | No |
| `org.xerial:sqlite-jdbc` | 3.45.1.0 | The Apache Software License, Version 2.0 | FX-005 JVM perf baseline: a real SQLite file over JDBC. Bundles native libraries for desktop; test classpath only. | No |
| `org.robolectric:robolectric` | 4.14.1 | The MIT License | Android framework on the JVM for the screen tests. | No |
| `io.github.takahirom.roborazzi:roborazzi` | 1.32.2 | Apache-2.0 | Screenshot-test runner (record mode: proves nothing visual). | No |
| `io.github.takahirom.roborazzi:roborazzi-compose` | 1.32.2 | Apache-2.0 | Compose side of the same. | No |
| `androidx.compose.ui:ui-test-junit4` | 1.8.2 | The Apache Software License, Version 2.0 | Compose UI test rule. | No |
| `androidx.compose.ui:ui-test-manifest` | 1.8.2 | The Apache Software License, Version 2.0 | Manifest entry the Compose test rule needs (debug). | No |
| `androidx.compose.ui:ui-tooling` | BOM | The Apache Software License, Version 2.0 | Compose preview tooling (debug builds only). | No |
| `androidx.test.ext:junit` | 1.2.1 | The Apache Software License, Version 2.0 | Instrumented test runner glue (device-only tests). | No |
| `androidx.test.espresso:espresso-core` | 3.6.1 | The Apache Software License, Version 2.0 | Instrumented UI tests (device-only). | No |
| `androidx.test.uiautomator:uiautomator` | 2.3.0 | unverified (not in the local Gradle cache; the `:benchmarks` module is only built with `-Pfotoz.benchmarks`) | Macrobenchmark device driver. | No |
| `androidx.benchmark:benchmark-macro-junit4` | 1.3.4 | unverified (same reason) | Macrobenchmarks (`:benchmarks`, opt-in). | No |

## Licence scan (WP1.9)

`./gradlew :app:reportRuntimeLicences` writes, per flavor, every component of the resolved release runtime
classpath with the licence names from its POM (parent POMs followed), using only Gradle's artifact-resolution API.
`scripts/verify-licences.sh` applies the policy and `scripts/verify.sh` runs both: a component whose every declared
licence is GPL or AGPL fails the build; LGPL, CDDL, EPL, MPL, GPL-with-classpath-exception, an unrecognised licence
name, or a missing licence warns unless `scripts/licence-allowlist.txt` lists the component with a reason (an
unused allowlist line warns too). `scripts/verify-licences.sh --self-test` proves the policy on built-in fixtures,
including a deliberately bad GPL dependency, without touching the real build.

Result on 2026-11-04 (228 components on Offline, 236 on Connect): **no GPL or AGPL component on either flavor.**
Everything not allow-listed is Apache, MIT or BSD. The allow-listed exceptions are all Google's proprietary terms
(the bundled ML Kit stack, "ML Kit Terms of Service", and the `play-services-*` client libraries it depends on,
"Android Software Development Kit License", 18 components on Offline and 19 on Connect with ML Kit Translate) and
`org.checkerframework:checker-compat-qual` (dual GPL-2.0-with-classpath-exception / MIT; the MIT option applies).
That the bundled ML Kit stack is not open source is worth knowing before any public distribution; it is not a
licence conflict for sideloading.
