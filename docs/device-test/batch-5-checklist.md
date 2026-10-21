# Batch 5 device test checklist

Every device-only claim from the fifth owner-feedback batch (item **#7**, backup; see
`docs/handoff/MASTER-PROGRESS.md`), in the same shape as `batch-3-checklist.md`. This sandbox has no
device or emulator, so **none of these is claimed as verified**: each section says what the JVM and
Robolectric tests cover, and the boxes are for what those cannot see (a real storage-access provider,
a real card, process death, a real notification, a real finger, a real phone's PBKDF2). Check each box on
a real phone, on **both flavours** where a line says so, before relying on the corresponding change.

This file is for the **write side** (unit 14: the encrypted backup, its check, and the plain
tags-and-collections file's new entry points). Section 13 is left for the restore unit to fill in.

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

Sandbox coverage: `BackupFormatTest` (the golden vector, on the JDK's PBKDF2), `BackupStreamsTest`.
The vector itself is run by `BackupKeys.providerMatchesGoldenVector()` as a self-check at the start of the
first backup or check after each launch; on a mismatch Fotoz says "This phone computes the passphrase
key differently from the standard ... Fotoz will not start one" and writes nothing.

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

## 13. Restore (to be filled in by the restore unit)

- [ ] Restore everything, and tags/collections only, on a second phone or after Clear data.
- [ ] Restore twice in a row creates no duplicates; a conflicting name becomes `name (restored).ext`.
- [ ] Protected folders are locked again after a restore; sensitive and archived flags are back; photos
      that had GPS have it.
- [ ] A wrong passphrase on restore; a tampered pack on restore.

## 14. Size and speed (record the numbers)

- [ ] Release APK size (Offline, R8): measured in the sandbox at **184,280,180 bytes**; compare with the
      installed size on a device.
- [ ] Throughput for a 1 GiB pack to the phone's storage, a card, a stick and a cloud-provider folder.
- [ ] Time to plan (the "Working out what is new..." step) with the real library, and with 50,000+ photos if
      you have them.
- [ ] Time of **Check a backup** per GB.
