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
arriving after OCR — must be read through `rememberUpdatedState`, as `ViewerScreen` now does for
`canLift` and the recognised-block count.

Related, **not fixed**: `LiveTextOverlay` is a full-size sibling whose `detectTapGestures` consumes
every tap and long press on the photo while the chrome is up, bare photo included. A long press to
lift therefore does nothing on a photo with recognised text; the actions room's "Lift subject" row is
the way in, and the same layer means a tap on bare photo cannot toggle the chrome there either.

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
