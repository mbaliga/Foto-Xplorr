# Foto Xplorr — multi-platform porting plan (an amendment to MASTER-PLAN)

> Part of the constellation-wide porting program (`Personal-Tracker/PORTING_PROGRAM.md`, 2026-10-06).
> Status: **PLAN — nothing in this document has been built.** Every claim about a target platform is
> labelled with its evidence class (§0). This file is owned by the lead planning session; a platform
> track updates only its own §4 row and appends to `PROGRESS` entries in this repo's own state file
> (`docs/handoff/MASTER-PROGRESS.md`, which does not exist yet; Phase 1 creates it).

**Written:** 6 Oct 2026. **Baseline:** `main` @ `0ff1373` (Phase 0 merged as #13, 24 Sep 2026).

**Relationship to `docs/handoff/MASTER-PLAN.md`.** This file *amends* the master plan; it is not a
competing plan. Phases 0 to 9 stand as written, Phase 8 (Ubuntu Touch) and Phase 9 (Linux desktop)
in particular. **Phase 1 is the hard precondition of every port below.** Anything that would change
MASTER-PLAN text is written as a **PROPOSAL** and applies only if the owner approves it (§8, above all
Q1, which is program question OQ-7c: *pull the non-Android shells ahead?*). No MASTER-PLAN table was
edited. The one change outside this file is a pointer line in MASTER-PLAN's "Companion files" list.

## Reading guide: what stands, what is proposed

| MASTER-PLAN item | Status in this amendment |
|---|---|
| Phase 0 (defect brief) | Merged. Untouched. |
| Phase 1 (KMP core, catalogue v2, sync) | Untouched, and the hard precondition. Five **proposed** port-readiness additions to its acceptance (§5.4, §6.1); nothing in its WP table is rewritten. |
| Phases 2 to 7 (Android feature phases) | Untouched. Only if Q1 pulls shells ahead: a proposed *core-first* rule for them (§5.3). |
| Phase 8 (Ubuntu Touch) | **As written.** Amendment notes only (§6.2). |
| Phase 9 (Linux desktop) | **As written.** Amendment notes only (§6.3). |
| Phases 10, 11, 12 (iOS/iPadOS, macOS, Windows) | **Proposed new phases**, written as paste-ready WP tables (§6.4 to §6.6). Not part of the master plan until approved. |

## 0. Evidence labels (never dropped)

`PLAN` (this document) · `CI (hosted VM) evidence` · `SIMULATOR` · `CI-APPROX — NOT DEVICE EVIDENCE` ·
`NEEDS-DEVICE-VALIDATION` (NDV) · `NEEDS-OWNER-VALIDATION` (NOV) · `NOT-APPLICABLE (<reason>)` ·
`CONTAINER-BUILD-ONLY` (this container: JVM x86_64 compile and tests, nothing else) · `BROWSER-HEADLESS`,
plus the program's remaining asom labels (`LAB`, `EMULATOR EVIDENCE`, `SIMULATED — NOT DEVICE EVIDENCE`,
`VIRTUALIZED — NOT DEVICE EVIDENCE`, `SYNTHETIC`, `CI-ONLY / NOT RUN`). MASTER-PLAN's `[DEVICE]` tag on a WP means NDV. The Phase 0 log's
rule stands: device behaviour goes on a checklist and is never claimed verified.

## 1. What this repo is, in porting terms

- **Product.** Foto Xplorr ("Fotoz"): a local-first, Apache-2.0 Android photo, video and audio gallery
  (package `com.fotoxplorr.app`, `v0.3.0-ai-spatial`). Own SQLite catalogue indexed from MediaStore;
  fonebrew rooms navigation via `dev.aarso:cell-shell`; on-device recognition (ML Kit bundled, MediaPipe
  embeddings); Places, a compass view and two OpenGL ES 2 photo scenes; a non-destructive editor;
  metadata-clean sharing; opt-in BYOK remote AI in a separate `connect` flavour. Two flavours on one
  `connectivity` dimension: `offline` (no INTERNET, no network library, build-time enforced) and `connect`.
- **State** (`docs/handoff/PHASE-0-PROGRESS.md`, `docs/handoff/MASTER-PLAN.md`). Phase 0 is merged (all rows
  `done`). MASTER-PLAN (24 Sep 2026) lays out P1 to P9 "Android → Ubuntu Touch → Linux". **Phase 1 has not
  started:** no `MASTER-PROGRESS.md`, no `:core:*` modules, no `org.jetbrains.kotlin.multiplatform` plugin.
  The `hyle-design-system/` and `shared-libraries/` submodules are empty on disk here.
- **Targets today.** Android only (minSdk 26, compile/target 36), sideloaded debug-keystore-signed APKs
  (`.github/workflows/release-apk.yml`); no store listing yet (WP7.7). Ubuntu Touch and Linux are designed
  in Phases 8 and 9. iOS, iPadOS, macOS and Windows appear nowhere in the repo.
- **Stack.** Kotlin 2.1.0 (jvmTarget 17); Jetpack Compose (BOM 2025.05.01) + Material3; `dev.aarso:hyle`
  0.2.0, `crash-recovery` 1.4.0, `cell-shell` 0.1.0 via git submodule + `includeBuild`; Gradle 8.14.3,
  AGP 8.9.1 (pinned to Hyle's); Coil 3.5.0; ML Kit bundled face/label/OCR; MediaPipe tasks-vision;
  MapLibre and OkHttp 5 (connect only); six files defining `SQLiteOpenHelper` databases, 18
  files using `SharedPreferences`, no Room. Modules: `:app`, `:feature:ai-remote`; `:benchmarks` is not in
  `settings.gradle.kts`. **No NDK, CMake, JNI or AIDL in the repo.**
- **Size** (measured 6 Oct 2026). `find app/src/main -name '*.kt'`: 201 files, 44,843 LOC
  (`xargs cat | wc -l`); 210 files and 46,009 LOC adding `src/offline`, `src/connect` and `feature/`.
  Tests: 118 files, 14,436 LOC, 977 `@Test` (`grep -r '@Test' app/src/test | wc -l`), 24 files under
  Robolectric. **Already platform-free:** 71 files, 10,331 LOC with no `android`/`androidx`/`dev.aarso`/
  `coil`/`com.google`/`maplibre`/`okhttp3` import (loop of `grep -E '^import …'` over the main tree).
  Caveat: 13 of those 71 still import `java.time`, `java.util` or `java.io`, so "pure" means "no Android",
  not yet "Kotlin/Native-clean"; WP1.2's `linuxX64`/`linuxArm64` compile is the arbiter.

## 2. Portable core vs platform-bound layers

Portability: **pure** = no Android or Compose import (grep), JVM-testable today; **mixed** = pure logic
beside platform glue in one package; **bound** = Android or Android-only-library dependent. LOC are the
profile's per-package totals for the main tree, approximate; a package total includes its pure files, so
rows overlap and do not sum to the 44,843 of §1.

| Module / dir | Role | Portability | Approx LOC | Notes |
|---|---|---|---|---|
| Pure subset across packages: `search/`, `formats/` (7 of 8 files), `share/MetadataStripper`, `metadata/MetadataEdit`, `gallery/GalleryProjection`, `GridIndexMap`, `fileops` planners, `media/MediaIndexer`+`ScanPlan`+`SweepPolicy`, `adaptive/`, `curate/`, `palette/`, editor maths, `recognition` heuristics and clustering | Catalogue logic, query language, format sniffing, stripper, projections, edit maths | **pure** | 10,331 (71 files, measured) | Exactly the set WP1.2 names for `commonMain`. 13 of the 71 import `java.time`, `java.util` or `java.io` and need `kotlinx-datetime` and Kotlin-multiplatform replacements for Kotlin/Native. `adaptive/` (374 LOC) already models desktop and tablet window classes and shortcuts. |
| `media/` | MediaStore scan, change observer, original access, SQLite catalogue v3 with a full in-memory mirror | bound (pure subset inside) | 1,478 | The source layer. WP1.3/1.4 replace it with `:core:db` (Room KMP) and the `Source` interface. |
| `gallery/`, `viewer/`, `hyle/`, `ui/`, `adaptive/`, `lens/`, `picker/` | Compose screens, rooms, viewer, Hyle bridge, theme | bound (Compose + `cell-shell`/`hyle`) | 15,578 (7,418 + 4,618 + 3,542) | Portable to Compose Multiplatform **once** `cell-shell`, `hyle` and `crash-recovery` are KMP (all three are `com.android.library` today, verified in sibling checkouts). Not viable on Ubuntu Touch. |
| `spatial/`, `experience/` | Places, stamp map, compass, GL photo scenes | bound | 3,871 (2,750 + 1,121) | `GLSurfaceView` in `AndroidView`; no GL surface in Compose on desktop or iOS. `StampMapProjection` (140) and `PhotoSceneModels` (93) are pure. |
| `video/`, `videoeditor/`, `moments/`, `audio/` | ADR-008 MediaCodec remux/transcode, EGL bridge, key moments, audio scanner | bound (the most Android-native code) | 4,389 | Per target: AVFoundation, Media Foundation, or a separately loaded LGPL pipeline. Pure parts: `Container`, `VideoEditRecipe`, `KeyMomentDetector`, `PcmTiming`. |
| `recognition/`, `ai/` | ML Kit and MediaPipe indexers, stores, BYOK store, remote-AI seam | bound (clustering and heuristics pure) | 2,150 + 2,461 | ML Kit has no desktop or UT form. Behind `:core:ml-api` (master plan §2.1). |
| `editor/`, `lift/`, `palette/`, `curate/` | Non-destructive editor, subject lift, curation | mixed (largely pure maths; renderer bound) | 4,680 | `EditRenderer` uses `Canvas`/`ColorMatrix`/AGSL, which maps closely to SkSL on Skia. |
| `fileops/`, `organize/`, `favorites/`, `privacy/`, `pro/`, `background/` | MediaStore file operations, SharedPreferences stores, PBKDF2 gate, JobScheduler work | mixed | 2,798 | `WorkRules` (267) and the rename planners are pure; stores move into the DB in WP1.3. |
| `:feature:ai-remote` | OkHttp provider client and downloader | pure in practice | ≈300 | Declared `com.android.library` but needs only OkHttp and coroutines; becomes `kotlin("jvm")` for desktop, Ktor/Darwin for iOS. |
| `app/src/test` | 977 tests; Robolectric/Roborazzi (24 files) | about 80% pure | 14,436 | `CatalogueCharacterisationTest` goldens are the oracle every target must reproduce. |

**Platform-bound APIs that matter** (the profile lists 21; these decide the plan).

| API | Where | Porting impact |
|---|---|---|
| `MediaStore`, SAF, `FileProvider` | `media/`, `fileops/`, `share/`, `FotoXplorrActivity` | Replaced by the `Source` interface (§2.3): folders plus watcher on desktop and UT, PhotoKit on Apple. Trash: system trash, Recently Deleted, Recycle Bin. |
| `SQLiteOpenHelper` ×6, `SharedPreferences` ×18 | `media/`, `spatial/`, `recognition/`, `ai/`, `organize/`, … | Schemas are plain SQL and already run on JVM via `sqlite-jdbc` in tests. WP1.3 (Room KMP, `BundledSQLiteDriver`, FTS5) is the single most leveraged step and a precondition for every target. |
| Android Keystore (`EncryptedSecretStore`) | `ai/` | Keychain, libsecret, DPAPI behind one interface (F6); no keystore for confined Click apps. |
| ML Kit, MediaPipe | `recognition/`, `ai/`, `lens/` | Vision.framework and Core ML on Apple; ONNX Runtime or LiteRT with licence-audited models elsewhere. `docs/MODEL_LICENSES.md` does not exist yet. |
| `MediaCodec`/`MediaMuxer`/GL bridge | `video/`, `audio/`, `moments/` | Hard: a rewrite per target. TRAPS #36 lets a desktop v1 fail closed on video clean-share. |
| `ExifInterface`, `android.graphics`, Coil `-gif`/`-video` | `metadata/`, `media/`, `formats/`, `editor/` | WP1.10 shared EXIF/XMP/IPTC reader; decode by Skia/Coil 3, ImageIO, WIC, or dynamic libheif/libjxl/piex packs. |
| `GLSurfaceView`, `EGL14`/`GLES20` | `experience/`, `spatial/`, `video/gl/` | Re-render the scenes as Compose Canvas 2.5D (the docs already call them "experimental 2.5D, not a 3D engine"); OpenGL ES is deprecated on Apple. |
| `SensorManager`, `LocationManager`, shake-to-refresh | `experience/`, `spatial/` | Core Motion/Location and QtSensors are native fits; desktops have no gyro or GPS, so the compass becomes an orbit view and refresh a command. |
| `JobScheduler`, manifest contracts, `FLAG_SECURE`, runtime permissions | `background/`, `AndroidManifest.xml` | Per-shell contracts. No `FLAG_SECURE` or picker-provider analogue on iOS; no INTERNET permission to prove the offline flavour off Android. |

## 3. Binding rules this port must not break

This repo has no `CLAUDE.md`; its rules live in `docs/handoff/MASTER-PLAN.md` §0.2 (MASTER), `docs/handoff/PHASE-0-BRIEF.md` §0.2
(P0B), `docs/TRAPS.md` (TRAPS), `README.md` and `docs/PRODUCT-AND-ARCHITECTURE.md` §4 (ARCH).

- **Offline identity is sacred** (MASTER §0.2 #3; TRAPS #12; P0B #4). No INTERNET or network-state permission in the merged manifest,
  no network library on the resolved runtime classpath, no network API in offline-visible sources, enforced by `verifyOfflineManifest`,
  `verifyOfflineRuntimeClasspath`, `verifyOfflineSourceReferences` and never loosened. Model packs reach `offline` by side-load only.
  TRAPS #12 is explicit that the gate is manifest plus classpath plus a *targeted* FQCN denylist, not a package-level import ban
  (`java.net.URI` and `android.net.Uri` do no I/O). **Every port carries an equivalent offline/connect split and gate** (§6.0).
- **Privacy** (README; ARCH §4): no backend, account, analytics or mandatory cloud; nothing leaves the device unless the user shares it;
  remote AI is opt-in BYOK with keys in an encrypted store and an HTTP client with no logging interceptor; location indexing starts
  only when the user opens Places. Program I-1 (no telemetry, any platform) and I-2 (report the key-storage tier) restate this.
- **Licensing** (MASTER §0.2 #1; repo is Apache-2.0): allowed Apache-2.0, MIT, BSD, ISC, zlib, MPL-2.0 (file level), public domain; LGPL
  and CDDL (LibRaw) only as a separately loaded, replaceable shared library with a documented source offer; never GPL/AGPL; never
  non-commercial model weights (MobileCLIP, InsightFace, EdgeFace, Jina CLIP v2, RMBG, YOLOv8/11); no code from Fossify, lomiri-gallery-app,
  digiKam, Immich, PhotoPrism, Loupe, gThumb, Shotwell; avif-coder 3.x only. Program I-11 agrees.
- **Dependency governance** (MASTER §0.2 #2): every new dependency gets a `docs/DEPENDENCIES.md` row and passes the offline gates; every
  model gets a `docs/MODEL_LICENSES.md` row first. Neither file exists yet; WP1.9 creates the CI checks.
- **Toolchain lockstep** (TRAPS #2, #3, #4, #6; MASTER §0.2 #7): AGP, Gradle, Kotlin match `hyle-design-system` and `shared-libraries`
  exactly; only WP1.1 bumps them; submodule changes go through their own repos as merge commits, never squash; the explicit
  `dependencySubstitution` for `dev.aarso:hyle` is load-bearing. P0B #2: do not edit the submodules from here. Program OQ-17 owns the pin.
  (MASTER §0.2 says all P0B §0.2 rules still apply, which read literally would forbid WP1.1's bump and new dependencies; this plan reads
  P0B #1 and #3 as superseded for Phase 1 onward by MASTER §0.2 #2 and #7. Q7 asks the owner to confirm.)
- **Data safety** (MASTER §0.2 #4; TRAPS #1, #7, #8, #17, #35): no path deletes or overwrites an original except an explicit, confirmed
  action; destructive operations go through a trash; writes are temp → verify → replace; a partial scan never sweeps; an absent source is
  `OFFLINE`, never deleted; trash needs the platform confirmation, otherwise decline (TRAPS #7).
- **Share hygiene** (TRAPS #31, #32, #36; P0B #9): a share output is verified clean or it is not sent; a metadata read failure is not an
  absence; only a location read may ask for the unredacted original.
- **Every feature ships complete** (MASTER §0.2 #5; `docs/competitor-parity.md`): empty, error and permission states, reversibility,
  accessibility labels, keyboard and mouse support, 100k-item behaviour. Budgets in MASTER §2.4 are acceptance criteria.
- **Design** (TRAPS #13, #14, #29, #30; `docs/fonebrew-navigation.md`): never state in colour alone; never the word "Synced"; the cove meets
  an edge; render a layout before believing it. Fonebrew navigation (rooms, word-wheel rail, edge scrubber, no hamburgers or bottom nav) is
  an owner mandate for every app. **Pull-down is reserved for the top room**, so no port may use it for refresh (the app's refresh is a shake,
  ARCH §2); desktop gets a command or key. WP8.4 already cedes the bottom edge to Lomiri.
- **Goldens** (MASTER §0.4; P0B #7): `CatalogueCharacterisationTest` changes only by its own `FX_GOLDENS_PRINT` procedure. The KMP core
  must reproduce it identically on every target.
- **Honesty** (README; MASTER §0.3): no device or emulator in the session; the private-folder feature is an access gate, never an
  "encrypted vault"; the offline map is a coordinate visualisation; the depth timeline is 2.5D; Pro UI never claims money changed hands.
  No third `play` flavour is created unasked. ADR-006's map end state stays an owner decision.
- **Process** (MASTER §0.1, §0.6): phases are sequential with an owner gate; branch `claude/fotoz-p<N>-<slug>` per phase, one commit
  per WP (`WP<n.m>: <title>`), one **draft** PR per phase, never merge; progress in `MASTER-PROGRESS.md`; `./scripts/verify.sh` summary
  in every gate report. This docs-only PR is on the program branch and is not a phase branch.
- **Program rules R1 to R4, R6, R11, R12** (`Personal-Tracker/PORTING_PROGRAM.md` §3): disjoint directories per track; the existing gate
  stays green; new CI workflow files only; pure core first; unsigned artefacts only, nothing stored on PRs, release binaries to draft
  Releases; no per-platform identifier before a NAMES.md row; reframes labelled as reframes.

## 4. Target matrix (owner's order)

Efforts are the program's §5 cells for this repo (engineer-weeks, **estimates**) and cover the target phase only; they exclude Phase 1
(the profile's reader put it near 10 weeks; MASTER-PLAN gives no figure and this plan has not derived one) and the shared F-items (§7).

| Target | Feasibility | Approach | Blockers | Effort (eng-weeks, estimate) | Evidence today |
|---|---|---|---|---|---|
| Ubuntu Touch | moderate | **Phase 8 as written.** Kotlin/Native `linuxArm64` `libfotozcore.so` with a coarse C API over `:core:*`; C++ `QObject`/`QAbstractListModel` bridge; Lomiri UI Toolkit (Qt 5.15) QML shell in `ut/`; Clickable on framework `ubuntu-touch-24.04-2.x`; `content_exchange` plus reserved `picture_files_read`/`video_files_read`, Content Hub import as fallback; `LINUX_DIR` source with inotify; QImageReader plus dynamic libheif/libde265, libjxl, piex; v1 scope per WP8.6. Compose is not an option on UT (no JVM in the Click path). | Phase 1 (not started); P7 gate (reordering is Q1); Kotlin/Native `linuxArm64` is Tier 2; reserved groups may be refused (Q6); no keystore and no background execution under Lomiri; no UT device on record (Q5); a second UI in QML (`cell-shell`/Hyle reusable as spec and tokens only) | 16 | PLAN |
| Linux desktop | moderate (straightforward once Phase 1 and F1 to F3 land) | **Phase 9 as written.** Compose Multiplatform Desktop (JVM) `:desktop:app` over the JVM `:core:*`, unless the WP9.1 spike picks Qt/QML. `LINUX_DIR` via XDG FileChooser and Documents portals, inotify plus rescan, udisks2/GIO, XDG Trash, libsecret, ONNX Runtime, Skia/Coil 3 plus libheif/libjxl/LibRaw by JNA/FFM or libglycin; scenes re-rendered on Canvas; video clean-share fails closed until a separately loaded LGPL pipeline exists; Flatpak on Flathub. | Phase 1 and Room KMP; `cell-shell`, Hyle and `crash-recovery` are Android-only (F1 to F3, in other repos); no desktop actual yet for the §2 bound APIs; MASTER-PLAN puts Phase 9 after P8 and P7 (Q1); Compose Desktop is XWayland-only; no Flatpak CI job | 14 | PLAN |
| iOS / iPadOS | moderate | **Proposed Phase 10.** Compose Multiplatform iOS in a thin SwiftUI shell over `:core:*` for `iosArm64`/`iosSimulatorArm64`; `:platform:ios`: PhotoKit source (limited library = partial access), Vision and Core ML behind `:core:ml-api`, ImageIO decode, AVFoundation, Core Motion/Location, Keychain (F6), `BGProcessingTask`, share sheet and document types; Skia/Metal Canvas for the 2.5D scenes; Ktor/Darwin and MapLibre Native in `connect`. iPadOS rides the same build (window size classes already modelled). | Phase 1 with iOS targets; F1 and F3 iOS actuals; Apple Developer Program, a delivery route to the iPad Pro M4, and a `macos-latest` lane (Q15); the whole platform layer is new; no `FLAG_SECURE` or picker-provider analogue; fonebrew edge gestures vs iOS system gestures (unknown); App Review on a BYOK-AI build; absent from MASTER-PLAN (Q2) | 18 | PLAN |
| macOS | straightforward, **only if** WP9.1 keeps Compose Desktop | **Proposed Phase 11.** The Phase 9 JVM `:desktop:app` packaged by `jpackage` as a Developer-ID-signed, notarised `.dmg` outside the Mac App Store; `:platform:macos` actuals by JNA/FFM: Keychain, ImageIO, AVFoundation or Vision (or ONNX CoreML provider), trash, `NSSharingService`, Info.plist document types. | Phase 9 first; Developer ID and notarisation secrets (OQ-3); no Mac on record, so device gates are NOV (Q15); native bridges are new code; absent from MASTER-PLAN (Q2) | 5 (the profile estimates about 3 more for Mac App Store sandboxing, bookmarks and two bundle ids; reader estimate, not in the program row) | PLAN |
| Windows | straightforward, **only if** WP9.1 keeps Compose Desktop | **Proposed Phase 12.** The same JVM shell packaged by `jpackage` as a WiX MSI plus a winget manifest; `:platform:windows` actuals by JNA/FFM: known folders, Recycle Bin (`IFileOperation`), DPAPI/Credential Manager, WIC or the shared libheif/libjxl/LibRaw packs, Media Foundation or a separately loaded LGPL pipeline; ONNX DirectML. | Phase 9 first; a signing route (Azure Artifact Signing is unavailable to the owner as an individual in India, program OQ-3); the Windows path lint (R3); the Dell's fate (Q17); absent from MASTER-PLAN (Q2) | 6 | PLAN |

Totals: 16 + 14 + 18 + 5 + 6 = 59 engineer-weeks across the five targets (estimate; overlapping with F-item work). Q4's two-shell
warning is the 16 + 14 = 30 weeks of Ubuntu Touch plus Linux (program OQ-8). **If the WP9.1 spike picks the Qt/QML shell,** macOS and
Windows become Qt 6 shells like Fylz's and both rows must be re-estimated; no figure is offered for that case (unknown).

## 5. Tier and sequencing

### 5.1 Tier

**Tier B** (program §5 row: "Phase 1 not started; main bare"). Foto Xplorr is the constellation's largest active app and the only repo with
an owner-written porting roadmap, but nothing of Phase 1 exists on `main`, so no wave can start on this repo yet. (A reader's profile argued
for A-flagship; this plan follows the program row and leaves any promotion to the owner under Q1.) Phase 1 needs no reordering and can
start now either way; it is also where the constellation's shared decisions are forced (F1 to F6, program OQ-17).

### 5.2 Waves (program §7) and the repo-local gate before each

| Wave | What Foto Xplorr does there | Repo-local gate that must hold first | Device entry (owner) |
|---|---|---|---|
| **P-0 Foundation** | Phase 1 (own program, WP1.1 to 1.10); consumes F1, F3, F5, F6 and, after PT:D-P, F2 | OQ-17 ruled; submodule PRs merged with merge commits (TRAPS #2); `VERIFY OK` and unchanged goldens | RedMagic 11 Pro for the P1 gate |
| **P-UT b** (own gate, not re-sequenced by the program) | Phase 8 as written | Phase 1 done with `linuxX64`/`linuxArm64` green in CI; P7 released unless Q1 amends it | A UT device (OQ-1, Q5); until then `CI (hosted VM)` and `CI-APPROX` only |
| **P-LX** | Phase 9 as written, after Phase 1 and the WP9.1 spike | Phase 1 done; WP9.1 [ADR] spike reported; P8 per MASTER-PLAN sequencing unless Q1 amends it | Steam Deck, the Dell only if OQ-5 says Linux, RedMagic under Termux:X11; checklist seeded from F11 |
| **P-iOS** (iPadOS first) | Proposed Phase 10, "after OQ-7c" | Q1 and Q2 answered; Phase 1 with iOS targets; F1/F3 iOS actuals; the CMP-iOS recipe proven on Clavis in the simulator | Apple Developer Program and delivery route (OQ-2); the iPad Pro M4 is the only Apple device on record |
| **P-mac** | Proposed Phase 11 | Phase 9 desktop shell exists and is Compose; OQ-3 secrets | No Mac on record: device gates NOV (OQ-5) |
| **P-win** | Proposed Phase 12 | Phase 9 desktop shell exists and is Compose; path lint in place; OQ-3 route or accepted unsigned | The Dell only while it is Windows (OQ-5); afterwards `CI (hosted VM)` only |

### 5.3 OQ-7c: pull the non-Android shells ahead? (Q1, the owner's call)

MASTER-PLAN gates Phase 8 on "P1's KMP core compiles for `linuxArm64`/`linuxX64` headless, **and P7 is released**", and Phase 9 after
Phase 8. Phases 2 to 7 are the Android feature phases (formats, sources, organise, search, editing, privacy and release). Options:

- **A. As written.** Shells follow P7; Phases 10 to 12 follow P9. Cheapest to coordinate; the first non-Android artefact is the last thing built.
- **B. Pull shells ahead.** After Phase 1, build a read-mostly desktop shell (browse, view, search tier A) in parallel with Phases 2 to 7.
  Validates Compose MP, `cell-shell` KMP and the JVM core early. **Cost and rule:** every WP in Phases 2 to 7 becomes *core-first* (R4), landing in
  `:core:*` with the Android UI as one consumer, otherwise the shells fall behind; the QA surface grows with every phase.
- **C. Linux first only.** Pull just the Phase 9 desktop skeleton after Phase 1 (it carries macOS and Windows later); Ubuntu Touch stays after P7.
  This matches the program's reading that Linux is the proving ground.

This plan does not decide. If B or C is chosen, the Phase 8 and 9 precondition lines are the only master-plan text to change (A5 below).

### 5.4 Proposed amendments to MASTER-PLAN text (none applied; for owner approval)

| # | Where in MASTER-PLAN | Current text (abridged) | Proposed change | Why |
|---|---|---|---|---|
| A1 | §1.3 Platforms row | "Android …; Ubuntu Touch (Lomiri/QML); Linux desktop (Flatpak)" | Append "; iOS/iPadOS (Compose Multiplatform, PhotoKit); macOS and Windows (the desktop shell, packaged)". Tier stays M for the first two; the new three await Q2. | The owner's order now names five targets. |
| A2 | §2.1 module map | `:platform:android`, `:ut:*`, `:desktop:app` | Add `:platform:ios` (iosMain), `:platform:desktop` (jvmMain, cross-OS) with `:platform:linux`, `:platform:macos`, `:platform:windows` (jvmMain, OS bridges), `ios/` (Xcode head). Add targets `jvm()`, `iosArm64`, `iosSimulatorArm64` to `:core:*`. **No** `mingwX64` (Windows runs the JVM build) and **no** `macosArm64` (desktop Compose is JVM only). | Keeps R1 disjoint directories and R4 core-first. |
| A3 | §2.2 `sources.kind` | `…`, `LINUX_DIR`, `UT_CONTENT` | Replace `LINUX_DIR` by `LOCAL_DIR` (a folder on any desktop OS) and add `PHOTOKIT`. | Schema v2 is forward-only (TRAPS #15); naming the kinds now is free, renaming later is a migration. |
| A4 | WP1.2, WP1.3, WP1.9 acceptance | Linux headless targets; Room KMP; "no Android import in core" | See §6.1 (five additions). | Fail early on the targets later phases need. |
| A5 | Phase 8 and 9 precondition lines | "…and P7 is released"; Phase 9 after Phase 8 | Only if Q1 = B or C: "Phase 1 done" (and, for Phase 9, WP9.1 reported). | OQ-7c. |
| A6 | §3 phase list | P0 to P9 | Add Phases 10 to 12 (§6.4 to §6.6) after Phase 9. | New scope (Q2). |

## 6. Work breakdown

### 6.0 Placement and gate rules for every step (R1 to R4 applied here)

- **Directories.** Existing names win: Click project in `ut/` (WP8.2), `:desktop:app` in `desktop/` (§2.1). New: `ios/` (XcodeGen
  `project.yml`, SwiftUI shell, privacy manifest; the program's generic `apple/` name is not used because macOS is JVM and shares nothing with
  it). Packaging templates (Flatpak, WiX, DMG, Click metadata) under `packaging/<os>/`. Source sets: `iosMain`, `jvmMain`, `linuxX64Main`/
  `linuxArm64Main`; `androidMain` and `:app` change only by their normal phase PRs.
- **CI.** New workflow files only; `android-debug.yml` and `release-apk.yml` stay untouched by any port (R3): `core-kmp.yml` (Phase 1),
  `ut-click.yml`, `desktop-linux.yml`, `ios.yml`, `desktop-macos.yml`, `desktop-windows.yml`. Actions SHA-pinned; package and sign lanes run on
  `main`/tags only and are compile-only on PRs; no PR uploads an artefact; every artefact is `UNSIGNED — not for release`; signing and store
  jobs exist only as disabled templates until OQ-3 (R6). The Windows path lint (reserved names, `:<>|?*"`; `-text` on byte-exact fixtures and
  goldens) lands before `desktop-windows.yml` (R3).
- **One gate definition per shell.** `scripts/verify.sh` stays the Android definition of "builds clean" (FX-002); Phase 1 decides whether the
  headless core tests join it. Proposed: each shell gets a `scripts/verify-<shell>.sh` that its workflow calls, so local and CI cannot drift.
- **Offline gate per target** (names are proposals; order of authority per TRAPS #12): manifest or equivalent first (Android permission;
  Click AppArmor group omitted; Flatpak `finish-args` without `--share=network`, plan to verify; iOS privacy manifest), then the resolved
  runtime classpath of the offline variant (no `okhttp3`, `io.ktor`, `org.maplibre`, `:feature:ai-remote`), then a targeted FQCN denylist.
  **Windows, macOS and iOS have no OS-level proof of "offline"**, so their gate is the classpath plus source checks alone (Q8).
- **What the container can verify.** JVM x86_64 compile and tests of `:core:*` and the desktop module; nothing else. No Swift or Xcode, no
  Clickable or Docker, no `flatpak-builder`, no Windows, no device. Every step below says which of its checks are CI-only or NDV.
- **ADRs this amendment adds** (numbers assigned as written; ADR-006 to 008 exist, so the next free is ADR-009; the Phase 1 ADRs take
  the first numbers): `ut-architecture` (WP8.1) and `desktop-shell` (WP9.1) as written, the latter's scope **proposed** to record that macOS
  and Windows ride the chosen shell; **proposed** `apple-architecture` (WP10.1), `media-pipeline-off-android` (extends ADR-008, which is
  Android-only; Q11), `offline-identity-and-secrets-per-platform` (Q8, Q9) and `desktop-distribution` (WP11.1 and 12.1). Architecture
  ADRs get the owner's Opus review (MASTER §7).
- **Checklists** follow the repo pattern `docs/device-test/phase-<N>-checklist.md` (phases 8 to 12), each seeded from program F11's template.

### 6.1 Precondition: Phase 1 (own program) plus five proposed port-readiness additions

Phase 1 is unchanged. These additions are proposed acceptance lines, to be adopted or dropped by the owner; each is cheap now and costly later.

1. **WP1.2:** compile `:core:*` also for `jvm()` and, compile-only on hosted `macos-latest`, for `iosSimulatorArm64`, beside `linuxX64`/`linuxArm64`.
   The 13 of the 71 platform-free files that import `java.time`, `java.util` or `java.io` move to `kotlinx-datetime` and Kotlin-multiplatform
   replacements (dependency rows first, MASTER §0.2 #2). Done when the `:core:*` test tasks pass on every declared target in `core-kmp.yml` (`CI (hosted VM)`).
2. **WP1.3:** the Room KMP spike also runs the FTS5 query path and the characterisation goldens on `iosSimulatorArm64` (the "same SQLite and
   FTS5 everywhere" claim is an assumption on iOS until tested). Done when goldens are identical there (`SIMULATOR`).
3. **WP1.3 and WP1.4:** adopt A3 (`LOCAL_DIR`, `PHOTOKIT`), and give each `Source` a case-sensitivity and Unicode-normalisation policy for
   `folder_key` and `relative_path` identity (Windows and default macOS volumes are case-insensitive; macOS may store decomposed names).
4. **WP1.9:** generalise the "no Android import in core" check to "no platform import in core" (`android.*`, `java.awt.*`, `platform.*`
   outside the matching source set), by adopting F5's check rather than hand-rolling a second one.
5. **Interfaces:** the secret store, folder picker, share sheet, power probe and app-directory interfaces come from F6 where F6 provides them;
   `EncryptedSecretStore` becomes the Android actual. Do not invent a parallel set in `:core:*`.

### 6.2 Phase 8, Ubuntu Touch: as written (WP8.1 to 8.7); amendment notes only

- **Not blocked by S-UT1.** The program's S-UT1 spike concerns a jlinked JVM inside a click; Phase 8 is Kotlin/Native plus QML and is one of the
  program's named R8 exceptions. It *is* blocked by OQ-1 for any device evidence.
- **Foreground-only indexing** is already the plan (WP8.6) and is what Lomiri forces: it SIGSTOPs unfocused confined apps about 1.5 s after the
  suspend request. Sync and index jobs must be resumable and treat "interrupted" as normal; the resumable breadth-first walk of §2.3 fits.
- **Proposed: no BYOK on the UT v1 Click** (program I-2 carries asom's rule; whether it binds this repo is Q9/OQ-22). The v1 Click is the
  `offline` build, consistent with WP8.6's deferral of network sources; a `connect` Click stays possible as WP8.3 writes it (`networking` group)
  once Q9 is answered. **Reserved groups** (`picture_files_read`, `video_files_read`) get manual review for **open-source apps only**; this repo
  is Apache-2.0 and so is eligible. Any future closed Pro module would be confined to common groups.
- **Qt 5.15 is end-of-road:** run the 26.04 canary lane from the first Clickable job, not at WP8.7. Whether Clickable's CI images include a
  24.04-2.x image is not established by the program documents; WP8.2 verifies (unknown).
- **Evidence:** the build (`ut-click.yml`, Clickable, K/N cross-compile on `ubuntu-latest`) is `CI (hosted VM)`; `ubuntu-24.04-arm` can run an
  arm64 userland smoke test (`CI-APPROX — NOT DEVICE EVIDENCE`). Halium GLES, sensors, Content Hub, lifecycle and OpenStore review are all NDV.
- **Waydroid** (program OQ-21) is not a port and does not replace Phase 8; if the owner holds a UT device, an unmodified APK under Waydroid is
  owner-device evidence only, labelled as such (R12).

### 6.3 Phase 9, Linux desktop: as written (WP9.1 to 9.6); amendment notes only

- **WP9.1's spike also decides macOS and Windows** (§4 note). Hosted runners can compile and give rough startup
  (`CI-APPROX — NOT DEVICE EVIDENCE`); MASTER §2.4's UI budgets (cold start ≤ 900 ms at 100k) need an owner machine, NDV. The JVM catalogue
  query numbers (`ProjectionPerfBaselineTest`, `docs/perf/baseline-jvm.md`, ±10%) can be re-run in CI.
- **Start without Wayland, Vulkan or Bluetooth.** Compose Desktop is XWayland-only today (X11 path first, Wayland behind a flag); the Redmagic-Edge
  Termux:X11 target is software GL. The Canvas 2.5D scenes (not a GL window) keep that target usable; Steam Deck Desktop and Gaming Mode are NDV.
- **WP9.5 packaging** consumes F10's templates: Flatpak primary (Flathub builds offline, so the manifest consumes a prebuilt app-image from a draft
  Release), `jpackage` tarball fallback; the Flatpak app id needs a NAMES.md row first (R11, Q16). Optional Snap stays as written; retiring
  AppImage/Snap constellation-wide is program OQ-4. If a lane builds native libraries itself, the program's glibc rule applies (build on `ubuntu-22.04`, so a binary still starts on SteamOS).
- **Desktop affordances, labelled as reframes (R12):** compass and scenes become an orbit view with mouse and keyboard; refresh is a command or `F5`, never
  pull-down (§3). `LINUX_DIR` becomes `LOCAL_DIR` if A3 is adopted, so macOS and Windows reuse the source.
- **Build:** `desktop-linux.yml` runs JVM tests and `createDistributable` for x86_64 (the only Linux lane this container could in principle
  verify; `.deb`/`.rpm`/Flatpak cannot be built here). The desktop offline gate of §6.0 lands with `:desktop:app`, before any packaging.

### 6.4 Phase 10, iOS / iPadOS (PROPOSED; paste-ready for MASTER-PLAN §3 if approved)

Precondition: Phase 1 done with the iOS compile targets (§6.1 item 1), F1 and F3 iOS actuals available, and Q1, Q2 answered. iPadOS first
(program §4.3). Estimate: 18 engineer-weeks; no per-WP split, because none can be derived before the core exists.

| WP | Title | Content | Acceptance |
|---|---|---|---|
| 10.1 [ADR] | Apple architecture | CMP iOS in a SwiftUI shell via the Objective-C framework (Kotlin Swift export is Alpha: not used); what is `iosMain` vs Swift; library scope (Q21: Photos library, Files folders, or both); `offline`/`connect` as two schemes with ids from NAMES.md (R11); privacy manifest; iCloud Backup stance (Q22); the fonebrew edge-gesture conflicts (system back swipe, home indicator, Control Center) and the Core Motion shake for refresh. | ADR reviewed by the owner. No code. |
| 10.2 | Core on iOS | `:core:*` build and test for `iosArm64`/`iosSimulatorArm64`; Room KMP iOS driver; goldens through it. | `iosSimulatorArm64Test` green in `ios.yml`, goldens unchanged (`SIMULATOR`, `CI (hosted VM)`). |
| 10.3 | PhotoKit source | `PHOTOKIT` kind. Limited library maps to partial access: assets leaving the selection become `OFFLINE`/`REVOKED`, never swept (TRAPS #8). Change observation by persistent change tokens (to verify in the ADR). Delete goes through the system prompt to Recently Deleted (satisfies TRAPS #7). iCloud-optimised originals not on the device are an availability state, not an error (TRAPS #32); the `offline` scheme must not trigger iCloud downloads (design intent, Q8). | Unit tests on a fake source; the real limited-library flow is NDV. |
| 10.4 | Decode and metadata | `DecoderProvider` over ImageIO/Core Graphics for HEIC, AVIF, RAW where the OS supports them (which formats on the baseline OS is verified per format against the WP1.8 corpus: unknown today); EXIF/XMP via WP1.10's reader. Pixels still come from one decode entry point (TRAPS #34 analogue). | Corpus decode on the simulator (`SIMULATOR`); on-device decode NDV. |
| 10.5 | Recognition and embeddings | Vision (faces, text, classification) and Core ML embedder behind `:core:ml-api`; clustering and heuristics stay in core; every model gets a `MODEL_LICENSES.md` row first; the engine id is part of the revision key so embeddings from different engines are never mixed; face and biometric tables excluded from iCloud Backup (MASTER §6). | Pure tests in core; Vision/Core ML behaviour NDV. |
| 10.6 | Video | AVFoundation remux/transcode and thumbnails behind a `VideoPipeline` interface that keeps ADR-008's contract: a share is verified clean or it is not sent (TRAPS #36). | Interface tests plus the new ADR `media-pipeline-off-android`; on-device NDV. |
| 10.7 | Platform services | Keychain (F6; `WhenUnlockedThisDeviceOnly`, never synchronisable); Core Motion/Location for compass and scenes; `BGProcessingTask` indexing (OS-timed; `WorkRules` still applies); share sheet and document types for open-with. **`NOT-APPLICABLE (no picker-provider analogue on iOS)`** for `PhotoPickerActivity`; `FLAG_SECURE` has no analogue (screenshot detection only), so the "screenshots blocked while unlocked" copy is restated per platform. | Copy reviewed; services NDV. |
| 10.8 | UI | CMP screens over F1/F3 for iPhone and iPad; `adaptive/` size classes, shortcuts and nav-rail presentation; scenes as Canvas 2.5D (OpenGL ES is deprecated; shared with Phase 9). | Simulator screenshots, `SIMULATOR`; gesture feel NDV. |
| 10.9 | Connect flavour | `:feature:ai-remote` behind an interface with Ktor/Darwin; MapLibre Native iOS; App Review items: no downloaded executable code (2.5.2), disclosure before large model downloads (4.2.3(ii)), consent before personal data reaches a third-party AI (5.1.2(i)). Model packs are data. | Classpath and source gates green for the `offline` scheme; submission is NOV. |
| 10.10 | CI and evidence | `ios.yml` on `macos-latest`: simulator build and tests, `CODE_SIGNING_ALLOWED=NO`, privacy-manifest lint, no uploads. `docs/device-test/phase-10-checklist.md`. | Green hosted run. Device gate NDV on the iPad Pro M4 only after OQ-2; iPhone items NDV until an iPhone exists. |

### 6.5 Phase 11, macOS (PROPOSED)

Precondition: Phase 9 shipped the Compose desktop shell (otherwise re-plan). Estimate: 5 engineer-weeks. No Mac is on record: device gates are NOV.

| WP | Title | Content | Acceptance |
|---|---|---|---|
| 11.1 [ADR] | Distribution | Developer-ID `.dmg` outside the Mac App Store (plain folder access, no sandbox) vs a sandboxed Mac App Store build (security-scoped bookmarks, two bundle ids); hardened-runtime entitlements from F10's verdict (minimum set, never `jpackage`'s default sandbox plist). Q16, OQ-3. | ADR reviewed. |
| 11.2 | `:platform:macos` | By JNA/FFM: Keychain (F6; never the `security` CLI), ImageIO decode, AVFoundation or Vision (or ONNX with the Core ML provider), trash, `NSSharingService`, Info.plist document types. JVM `WatchService` on macOS is, to our knowledge, polling-based (verify in a spike); native FSEvents by FFM is the fallback. | Unit tests with fakes on every runner; native calls NOV. |
| 11.3 | Source identity | `LOCAL_DIR` on APFS: case-insensitive by default and Unicode-normalised names, per §6.1 item 3. | Tests with colliding names. |
| 11.4 | Package | `desktop-macos.yml` on `macos-latest`: `jpackage` `.dmg` on `main`/tags only; sign and notarise as **disabled templates** until OQ-3; never uploaded to Actions storage. | Unsigned `.dmg` builds (`CI (hosted VM)`); Gatekeeper behaviour NOV. |
| 11.5 | Later, optional | Photos.app library source via PhotoKit for parity with iOS (no JVM binding known: unknown). "Designed for iPad" presence is a by-product of Phase 10 and not a deliverable. | Not scheduled. |

### 6.6 Phase 12, Windows (PROPOSED)

Precondition: Phase 9 shipped the Compose desktop shell, and the R3 path lint is in place. Estimate: 6 engineer-weeks. The Dell is the only Windows machine, and it is slated to become Linux (OQ-5).

| WP | Title | Content | Acceptance |
|---|---|---|---|
| 12.1 [ADR] | Distribution and signing | WiX MSI plus winget; MSIX/Store re-signing; SignPath Foundation (public OSS repo), or OV on HSM, or unsigned until OQ-3. Q16, Q17. | ADR reviewed. |
| 12.2 | Path lint and fixtures | Windows-checkout path lint over `testdata/formats/` (WP1.8) and any fixture directory the test tree gains (none exists today; `app/src/test/` is Kotlin only); `-text` on byte-exact fixtures and goldens so CRLF cannot change a hash. | Lint green on `windows-2025` before any packaging lane. |
| 12.3 | `:platform:windows` | By JNA/FFM: known folders, removable drives (polling or WMI), Recycle Bin by `IFileOperation`, DPAPI/Credential Manager (F6), WIC (which HEIF/AV1 extensions are present is unknown) or the shared libheif/libjxl/LibRaw packs, Media Foundation or a separately loaded LGPL pipeline, ONNX with DirectML; file associations and Share contract. | Fakes in CI; native calls NDV on the Dell while it is Windows, else NOV. |
| 12.4 | Source identity | Case-insensitive volumes, long paths, reserved names, per §6.1 item 3. | Tests. |
| 12.5 | Package | `desktop-windows.yml` on `windows-2025`: JVM tests on every PR (charset and CRLF traps), `jpackage` MSI on `main`/tags only; the signing job is a disabled template. | Unsigned MSI builds (`CI (hosted VM)`); SmartScreen behaviour NDV. |

## 7. Shared foundation this repo consumes or provides

Program §6 F-items; the gate column is the program's. Foto Xplorr is a **consumer** of every item below; Phase 1 is what forces F1, F3, F5 and F6
to exist, so it is in practice their pilot. None of the F-item repos is edited from here (P0B #2).

| F-item | What Foto Xplorr needs | Needed by | Gate |
|---|---|---|---|
| F1 hyle-kmp | Hyle as Compose Multiplatform (`jvm()`, iOS) plus the generated `qml` token files for the Ubuntu Touch shell | Phases 9 to 12; Phase 8 (tokens only) | OQ-17; a new PT:DECISIONS entry; Hyle-consumer status (OQ-29) |
| F2 crash-recovery KMP | A JVM Swing/AWT recovery window; iOS report plus a consumer-owned SwiftUI view | Phases 9 to 12 | After PT:D-P's device verification; OQ-27 |
| F3 cell-shell KMP | `SpatialShell`, word-wheel rail and `EdgeTimelineScrubber` with `jvm()` and iOS actuals (Core Motion shake; gesture exclusion is a no-op on iOS); a written QML adaptation for Lomiri | Phases 9 to 12; Phase 8 (spec only) | OQ-17 |
| F5 kmp-conventions | Version catalogue at OQ-17's pin; the no-platform-import ban (§6.1 item 4); licence allowlist | Phase 1 onward | OQ-17 |
| F6 platform-ports | Secret storage with a reported tier, app directories, folder picker, share sheet, power/metered probes | Phases 8 to 12 | OQ-22 |
| F7 ubuntu-touch-shell | Click templates, policy checker, `DEVICE_CHECKLIST_UT.md`, the OpenStore account. **Not** the jlink core or S-UT1 | Phase 8 packaging | OQ-1, OQ-24 |
| F9, F10, F11 | CI matrix, packaging templates (Flatpak, jpackage, WiX, XcodeGen, Click), evidence scheme and checklists | Phases 8 to 12 | OQ-20, OQ-24 |
| F4 asom-client | Only if remote AI on desktop is routed through asystemofmodels (Q12); unusable off-Android until asom rules D25(b) and D14 part B | Optional, `connect` | OQ-7d |
| F8 native-engines pin | Optional (a local LLM query-parser pack, WP5.4); not on the critical path | Optional | OQ-24 |

**Needed but not an F-item in program §6** (noted for the lead session): a shared on-device ML runtime layer and model-licence discipline
(`:core:ml-api`, ONNX Runtime or LiteRT packaging, a constellation `MODEL_LICENSES.md`); decoder packs under the licence policy (libheif plus
libde265 dynamic, libjxl, piex, LibRaw dynamic) and a video-pipeline abstraction; Linux source plumbing shared with Fyl-Manager (XDG portals
over D-Bus from the JVM, inotify, udisks2, XDG Trash, freedesktop thumbnails).

**What this repo can offer others** (offers, not commitments): the first Room KMP plus `BundledSQLiteDriver` plus FTS5 evidence on `linuxArm64` and iOS
(Fonebrew's Room-KMP spike has the same question); the WP1.9 governance checks (dependency and model-licence tables, GPL/AGPL classpath scan,
16 KB `.so` alignment) as one constellation tool; the decoder-pack licence policy; the TRAPS-derived offline gate pattern.

## 8. Open questions for the owner

Numbered; each says what is blocked. "OQ-n" are the program's ids (`Personal-Tracker/PORTING_PROGRAM.md` §8); "local" has no program id.
Q21 and Q22 were raised while writing this plan; the rest come from the profile's list, deduplicated.

| # | Question | Program id | Blocks until answered |
|---|---|---|---|
| Q1 | Pull the non-Android shells ahead of Phases 2 to 7 (§5.3 options A, B, C)? Phase 1 can start now either way. | OQ-7c | Any start of Phase 8 or 9 before P7; whether Phases 2 to 7 become core-first; edit A5. |
| Q2 | Are iOS/iPadOS, macOS and Windows in scope for Foto Xplorr, in what order after UT and Linux, and may MASTER-PLAN §1.3, §2.1 and §3 be amended as in §5.4? | OQ-7c, OQ-28 | Edits A1, A2, A6; Phases 10 to 12 and their ADRs. |
| Q3 | Desktop shell: run the WP9.1 Compose-vs-Qt spike first, or accept Compose Multiplatform as the default now? | OQ-7c, OQ-8 | Phase 9 and, by dependency, the macOS and Windows rows (§4 note). |
| Q4 | Ubuntu Touch shell: Kotlin/Native plus Qt 5.15 QML on 24.04 now, or wait for Qt 6 on 26.04? Who owns the QML rendition of the fonebrew pattern (shared with Fylz)? | OQ-8 | WP8.1 [ADR]; the second-UI cost (about 30 weeks for two shells). |
| Q5 | Which UT device will you supply for the P8 gate (Pixel 3a, Volla, Fairphone, …)? Is Waydroid on that device acceptable as a stopgap (labelled owner-device evidence)? | OQ-1, OQ-21 | Every UT device gate; a yes to Waydroid shrinks the Ubuntu Touch column. |
| Q6 | If OpenStore refuses the reserved `picture_files_read`/`video_files_read` groups, is a Content-Hub-import-only UT app acceptable for v1? | OQ-4 | WP8.3 fallback design. |
| Q7 | Authorise WP1.1's toolchain bump across `hyle-design-system` and `shared-libraries` (merge commits in those repos) and name the Kotlin version; confirm P0B §0.2 #1 and #3 are superseded from Phase 1. | OQ-17, OQ-24 | Phase 1, so everything. |
| Q8 | Offline identity off Android: iOS and desktops have no INTERNET permission. Is a classpath-and-source gate with two app bundles per platform acceptable, or one build with a per-source network indicator (WP7.2)? On iOS, may the `offline` scheme still use PhotoKit (which can reach iCloud) with network access disabled? | local | The `offline-identity` ADR; the offline gate per shell (§6.0); WP10.3. |
| Q9 | Key custody per platform for BYOK keys: Keychain, libsecret, DPAPI, passphrase file, or none on UT; each weaker tier shown in the UI. | OQ-22 | F6 actuals; every `connect` build off Android. |
| Q10 | ML off Android: accept ONNX Runtime or LiteRT with open-licence models on Linux, Windows, UT and Vision/Core ML on Apple (no ML Kit, no MediaPipe)? Which face, label, OCR and embedding models will you licence-audit? | local | `:core:ml-api` actuals; every `MODEL_LICENSES.md` row. |
| Q11 | Video off Android: a separately loaded LGPL FFmpeg/GStreamer with a source offer, platform frameworks only (nothing on Linux or UT), or fail closed on video clean-share and transcode in desktop v1? | local | The `media-pipeline-off-android` ADR; desktop video share. |
| Q12 | Remote AI on desktop: keep the in-app BYOK path, or route through asystemofmodels' `InferenceClient` once its desktop daemon exists (keys never transfer programmatically)? | OQ-7d | `connect` on desktop; F4. |
| Q13 | ADR-006: keep the "needs the Connect build" street-map interim; on UT, desktop and iOS ship MapLibre in `connect` or the stamp map only? | local | Maps in Phases 8 to 10. |
| Q14 | Pro entitlement: what is the purchase story where there is no Play Billing, and does "billing is a connect-flavour concern" extend to them? | local (touches OQ-4) | Any Pro UI off Android. |
| Q15 | Apple: join the Developer Program? How does a build reach the iPad Pro M4 with no Mac (TestFlight with its crash-report egress, ad-hoc OTA, or sideload)? Is a Mac acquired for macOS? Signing custody? | OQ-2, OQ-3, OQ-5 | Phase 10 and 11 device gates; any signed artefact. |
| Q16 | Channels and identifiers: OpenStore, Flathub (app-id domain), `.dmg` vs Mac App Store, MSI vs Store/winget; a NAMES.md row for every click name, Flatpak id, bundle id, MSI GUID and winget id. | OQ-4, OQ-25 | Any manifest (R11); WP11.1, 12.1. |
| Q17 | Windows: signing route (SignPath, OV on HSM, Store re-signing, or unsigned), and the Dell's fate. | OQ-3, OQ-5 | Phase 12 device and signing steps. |
| Q18 | Is Foto Xplorr a Hyle consumer going forward (PT:D-W), and do F1 to F3 land as their own repos' PRs first? | OQ-29, OQ-17 | F1 and F3 consumption by Phases 9 to 12. |
| Q19 | CI: confirm hosted macOS and Windows runners are acceptable on this public repo, and that new lanes upload nothing. The existing `android-debug.yml` uploads two APKs (about 225 MB) and logs on every PR; that is outside this plan's edits (R3), and is likely a large user of the exhausted artifact storage. | OQ-20 | Lane design (§6.0). |
| Q20 | Carried from Phase 0 (owner question 1): should the similarity index keep indexing only currently-browsable photos? | local | The index-job contract in WP1.4. |
| Q21 | iOS library scope for v1: the Photos library (PhotoKit), Files-app folders, or both? | local (raised here) | WP10.1 and 10.3. |
| Q22 | iOS iCloud Backup: derived caches and face data excluded (MASTER §6); is user metadata (favourites, tags, collections) in the backup, or carried only by the XMP sidecars and the WP3.7 manifest? | local (raised here) | WP10.1; privacy copy. |

## 9. Sources read

- This repo: `README.md`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitmodules`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
  `app/src/offline/AndroidManifest.xml`, `app/src/connect/AndroidManifest.xml`, `feature/ai-remote/build.gradle.kts`, `benchmarks/build.gradle.kts`, `scripts/verify.sh`,
  `.github/workflows/android-debug.yml`, `.github/workflows/release-apk.yml`.
- Docs: `docs/handoff/MASTER-PLAN.md`, `docs/handoff/PHASE-0-BRIEF.md` (§0.2), `docs/handoff/PHASE-0-PROGRESS.md`, `docs/PRODUCT-AND-ARCHITECTURE.md`, `docs/TRAPS.md`,
  `docs/RECON.md`, `docs/v2-acceptance.md`, `docs/competitor-parity.md`, `docs/fonebrew-navigation.md`, `docs/partial-media-access.md`, `docs/perf/baseline-jvm.md`,
  `docs/adr/ADR-006-maplibre-offline.md`, `docs/adr/ADR-007-photo-editing.md`, `docs/adr/ADR-008-video-transcode-pipeline.md`.
- Source (read for the API inventory): `FotoXplorrApplication.kt`, `pro/ProEntitlement.kt`, `experience/OpenGlPhotoScene.kt`, `spatial/SpatialOpenGlScene.kt`, and the
  per-package surveys behind §2.
- Sibling checkouts (plugin types only, because the submodules are empty here): `Hyle-Design-System/hyle/build.gradle.kts`,
  `Shared-Libraries-asoc/{cell-shell,crash-recovery,search-core}/build.gradle.kts`.
- Program: `Personal-Tracker/PORTING_PROGRAM.md` §0 to §3, §4.1 to §4.5, §5 (this repo's row), §6, §7, §8.
