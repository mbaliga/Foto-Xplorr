# Phase 0 device test checklist

Every device-only claim from `docs/handoff/PHASE-0-BRIEF.md` and from
`docs/handoff/PHASE-0-PROGRESS.md`'s "Device checks added" section, grouped by item. This sandbox
has no device or emulator (`adb devices` empty, no `emulator` package, no `androidTest` source
set), and only one Robolectric framework jar (API 34) cached offline, so none of these are claimed
as verified — every item below is checked by the pure-logic/Robolectric tests named in the item's
own row of `PHASE-0-PROGRESS.md`, never by this list. Check each box against a real phone before
relying on the corresponding fix.

Unrelated to a specific item, `docs/adr/ADR-008-video-transcode-pipeline.md`'s own standing caveat
("not verified on a real device") applies to every video-transcode row below (P0-11) exactly as it
did before this brief.

## P0-01 — One visibility filter everywhere

- [ ] Lock a folder that has geotagged photos → Places shows none of them.
- [ ] Unlock that folder → the same photos reappear in Places.

## P0-02 — Original access helper; location reading on Android 10+

- [ ] On an Android 10+ phone with geotagged camera photos, open Places → the photos appear (before
      this fix they most likely did not — note in your results whether they did).
- [ ] Revoke "Allow access to location in media" in system settings → Places shows nothing new for
      previously-unindexed photos, and nothing is persisted as "no location" for them (re-granting
      the permission and rescanning should recover their real location, not a cached absence).

## P0-03 — One oriented, bounded bitmap decoder

- [ ] A portrait photo from a camera that writes an EXIF rotation (for example a Samsung) opens
      upright in the editor, saves upright, and shares upright with a frame applied.

  Sandbox coverage: `BitmapDecodingImageDecoderExifTest` empirically confirms `ImageDecoder` applies
  orientation, but only for a synthetic 4×6 bitmap re-encoded by this same JVM's `Bitmap.compress` —
  not a real camera JPEG's own encoder quirks, ICC profile, or a HEIC/HEIF file.

## P0-04 — Image shares strip metadata by parsing, not by ExifInterface no-op

- [ ] Share a geotagged JPEG, HEIC and PNG to yourself (or another device) and check each in an EXIF
      viewer for no GPS. The HEIC case specifically exercises the `Unsupported` → re-encode path,
      which this sandbox can construct real bytes for but has no HEIC decoder to confirm against.
- [ ] Share a Samsung/Google motion photo → the received file is a plain still JPEG, not the
      original's embedded-MP4-trailer format (this app's JPEG stripper stops at the first EOI
      specifically for this case).
- [ ] Share a portrait photo with a Polaroid or stamp frame selected → it arrives upright.
- [ ] Share a GIF or animated WebP with a frame selected → the animation still plays on the
      receiving end, rather than arriving as one static, framed image.

## P0-05 — Video shares remove location by remuxing, not raw copy

- [ ] Share a geotagged phone video (with "Remove location and camera data" on) → the received file
      has no location (check in a metadata viewer, and `MediaMetadataRetriever` directly if
      possible), it plays, and it is the right way up. This exercises real `MediaExtractor`/
      `MediaMuxer` behaviour this sandbox has no device to confirm at all.
- [ ] If the source is HEVC, VP8 or VP9 → the codec-appropriate container comes out (MP4 for HEVC,
      WebM for VP8/VP9) and it still plays in a normal video player.
- [ ] If the phone has an AV1-recorded video → sharing fails closed with "can't be shared without
      its location yet" rather than silently going out unstripped. (This app's `containerFor`
      deliberately excludes AV1 rather than guess at `MediaMuxer`'s own AV1-in-MP4 support level; a
      real device check may show AV1 is actually safe to add.)

## P0-06 — Zip export works and follows the share metadata policy

- [ ] Select 5 photos (1 HEIC) and 1 video → Zip → share to Files → the archive opens with 6
      entries and no GPS in any of them.
- [ ] The HEIC entry's extension actually matches its real (re-encoded) content, and a zip tool
      reports no compression (STORED-equivalent size) on the entries, not the smaller size a normal
      DEFLATE archive would show.

## P0-07 — Edited copies keep their metadata; saves land in allowed folders

- [ ] Edit a geotagged camera photo (GPS, date, Make/Model, any existing XMP keywords) and Save a
      copy → the copy sits next to the original in the timeline with the same camera, date and
      location, and its `Orientation` reads normal even if the original camera JPEG's did not.
- [ ] Edit a WhatsApp image (`Android/media/com.whatsapp/...`) and Save → the copy lands in
      `Pictures/Foto Xplorr/`, not alongside the WhatsApp original (which the save would otherwise
      fail into).
- [ ] "Replace original" on a photo, then reopen it → metadata and date are unchanged from before
      the edit.
- [ ] Convert or edit a video → its `RELATIVE_PATH` lands correctly for a `DCIM`/`Movies`/`Pictures`
      source vs. falls back correctly for a `Download` source, and its date-taken matches the
      source.

  Sandbox coverage: none of the actual MediaStore insert/`IS_PENDING`/rescan behaviour, or whether a
  real device's own MediaStore genuinely re-derives `DATE_TAKEN` from EXIF on rescan (the reason
  `save()` re-asserts it), can be confirmed without a device.

## P0-08 — Safe metadata writes

- [ ] Turn OFF "Allow access to photo locations" (revoke `ACCESS_MEDIA_LOCATION`) and try to edit a
      caption on any photo → refused with "Allow access to photo locations to edit metadata
      safely", never a consent screen. Turn it back on and retry → succeeds.
- [ ] Add a star rating and a few non-English keywords ("café", "北京") to a Lightroom-exported JPEG
      that already carries its own real UTF-8 XMP keywords → every existing keyword survives
      exactly, unmangled, alongside the new rating. (`Utf8XmpPreservationTest` proves the decode fix
      against a hand-built packet in this sandbox; a real Lightroom/Capture One export may shape its
      XMP differently.)
- [ ] Open a HEIC or RAW photo's metadata controls → disabled with "Foto Xplorr can't write
      metadata into HEIC files yet" the moment the photo is opened, not after tapping a field.
- [ ] Edit a geotagged photo's caption, then immediately (before the consent screen or write
      finishes) edit its rating too → confirm BOTH land, not just the second one. This is the one
      behaviour no Robolectric test in this sandbox can exercise, since it needs the real, timed
      consent-screen round trip `PhotoDetailRoom`'s two independent field commits race against.

## P0-09 — Scanning and app state survive the Activity

- [ ] Open a photo in the viewer and rotate the phone → the photo stays open, with no "Indexing"
      banner reappearing.
- [ ] Cold-start the app with a 20k+ item library → one full scan runs once, then only deltas for
      the rest of the session (watch for a SECOND full-progress sweep from 0, which would mean
      `ensureInitialScan`'s once-per-process guard failed).
- [ ] Plug in a USB keyboard or hub while viewing a photo → nothing resets or reopens the grid.
- [ ] With "Don't keep activities" enabled in Developer Options, open a photo, background the app,
      and let the process die, then return to it → the same photo is still open (`rememberSaveable`
      selection/viewer-list survives; `configChanges` alone does not cover this case, only
      rotation/fold/keyboard/density do).

  Sandbox coverage: none of process death, a real 20k+ item MediaStore library, or real USB
  keyboard/hub attach events can be produced here.

## P0-10 — Audio permission requested separately, only when needed

- [ ] Android 13+ → open Audio with no prior grant → "Allow access to audio" with a button, not the
      empty "No audio files found" state, and no permission dialog until the button is tapped. Tap
      it, grant → voice recordings, podcasts and music all appear, not just files tagged as music; a
      stock ringtone or notification tone must NOT appear.
- [ ] Deny instead → the same prompt reappears on returning to Audio, and the photo/video gallery is
      completely unaffected (still shows whatever it already had permission to show).
- [ ] On Android 12 and below, opening Audio for the first time → the library shows directly with no
      separate prompt at all, since `READ_EXTERNAL_STORAGE` was already granted by the photo/video
      flow.

  Sandbox coverage: none of an actual `READ_MEDIA_AUDIO` grant dialog, a real device's mixed
  ringtone/recording/music library, or the pre-13 code path (this sandbox's one cached Robolectric
  framework jar is API 34 only) can be produced here.

## P0-11 — Video transcode correctness

- [ ] Convert a portrait phone video to MP4 → the exported file plays upright, not sideways.
- [ ] Convert a 3-minute 4K clip → completes without an OOM crash or a "not enough space" refusal on
      a device with reasonable free space; filling the device's free space first (or a very
      large/long source) should instead show "Not enough free space" BEFORE any progress UI
      appears, not partway through.
- [ ] Export at 1.5× speed with sound → the exported audio actually plays back faster and
      higher-pitched, not silent, corrupted or a failed export.
- [ ] Convert an HDR source (an HDR10 phone recording, or a downloaded HDR clip) → the app refuses
      up front with "HDR video conversion isn't supported yet" rather than producing a washed-out or
      clipped SDR file.
- [ ] Convert an HE-AAC or parametric-stereo source's audio (uncommon on a phone recording, common
      on some downloaded/transcoded web video) → the exported AAC audio is not garbled or the wrong
      pitch.

  Sandbox coverage: none of these can be produced in this device-less sandbox (confirmed via `adb
  devices`/no `emulator` package/no `androidTest` source set, per ADR-008's own standing caveat) —
  every fix here is verified by unit-testing the pure logic pulled out of the real `MediaCodec`/
  `MediaExtractor`/`MediaMuxer`/`EGL14` call sites, never by an actual transcode running end to end.

## P0-12 — Recognition: bound retries, stop the full reloads

- [ ] Put a 0-byte `.jpg` in Pictures and run recognition twice → the file is attempted at most 3
      times in total, never retried forever.
- [ ] During a recognition pass over a real library, the grid does not stutter (previously
      `RecognitionStore` republished the whole recognition index after every 24-photo batch).

## P0-13 — Duplicates album keeps one of each

No device-only concern identified for this item — the keeper-selection rule is pure logic, fully
covered by `GalleryProjectionV2Test`/`ArchiveAdvisorTest`, and the `smart:DUPLICATES` golden pins
the exact membership change.

## P0-14 — Real animation detection; the viewer animates

- [ ] A real GIF and a real animated WebP both play (not a frozen first frame) in the full-screen
      viewer, regardless of the "Loop animations" setting.
- [ ] A static WebP does NOT appear in the Animated album or match `is:animated`; an APNG with a
      real `acTL` chunk DOES appear in both.
- [ ] With "Loop animations" off, the grid never decodes a static WebP/AVIF/PNG through the animated
      path (previously every one of them did, regardless of whether it actually animated) —
      observable as reduced jank scrolling a grid with many static WebP/AVIF thumbnails.
- [ ] A freshly imported animated file may briefly show as non-animated (its frame frozen in the
      viewer, absent from the Animated album, framable in a share) until the next background scan's
      own sniff pass catches up — confirm this window closes on the next scan, not left open
      indefinitely.

## P0-15 — `GridIndexMap`: scrubber correct with headers, hidden in masonry

- [ ] Timeline with day headers → scrub to a month in 2023 → the grid lands on that month, and the
      pill shows that month.

## P0-16 — Search and label fixes

No device-only concern identified for this item — every fix (byte-size suffix parsing, RAW
extension classification, camera:/iso: neutrality, archived/screenshot categories, the HDR badge,
the Kind label) is pure logic or XMP-namespace parsing, fully covered by
`DetailFormattingTest`/`XmpPacketTest`/`MediaFormatTest`/`SearchQueryTest`/`GalleryProjectionV2Test`.

## P0-17 — Migrate V1 catalog data; remove dead Java

No device-only concern identified for this item — the migration reads and writes
`SharedPreferences` directly and is fully covered by `LegacyCatalogMigrationTest`, including a
genuine second-process-start scenario.

## P0-18 — "Open with" (`ACTION_VIEW`)

- [ ] In the Files app, browse to a JPEG on external storage (e.g. a USB drive), tap it, and choose
      "Open with Foto Xplorr" from the system chooser — confirms the app is actually offered by the
      OS, not just that its manifest declares an intent filter. The photo opens full-screen,
      zooms/pans, Share and Open-with both work, and Edit/favourite/sensitive/trash/tags/caption/
      location are all absent from the UI entirely, not merely disabled.
- [ ] Open a photo that is ALREADY in the library from another app (a shared link, or "Open with" a
      file the gallery already indexed) → it shows its real favourite/tag/caption state
      immediately, proving the redirect-to-`FotoXplorrActivity` path actually re-selects the same
      catalogued asset rather than opening a lookalike, uncatalogued duplicate of it.

  Sandbox coverage: neither the OS's own real chooser UI, nor a genuine already-catalogued external
  open (as opposed to this sandbox's unit test of the same `awaitLoaded()`/`LaunchedEffect(assets)`
  redirect logic), can be produced here.
