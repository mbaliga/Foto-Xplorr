# ADR-013 — Linux strategy: one Qt 6 QML shell for Ubuntu Touch, other Linux phones and the desktop

**Status:** accepted (supersedes MASTER-PLAN Phase 8 and Phase 9 shells and ADR-009 §6's Compose Desktop trigger)
**Date:** 25 Sep 2026
**Trigger for execution:** after Android v1 meets `V1-RELEASE-BAR.md`

## Context (researched September 2026; sources in the Linux landscape notes, §Sources)

- **Ubuntu Touch has no maintained local photo library manager.**
  - `lomiri-gallery-app` 3.1.1 (May 2025) has no tags, no map and no search.
  - On the 26.04 test builds it fails to start (`libexiv2.so.27` missing).
  - Imaginario has stalled (last release March 2025, launch failures reported on Fairphone 4/5).
  - New Ubuntu Touch photo apps are clients for Immich or Synology servers, not local libraries.
- **Phosh / GNOME Mobile** lost GNOME Photos, now archived. Loupe is a viewer only, and Shotwell and gThumb aren't adaptive.
- **Plasma Mobile** has KDE Photos (formerly Koko), which is active and sponsored. It lacks faces, semantic search, duplicates and bulk rename/move, and was measured at ~3× Gwenview's RAM on 15k images.
- **No Linux phone app** has any of these:
  - on-device face recognition, semantic search or duplicate detection;
  - XMP ratings/keywords round-trip;
  - folder include/exclude;
  - bulk rename/move;
  - motion photos.

  On the desktop, digiKam 9.x covers most of these but is desktop-only.
- **Ubuntu Touch toolkit timing.** Apps target Qt 5.15 today. Qt 6 app-API stability is planned for UT 26.04-1.0, and the stock gallery must be rebuilt for it anyway.
- **Audience.** It's small: Linux phone users number in the tens of thousands, not millions. The stock UT Gallery's current version has about 20k OpenStore downloads; Imaginario about 4.9k. A UT-only port reaches a few thousand active users. The same app packaged for Plasma Mobile/postmarketOS (Flatpak/APK) and the desktop reaches all Linux users with one codebase.

## Decision

1. **One Linux UI: Qt 6 + QML (QtQuick Controls 2 with a Hyle QML style), not Compose Desktop and not Lomiri UI Toolkit.**
   - It runs on UT 26.04 (Qt 6), Plasma Mobile, Phosh (via Flatpak), postmarketOS and the desktop.
   - Lomiri-only integration lives in a thin optional layer (`ut/`): Content Hub, click packaging, `lomiri-thumbnailer` when available.
   - No UT 24.04 / Qt 5 build unless 26.04 slips past the point where the Linux shell is ready. If it does, a Qt 5.15 compatibility pass is a separate, bounded ADR.
2. **The core stays Kotlin Multiplatform**, compiled by Kotlin/Native (`linuxArm64`, `linuxX64`) into `libfotozcore.so` with a coarse C API (as in master plan WP8.1). Room KMP with the bundled SQLite driver gives the same `fotoz.db` on every platform. **The library is portable between phone and desktop.**
3. **Decoding on Linux:**
   - Qt image plugins plus **KImageFormats** where the distro ships it (AVIF, HEIF, JXL, RAW via LibRaw).
   - A bundled fallback set (libheif + libde265, libjxl, LibRaw, piex), all dynamically linked. **No x265, and no GPL glycin loaders.**
   - The shared `Capability` model reports the same way as on Android.
4. **ML on Linux:** ONNX Runtime (x64/arm64), with the same model packs and `MODEL_LICENSES.md` as Android. Delivered in Linux v1.1, not v1.0.
5. **Sources on Linux:**
   - XDG folders plus folders the user adds (the portal FileChooser under Flatpak, or `QFileDialog`), watched with inotify and a periodic rescan;
   - removable drives via UDisks2 D-Bus;
   - MTP/cameras via GVfs/KIO mounts treated as import sources;
   - on UT, Content Hub import plus `picture_files_read`/`video_files_read` (reserved groups, which need OpenStore review, with a justification).
6. **Packaging:**
   - UT `.click` on the OpenStore;
   - Flatpak (Flathub) for desktop, Phosh and Plasma Mobile;
   - postmarketOS/Alpine package later if the community asks.
7. **Scope of Linux v1.0** (the "much better than every Linux phone gallery" bar):
   - timeline with an accurate scrubber at 100k;
   - albums, folders, include/exclude;
   - favourites, ratings, keywords **written to XMP** (round-trips with digiKam and darktable);
   - token search + filter builder (tiers A and B);
   - bulk rename (rule engine) and move with trash;
   - duplicates (exact + perceptual);
   - motion photos;
   - HEIC/AVIF/JXL/RAW preview;
   - viewer with deep zoom;
   - share/import through portals and Content Hub;
   - removable drives with offline previews.
8. **Linux v1.1:** on-device semantic search and faces (ONNX packs), map/places, basic editing (crop, rotate, light/colour through the shared edit recipe).

## Consequences

- Master plan Phases 8 and 9 merge into one **Phase 8 — Linux** (UT is one packaging target of it). Compose Desktop is dropped, and ADR-009 §6's constellation toolchain upgrade is **no longer triggered by Linux**.
- The QML UI is new work (no reuse of the Compose screens). The core, database, search, organise, formats, metadata and sync logic are reused as they are, which is what Phase 1's module split was for.
- Kotlin/Native `linuxArm64` is Tier 2 upstream: device testing on a real UT phone (e.g. a Pixel 3a or Volla) is mandatory before the OpenStore release.
- If the UT 26.04 release or its Qt 6 app API slips, Linux v1 ships first as a Flatpak for desktop and Plasma Mobile/Phosh, and UT follows.

## Is it worth it?

Yes, as mission and reputation, not as volume.
- **The gap is real and wide open.** No Linux phone has a local, private, capable library manager, and the UT stock app is about to break on 26.04.
- **The cost is contained.** The expensive half, the core, is already shared. The QML shell is roughly one phase of work.
- **One Qt 6 shell reaches all of Linux, not just UT's few thousand users.**
- **It supports the constellation's systems-trap argument.** A capable phone deserves capable software, whoever made the OS.

Don't do it before Android v1 is solid. A weak Android core ported to Linux just produces two weak apps.

## Addendum (2026-09-30) — WP8.1 started ahead of schedule: `:ut:bridge`, the C API over the core

**This work was started ahead of this ADR's own stated precondition, at the owner's explicit
request.** MASTER-PLAN.md's Phase 8 precondition reads "P1's KMP core compiles for
`linuxArm64`/`linuxX64` headless (WP1.2), **and P7 is released**." Only the first half is true:
Fotoz is still on Phase 1 (in progress), Phases 2–7 are not yet even defined (blocked on the
owner's own Phase-1b scope decision, per the task tracker), and no Android release candidate
exists. The owner asked for this Linux/UT groundwork to begin anyway, accepting explicitly that it
is ahead of schedule and cannot be device-verified yet. Nothing below should be read as Phases 2–7
being done, or as this ADR's precondition being met — it is not.

What was actually built, matching this ADR's point 2 and MASTER-PLAN.md's WP8.1 line exactly: a
new Kotlin/Native-only module, `:ut:bridge` (`ut/bridge/`), compiling `linuxX64`/`linuxArm64` into
a real, linked `libfotozcore.so` (`linkDebugSharedLinuxX64` verified by hand — the `.so` and its
generated C header both exist and `nm -D` shows every exported symbol) with a coarse C API:
`fotoz_library_open`/`fotoz_library_close`, `fotoz_capabilities`, `fotoz_query_page`,
`fotoz_asset_detail`, `fotoz_set_metadata`, `fotoz_sync_start`, `fotoz_ops_execute`, plus small
`fotoz_status_*` accessors and `fotoz_free_string`. Opaque handles (`COpaquePointer` wrapping a
`StableRef`) and JSON byte buffers only, per point 2 — no Kotlin object graph crosses the C
boundary. Every exported function uses `@CName`. Full detail — every entry point's real-vs-stubbed
status and why, every `@CName` signature, and the 20 real `linuxX64Test` tests that call them
directly — is logged in `docs/handoff/MASTER-PROGRESS.md`'s WP8.1 row and Decisions (search
"WP8.1"), not duplicated here.

One correction to point 3 discovered while building this: **no shared `Capability` model exists
anywhere in this codebase yet** to report through. ADR-010's original `:core:model` row named one;
WP1.2, then WP1.3, then WP1.7a's own Decisions each deferred building it, in that order, and it is
still deferred today. `fotoz_capabilities()` therefore reports a bridge-local, honest stand-in
shape instead of "the same way as Android" — there is no Android-side model to match yet either.
Once a real `Capability` matrix exists, `fotoz_capabilities()` should be rewired to report through
it, closing this gap for real rather than leaving the stand-in in place.

**Out of scope for this WP, on purpose:** no QML/Qt/C++ shell of any kind was attempted here — not
even a smoke test of one. Point 1's Qt 6 + QtQuick Controls 2 decision, the Hyle QML style, and
every Lomiri-only integration question (`ut/` Content Hub, click packaging, `lomiri-thumbnailer`)
belong to that later, separate dispatch, which needs this WP's exact resulting C API surface and
generated header shape as its own starting point — now available at
`ut/bridge/build/bin/linuxX64/debugShared/libfotozcore_api.h` (regenerated by
`:ut:bridge:linkDebugSharedLinuxX64`, not itself committed as a build artifact).

## Sources

The research notes behind this ADR are in the conversation record. The key sources:
- [UT 24.04-2.0 release](https://ubports.com/blog/ubports-news-1/ubuntu-touch-24-04-2-0-and-24-04-1-4-release-4007)
- [26.04 test gallery failure](https://forums.ubports.com/topic/12312/looking-for-testers-ubuntu-touch-26.04-1.x-early-version/64)
- [OpenStore gallery stats](https://open-store.io/api/v4/apps/gallery.ubports)
- [KDE Photos proposal](https://pointieststick.com/2026/09/06/photos-a-proposed-replacement-for-gwenview/)
- [GNOME Photos archive](https://gitlab.gnome.org/Archive/gnome-photos)
- [digiKam 9.1](https://www.digikam.org/news/2026-06-07-9.1.0_release_announcement/)
- [Immich v3](https://immich.app/blog/v3.0.0-release)
