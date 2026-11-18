# Ubuntu Touch / Linux: what only the owner can check

Nothing below has been observed. This container has no Ubuntu Touch device, no Lomiri, no Docker, no arm64 host. Tick a
box only after you saw it on the device. Evidence classes are defined in `docs/handoff/UT-PORT.md`.

## 0. Get the click
- [ ] Tag `ut-vX.Y.Z` (or run the **Fotoz Ubuntu Touch** workflow by hand). The `click` job should produce `fotoz.mbaliga_0.1.0_arm64.click`
      (and `_amd64`). If the job fails, the lane was never run before: read the log, the likely first failures are listed in
      `docs/handoff/UT-PORT.md` "Known lane risks".
- [ ] `manifest.json` `maintainer` still says `OWNER-FILL`: put your name/e-mail in `ut/app/manifest.json.in` before any OpenStore upload.
- [ ] Install: `clickable install --arch arm64` or `adb push` + `pkcon install-local fotoz.mbaliga_*.click` (needs developer mode).

## 1. Starts
- [ ] The app launches and shows the folder page (not a blank screen, not an immediate exit). If the status line says
      "[!] ... core library could not be loaded", copy that message: it is the `dlopen` error (glibc/libstdc++ mismatch is the
      prime suspect for a Kotlin/Native `.so` on UT; the linkage report in the CI log shows which GLIBC version it needs).
- [ ] The Qt Quick Controls 2 style looks acceptable (the app uses the plain `Default` style; Lomiri's Suru style is a one-line
      change via `FOTOZ_QC2_STYLE`). Layout is usable at phone width with the on-screen keyboard up.

## 2. Reading photos (the AppArmor guess)
- [ ] Type `/home/phablet/Pictures` (or tap **Scan Pictures**) and **Add**. Expected: a count of photos found, a folder
      list, thumbnails. If the scan fails with "cannot read directory", the `picture_files_read` / `video_files_read`
      policy groups are wrong or were refused (they are reserved groups and need OpenStore review for store distribution;
      sideloaded they should apply). Report the exact AppArmor denial from `journalctl`/`dmesg`.
- [ ] A folder you put outside `~/Pictures`/`~/Videos` is refused by confinement (expected, not a bug): there is no Content Hub
      import yet.
- [ ] Scan time for your real library, and that the UI stays responsive during the scan (the scan runs on a worker thread).
- [ ] Open a photo, swipe between photos, tap for the info bar. HEIC/AVIF/JXL/RAW tiles say `[no preview]` unless the OS
      has Qt image plugins for them: note which formats you actually have.
- [ ] Videos show `[video]` and say playback is not in this build (expected).

## 3. Offline identity (the point of the app)
- [ ] The app's AppArmor profile has no `networking` group: `cat /var/lib/apparmor/profiles/*fotoz*` or the click's
      `fotoz.apparmor` shows only `picture_files_read`, `video_files_read`.
- [ ] With airplane mode on and off the behaviour is identical.
- [ ] The About page text is accurate for what you see.

## 4. Persistence and lifecycle
- [ ] Close and relaunch: folders and counts are still there without a rescan (the library is `fotoz.db` in the app's data dir).
- [ ] Switch away mid-scan: Lomiri freezes unfocused apps within ~1.5 s. On return the scan must either finish or can be
      restarted with **Rescan all**; it must never leave the library half-swept (a pass that did not finish enumerating
      never removes anything: unit-tested, but not lifecycle-tested).
- [ ] Delete a photo with another app, **Rescan all**: its tile disappears.

## 5. Things to tell me
- Device model, UT version (24.04-1.x / 2.x / 26.04), whether the framework `ubuntu-touch-24.04-1.x` installed on it.
- Whether the arm64 `libfotozcore.so` runs at all (Kotlin/Native `linuxArm64` is Tier 2 upstream, never run by anyone here).
- Memory use with 10k+ photos (every tile decodes at 256 px, the viewer at 2048 px).

## Owner decisions this port needs
1. **Qt 5.15 now vs Qt 6 (ADR-013).** The shell targets Qt 5.15 / `ubuntu-touch-24.04-1.x` because that is what the OpenStore and Clickable
   can build and ship today; ADR-013 said "no 24.04 build unless 26.04 slips". It slipped enough that nothing else is installable. The
   code compiles for Qt 6 in principle (CMake finds either; a CI canary tries it) but that is unproven. Confirm or overrule.
2. **Click name** `fotoz.mbaliga` and maintainer string (see 0).
3. **OpenStore account and review** for the reserved policy groups (open-source apps only: this repo is Apache-2.0).
   Decided 2026-11-18 (owner, "Option 2, narrow allowance"): CI tolerates only the `picture_files_read` / `video_files_read`
   "(NEEDS REVIEW)" click-review findings (`ut/scripts/click-review-gate.py`, ADR-013 addendum); everything else fails the `click` job. This
   does not replace the OpenStore review, which must still clear them before a store submission.
4. **Hyle QML style.** `ut/app/qml/Theme.qml` is neutral tokens, not Hyle. Say if/when the real style should be wired.
5. **Linux desktop packaging (Flatpak).** Out of this stage; the same shell builds on a desktop (`UT-PORT.md`).
