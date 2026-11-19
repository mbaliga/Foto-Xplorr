# Batch 11 device test checklist

Every device-only claim from batch 11 (Phase 3 wave A, every storage location), grouped by area. The areas are: adding
and walking a folder or drive, the merged gallery and its protection, the Sources screen and index job, offline previews,
drive plug and unplug and the storage panel, the file-ops engine, camera ingest, share-receive, and CI. See
`docs/handoff/MASTER-PROGRESS.md` (Batch 11) and TRAPS #153 to #162.
**Nothing in this batch has run on a device. Every line is unverified until you tick it.** Roborazzi runs in record mode,
so no screenshot test proves a look.

Suggested devices:
- an API 26 or 27 phone (no `RELATIVE_PATH` before 29, USB and mount broadcasts instead of the volume callback);
- an API 29 phone (the picker intent for a volume, `ImageDecoder` on content uris);
- a current phone (Android 14 or newer, including partial media access);
- a phone with an SD slot, and one with USB-C host mode.

Prepare:
- a USB stick or SD card, formatted FAT32 or exFAT, with DCIM-style folders, about 20,000 photos in total (a second, smaller
  one with the same label but a different UUID is useful), plus a few HEIC, RAW, TIFF and video files, a `.nomedia`
  folder, a folder named `Android`, and a file named `x.jpg` that is really a PNG;
- a copy of one library photo on the card (same name and size), and a locked folder and a sensitive photo whose copies are
  also on the card;
- a folder in internal storage as a second source, and a folder in a cloud provider app (Drive, OneDrive or Dropbox) through
  the system picker;
- an app that can share several photos at once (Files, Photos, Chrome), 2 photos, 1 video and about 500 photos in one share;
- a camera or phone that offers PTP/MTP over USB with 500 photos, and a card reader with an SD card of camera files;
- a library of 10,000 or more photos.

## 1. Adding and walking a folder or drive
- [ ] **Real providers.** Add a folder on external storage, a USB stick and a cloud provider folder (Drive, OneDrive,
      Dropbox). Each walks to the end and lists photos; note EXTRA_LOADING and EXTRA_ERROR behaviour and whether the
      retry timings feel right. (unverified)
- [ ] **Persistable grant.** `OpenDocumentTree` returns a persistable read grant on every API level tried, and the folder is
      still readable after a reboot. (unverified)
- [ ] **50k files.** A tree of about 50,000 files walks to the end. Note the total time, the tile speed in the grid on first
      scroll over USB, and that the screen stays responsive. (unverified)
- [ ] **Unmounted drive.** Pull the stick while it is listed, then open the Sources screen: the source is Offline, not Error.
      Note which exception the platform throws (`FileNotFoundException` and `IllegalArgumentException` are mapped to
      unreachable without being seen). (unverified)
- [ ] **Revoke.** Revoke the grant from system settings, and revoke it by removing the source and re-adding: the source
      shows Revoked, keeps its rows, and a new grant lifts it. Whether `releasePersistableUriPermission` behaves as
      expected on a read-only grant. (unverified)
- [ ] **Grant cap.** Add several sources next to backup, sync and sidecar folders: the app refuses a new source with a plain
      sentence near the cap, a single-document grant counts against the cap, and the real limit (128 before Android 11,
      512 after) matches. (unverified)
- [ ] **Ignore rules.** The `.nomedia` folder and its children, `Android/` at the root, `.fotoz-trash`, `._*` files and hidden
      folders do not appear; `Android/` below the root does. (unverified)
- [ ] **Sniffing.** The PNG named `x.jpg` is drawn correctly, a renamed RAW and an extension-less file are recognised, and
      a TIFF-based RAW (NEF, ARW, DNG) stays a RAW. Cloud-backed files are listed but not downloaded just to be sniffed.
      (unverified)
- [ ] **Head size.** 1 MiB is enough for the EXIF block of HEIC and RAW files from the card; the sizes and dates appear
      after the sizing pass. Video size and duration come from `MediaMetadataRetriever` on a SAF uri, and the
      `decodeUpright` 64 px fallback measures a RAW from a SAF uri. (unverified)
- [ ] **Resume.** Kill the app mid-walk, or pull the drive mid-walk, and reopen: the walk resumes from its checkpoint and
      ends with the same set as an uninterrupted one. A cursor of about 600k characters reads back through a real Android
      cursor window. (unverified)
- [ ] **Bundled driver.** The first Add creates `fotoz-sources.db` on the bundled SQLite driver on a device (the JVM tests use
      the Android driver under Robolectric). (unverified)
- [ ] **Remove.** Remove a source on a real drive: the drive's files are untouched, the grant is released only if this source
      took it, and a folder also used by backup or sync keeps its grant. (unverified)

## 2. The merged gallery and its protection
- [ ] **Merged grid.** A large library plus a source drive: the grid appears without a main-thread stall and the source photos
      arrive a beat later. Tiles and the viewer decode from real SAF providers, including `ImageDecoder` on API 29 and up.
      (unverified)
- [ ] **Unenriched tiles.** Before the sizing pass finishes, source tiles may look square; they settle after. Mixed-list
      layout looks right. (unverified)
- [ ] **Offline or revoked source hidden.** An Offline source stays listed (greyed); a Revoked source is hidden from the
      merged gallery. (unverified)
- [ ] **Disabled rows.** Select a source photo: favourite, sensitive, archive, tag, collection, rename, move, trash and cull
      are disabled with "Not available for files from added folders yet.". The trash target is disabled with no sentence.
      A mixed selection acts on its library part and shows the notice. Check the look. (unverified)
- [ ] **Share and copy out.** Share, share with options, copy to folder, zip export and Save as new file work from a SAF
      uri, including a video. (unverified)
- [ ] **Locked and sensitive twins.** A locked, sensitive or archived photo whose copy is on the SD card or USB source never
      appears from the source, in the grid, map or calendar. An unprotected photo's SD twin still appears. (unverified)
- [ ] **Duplicate on SD.** A source on SD or USB that MediaStore also lists is shown twice for an unprotected photo, since
      the library side treats its volume as primary. Note how often this happens. (unverified)
- [ ] **Locked source folder.** A source folder locked from Albums with the existing folder lock appears in no list until
      unlocked. (unverified)
- [ ] **Sync feed.** Settings > Services > Sync with a source added: no `~src` folders in the folder choices, and nothing from
      a drive uploads. (unverified)
- [ ] **Map and Tidy up.** Opening the map with a source added reads no file of the drive, and Tidy up never lists a source
      photo. (unverified)
- [ ] **Viewer restore.** Kill the app with a source photo open in the viewer, then relaunch: it reopens on that photo once
      the sources have loaded. (unverified)
- [ ] **No media access.** With media access denied and a source present, the grid shows the source and a strip appears
      ("Choose media", or "Open app settings" when permanently denied) with a 48 dp target. On Android 14 partial media
      access plus a folder source: nothing is lost. (unverified)

## 3. The Sources screen and the index job
- [ ] **Library tab row.** Settings > Library > Folders and drives opens the pane; check colours, state chips (glyph and word),
      touch sizes and the empty state. (unverified)
- [ ] **Job in the shade.** An index appears in the activity shade and the foreground service with progress and Stop;
      Rescan resumes from the checkpoint. (unverified)
- [ ] **Index on connect.** With the per-source switch on, the read starts on connect under the user's rules (charging
      default). (unverified)
- [ ] **Duplicate and nested.** Adding the same tree twice, a tree inside a source, or a tree containing a source is refused
      with a short sentence. (unverified)
- [ ] **Show in gallery.** The Show in gallery switch removes and restores a source's photos in the grid. (unverified)

## 4. Offline previews
- [ ] **Greyed tiles.** Unplug a drive with the grid open: its tiles turn grey with a cloud-off glyph, and "Plug in the drive."
      shows on tiles of 140 dp or more; plug it in and they return. Check the grey, the glyph size and the cue text.
      (unverified)
- [ ] **No preview stored.** A photo never previewed shows a labelled neutral tile, never a blank. (unverified)
- [ ] **Mid-scroll yank.** Pull the drive while scrolling: tiles with a stored preview fall back to it, and whether Coil's
      `onError` fires quickly enough. (unverified)
- [ ] **Cost and speed.** The real WebP size per 256 px tile (the 1 GB cap is about 40,000 previews at an assumed 25 KB),
      the time to build previews on a slow USB provider, and that the background pass runs without scroll jank or heavy
      battery use on a drive of about 20,000 photos (2 s debounce, 1.5 s pause). (unverified)
- [ ] **Pass is cancelled.** Unplugging mid-pass cancels it cleanly. (unverified)
- [ ] **Keep and space controls.** The Keep previews switch and the space choice (Off, 250 MB, 1 GB, 2 GB) wrap and look right
      at narrow widths, the selected chip is legible, and TalkBack reads the chips and the disabled switch. Choosing Off
      evicts the previews and the storage figure updates. (unverified)
- [ ] **Clear and remove.** Clear previews deletes only the previews; removing a source frees its
      `noBackupFilesDir/source-previews/<row>` folder. (unverified)
- [ ] **Backup exclusion.** `noBackupFilesDir` really is excluded from device backup. (unverified)
- [ ] **Locked photos.** A locked or sensitive source photo has no preview file anywhere. (unverified)

## 5. Drive lifecycle and the storage panel
- [ ] **Volume events.** The "Drive connected" strip appears on USB and SD insert and removal on Android 11 to 16 (volume
      callback), and on Android 8 to 10 through the mount and USB broadcasts. Rapid plug and unplug does not break it.
      (unverified)
- [ ] **Offer.** An unknown drive offers Add, Not now and Never for this drive, with no scan and no permission prompt until
      Add. Add opens the picker on the drive (`EXTRA_INITIAL_URI` is only a hint), and a volume-root pick is named after the
      drive. Not now returns on the next plug; Never survives an app restart and Ask again re-offers it. (unverified)
- [ ] **Replug.** The same drive returns: nothing is offered or duplicated, the source goes Online with no rescan unless index
      on connect is on, and photos keep their ids. A different drive with the same label gets a new offer. (unverified)
- [ ] **Unplug mid-read.** A pull during a read stops it and shows the source Offline; nothing is deleted. (unverified)
- [ ] **Strip look.** The strip draws over the gallery, viewer and editor with edge-to-edge insets, and does not block touches
      elsewhere. If the activity is recreated while the picker is open, that counts as Not now. TalkBack reads the card as
      one group. (unverified)
- [ ] **Storage panel.** Online or offline word and glyph, files, bytes, preview cost, last read and errors read correctly,
      totals add up, "Protected on N places" shows the placeholder line, and the ignored-drives list works. (unverified)
- [ ] **Drive preview policy.** Only the default (As viewed) can be tested: it keeps previews off for a drive source on
      first reconnect. The All and None policies cannot be set on a device yet, because no screen for them is built. (unverified)
- [ ] **Empty phone.** Behaviour on a phone with no `fotoz-sources.db`: no file is created and no strip appears for the
      internal volume. (unverified)

## 6. File-ops engine
- [ ] **Real file system.** When the Android `FileSystem` seam exists, run it against a real USB or SD tree: copy, move,
      rename and trash; a provider that renames on a clash is detected; a pulled drive surfaces as `DEVICE_GONE` and not a
      generic I/O error. (unverified)
- [ ] **Yank test.** Pull the drive mid-copy of a large video: no partial file under a final name, the next run finishes only
      the remaining work, and no original is lost. (unverified)
- [ ] **Case-only rename.** A rename from `IMG_1.jpg` to `img_1.jpg` on a real FAT or exFAT SAF volume works through the
      `.fotoz-case` hop and a provider's rename semantics do not break it. (unverified)
- [ ] **Trash visibility.** `.fotoz-trash` with `.nomedia` is not shown in the system gallery or other apps, and the file
      manager behaviour is acceptable. (unverified)
- [ ] **Journal append.** `openAppend` ("wa") works for the journal in the app's internal storage. (unverified)
- [ ] **Throughput.** Streaming speed and memory on large videos. (unverified)

## 7. Camera ingest
- [ ] **Card as a tree.** A real camera card as an SAF tree with an `IngestSource`: list, open and delete. (unverified)
- [ ] **MTP and PTP.** `android.mtp.MtpDevice` with 500 photos: object handles as stable ids, capture time and size from
      `getObjectInfo`, whether the camera allows delete, and the USB permission prompts. (unverified)
- [ ] **Speed.** The 128 KiB-per-file fingerprint pass over MTP, and the delete-from-card re-read of the whole card file and
      every copy (slow for large files). (unverified)
- [ ] **Pull mid-copy.** Pulling the card or cable mid-copy and resuming copies only the rest, and a second card with the
      same item ids is not skipped or deleted. (unverified)
- [ ] **FAT names.** FAT and exFAT case-insensitivity through the real `IngestTarget`, and disk-full behaviour. (unverified)

## 8. Share-receive
- [ ] **Chooser.** Share 2 or more photos from Files, Photos and Chrome: Fotoz is listed once for the multi-share, and the
      sheet looks right with View first. (unverified)
- [ ] **View.** View opens a read-only swipe run and writes nothing; PiP and video playback work in the receive viewer.
      (unverified)
- [ ] **Save to library.** Save writes new files into `Pictures/Fotoz` and `Movies/Fotoz` (videos are accepted there), with
      unique names, and the originals are untouched. Cancel leaves no pending entry. (unverified)
- [ ] **Old Android.** On API 26 to 28 files land in the root of Pictures and Movies, and a kill mid-copy leaves a truncated
      file this code cannot remove. (unverified)
- [ ] **Big share.** A share of about 500 items: memory and time; more than 500 keeps the first 500 and says so. (unverified)
- [ ] **Hidden library photos.** Sharing from another gallery app's `content://media` uris: hidden or locked photos are
      refused, visible ones open and save. (unverified)
- [ ] **TalkBack.** TalkBack reads the title, the progress and the result, and the swipe run pages correctly. (unverified)
- [ ] **Single share.** A single shared photo still opens the main viewer (no sheet), as before. (unverified)

## 9. CI
- [ ] **Green run.** The PR's CI is green after these commits, including `core-native` (the Native compile of the file-ops,
      ingest and walker code, never run locally), `lintOfflineDebug` and the full unit suite for both flavors.
      (unverified)
- [ ] **Flaky test.** `SyncEngineTest` "the run reports paused when cancelled" passes in CI; it failed once in a full run and
      passed alone. (unverified)

## 10. Left-room card pager (follow-up, from the transition video)
- [ ] **The column moves with the wheel.** Open the room and turn the words slowly: the big card slides up (or down) and out
      while the next one slides into the centre, in step with the finger, and reverses when you turn back. (unverified)
- [ ] **No jump at commit.** Pause on a new word (the card's content swaps after about 140 ms): nothing moves or flashes at
      that moment, the old card simply becomes the neighbour at the same place. (unverified)
- [ ] **Neighbours peek.** At rest the previous card shows in the strip above and the next below (about 7% of the height
      each), clipped by the window, with the gap between them. (unverified)
- [ ] **The ends.** On the first word there is nothing above; on the last nothing below; the wheel does not loop. (unverified)
- [ ] **Covers.** Each peeking card shows that destination's newest photo; Protected, Experimental and Map show a soft tint;
      a sensitive or locked photo never appears, also after marking one sensitive while the room is open. (unverified)
- [ ] **Reduced motion.** With animations off the big card does not slide; neighbours stand still. (unverified)
- [ ] **Drag and tap.** The big card still takes a tap (closes the room) after the column has moved, and a drag from its edge
      still closes it. TalkBack does not read the ghosts. (unverified)
- [ ] **Cost.** Turning the wheel stays smooth with 20,000 photos; opening the room does not stall. (unverified)

## 11. Selection chrome (the owner's 2026-11-18 reference)
- [ ] **Matches the reference.** On a real phone, long-press a photo and select many (select all): the black tab at the top
      left holds zip, move and copy; the black bar at the top right reads the count large and "SELECTED" smaller and grey; the
      pure red tab rises from the bottom edge near the right with the trash glyph. Put a screenshot beside the reference: same
      shapes, the tab's rounded bottom corners, the gap between tab and bar, the bar's length. (unverified)
- [ ] **Tiles.** Selected tiles show no mark; tiles not selected are dimmed; tapping a dimmed tile brightens it. (unverified)
- [ ] **48 dp targets.** Each tab glyph, the count bar (clears the selection) and the trash take a tap anywhere in a 48 dp
      square; no mis-taps on the neighbouring glyph. (unverified)
- [ ] **Font scale 2.** With system font size at its largest, select 9999 (or as many as there are): the count and
      "SELECTED" stay on one line inside the bar, the bar never covers the copy glyph. (unverified)
- [ ] **Cutout and status bar.** On a phone with a camera cutout or a tall status bar, the tab and the bar are not hidden
      under it and are not floating far below it; landscape too. (unverified)
- [ ] **RTL.** In an RTL language the chrome keeps the same left-to-right layout and the count text reads correctly. (unverified)
- [ ] **TalkBack.** A tile reads "Selected" or "Not selected" while selecting. (unverified)

## 12. Long-press preview window (the owner's 2026-11-19 reference)
- [ ] **Default mode.** Settings, Long press does = Quick preview, Preview window = "Closes when you let go" (the default on
      a fresh install and after an update). Hold a photo: the preview shows with no tab and no ✕; lift: it closes. (unverified)
- [ ] **Stays-open mode matches the reference.** Switch to "Stays open", hold a photo and lift: the window stays up; black
      frame, rounded outer corners, the tab top left with zip, move and copy, the square ✕ tab bottom right, concave fillets
      where the tabs meet the frame. Put a screenshot beside the reference. (unverified)
- [ ] **Release does not tap through.** Lifting the finger that opened the window does not open the viewer, select the
      tile, or press an icon. (unverified)
- [ ] **Closing.** The ✕, Back (gesture and button), and a tap outside the card each close the window; a tap on the photo
      itself does not. (unverified)
- [ ] **Icons act on the previewed item.** Zip exports only that item, move and copy open the folder picker for only that
      item, and the window closes after each. On a source file the move icon is absent. In the Trash no icons show. (unverified)
- [ ] **Video.** Hold a video in both modes: the preview shows it and the icons act on the video. (unverified)
- [ ] **Sensitive item.** A sensitive photo in the browsable list opens blurred in the window; a hidden one never opens. (unverified)
- [ ] **TalkBack.** Long-click (double tap and hold) opens the window in stays-open mode; the ✕ reads "Close preview" and the
      icons read "Export as zip", "Move to folder", "Copy to folder". (unverified)
- [ ] **Font scale 2 and 48 dp targets.** At the largest font size the Settings row and its chips fit; each icon and the ✕
      take a tap anywhere in a 48 dp square. Landscape and a tablet too (window capped at 520 dp). (unverified)
