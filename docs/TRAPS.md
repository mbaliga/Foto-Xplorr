# TRAPS — invariants that bind every session working on this repo

Each of these was earned, not invented: every entry is a defect that either shipped or
was caught one line before shipping. Breaking one is never a refactor; it is a behaviour
change that needs saying out loud. Source: the 6 Aug 2026 handoff plus plan FX-IMP-002 §8.

## Long-standing

1. **A delta scan must never call `replaceAll`.** One line away from deleting the
   library's index. The delta branch is deliberately `Unit` with a comment saying exactly
   that; `MediaIndexerTest` pins it.
2. **Never squash-merge `shared-libraries` (or `hyle-design-system`) while this app pins
   it.** A squash mints a new SHA and orphans the submodule pin; a fresh
   `clone --recurse-submodules` fails weeks later with `fatal: reference is not a tree`,
   pointing nowhere near the cause. Merge with `--no-ff`, then bump the pin here.
3. **AGP must match `hyle-design-system` exactly** (currently 8.9.1), Gradle wrapper
   pinned at 8.14.3 in all three builds. Composite builds hard-fail with *"Using multiple
   versions of the Android Gradle plugin"* otherwise.
4. **The `dependencySubstitution` block in `settings.gradle.kts` is load-bearing.**
   Declaring ANY explicit substitution for an included build disables AUTOMATIC
   substitution for that build — a module you forget to declare is silently not
   substituted. The inline reasoning stays with the block.
5. **The scrubber assumes a headerless grid.** Grid item *n* is asset *n*. Date headers,
   stacks or any non-1:1 grid item silently desync the edge timeline strip from the list.
6. **`local.properties` (with `sdk.dir`) is needed in every included build** on a fresh
   clone — the root's copy does not propagate. `scripts/verify.sh` creates them if absent
   and never clobbers an existing file.
7. **Trash requires Android 11+.** On older versions decline rather than deleting without
   the platform dialog.

## Added by FX-IMP-002 (10 Aug 2026)

8. **A partial scan never sweeps.** The generalisation of #1 to every future source: a
   scan that did not fully enumerate its root must not remove anything it failed to see.
9. **Nothing that changes grid item count ships before `GridIndexMap`.** Conflict stacks,
   duplicate stacks, grouping and headers all break #5 through different doors. (WP3
   ticket FX-044; nothing on the current branch stacks yet.)
10. **Foreign keys are enabled in `SQLiteOpenHelper.onConfigure` only.** Anywhere else
    is a silent no-op (inside a transaction) or a throw (`setForeignKeyConstraintsEnabled`
    mid-transaction). Every `ON DELETE CASCADE` is decorative until this is done.
11. **`PRAGMA integrity_check` does not check foreign keys.** It covers page/index/
    UNIQUE/NOT NULL structure. `PRAGMA foreign_key_check` is the only thing that finds FK
    violations. Run both, after COMMIT, never inside the migration transaction.
12. **The offline flavor's gate is the merged manifest and the runtime classpath, not an
    import scan.** `android.net.Uri` and `java.net.URI` do no I/O; a package-level import
    ban breaks every MediaStore/SAF call in the app. Gates: `verifyOfflineManifest`,
    `verifyOfflineRuntimeClasspath`, then a *targeted* FQCN denylist — in that order of
    authority.
13. **Never the standalone word `Synced`.** Four operating modes, four different
    promises. `Syncthing · Replicated local folder`, never `Synced by Foto Xplorr`.
14. **Never encode state in colour alone.** Pair every state with shape, label or icon.
15. **Phase C of a migration is forward-only.** The user writes to the live catalogue
    throughout a backfill; restoring the pre-migration backup to "fix" a failed batch
    silently discards everything they did since. Resume from `migration_progress`.
16. **`zipalign -P 16`, never `-p`.** At target 36 the app must support 16 KB page
    sizes; `-p` silently downgrades correct 16 KB alignment to 4 KB and native libraries
    stop loading on 16 KB devices. Verify with `zipalign -c -P 16 -v 4`.

## 17. An editor must never open the original for writing

`EditedCopyWriter` writes a NEW MediaStore row and nothing else; there is no code path in it that
opens the source for writing, and there must never be one. A photo library is frequently the only
copy of an irreplaceable image, and an editor that can overwrite one eventually will. "Save" means
"save a copy" — see `docs/adr/ADR-007-photo-editing.md`.

Related: do not reach for uCrop to "just add crop". It declares `com.squareup.okhttp3:okhttp` and
hard-fails `verifyOfflineRuntimeClasspath`, whose allowlist holds one exact coordinate and has no
escape hatch. And do not copy from Fossify/Simple-Gallery: GPL-3.0 with no "or later", which would
make this whole app GPL.

## 18. AGP's `splits {}` block is global, not per-build-type

There is no `release { splits { … } }`. Configuring ABI splits anywhere in `android {}` renames
the artifacts of **every** build type, so `app-offline-debug.apk` silently becomes
`app-offline-arm64-v8a-debug.apk` and every workflow step that names the old file fails — three
red CI runs before the cause was obvious, because the build itself stayed green and only the
artifact upload failed.

Splits are therefore gated behind a Gradle property: `providers.gradleProperty("abiSplits")`, set
only by the release job (`assembleOfflineRelease -PabiSplits`). If you need per-variant packaging,
gate it on a property; do not reach for a nested block that does not exist.

## 19. An XML comment may not contain `--`

Not a style rule — `--` inside `<!-- … -->` is malformed XML, and the manifest merger reports it
as `ManifestMerger2$MergeFailureException: Error parsing …/AndroidManifest.xml`, which reads like
a merge conflict and sends you looking in entirely the wrong place. Prose dashes in a manifest
comment must be commas or em dashes. Parse-check with `python3 -c "import
xml.dom.minidom,sys; xml.dom.minidom.parse(sys.argv[1])"` before rebuilding; it names the line,
which the merger does not.

## 20. "ML Kit" is not uniformly offline

The bundled models this app already uses — face detection, image labeling, text recognition — ship
inside the APK and need no network. `com.google.mlkit:translate` does **not**: it downloads a
~30 MB language model over the network the first time a given language pair is used, which makes
it a network-capable library in exactly the way OkHttp is.

So it is `connectImplementation`, never `implementation`, and the offline flavour's
`com.fotoxplorr.app.lens.TextTranslator` implementation must not reference the artifact at all —
it reports itself unavailable and the UI hands off to an installed translator app. Do not assume a
new `com.google.mlkit:*` coordinate is safe for the offline classpath because the existing ones
are; check whether its model is bundled or downloaded before adding it.

## 21. A StateFlow hands a new collector its CURRENT value, which for a fresh store is "empty"

`SqliteMediaRepository` (and `RecognitionStore`) load from disk in a coroutine started by the
constructor. `observeAll().first()` on a just-constructed instance returns the `emptyList()` the
flow was initialised with, not the library — the UI never notices because it collects
continuously and gets the real list a moment later, but a one-shot reader (the background pass)
saw an empty library every single time, decided there was nothing to do, and returned. No error,
no log, a feature that ran on schedule and did nothing. One-shot readers use
`awaitLoaded()`, which joins the load first. If you add a store with this shape, give it one.

## 22. `LaunchedEffect`s run in declaration order on first composition — every one of them

A `LaunchedEffect(destination, route)` that resets the search query "on change" also runs on the
browser's first composition. A seeding effect declared ABOVE it set the query, and the reset
below it wiped the seed in the same frame: the Search pill closed the viewer and opened an empty
grid. Effects that must have the last word go last, and an effect that reacts to a change your
own code is about to make needs a flag to tell that change from the user's.

## 23. JobScheduler's "idle" is not Doze

`JobInfo.setRequiresDeviceIdle` is satisfied by the platform's own idle tracking (screen off or
docked plus an inactivity timeout). `PowerManager.isDeviceIdleMode` is Doze, which never engages
on a charger. Re-checking the first with the second blocked the exact overnight-on-the-charger case
the rule exists for. Where the platform enforces a constraint before `onStartJob`, report it as
satisfied; do not re-derive it from a different signal.

## 24. `MediaExtractor` sample flags and `MediaCodec.BufferInfo` flags share bit values

`SAMPLE_FLAG_SYNC` is 1 and so is `BUFFER_FLAG_KEY_FRAME`, so a pass-through works on keyframes
by luck. `SAMPLE_FLAG_PARTIAL_FRAME` is 4, which the muxer reads as `BUFFER_FLAG_END_OF_STREAM`.
Translate explicitly; lint's `WrongConstant` is right and CI runs it even when you do not.

## 25. Two writes to the same key in one `SharedPreferences.Editor`: the last one wins

`putStringSet(KEY, read() + item)` inside a loop reads the NOT-YET-APPLIED preferences each time,
so every iteration overwrites the previous iteration's pending value with "original plus this
item". Only the last item survived. Accumulate in memory, write the key once.

## 26. Two instances over one `SharedPreferences` file lose writes

Every `LibraryStore` mutation is a read-modify-write of a whole set key. Two instances (Activity on
Main, background job on Default) can read the same starting set, and the second `apply()` drops
whatever the first added — and each instance's StateFlow only refreshes on its own writes, so the
other's changes are invisible until restart. One instance per process (`LibraryStore.get`), every
mutation `@Synchronized`. The constructor is `internal` for tests only.

## 27. `AndroidView`'s factory runs once per node — identity must be a `key()`

`remember(asset.id) { VideoView(...) }` handed to `AndroidView(factory = { videoView })` built a
new view per video and attached none of them after the first: the factory lambda is called once
for the node's lifetime and later changes to it are ignored. Swiping between videos showed the
old, stopped view. Wrap the composable in `key(asset.id) { … }` so a new video is a new node.

## 28. ML Kit Translate wants "en", not "en-US"

`TranslateLanguage.fromLanguageTag` recognises its bare two-letter codes (plus three legacy
aliases) and returns null for anything region-qualified, which is what `Locale.getDefault()
.toLanguageTag()` returns on practically every device. Every on-device translation fell through to
the hand-off. Pass `Locale.forLanguageTag(tag).language`.

## 29. The cove meets a screen EDGE, never another shape

`HyleNotch`'s coves are inverted-radius flares that blend a black surface into the edge it touches:
the toolbar into the top, the trash into the bottom. They only read correctly against an edge. When
the count moved to the top right, mirroring the old bottom-left pill's cove cap so it would face
the toolbar produced a spike hanging down into the photographs and a flare wide enough to fuse the
count and the toolbar into one black band. A surface floating clear of an edge takes a plain
rounded rectangle. A second consequence: a cove has to be drawn at its design aspect or its curve
distorts, so a coved surface is a fixed size and only a plain one can be sized to its content.

## 30. Render a layout before believing it

Three defects in one screen survived reading the code and died the moment it was rendered: a
sideways-scrolling action row that clipped its fourth item mid-word, an album name printed as a
bare unlabelled line that read as a mystery word, and a camera card repeating the labelled list
beneath it field for field — including focal length and aperture twice within the card itself.
`ScreenRenderTest`/`NewSurfaceRenderTest` draw the real composables to PNG on the JVM in seconds.
Use them; a layout you have only read is a layout you have not checked.

