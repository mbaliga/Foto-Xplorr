# Dependencies

Every new Gradle dependency gets a row here before it lands (`MASTER-PLAN.md` §0.2 standing rule
2), and every ML model gets a row in `docs/MODEL_LICENSES.md` before use. WP1.9 adds a CI check
that fails when either file is out of date against the resolved classpath.

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
| `com.squareup.okio` | 3.9.1 | Apache-2.0 | `commonMain` file I/O abstraction, if/when a module needs it (most likely WP1.10's XMP sidecar writer) — not adopted by anything yet. | none (unused) | ~0.4 MB (JVM artifact) | No |

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
