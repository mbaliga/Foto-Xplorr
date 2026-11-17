# Linux desktop port: status and decisions (2026-11-17)

**Owner request 2026-11-17:** builds for macOS, iOS, Windows and Ubuntu Touch on **GitHub Actions**; this repo's Linux target is the
third in line after iOS and Ubuntu Touch. Rulings that still bind: native port only (no Waydroid, no Android-in-a-box), no telemetry,
no signing material in the repo, actions pinned. Honest label: **a native Qt Quick desktop build of the same shell Ubuntu Touch uses, over
the shared Kotlin/Native core. It is not a port of the Android Compose app.**

Evidence classes: **BUILT-HERE** (run in this container, passed), **CI-LANE-WRITTEN** (workflow exists, never run), **CODE-ONLY**
(written, never compiled/run), **NEEDS-DEVICE-VALIDATION** (a real desktop session; `linux/OWNER_VERIFY.md`).

## Decision: one shell, two Qt majors, Qt 6 ships
ADR-013 (accepted) says one Qt 6 + QML shell for UT, Plasma Mobile, Phosh and desktop, and drops Compose Desktop. PORTING-PLAN Phase 9
(older, stale) still offers Compose Desktop; **ADR-013 wins and Compose Desktop is not built.** The Ubuntu Touch work already forced the
shell to stay Qt-5.15-compatible, so this port did not fork it: `ut/app` has a `FOTOZ_DESKTOP` switch (desktop install layout, folder picker,
folder watching, launch re-check) and builds on **both** Qt 5.15 and Qt 6 (`-DFOTOZ_QT_MAJOR=5|6`). The Qt 6 build is what is packaged
(Flatpak runtime `org.kde.Platform//6.11`, Ubuntu 24.04 deb against Qt 6.4). The Qt 5.15 desktop build is only tested (it is the UT toolchain).
This turns the UT canary's "Qt 6 unproven" into a measured result for the shell logic (below); the **UT click itself stays Qt 5.15**.

## What exists
| Item | Evidence |
|---|---|
| Shell builds in desktop mode on **Qt 5.15.13** and **Qt 6.4.2** (Ninja, GCC 13.2) | **BUILT-HERE**: both configure and compile with no warnings |
| QtTest, offscreen + software renderer, on both majors: `tst_shell` (fake core) 12/12, `tst_realcore` (real `libfotozcore.so`, real files, real SQLite) 9/9 | **BUILT-HERE**: 100% on Qt 5.15.13 and on Qt 6.4.2 |
| Folder watching (`QFileSystemWatcher`, one watch per directory, capped at 2048, dotfiles/symlinks skipped as in the core's walk, 3 s debounce): copy of 2 files into a watched folder = one rescan, a new sub-folder is noticed and then watched, a deletion sweeps, switching watching off removes every watch and stops rescans | **BUILT-HERE** (`watcherRescansOnChange`, real inotify, real core). Phone mode never watches (`phoneModeDoesNotWatch`) |
| Launch re-check of the folders the user added (desktop + watching on) and `fotoz [FOLDER...]` command line ("Open with") | **BUILT-HERE** (smoke below); the file-manager integration itself is NOT registered (no MIME association, deliberately) |
| Desktop UI: 1100x720 window, wider tiles, folder picker button (Qt.labs.platform on Qt 5, Qt Quick Dialogs on Qt 6, loaded through a `Loader` so a missing module leaves the path field as the way in), prev/next buttons and arrow/Home/End/Space/I keys in the viewer, Esc/Alt+Left back, Ctrl+R rescan, Ctrl+O choose folder, Ctrl+F search, grid key navigation | **BUILT-HERE** for load + the loader being Ready on both Qt majors, arrow keys moving the viewer, Esc going back, zero QML warnings (`qmlDesktopMode`). The dialog itself, shortcut feel, layout at real sizes: **NEEDS-DEVICE-VALIDATION** (a desktop session) |
| `fotoz --version` / `--help` answered before any GUI platform starts (works over ssh and in CI) | **BUILT-HERE** |
| Installed layout: `cmake --install` (prefix `/usr`) gives `bin/fotoz` (RUNPATH `$ORIGIN/../lib/fotoz`), `lib/fotoz/libfotozcore.so`, `.desktop`, AppStream metainfo, scalable icon. The installed binary found the core, scanned a folder given on the command line and created `~/.local/share/fotoz/fotoz/fotoz.db`; it stayed running under `timeout 8` (exit 124) with the offscreen platform | **BUILT-HERE** |
| `.deb` and `.tar.gz` via CPack, for both Qt majors (Qt 6 deb Depends on `qml6-module-*` + shlibdeps-derived libs; Qt 5 deb on `qml-module-*`) | **BUILT-HERE**: `dpkg-deb -I/-c` inspected. **Not installed** with dpkg here (the container's dpkg is broken by an unrelated `click` package), never run on a clean Ubuntu |
| `desktop-file-validate`, `appstreamcli validate --no-net` (passes; 1 pedantic note: no screenshots yet), id agreement across desktop/metainfo/manifest/`main.cpp`, Flatpak sandbox promises (no `--share=network`, no bus/home/host grants, only `:ro` filesystem), no network lib in package deps: `linux/scripts/verify-linux.sh`, also in `scripts/verify.sh`; negative test (injected `--share=network`) fails it as it should | **BUILT-HERE** |
| Flatpak manifest `linux/flatpak/io.github.mbaliga.Fotoz.yml` (KDE 6.11 runtime, Wayland + fallback X11, `xdg-pictures:ro`, prebuilt core, cmake-ninja module). The runtime tag was checked against Flathub's live API | **CODE-ONLY** for the build: parses (`flatpak-builder --show-manifest`), never built (the container has 2 GB of free disk; the KDE SDK is several GB) |
| `.github/workflows/linux.yml`: `desktop` (static gates, Gradle bridge tests, amd64 + arm64 core, Qt 5.15 and Qt 6 build + ctest, deb + tarball, install smoke, draft release on `linux-v*` tags), `flatpak` (flatpak-builder, `--version` smoke, bundle, draft release), `arm64-native` (shell + tests on a real arm64 host against the cross-linked core) | **CI-LANE-WRITTEN**: YAML parses, every action SHA-pinned, no secrets (`GITHUB_TOKEN` only for the tag-gated draft release). Every non-Docker command in `desktop` was run locally one by one |
| The `linuxArm64` core executed | **Nothing here has ever run it** (no arm64 host). The `arm64-native` lane exists to do exactly that; GitHub's arm64 runners are free for public repos only |
| Everything on a real desktop (Wayland, X11, GNOME, KDE), real photo libraries, HiDPI | **NEEDS-DEVICE-VALIDATION** |

Counts come from this run: `ctest` Qt 5.15.13 `12 passed` + `9 passed`; Qt 6.4.2 `12 passed` + `9 passed`; `verify-offline OK`; `verify-linux OK`;
`verify-core-purity OK`. The Gradle bridge tests were not re-run for this stage (no Kotlin was changed; the 25-test `:ut:bridge:linuxX64Test` result
is the UT stage's).

## Decisions and why
1. **Reuse the UT shell, do not start a second UI.** Same QML, same C++ glue; desktop behaviour is behind `Library.desktop` (set only by the
   `FOTOZ_DESKTOP` build), so the click is unchanged in behaviour (its tests still pass).
2. **App id `io.github.mbaliga.Fotoz`** (Flathub's rule for GitHub-hosted projects). It is a placeholder for the owner: if the owner wants a domain
   id (e.g. under `asystemofcells.com`) it is one string in `CMakeLists.txt`, `main.cpp`, `linux/`. The Apple-style `com.asoc.*` rule is Apple-only.
3. **Desktop data lives in the XDG default for `fotoz`/`fotoz`** (`~/.local/share/fotoz/fotoz/fotoz.db`, Flatpak: `~/.var/app/io.github.mbaliga.Fotoz/data/fotoz/fotoz/`), while the click keeps its package-name directory.
4. **Watching is on by default on the desktop and off on the phone.** It only reads directory metadata; "Watch for changes" turns it off and removes all
   watches. The 2048-directory cap protects the user's inotify budget; beyond it the status line says so and Rescan all still works.
5. **The core is prebuilt outside the Flatpak** (Gradle and the K/N toolchain need the network, a Flatpak build has none). This is fine for sideloaded
   bundles from CI; Flathub would not accept a prebuilt binary blob without a source build, so **a Flathub submission is a separate owner decision**
   (also: the licence is PolyForm Noncommercial, which Flathub treats as proprietary).
6. **Flatpak and non-Pictures folders.** The sandbox sees `~/Pictures` read-only. A folder chosen through the portal dialog arrives as a document-portal
   path, which is a FUSE mount: scanning should work, inotify watching there probably does not. This is predicted, not observed; if it matters the
   documented alternative is `flatpak override --user --filesystem=<dir>:ro io.github.mbaliga.Fotoz`.
7. **No "reveal in file manager" / "open with" action.** It would need `QDesktopServices::openUrl`, which the offline gate forbids on purpose (it is also how a URL gets opened). Revisit with an explicit gate exception if wanted.
8. **No AppImage.** It needs downloaded, unpinned tooling (linuxdeploy). The deb, tarball and Flatpak cover the routes; the tarball needs the distribution's Qt.
9. **Thumbnails, date, search are exactly the UT shell's** (Qt decoders, mtime as date, file-name search). See `UT-PORT.md`.

## Not done (not hidden)
Removable-drive monitoring (UDisks2), EXIF/XMP (so "date taken" is the file date), tags/ratings, duplicates, rename/move/trash (no operation engine
exists), video playback, HEIC/AVIF/JXL/RAW beyond system Qt plugins, libheif/libjxl/LibRaw bundling, XDG Trash, libsecret, recognition (ONNX), Hyle QML
style, Wayland-specific polish, screenshots for AppStream, a Windows/macOS Qt build (other stages), signing of any kind, Flathub submission.

## Owner follow-ups
`linux/OWNER_VERIFY.md` (what only a desktop can show, plus decisions: Flatpak id, maintainer address, Flathub vs sideload, runtime branch, whether
to make the repo public so the arm64 runner and Actions minutes are free).
