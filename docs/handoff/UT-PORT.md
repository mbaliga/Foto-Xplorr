# Ubuntu Touch port: status and decisions (2026-11-17)

**Owner request 2026-11-17:** build for Ubuntu Touch (and macOS, iOS, Windows) on **GitHub Actions**. This overrides the plan's
"proposed line" as a proposal; the plan's owner *rulings* still bind (native port only, no telemetry, signing material never in the repo,
actions pinned). Honest label: **a native Qt Quick shell over the shared Kotlin core, not a port of the Android Compose app.**

Evidence classes: **BUILT-HERE** (run in this container, passed), **CI-LANE-WRITTEN** (workflow exists, never run), **CODE-ONLY**
(written, never compiled/run), **NEEDS-DEVICE-VALIDATION** (`ut/OWNER_VERIFY.md`).

## What exists
| Item | Evidence |
|---|---|
| `LocalDirSource` (`:core:index`, `LINUX_DIR` source: breadth-first walk, skips dotfiles and symlinks, never claims "fully enumerated" after an unreadable directory or the depth cap) + `SourceKind.LINUX_DIR` | **BUILT-HERE**: 7 new jvm tests, `:core:index:jvmTest` 18/18 green |
| `GalleryProjectionDao.folders()` (one row per browsable folder, hides missing/archived/trashed) | **BUILT-HERE**: new `:core:db:desktopTest` case, 31/31 green |
| `:ut:bridge` `fotoz_sync_start` is now REAL for `LINUX_DIR` (POSIX `opendir`/`lstat` lister, real `SyncEngine`, real Room/SQLite); new `fotoz_folders`; query pages hide swept (MISSING_CONFIRMED) files | **BUILT-HERE**: `:ut:bridge:linuxX64Test` 25/25 green (was 20), real temp directory trees, symlink, sweep-on-delete, unreadable root |
| `libfotozcore.so` RELEASE links for linuxX64 and **linuxArm64** (cross-linked on x86) | **BUILT-HERE**: x64 9.6 MB, ELF x86-64, needs only glibc 2.14 + libm/libpthread/libdl/librt/libutil/libcrypt/libresolv/libgcc_s (no libstdc++); arm64 9.0 MB, `ELF 64-bit ... ARM aarch64`. The arm64 file has **never been executed** (no arm64 host) |
| Qt 5.15 / QtQuick Controls 2 shell `ut/app` (C++: `CoreApi` QLibrary binding, `FolderModel`, paged `AssetModel`, `LibraryController`; QML: folders, grid, swipe viewer, About) | **BUILT-HERE** on Qt 5.15.13: compiles clean; 12 QtTest cases against a fake core (paging, text filter after paging, error surfaces, off-thread sync, offscreen QML load with zero QML warnings) |
| Shell against the **real** core: real files on disk -> real scan -> real SQLite -> Qt models -> QML grid; delete + rescan sweeps; reopen keeps the library | **BUILT-HERE**: `tst_realcore` 5 cases green (offscreen, software renderer); the `fotoz` binary also launches with a real core and with none (stays up, shows the error) |
| `qmllint`, offline gate (`ut/scripts/verify-offline.sh`), `clickable.yaml` validated against Clickable's own `project.schema`, manifest/apparmor templates rendered and parsed | **BUILT-HERE** (pass) |
| `manifest.json.in`, `fotoz.apparmor.in`, `fotoz.desktop.in`, `assets/icon.svg`, `clickable.yaml` (framework `ubuntu-touch-24.04-1.x`, builder cmake, arm64 + amd64) | **CODE-ONLY** for UT behaviour: schema-valid, never built into a click, never installed. The AppArmor policy-group names and `Exec=fotoz` are from the plan/memory, not verified against a device |
| `.github/workflows/ut.yml`: `shell` (core + Qt build + all tests), `click` (Clickable build arm64 + amd64, inspect, advisory `clickable review`, draft release on `ut-v*` tags), `canary-qt6` (allowed to fail) | **CI-LANE-WRITTEN**: YAML parses, every action SHA-pinned, no secrets; the commands of `shell` were run locally one by one, Docker/Clickable steps were not (no Docker here) |
| Everything on a phone | **NEEDS-DEVICE-VALIDATION** |

## Decisions and why
1. **Qt 5.15 / `ubuntu-touch-24.04-1.x` first** (ADR-013 addendum 2026-11-17): it is what Clickable (default for Qt 5.15) and the OpenStore can build and ship today. 1.x not 2.x because a newer OS is expected to keep older frameworks; unverified. The framework is chosen in one place (`ut/app/clickable.yaml`) and flows to the manifest and AppArmor policy version through `build_args`. The shell is written to build on Qt 6 too (CI canary), unproven.
2. **QLibrary, not link-time linking**, for `libfotozcore.so`: a missing or ABI-incompatible core becomes an on-screen error instead of a launch failure, and the glue is testable with a fake core.
3. **The core is built by Gradle outside Clickable** (`ut/scripts/build-core.sh`) and handed in with `-DFOTOZ_CORE_LIB`: Clickable's container has no JDK/Kotlin/Native and shouldn't. K/N cross-links arm64 from an x86 runner (confirmed here).
4. **Scans run on a worker thread; queries on the GUI thread.** `fotoz_sync_start` blocks until the pass ends and calls back once; there is no progress callback or cancellation yet.
5. **Read-only on the user's files.** This build never writes, moves, trashes or renames a photo. (`fotoz_ops_execute` stays a stub; `:core:organize` only plans.)
6. **Offline by construction:** no QtNetwork/WebEngine, no URL literals, no networking AppArmor group, no network library in `:ut:bridge`; gated by `ut/scripts/verify-offline.sh` (also in `scripts/verify.sh`). The About page says so.
7. **Hidden-by-availability workaround.** `GalleryProjectionDao`'s 13 existing queries ignore `availability`, so a file swept to `MISSING_CONFIRMED` would still be paged (found by the real-tree sweep test). The bridge filters after paging; the new `folders()` query filters in SQL. The Android app's own handling is not changed here.
8. **Thumbnails are Qt's.** Tiles decode at 256 px, the viewer at 2048 px via `Image.sourceSize`; formats Qt cannot decode say `[no preview]`. No `lomiri-thumbnailer`, no libheif/libjxl bundle yet (ADR-013 point 3 is not done).
9. **`date_taken` is the file mtime** until a metadata pass exists (EXIF is not read on this platform), and the viewer says so.
10. **Neutral theme, not Hyle.** `qml/Theme.qml` is one file of tokens to replace when a Hyle QML style exists.

## Not done (not hidden)
inotify/periodic watching (use **Rescan all**); removable drives (UDisks2), MTP, Content Hub import and Pictures-only confinement UX; folder picker (a path field and a **Scan Pictures** button instead); EXIF/XMP, tags, ratings; duplicates, bulk rename/move/trash (no operation engine exists); motion photos; video playback; HEIC/AVIF/JXL/RAW decoding beyond system Qt plugins; the real `Capability` model; on-device semantic search and faces; Flatpak/desktop packaging (the shell builds and tests on a Linux desktop, nothing is packaged); Hyle QML style; signing and OpenStore submission; `:core:db` migrations on a pre-existing `fotoz.db`.

## Known lane risks (read before the first CI run)
- `clickable build` cross-arch uses Clickable's own Docker images (`clickable/amd64-ut24.04-1.x-arm64`, per `clickable/config/constants.py` in 8.10.0). Image names are from that file, not pulled here.
- `Exec=fotoz`, the install layout (`DESTDIR` + prefix `/.`), the `lib/` RPATH and the AppArmor groups `picture_files_read`/`video_files_read` are unverified against a real click. If the click installs but does not start, check these first.
- The `click` job's "Inspect" step greps for `lib/libfotozcore.so` and fails the job if it is not in the click.
- `canary-qt6` package names (`qml6-module-*`) are best-effort; it is allowed to fail.
- Private submodules: same `SUBMODULE_TOKEN` fallback as `ios.yml`. `:app` is in the root build, so the lanes create an Android `sdk.dir` even though they never build it.
- The K/N arm64 link needs the toolchain download (`download.jetbrains.com`) on the runner; the Maven Central 429 rate limit seen in this container can hit CI too (re-run).

## Owner follow-ups
`ut/OWNER_VERIFY.md` (device checks and the owner decisions: Qt 5.15 vs 6, click name/maintainer, OpenStore account and reserved-group review, Hyle QML style, Flatpak). The Apple bundle id question does not apply to this platform.
