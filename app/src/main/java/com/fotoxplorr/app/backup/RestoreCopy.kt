package com.fotoxplorr.app.backup

import java.text.DateFormat
import java.util.Date

/**
 * The words of the encrypted restore, kept apart from any screen so they can be tested. The
 * one-line row caption is in `ui.HelpCopy`.
 *
 * Counts are of files, never names, and nothing here is used in a notification: the job's title is
 * [JOB_TITLE] and nothing else (no file name, no count of protected photos).
 */
object RestoreCopy {
    const val ROW_LABEL = "Restore from an encrypted backup"

    /** Shown in the notification and the activity shade. No file names, no counts. */
    const val JOB_TITLE = "Restoring your library"

    const val OPTION_EVERYTHING = "Everything"
    const val OPTION_EVERYTHING_CAPTION = "Photos and videos, then tags, collections, favourites, folder locks, map pins and edits."
    const val OPTION_TAGS = "Tags and collections only"
    const val OPTION_TAGS_CAPTION = "No photos are copied. Tags, collections, captions, favourites and marks are added to the photos already on this phone."
    const val NO_TAGS_IN_BACKUP = "This backup has no tags and collections."

    const val NOTHING_IN_BACKUP = "This backup holds nothing to restore."
    const val LOOKING = "Looking at the photos on this phone…"
    const val NEVER_OVERWRITES = "Nothing on this phone is replaced or deleted. A file whose name is already taken comes back as \"name (restored).jpg\"."
    const val SAFE_TO_REPEAT = "If it is interrupted, run it again: what is already on the phone is skipped."
    const val TAGS_MERGED = "Tags and collections are added to yours. Nothing you wrote here is replaced."
    const val ONLY_WITH_EVERYTHING = "Folder locks, map pins and edits come back with \"Everything\"."
    const val LOCKS_BACK = "Photos that were in protected folders go back behind their lock, with the password they had."
    const val LOCKS_RELOCATED = "Some of them cannot go back to the folder they came from, so they get a protected folder of their own under Pictures/Fotoz."
    const val LOCKS_MISSING = "Some photos were in a protected folder whose lock is not in this backup. They come back unprotected."
    const val NEEDS_FOLDER = "Photos from protected folders are left out: before Android 10 Fotoz cannot put a photo in a folder of its own, so it could not keep them locked."
    const val LOCATION_MISSING_RESTORE =
        "Fotoz may not be allowed to read photo locations, so it may not recognise photos already on this phone and bring them back as copies. Allow it first to avoid that."
    const val FEW_PHOTOS_NOTE = "Photos and videos only; any other file in the backup is left out."

    private fun count(n: Int, noun: String, plural: String = noun + "s") = BackupCopy.count(n, noun, plural)

    /** "12 March 2026", in the phone's own language and calendar. */
    fun dateOf(millis: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(millis))

    /** The first card: what the backup holds. */
    fun summary(info: RestoreSetInfo): List<BackupCopy.Line> = buildList {
        val made = info.createdAtMillis?.takeIf { it > 0 }?.let { " made on ${dateOf(it)}" }.orEmpty()
        val by = info.appVersion?.takeIf { it.isNotBlank() }?.let { ", by Fotoz $it" }.orEmpty()
        add(BackupCopy.Line(BackupCopy.Severity.INFO, "This backup$made$by holds ${filesPhrase(info)} (${BackupCopy.size(info.totalBytes)})."))
        info.metadata?.let { m -> MetadataBackupCopy.contents(m)?.let { add(BackupCopy.Line(BackupCopy.Severity.INFO, "Tags and collections: $it.")) } }
        val extras = buildList {
            if (info.folderLocks > 0) add(count(info.folderLocks, "folder lock"))
            if (info.pins > 0) add(count(info.pins, "map pin"))
            if (info.recipes > 0) add(count(info.recipes, "saved edit"))
        }
        if (extras.isNotEmpty()) add(BackupCopy.Line(BackupCopy.Severity.INFO, "Also: ${extras.joinToString(", ")}."))
        if (info.missing > 0) {
            add(
                BackupCopy.Line(
                    BackupCopy.Severity.WARNING,
                    "${count(info.missing, "file")} ${if (info.missing == 1) "is" else "are"} listed but ${if (info.missing == 1) "its" else "their"} pack is not in the folder, so ${if (info.missing == 1) "it" else "they"} cannot be restored.",
                ),
            )
        }
    }

    private fun filesPhrase(info: RestoreSetInfo): String = when {
        info.photos > 0 && info.videos > 0 -> "${count(info.photos, "photo")} and ${count(info.videos, "video")}"
        info.videos > 0 -> count(info.videos, "video")
        else -> count(info.photos, "photo")
    }

    class ReviewFacts(
        val freeBytes: Long?,
        val locationApplicable: Boolean,
        val locationGranted: Boolean,
    )

    /** The second card: what this choice would do on this phone, before anything is written. */
    fun review(prep: RestorePreparation, facts: ReviewFacts): List<BackupCopy.Line> = buildList {
        val c = prep.counts
        if (prep.scope == RestoreScope.TAGS_ONLY) {
            add(BackupCopy.Line(BackupCopy.Severity.INFO, "${count(c.matched, "photo")} from the backup ${if (c.matched == 1) "was" else "were"} found on this phone."))
            if (c.notOnPhone > 0) {
                add(BackupCopy.Line(BackupCopy.Severity.WARNING, "${count(c.notOnPhone, "photo")} ${if (c.notOnPhone == 1) "is" else "are"} not on this phone, so ${if (c.notOnPhone == 1) "its" else "their"} tags are left out."))
            }
            prep.metadata?.let { m -> MetadataBackupCopy.contents(m.summary)?.let { add(BackupCopy.Line(BackupCopy.Severity.INFO, "To add: $it.")) } }
            add(BackupCopy.Line(BackupCopy.Severity.INFO, TAGS_MERGED))
            add(BackupCopy.Line(BackupCopy.Severity.INFO, ONLY_WITH_EVERYTHING))
            return@buildList
        }

        if (c.toWrite == 0) {
            add(BackupCopy.Line(BackupCopy.Severity.INFO, "No photo needs to be copied back."))
        } else {
            add(BackupCopy.Line(BackupCopy.Severity.INFO, "${count(c.toWrite, "file")} (${BackupCopy.size(c.bytesToWrite)}) will be copied back."))
        }
        if (c.alreadyHere > 0) add(BackupCopy.Line(BackupCopy.Severity.INFO, "${c.alreadyHere} already ${if (c.alreadyHere == 1) "is" else "are"} on this phone and ${if (c.alreadyHere == 1) "is" else "are"} skipped."))
        if (c.asCopies > 0) {
            add(BackupCopy.Line(BackupCopy.Severity.INFO, "${c.asCopies} will be renamed \"name (restored)\" because a different file has that name."))
        }
        if (c.missing > 0) add(BackupCopy.Line(BackupCopy.Severity.WARNING, "${count(c.missing, "file")} cannot be restored: ${if (c.missing == 1) "its" else "their"} pack is missing from the folder."))
        if (c.unsupported > 0) add(BackupCopy.Line(BackupCopy.Severity.INFO, "${count(c.unsupported, "file")} skipped. $FEW_PHOTOS_NOTE"))
        if (c.heldBack > 0) add(BackupCopy.Line(BackupCopy.Severity.WARNING, NEEDS_FOLDER))

        prep.metadata?.let { m -> MetadataBackupCopy.contents(m.summary)?.let { add(BackupCopy.Line(BackupCopy.Severity.INFO, "Tags and collections to add: $it.")) } }
        prep.arranged?.let { arranged ->
            if (prep.locksToApply.isNotEmpty()) add(BackupCopy.Line(BackupCopy.Severity.INFO, LOCKS_BACK))
            if (arranged.relocated > 0) add(BackupCopy.Line(BackupCopy.Severity.INFO, LOCKS_RELOCATED))
            if (arranged.withoutLock > 0) add(BackupCopy.Line(BackupCopy.Severity.WARNING, LOCKS_MISSING))
        }

        val free = facts.freeBytes
        when {
            c.toWrite == 0 -> Unit
            free == null -> add(BackupCopy.Line(BackupCopy.Severity.INFO, "Fotoz can't tell how much room is left on this phone."))
            c.bytesToWrite > free -> add(
                BackupCopy.Line(
                    BackupCopy.Severity.PROBLEM,
                    "Not enough room: ${BackupCopy.size(c.bytesToWrite)} needed, ${BackupCopy.size(free)} free. The restore stops when the phone is full; run it again after freeing space.",
                ),
            )
            else -> add(BackupCopy.Line(BackupCopy.Severity.INFO, "${BackupCopy.size(free)} free on this phone."))
        }
        if (facts.locationApplicable && !facts.locationGranted && c.alreadyHere + c.toWrite > 0) {
            add(BackupCopy.Line(BackupCopy.Severity.WARNING, LOCATION_MISSING_RESTORE))
        }
        add(BackupCopy.Line(BackupCopy.Severity.INFO, NEVER_OVERWRITES))
        add(BackupCopy.Line(BackupCopy.Severity.INFO, SAFE_TO_REPEAT))
    }

    /** What the finished run reports: restored, already here, renamed, missing, skipped, and what went wrong. */
    fun done(outcome: RestoreOutcome): String = buildList {
        if (outcome.scope == RestoreScope.TAGS_ONLY) {
            val m = outcome.metadata
            if (m != null) {
                add("Added ${MetadataBackupCopy.contents(m) ?: "nothing new"} to ${count(m.photosFound, "photo")} on this phone.")
                if (m.photosMissing > 0) add("${count(m.photosMissing, "photo")} from the backup ${if (m.photosMissing == 1) "is" else "are"} not on this phone, so ${if (m.photosMissing == 1) "its" else "their"} tags were left out.")
            } else if (outcome.metadataFailed) {
                add("The tags and collections could not be added. Nothing was changed that you can see; try again.")
            } else {
                add("This backup has no tags and collections to add.")
            }
            return@buildList
        }
        if (outcome.restored + outcome.restoredAsCopy == 0 && outcome.failed.isEmpty()) {
            add("No photo needed to be copied back.")
        } else {
            add("Restored ${count(outcome.restored + outcome.restoredAsCopy, "file")} (${BackupCopy.size(outcome.bytesWritten)}).")
        }
        if (outcome.alreadyHere > 0) add("${outcome.alreadyHere} ${if (outcome.alreadyHere == 1) "was" else "were"} already on the phone.")
        if (outcome.restoredAsCopy > 0) add("${outcome.restoredAsCopy} came back as \"name (restored)\" because the name was taken.")
        if (outcome.missing > 0) add("${count(outcome.missing, "file")} could not be restored: ${if (outcome.missing == 1) "its" else "their"} pack is not in the folder.")
        if (outcome.skipped > 0) add("${count(outcome.skipped, "file")} skipped.")
        if (outcome.failed.isNotEmpty()) {
            val damaged = outcome.failed.count { it.reason == RestoreFailure.DAMAGED || it.reason == RestoreFailure.NOT_IN_PACK }
            val cause = if (damaged == outcome.failed.size) "were damaged in the backup" else "could not be written"
            add("${count(outcome.failed.size, "file")} $cause. Run Check a backup to see which.")
        }
        outcome.metadata?.let { m ->
            MetadataBackupCopy.contents(m)?.let { add("Added $it.") }
            if (m.photosMissing > 0) add("${count(m.photosMissing, "photo")} with tags ${if (m.photosMissing == 1) "is" else "are"} not on the phone, so ${if (m.photosMissing == 1) "its" else "their"} tags were left out.")
        }
        if (outcome.metadataFailed) add("Some tags, pins or edits could not be added. Run the restore again to add them.")
        outcome.locks?.let { if (it.added > 0) add("${count(it.added, "folder lock")} put back.") }
        if (outcome.pins > 0) add("${count(outcome.pins, "map pin")} put back.")
        if (outcome.recipes > 0) add("${count(outcome.recipes, "saved edit")} put back.")
        if (outcome.protectedRelocated > 0) add(LOCKS_RELOCATED)
        if (outcome.protectedWithoutLock > 0) add(LOCKS_MISSING)
    }.joinToString(" ")
}
