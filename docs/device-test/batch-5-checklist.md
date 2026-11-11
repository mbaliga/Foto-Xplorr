# Batch 5 device test checklist

Every device-only claim from the fifth owner-feedback batch (item **#7**, backup; see
`docs/handoff/MASTER-PROGRESS.md`), in the same shape as `batch-3-checklist.md`. This sandbox has no
device or emulator, so **none of these is claimed as verified**: each section says what the JVM and
Robolectric tests cover, and the boxes are for what those cannot see (a real storage-access provider,
a real card, process death, a real notification, a real finger, a real phone's PBKDF2). Check each box on
a real phone, on **both flavours** where a line says so, before relying on the corresponding change.

This file is for the **write side** (unit 14: the encrypted backup, its check, and the plain
tags-and-collections file's new entry points) and, in section 13, the **restore side** (unit 15).

What is being tested: Settings > Data now has two named options. **Tags and collections** is the
existing plain JSON file (not encrypted, merge by default, a confirmed Replace, an identity block so
another phone cannot attach tags to the wrong photos). **Everything, encrypted** writes a folder of
encrypted packs and manifests that only the passphrase opens, with **no recovery of any kind**. Both
need a real phone, because the interesting failures are the folder provider's, the card's and the OS's.

Suggested devices: a current phone on Android 15 or 16, one on Android 13 or 14, one on Android 8.x if
you can get it (minSdk 26), and, for the folder lines, **an SD card formatted FAT32**, **an exFAT
USB-OTG stick**, the phone's own Downloads folder, and **a folder served by a cloud-storage app** (any
provider with a "Files" document provider). Use a prepared library, not your real one, for the first
pass: about **200 mixed photos and videos** (some with GPS, some in a protected folder, some marked
sensitive, one archived, one in the bin, one video over 1 GB, one edited-but-unsaved photo, one manual map
pin), then a second pass with **a library of several GB** for the throughput and screen-off lines.

## 0. Before anything else: the passphrase key must come out the same on this phone

Sandbox coverage: `BackupFormatTest` (the golden vector, on the JDK's PBKDF2), `BackupStreamsTest`,
`BackupGoldenVectorTest` (the self-check passes on the JVM, and each broken cipher is a failed check).
The vector itself is run by `BackupGoldenVector.run()` as a self-check at the start of the first backup,
check or restore after each launch. It proves three things on the phone: PBKDF2 gives the standard key,
the cipher opens a known encrypted file to its known text (the decrypt side, which is the part a release
build could break), and what it seals opens again while a copy with one bit changed does not. On a key
mismatch Fotoz says "This phone computes the passphrase key differently from the standard ... Fotoz will
not start one"; on a cipher failure "Encryption did not pass Fotoz's self-test on this phone ... will not
start one". Either way it writes nothing.

- [ ] On **every** device in the list, start "Back up everything, encrypted" and reach the passphrase
      dialog and then the preflight card. The self-check passing is the card appearing at all. If any
      device shows the "differently from the standard" message, **stop**: the non-ASCII passphrase
      encoding of that phone's PBKDF2 provider differs and a backup made there would not open elsewhere.
- [ ] Time the pause between tapping Continue and the next screen ("Working out the key from your
      passphrase") on the slowest phone you have. 600,000 rounds of PBKDF2; the guess was 1 to 3 s.
      Record the number. It must not freeze the UI (the dialog's spinner keeps turning).
- [ ] **Release build only** (`assembleOfflineRelease`, R8): make one backup and one check. R8 ran over
      the Tink code and an R8-minified harness on the JVM round-tripped all three file kinds, flipped-bit
      and wrong-passphrase included, but ART on a device is the real test.
- [ ] **One release-build backup, Check and restore on a device** (the whole round trip on the shrunk
      build, not a debug one): `assembleOfflineRelease`, install it, back up the prepared 200-photo library
      with a passphrase containing an accent and an emoji, run **Check a backup** (it must end "everything
      is intact"), then restore into a cleared phone (section 13) and compare a few photos byte for byte.
      Do the same on the Connect release build. If the backup is refused at the passphrase step with
      "Encryption did not pass Fotoz's self-test on this phone", the release build's cipher is broken on
      that device (R8 or the phone's AES-GCM provider): stop and note the device, the flavour and the Android
      version.

## 1. The two options are told apart, on the page

Sandbox coverage: `BackupSettingsRenderTest` (both section labels, the "not encrypted" line, the pinned
no-recovery sentence once on the page), `HelpCopyTest` (the sentence word for word, the ceilings).

- [ ] Settings > Data shows **BACKUP: TAGS AND COLLECTIONS** (Save / Restore, "Not encrypted. Anyone who
      gets the file can read your captions and tags.") and below it **BACKUP: EVERYTHING, ENCRYPTED**
      ("Adds what is new each time. Photos you delete from the phone stay in it." and "Fotoz never stores
      this passphrase. If you forget it, nobody, including us, can open the backup. There is no reset."),
      then **Back up everything, encrypted** and **Check a backup**. Nothing is cut off at 1.3x font size.
- [ ] TalkBack reads each row as one control with its caption.
- [ ] While a backup or a check is running, the **Back up everything, encrypted** row is greyed out and a
      second tap does nothing; tapping **Check a backup** then says "A backup is already running."
- [ ] Open the plain "Tags and collections" file and read it in a text editor: it **is** readable JSON
      with captions and tags (that is the point, and why it says it is not encrypted).

## 2. The first backup: passphrase and preflight

Sandbox coverage: `BackupCopyTest` (the card's every line, strength hints, the form's rules in order),
`BackupJobTest`, `BackupVerifierTest`.

- [ ] Tap **Back up everything, encrypted**, pick an empty folder (create one in the picker). The dialog
      is titled "Choose a passphrase", leads with the red no-recovery sentence, has a **Passphrase** and a
      **Passphrase again** field, a Show/Hide toggle, a strength line, and an "I understand: Fotoz cannot
      recover this backup without the passphrase" tick. **Continue stays disabled** until: 12 or more
      characters, the two fields match, and the tick is set. Eleven characters stay disabled; a mismatch
      says "The two passphrases are different."
- [ ] Type a passphrase with an accented letter and an emoji, show it, hide it. The field asks the
      keyboard for a password field with auto-correct off: no auto-correct underline, and the typed words
      should not turn up in the keyboard's suggestions afterwards (a keyboard may ignore the request; note
      which one did). (Type it composed on one try and decomposed on another keyboard later: it must
      open the same backup.)
- [ ] Rotate the phone with the dialog open: the dialog starts again, **empty** (the passphrase is never
      saved across a rotation, by design).
- [ ] The preflight card ("Back up to <folder>") states: the number of files and their size, the pack
      count and the encrypted size, free space (or "Fotoz can't tell how much room is left in this folder"
      for providers that do not say), the GPS line, "The backup only ever adds files ...", and "Included:
      photos and videos, including those in protected folders or marked sensitive ...". It states **no
      count of protected photos**.
- [ ] **GPS warning.** With the "Location in photos" (media location) permission **denied**, the card says
      "Photo locations will NOT be in this backup" in the warning colour with an **Allow reading photo
      locations** button; after allowing it (system dialog, or from system settings and back) the line turns
      into "Photo locations are included, encrypted with the rest." On Android 9 and below the line is not
      shown at all.
- [ ] Pick a folder **on the phone's own storage**: the card also says it is next to the photos and not a
      safe copy. Pick a folder on a card or stick: it does not.
- [ ] Pick a folder served by a cloud app: the card says another app manages the folder and that Fotoz
      itself never goes online.
- [ ] Start with the battery under 20% and not charging: the low-battery line shows. On the charger it
      does not.
- [ ] Cancel at the preflight card: nothing is written to the folder (check it in the Files app) and no
      notification appears.

## 3. What a finished backup looks like

- [ ] Start. A notification titled **Backing up your library** appears with a progress bar and a Cancel
      action. It contains **no file name and no number of photos**. The shade shows the green "Backing up"
      strip.
- [ ] When it finishes, a message reads like "Backed up 198 files (1.9 GB) in 2 packs." and says how many
      could not be copied, if any (the edited-while-copying case below).
- [ ] In the Files app the folder holds only `pack-0001.fzbp`, `pack-0001.fzbx`, ..., and `manifest-0001.fzbm`.
      File names, folder names and dates **do not appear anywhere in the folder** (open a pack with a hex
      viewer and search for a photo's name, "DCIM", "jpg": nothing). No pack is larger than about 1 GiB
      (a single longer video gets a pack of its own).
- [ ] Nothing was written to the phone's own storage besides the cache: no journal file in the app's
      files (debug build, `adb shell run-as <package> ls files`).
- [ ] The trashed photo is **not** in the backup; the protected, sensitive and archived photos **are** (use
      Check a backup, section 4, and, once the restore unit lands, restore them).

## 4. Check a backup

Sandbox coverage: `BackupVerifierTest` (a flipped bit in three places, a truncated pack, a deleted pack,
two packs swapped, a pack from another set, a forged file whose pack still authenticates, a wrong
passphrase, no manifest, that the check writes and deletes nothing).

- [ ] Tap **Check a backup**, pick the finished folder, enter the passphrase: a job "Checking your backup"
      runs (notification, no names) and ends with "Checked N files in P packs (X GB): everything is
      intact." The check reads the **whole** backup, so it takes about as long as the copy did.
- [ ] Enter a **wrong passphrase**: the form comes back with "That passphrase did not open the backup.
      Check it and try again, or the backup is damaged." and the folder is unchanged.
- [ ] **Tampered pack.** Copy the folder to a PC, change **one byte** in the middle of a pack with a hex
      editor, put it back: Check a backup lists the pack as damaged and names the files that could not be
      found, and says to back up again to a fresh folder.
- [ ] Truncate a pack by a few hundred bytes: reported (size and damage). Delete a pack: reported as
      missing, with its files. Swap two packs' contents: reported. Rename a pack: reported as missing.
- [ ] A check never writes, renames or deletes anything in the folder (compare the folder listing and
      timestamps before and after).

## 5. Destinations (the part only a device can test)

- [ ] **FAT32 card.** The 4 GB limit: with a library that makes one pack over 4 GB it must **not** be
      attempted: no pack is larger than 4 GiB minus 16 MiB. A video larger than that limit is **named on the
      preflight card as too large for a pack and left out**; the backup still finishes and says so.
- [ ] **exFAT stick** (USB-OTG): same run completes; unplug the stick mid-pack: the job fails with "The
      backup folder could not be written to ... Check that the card or drive is still connected", no crash,
      and replugging and running again resumes (section 7).
- [ ] **Downloads folder.** Works; the preflight shows free space.
- [ ] **A cloud-provider folder.** (a) The run **completes**, or fails with a plain sentence. (b) The
      provider does not rename the files (no ` (1)`, no `.bin`): if it does, the run stops with "The
      folder renamed a file Fotoz created, so it cannot be used for a backup" and leaves nothing behind.
      (c) Free space is "can't tell". (d) Check a backup after the provider has synced: still intact.
      (e) Note the throughput against a plain folder; a provider that is slow per file benefits from the
      packs (a few hundred files, not tens of thousands).
- [ ] A **non-empty folder** (other files in it): the backup uses it, touches none of the other files, and
      the other files are untouched afterwards.
- [ ] Pick a folder the system refuses to share (the root of the card on some versions): a plain message,
      no crash.

## 6. Low storage

- [ ] Fill the destination until about 200 MB remain, then back up a few GB. The job stops with "The backup
      folder is full. N packs (X) were saved before it stopped. Free some space. Run the backup again with
      the same folder and passphrase and it carries on where it stopped."; the half-written pack is gone
      (the folder has no pack without an `.fzbx` beside it); a manifest for the finished packs was written.
      Free space, run again with the same passphrase: it carries on, and Check a backup is clean.
- [ ] The preflight card's **Not enough room** line shows in red on a provider that reports free space,
      and Start still works (it backs up what fits).
- [ ] Phone storage nearly full: a backup to a card works (the app writes nothing big locally).

## 7. Cancel, resume and process death

Sandbox coverage: `BackupJobTest` (cancel leaves finished packs usable and deletes the half pack and
rethrows cancellation; a re-run copies only what is missing and never repeats a finished pack; with the
manifest taken away the indexes alone resume; an orphan pack is kept and its number is not reused).

- [ ] Start a backup of several GB and press **Cancel** in the notification half way: the notification goes
      away, the folder has only finished packs (each with its `.fzbx`) plus a manifest. Run again with the
      same folder and passphrase: the preflight says N files are already in the backup, only the rest is
      copied, and **no pack number is repeated**.
- [ ] **Process death mid-pack.** Start, and while it is writing the second or third pack kill the app
      (`adb shell am force-stop <package>`, or swipe it away). Relaunch, run again with the same folder and
      passphrase: the preflight shows "N leftover files (X) from an interrupted backup cannot be used"
      with **Remove the leftover files...**; the finished packs are recognised (resumed without a manifest),
      only the rest is copied. The leftover is **not** deleted unless you confirm, and the confirmation lists
      exactly the count and size shown.
- [ ] Remove the leftovers: only those files disappear (compare the folder), and the card is drawn again.
- [ ] Reboot the phone mid-backup and run again: same result as process death.
- [ ] Cancel at the passphrase dialog, the spinner dialog ("Stop") and the preflight card: each leaves the
      folder as it was.

## 8. Wrong passphrase on a backup run

- [ ] Run **Back up everything, encrypted** into a folder that already holds a backup and enter a wrong
      passphrase: the dialog returns with the red "That passphrase did not open the backup ..." line and
      **nothing is written** (compare the folder). A second try with the right one proceeds.
- [ ] With only leftovers and no manifest in the folder, a wrong passphrase is also refused.

## 9. The second and later runs

Sandbox coverage: `BackupJobTest` (a second run with nothing new writes no pack and a newer manifest, and
changes no existing file; changed files are copied, the old copy stays; deletions stay), `BackupPlannerTest`.

- [ ] Run again with nothing new: "Everything is already backed up. The tags, collections and locks will be
      saved again." The folder gains only `manifest-0002.fzbm` (each run adds one manifest; older ones are
      kept and may be deleted by hand, keeping the newest).
- [ ] Add 10 photos and edit one in another app: only those 11 are copied; the old version of the edited one
      stays in its old pack.
- [ ] Move an album's folder to another folder name (Files app): the photos are **recognised as moved** and
      not copied again.
- [ ] Delete a photo from the phone: it stays in the backup, and Check a backup is still clean.
- [ ] A file that is being written while the backup runs (record a video, or edit in another app): the run
      finishes, says "N files could not be copied (changed during the backup, or unreadable). They are tried
      again next time.", and the next run copies them.
- [ ] A video over the pack hard limit is named on the card every time ("will NOT be backed up") and never
      silently dropped.
- [ ] A folder lock re-set between runs, a caption changed, a pin dropped: the new manifest carries it (see
      restore, section 13).

## 10. Screen off, doze, the notification and the service

- [ ] Start a backup of several GB, press the power button, and leave the phone for 20 minutes on the
      charger, then on battery: the notification keeps advancing (the partial wake lock keeps the copy running
      with the screen off). Check with `adb shell dumpsys power | grep fotoz:backup` that the lock is held
      while it runs and **gone afterwards**, including after Cancel.
- [ ] Android 15 and 16: the service type is **data sync**, not media processing
      (`adb shell dumpsys activity services <package>`, "foregroundServiceType"). A video conversion still
      uses media processing.
- [ ] If you can run a backup for more than six hours on Android 15 (a very large library over a slow
      provider): when the system's data-sync time is spent the notification goes away (the service's
      `onTimeout`); the app does not crash or raise a "did not stop in time" error. Run again: it resumes.
- [ ] Revoke the notification permission, then back up: the copy still runs, without a notification, and
      the activity shade inside the app still shows it with its Cancel.
- [ ] Open another app while it runs, return: the job is still running and the Back up row is still greyed.
- [ ] Android 13+: the first job shows the notification-permission prompt exactly once.

## 11. Protected, sensitive, archived, trashed

- [ ] A photo in a **protected folder** is in the backup (Check a backup counts it) and its pack bytes are
      encrypted like the rest. The notification and the preflight card never name a protected photo or count
      them.
- [ ] A sensitive-marked photo and an archived photo are in; the trashed photo is not.
- [ ] A manual map pin and an unsaved edit are carried (the manifest sections), kept for the file revision
      they were made against only.

## 12. Privacy line

- [ ] The plain uri paths are unchanged: share, zip and Save As still strip location by default
      (`TRAPS #31`). Only the backup reads the original location, and only when `ACCESS_MEDIA_LOCATION` is
      granted; with it off the backup says so before it starts.
- [ ] The **Offline** build's merged manifest has no network permission (`verifyOfflineManifest`); the
      backup adds only `WAKE_LOCK`.
- [ ] Selecting a cloud-app folder never makes the **Offline** build touch the network: watch it with the
      system's per-app data usage while a backup into such a folder runs; the app's own line stays at zero.

## 13. Restore (unit 15)

Sandbox coverage: `RestoreJobTest` (the whole path on the JVM, through the real cipher: a backup written
by `BackupJob` into a folder, opened, planned and restored to an in-memory phone; a second run writes
nothing; a run cancelled half way finishes on the next; a name clash gives `name (restored).jpg` and leaves
the other file alone; hostile paths; a wrong passphrase; a flipped bit in a pack, a pack cut short, a
missing pack and a damaged manifest never put a wrong photo on the phone; a refused create, a failed write
and a full phone; ids remapped; pins and edits; locks before the first write), `RestorePlannerTest`,
`ProtectedPlacementTest`, `PackReaderTest`, `RestoreSetTest`, `MediaStoreRestoreWriterTest` (the insert,
`IS_PENDING`, `DATE_TAKEN`, the new id from the uri, on Robolectric's stand-in for MediaStore),
`AndroidRestoreApplierTest` and `RestoreLocksTest` (the real stores), `RestoreDialogsTest`.
**Not covered**, because Robolectric's MediaStore is a stand-in: what the real provider does with a
folder, a type or a name it dislikes, the scanner after `IS_PENDING = 0`, the expiry of a pending row, and
everything about a real folder provider, a real notification and a real kill.

Prepare once: make a full backup of the 200-photo library from the top of this file (with a protected
folder, sensitive photos, an archived one, a manual pin, an unsaved edit, tags and a collection), then
restore it on **a second phone** (or this phone after Settings > Apps > Fotoz > Clear data **and** deleting
the photos, which is the real disaster case). Do the lines below on both.

- [ ] Settings > Data > **Restore from an encrypted backup** is there, with "Puts photos and tags back
      from an encrypted backup. Replaces nothing." While a backup, check or restore runs it is greyed out.
- [ ] Pick the backup folder. A folder with no backup says so. A wrong passphrase says "That passphrase did
      not open the backup..." and asks again; the folder stays picked. A folder made by a newer Fotoz says so.
- [ ] The first card says the date the backup was made, "by Fotoz <version>", the number of photos and
      videos and the size, the tags it holds, and "Also: N folder locks, N map pins, N saved edits". With a
      pack deleted from the folder it warns that N files cannot be restored.
- [ ] **Everything** is chosen by default; **Tags and collections only** is greyed out for a backup made
      without tags. Continue shows "Looking at the photos on this phone..." and then the second card.
- [ ] The second card on an **empty** phone: "N files (size) will be copied back", free space, "Nothing on
      this phone is replaced or deleted...", and a Restore button. On a phone that already has some of the
      photos: "N already are on this phone and are skipped" and "N will be renamed". With the free space
      lower than the size it shows the red "Not enough room" line.
- [ ] With **ACCESS_MEDIA_LOCATION not granted**, the second card warns that photos already on the phone may
      come back as copies, and "Allow reading photo locations" turns the warning off. (Reading a local file
      without its location gives different bytes from the backed-up original, so it would not be recognised
      as already here.) Test it: restore onto a phone that has the same photos, with and without the permission.
- [ ] Restore. The notification reads "Restoring your library" with a progress bar and **no file name, no
      count**; Cancel in the notification stops it and says nothing alarming. The screen can be switched off.
- [ ] The result lists restored, already here, renamed "(restored)", could not be restored (missing pack),
      skipped, and what else was put back (tags, locks, pins, edits). A clean restore is a passing notice;
      anything left out is a dialog that stays.
- [ ] Restored photos are in their **own folders** (DCIM/Camera and so on), appear in the gallery with the
      right **date taken** (the order of the grid is the order of the original), are byte for byte the
      originals (compare a few with the backup phone: size, and a checksum if you can), and a photo that
      had GPS has it again (open its info). Photos from a folder Android refuses (Download, WhatsApp) are in
      Pictures/Fotoz, and videos in Movies/Fotoz.
- [ ] **Restore twice in a row** creates nothing new: the second card says everything is already here and
      its button is greyed out. A photo whose name was taken came back as `name (restored).jpg` the first
      time and is recognised on the second.
- [ ] **Kill the app mid-restore** (swipe it away from recents while a few hundred photos are going back),
      start it again and run the restore again with the same folder and passphrase: no duplicate photos, no
      broken or zero-length items in any gallery, and the rest comes back. A file the killed run was writing
      is a pending row that other gallery apps do not show; each carries an expiry a day ahead, so it should
      be gone by itself within a day. Record whether it was (this is the platform's documented behaviour,
      not something that has been watched happen here).
- [ ] Protected folders: after a restore the formerly protected folder is **locked** with the **old
      password**, **before** you see any of its photos (watch the grid while the restore runs: no protected
      photo is ever visible without its lock, even for a moment). A protected photo that came from a folder
      Android refuses is in "Pictures/Fotoz/Restored protected/..." and that folder is locked with the same
      password. A phone that already had a lock on the same folder keeps ITS password.
- [ ] Sensitive photos are hidden **as they arrive** with "Hide sensitive" on (not only at the end);
      favourites, the archive, captions, tags and collections are back on the right photos (spot-check
      ten against the old phone); nothing you had written on this phone was replaced.
- [ ] The map pin placed by hand is back on the **right** photo (give the app a minute after the restore,
      it waits for the gallery to list the new photos), and an unsaved edit offers "Continue editing" on the
      restored photo. If the edit is missing, note whether a rescan changed the photo's modified time: the
      edit is stored against the file's revision when the restore finishes.
- [ ] **Tags and collections only** on a phone that already has the photos: no photo is copied, tags land on
      the right photos, and photos the phone does not have are counted as "not on this phone".
- [ ] A wrong passphrase on restore; a pack whose bytes you changed with a hex editor (the result says N
      files were damaged and lists no wrong photo); a pack you cut in half; a pack you deleted; a manifest
      you changed (the photos still come back, the tags do not). None of these leaves a half file behind.
- [ ] A phone with **almost no free space**: the restore stops with "The phone's storage is full. N files
      came back before it stopped. Free some space.", nothing is left pending, and running it again after
      freeing space finishes.
- [ ] **Android 8 or 9** (before Android 10): photos come back to the default folders, protected photos are
      left out and the card says so ("...before Android 10 Fotoz cannot put a photo in a folder of its own...").
- [ ] Both flavours: Offline and Connect behave the same; Offline makes no network request while restoring.

## 14. Size and speed (record the numbers)

- [ ] Release APK size (Offline, R8): measured in the sandbox at **184,280,180 bytes**; compare with the
      installed size on a device.
- [ ] Throughput for a 1 GiB pack to the phone's storage, a card, a stick and a cloud-provider folder.
- [ ] Time to plan (the "Working out what is new..." step) with the real library, and with 50,000+ photos if
      you have them.
- [ ] Time of **Check a backup** per GB.

## 15. Safety fixes from the backup audit

Sandbox coverage: `SafListingTest` (the loading flag is re-asked with a backoff and then fails, an error
is thrown, a created document that is not new is refused), `BackupFolderSafetyTest` (a listing that was
empty while the folder loaded cannot start a second set; files of another set are never leftovers; the
removal lists again and deletes only what is still a leftover), `BackupCheckCoverageTest`,
`SectionMergeTest`, `BackupJobSectionsTest`, `BackupPlannerLocationTest`. **Not covered**: a real
provider's `EXTRA_LOADING`/`EXTRA_ERROR` behaviour and timing, which is why these lines need a device.

- [ ] **Cloud folder that is still syncing.** In a folder served by a cloud-storage app that already holds
      a finished backup, make the provider cold (clear the app's cache, or turn the network off in that
      app, then on) and start **Back up everything, encrypted**. Either the folder is recognised as an
      existing backup ("Enter the passphrase") or Fotoz says "The folder is still loading. Wait for the app
      that manages it to finish, then try again." It must **never** offer "Choose a passphrase" for a folder
      that already holds a backup. Note how long the provider took to answer and whether the 8 seconds
      Fotoz waits is enough.
- [ ] Pick the same folder with a **new** passphrase by mistake (a different one from the backup's): the
      run stops with "This folder already holds a backup that this passphrase does not open ..." and the
      folder is unchanged. With the **right** passphrase it proceeds.
- [ ] Put a file of another backup (copy `pack-0001.fzbp` from a different backup set, renamed
      `pack-0050.fzbp`) into a folder that holds your backup: the preflight says "1 file in this folder
      belongs to a different backup ... and is left alone", and **Remove the leftover files** (if shown)
      never lists or deletes it.
- [ ] **Remove the leftover files** with a folder that finishes syncing while the confirmation is open:
      only files that are still leftovers are removed; no `manifest-*`, `pack-*.fzbx` or listed pack goes.
- [ ] **Check a backup after an interrupted run.** Kill the app while it writes the manifest (or truncate
      the newest `manifest-NNNN.fzbm` by a few hundred bytes), then Check: it must **not** say "everything
      is intact". It says "The newest snapshot could not be read; checked the one before it plus N finished
      packs." and the count of files covers the newest photos too. Damage one byte in one of those finished
      packs: the check reports it.
- [ ] **A wiped or new phone, back up first.** Restore nothing yet; run **Back up everything, encrypted**
      into the existing folder with the right passphrase. The preflight says "This phone has fewer tags,
      collections, favourites or locks than the backup already holds. The backup keeps the ones it has".
      After the run, restore (section 13): tags, favourites, collections, folder locks, pins and edits are
      all there, and formerly protected photos come back **locked**. Note the other direction too: a tag you
      removed on a phone you kept using comes back after a restore (the backup only adds).
- [ ] **Location granted later.** Back up once with the location permission **denied**, then allow it and
      back up again: the preflight says "N files were backed up without their location and will be copied
      again", the second run copies them, and a restored photo has its GPS (open its info). A third run says
      everything is already backed up (it must not copy them every time; if it does, note which files, they
      are ones whose original cannot be read). Run it a **fourth** time: still nothing to copy, and no new
      `pack-*.fzbp` appears in the folder (the first run's pack stays there; that is expected).
- [ ] **A folder that is still syncing must not offer to delete the last run.** Back up twice, then make
      the provider hide or cut the newest `manifest-NNNN.fzbm` and shorten its newest pack by a few bytes
      (or stop the cloud app mid-upload of a second run). Start **Back up everything, encrypted**: the
      preflight says the files "do not match the backup that lists them ... and are left alone" (or that
      the leftovers stay because the newest backup file could not be read) and shows **no** "Remove the
      leftover files" button; **Check a backup** says "... were not checked", never "everything is intact".
      After the provider finishes syncing, the same folder opens normally.
- [ ] **A wiped phone's pins and edits.** Back up on phone A with a hand-placed map pin and a saved edit,
      restore onto a fresh phone B, place a different pin on another photo and back up into the same
      folder before restoring everything. Restore on a third phone (or wiped B): both photos get their own
      pin and edit; neither pin is on the other photo.

