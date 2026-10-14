# Phase 1 device test checklist

Every device-only claim from `docs/handoff/MASTER-PLAN.md`'s Phase 1 gate and from
`docs/handoff/MASTER-PROGRESS.md`'s Decisions section, grouped by work package — same shape as
`docs/device-test/phase-0-checklist.md`. This sandbox has no device or emulator (confirmed
throughout Phase 1, same as Phase 0: no `adb devices`, no `emulator` package, no `androidTest`
source set), so none of these are claimed as verified — every item below is checked by the JVM/
native/Robolectric tests named in `MASTER-PROGRESS.md`'s own WP rows and Decisions, never by this
list. Check each box against a real phone, with real data, before merging PR #14.

## MUST PASS BEFORE MERGE — the ADR-011 migration check

Install this branch's build **over an existing installation of the current, pre-Phase-1 app,
carrying the owner's real photo library** — not a fresh install, not a synthetic catalogue. Let
`MigrationToV2` run to completion (it runs once, automatically, on the first process start after
upgrade — watch for it to finish before checking anything below). Then confirm, against the same
library as it was immediately before the upgrade:

- [ ] Every photo that was **favourited** before the upgrade is still favourited after.
- [ ] Every **tag** (keyword) attached to a photo before the upgrade is still attached to the same
      photo after — including multi-tag photos, and tags with non-ASCII characters.
- [ ] Every **collection** still exists, with the same name, and every photo's membership in it is
      unchanged (including photo order within a collection, if the UI shows one).
- [ ] Every **locked (private) folder** is still locked after the upgrade, and its existing
      PIN/password still unlocks it (do not set a new one to test this — use the one already in
      place).
- [ ] Every **caption** typed into a photo before the upgrade reads back identically after
      (including non-ASCII captions — this exercises the same UTF-8 path P0-08's device checklist
      already flagged as sandbox-unverifiable).
- [ ] Every **manual location pin** (a GPS position corrected or placed by hand, not read from a
      photo's own EXIF) is unchanged — same coordinates, same "checked against original" state.

If **any** of the above is not true, do not merge. This is not one item among many: it is the one
guarantee WP1.3's entire migration design exists to make, and the one failure mode ADR-011
specifically built catch-up reconciliation, the post-migration `integrity_check`/
`foreign_key_check`, and the kill-mid-migration resume path to prevent (`docs/adr/
ADR-011-catalogue-v2-and-migration.md` §5). `MASTER-PROGRESS.md`'s WP1.3 row covers what this
sandbox *could* verify (9 JVM tests, 13 Robolectric tests against the real seven old-store
formats) — none of it substitutes for running the real migration against the real library it will
actually run against on the owner's phone.

- [ ] **Kill-mid-migration recovery, on device.** Force-stop the app mid-migration (a large enough
      library that migration takes several seconds makes this easier to time) and reopen it →
      migration resumes and completes correctly, with none of the checks above failing afterward.
      Sandbox coverage: `MigrationToV2`'s resumability is proven with a fake, in-memory legacy
      source (`FakeLegacySource`) and JVM tests, never a real killed Android process.

## Gate P1's own remaining acceptance bar (MASTER-PLAN.md §3)

- [ ] **100k scroll and scrub budgets** (MASTER-PLAN.md §2.4) — not measured this phase. The old
      `GalleryProjection.kt`/`GridIndexMap` path (still what the live app runs; see WP1.5 below) is
      unchanged by every WP landed in Phase 1, so `docs/perf/baseline-jvm.md`'s existing numbers
      still apply, but a fresh 100k-scale on-device measurement was not taken.
- [ ] **The compass view and Places show GPS** (P0-02, merged in Phase 0) — re-confirm still true;
      unrelated to any Phase 1 change, listed here only because MASTER-PLAN.md's own Gate P1
      wording names it explicitly.

## WP1.1 — Toolchain upgrade

No device-only concern identified — verified via `:app:dependencies --configuration
offlineReleaseRuntimeClasspath` (no `kotlin-stdlib` drift) and `./scripts/verify.sh --native`,
both reproducible outside a device.

## WP1.2 — Module split

No device-only concern identified — the module split is a pure code-motion refactor with the
existing 977+ tests (now more) and `CatalogueCharacterisationTest`'s goldens as the proof it
changed nothing observable.

## WP1.3 — Catalogue v2 schema + migration

Covered by the MUST PASS section above. Two narrower items beyond it:

- [ ] `runCleanupIfDue()` (deletes the six old SQLite files and four old prefs files once migration
      is confirmed durable) is **not called anywhere yet** — deliberately, see
      `MASTER-PROGRESS.md`'s WP1.3 Decisions. The old stores are still present on disk after this
      PR merges. Confirm this is the intended state for the merge (i.e. cleanup is a deliberate,
      later, separate step) rather than an oversight before relying on old-store deletion.
- [ ] `CatalogueFavoriteStore`/`CatalogueSensitiveStore` (`:core:db`) are built and tested but **not
      wired into `LibraryRuntime` or the live UI** — favouriting/marking-sensitive in the live app
      still goes through the old prefs-backed stores, not these. No device check applies to code
      that isn't wired in yet; listed here so a reviewer doesn't assume otherwise from the class
      names alone.

## WP1.4 — Sync engine

- [ ] **`AndroidMediaStoreSource`'s real `ContentResolver.query` path**, on a real device: confirm a
      newly added photo, a newly deleted photo, and a photo whose file was modified (mtime changed,
      same `_ID`) are all picked up by the next sync. Sandbox coverage: only the pure
      `buildSelection` predicate is unit-tested (`AndroidMediaStoreSourceSelectionTest`); the real
      cursor-querying/column-mapping path has never run under Robolectric either, matching this
      codebase's existing precedent for the old scanner (see `MASTER-PROGRESS.md`'s WP1.4b
      Decisions).
- [ ] **Unplug/reconnect an SD card or USB volume** (if the owner's device has one) → that volume's
      assets go OFFLINE, not deleted, and a full re-sync runs on reconnect. Sandbox coverage: proven
      against `FakeSource`, never a real Android volume state transition.
- [ ] `AndroidMediaSync.syncAll()` runs once per process start today, **not** on every live
      MediaStore change (it is not hooked into the existing `scanRequests`/`MediaStoreChangeObserver`
      loop — deliberate, see Decisions). A photo taken mid-session will not appear in `fotoz.db`'s
      `asset` table until the next process start. This has no live-UI consequence yet (nothing reads
      `fotoz.db` live — see WP1.5), but is worth the owner knowing before WP1.5's cutover changes
      that.

## WP1.5 — Paging and SQL projections

**Not wired into the live UI at all** — the single biggest remaining gap of Phase 1. Everything
below describes what device testing would need to cover *once* that cutover happens; none of it
applies to this PR's actual live behaviour, since the live app still runs the old
`GalleryProjection.kt`/`MediaIndexer`/`SqliteMediaRepository` path unchanged.

- [ ] 500k-scale scroll/scrub/cold-start performance against `GalleryProjectionDao` (MASTER-PLAN.md
      §2.4's budgets) — needs a real device and a real or synthetic 500k-asset library; this sandbox
      has neither.
- [ ] Locked-folder visibility is **not implemented** in `GalleryProjectionDao` at all yet (no SQL
      join against `folder_lock`/session-unlocked state). Do not cut the live UI over to this DAO
      before this is closed — a locked folder's contents would leak into every smart album and the
      everyday timeline.
- [ ] Free-text search is **not implemented** in `GalleryProjectionDao` either, for the same reason.

## WP1.6 — App architecture

- [ ] **Rotation** — already passes today without any Phase 1 change (`android:configChanges`, P0-09
      device-checked already); re-confirm unaffected by this PR's changes as a regression check, not
      because anything here was expected to break it.
- [ ] **Process death** (`Settings → Developer options → Don't keep activities`, background the app,
      return) — expected to **fail** for everything except the one existing `rememberSaveable` call
      site (the viewer's active-asset id); no ViewModel/`SavedStateHandle` work was done this phase
      (deliberately deferred — see Decisions). Confirms the gap is exactly as documented, not
      narrower or wider.
- [ ] **A long copy/move survives leaving the app** — expected to **fail**: `MediaFileOperations`
      operations are still plain `suspend fun`s in UI-lifecycle-scoped coroutines, with no foreground
      service. Backgrounding the app mid-copy is expected to cancel it, not pause it. Confirms the
      gap, not a fix.
- [ ] Permanent delete ("shred") still has no live UI path at all (only Trash exists), so
      `audit_log`/`AuditLogDao` has nothing to check on device — listed here only so a reviewer
      doesn't look for an audit trail that cannot exist yet.

## WP1.7 — Format registry

- [ ] `FormatSniffer`'s 20 new magic-byte signatures were each checked against a primary/
      authoritative source (not assumed) but never run against a REAL file of most of these formats
      — this sandbox has no sample corpus (see WP1.8). If the owner has real AVIF/JPEG XL/PSD/EXR/
      HDR/etc. files, sniffing one of each and confirming the result matches its real format would
      close a real gap between "signature transcribed correctly from documentation" and "correctly
      identifies a real file in the wild."
- [ ] "A black tile is impossible" (WP1.7's own acceptance bar) is **not implemented** —
      `MediaFormat.isLikelyDecodable`/`FormatSniffer` are not wired into the live gallery tile
      composable at all (confirmed via grep: zero call sites). An undecodable file's tile still
      falls through to Coil's default broken-image handling today. No device check applies to a
      feature that doesn't exist yet.

## WP1.8 — Test infrastructure

- [ ] Macrobenchmark (`-Pfotoz.benchmarks`) has never actually been RUN on a device this phase, only
      confirmed to compile/configure correctly with and without the flag. Running it for real needs
      a device.
- [ ] `androidTest` source set and the format corpus (`SOURCES.md`, licence-audited) were not
      created this phase — deliberate, see Decisions (no concrete instrumented test to put in the
      former; the latter needs real, individually licence-checked sample files, a content-curation
      task, not a code change).

## WP1.9 — Governance checks in CI

No device-only concern identified — `verify-core-purity.sh`, `verify-native.sh`, and
`verify-page-alignment.sh` are all reproducible in CI/this sandbox and already wired into
`.github/workflows/android-debug.yml`. The 16 KB page-alignment guard was itself confirmed by hand
against both a debug and a release APK build in this sandbox (no device needed — `zipalign -c` is
a static check on the built artifact) — listed here only for completeness, not because a device
check is missing.

## WP1.10 — Metadata core (XMP sidecars)

- [ ] `SidecarNaming`'s darktable/Lightroom filename conventions were verified against
      documentation (darktable's own 4.6 user manual, corroborated community sources for Lightroom
      specifically — Adobe's own help pages returned HTTP 403 to automated fetch during that
      research) but never checked against a REAL darktable- or Lightroom-written sidecar file. If
      the owner has access to either tool, writing a sidecar for a real raw file and confirming this
      app's naming matches what that tool actually produced (and that `XmpSidecarStore.read` parses
      it) would close a real gap between "documented convention" and "what version N.M actually
      does."
- [ ] The Android-side glue to locate or create a sidecar file beside a real, scoped-storage
      MediaStore asset **does not exist yet** (deliberately deferred — see Decisions). There is
      nothing to device-test here: `MetadataWriter` does not call into `XmpSidecarStore` at all in
      this PR. Listed so a reviewer looking for "does editing a RAW's metadata write a sidecar on my
      phone" knows the answer is: not yet, by design, this increment only built the portable,
      JVM-tested naming/parsing core.
