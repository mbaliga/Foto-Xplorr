# TRAPS — invariants that bind every session working on this repo

Each of these was earned, not invented: every entry is a defect that either shipped or
was caught one line before shipping. Breaking one is never a refactor; it is a behaviour
change that needs saying out loud. Source: the 6 Aug 2026 handoff plus plan FX-IMP-002 §8.

## Long-standing

1. **A delta scan must never call `removeAllExcept`.** One line away from deleting the
   library's index. The delta branch is deliberately `Unit` with a comment saying exactly
   that; `MediaIndexerTest` pins it. (P0-09: replaces the old `replaceAll` — same invariant,
   new name, since a full pass's own deletion reconciliation is no longer a wholesale
   delete-and-reinsert.)
2. **Never squash-merge `shared-libraries` (or `hyle-design-system`) while this app pins
   it.** A squash mints a new SHA and orphans the submodule pin; a fresh
   `clone --recurse-submodules` fails weeks later with `fatal: reference is not a tree`,
   pointing nowhere near the cause. Merge with `--no-ff`, then bump the pin here. Order matters
   too: the pin must exist on the library's remote before this app's PR merges. Batch 3 pins
   `18fc20e` (batch 2 pinned `1f33d10`) on `claude/friendly-lovelace-06dito` (PR #17 in
   `Shared-Libraries-asoc`), so that PR merges first.
3. **AGP must match `hyle-design-system` exactly** (currently 8.9.1), Gradle wrapper
   pinned at 8.14.3 in all three builds. Composite builds hard-fail with *"Using multiple
   versions of the Android Gradle plugin"* otherwise.
4. **The `dependencySubstitution` block in `settings.gradle.kts` is load-bearing.**
   Declaring ANY explicit substitution for an included build disables AUTOMATIC
   substitution for that build — a module you forget to declare is silently not
   substituted. The inline reasoning stays with the block.
5. **The scrubber, pill and arrow keys must agree with the grid through `GridIndexMap`,
   never grid-item-*n*-is-asset-*n*.** Fixed for date headers by P0-15
   (`core/organize/src/commonMain/kotlin/com/fotoxplorr/core/organize/GridIndexMap.kt`):
   `GalleryScreen` now converts every grid index through it
   before it reaches `pillCaption`/`gridState.scrollToItem`, and `renderedAssets` (not
   `scrubberAssets`) is the one asset list all four agree on. Still true in masonry mode,
   where the staggered grid keeps a `LazyStaggeredGridState` this screen cannot reach at
   all — the scrubber and the pill's position text are hidden there instead of tracking a
   position nothing on screen agrees with (see `MediaGridScreen`'s own doc).
6. **`local.properties` (with `sdk.dir`) is needed in every included build** on a fresh
   clone — the root's copy does not propagate. `scripts/verify.sh` creates them if absent
   and never clobbers an existing file.
7. **Trash requires Android 11+.** On older versions decline rather than deleting without
   the platform dialog.

## Added by FX-IMP-002 (10 Aug 2026)

8. **A partial scan never sweeps.** The generalisation of #1 to every future source: a
   scan that did not fully enumerate its root must not remove anything it failed to see.
9. **Nothing that changes grid item count ships before `GridIndexMap`.** `GridIndexMap`
   now exists (P0-15) and Timeline's date headers go through it; conflict stacks and
   duplicate stacks still don't exist yet and must go through it too, the day they do.
   (WP3 ticket FX-044.)
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
    promises. `Syncthing · Replicated local folder`, never `Synced by Fotoz`.
14. **Never encode state in colour alone.** Pair every state with shape, label or icon.
15. **Phase C of a migration is forward-only.** The user writes to the live catalogue
    throughout a backfill; restoring the pre-migration backup to "fix" a failed batch
    silently discards everything they did since. Resume from `migration_progress`.
16. **`zipalign -P 16`, never `-p`.** At target 36 the app must support 16 KB page
    sizes; `-p` silently downgrades correct 16 KB alignment to 4 KB and native libraries
    stop loading on 16 KB devices. Verify with `zipalign -c -P 16 -v 4`.

## 17. Overwriting the original is a named, consent-gated choice — never a silent default

`EditedCopyWriter.save`/`saveTo` write a NEW MediaStore row; `overwrite` is a separate,
deliberately-named function that DOES open the source for writing — this is Replace the
Original, one of three save modes a user explicitly picks (see
`docs/adr/ADR-007-photo-editing.md`). A photo library is frequently the only copy of an
irreplaceable image, so the invariant is not "never write the source" but: `overwrite` must
never run without the same three-tier Android consent dance every other in-place write in this
app uses (`RecoverableSecurityException` pre-Q, `MediaStore.createWriteRequest` on R+), the
editor must stay open across that round-trip so a declined consent does not silently discard the
edit, and the default save mode remains "save a copy" — overwrite is opt-in per edit or via an
explicit, reversible Settings choice, never the silent behaviour of plain "Save".

Consent is only half of it. A Replace can fail after the original has been truncated, and it
is **best effort, not atomic**: MediaStore has no replace-in-place. The writer therefore throws a
typed `OverwriteException`: `Refused` (a `Reason` of `METADATA_WOULD_BE_LOST`,
`WOULD_DESTROY_CONTENT`, `FORMAT_UNSUPPORTED` or `BACKUP_FAILED`) means the original's bytes were
never opened for writing; `FailedAfterWrite(restored, recoveryFile)` means they were, and says
whether they were put back. Any other failure type means the original was never opened (an encode
failure, or write consent declined). A message must never say "your photo is untouched" for the
second kind, and must never blame Android's consent for a failure that was not a refusal. The full
contract, and what may be promised about recovery, is #59.

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
(Still true after the move to Media3: `VideoPlayer` hosts a `PlayerView` in an `AndroidView`, and
`ViewerScreen` still wraps it in `key(asset.id)`; see also #39 and #42 for the rest of its lifecycle.)

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

## 31. A location read may ask for the unredacted original; only reads whose bytes stay inside the app may

`Context.uriForLocationRead` exists so this app can show a user their own photo's real GPS —
`MediaStore.setRequireOriginal`, gated behind the `ACCESS_MEDIA_LOCATION` permission, is how a
reader asks the platform to skip the Android 10+ location redaction (P0-02: before this, the
permission was requested but the method was never called, so every location read silently saw no
GPS and the absence was then cached forever). Reusing that call, or its already-original uri, on a
share, zip or export path would launder a redacted uri into one that leaks a location the user
never consented to send to another app.

The sanctioned callers are the ones whose output never leaves the app: `GeoMetadataRepository`;
the detail room's own EXIF read; and, since batch 2, the editor's and the metadata writer's own
reads, which exist to stop a *redacted* view from being written back over the user's photo —
`MetadataWriter`'s staging copy (a read only: the write into the original still never goes through
the unredacted view, #35), `EditedCopyWriter.applySourceMetadata` (the staging metadata copy),
`EditedCopyWriter`'s `ResolverOriginal.openForRead` (the safety copy, its read-back and the size
check) and `readOverwriteHazards` (the replace guard's scan). Their bytes feed staging files, the
private safety copy under `filesDir/overwrite-recovery` and byte comparisons. Every
share/zip/export path keeps reading the plain uri exactly as before P0-02, and Save As (`saveTo`)
passes `keepLocation = false` and proves it with `carriesLocation`, so an export to a place the
user picked never carries a location out. A new caller names, in its KDoc, where the bytes end up.

Since batch 5 there is one more sanctioned caller, and it is the one exception that DOES write the
original location somewhere the person chose: `MediaStoreBackupSource` (`backup/BackupSource.kt`), the
reader of the encrypted backup (#75). A backup that quietly lost every photo's GPS would be a worse
copy than the photo it replaces, so it reads the unredacted original on purpose. The bytes end up
only inside an AES-GCM encrypted pack, in the folder the person picked, readable only with their
passphrase; nothing hands a plain stream, or a uri that carries the original, to another app. The
preflight card says when `ACCESS_MEDIA_LOCATION` is not granted ("Photo locations will NOT be in this
backup"), and an entry records whether it carries the original location (`EntryFlag.ORIGINAL_LOCATION`).
Any OTHER new caller on a path that leaves the app is still a violation.

## 32. A metadata read failure is not an absence

Three places distinguish "there is genuinely nothing here" from "the read itself failed":
`GeoMetadataRepository`'s `LocationReadOutcome` (`Located`/`NoLocation`/`Unreadable`, P0-02 — a
denied permission or a transient open failure used to be persisted as "no location", forever), the
detail room's own metadata display (a failed read must not render as an unfilled field the user is
invited to fill in themselves), and `RecognitionStore`/`EmbeddingRepository`'s per-attempt failure
tables (P0-12 — an unreadable file used to be retried forever with nothing recorded, while a
genuinely faceless photo is recorded once and correctly left alone). Collapsing "failed" into
"empty" either hides a real problem forever or retries it forever; keep the failure a first-class
outcome and act on it once.

## 33. One visibility filter for anything that draws photos

`browsableAssets(...)` (`GalleryProjection.kt`) is the one "may be shown right now" rule — it
excludes trashed, archived, hidden-sensitive and locked-folder assets. Every screen that lists
photos filters through it, or a name built on top of it (`everydayAssets`); a screen that instead
writes its own `filterNot { it.isTrashed }` will silently leak locked or hidden photos onto a map, a
calendar, or a strip of cover thumbnails the moment it diverges from whatever the other screens
happen to filter on that week (P0-01: five separate leaks, three named in the original brief and
two more found only by auditing every consumer of `state.assets`).

## 34. Pixels come from `decodeUpright`, never a raw `BitmapFactory`/`ImageDecoder` call

`media/BitmapDecoding.kt`'s `decodeUpright(context, uri, DecodeLimits)` is the app's one decode
entry point: it applies EXIF orientation and samples to the exact requested bound, not the nearest
power of two under it. Nine call sites decoded independently before P0-03, each ignoring
orientation and under-shooting its own target size. A new decode path that calls `BitmapFactory` or
`ImageDecoder` directly reintroduces both bugs at once.

Fixed after batch 2: `decodeWithBitmapFactory` (the path below API 28, and the fallback when
`ImageDecoder` throws) used to end its bounds-only pass with `?: return null`. A bounds-only decode
returns null by design, so every image failed on API 26 and 27. It now returns null only when the
stream itself is missing and reads `outWidth`/`outHeight` from the options; the `ImageDecoder` path
was never affected. `BitmapDecodingBitmapFactoryTest` runs it at SDK 26. Keep it that way: never
use the return value of a bounds-only `decodeStream`.

## 35. A metadata write goes temp → verify → original, never through a redacted view

`MetadataWriter.write` (P0-08) stages the source into `cacheDir/metadata-staging/`, edits and
independently re-reads the temp file to confirm every field actually landed and its bounds are
unchanged, and only then streams the verified copy into the original with `"wt"`. Writing through a
location-redacted file descriptor risks `ExifInterface`'s own read-modify-write cycle silently
erasing GPS tags the edit never touched (see `RedactingFileDescriptor`'s own AOSP javadoc) — so a
metadata write never opens through `uriForLocationRead`, and never writes straight to the original
without a verified intermediate copy. (The staging read itself does use `uriForLocationRead`: #31
sanctions it because the bytes only land in `cacheDir`. It is the *write target* that must never
be the redacted view.)

## 36. A share output is verified clean or it is not sent

`MetadataStripper`'s stripped bytes are re-parsed and confirmed to carry no GPS before
`SharePreparer` ever hands them to the caller (P0-04); `StreamCopyRemuxer` re-verifies a remuxed
video's own location is null the same way (P0-05). A stripper that trusts its own output without
re-reading it is one parsing bug away from "removed" quietly meaning "attempted to remove."

## 37. `ExifInterface.getDateTimeOriginal()` compiles, and Lint fails the build on it anyway

It is a public Kotlin property accessor on `androidx.exifinterface.media.ExifInterface`, resolves
fine, no deprecation warning — and is annotated `@RestrictTo(LIBRARY)` upstream. Android Lint's
`RestrictedApi` check treats that as a hard error, not a warning: `"ExifInterface
.getDateTimeOriginal can only be called from within the same library"`. A green compile and a red
`lintOfflineDebug` for the same line is not a flaky gate; it means Lint saw an annotation the
compiler doesn't enforce. Read the raw tag with the ordinary public `getAttribute
(ExifInterface.TAG_DATETIME_ORIGINAL)` and parse the `"yyyy:MM:dd HH:mm:ss"` string yourself instead
of calling the restricted accessor.

## 38. A KDoc naming a glob or a bare `*/*` mime type can close its own comment

`/** ... */` closes at the first `*/` it finds, with no awareness of Markdown backticks or intent —
a doc comment naming the wildcard mime type `"*/*"` (P0-20, writing down `matchesRequestedMimeType`'s
own contract) closes itself two characters in, and every word after that point becomes code the
compiler tries to parse, surfacing as a wall of unrelated syntax errors far from the real cause. The
same shape bites over a glob path (`*/*.jpg`) just as easily. Reword around the literal instead of
trying to escape it — a backslash has no escape meaning inside a Kotlin comment and only makes the
rendered doc uglier for no safety.

*Entries 39 onward were earned in the 21 Oct 2026 owner-feedback batch (videos not playing, the
filmstrip over a video, subject lift, the curves editor, the shrink-to-map transition). Each names
the report it grew out of. None of it has run on a phone: the claims are checked against the code
and the JVM/Robolectric tests, and what only a device can settle is in
`docs/device-test/batch-1-checklist.md`.*

## 39. The filmstrip is roll-level navigation — never gate it on the current asset

Owner #28: *"When I scrub through the filmstrip and stop on a video, the video player progress bar
etc. replaces the filmstrip so scrubbing through is no longer possible."* The strip drives the
viewer **live** — `onSelect` fires mid-drag, the moment a different thumbnail is nearest the centre
— and it was drawn under `!asset.isVideo && …`. So the instant the drag landed on a video the strip
left composition from under the finger, the gesture was cancelled, and the video's own transport
took the space. A control whose input changes the very thing its visibility depends on will always
do this. `ViewerChromePolicy.filmstripShown` therefore takes no asset and no `isVideo` (chrome up,
not in picture-in-picture, the preference, a roll larger than one, not lifting), and
`ViewerChromePolicyTest` pins that shape. The video transport makes room instead:
`VideoPlayer.bottomInset = FilmstripGeometry.REST_BAND_DP`.

Two companions. `FilmstripScrubber.onScrubbingChanged` must report `false` from `onDispose`: the
strip is chrome, so toggling the chrome or opening the lift editor removes it mid-fling, and a host
left believing someone is still scrubbing keeps its video paused and un-armed
(`VideoPlayer.scrubSuspended`) for good. And do not unmount the player while scrubbing: `VideoPlayer`
builds nothing until it has been on screen `ARMED_START_DELAY_MS` (250 ms) with no finger on the
strip, so a scrub past ten videos creates no ExoPlayer, no sidecar query and no indexer run. That
placement also covers a page swipe and the details room, which a scrub-only gate in `ViewerScreen`
would not.

## 40. The shrink-to-map flight is a pure function of `vProgress`; the room slides 1:1, so time an animation to when its target is on screen

Owner #19: *"The transition effect of the photo becoming smaller and appearing on the map ... is not
happening."* It was happening — somewhere nobody could see. The details room is the TOP room and
the shell places it at `y = -viewportHeight × (1 − v)`, a rigid slide that follows the finger 1:1.
The old morph lived inside the plate and was staged over a window of the drag in which the plate was
still mostly above the top of the screen, so the shrink played off-stage. Whenever something animates
inside a room that is itself still arriving, ask at which `v` its target is actually on screen, and
either stage it there or give it a surface of its own.

The fix is `PhotoToPinFlight`: one copy of the photo, an overlay above the shell, timed over the
**whole** pull (`PlaceMorph.flightT`, a smoothstep over 0..1). It has no state and no animation of
its own; every pixel is derived from the shell's `vProgress` inside `graphicsLayer`/draw lambdas, so
it reverses on close, holds perfectly still under a stationary finger and never recomposes during a
drag. Because the slide is rigid the pin's screen position is analytic (`PlaceMorph.pinRectAt`,
`restY − H × (1 − v)`) and is measured once, in room-local coordinates. What bit while building it:

- The hand-off is the real pin **opaque underneath a fading flier** (`realPinVisible(v)` steps to 1
  at `v ≥ 0.96`), not a cross-fade of two copies whose alphas sum to 1 — that dims the plate for a
  few frames.
- Measure the pin tile with `onGloballyPositioned` **before** the hero `graphicsLayer`, or you
  measure the hero-scaled tile instead of where it lands.
- Never stretch a layer into the pin's square: it squashes the photo on the way. The flier scales
  uniformly and narrows a crop window (`PlaceMorph.cropPlacement`), with no relayout or re-decode.
- The flier and `PhotoDetailRoom(flightActive)` must be driven by ONE flag
  (`ViewerChromePolicy.flightActive`). A room waiting for a flier that was never composed leaves its
  pin invisible.
- The pin rectangle is snapshot state read only in draw/layer lambdas, so a report that lands after
  the last progress change still repaints the flier.
- No map tile is fetched or pre-warmed by any of it. The connect flavour's one-shot snapshot still
  waits for the room to settle (`SETTLE_DEBOUNCE_MILLIS`, 160 ms). That is a privacy rule, not a
  performance one: nothing leaves the phone because a finger moved.

The old `LocationPicker` spinning world (owner, 2026-08-18) is gone on purpose: the owner called the
unknown-location map *"weird, and not comforting at all"*, and the picker's KDoc says not to restore
it. The unknown-location plate is a still graticule with a dimmed ghost pin, and it has to look right
on the graticule alone because the offline flavour never has a map behind it.

## 41. `ParkStyle` for the top room is chosen app-side, because `cell-shell` is another repo's submodule

The flier follows the parked card's *rectangle*, and a swivelling card is not a rectangle, so the
details room needs the shell's flat `ParkStyle.SLIDE` while every other room keeps `SWIVEL`. The
first plan added a `topParkStyle` parameter to `SpatialShell`. But `shared-libraries/` is a git
submodule of a different repository (`.gitmodules`: `Shared-Libraries-asoc`), consumed through
`includeBuild`; no change there can be part of a PR on this repo, and `cell-shell` is a contract
shared with other apps. `parkStyle` is an ordinary parameter the shell reads every composition, and
its swivel angle is multiplied by `lift`, which is ≈ 0 at the instant `vProgress` leaves 0 — so
`ViewerScreen` passes `parkStyle = if (topPulling) SLIDE else SWIVEL` itself, with `topPulling` a
`derivedStateOf { shell.vProgress > 0.001f }` so the screen recomposes when the flag flips, not on
every drag frame. No pop at the flip. `ParkStyle.entries.size == 2` is pinned by the submodule's
`SwivelParkTest`; do not plan on a third style.

The general rule: when a fix seems to need a change under `shared-libraries/` or
`hyle-design-system/`, first look for a parameter the shared code already reads. The one thing this
app cannot read from the shell is its private `BAND_DP`; `PlaceMorph.SHELL_BAND_DP` (72) mirrors it
by hand, so a change to the shell's grab band has to be echoed there or the flier drifts off the
parked card.

## 42. Resume is saved at `ON_STOP`, not in `onDispose` — dispose never runs on process death

A backgrounded viewer is exactly what Android kills, and when it does nothing is disposed and no
`finally` runs. `VideoPlayer` used to save the resume position only in dispose and after its polling
loop, so "Resume from" never appeared. It now writes on pause, on `ON_STOP` (the load-bearing one),
when the poll loop is cancelled, and just before release. Anything else that must survive a killed,
backgrounded screen follows the same rule: persist at `ON_STOP` or on the state change, not on
teardown.

Corollary: `while (isActive) { …; delay(…) }` followed by a save is unreachable, because cancellation
is delivered by throwing out of `delay()`. Put the save in a `finally`. (`VideoPlayer`'s
`LaunchedEffect(asset.id, isPlaying)` has the comment at the exact spot.)

## 43. The offline source scan is line-based and reads comments

`verifyOfflineSourceReferences` (`app/build.gradle.kts`, `VerifyOfflineSourcesTask`) reads every
`*.kt` under `app/src/main/java` and `app/src/offline/java` **line by line** and fails on any line
that merely contains one of: `okhttp3.`, `io.ktor.client`, `java.net.Socket`, `java.net.ServerSocket`,
`java.net.HttpURLConnection`, `javax.net.ssl.`, `android.net.ConnectivityManager`,
`android.net.NetworkRequest`, `.openConnection(`, `.openStream(`. It has no idea what a comment or
KDoc is, so a sentence explaining *why* the code avoids `ConnectivityManager` fails the offline build
exactly as an import would. Write around the token ("the platform connectivity service", "the HTTP
client library") — in comments, KDoc and string literals alike. Tests under `src/test` are not
scanned; docs are not scanned. This is the *targeted denylist* tier of TRAPS #12, and it comes last
in that list's order of authority: the manifest and runtime-classpath gates are what actually keep
the offline flavour offline. Run `:app:verifyOfflineSourceReferences` after writing any comment that
names a network or connectivity API, even to say the code avoids it.

## 44. A `LazyRow` beside a sibling in a `Row` starves the sibling

A lazy list with no width modifier takes **all** the width its parent offers, and a `Row` measures
its children in order, so whatever is placed after it is measured at roughly zero. The editor's "Pro
tools" chip sat after the tool strip in a `Row(SpaceBetween)`: it was measured at nothing, the
Simple/Pro switch was unreachable, and Curves — a Pro-only tool — went with it (owner #15, *"Curves
doesn't show me the curves at all"*). Chip-width arithmetic had suggested the chip was merely
clipped; the real cause is Compose measure semantics, independent of any chip's width. Give the
lazy list its own full-width row, or `weight(1f)`, and put its siblings in an explicit slot. The
Simple/Pro tier is gone (one scrollable strip, Curves third); the rule is not.

## 45. ExoPlayer errors must be handled and surfaced — a swallowed `PlaybackException` is a black frame forever

Owner #1, *"Fotoz is not playing videos"*. The old `VideoPlayer` registered no `onPlayerError`:
a decoder, format, I/O or permission failure left a black or first-frame view with a Play glyph that
did nothing, because `play()` on an idle player sets `playWhenReady` and never re-prepares, and
`PlayerView`'s built-in error text is off unless an `ErrorMessageProvider` is set. Every player now
maps `PlaybackException.errorCode` through `playbackFailureFor` to plain-language copy, shows a panel,
and offers Retry only where trying again can help and Open with where another app might succeed.
Never log a display name or URI with the error (the code name only).

The sidecar-subtitle path is the same hazard in disguise. A `.srt`/`.vtt` that cannot be opened is a
load error on the *merged* media source, and a bad subtitle must never cost the owner a good video.
`VideoPlayer` resolves sidecars before the first prepare (bounded by `SIDECAR_QUERY_TIMEOUT_MS`),
probes each with one open/close (`filterReadableSidecars`), and on an I/O or parse-family error with
sidecars attached silently reloads **once** without them (`shouldRetryWithoutSubtitles`). That the
error is fatal rather than ignored is inferred from Media3's behaviour, not observed on a device —
the retry is cheap insurance either way. Any new `Player` you build goes through `buildViewerPlayer`
and needs a listener with an `onPlayerError`; there is no acceptable listener without one.

## 46. Transport state is intent, not `isPlaying`

`Player.isPlaying` is false while the player is buffering, which is the whole stretch between pressing
Play and the first frame. A transport keyed on it shows "paused" while `playWhenReady` is true, makes
Pause unpressable until the first frame, and — after an error or `STATE_ENDED` — a Play button that
only sets a flag. The rule is Media3's own `Util.shouldShowPlayButton`: show Play when
`!playWhenReady`, or the state is `IDLE` or `ENDED`. `transportShowsPlay` mirrors it as a pure
function for the recomposition-driving display state; the click itself goes through
`Util.handlePlayPauseButtonAction`, which re-prepares an idle player and replays an ended one with
no duration needed. One hole: that helper does nothing while the timeline is **empty** (before the
first `setMediaItem`), so `VideoPlayer` toggles `playWhenReady` directly in that window, and ignores
`seekTo` on an empty timeline (the later `setMediaItem` would discard it).

## 47. Resume writes are gated on "engaged", and an external video must not share a resume key

Two ways the resume store was made to lie. First: saving on an open-and-leave writes 0:00, which
`ResumePositionStore` treats as "not worth keeping" and **clears** — erasing the very position the
person came back to resume. `resumeWriteFor` saves only once the viewer has played or seeked this
video in this session. Second: `ExternalViewerActivity` built every asset with the shared
`MediaId.EXTERNAL` and without `isExternal`, so all open-with videos shared one resume key
(`resume_-9223372036854775808`) and the manual key-moment button wrote catalogue rows for files this
app does not own. It now uses `syntheticNegativeMediaId(uri)` with `isExternal = true`;
`ResumePositionStore` refuses every negative id as a second line; and `VideoPlayer` derives
`catalogued = catalogueActions && !asset.isExternal` — the VideoPlayer-side gate is load-bearing.
(An earlier version of this entry added "because `FotoXplorrActivity` never passes
`catalogueActions = false`". That stopped being true in batch 2: `FotoXplorrActivity`'s viewer now
passes `catalogueActions = !activeAsset.isExternal` for a photo opened from an EDIT or SEND intent,
and `ExternalViewerActivity` passes `false`. The `isExternal` gate stays as a second line.)

## 48. Lift maps taps through the decoded bitmap, never `MediaAsset.width`/`height`

Owner #12, *"Tap the subject to lift it is not working."* One cause: the overlay fitted the image
rectangle from `asset.width`/`asset.height`, which are whatever MediaStore reported — possibly the
sensor's un-rotated grid for an EXIF-rotated portrait — while the segmenter works on the upright
decoded bitmap. Fitting the wrong aspect shifts the rectangle, so a tap on the photo reads as a tap
on the letterbox (which used to dismiss the overlay) or seeds the wrong pixel. `LiftMapping` takes the
bitmap's own width and height. The same `asset.width/height` pair is still handed to
`LiveTextOverlay` and to the flight's `photoRect`, so Live Text and the shrink-to-map start rectangle
may misalign for a rotated-EXIF photo — **unverified, not fixed**, and listed in the device checklist.

*Text half fixed.* `ViewerScreen` now lays the text layer over `PlaceMorph.restingImageSize(...)` (the
EXIF-displayed upright size) and the word-level layout carries its own decode's size
(`TextLayout.frameWidth/Height`), never `asset.width/height`. The block-only and word-level layouts
are NOT the same size (full-size upright frame against a 2048 px decode), so a selection carried from
one to the other is rescaled (`TextSelection.carriedOver`). Whether it lines up on a real EXIF-rotated
portrait is still a device check. The flight's `photoRect` already used the upright size.

## 49. `runCatching` around suspend work swallows cancellation; and `decodeUpright` can throw before its own `runCatching`

`runCatching { suspendCall() }` catches `CancellationException`, so a cancelled coroutine keeps running
as if the call had merely failed. Lift cancels a solve on every new stroke, so it uses
`catchingResult` (`LiftMapping.kt`), which rethrows cancellation. And `decodeUpright` calls
`readOrientation(...)` **outside** the `runCatching` that protects the decoder: `openInputStream` can
throw `FileNotFoundException` or `SecurityException` for an unreadable or permission-revoked URI, and
from inside a `LaunchedEffect` that crashes the composition scope instead of showing a dead overlay.
Any caller of `decodeUpright` on a URI it does not control guards the call (lift now shows
"Couldn't open this photo for lifting" with Close).

## 50. A `pointerInput` keyed only on the asset captures its closure once

`detectTapGestures` consumes the down event and the lambda you passed is captured when the
`pointerInput` block starts, so a block keyed on `asset.id` sees whatever the surrounding state was
at that moment, forever. The Live Text long-press guard read a stale `liveTextBlocks` list that way.
Anything that can change without the asset changing — the animated sniff result, recognised text
arriving after OCR — must be read through `rememberUpdatedState`, as `ViewerScreen` does for
`canLift` (a mode flag held in a `mutableStateOf` is read at call time and needs no wrapper).

*Fixed:* the always-on text layer is gone. `LiveTextOverlay` renders nothing at all unless Select text
is on (the `LiftOverlay` rule), so a photo that is only being looked at has no invisible tap target
over it: tap-to-hide-chrome, double-tap zoom and long-press Lift all work on a photo with text, and
`longPressStartsLift` no longer looks at the chrome or the recognised-block count. While the mode IS
on, a long press is not Lift (leave the mode first).

## 51. An overlay that draws its own stage belongs outside the viewer's zoom layer

`LiftOverlay` paints an opaque near-black stage and the decoded bitmap itself, so it must sit outside
the pinch/rotate `graphicsLayer`: inside it, a zoomed or turned photo would give the editor chrome
the same zoom and rotation. It also consumes every touch on itself, so the gestures beneath never
see them; and `ViewerScreen` withdraws the rooms (null slots) while it is up — but only once the shell
is back home (`ViewerChromePolicy.roomsWithdrawn`), because a room closed to start the lift should
slide away rather than vanish. Its back handler registers after the shell's, so the most recent
enabled one wins and Back closes Lift before the viewer.

## 52. `ToneCurve.isIdentity` and `toLut()` must never call each other

`toLut()` returns the shared identity table when the curve is the identity, and `isIdentity` is
"the table equals the identity table" (a collinear three-point curve counts, which a point-count check
cannot see). Written naively that is `isIdentity → toLut() → isIdentity` forever. Both read one private
cached table (`computed`, a `lazy`), and the table is **rounded**, not truncated: truncation put a
collinear three-point curve one level low, so a curve that was visually the identity was not
recognised as one and read as an edit.

## 53. Never drag a curve point through `withPoint` — use the index-based edits

`ToneCurve.withPoint` merges by x: it never moves the grabbed point, it leaves stray points behind a
fast horizontal drag, and the list re-sorts under any index you saved at touch-down. The editor uses
`moveTo` / `insertAt` / `removeAt`, which never reorder, so an index taken at touch-down is valid for
the whole gesture. Inside one `awaitEachGesture`, the event that crosses touch slop while waiting out
the long-press timeout must also be **applied** as the first move; throwing it away dropped the first
leg of every drag on an existing point (found by `CurvesEditorGestureTest`).

## 54. Curve editor state and layout: read the latest recipe, hoist what a tab switch resets, keep the graph out of a scroller

- When one gesture fires several edits between recompositions (insert, then move), the callback must
  build on the latest recipe, not the value captured at composition: `EditorScreen`'s `set { }`
  reads `recipe.adjustments` afresh, and the gesture keeps its own working curve.
- State kept inside a composable that leaves composition on a tab switch resets with it. The curve
  channel is hoisted to `EditorScreen` and, with the selected tool, is `rememberSaveable`, so a
  process kill returns the person to Curves on the channel they were using.
- Do not put the graph in a `verticalScroll`: a vertical drag on a control point is contested by the
  scroll container and the panel moves instead of the point. The graph sits outside; every other
  tool's controls scroll inside a cap of 40% of the screen height.
- The luma curve is applied per pixel in `applyColour`, after the three channel tables and before
  saturation, scaling RGB by Y′/Y so hue is kept. It is deliberately not folded into the channel
  tables (that would be an RGB curve in disguise). Bright saturated pixels can clip at 255 when
  scaled, which shifts their hue slightly — accepted, listed in the device checklist.

## 55. A morphological opening can erase a small region entirely

The lift segmenter tidies a flood-filled mask with open-then-close. Opening removes anything thinner
than its 3 × 3 kernel, so a tap on a thin branch, or a textured patch that filled only a few pixels
wide, produced an **empty** mask — reported to the person as "Couldn't find a clear subject", which
was the most common way a tap "did nothing". `SubjectSegmentation` now keeps the un-opened fill when
opening would leave nothing. A clean-up step may tidy a region; it must never be able to veto one.

## 56. Robolectric: `captureToImage()` times out — capture through Roborazzi

On this setup androidx's `captureToImage()` waits on a redraw (`forceRedraw`) that never completes
under Robolectric. Use Roborazzi's `captureRoboImage(path)` to write a PNG and read it back with
`BitmapFactory`; `javax.imageio` is not available on the unit-test compile classpath. See
`PhotoToPinFlightTest` for the pattern, which doubles as a picture of the frame under
`app/build/renders/`.


*Entries 57 onward were earned in the second owner-feedback batch (October 2026: audio removed, one
open-with entry point, overwrite robustness, the rooms geography, the Settings page). Like 39 to 56,
none has run on a phone; what only a device can settle is in `docs/device-test/batch-2-checklist.md`.*

## 57. There is one open-with entry point, and it is `ExternalViewerActivity`

Another app's chooser used to list Fotoz twice: `FotoXplorrActivity` carried VIEW filters beside
`ExternalViewerActivity`'s, and a `file://` filter on the first made the app read any path it could
on a sender's say-so. Now `ExternalViewerActivity` is the single VIEW target (`content://` only, in
the manifest and re-checked in `onCreate` because the activity is exported), and `FotoXplorrActivity`
holds only EDIT (`image/*`) and SEND (`image/*`, `video/*`). Its SEND filter carries no scheme because
a share names its Uri in `EXTRA_STREAM`, so `classifyIncomingIntent` enforces `content://` itself.
Never add a VIEW filter, or a data filter that can match `file://`, to `FotoXplorrActivity`; the
in-app "Open with" chooser's exclusion of its own component stays correct because EDIT remains.

Every path that opens a photo on another app's say-so (`EXTRA_OPEN_MEDIA_ID`, a `content://media`
Uri in an EDIT or SEND, process-death restore of the viewer) goes through `browsableAssets`, and the
set it reads is the stores' `StateFlow` `.value` **at the moment of the call**. The composition-time
locals start out `emptySet()` (#21), so a check against them would let a locked photo straight
through during the first frames. A photo the library hides is refused with a notice and never shown
as an "external" asset; that would put it on screen by a side door (#58).

## 58. Hidden photos have side doors, and a Uri can be spelled many ways

`browsableAssets` hides locked-folder, archived, trashed and sensitive-hidden photos (#33), but an
outside request names a photo by *address*, and the address has more than one spelling.
`classifyMediaStoreUri` reads a Uri the way Android does: the authority and each path segment are
percent-decoded (`%6Dedia`, `/media/%34%32`, `/media/42/thumbnail` all reach row 42), an authority
may carry a user id (`0@media`, everything up to the last `@`), and the last numeric segment is the
row id. A photo-picker Uri shares the `media` authority but its trailing number is a *picker* id, not
a `_ID`: treating it as a row (the old check compared the authority and parsed the last segment) could match the wrong catalogued photo. A media-provider Uri that
yields no row id is refused, never opened as an external file. A row the catalogue has not scanned
yet (a photo saved a moment ago, inside the scan debounce) is looked up in MediaStore, with the same
bucket / relative-path columns the scanner reads, and held to the same folder rules; if MediaStore
will not describe it the answer is "could not be checked", a refusal, not a pass.
`ExternalViewerActivity` builds a fresh `PrivateFolderStore` whose unlocked set is empty, so an
uncatalogued photo in a locked folder is refused there even if the folder is unlocked in the main
app. An `EXTRA_OPEN_MEDIA_ID` the catalogue does not hold is dropped once the first scan has
settled, and cannot open a viewer later when that id is scanned in. Process-death restore returns
only browsable photos (`restoreViewerRun`).

## 59. Replace the original is best effort, and the recovery story must be told as it is

What `EditedCopyWriter.overwrite` promises, and what docs and messages may say:

- It is **not atomic.** MediaStore has no replace-in-place, so the file is truncated and rewritten.
  A crash between the truncate and the put-back leaves a damaged file.
- The failure contract is typed (#17): `Refused` = original never opened; `FailedAfterWrite` =
  opened, with `restored` saying whether it was put back. Both carry text written to be shown as is.
- The **safety copy** lives in `filesDir/overwrite-recovery`, not the cache (the system clears the
  cache exactly when storage is tight). It is made and flushed before the truncate, with a sidecar
  naming the photo, and is deleted when the write verifies, or when a restore read back at the
  right size. A copy that survives is the only intact copy of a truncated original.
- A surviving copy is **unresolved until the person answers a dialog** (OK or Delete), which names the
  photo and offers *Share the copy*. Only then does an age limit start (7 days; 30 for a `.bak`, the
  copy kept after a failed put-back). It is derived from disk on every start, so a dialog that never
  appeared, or was dismissed by a tap outside, comes back.
- **There is no Restore button**; recovery is share-out only, through a staged copy so the private file
  is never exposed. No doc, message or listing may say a copy "can be retrieved from Fotoz", that the
  original "is always put back", or that Replace is "safe". It is guarded, and the guard fails open.
- The guard refuses unless the **whole** relevant metadata was carried (`MetadataCopy.COPIED`) — but
  "whole" means what `ExifInterface` can carry: IPTC-IIM, extended XMP, PNG text with real content
  and unknown WebP chunks are refused; MakerNote and the embedded thumbnail are not gated, because
  refusing them would refuse almost every camera file. Say "metadata Fotoz cannot carry over", never
  "all metadata". The hazard list (animation, Ultra HDR, Motion Photo, extra images, flattened
  transparency, lossless WebP) is recognised from bytes and from memory of layouts (Samsung's
  `MotionPhoto_Data` tag was written from memory and not checked against a real file).
- The window: JPEG is judged from its first 256 KiB (and last 64 KiB), which is exact for the
  markers it looks for. A PNG or WebP that fills the window is walked end to end, because its text and
  extra chunks can follow any amount of image data. Docs that mention "256 KiB" for PNG/WebP are stale.

## 60. A write that must outlive the screen is not owned by the screen

Rotating, changing font size or language, or Android recreating the activity cancels the
composition's coroutines. A replace running in one used to leave its lock flags set for good (the
editor stuck, no banner even though the write had failed). `AppStateViewModel.launchWrite` runs the
code that *records the outcome* in the view model's scope, with `settleInterruptedWrite` as a
backstop so the flags cannot stay set; the write itself is blocking I/O and finishes either way.
The manifest also adds `locale|layoutDirection|fontScale|colorMode` to `configChanges` so those
changes no longer recreate `FotoXplorrActivity` under a write. While a write runs: Save, Cancel and
Back are off, and an EDIT or SEND for another photo is answered "Finish saving your edit first"
rather than swapping the editor underneath it. State that records a failure (a refusal, an at-risk
original, an unanswered recovery notice) is refreshed at the single place a failure is recorded
(`declineOverwrite`), not scattered, so a failure shows its dialog at once and not at the next start.

## 61. A selection callback must not also close the room

The rail used to select a destination *and* close the room in the same callback. Once the rail
scrubs (it previews as the finger moves and commits the hovered word after a 140 ms rest, owner #5),
closing on select would end every scrub at the first word it rested on, and a release would "select"
what was already selected. Committing a word and closing the room are two decisions: committing is the rail's, closing belongs to tapping the selected word
(`onReselect`), tapping the parked card or band, or Back. The pinned (wide) rail has no
`onReselect` and never closes a room. A fling commits once where it lands, not on the way. Release
only settles. Any new picker inside a room asks the same question: does choosing close it, and who
decided that?

## 62. A destination select clears `legacyScreen`; anything drawn over the grid is gated on `paneTakenOver()`

Albums, Calendar, Discover, Library and Tidy up are shown through `legacyScreen`, and the
`legacyScreen != null` branch wins over the grid, so a rail select that did not also set it to null
left the old screen on top of the new destination (a verified bug). Every select path clears it: the
room rail, the pinned rail, the seeded search and the folder unlock. Settings and those screens now
share one scaffold (`PaneScaffold`), and `paneTakenOver(settingsOpen, legacyScreenOpen)` is the one
predicate for "the photos are not what is showing", so anything drawn over the grid gates on
that, not on `legacyScreen == null`. Back and Escape use one
order (`paneBackTarget`: room, Settings, a destination screen, the time-range panel, selection,
search, the map, the route); new back-able state is added there and nowhere else (see #71). A pane is literal black, so text with no explicit colour inherits `onSurface`,
which is dark in the light theme: the scaffold provides `LocalContentColor` (and Places provides it
once for its whole hub), and a new page must not paint its own ink.

## 63. Room geography is one table (`ui/RoomGeography.kt`)

Left = navigation, top = details, bottom = actions, right = view options, in the gallery and the
viewer. Before it, three hand-kept tables had drifted (the gallery had settings on top, actions on
the right, details at the bottom; the viewer had details on top, actions on the right, settings at
the bottom), and the viewer's "deviation" was documented as intended. Both screens now build their
slots through `roomSlots(navigation, details, actions, viewOptions)` and never name an edge, and
`clearSystemBarsFor(edge)` decides which system bar a room clears from the *edge*, so moving a kind
moves its padding with it. Settings is a page (#62), not a room. Four things to keep:

- The shell's 72 dp head band is private to `cell-shell`; `PlaceMorph.SHELL_BAND_DP` mirrors it by
  hand and `RoomGeography.ActionsPeek` is the strip's own height *below* it (the shell adds the band
  and the navigation bar). A change to the band is a change in two repositories (#41).
- #41 stands: the details (top) room parks with `SLIDE`, every other room with `SWIVEL`; the viewer
  still picks by `topPulling`, and the bottom actions room has `vProgress < 0`, so it swivels.
- `topReserve` stays: the top 56 dp strip is handed back to the notification shade at the root grid,
  because the shell otherwise claims every pull there for the top room.
- Help text names edges, so a move needs its captions changed (the viewer's GESTURES note, the
  Lift hints were checked and name no edge). Any store text that says actions are "in from the right"
  or settings "up from the bottom" is stale.

## 64. Delete only what the person was shown

The recovery dialog's *Delete copies* removes exactly the copies it listed (`deleteCopies(names)`,
the same names it marks as seen), never "everything in the directory". A safety copy that appeared
after the dialog was built (say, a second replace failed while it was open) is neither deleted nor
marked as answered, and a copy a replace running in this process is using is never listed, swept or deleted
(`OverwriteRecovery.active`). The age-based sweep likewise only touches what has been announced and
answered. The rule generalises: a destructive action takes the explicit, visible list as its
argument, so the thing deleted is the thing the person agreed to.

## 65. `ExifInterface.setAttribute` writes every char above U+007F as `?`

So any raw string handed to it must be 7-bit ASCII. `XmpPacket.serialize` escapes non-ASCII as
numeric references; the verbatim fallback used for an XMP packet the parser could not read did not,
and turned "café" into "caf?". An unparseable XMP packet that holds non-ASCII is now skipped rather
than copied (`copiedXmpValue`). Any new path that passes a string through `setAttribute` checks it
first.

## 66. A docs claim about metadata is per format and per output

"EXIF/XMP preserved" was written for all saves and was false for HEIC: `applySourceMetadata`
returns `SKIPPED_HEIC`, so a HEIC the editor writes is pixels only (`HeifWriter.addExifData` is the
unbuilt fix and needs a device check). The accurate statement is a table: Save a copy keeps EXIF/XMP
and location for JPEG, PNG and WebP; Save As keeps the same minus all location (verified by
`carriesLocation`); Replace keeps them and is refused rather than lossy; HEIC keeps none. The same
care applies to the "Clear location" button in the metadata panel, which deliberately still leaves
place names that Save As strips. When a store text, README line or help string makes a metadata
promise, check it against these.

## 67. Robolectric cannot open a file descriptor on `file://` or an unattached authority

`ShadowContentResolver.openFileDescriptor` returns null for `file://` and throws for a provider
nobody attached, and `ExifInterface(FileDescriptor)` throws under Robolectric because `Os.dup` is
shadowed to null. `app/src/test/.../editor/FileBackedProvider.kt` attaches a real `ContentProvider`
whose `openFile` serves real files in `r` and `wt` modes, and `EditedCopyWriter` reads the metadata
source through `openInputStream` + `ExifInterface(InputStream)` so that path is testable. Tests of
the overwrite and recovery paths use it; do not write another shadow. `MetadataWriter` itself still
opens a descriptor on fabricated media Uris, which is why its write test's KDoc still describes a
sandbox limitation.

## 68. The gallery is a sibling of the viewer, so its `rememberSaveable` state dies with each round trip

`FotoXplorrApp` composes the editor, the viewer and `GalleryScreen` as `if / else if / else`
branches of one slot. A branch that leaves composition takes everything the browser kept in
`rememberSaveable` with it (a registry only saves at `onSaveInstanceState`, not on leaving
composition), so Back from a photo landed on the default destination, Root route, no search, top of
the grid. The gallery's branch is wrapped in `rememberSaveableStateHolder().SaveableStateProvider(GALLERY_STATE_KEY)`
and the holder is remembered above the branches. Anything new that must survive opening a photo has
to be `rememberSaveable` (or a preference) inside `GalleryBrowser`; plain `remember` is rebuilt, which
is right for `mapOpen` and other session-only modes. The `LaunchedEffect(destination, route)` reset in
`GalleryBrowser` used to run on the first composition and clear the restored `query`, `searchVisible`
and `selection`; it now skips that first run (`browserSettled`), so do not add a reset to it, or any
effect keyed on restored state, that is not guarded the same way. `GalleryViewerRoundTripTest` holds
the pair: with the holder the search survives, without it it does not. Rotation is not a loss path
(`configChanges` in the manifest), process death and this round trip are.

## 69. The timeline strip, its gutter and the open-at-the-latest-end anchor each have one source of truth

`stripShouldShow` (`gallery/TimelineLayout.kt`) decides both whether `EdgeTimelineScrubber` is
composed and how much room `timelineReserve` takes from the grid; decide either separately and the
grid and the strip disagree (a gutter with no strip, or a strip over photos). The strip lives in
the grid's own `Box` inside the Column, not in the screen-wide overlay `Box`, because the pinned
`PersistentNavRail` is a Row sibling of that Column: a LEFT strip in the overlay is drawn over the
rail. The reserve is NOT passed to `SpatialShell` as `topReserve`/`bottomReserve` (that would move
the details and actions rooms' pull zones off the edge); the strip's own drag runs along its axis,
the shell's runs first on the Initial pass, so only a strip's two 56dp ends can lose a drag to a side room.
The one exception is the notification shade: it is drawn inside the gutter box, so a TOP strip pushes
it down by the gutter and `shadeTopReserve` adds the same to the shell's `topReserve`, or the top-edge
pull zone would sit over the shade's body and a drag down on the shade would open the details room.

"Latest at the bottom" is Oldest first plus a scroll to the last item, never `reverseLayout`
(a header placed before its group would draw below it and `GridIndexMap` would have to learn a
header-after mode). The scroll is an effect keyed on `anchorToken` (destination, route, sort and
the rail-choice counter), held in `rememberSaveable`: a plain `remember` would re-run after the
viewer round trip (TRAPS #68) and throw away the restored scroll position. It waits for the scan to
finish only when the end matters (Oldest first), because the library arrives in batches and the end
keeps moving. `sortAssets` orders by `dateTakenMillis` but the strip, the headers and the time
range read `timelineMillis` (date taken else modified), so photos with no capture date sort
together at one end and a month can head two runs; header keys carry the run index for that reason
(duplicate lazy keys throw), and `TimelineStopsTest` pins that every stop opens a header.

## 70. The zoom is one grid; the rung is stored when a gesture settles; a lazy grid keeps its place by KEY

The rungs are one ordered list (`adaptive/GridZoom.kt`: 1 to 6 columns, then Stamps) over ONE
`ZoomGrid`, and the pinch detector (`gridZoomGestures`) sits on that grid's root. A rung that drew
something else would have no detector and so no pinch back out of it. Do not add a rung with its
own composable. The live rung is screen state
(`zoomRung`); the stored preference follows only when a pinch settles (`onZoomEnd`), the Ctrl-wheel
pauses for 250 ms, or a button or key steps it, so a pinch does not write the preferences on every
step. The pinch detector reads the pointer count itself and consumes only with two fingers or a
Ctrl wheel tick, so one-finger scroll and tile gestures pass through (the stock transform detector
eats them).

A `LazyGrid` keeps its scroll position across a layout change by the KEY of the first visible item,
and if that key is gone it falls back to the SAME NUMERIC INDEX. A photo key (its id) exists in every
layout; a month header's does not once the headers go (Stamps to columns, or Stamps with months to
columns with days), so the grid landed one item later per header above it, up to ~180 items on 22k
photos. `ZoomGrid` re-anchors in a `SideEffect` (after composition, before the next measure, so
there is no frame at the wrong place) with `reanchoredGridIndex`, only when the old top line was a
header; any other top is left to key retention, which preserves the sub-item offset. The `LayoutAnchor`
it compares with is the BROWSER's (hoisted beside `gridState`), not the grid's: Settings, a destination
screen and the map replace the grid in the browsing `when`, which disposes it, and a copy remembered
inside would be gone when Timeline or Group by changes meanwhile, leaving the old numeric index on a
layout it no longer means. Header keys
carry the run index (a repeated month is a second run, and duplicate lazy keys throw; #69).

## 71. The map is a mode with no Back of its own, and "settled" must include the rail's own motion

`mapOpen` is a flag beside `destination` and `route` (plain `remember`, not saveable: a cold start
lands on the grid and no tile is fetched without a tap; Back from a photo opened on the map lands on
the grid). The embedded map screens (`StampMapScreen`, `RichPhotoMapScreen` with `embedded = true`)
register NO `BackHandler`: a second handler would outrank `paneBackTarget` and the map would eat Back
before a selection or a search could. The Back order is in one place; Escape and the keyboard gates
treat the map as a pane. Likewise the time-range panel: it is on `paneBackTarget` as
`CLOSE_TIME_EDITOR`, keyed on `timeEditorShown(...)` (whether it is DRAWN) and not on the flag that
opens it, because that flag was left set under a selection or a pane and swallowed the next Back.

The Connect build's street map starts a style and tile fetch the moment its view exists, and the rail
commits the word under the finger after a short rest while the finger is still down (#61), so a scrub
resting on Map must not build it. `PhotoMapPaneImpl` is a plain field until `settled`, then swaps once
and keeps the map (a rebuild is a second fetch). `settled` is `mapPaneSettled(atHome, railTurning)`:
`atHome` covers the pull-out room, but the pinned rail on a wide window is a Row sibling of the grid,
so no room opens and `atHome` stays true throughout a scrub. The rail therefore reports `onTurning`
(dragging or its own scroll, settle included) and the browser holds it as `railTurning`. A TAP on the
Map word settles at once, because no turn is in progress at that instant. Anything new that starts
network work from a rail commit has to read the same signal. A `LaunchedEffect` keyed on destination
or route runs on first composition too, so restorable state such as the time range must never be
reset there (#68).

## 72. A strip on the top or bottom, and a sideways grid, share the shell's 56 dp edge zones

The shell takes a horizontal drag that STARTS within 56 dp of the left or right screen edge, on the
Initial pass, before any content sees it. That is accepted, not fixed, in two places: a Top or
Bottom timeline strip (full width, so its two ends sit in those zones), a sideways grid (it cannot
begin a scroll in its first or last 56 dp; a fling there opens a room). A Left or Right strip scrubs
vertically and does not compete with the rooms' horizontal drags at all. The strip is NOT handed to the shell
as `topReserve` / `bottomReserve` (that moves the details and actions rooms' pull zones off the edge;
#69). A vertical drag that starts on a Top or Bottom strip belongs to the details or actions room.
The scroll axis is a third, independent setting (`ScrollAxis`): the sideways grid is a
`LazyHorizontalGrid` fed from the same item DSL as the vertical one and driven by the same
`LazyGridState`, so the scrubber, the pill and `GridIndexMap` need no change; `gridScrollsHorizontally`
is the one decision (Stamps and masonry stay vertical). Two things that look like they work and do
not: `layoutInfo.beforeContentPadding` and `afterContentPadding` cover only the MAIN axis, so a cross
axis `contentPadding` is invisible to them, and lazy item keys here are `Long` for photos, so test
code must not cast a key to `String`. A sideways grid's tile width comes from the fixed row height
(`GRID_TILE_ASPECT` is width over height), and the pill and discs permanently overlay its bottom, so
it keeps an 88 dp band there. `LazyHorizontalGrid` flows right to left under RTL; not handled.

## 73. A hold's meaning is read at gesture time, through the bridge

What a long press does is a setting (`GridLongPress`), and `MediaTile` / `tileGestures` read it from
`GridChromeBridge.gridLongPress` when the hold completes (`LongPressResolver.resolve`), never from a
value captured when the tile composed. The same `pointerInput` is keyed on the bridge instance and
reads `onOpen`, `onToggleSelection`, `onPeek`, `onPeekEnd`, `selectionActive` and the asset through
`rememberUpdatedState`; keyed on `Unit` or the asset alone it captures its first closure for good
(#50), which made a tap after a sort or filter change page the viewer through the OLD list. The
detector is still `detectTapGestures`, so a stamp tile reuses it rather than writing a second one.
The bridge field is `mutableStateOf` only so the TalkBack long-click label recomposes when the
setting changes; the gesture does not depend on that. While a selection runs a hold toggles the tile
under every setting except Nothing; in the Trash, Favourite, Share and Edit resolve to the preview
(`effectiveFor`); no setting offers Trash. The retired `long_press_preview` key is read once as the
default and never written, so a downgrade keeps the old choice.

## 74. The info sections are read as one node per row, copied one row at a time, and counted from the browsable run only

`InfoSections` (drawn inside `InformationBlock` once Show More is open) makes each row ONE accessibility
node with `clearAndSetSemantics { contentDescription = "label, value" }`; the label and the value are
not two stops, and tests find a row by that description (its two `Text`s exist only in the unmerged
tree). The semantics modifier must sit BEFORE the row's vertical padding in the chain: after it, the
node's bounds are the padded middle (36 dp) and a screen reader outlines less than the 48 dp the
finger can hit. A long press copies `MetaRow.copyText` through `pointerInput` + `detectTapGestures`
(not `combinedClickable`, which would add a click action that does nothing and be announced as one),
and the same copy is offered as the node's `onLongClick` and as a `CustomAccessibilityAction`; a
failed-read row offers neither. There is no "copy all details" and must not be one: a block of every
row carries the coordinates out of the app in one gesture (#31). The video read is hoisted in
`ViewerScreen` beside the EXIF read (`VideoDetails.PENDING` until it lands; a still passes null), and
`MetadataSections.nearbyCount` runs over `relatedAssets` -- the run the grid or folder handed to the
viewer, browsable by construction (#33) -- so "Nearby in time" counts photos in that run, not the whole
library; never pass it `libraryAssets`, which is unfiltered. A new row label must not contain "Palette"
(the palette tests assert the old row is gone by that text).

## 75. The encrypted backup is additive, resumes from its own folder, and the passphrase is its only key

`backup/` writes a set of files into a folder the person picked (`SafBackupDestination`, one
`DocumentsContract` child query, not `DocumentFile.listFiles()`): `pack-NNNN.fzbp` (about 1 GiB of
records `u64 id, u64 size, bytes, sha256`), `pack-NNNN.fzbx` (the encrypted index of that pack),
`manifest-NNNN.fzbm` (a whole snapshot). Rules that hold it together:

- **Nothing is overwritten or removed.** `BackupDestination.create` refuses a name that exists; a
  listing that fails throws instead of answering "empty" (an empty answer is a decision to start over);
  a plain `IOException` while reading a manifest is NOT damage, so the loader never falls back to an
  older snapshot and writes a newer one that forgets files (`BackupSetLoader.isDamage`). The only
  deletions are of the file THIS run created and could not finish, and of leftovers the person was
  shown and confirmed. `create` is called before the `try` whose catch deletes, so a name collision
  can never delete the file that was already there.
- **Resume needs no local state.** A pack is written whole, then its index; the index is what makes
  the pack count. No journal on the phone: clearing data, or another phone, loses nothing and no file
  name is written to app storage. A pack with no index, or an index whose pack is gone, is a leftover
  (never reused: its number is reserved so the new pack's index cannot collide with it).
- **A wrong passphrase must stop before a byte is written.** With backup files in the folder, the run
  proves the key (a manifest, an index or a pack's first segment) before it plans; GCM cannot tell a
  wrong passphrase from damage and the copy says so ("wrong passphrase, or the backup is damaged").
- **The passphrase never leaves the dialog.** No `rememberSaveable` anywhere near it (a Bundle can reach
  disk), a `CharArray` that `BackupKeys.derive` wipes, keys closed in the job's `finally`. Notification
  titles and job names carry no file name and no count (`BackupCopy.JOB_TITLE`).
- **A cancelled or failed run still writes a manifest for what it finished**, under `NonCancellable`;
  cancellation is rethrown as cancellation, never as a failure message (#49).
- **The size on the wire is the provider's size now**, not the scan's: a file edited since the scan is
  copied as it is; one whose bytes do not add up to its length is padded to keep the pack well formed,
  reported, and left out of the index so it is tried again.
- **The service type is data sync** for a backup (`JobForegroundService.foregroundTypeFor`), including on
  Android 15, where `onTimeout` stops the notification; the job is not cancelled, and a backup that is
  cut off simply resumes. A partial wake lock keeps the copy going with the screen off.
- A SAF provider may rename a created file (a number, an extension); the destination checks the name it
  got and refuses the folder rather than let the set's names drift.
- **A tree provider's listing is not an answer until it says it is** (`SafListing`). A cloud-backed
  folder answers its first children query with an empty or partial cursor and
  `DocumentsContract.EXTRA_LOADING`; read as "empty folder" it offers "Choose a passphrase" for what is
  an existing set, and the run writes a second set (new salt, next manifest) beside the real one, after
  which the real passphrase is "wrong" and "remove the leftovers" would delete the real files. Loading
  is re-queried with a bounded backoff and then fails (`FolderNotReadyException`); `EXTRA_ERROR` throws.
  Behind it: `BackupSetLoader.load` refuses (`AnotherBackupHere`) when nothing opened and a file has a
  genuine header for other keys; a file of another set, or of a newer format, is `foreignFiles`, never a
  leftover; `LeftoverRemoval` lists and classifies again before it deletes; `create` refuses a document
  id the listing already had (writing "w" to it would truncate it).
- **A manifest's sections are the phone's current stores, so they are carried forward** (`SectionMerge`).
  Without it a run made on a wiped phone writes empty tags, locks and pins over a full backup, and a
  restore then puts protected photos back unlocked. The price of additive: a tag or lock removed since
  the last run comes back at a restore. Photo ids are one phone's row numbers; the tags file and each
  pin and recipe record carry an identity (name, size, date taken, folder), a clashing id is renumbered
  on the previous side, and a restore finds a pin's photo by that identity (a record without one is the
  same phone's, by id).
- **"Backed up" includes whether the GPS was readable.** A copy made without `ACCESS_MEDIA_LOCATION` has
  no `ORIGINAL_LOCATION` flag and is copied again once the location is readable
  (`BackupPlanner` `locationReadable`); a file whose original read falls back every time would be
  copied on every run. The re-copy's manifest no longer lists the old pack, but it stays in the folder
  with a valid index, so every later run reads it back as a recovered record BESIDE the newer one: the
  planner must look at all same-bytes records of a path and take the one with the location (and not
  carry the replaced one), or it re-copies the file forever. Likewise "not listed" proves nothing while
  the newest manifest could not be read, and a pack whose index disagrees about its length may be the
  only copy of the last run: neither is ever offered for deletion (`unverified`, `leftoversRemovable`).
- **The device self-check covers the cipher** (`BackupGoldenVector`): opening the embedded golden file,
  a fresh seal and open, and a flipped bit that must fail, on top of PBKDF2. Keep the embedded bytes
  equal to `BackupFormatTest`'s (a test pins it).

## 76. A mode that takes the lone finger must claim the down, skip the viewer's loop, and never consume a move

Select text reads one finger as a stroke, and a second finger must still pinch. Three rules make that
work, and each was found by thinking through what the stock detectors do. (1) The mode's detector
(`detectTextSelectGestures`) **consumes the down**, because `detectTapGestures` only looks at
unconsumed downs: that alone switches off tap-to-hide-chrome, double-tap zoom and long-press Lift while
the mode is on. (2) It **does not consume moves**: a consumed move makes `detectViewerGestures` break
out of its loop, and `awaitEachGesture` then waits for every finger to lift, so a pinch begun after
a consumed stroke would never zoom. (3) The viewer's loop therefore skips a lone finger itself
(`oneFingerInert`, read live) instead of being pre-empted, and a second finger reaches it intact; the
overlay abandons its stroke the moment a second pointer appears (`onCancel`). Strokes, tap slop and
the lasso-close distance are dp divided by the zoom, because the overlay sits inside the zoom layer.
The arbitration is pinned in `TextSelectOverlayTest` with the real `detectViewerGestures`, but a real
finger on a real screen is still a device check.

## 77. Text actions have one implementation and read the host's callback live, not from a plain var

The details card and the Select text toolbar share `LensTextActions` (`rememberLensTextActions`); each
call passes the text to act on, so "Copy text" stays copy-everything while the toolbar acts on the
selected words only. Three things found while wiring it. (1) The library-search callback is read at
use time through `rememberUpdatedState`. A first version stored it in a plain `var` from a
`SideEffect`: nothing observes a plain var, so the first frame had no Search button (or a disabled
tile) and nothing ever recomposed it. (2) The translator's `getClient` sat outside the `try` in the
connect flavor, so an ML Kit that was not initialised threw out of the coroutine instead of falling
back to the hand-off; creation is now inside the failure path. (3) Making a whole `ActionRow` the
button merges its icon and word into one node, so a test that aimed "left of the label" to hit the old
icon-only target now misses the row; click the word. The source language for Translate and the voice
for Listen come from the recogniser's tag on the selected words (`TextSelection.dominantLanguage`),
with English and the device language as the fallbacks; text already in the device language is not sent
to a translator.

## 78. The Experimental host owns Back and the open id; an experiment owns only its inner steps

The Experimental destination (`HyleDestination.PLACES`, labelled "Experimental"; the constant is
historical and saved as "Open the app on", so only the label changed) is `experimental/ExperimentalScreen`.
It keeps ONE `rememberSaveable` id and ONE `BackHandler(enabled = open != null)`; an experiment calls
`onClose` and registers no Back handler for "close", because two of them (similarity, AI keys) never had
one. An experiment with an inner step registers its own: it is composed after the host's, so it is asked
first, and the host's gets the press after the last step. A shell room (the nav or options room) opened
over an open experiment is closed FIRST: `GalleryBrowser`'s Back handler is `RoomFirstBackHandler`, which
re-registers itself one frame after a room opens so it is the newest callback, whichever experiment and
inner step is open. Only a Robolectric stand-in handler has run this; the real shell's room over the real
browser has no test and nothing has run on a device. The saved id is tagged with a per-process UUID
(`currentProcessId`), and `restorable` applies to PROCESS DEATH only: in the same process (rotation, the
viewer round trip, leaving the destination) every open id comes back, after a process kill only a
`restorable` entry's id does. Without the tag a non-restorable entry would also vanish when the gallery is
parked for the viewer, because that parking runs the same saver. An id this build does not know, or an
entry the device cannot run, opens the hub. Everything inside an experiment is that experiment's to save;
the host saves nothing but the id. The section is composed only while its rail word is the destination, so
leaving it (Settings, the Map word, a rail scrub that rests on another word, a route) would drop the open
id and every `rememberSaveable` inside it: `GalleryScreen` owns one `rememberSaveableStateHolder` and
`DestinationContent` wraps only the PLACES (Experimental) branch in `KeepExperimentalState`, so the open
experiment and its draft are parked and handed back. Anything only `remember`ed is not kept, and the
parked drafts ride in the saved-state Bundle (a very large sketch could approach its size limit; not
measured). The "Experimental" word does not fit the rail at 44 sp (the word measures 274 sp at 44 sp, more
than the row on the 260 dp rail gives it): `DestinationWordRail` measures each row's label and `railFontSpFor` shrinks only
the rows that do not fit, down to a 28 sp floor, so the other words stay at 44 sp and a large font scale
can still ellipsise it. The hub's cards are `Role.Button` with no long-press action, and the hub, privacy
and group titles are headings. The host also provides
`LocalContentColor` (#62) for every screen it opens. A full-screen area that takes drags
(a drawing board, a wall editor) goes inside `Modifier.clearOfShellEdges()` (#72), which keeps it
`ShellEdges.INSET_DP` (the shell's private 56 dp zone plus 4) from each side and clear of the system bars;
`ShellEdgesTest` reads the shell's source (the relative path `../shared-libraries/...` from `app/` must
exist) and fails if its `EDGE_DP` changes, or if the source or the declaration cannot be found (it no
longer skips). Photos come only from
`ExperimentEnv.assets` (the browsable list, #33) and pixels only through `ExperimentEnv.decode`, which asks
`mayExport` again, so a folder locked again while the experiment is open stops the next read. The location
index starts from the compass entry when it opens with nothing scanned, and from Map mode (`PhotoMapPane`);
nowhere else.

A camera screen, a permission prompt, or anything that starts work on open is registered with
`restorable = false`: after process death the host then shows the hub, never that screen with no
permission and no work behind it. The compass is the built-in example (a location read on open, a
permission in context); `ExperimentalHostTest` pins it as the only one. Registration is one `ExperimentEntry` appended to
`builtInExperiments`; its id is saved state, so it is never renamed and never reused (`"wall"` is the
3D photo wall, the room mock-up is `"wall-mockup"`). `ExperimentalHostTest` pins the id list on purpose.

## 79. The wall and aquarium take drags at the edge: inset the touch layer only, and say what is lost

The drawing board (aquarium) and the wall editor (wall mock-up) wrap only their TOUCH area in
`Modifier.clearOfShellEdges()` (#72, #78): the wall photo is still drawn across the full area, and a
frame wholly inside the 60 dp zone cannot be grabbed by touch and is moved with the buttons. For the wall
this inset applies to the touch layer in the HANG step only: in the SCALE step the tap layer covers the
whole canvas (a tap is no drag), so a reference end at the photo's border can be marked; whether the
shell still reads a tap in its edge zone there is a device check. The wall
screen applies `statusBarsPadding` and `navigationBarsPadding` itself so the helper does not add a second
system-bar inset. The tank (taps and holds, not drags) is deliberately NOT inset: a tap at the screen
edge still feeds the fish, and a drag that starts in the zone opening a room is the shell doing its job.
The inset costs a 360 dp phone 120 dp of width; a vertical scroller can still lose a drag that starts in
the bottom 56 dp to the actions room (as the timeline strip does). The owner may prefer "ignore only the
first touch inside the zone" (`inShellEdgeZone`) over an inset: that is a decision, not a bug.

## 80. The aquarium's simulation is single-threaded state on one frame loop; the renderer is a seam

`TankSimulation`, `Creature` and `FoodPellet` are mutable and are read by the draw lambda: call `step()`
and draw from the same `withFrameNanos` loop on the main thread, never from `Dispatchers.Default`. The
loop runs only while the lifecycle is RESUMED and stops on ON_STOP, so a locked app does not animate
behind the lock; the first step after a resume is 0 s, so fish do not jump. `step(dt)` clamps dt to 0.05 s
and integrates once: runs are deterministic for a dt sequence but NOT frame-rate independent; assert no such
thing. The mesh is the identity grid at `waveScale` 0 (paused and reduce-motion), not at phase 0, because a
travelling sine is never flat. Drawing goes through the `CreatureRenderer` interface; the only
implementation is `Canvas.drawBitmapMesh`, and the fallback (per-segment `drawBitmap`, or `drawVertices`
with a bitmap shader) is NOT written. A Compose test that shows the tank unpaused never goes idle (the
loop asks for a frame forever): set `mainClock.autoAdvance = false` and drive the clock, or open it paused
with `Settings.Global.ANIMATOR_DURATION_SCALE` 0. When the tank canvas is first measured `setAspect`
clamps creatures into the real, narrower tank, so a test that records a position waits for layout first.

## 81. A draft goes through a bounded string, and a restored draft is reconciled, not trusted

`rememberSaveable` lands in the activity's size-limited saved-state Bundle. The aquarium draft is saved as
a quantised string, thinned to 300 points a stroke and 6000 in all (about 40 KB at most; the redo stack,
a half-drawn stroke and the open list are not saved); the wall's draft is ONE versioned string (step,
wall id, calibration, distance, layout, picture ids, frames, style, mat, units). Decoded bitmaps, the
two taps of a reference in progress and typed-but-unsent text are not saved. A restored wall draft is
reconciled against `env.assets`: a lost wall photo returns to step 1, lost pictures to the pictures step;
an EMPTY list is "still loading" and never wipes the draft. Creatures live in `filesDir/aquarium` as
a PNG then a JSON written LAST (a PNG without its JSON is ignored and swept), with UUID-only ids so a
file name cannot walk out of the folder; a record from a newer version is read for the fields known.
`SpriteIo.loadAndSweep` decodes every listed pair on open and deletes the ones that are not an image, are
over 1024 px or are not the size their record says, so a pair that lists but will not decode neither
counts toward the 12 nor traps the person (a decode that fails only with `OutOfMemoryError` is skipped
for that run, the files kept). Robolectric's decoder refuses a PNG cut off after its header; a device's
may return a partial or blank bitmap, which is then kept and deletable: unverified. While Swim saves,
the board gesture is not installed and the chips are disabled, and `draftAfterSwim` clears the draft only
if its sketch is the one that was saved. A ready shape is added whole or not at all
(`ReadyShapes.fits`), mirrored for "Head faces left".

## 82. A print file is a new file with the place removed, and `ExifCopier` carries the source's resolution

`ExifCopier` copies the source file's own `XResolution`, `YResolution` and `ResolutionUnit` (they are not
in `EXCLUDED_TAGS`; a test pins it), so the density the print needs must be written AFTER `ExifCopier.copy`,
then the JFIF density last (`JfifDensity`, which patches only bytes 13..17 of the APP0, or inserts one;
`stampFile` and `readDpi(file)` walk the segments by seeking and stop at SOS, patch in place or stream
into a `.part` file, so the print is never read whole into memory). The file lands in `Pictures/Fotoz/Prints`
on Android 10 and later (below that `RELATIVE_PATH` is not set and it is in the default Pictures folder,
so the saved sentence names no folder). A cancel that arrives during or after `publish` returns the
saved file, because `withContext` would drop a result when the caller was cancelled; one that arrives
before it publishes nothing.
androidx `ExifInterface.saveAttributes` puts the Exif APP1 BEFORE Skia's JFIF APP0, so JFIF is not first
after SOI; libjpeg-based readers do not mind, a strict sniffer may call it Exif. Both densities are
written and equal; `PrintExporter` reads them back and refuses to publish otherwise. GPS and XMP location
are removed with `ExifCopier(keepLocation = false)` and PROVED with `carriesLocation` before publishing
(camera make, serials, owner and date stay: an owner decision). `EditedCopyWriter` is untouched: its
encoder is private, copies the place and writes no density. A protected or hidden photo is checked three
times: `mayExport` at the start, inside `env.decode`, and again immediately before `publish`; the system
print path checks on every `onWrite` and closes the adapter when the experiment is left. Wide gamut: the
bitmap is drawn onto an opaque white sRGB bitmap, which converts Display-P3; only a flat colour is proven
in tests. `android.print` + `PdfDocument` need no dependency, but Robolectric has no PDF writer, so the
adapter callbacks are untested (the page maths is pure). The system print page keeps a picture at real
size only if it fits inside the paper less the margins the dialog reports (`PrintAttributes.minMargins`,
0 on borderless photo paper; 1 cm only when it reports none): A4 on A4 is shrunk inside the printer's
edge. What a real dialog reports for a given printer is unverified.

## 83. No camera intent: `ACTION_IMAGE_CAPTURE` is not used, and declaring CAMERA later would break it

The wall mock-up takes a photo that is ALREADY in the library; it neither launches `ACTION_IMAGE_CAPTURE`
nor declares `CAMERA`. Do not add either casually: an app whose manifest declares `CAMERA` without holding
the grant makes `ACTION_IMAGE_CAPTURE` throw a `SecurityException`, so a later live-camera feature must
bring the permission flow with it (and `restorable = false`, #78) instead of mixing the two. Nothing
about this was tried on a device.

## 84. Enlarging is banded, honest, and tied to the editor's mask by a two-row halo

`lanczosResample` streams rows through `PixelRows` and `RowSink` (a 64 MP result as ints is 256 MB), so
nothing holds a whole picture as an `IntArray`. The editor's `applyUnsharpMask` has a private radius of 1
and full-image scratch buffers, so it runs per band with a halo of `LanczosBand.HALO_ROWS` = 2; if the
editor's radius ever exceeds 2, the test that pins band-wise equals whole-picture fails and the halo must
grow. The caps (8192 px an edge, 64 MP) are checked with Long arithmetic before allocating and the plan
is fuzzed on absurd sizes. Lanczos adds pixels, not detail: the chip, the notice and the KDoc say
"Enlarged, no new detail", and the file keeps the honest density. `Bitmap.getPixels` converts to sRGB
(and clips), so a wide-gamut photo is converted on read.

## 85. The wall's scale is a rectilinear estimate, and "size" means the picture

From a typed distance the wall width uses the image's SHARE of the 43.27 mm full-frame diagonal, not
`2*atan(12/f35)` (which assumes 36 x 24 and under-reads a 4:3 phone portrait by about 8 percent; 185 vs
200 cm at 2 m, test pins 199.7). Both ignore lens distortion and a wall that is not square to the camera;
a reference measured off the wall's plane is wrong by the perspective. A frame's size is the PRINT: mat
and moulding are drawn outside it and are in neither the file nor the dpi. A photo's real pixels come from
`DecodedBitmap.sourceWidth/Height`, never `MediaAsset.width/height` (#48). "Hang them" hangs a landscape
photo lying down, each picture by its own shape (a mixed set is laid out in square cells); the chosen
pictures are decoded as soon as they are chosen to know that, and until a decode finishes the library's
listed size is used, which can be the un-rotated grid for an EXIF-rotated photo. A plain tap on a frame
changes nothing: the snap to a standard size and level runs only after a gesture that moved, sized or
turned it. A typed distance that gives no usable width zeroes the wall width ("Set the scale to go on.")
and a different wall photo resets the distance; the Hang step is still reachable with frames hung and a
zero width, and the Move buttons are not guarded there (known, unfixed).

## 86. A map download is its own job kind, and the network library's HTTP hook is global

`ActivityKind.DOWNLOADING_MAP` needs its own foreground-service type mapping: `JobForegroundService`
treats `DOWNLOADING_MAP` (like `BACKING_UP`) as data sync on every version (Android 15 and later give every other
kind media processing, and a mix asks for both), and `JobForegroundTypeTest` pins it; a new job kind that fetches
or copies bytes is added to `movesBytes()` or it is declared as media processing on Android 15. `JobRunner` treats a job that returns a
non-blank String as "<title> finished." and a thrown `CancellationException` as silent, so a download that is
cancelled must pause and RETHROW; one the person ended returns a blank String. The map library's
`HttpRequestUtil.setOkHttpClient` is process-global: `MapRequestCap` installs the 4-request cap only while a
region downloads and restores the library's default after the last one has STOPPED, or live panning stays
throttled. `setActive(false)` is asynchronous, so the engine marks a region as stopping on pause, delete
or finish, and takes the cap off only when every running or stopping region has reported itself stopped,
or 5 s after it was told to stop (`SETTLE_MILLIS`, a guess); a resume of a stopping region supersedes the
old stop. A stopped region's readings are dropped by the library unless `setDeliverInactiveMessages(true)`
is set, which `MapLibreOfflineBackend` does while it has a listener (read from bytecode, not run). Pause
finds the running job by the area's region id, not its title (a process-wide map from region id to job
id), and a tap on Download or Resume with no connection says so before anything is created. The cap is a hard precondition: the engine probes it once (install then restore, no request) and
turns `throttleInstalled` off for the process if installing ever fails, which disables the button.
`create` and `list` fetch nothing and `resume` is the only call that may; a late progress reading after a
pause is `Rejected` by `DownloadGuard`, so the glue stops the engine on any move into Paused or Failed.
Cancel is pause: nothing deletes except `engine.delete` on the person's request, naming the area (#64).

## 87. Offline-map suggestions use the browsable list; the on-screen area is a flat view

`GeoIndexState.browsablePoints(ids)` takes explicit ids from `browsableAssets` and refuses to read the whole
geo index (#33): a locked photo's place can never become a suggested area. The map library's visible-region
bounds on the pitched Map mode span hundreds of kilometres, so "what is on screen" (the pill) is
`ViewArea.boxOf` from the camera centre, zoom, size and bearing, as a flat view (the bounding box of
the rotated screen, width*|cos|+height*|sin| across; pitch is ignored). The tile maths follows slippy xyz
at the same zoom (512 px tiles, assumed identical to the library's cover, not verified); a box over the date
line is refused (`ANTIMERIDIAN_VERIFIED = false`). `RegionBudget`'s byte table and overhead are guesses
until a device spike replaces them. The source scan (#43) still applies: the Offline build's pane is a stub
with the same FQN and signature, all library, HTTP and connectivity code is in `src/connect` and
`feature/ai-remote` (a compile-only map-library line, ADR-015), and the package's KDoc says "network" and
"map library" only. Tests for Connect-only code live in `src/testConnect`. A new optional callback on
`JobRunner.launch`-style helpers goes BEFORE the trailing lambda or every trailing-lambda caller breaks.

## 88. Robolectric-native compose tests that decode on a worker can abort a fresh test JVM

When such a test (wall, export) is the first thing in a fresh JVM the process can exit 134 with
"Class not found: java/nio/FloatBuffer" and no result file: the first native image decode on an IO worker
races the main thread. `NativeWarmUp.warmUpNativeImageDecoder()` in `@Before` fixes the two wall classes
that hit it; any new test that decodes on a worker (aquarium, panorama) needs the same line. Read test
results inside the same `flock` hold: other agents' runs overwrite the shared results directory.

## 89. Panorama pixels come from `decodeUpright`, and protection is asked four times

Everything the panorama reads goes through `ExperimentEnv.decode` (`decodeUpright` behind the live `mayExport`
check, #33 and #34); the focal length is read from EXIF directly, only for a photo that may be exported, and
not through the location reader (focal length is not a place, and the redacted view keeps it).
`PanoramaJob` asks `mayExport` (a) for every photo before any pixel or EXIF is read, (b) inside every decode
(a null there becomes "Photo N is no longer available." if `mayExport` is now false, because the host cannot
tell a lock from a deletion and the sentence blames neither, else "Photo N could not be read."), (c) once for
all photos right after the last draw, and (d) in the writer right before the MediaStore row is created. The
start check (a), the final look (c) and the writer's check (d) still give the protected sentence. Nothing is staged anywhere another app can see before (d); the staging JPEG is in app cache. A new
`PanoSource` or `PanoramaHost` must keep (b): a photo protected while the job runs has to abort it, not leak
into the output. `PanoSource.decode` is blocking and the host's is suspending (`runBlocking` on the
compositor's IO thread), so a cancel cannot interrupt a decode that is in flight; it finishes, then the next
`shouldStop` poll ends the job. The output has no EXIF at all (`Bitmap.compress` writes none): no GPS, make,
model or date inside the file; the date lives only in the MediaStore `DATE_TAKEN`, re-asserted after
`IS_PENDING` is cleared.

## 90. The panorama's size is the pure `plan()`, never the width of the sweep

`PanoGeometry.plan` is the only place that decides the canvas: 24 MP by default, clamped to the editor's
`MAX_EXPORT_PIXELS` (64 MP) and `MAX_EXPORT_EDGE` (8192, both referenced directly), never enlarged,
Long arithmetic, and the largest width that fits so a smaller budget never gives a larger scale. A wide sweep
is therefore held by the 8192 edge, not the 24 MP budget (a 12-photo sweep at 35 percent overlap is about
8192x745; eight 12 MP photos at 35 percent is about 8192x1271, 10 MP), and `Plan.limit` says which ceiling
bit; the screen's "up to 24 megapixels" line is the budget and the "About W by H" line after Preview is the
real figure. Photos are decoded at the canvas scale (`PanoCompositor.decodeSize`), not their own; the Android
source must resize to that size or return something close (the compositor scales its sampling to the size it
gets, but more than 2x the request breaks the memory plan). `OutOfMemoryError` is not caught inside
`compose`: the job takes the next `PanoGeometry.ladder` rung on a NEW canvas (a pixel's validity is its
alpha, so a dirty canvas from an aborted attempt reads as already drawn). An out-of-memory error during the
small reads in phase 1 is not stepped down. The crop is in the pixels of the canvas it was found on: if the
output is stepped down after the crop was found, recompute it, do not scale it up. The cropped copy at publish
is a second bitmap, so the peak is about two canvases of native memory (arithmetic, not measured). Only the
canvas is native: each decoded source is an `IntArray` on the Java heap (4 bytes per pixel, two alive at a
time), and `PanoramaJob.startRung` picks the first ladder rung whose two sources fit 40 percent of
`Runtime.maxMemory()` (`HEAP_SHARE`, a guess, unmeasured; the manifest has no `largeHeap`). The compositor
also keeps two band buffers per worker, allocated once at the widest strip times the band (about 16 MB for a
typical sweep, 64 MB at worst, arithmetic); `PanoCompositor.scratchBytes` is counted in
`PanoramaJob.sourceHeapBytes` so `startRung` sees it.

## 91. The offline source scan reads panorama comments too

`:app:verifyOfflineSourceReferences` scans `src/main` and `src/offline` `*.kt` line by line, comments
included (#43). The pano package is in `src/main`, so a KDoc that names a banned class or call, even to say
the code does not use it, fails the offline build. Write "network" or "the web", never the class names. The
tests are not scanned, but keep them clean as well. `PanoramaWriter` and the host use only MediaStore,
`ExifInterface` and bitmap classes; there is no network code in the package, no dependency, no native code and
no manifest change.

## 92. The aligner returns `confident = false` instead of a wrong offset; the UI must say which photo

`PairAligner.align` always returns an `Offset`; trust only `.confident`. Confidence needs score >= 0.55 AND
overlap >= 15 percent of the smaller image AND >= 4096 overlap pixels AND no distinct rival slide (more than 4
px away) within 0.05 of the best score; a true overlap under 15 percent is found but refused, so tell people to
overlap by at least a third. The rival-slide rule is what refuses fences, stripes, gradients and window grids
(it also refuses a small smooth patch whose peak is wide), and that is deliberate. The rival set includes a
local rescan within 48 px of the answer (every slide scored at half size, the best 4 local peaks refined at
full size), so a texture laid over real content is refused as well as a pure pattern (without the rescan, 23
of 56 periodic-over-content cases were confidently one or more periods out; with a 28 px rescan 2 of 108
coarse-period cases still were; at 48 px 0 of 108 were). A wide peak is a separate check: slides 6 to 8 px from
the best that score within `Params.shoulderMargin` (0.01) make the answer ambiguous. It refuses every
faint-cloud sky tried (8 and 14 grey levels of spread) and lost 3 of 48 correct horizon pairs, so a blurry or
smooth real photo may be refused when it would have stitched; loosen it after the real sweeps. Texture
periods beyond about 48 working px (about 160 px on a 4000 px photo) are not covered by the rescan (the walk
down is trusted there; untested above 60 px). "Never a confident wrong answer" is measured on procedural
scenes only. The false-positive scan (unrelated scenes, 0 to 10 percent overlap, flat sky, 16 seeds) lives in
`PairAlignerConfidenceTest`, and the periodic-over-content and faint-cloud scenes in `PairAlignerTextureTest`: do
lower `minScore` or `minConfidentOverlap` without re-running it; the margin is thin (false-positive maximum
0.48, true-positive minimum 0.61). The offset convention is "the SECOND image's top-left in the FIRST image's
grid" (a left-to-right sweep has dx > 0); offsets are at the aligner's ~1200 px working size, in
cylinder-warped space, and are scaled to native with `Layout.scaledPositions(nativeWidth / workingWidth)`
once, not per pair. `PanoLayout` uses only offsets inside the chosen run, so a broken pair's guess never
leaks in. `cannotJoin` is the photo AFTER the first broken pair (0-based); `isolated` (matches neither
neighbour) is the only case with a single culprit, so for a break between two good photos the screen says
"Photo N doesn't overlap enough." (the pair), and offers Remove. The job requires the whole selection to join,
in the order given, and does not drop or sort photos.

## 93. The panorama's real-photo quality is unproven, and its worst failures are silent

Every figure for the panorama comes from procedural scenes (1/f noise, hard edges, gradients, with gain, bias,
noise, vignetting, roll, shear, jitter, a moving blob and a parallax layer). No real photograph has gone
through any of it and nothing has run on a device. Measured on those scenes, and NOT detected by the app
(every pair stays "confident"): roll (0.5 degrees costs 2 to 4 px of working-size error, 1 degree 5 to 8),
parallax (the aligner locks onto the near layer and its error equals the layer shift; the far scene ghosts),
a focal-length tag wrong by about 10 percent (13 to 15 grey levels of seam error, no note), and an additive
exposure offset (a scalar gain leaves 10 to 26 percent of a step). Detected: a photo with no tag
(`LENS_GUESSED` note; an untagged photo now borrows the median tag of the others, so it no longer makes the
sweep `MIXED`, while two tagged photos on different lenses or sizes still do), a photo not within 2 percent of
4:3 or 3:2 (`CROPPED` note: the tag is relative to the lens's whole frame, so the pixel focal length is too
small and lines may bend; noted, not corrected, unmeasured on a real photo) and a big mover in the overlap (the join is refused, which is the right direction to
fail; a mover over about a fifth of a 25 percent overlap loses 4 of 18 pairs). Do not describe the panorama
in a store text, help line or release note as dependable. The kill criterion's second half stands: three real
handheld sweeps (still and turning; one with a walking person; one indoors) compared with the phone's own
panorama mode (`docs/device-test/batch-7-checklist.md`).

## 94. Two cylinder warps agree to rounding, and nothing shares their code

The aligner's grey copy (`PanoramaJob.warpToGrey`) and the compositor's column maths are two
implementations of one convention: pixel centres at half-integers, the warped image of width
`warpedSize(w,h,f).width` centred at Wc/2 and h/2, column u = (i + 0.5) - Wc/2, plane x = f tan(u/f), row
stretch 1/cos(u/f), bilinear in 1/256 px, the photo's rectangle on the canvas is [pos, pos + Wc) x [pos.y,
pos.y + h). A half-pixel disagreement shifts every seam. `PanoramaWarpTest` pins them equal (mean <= 0.4,
max <= 2 grey levels; shifting the offset by half a pixel fails it); change one and run it. The warp must use
the same `f`, scaled to the working size, for the grey copy. The warp truncating instead of rounding the
sample position once gave 1-level errors at integer positions; the compositor rounds. The job also requires
one camera: all photos the same upright size within 1 percent and focal/diagonal within 5 percent of the
first (27 vs 28 mm is accepted), else `MIXED`, because positions share one cylinder unit.

## 95. Exposure is one luma multiplier per photo, solved from block medians

`ExposureGain` measures the median of 32 px block ratios over each overlap (a plain ratio of means was 26.6
percent wrong with a figure over a quarter of the overlap; the median was 0.03 percent), drops pixels <= 4 or
>= 251 from both images, needs >= 6 usable blocks, and solves for gains with mean 1 limited to 0.5 to 2. The
returned gain is the multiplier to APPLY to every channel (a brighter photo gets the smaller gain);
white balance is not corrected. `clamped = true` means the seam will show, not that it was solved. Pure gain
changes are matched to under 0.1 percent; gain plus bias, vignetting and a mover over most of the overlap are
not (measured 10 to 26 percent leftover step at +-10 to 25 grey of bias). `measure` indexes both images
directly and trusts the offsets it is given; `measureAll` guards them. Measure on the cylinder-warped working
grey, with `layout.positions` in the same unit.

## 96. Blend and crop contracts: binary alpha, two photos at a time, a full-canvas mask

`SeamBlend.blendBand` blends exactly two bands, the first on the left, with a cosine feather centred on the
overlap middle (clamped to the overlap width, so a thin overlap becomes a hard cut). Alpha is validity: 0 = no
data, anything else = data, partial alpha is not blended, and a pixel with data in one photo only takes that
photo. Three photos in the same columns are blended in sequence into the running canvas, so the result
depends on the draw order (left to right by position, ties by index). Blending is in encoded sRGB values, not
linear light. A feather spreads misalignment into a ghost, it does not fix it (a 2 px error scored the same as
a hard cut). `AutoCrop.cropRect` is hole-free by construction (a coarse cell counts only if every pixel is
valid, then tightened, then verified at full size) but not guaranteed optimal; it needs a full-canvas
`BooleanArray` (24 MB at 24 MP) whose size is checked as a Long. The compositor's result does not depend on
band height, worker count or list order (tested bit for bit); keep it so.

## 97. Panorama lifecycle: what survives, what stops, and one latent `JobRunner` race

Process death keeps the screen's step, chosen photos and order (`PanoramaDraft.encode`, restorable entry) and
nothing else: no preview, no running job. A half-written MediaStore row is `IS_PENDING` on Android 10 and later
(hidden, discarded later); on API 26 to 28 there is no `IS_PENDING` and no `RELATIVE_PATH`, so the row is
visible while the bytes are copied, the file lands in the root of Pictures, and a kill mid-copy leaves a
truncated JPEG that nothing removes (in practice API 28 only, since `decodeUpright` returns null on 26 and
27). A leftover staging JPEG in `cache/panorama-staging` is deleted by the next publish once older than
an hour. Any failure or cancel during the copy deletes the pending row, and a cancel that arrives after the row was
made visible also deletes it (`publish` removes the uri it had made), so "Cancelled." never has a file
behind it; a failed write on a phone with under 8 MiB free (or an ENOSPC in the cause chain) is its own
sentence, "There is not enough space on the phone."; nothing is created before the crop is
encoded, the job's look has passed and the coroutine is active. A save is cancelled when the screen leaves
composition (as the wall's print is), because its host reads the live browsable list of `ExperimentalScreen`
(#33); rotation does not (the activity handles configChanges), and Back inside the screen does not.
`JobRunner.launch` adds the job to its list after `scope.launch` returns, so a body that can finish on another
thread before `launch` returns leaves a stale entry for ever; `PanoramaJob.run` always suspends first
(`withContext(Default)`) and the view-model scope is `Main.immediate`, so it is safe, and tests use
`Dispatchers.Unconfined`. The app-wide outcome collector also posts "Joining a panorama finished." for a
successful job whose value is not a String, besides the screen's own strip (nothing silenced it). API 26 to 28
inserts without `RELATIVE_PATH` or `IS_PENDING`, so the file lands in the default Pictures folder and is
visible while copied (unverified). On the screen: Save reads the runner's live job list, so a second tap
before a redraw cannot start a second save; the first Back that would leave while a save runs is held (the
next one leaves and cancels); outcomes from a job this screen instance did not start are ignored; a failure
is shown only while the photos are the ones it was made for. The gallery
selection action "Stitch panorama" was not built (about 80 lines over five files, two of them hot); the
Experimental entry is the only way in, and the seam, if wanted, is a one-shot launch request carrying the
chosen ids.

## 98. Panorama test hygiene

Tests that use `SyntheticPanoScene.rig()` cache scenes (10 to 20 MB each) in a static map and must clear it in
`@AfterClass`. Tests that decode on a worker call `NativeWarmUp.warmUpNativeImageDecoder()` (#88). The job
tests run the real aligner and compositor (about 1.3 minutes a class); two ordering tests depend on the first
pass doing exactly one decode per photo and the compose pass reading left to right in index order. The "only
two photos alive" test relies on a collection within about 400 ms of `System.gc()`; it failed when the
compositor was made to retain photos (so it can fail) and has not false-failed, but a pathological GC could
make it flaky. `PANO-*` lines in the test output (`PANO-LIMIT`, `PANO-BLEND`, `PANO-CROP`, `PANO-COMPOSE`,
`PANO-TIME`, `PANO-JOB-MEASURE`) are the measurements the docs quote. Roborazzi runs in record mode, so no pano
screenshot test proves how anything looks. The writer tests use a recording MediaStore stand-in that cannot
refuse to open a file (the copy failure is simulated with a read-only descriptor) and Robolectric's JPEG
encoder, which differs from a device's in size, not dimensions.

*Entries 99 onward were earned in batch 8 (28 Oct 2026): colour vision, the editor hub, the palette builder,
animated pictures, reels, the mood board and Chroma ID. They were written by the units that hit them and gathered
here by the integrator, so a few say "I" in the sense of the unit. Nothing in this batch has run on a device.*

## 99. The Machado matrices are published numbers we did not read from the authors, and they are not idempotent

`vision/MachadoMatrices.kt` holds the 33 severity matrices extracted programmatically from the colorspacious and
DaltonLens copies of the authors' supplementary table (identical to the last digit; every row sums to 1 within 2e-6).
The authors' own page was unreachable from the sandbox, so the table was never compared with it directly; the KDoc
says so. Severity 0 is not in the table and is implemented as the identity. Protan and deutan matrices are rank 2;
**tritan is not** (smallest singular value 0.156) and is not monotone by L1 distance between steps 3 and 4. So
`Simulation.simulate` is not idempotent (mean dE on a colour grid: protan 3.8, deutan 1.8, tritan 10.3), and the
tritan "confusion axis" used by the plates is only the least-flattened direction (residue below 0.2, not zero). Do
not write a test that assumes "simulate twice changes nothing"; the tests pin the real figures.

## 100. The GPU colour matrix is a preview, and anything saved or shared takes the exact per-pixel path

An `android.graphics.ColorMatrix` has no transfer curve, so `GpuFilter` applies the 3x3 to encoded values. Measured
against the exact linear-light result: simulate is off by up to 83 to 89 steps over all colours and 27 to 33 over
muted ones; daltonize by up to 111 to 117 and 91 to 103. A least-squares refit in the encoded domain was tried and
was no better in the worst case. Rule: `GpuFilter.simulate` is the live preview only, `GpuFilter.daltonize` is a rough
hint, and the viewer's exact picture, the names, the patterns and the corrected copy all use `Simulation.simulateAll` and
`Daltonizer.daltonizeAll`. The viewer shows the preview first and swaps in the exact picture a moment later; a visible
shift at the swap on dark or saturated photos is expected and is a device check. Daltonize makes confusable colours
distinguishable; it does not show a colour-blind person normal vision (the KDoc says so).

## 101. Colour vision in the viewer: everything computed is tagged with the photo it was computed for

After a swipe the viewer clears its holder one frame late (a `LaunchedEffect`), so the first frame of the new photo
would draw the previous photo's filtered bitmap and a tap would read the previous photo's pixels. Everything computed
(filtered bitmap, colour index, hatch layer, the pixel read for a name) carries a `PhotoKey` (id plus modified time);
`ColourVisionPhoto`, `nameAt` and the hatch layer ignore it until the key matches. The viewer's tap detector is keyed
on the asset and captures its first closure (#50), so the new `onTap` reads one remembered holder, the
delegated-state geometry at call time and the key through `rememberUpdatedState`. A tap with names on is consumed
only when it hit the picture and the pixels are read; otherwise it is the ordinary chrome toggle, so the black margin
always brings the bars back (#62, #76, #78). No drag was added to the viewer, and the name label, chip and notice stay
`ShellEdges.INSET_DP` from every edge (#72). Compose 1.8's `GraphicsLayerScope` here has no `colorFilter`, so the live
preview is an offscreen `saveLayer` with a `Paint` colour filter in `colourMatrixLayer`, not an edit of `MediaImage`.
Video is not supported (`VideoView` is surface-backed, a Compose filter would not apply); the room says so in one sentence.

## 102. `JobRunner.launch` adds the job to its list after the job may already have finished

Already latent in #97; batch 8 hit it in five places. `launch` calls `scope.launch` and only then puts the
`RunningJob` on its list, so a body that completes on another thread before `launch` returns leaves a stale entry
that shows as running for ever. It cannot happen in the app (the runner lives on the main-thread view-model scope and
every job suspends first). It happens in tests that give the runner a multi-threaded or even `Main.immediate` scope
under Robolectric (a scratch test of 2000 do-nothing jobs left 1882 stale entries), as a one-in-three flake in
"photo locked before Save" on the animate screen, a ghost job in the corrected-copy job tests, and a "Making" state that
never cleared in the reel tests. Workarounds used in tests: run the runner in the `runBlocking` scope, give a fast job's
fake sink a 40 ms wait, or give the runner `SupervisorJob() + Dispatchers.Main.immediate` to match the app. **The real
fix is three lines in `JobRunner.launch`** (`scope.launch(start = CoroutineStart.LAZY)`, add to the list, then
`job.start()`); `jobs/` was not in any unit's paths, so it was not made. A test that cancels a job and starts it in one
step has the same shape: assign the `Job` before it can run (start LAZY, assign, start).

## 103. `EditedCopyWriter.save` swallows cancellation and keeps the place

It wraps its work in `runCatching` around `withContext`, so a cancel that arrives during the write comes back as an
ordinary failed `Result` after the file exists. The corrected-copy job (vision) and the recolour job (palette
builder) therefore run the writer inside `NonCancellable` and discard the new row themselves when the cancel
arrived meanwhile, so "Cancelled." never has a copy behind it. A test fake that cancels inside `save` will never see a
discard: the fake must be uncancellable too and the test must wait for the caller job's `isCancelled` before it
releases the write, or the job simply completes first. The writer also has one fixed marker (`-edited.jpg`) and copies
the source's EXIF/XMP **including the place**, as an editor copy in the library does. So the colour-vision corrected
copy and the palette recolour copy both carry GPS; they are not the stripped `Save to...` export. This is on the
owner list (a `keepLocation` switch on `save()` is a small change in `editor/`).

## 104. The editor hub: the catalogue is tied to `EditorScreen.kt` by tests, and a new control must call `hub.applied()`

`EditTool` (the old tab enum) is private and was not changed; `ToolCatalog` carries each tool's `hostTab` as the enum
entry's name, and `ToolCatalogTest` scans `EditorScreen.kt` as text (same technique as `HelpCopyTest`) for the tab
names, every `LabelledSlider` label and every catalogue title as a literal, and checks that every `Adjustments` and
`EditRecipe` property is claimed by exactly one tool. Rename a tab or a label and the test fails until the catalogue
follows; the scan guards itself (it asserts it found at least 9 tabs and 15 labels). A tool shows only its own control
now (`shows(ToolIds.X)` around each existing control); Vignette is hosted by the Detail panel but filed under Effects;
Reset all and Revert to original are catalogue *actions*, never centre-eligible, and now take two taps. **A new tool
control added later must call `hub.applied()` where it changes the recipe, or it will never be learned**; Undo, Redo
and the auto-fix chips deliberately do not count. `HubModel.view` never recomputes the centre; only `settle`, `closeTool`,
`showGroup` and back-from-tool do, and `settle` runs when a *new* `HubSession` is made (editor entry), not when one is
restored, so a restored session cannot move the centre under an open tool.

## 105. The editor hub is outside the shell's edge zones, and its Back order depends on composition order

The editor is a full-screen branch of `FotoXplorrActivity` (`editingAsset != null`) that replaces the gallery, so the
shell and its 56 dp zones are not composed under it (#72), and the hub is tap-only anyway; the ring is not wrapped in
`clearOfShellEdges` (it would cost 120 dp of a 360 dp ring). If the editor is ever hosted inside the shell, revisit.
The hub's `BackHandler` is composed inside `EditorScreen` after the activity's `BackHandler { closeEditor() }`, so it is
asked first, and it is enabled only while `HubModel.back(state) != null`; with the Save sheet or the Continue-editing
dialog open the back press must still be theirs. A hub session restored after rotation or process death re-reads the
memory from `SharedPreferences` and the page from the saved Bundle; the recipe and the crop aspect/heal radius are not in
the hub's saved state (the existing Continue-editing dialog offers the recipe back).

## 106. `ContrastMatrix` takes places in the palette, not colours

`ContrastMatrix.ratio`, `level` and `passes` take **indices**. Passing ARGB ints compiles and then throws
`ArrayIndexOutOfBoundsException` only when the grid draws; a Compose test caught it. `Palette.name` is validated as
already clean in the constructor (use `Palette.of`, `renamed`, `cleanName`), so a name can never carry a newline into the
`.gpl` header, the codec or the JSON. The recolour's "a pure swatch maps exactly" holds only while no other palette colour
is within softness (default 10 Lab dE) and only with `keepLightness = 0`; with it above 0 a pure swatch returns within
+-1 per channel through the Lab round trip. `PaletteRecolour` allocates its scratch per `mapInto` call, so one
instance is safe from several coroutines.

## 107. A comment that contains `*/` or `/*` breaks the file, and Kotlin comments nest

#38 again, in two new shapes. A KDoc that wrote "L\*a\*b\*/LCh" (palette builder) contains a close-comment sequence and
the whole file failed with a cascade of "Expecting a top level declaration". A KDoc that wrote the audio mime glob
(reel sound file) opened a nested comment, because Kotlin block comments nest, and broke the build the same way. Write
"any audio type" and "Lab and LCh" in comments; the string constants themselves are fine.

## 108. A palette remembers where it came from, so the export gate cannot be bypassed by editing

A palette made from photos carries their ids (`PaletteDraft.sources`). `reconciled()` drops ticked photos that left the
library but **not** sources: dropping them would let a lock re-open the gate. `ExportGate` asks the live `mayExport` for
each source at share, card, preview and recolour time; a card is asked again after it is drawn and deleted if refused; a
photo missing from `env.assets` counts as "no". An **empty** asset list is "still loading", never "everything is gone",
and never wipes ticks or the draft. Honest limit: a palette typed in, or saved and reopened, has no photos behind it,
so saving then reopening bypasses the gate (saving to the private store is not publishing). The draft is one
`rememberSaveable` string (`PaletteDraft.encode`, under 8000 characters worst case, undo palettes after a `===` line);
a palette that cannot be read loses the draft rather than reopening a page that needs it, and the reconciled draft is
written back by a `LaunchedEffect` that recomputes from the *current* saved draft, never from the composition's
snapshot, so it cannot undo an edit made in between. The saved-palette store is `filesDir/palettes/palettes.txt`
(one process-wide lock, tmp + fsync + rename; a name, case-insensitive, is the identity; a damaged entry is copied to
`.bad` before the next write drops it; an unreadable file is never overwritten). One instance per file (#26).

## 109. Animated pictures: `ImageDecoder` cannot step an animation, and the decoders never throw

`ImageDecoder` returns an `AnimatedImageDrawable` on its own clock with no seek, so it is not used for animated files.
GIF and APNG are read by the package's own readers on every API level; animated WebP by a pure-Kotlin RIFF/ANMF
parser (`WebpReader`) that wraps each frame's chunks in a stand-alone VP8X file and decodes it with `BitmapFactory`
(one frame in memory, no orientation, a library photo never goes through it; #34 documents why). That works from API
26, so no "unsupported on 8 and 9" warning exists; only Robolectric's native decoder has run it. Decoders return the
frames read plus a `DecodeProblem` and never throw on bad bytes; a fuzz test found an APNG header with a width over
65535 reaching `AnimSequence`'s `require`, so any new decode path that builds an `AnimSequence` must check `MAX_SIDE`
first. GIF has one bit of alpha: pixels under alpha 128 are clear, and if any frame has clear pixels the encoder cannot
delta-code (every frame is cropped to its opaque box with disposal 2: a 30-frame sticker is 371 KB as GIF, 62 KB as
APNG). GIF delays are hundredths, carry the rounding error and are never under 2 (browsers show 0 and 1 as 10); the
decoder returns delays of 10 ms or less as 100 ms unless `browserDelays = false`; a loop count N is N+1 plays.
A truncated GIF frame is returned part-painted and flagged; a truncated APNG frame is dropped. APNG is held to 30
percent of the heap before it is tried; GIF is streamed. APNG is saved as `.png`/`image/png` on purpose, so the name
does not say it moves. Whether the platform accepts `Pictures/Fotoz/Animations` for a *video* row is unverified.

## 110. `ExperimentEnv.mayExport` is false for every video; clips are asked through an image-typed copy

`mayExport` is `!asset.isVideo && ...`. Both the animated-picture maker (`ExperimentAnimateHost.mayUseVideo`) and the
reel export (`ReelEnvHost.mayUseVideo`) ask it of `asset.copy(mimeType = "image/jpeg")`, which reads the host's current
browsable ids and needs no change to `experimental/`. A test pins that the answer is live (clearing the set flips it). If
the host's photo rule ever looks at more than the id, change both. For a clip the export library reads the file itself,
so protection can only be checked before and after the render, never during. Videos are listed from `env.assets` (the
browsable list) and never offered when trashed.

## 111. Media3 Transformer has one thread, and a refused composition is a step down

Create, start, poll and `cancel()` on the application looper: `Media3VideoMaker` and `TransformerReelEncoder` run in
`withContext(Dispatchers.Main.immediate)` and post cancel to that looper when another thread cancels, instead of
`VideoExporter`'s `invokeOnCancellation` (which runs on the cancelling thread). `kotlinx` `CancellationException`
is an `IllegalStateException`: catch it first, then `ExportException`, then `RuntimeException`, so a composition the
library refuses at `start()` becomes a step down and a cancel still propagates. Transformer picks the *primary*
sequence deterministically (lowest index holding video), so there is no registration race.

## 112. The reel's layered render: facts from the Media3 1.9.0 sources, and what has never been seen

A reel with fades or slides is two video tracks plus `ReelCompositorSettings`. From the library's source (not tried
on a device): the first video-bearing sequence is primary (drives frame times, ends the output, drawn on top); an
empty stretch in a video sequence is 1x1 black at 30 fps, so every layer's alpha is explicit per time (0 in its empty
stretches) and the output size comes from `getOutputSize`; the last item is on layer 0 so the primary runs to the end;
`StaticOverlaySettings` accepts anchors only in -1..1, so a whole-frame push is split as background anchor +s/2 and
picture anchor -s/2 (a unit test caught the first version throwing); `Presentation`'s empty area is black with alpha 0,
so in original-shape mode stills are pre-composed onto black in staging while a clip is letterboxed by the library
and its bars are see-through during a fade (the previous picture can show). The Ken Burns matrix is the library's own
`Crop` (a test asserts equality) and the effect remembers the first frame time it sees. HDR clips are tone-mapped
before the compositor (it rejects HDR). The plan is pure and tested to cover every moment of 120 random reels; **a
wrong-looking success has no fallback**, which is why `transitions = false` (plain cuts) exists as an up-front choice.

## 113. Nothing a reel makes may carry a place, and the check walks `moov` only

The export library copies the first video track's metadata (a clip's recorded place and creation time) onto the
output, so `PlaceFreeMuxerFactory` drops *every* metadata entry, staged photos are JPEGs with no EXIF at all, and the
finished file is walked box by box (`ReelMp4Check`) for the QuickTime `xyz` atom, `loci` and a location key; a hit
refuses the save (`PLACE_LEFT_IN`). The scan looks in `moov` only because four-byte patterns occur by chance in picture
data about once per 100 MB. The muxer drop has only been tested with a fake muxer. A sound file that cannot be used
means nothing is saved and the screen asks "Make the reel without sound?"; the job never retries without sound by
itself. A reel's staging JPEGs are swept by the writer when older than an hour; a process kill mid-export leaves no
row on Android 10 and later (pending), and on API 26 to 28 can leave a truncated file nothing removes.

## 114. A main class named like a test class shadows it, and a saved reel is a file, not a bundle

`ReelWriterTest.kt` defines a test-source `ReelStore` (a stand-in MediaStore provider) in package
`com.fotoxplorr.app.reel`; a main class of that name would be shadowed at runtime and fail to compile in tests. The
library class is therefore `ReelProjectStore` (file `ReelStore.kt`). **Grep the test source set for a name before
adding a main class to a package whose tests define stand-ins.** The reel autosaver starts from the project as its file
had it (`OpenReel.loaded`), so opening a reel and leaving it does not rewrite a file from a newer build; a reel
restored after a kill has `loaded = null` and is written on the next change. A reel whose JSON is over
`SAVED_STATE_SAFE_CHARS` (60,000) is not put in the saved-state bundle and comes back as the list. The reel's name is
typed untrimmed and trimmed only on save; a card's words keep line breaks and lose other control characters.

## 115. The reel editor, the animate screen and the board inset their sideways content instead of using `clearOfShellEdges`

`clearOfShellEdges()` also adds vertical system-bar padding and 60 dp top and bottom padding (#72), which is wrong for
a strip in the middle of a scrolling page and costs about 120 dp of board height. The animate photo strip, the reel
item strip and the mood board are inset horizontally by `ShellEdges.inset` (or `INSET_DP`) instead, and pages end with
a spacer; everything else scrolls vertically. Every drag has a button alternative (Move earlier/later, Remove, steppers,
Same for all), so nothing needs a drag. The cost is that a vertical scroll that starts near the top or bottom edge can
open the details/actions room (accepted, #72). `MediaImage` describes itself with the file name, so a cell that already
says the name reads it twice; the animate cells wrap the picture in `Modifier.clearAndSetSemantics {}` (an outer
clearing modifier on the same node wipes the picture's inner description). The palette builder's photo grid has the
same doubled description and was not changed.

## 116. Mood board rendering: `PorterDuff.MULTIPLY` is not multiply, and masks are multiplied in by hand

`PorterDuff.Mode.MULTIPLY` is the componentwise product *including alpha*, so a half-opacity multiply layer halves the
backdrop's alpha (probed: `0x80804000` over opaque grey). The renderer uses `BlendMode.MULTIPLY` on API 29 and later and
on 26 to 28 lays the layer on white first (`backWithWhite`); screen and overlay are fine through `PorterDuff`. Drawing an
`ALPHA_8` bitmap with `DST_IN` left the destination unchanged under Robolectric native graphics (an `ARGB_8888` mask
worked), so mask planes are multiplied into the layer bitmap row by row. The renderer draws bitmaps on a software canvas
only: a decoder must not return `Config.HARDWARE` (`decodeUpright` does not). A photo shared by several layers is decoded
once at the largest size any needs and kept to its last use; the set of shared ids is fixed *before* drawing (an
earlier version recomputed it from the falling count and decoded again). `PreviewPhotoCache` is for the preview only:
availability is asked every time and a photo that stops being available is dropped from memory; the export never uses it.

## 117. A subject mask is the lift tool's marks, re-solved on every render

The lift package keeps no mask, only the editable list of marks. `MaskShape.Subject` stores the marks (normalised) and the
renderer replays `SelectionOps.replay` over the layer's pixels at a 512 px working size on **every preview and export**
(a fresh solve, not byte-identical to what the lift screen showed). A feather does not apply to a subject mask (`setMask`
and the codec zero it), hit-testing treats it as the whole box, and each subject layer multiplies the cost. The
alternative (freeze the cut as a transparent PNG layer) is a different model and is an owner decision. Stored boards
write `argb` as a signed int and read it back through `Long.toInt`; `JsonValue` numbers are doubles. A board over
`BoardJson.SAVED_STATE_SAFE_CHARS` (200k) comes back from its autosaved file, so the last second of edits can be lost.
Each finger gesture gets a process-wide counter in its merge key (`gesture:N`): a per-`pointerInput` counter restarts at
0 whenever the board's scale changes and could merge two gestures into one undo step.

## 118. Compose tests of the new screens: five ways to read one step behind or never go idle

(1) A `Dialog` containing a `TextField` never reaches idle under Robolectric (`AppNotIdleException` after 60 s; plain
`AlertDialog` is fine, plus a text field hangs), so the board's name is a field in the Board panel, not a dialog.
(2) State set by a `SideEffect` (a `BackHandler`'s enabled flag) is read one step behind right after `performClick`; call
`waitForIdle()` before asserting it. (3) A click on a node a scrolling panel has scrolled out of sight lands on nothing,
and a `LazyColumn` row needs `performScrollTo` first. (4) A test class helper named `click()` shadows
`TouchInjectionScope.click(Offset)` inside `performTouchInput` (the outer member wins). (5) A plain `withFrameNanos` loop
makes a test never idle: the animate `FramePlayer` asks for frames through `InfiniteAnimationPolicy` when the context has
one, so with the default `autoAdvance` the loop stops and tests go idle; a test that wants it moving sets
`mainClock.autoAdvance = false` *before* the preview is made, then steps with `advanceTimeByFrame`/`advanceTimeBy`. (The
aquarium's own loop uses plain `withFrameNanos` and would block idleness.) Also: a `LaunchedEffect` that clears a notice
after a delay vanishes before it can be asserted because the test clock auto-advances, so the new screens clear notices on
the next action or view change (also better for TalkBack); a composable that emits siblings with no container (Chroma's
controls) piles them up when a test calls `setContent` directly: wrap it in a `Column` in the test. Roborazzi runs in
record mode, so none of these screens has a screenshot that proves a look.

## 119. `build/test-results` is shared by every agent on the tree

The XML is wiped by anyone else's test run. To read a failure, copy the results out *inside* the `flock` that ran the
tests. Builds were also blocked for minutes by another agent's half-written files (a `return` in an expression-body
function, an unresolved reference, a missing test kit); the rule of waiting 90 s and retrying up to six times held. An
import-pruning script that treats `.name` as "not a use" removed extension-function imports; do not prune imports by
script.

## 120. Chroma ID's solver: the ambient frame is a dark frame, and a room that is too bright is told from a flash that is too far

`PatchSampler` must sample the **ambient** frame with `keepDark = true`: an unlit room gives a black ambient frame, which
is a valid answer (the first version refused it as `TOO_DARK` and every near-dark-room trial failed). A matte surface's
room-lit brightness does not change with distance, so only the *flash* frame's distance matters: the correction is
`(d_surface / d_card)^2`, applied only when both distances are known and differ by more than 5 percent, refused beyond
1.5x (a 10 percent distance error leaves a mean dE of about 3.9). `TOO_BRIGHT_ROOM` versus `TOO_FAR` is decided by the
ambient patch's absolute level (>= 0.15 linear) *and* its share of the flash frame (>= 0.5); a share-only version called a
dim room "too bright". The noise the signal is judged against includes a 1 percent frame-to-frame jitter term (flicker,
imperfect lock), which keeps a thin signal honest; if the real jitter is 3 percent the dE grows (mean 1.8 at 3, 3.6 at 6
on synthetic frames) and the solver does not know. **Phones apply a tone curve (sometimes local tone mapping) to JPEGs,
so the linearity the method rests on holds only approximately; this is the biggest unverified assumption**, and every
accuracy figure is from synthetic frames that obey the model exactly. The blue/purple boundary of the plain-word hue
families is set at 315 degrees by eye (pure sRGB blue is at 306 in CIELAB). Colour name lists are original words with
hex values written by hand (not copied from xkcd, Pantone or RAL; a test splits names into words so "floral white" does
not trip the "ral" check); `vision/VisionCopy.COLOUR_TABLE` holds a second list of about 162.

## 121. Chroma ID's capture: the CAMERA permission implies required hardware, and the order of the shots is not the order of the maths

The `CAMERA` permission implies `uses-feature` camera and autofocus as **required**, which hides the app on the store from
phones without them. `camera.any` alone does not remove the implied ones: declare `camera.any`, `camera` and
`camera.autofocus` all `required="false"` (`ChromaPrivacyTest` checks). The light is locked with the torch **on** (so the
exposure auto-exposure picks will not clip the flash frame), then the ambient frame is shot with the torch off under that
locked exposure, then the flash frame with the torch on: the ambient frame is the dark one. The flash is a held torch, not
a burst (a burst's timing against a locked exposure is not ours; a pre-flash would break the subtraction); the price is a
dimmer LED and a short hold distance (10 to 15 cm). Focus is settled once per pair, not locked for the whole lock,
because the surface's own distance is wanted; the distance is reported only when the AF state was FOCUSED_LOCKED or
PASSIVE_FOCUSED for the flash frame. Frames are matched to the capture result by `SENSOR_TIMESTAMP` (the wanted image is
the first with timestamp >= latest result + 1); if image and result timestamps are on different bases the wait times out
after 3 s and the person sees "The camera took too long": a safe failure that would make the tool unusable on that phone.
A pair whose reported exposure, ISO or white balance differs by more than 2 percent is refused (`LOCK_SLIPPED`). A card
shot is valid only for its own exposure, so the job keeps it as patches (numbers) only while the lock is held and drops it
on relock, unlock, stop, cancel or failure. The job and the gyroscope listener assume one thread (main); only the solver
runs off it. In `kotlinx-coroutines-test`, `advanceUntilIdle` does not run coroutines launched in `backgroundScope` when
only background work remains, so a job given `backgroundScope` never starts: give it a `StandardTestDispatcher` scope and
stop it at the end. Every threshold (hold distance, bright-room limit, gyroscope 0.15 and 0.35 rad/s, settle waits of 8
frames and 350 ms, the 2 percent lock tolerance) is a guess, named in `ChromaGuidance`, `LockCheck` and `Camera2Source`.

## 122. Chroma ID's screen: the shown colour lives above the camera, and the camera body must stay composed

A job that `stop()`s clears its own outcome, so the colour the person is looking at lives in `ChromaIdScreen`
(hoisted, saved with a `Saver` of its history line); the camera body only reports a finished colour up through
`onReading`. Opening History, rotating or pausing would otherwise wipe the result card. While the result is shown the
camera body stays composed (the `SurfaceView` leaving composition fires `surfaceDestroyed`, which calls `job.stop()`), and
the result covers the live view with an opaque box the size of the preview; whether a `SurfaceView` really stays hidden
behind an opaque sibling on every device is a device check. Leaving the measuring view (History, Compare, Save) disposes
the camera body, which stops the camera on purpose (no camera indicator while browsing); coming back reopens it because
an observer added to an already RESUMED lifecycle is replayed ON_CREATE/ON_START/ON_RESUME. The card measurement is lost
by that trip, the shown colour is not. The palette builder's store replaces a palette of the same name (case-insensitive)
and moves it first, so a new palette from Chroma gets "Chroma ID", "Chroma ID 2", ... chosen again from a fresh load at
save time; a palette needs 2 to 12 colours, so a single reading cannot make one alone (read-then-save is two store
calls, not a transaction). The history is plain text (colour, kind, time) in `filesDir/chroma`, so `ChromaPrivacyTest`
had to stop forbidding file writers in the whole package: it exempts `ChromaHistory.kt` only, and a second test pins that
it is the only writer and takes no picture type.

## 123. The Experimental registry after batch 8

Six new permanent ids: `reel`, `animate`, `board`, `palette` (Make), `chroma`, `colourcheck` (Explore), inserted before
the Setup entry, which stays last; `ExperimentalHostTest` pins the id list and each group's order. Only `chroma` is not
restorable (it opens the camera and asks the permission in context) and only `chroma` has an `available` check
(`deviceHasCamera`: `FEATURE_CAMERA_ANY`; the manifest declares the same feature optional). Titles must stay unique and
blurbs at most 80 characters and one full stop; each is taken from the package's Copy object. `reel`, `animate`, `board`
and `palette` need the activity's view-model store for the app's job list (as the panorama and the mood board do): a host
that composes them without one gets a disabled Make/Save, not a crash. `ChromaId` replaced the C2 name
`ChromaIdExperience`. The viewer's colour vision and the editor hub need no card. The Experimental page now has two more
privacy lines (`HelpCopy.Note.EXPERIMENTAL_CAMERA`, `EXPERIMENTAL_SOUND`; ADR-017) pinned by `HelpCopyTest`; the Play
full description is capped at 4000 characters and is now 3985, so the next line added needs one removed.

## 124. Colour vision check: it is a game, and its wording is held by tests

`vision/ColourCheck` runs seven dot plates (one easy control, two each for red-green in two kinds and blue-yellow, in a
seeded order). It cannot tell a red-weak person from a green-weak one (two plates per axis cannot) and says so by never
naming a condition: a banned-word scan covers the result, which always carries the "this is a game, not a test" line and
"An eye doctor can test you properly." A missed control plate is "check your screen" (night mode, blue-light filters and
screen colour all degrade the blue-yellow plates, and the screen warns). The plates are our own seven-segment digits on a
seeded dart-throwing packing; the figure/ground hue is pushed along the null direction of the full-severity matrix
(inverse iteration), lightness is randomised per dot. The grey square behind the dots is untested on a screen. The step
and answers survive recreation via `rememberSaveable` and the host's restorable id; the host's Back is free only at the
introduction.
