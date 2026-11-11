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
      `Pictures/Fotoz/` (builds before the rename saved to `Pictures/Foto Xplorr/`), not alongside
      the WhatsApp original (which the save would otherwise fail into).
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
- [ ] Open a HEIC or RAW photo's metadata controls → disabled with "Fotoz can't write
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

## P0-10 — OBSOLETE: Audio permission requested separately (audio was removed)

**Retired 21 Oct 2026 (batch 2).** Fotoz no longer has an Audio destination, library or player, and
`READ_MEDIA_AUDIO` is not in the manifest, so the three checks that used to be here (the "Allow access
to audio" prompt, deny and re-prompt, the pre-Android-13 path) have nothing to test. What replaces
them is in `docs/device-test/batch-2-checklist.md` ("Audio is gone"). Do not tick the old boxes; they
are kept out of this file on purpose.

## P0-11 — Video conversion correctness (rewritten 21 Oct 2026 for the Media3 pipeline)

This section first described the hand-written `MediaCodec` pipeline (HDR refused up front, a "Not
enough free space" check before progress, HE-AAC audio conversion). That pipeline is deleted
(`docs/adr/ADR-008-video-transcode-pipeline.md`, revision 2) and so is audio conversion. The checks
below are for what exists now: Media3 Transformer exports through `VideoExporter`, published to
MediaStore with `IS_PENDING`. None of this has run on hardware.

- [ ] Convert a portrait phone video to MP4 → the exported file plays upright, not sideways, in the
      gallery and in another app.
- [ ] Convert a 3-minute 4K clip → completes without an out-of-memory crash, with progress in the
      activity strip, on a device with reasonable free space. (There is no up-front free-space
      refusal any more; a full disk shows up as a failed export. See the next item.)
- [ ] **Fill the device's storage** during a long export → the export fails with a message, and
      afterwards there is **no partial file and no pending (`IS_PENDING`) MediaStore row** left in
      Files or in another gallery. (The Robolectric test only proves the delete call after a mid-copy
      read failure.)
- [ ] A successful export **appears in the gallery** after it finishes (the `IS_PENDING` to 0
      promotion is not unit-tested), in the right folder (`DCIM`/`Movies`/`Pictures` source keeps its
      folder; a `Download` source lands in `Movies/Fotoz/`; a pre-rename `Movies/Foto Xplorr/` source
      stays there) and with the source's date taken.
- [ ] Cancel mid-export → nothing is left behind (no cache file the user can see, no row).
- [ ] Export at 1.5x speed with sound → the audio plays back faster with its pitch preserved (Sonic),
      not silent, garbled or a failed export; mute removes the audio track.
- [ ] Convert an HDR source (an HDR10 or HLG phone recording) → it is **tone-mapped to SDR** (the
      mode is always requested) and the result is LOOKED AT: not washed out, not clipped. On a device
      that cannot tone-map, the export fails with "This device could not process this video (it may be
      HDR content this device cannot tone-map)." rather than producing a wrong-looking file.
- [ ] A source the device cannot decode, and a target codec the device cannot encode → the specific
      messages ("This device cannot decode this video's format." / "...cannot encode the exported
      video."), never a generic crash.

  Sandbox coverage: the error wording, the HDR mode and the publish step's insert and cleanup are
  pinned by `VideoExporterMessageTest` and `VideoExporterPublishTest`; the plan resolution by
  `ResolvedExportPlanTest` and `VideoExportOptionsTest`. No transcode has run end to end in this
  device-less sandbox (per ADR-008's standing caveat).

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

## P0-18 — "Open with" (`ACTION_VIEW`), rewritten 21 Oct 2026

`ExternalViewerActivity` is the single VIEW target (`content://` only). `FotoXplorrActivity` receives
EDIT (`image/*`) and SEND (`image/*`, `video/*`) only. The old `file://` and audio cases are gone.

- [ ] In the Files app, browse to a JPEG on external storage (e.g. a USB drive), tap it, and open it
      with Fotoz from the system chooser. **Fotoz is listed once**, not twice. The photo opens
      full-screen, zooms/pans, Share and Open-with both work, and the favourite heart,
      tags/caption/location and the other library actions are absent from the UI entirely, not
      merely disabled. A video opens the same way.
- [ ] Open a photo that is ALREADY in the library from another app (the Files app, or the gallery's
      own picker) → it opens in the full in-app viewer showing its real favourite/tag/caption state,
      proving the redirect-to-`FotoXplorrActivity` path re-selects the same catalogued asset rather
      than a lookalike, uncatalogued duplicate.
- [ ] **A hidden photo is never opened from outside.** From the Files app or another app's picker, open
      a photo in a locked folder (still locked), an archived photo, a trashed photo, and a sensitive
      photo with "Hide sensitive" on → each shows the notice "That photo is hidden or locked in Fotoz,
      so it was not opened." and the gallery; never the viewer. Unlock the folder in the app and repeat
      → it now opens.
- [ ] The same for a photo saved a moment ago into a locked folder (inside the ~0.8 s scan debounce).
- [ ] A photo-picker Uri (pick through the system photo picker into a VIEW) opens as an external file,
      not as the wrong library photo.
- [ ] SEND a photo, and a video, to Fotoz (and a multi-select SEND) → it opens in the viewer as an
      external asset (no tag/caption/location rows). SEND of a hidden catalogued photo shows the notice.
      EDIT of an image from another app opens the editor.
- [ ] The in-app "Open with" chooser does not list Fotoz itself.
- [ ] Android 14 "selected photos" access: open a `content://media` photo you did not grant → it is
      refused with "could not be checked against your library" rather than opened (a deliberate
      trade; judge whether the notice is acceptable).
- [ ] Hostile intent from `adb` (a custom Parcelable extra Fotoz cannot load, with and without
      `EXTRA_OPEN_MEDIA_ID`), including relaunching the task from Recents → no crash; the intent is
      treated as extra-less.
- [ ] Process death: open a photo from an unlocked folder, background the app, `adb shell am kill
      com.fotoxplorr.app`, return → the viewer and editor do NOT reopen on a locked photo; a normal
      photo reopens with its saved run.

  Sandbox coverage: the pure classification and lookups (`classifyIncomingIntent`,
  `classifyMediaStoreUri`, `lookupOpenRequest`), the manifest routing
  (`ExternalViewerActivityManifestTest`) and the guard branches are unit-tested; the OS's real chooser
  and a genuine cross-app grant cannot be produced here.

## P0-20 (stretch) — Act as a photo picker

- [ ] From a second real app (or a generic file-manager/picker test app), issue `ACTION_GET_CONTENT`
      for `image/*` → Fotoz is offered in the system chooser, opens to a grid of only
      browsable photos (nothing locked, archived or sensitive), and tapping one returns control to
      the caller with a usable `content://` uri.
- [ ] The calling app can actually read the bytes behind the returned uri (`openInputStream` or
      equivalent) — the real test of whether `FLAG_GRANT_READ_URI_PERMISSION` genuinely grants
      cross-process access, not just whether it was set on the result Intent.
- [ ] Repeat with `ACTION_PICK` in place of `ACTION_GET_CONTENT` → same behaviour.
- [ ] Repeat with `EXTRA_ALLOW_MULTIPLE` set → the "Add (N)" multi-select flow returns every
      selected uri via `ClipData`, and the caller can read all of them, not just the first.
- [ ] Deny the media permission when the picker first launches (a cold process, never opened by the
      user directly before) → a clear "Grant access" prompt, never a crash; granting afterward
      shows the grid.

  Sandbox coverage: none of a second real app, a real system chooser, or a real cross-process
  URI-permission grant can be produced here — the manifest routing (`PhotoPickerActivityManifestTest`)
  and the pure mime-scope logic (`PhotoPickerMimeMatchingTest`) are unit-tested, but the actual
  grant mechanism this whole feature exists to provide is not.
