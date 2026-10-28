# Batch 2 device test checklist

Every device-only claim from the second owner-feedback batch of 21 Oct 2026 (see
`docs/handoff/MASTER-PROGRESS.md`, "Batch 2"), in the same shape as `phase-0-checklist.md` and
`batch-1-checklist.md`. This sandbox has no device or emulator, so **none of these is claimed as
verified**: each section says what the JVM and Robolectric tests cover, and the boxes are for what
those cannot see (a real finger, real insets, real decoders, the system chooser, process death, how a
thing *feels*). Check each box on a real phone, on **both flavours** where a line says so, before
relying on the corresponding change. Robolectric reports zero system-bar insets, so every "clears the
status bar / navigation bar" line below is reasoned and rendered, not seen.

The owner items being answered: **#18** *"The favourite icon should be visible when a photo is being
viewed - doesn't need to hide in the right side menu."* **#16** *"Once slideshow mode is on, the
slideshow controls should be overlayed in the style of the actions bar at the top."* **#17** *"When
viewing images that have transparency, we should be able to choose background - the grey white grid,
or white or black."* **#6** *"The help text is too verbose."* **#5** the rail scrub, **#26** the rooms
geography, plus the audio removal, the open-with decision and the editor's overwrite safety.

Suggested devices: a current phone on gesture navigation, one on three-button navigation (a 48 dp
bar), a small phone (about 640 dp tall, 360 dp wide), one on Android 8.x if you can get it, and a
tablet or ChromeOS window for the pinned rail. Use a prepared library of photos (see
`fastlane/metadata/android/en-US/images/README.md`), not your real one.

## 1. Audio is gone

Sandbox coverage: `AndroidProjectionParityCheckTest` (25 projections per privacy view), the enum
fallback for a saved default view of Audio, `LegacyAudioCleanupTest`, `JobNotificationPermissionTest` and
`JobNotificationAnswerTest` (the one-shot notification ask), and the merged manifests (checked by
reading them).

- [ ] Install over a build that had the Audio destination and a saved default view of Audio → it opens
      on **Photos** with no crash. The rail shows nine words (Pets, People, Identity, Screenshots,
      Photos, Videos, Favourites, Places, Protected) with correct spacing and Protected last, and
      Settings > Media (default view) lists nine entries.
- [ ] On a device that had the audio library, after the first launch of the new build the files
      `databases/foto_xplorr_audio_catalogue.db` and `shared_prefs/audio_scan.xml` are gone (debug
      build, `adb shell run-as`), and the user's own audio files, including any converted `.m4a` and
      tagged files in Music, are untouched.
- [ ] Open an `.mp3` from the Files app → Fotoz is **not** offered in the chooser (no `audio/*` filter
      anywhere in the manifest).
- [ ] A fresh install on Android 13+ never shows an "allow access to audio" prompt anywhere, and the
      installed app's permission list (system settings) has no music/audio entry and no
      "media playback" foreground service.
- [ ] A video with two audio tracks still shows the audio-track menu, the volume gesture works, the
      editor's mute still mutes the export, and starting a video still takes audio focus and pauses
      *another app's* music.
- [ ] Android 13+, fresh install, **first conversion/export**: the notification permission prompt
      appears exactly once; after answering, later jobs do not prompt again; with it granted the progress
      notification shows, including when granted while the job is already running.
- [ ] Start a job with the app backgrounded and then foreground it: the prompt appears once the
      activity is in front, and again on the next job if the first was dropped. Start a job while another
      permission dialog is up: the app must not record the notification prompt as asked. (Rationale-based,
      unverified; an OEM that never reports a rationale after a real refusal would just re-ask.)

## 2. Open-with, share-in and the picker

Sandbox coverage: `IncomingIntentRouteTest`, `MediaStoreUriClassificationTest` (percent-encoding,
`0@media`, picker Uris), `MediaStoreRowFallbackTest`, `OpenRequestLookupTest`,
`ExternalViewerActivityManifestTest`, `SafeIntentExtrasTest`.
The full list of cross-app steps is in `phase-0-checklist.md` P0-18; the extras below are the batch-2
additions.

- [ ] From the Files app and the system photo picker, tap a normal photo and a video (`content://`) →
      the VIEW chooser lists Fotoz **once** and opens `ExternalViewerActivity`; a catalogued
      `content://media` photo redirects into the full in-app viewer.
- [ ] A photo in a locked folder (folder still locked), an archived photo, a trashed photo and a
      sensitive photo with Hide sensitive on, opened via another app → the notice "That photo is hidden
      or locked in Fotoz, so it was not opened." and the gallery; never the viewer. Unlock the folder
      in-app and repeat → it now opens. Also a photo saved a moment ago into a locked folder (inside the
      scan debounce) → the same notice, nothing opened.
- [ ] A picker Uri (system photo picker into a VIEW) → opens as an external file, not as the wrong
      catalogued photo.
- [ ] Android 14 "selected photos" access: a `content://media` photo you did not grant → refused with
      "could not be checked against your library ... try again in a moment". Judge whether that is
      acceptable UX. Separately, a per-Uri grant for a row not yet scanned and not covered by standing
      permission: expect it to **open** (the item-Uri fallback is unverified: if MediaProvider only
      honours the grant on the typed Uri, the fallback will not help).
- [ ] SEND a photo and a video to Fotoz (and SEND_MULTIPLE) → the viewer opens them as external assets
      (no tag/caption/location rows, no heart); SEND of a hidden catalogued photo shows the notice. EDIT
      of an image from another app opens the editor.
- [ ] The in-app "Open with" chooser does not list Fotoz itself.
- [ ] Hostile intents from `adb` or a test app: a custom Parcelable extra Fotoz cannot load, with
      `EXTRA_OPEN_MEDIA_ID` alongside, sent as VIEW/SEND/EDIT to `FotoXplorrActivity`,
      `ExternalViewerActivity` and `PhotoPickerActivity`, including relaunching the task from Recents →
      no crash and no crash-loop; the intent is treated as extra-less. On API below 33 a hostile value
      under any key drops all extras (Bundle unpacks at first touch; unverified there).
- [ ] Process death with a photo from an unlocked folder open: `adb shell am kill com.fotoxplorr.app`,
      return → neither the viewer nor the editor reopens on a photo that is locked again; a normal photo
      reopens with its saved run.
- [ ] Send ACTION_SEND/SEND_MULTIPLE/PICK with `EXTRA_STREAM`, `EXTRA_ALLOW_MULTIPLE` and
      `EXTRA_OPEN_MEDIA_ID` → the viewer, editor and picker still behave.

## 3. The rail scrubs

Sandbox coverage: `DestinationWordRailTest`, `DestinationWordRailComposeTest` and
`DestinationWordRailPaneComposeTest` (a Robolectric drag, not real touch slop), and the render of the
nine-word rail.

- [ ] A real-finger scrub through the rail: the square hops with the wheel, nothing jumps on lift, the
      hovered word commits after about 140 ms of rest, and the pane behind (only the 72 dp parked-card
      sliver on a phone; fully visible on the pinned rail) follows it. Release never closes the room.
- [ ] Performance on a real library of about 22k assets: each committed hover recomputes
      `destinationAssets` and calls `scrollToItem(0)` on the main thread, up to nine times in a slow
      scrub. If it janks, fall back to committing only at landing.
- [ ] A drag that starts on a row (rows are clickable): a normal vertical drag from any word scrolls
      the wheel and does not register a tap.
- [ ] Fling feel: the commit now happens when a fling ends, up to about 1 s after lift. Does that read
      as lag?
- [ ] Back, a tap on the parked band, and a tap on the selected word each close the room after a
      scrub-select. TalkBack or accessibility scroll actions are programmatic scrolls and do not commit
      on landing (rows stay tappable): check on a device.
- [ ] Mouse wheel or trackpad over the rail on a tablet or ChromeOS lands on a word and the pane
      follows.

## 4. Settings and destination pages

Sandbox coverage: `PaneRenderTest`, `PaneReviewFixesTest`, `PlacesRootPathInkTest`, `PaneBackTargetTest`,
`PaneInputTest`, one `Role.Switch` node per settings row.

- [ ] Open Settings from the rail's Settings row: the room swings closed and Settings is the page, with
      no flash of the grid. The back chevron and system Back both return to the grid. Check the status
      bar and navigation bar clearance with gesture navigation (the scaffold applies both paddings,
      reasoned as no-ops inside the browser's Scaffold, not run).
- [ ] Pinned rail on a wide window: Settings from the pinned rail swaps the page in place with no room
      involved, and choosing a destination leaves Settings.
- [ ] Albums, Calendar, Discover, Library and Tidy up in the page: Calendar draws its own
      `statusBarsPadding` and Discover's Places screen has its own chrome. Check for a doubled top gap or
      header. The Albums FAB still shows over the page and sits where it did relative to the nav bar.
- [ ] Rotate while Settings is open. `settingsOpen` and the selected tab are saved; `legacyScreen` is
      plain `remember`, so a destination page does not survive rotation (unchanged).
- [ ] Search, selection and the drill-down bar while a page is open: the search field is hidden under
      Settings but keeps its value, selection is cleared on opening Settings, and the search field
      returns on Back.
- [ ] With a page open, the top, bottom and right edge drags do nothing; the left rail still opens.
      With a hardware keyboard: Ctrl+A, Delete, Enter, `/` and arrows do nothing; Escape closes the page.
- [ ] With Settings open, open the rail and tap the highlighted word → Settings closes (and the room
      closes); same on the pinned rail, and with a destination page such as Albums. Scrub away from the
      highlighted word and back with a page open → the page leaves after the pause (holding a few dp
      of drag on the highlighted word also leaves it: intended, feel unverified).
- [ ] Start a background job (index or scan) and open Settings > Data: the activity shade shows over the
      page, the page moves down with it on the shade's 320 ms curve (no visible jump or overlap of the
      back chevron), and pulling the shade works.
- [ ] Light theme: Discover > Places (Immersive and AI) header, the privacy-boundaries heading and
      paragraph, the similarity explorer, "Organise by similarity" and (offline) the street-map message
      are white on black, readable.
- [ ] The Settings tab chips are about 37 dp drawn and 48 dp to hit: tap just above and below a pill;
      check row spacing, and the Default view and accent chips.
- [ ] Wi-Fi-only row (offline build, Background tab): caption legible, toggle state readable when
      disabled.
- [ ] TalkBack: a settings switch row announces once, as a switch with On or Off.
- [ ] About tab: the offline build shows the shortened no-INTERNET sentence; a **Connect** build shows
      the new Connect statement (it used to claim "no INTERNET permission" wrongly).

## 5. One toggle

Sandbox coverage: touch bounds and an injected tap 5 dp above the track in Robolectric; renders.

- [ ] The toggle is 64 x 32 dp drawn with a 48 dp touch region: tap near its top and bottom edge in the
      Settings page and in the viewer view options room.
- [ ] Press ripple: `HyleToggle`'s toggleable uses the theme's default indication, so the ripple covers
      the 64 x 32 track with square corners. If it looks rough on black, pass `indication = null`.
- [ ] TalkBack: rooms that put a toggle inside a clickable row (gallery actions, viewer view options)
      still expose two actionable nodes (the row and the switch), as before.
- [ ] Disabled legibility: on a real OLED panel the disabled switch (about `#565657` on black) is still
      distinguishable from the ground, and on the light card (about `#E8E8E9` on `#F4F4F5`).
- [ ] The gallery view options, the viewer view options and the editor's "Do this every time" row show
      the same parallelogram switch as Settings.

## 6. One room geography, and the quick strip

Sandbox coverage: `ViewerRoomGeographyTest`, `ViewerActionsRoomPeekTest`, `ViewerBottomReserveTest` and
`ViewerBottomReserveFoldedTest`, `GalleryQuickStripSelectionTest`, `RoomGeographyTest`,
`RoomGeographyPaneTest`, `ActionsPeekTest`, `ActionsPeekInsetsTest`, `QuickActionStripTest`,
`PlaceMorphShellBandTest`, renders
`gallery-7` to `gallery-10` and `room-1` to `room-5` under `app/build/renders/` (reviewed: gallery-7 to
gallery-10 and room-5 only). The two-stage reveal itself is a shared-libraries feature whose
re-settle path, stale-flick guard and tap slop were never exercised by a Compose test.

Geometry and insets

- [ ] Compare by eye: top room pulls from the very top (details), bottom room tilts up from the bottom
      (actions), right room comes in from the right (view options), in the gallery **and** the viewer.
- [ ] Insets for the moved rooms on a phone with a camera cutout, on gesture navigation and on
      three-button navigation: the top details room clears the status bar, the bottom actions room the
      navigation bar, the right view options room both (its last row clears the bar and scrolls
      fully).
- [ ] Gesture navigation: the bottom pull zone is the 56 dp band **above** the navigation or gesture bar
      (the larger of the two insets). A pull starting just above it opens the strip and does not
      trigger the system home or recents swipe; a swipe starting on the gesture bar goes home; the
      bottom hint pill is clear of the gesture bar and of the app's own pill and filmstrip.
- [ ] Three-button navigation (a 48 dp bar): the strip sits entirely above the bar; a pull that starts
      on the bar itself does not open the room; the zone is not just an 8 dp strip.
- [ ] Rotate portrait and landscape while the room is parked on the strip: on a window where the strip
      fits it animates to the new stop; where it does not, the room closes. Also split-screen.

The two-stage bottom room (gallery and viewer)

- [ ] From rest, a slow pull and release in the lower part settles on the **strip** (not snapping
      closed, not jumping to full); release past roughly the middle of the screen goes to full; a fast
      flick up from rest lands on the strip; a second flick up opens full; a flick down from either
      closes. Holding still on the strip for about half a second, then lifting, keeps it there.
- [ ] Tap the parked peek card, with a slightly jittery finger → it closes. Dragging it up more than
      touch slop carries on to full. Tapping a quick action in the strip is not swallowed by the card
      overlay. System Back closes a peek.
- [ ] The strip (icons plus labels) is fully visible above the navigation bar with at most a few dp
      spare, no part of the first full row shows at the peek (Slideshow in the viewer, ORGANISE in the
      gallery), and the strip sits directly under the empty 72 dp band. Check a short phone (about
      640 dp tall) and a tall one (900 dp and more), since the card-scaling correction grows with height,
      and also under `ParkStyle.SWIVEL`.
- [ ] At full stage there is a navigation-bar-height black gap between the strip and the list; is it
      acceptable? On a short pull a black slice, not list rows, sits under the strip. With a translucent
      navigation bar the black ground does not look cut off from the strip, and on the full room the last
      list row clears the bar.
- [ ] A very tall navigation bar plus peek (more than half the height) or a window too short for the
      strip (landscape at the largest font, split-screen) → the room is single-stage: a short pull goes
      straight to the full list, with **no strip and no 72 dp slice**.
- [ ] Pinch shortcuts (viewer, "Pinch opens panels" on): pinch IN opens the details, pinch OUT opens the
      actions fully (not the peek). The Robolectric test exercised pinch out only.

Gallery

- [ ] Browsing: the strip is Select, New collection, Refresh; Select closes the room and enters
      selection, New collection opens the dialog, Refresh closes the room and rescans (and the "Rescan
      the library" row behaves identically).
- [ ] Select 2+ photos then pull a little: Share, Collection, Favourite (Unfavourite when every chosen
      photo is a favourite, and TalkBack announces "Favourited"), Trash. In the trash with a selection:
      Restore and Delete forever. A selection with nothing chosen: the cells are dimmed and dead.
      Four labels fit at 360 to 411 dp without ellipsizing at default font; check "Unfavourite" at 1.3x.
- [ ] The Niagara-style timeline scrubber runs down the right edge, alongside the view options room's
      right-edge pull: a drag down the scrubber does not open view options, and a pull in from the
      right edge does.
- [ ] Open the shade-producing activity (a scan) and go into an album or timeline view: the top room
      pulls from the very top there; at the root grid and on Settings the shade still expands.
- [ ] Gallery view options: Columns, sort, Videos, Blur sensitive, Hide sensitive. Note that any right
      room parks the grid to a 72 dp sliver, so the effect of Columns is only partly visible while open.
- [ ] Hardware keyboard with a room open: Escape closes the room; Delete, Ctrl+A, Enter and arrows do
      nothing behind it.

Viewer

- [ ] The quick strip is Favourite, Share, Edit, Trash; Favourite closes the room and the heart state
      changes; Edit, Share and Trash close the room and run. For an external (open-with) photo only
      Share and Edit show; with Trash unavailable (below API 30) the strip shows three.
- [ ] Video seeking: dragging the seek bar never opens the actions room, especially a slightly upward
      diagonal (an up-dominant first move past slop is claimed by the shell). A vertical-first start on
      the filmstrip or the seek bar scrubs and does not open actions; a pull from just above the
      transport (96 dp, or 50 when its second row is folded) does. Horizontal scrub along the filmstrip,
      then a vertical pull from the strip, with the strip bulging under the finger: no stray room opens.
      Photo with the chrome hidden: a pull from the very foot still opens it. Check landscape (the zone
      is 170 dp up plus 56 dp tall on a 360 dp-tall window and may feel high).
- [ ] Landscape video with the filmstrip and the transport folded: the pull zone sits just above the
      single transport row and the "pull up" hint pill does not float over the picture; unfolding the
      second row via the toggle still lets the seek bar scrub without opening the actions.
- [ ] Short viewport: the actions room is one scrolling column of up to 12 rows under a 72 dp band; the
      last row (Move to trash) is reachable on a short phone.
- [ ] A video playing, then the 3D swivel of the bottom and right rooms (batch 1 left this open): the
      surface follows acceptably or does not.
- [ ] The viewer's GESTURES note reads: drag the photo down for details, up from the bottom for what you
      can do with it (a little for quick actions, further for all), in from the right for view options.

## 7. Replace the original, Save As, and exports

Sandbox coverage: `OverwriteGuardTest`, `OverwriteRefusalTest`, `OverwriteHazardScanTest`,
`OverwriteHazardsExtendedTest`, `OverwriteMetadataGateTest`, `ReplaceWithRestoreTest`,
`ReplaceWithRestoreDurabilityTest`, `OverwriteSafetyCopyRecordTest`, `OverwriteRecoveryNoticeStateTest`,
`OrphanedBackupNoticeTest`, `EditorOverwriteStateMachineTest`, `EditorWriteLifecycleTest`,
`OverwriteFailureTest`, `ExportLocationStripTest`, `ExportEdgeLadderTest`, `EditorOomLadderTest`, and
the tests that use `FileBackedProvider` (a real `ContentProvider` over real files). Nothing here has
overwritten a real MediaStore file.

Refusals (the original stays byte-identical, "Save a copy" is offered)

- [ ] A Pixel Motion Photo, a Samsung Motion Photo, an Ultra HDR JPEG and an animated WebP: Replace is
      not offered or is refused, and the message names what would be lost. **Check the Samsung
      `MotionPhoto_Data` marker specifically** (written from memory of its layout).
- [ ] A real Samsung or Pixel JPEG with MakerNote and an MPF block is **not** refused wrongly. A
      multi-picture Pixel or Samsung JPEG will now be refused as extra embedded images (intended;
      confirm).
- [ ] A JPEG from Lightroom or Photoshop with IPTC captions → refused, Save a copy offered.
- [ ] A lossless WebP, and a WebP with alpha, on API 30+ (refused) and on API 26 to 29 (to confirm the
      legacy encoder keeps alpha before API 30, as `encodesAlpha` assumes).
- [ ] An ordinary screenshot PNG (sRGB or Display P3 profile, ImageMagick `date:*` text) now
      **proceeds**; confirm the written PNG keeps its colours (the claim that the encoder re-embeds the
      colour space is the reviewer's, unverified). A PNG with a generation prompt (`tEXt` after the image
      data, even over 256 KiB) is refused with the PNG-text message; a plain large PNG still replaces.
- [ ] Photo-location access: with `ACCESS_MEDIA_LOCATION` granted, Replace of a normal MediaStore JPEG
      keeps GPS, DateTimeOriginal and XMP. With it denied (API 29+), Replace is refused with the "allow
      photo-location access" message and the original is byte-identical. Also check whether a photo the
      app itself created is treated as redacted when the permission is denied (if so, those are refused
      too).
- [ ] The hazard scan reads through `setRequireOriginal` on API 29+ with the permission granted and
      denied.

Failure and recovery

- [ ] Force a mid-write failure (fill the storage almost completely, or kill the app during a large
      replace): the original is put back, or the message says a safety copy is kept inside Fotoz. After
      normal successes `filesDir/overwrite-recovery` (and `cacheDir/edit-backup`, the old location) is
      empty.
- [ ] Force a replace whose put-back fails (a provider that refuses the second write open): the "A save
      did not finish" dialog naming the photo appears **at once**, over the editor banner, without a
      restart. Check the two stacked dialogs (the message and the recovery dialog) render acceptably.
- [ ] **Kill the app** (swipe away, or `adb shell am force-stop`) in the middle of a Replace on a large
      photo and reopen → the dialog names the photo; *Share the copy of <name>* opens the system share
      sheet with the original bytes under the photo's name (confirm the target can read the staged
      file on API 26 and on a current release); the dialog returns after another force-stop until OK or
      Delete is tapped, and a tap outside it does not count as answering.
- [ ] "Save a copy" after a failed replace: the copy-saved message names the photo and says the original
      may still be damaged; the recovery dialog comes back if it had been dismissed; the next cold start
      shows the notice. If the copy also fails, the banner and the held edit stay, the button reads
      "Saving..." and Discard is off while it runs.
- [ ] Plant a file named like `IMG.jpg.1700000000000.bak` (plus an interrupted `backup-*.bin`) in
      `files/overwrite-recovery` on a debug build → the dialog appears once on the next cold start and
      not the one after; *Delete copies* removes exactly those listed. A `backup-*.bin` already answered
      for more than 7 days is gone at start (use a back-dated acknowledgement); a `.bak` after 30.
- [ ] `fsync` on the MediaStore FUSE file descriptor: if the provider throws, the backup is kept until
      the age limit. A successful replace on API 29 to 34 leaves nothing in `overwrite-recovery`.
      `statSize` of the MediaStore descriptor is the real size, and the live-size comparison does not
      wrongly refuse on a FUSE-backed file.
- [ ] Tap Save with Replace, then press Back and Cancel at once on a large JPEG → the editor stays until
      the write finishes, then shows "Replaced the original." Rotate, change font size, or switch language
      mid-write: the editor stays locked until the outcome, then closes (success) or shows the banner
      (failure); never stuck.
- [ ] While a save is rendering, or a replace is in flight, or a failure banner is up with the original at
      risk, send an EDIT or SEND for another photo from a file manager → "Finish saving your edit first",
      the editor unchanged, and the first photo's save completes on the right photo. With the original
      at risk, Back, Cancel and the back gesture leave the editor in place and only "Save a copy" is
      offered; after a declined-but-safe overwrite, Back closes and the next photo shows no banner.
- [ ] Force a failed replace that cannot be put back (hard to provoke; fill storage after the backup)
      to see the banner paths; the not-restored one is covered only by mapping tests.

Save As, copies and exports

- [ ] **Save As** ("Save to...") of a geotagged JPEG to Drive or another cloud location: no GPS in the
      exported file (check with a metadata viewer, XMP included) and no place names, but DateTimeOriginal,
      Make and Model are kept. "Save a copy" beside the original still keeps GPS. HEIC Save As writes
      pixels only (no EXIF, XMP or location).
- [ ] A photo whose XMP has accented text but fails the app's XMP parser, edited and saved as a copy →
      either no XMP or an intact one, never `?` substitutions.
- [ ] Export "Original" of a 48 or 108 MP photo on a device with a 256 MB heap: succeeds at full size or,
      after a real `OutOfMemoryError`, says "at reduced size (W x H)"; no crash, and no regression to
      about 2.8k px on a high-memory device. The 4096/2048/1024 presets still honour the cap. A Replace on
      a 60+ MP photo that cannot be decoded at full size falls back to a copy with the "too large to
      replace at full size" message. A large photo with a forced low memory limit: the encode step-down
      message appears and no OOM escapes, including the white flatten of a transparent PNG saved as JPEG.
- [ ] Metadata panel: "Clear location" still leaves place names (deliberately), unlike Save As.
- [ ] On **API 26 and 27**, do stills open in the editor, lift, and share frames at all? `decodeUpright`'s
      `BitmapFactory` path returns null there (TRAPS #34, not fixed); this is the check that proves it
      on a real device.

## 8. Video export (see P0-11)

- [ ] The device checks of `phase-0-checklist.md` P0-11, rewritten for the Media3 pipeline: a full disk
      leaves no partial file and no pending row; a successful export appears in the gallery.

## 9. The viewer: heart, slideshow bar, transparent photos

Sandbox coverage: `ViewerHeartTest`, `ViewerChromePolicyTest`, `ViewerSlideshowPolicyTest`,
`ViewerSlideshowBarTest`, `SlideshowBarRenderTest`, `SlideshowPausedStateTest`, `ViewerTransparencyPolicyTest`,
`TransparencyBackdropTest`, `BitmapDecodingAlphaTest` (ImageDecoder path only), `ViewerSettingsRoomTransparencyTest`.
A video cannot decode under Robolectric, so slideshow with real playback is untested.

The favourite heart (#18)

- [ ] The row is Close, the position pill, the heart (about 200 dp wide, centred): placement and look with
      the real status bar and cutouts at 360 dp width. It shows only while the chrome is up, for a
      library photo or video, and not for an external (open-with) photo.
- [ ] Tap it: filled vs outlined, TalkBack reads "Favourite" plus "Favourited" or "Not a favourite".
      The pop animation feels right turning on (it may fire on a page change if the host's favourite flow
      lags the asset change: cosmetic). The heart on a video with the chrome up does not collide with the
      "Resume from" chip.
- [ ] Narrow 320 dp phone: the chip row (Close, pill, heart, swatch button) fits without clipping.

Transparent photos (#17)

- [ ] Open a transparent PNG and a transparent WebP from the gallery: the grid shows behind the
      see-through parts; tap the chrome and the swatch button appears; cycle grid, white, black. Pinch
      zoom and rotate with the backdrop (its squares scale with the picture by design). The choice is
      also a row in the view options and persists across restarts.
- [ ] Open a transparent PNG or SVG via **Open with** (`ExternalViewerActivity`): the button is there (no
      heart), and the choice is shared with the in-app viewer.
- [ ] Also a GIF with a transparent index (backdrop and button), an AVIF with an alpha plane, an SVG and an
      ICO (backdrop shows), an HEIC with alpha, and a GIF with a very large first frame. Opaque JPEG,
      PNG, WebP and AVIF: no button, no backdrop. (Real-world layouts are covered only by synthetic
      fixtures.) An opaque RGBA PNG shows the button and changing it changes nothing visible, as designed.
- [ ] An SVG's backdrop hugs the drawing (the rectangle comes from the decoded painter size). On API 26
      and 27, check that stills open at all (see section 7 and TRAPS #34).
- [ ] A large (tens of MP) PNG: the 64 KiB header read causes no visible hitch on a page change. TalkBack
      reads "Transparent background: grid (tap for white)".
- [ ] Grid and filmstrip thumbnails of transparent images do not draw the backdrop, and the shrink-to-map
      flier does not either (known, out of scope).

The slideshow bar (#16)

- [ ] The cove against the status bar: the bar is flush to y = 0 and drawn taller by the status-bar inset,
      so the coves meet the top edge and the glyphs sit below the status icons. Check the clock and icons
      over the black bar, especially a **light system theme** where the icons are dark.
- [ ] Stacking height: status inset + 50.8 dp bar + 64 dp chip row is about 140 dp. At 360 dp width and
      in landscape it takes a large share of the height; is it too much?
- [ ] Over a video: the "Resume from" chip sits beneath the chip row and the bar (geometry only was
      checked). The bar and transport stay up for the clip.
- [ ] Targets are 48 dp, wider than the gallery toolbar's 46 dp pitch: do the corner targets feel right
      against the cove?
- [ ] Auto-hide: 3 s is not tuned. A tap brings the chrome back; the bar stays up while paused and over a
      video; Pause really pauses (the countdown stops), Play resumes, and Next or a swipe in a shuffled
      slideshow goes to the next photo **in order**. Stop slideshow (the actions room row) ends it.
- [ ] Slideshow with the host stopping (Home, lock) and with real video playback is covered only by the
      pure policy tests: confirm the countdown does not fire behind a dark screen and a clip plays out
      before it advances.

## 10. Help text (#6)

Sandbox coverage: `HelpCopyTest` (length ceilings: captions 60, hints 80, notes 160),
`BackgroundWorkStatusTest`, `ViewerRoomGeographyTest`.

- [ ] On a small phone open every Settings tab (nine), the viewer view options room with the pinch panels
      on and off, the gallery actions and view options rooms, the Places hub, AI settings and the
      similarity explorer, the photo map's empty state, the share options sheet and the editor's crop
      and heal panels: each hint fits in one or two lines, and rows whose caption was dropped (Show videos
      in Settings, Videos in the view options) show no empty gap.
- [ ] AI settings, offline build: the "Find similar photos" card reads "Not set up. The offline build
      can't download it. Import a file." next to the "Import a model file" button.
- [ ] Trash on Android 10 or below reads "Trash needs Android 11 or newer." (unverified).
- [ ] The not-put-back message says Fotoz "now offers to share" the copy (the old "next time Fotoz
      starts" promise is gone), and the "Select photos" caption says the count is at the top right.
- [ ] Read the four privacy and legal lines for sense, and have the owner or counsel read them: the
      offline statement, the Places privacy paragraph, the AI-service paragraph and the Pro "nothing is
      charged yet" line (and the new Connect statement).

## 11. Store listing and docs

- [ ] The installed launcher label, recents title and the "Open with" entry say **Fotoz**.
- [ ] Before upload: `versionCode` 5 matches `changelogs/5.txt`; the merged **offline release** manifest has
      no `READ_MEDIA_AUDIO`, no `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and no `INTERNET`; the hosted privacy
      policy is republished; `shared-libraries` PR #17 is merged first (see `docs/store/play-console.md`).
