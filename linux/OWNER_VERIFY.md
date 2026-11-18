# Linux desktop: what only the owner can check

Nothing below has been observed. This container has no desktop session, no display server, no Flatpak sandbox runtime and no arm64 host.
Tick a box only after you saw it. Evidence classes: `docs/handoff/LINUX-PORT.md`.

## 0. Get the packages
- [ ] Run the **Fotoz Linux** workflow (or tag `linux-vX.Y.Z`). First run of a never-run file: read the log of whichever job fails. Likely first failures:
      Gradle needing the private submodules (`SUBMODULE_TOKEN` secret), the Maven Central 429 rate limit (re-run), `flatpak-builder` unable to fetch the
      KDE 6.11 runtime, the `arm64-native` job never being scheduled on a private repo (expected; allowed to fail).
- [ ] The release (draft) carries `fotoz_0.1.0_amd64.deb`, `fotoz-0.1.0-Linux.tar.gz`, `fotoz-x86_64.flatpak`.
- [ ] Decide: package maintainer address (`CPACK_PACKAGE_CONTACT` in `ut/app/CMakeLists.txt` says `owner-fills-this@invalid`).

## 1. deb (Ubuntu 24.04+)
- [ ] `sudo apt install ./fotoz_0.1.0_amd64.deb` pulls the Qt 6 QML modules and starts. Launcher entry "Fotoz" with the icon.
- [ ] If the status line says "core library could not be loaded", copy the message (glibc/libstdc++ of the Kotlin/Native `.so`; the CI linkage report names the GLIBC version needed).

## 2. Flatpak
- [ ] `flatpak install --user fotoz-x86_64.flatpak` then `flatpak run io.github.mbaliga.Fotoz`. Starts on Wayland and on X11.
- [ ] **Scan Pictures** works (the manifest grants `~/Pictures` read-only). A folder elsewhere: **Choose folder...** shows the portal dialog; does the scan work? Does "Watch for changes" notice new files there (predicted: no, document portal is FUSE)? Does `flatpak override --user --filesystem=<dir>:ro` fix it?
- [ ] `flatpak info --show-permissions io.github.mbaliga.Fotoz` shows no `network`.

## 3. The app (any package)
- [ ] Add your real photo folder. Scan time, and the window stays responsive during the scan (scan runs on a worker thread).
- [ ] Copy new photos into it while the app is open: they appear within a few seconds without pressing anything. Delete one: it disappears. Create a sub-folder: its photos appear.
- [ ] Turn "Watch for changes" off: nothing updates until Rescan all (Ctrl+R). Relaunch: the setting is remembered; with it on, changes made while closed show up after launch.
- [ ] A library with more than 2048 folders: the status line says watching is capped.
- [ ] Grid: arrow keys move, Enter opens. Viewer: Left/Right/Home/End, Space or I toggles info, the on-screen arrows work, Esc returns. Ctrl+F focuses search, Ctrl+O opens the folder dialog.
- [ ] HiDPI / fractional scaling look right; the dark theme is acceptable; window can be shrunk to a narrow size and stays usable.
- [ ] HEIC/AVIF/JXL/RAW tiles say `[no preview]` unless your system has Qt image plugins: note which formats you actually have (KImageFormats on KDE).
- [ ] Your photos are untouched (timestamps unchanged). The app never writes to them in this build.

## 4. Offline identity
- [ ] With the network off and on, behaviour is identical. `ss -tp | grep fotoz` and a firewall log show no connection attempts (a static gate, not a proof: `ut/scripts/verify-offline.sh`).
- [ ] About page text is accurate for what you see.

## 5. Owner decisions
- [ ] App id `io.github.mbaliga.Fotoz` (or a domain id you control).
- [ ] Flathub: the prebuilt core and the PolyForm Noncommercial licence both need a ruling before any submission; sideloaded bundle only for now?
- [ ] Qt 5.15 vs Qt 6 for Ubuntu Touch (ADR-013 addendum); this stage shows the shell builds and passes its tests on both.
- [ ] Repo public or private: private repos pay for Actions minutes and get no free arm64 runner.
