package com.fotoxplorr.app.backup

import java.util.Locale

/**
 * The words of the encrypted backup that are built from live values or are too long for
 * `HelpCopy`'s ceilings (dialog bodies), kept apart from any screen so they can be tested. The
 * one-line facts and the pinned no-recovery sentence are in `ui.HelpCopy`.
 *
 * Every figure is of files and bytes. Nothing here, and nothing in a job title, names a file or
 * counts protected photos; the notification shows only [JOB_TITLE].
 */
object BackupCopy {
    const val OPTION_LABEL = "Back up everything, encrypted"
    const val CHECK_LABEL = "Check a backup"
    const val SECTION_LABEL = "BACKUP: EVERYTHING, ENCRYPTED"

    /** Shown in the notification and the activity shade. No file names, no counts. */
    const val JOB_TITLE = "Backing up your library"
    const val CHECK_JOB_TITLE = "Checking your backup"

    const val ONLY_ADDS = "The backup only ever adds files. Photos you delete from this phone stay in it, and older copies of a file you changed stay too."
    const val INCLUDED = "Included: photos and videos, including those in protected folders or marked sensitive, with their tags, collections, favourites, locks, map pins and edits. Not included: the bin, settings, and anything Fotoz can recompute."
    const val SAME_PHONE = "This folder is on the phone's own storage, next to the photos. A copy that lives on the phone is not safe if the phone is lost or wiped."
    const val OTHER_APP_FOLDER = "Another app manages this folder. If that app syncs it online, that app moves the encrypted files; Fotoz itself never goes online."
    const val LOW_BATTERY = "The battery is low and the phone is not charging. A long backup is best done on the charger."
    const val LOCATION_MISSING = "Photo locations will NOT be in this backup, because Fotoz may not read them. Allow it, or the copies will be without their GPS."
    const val LOCATION_INCLUDED = "Photo locations are included, encrypted with the rest."
    const val NO_CATALOGUE = "Fotoz can't see any photos right now. Try again once your library has loaded."
    const val UP_TO_DATE = "Everything is already backed up. The tags, collections and locks will be saved again."
    const val ALREADY_RUNNING = "A backup is already running."
    const val KEY_SELF_CHECK_FAILED =
        "This phone computes the passphrase key differently from the standard, so a backup made here might not open elsewhere. Fotoz will not start one."
    const val CIPHER_SELF_CHECK_FAILED =
        "Encryption did not pass Fotoz's self-test on this phone: it could not open a known sample, or what it sealed would not open again. " +
            "A backup made here might not be readable later, so Fotoz will not start one."
    const val SECTIONS_FEWER =
        "This phone has fewer tags, collections, favourites or locks than the backup already holds. The backup keeps the ones it has; nothing is removed from it."
    const val WRONG_PASSPHRASE = "That passphrase did not open the backup. Check it and try again, or the backup is damaged."
    const val CHOOSE_ANOTHER_FOLDER = "Choose a different folder for a new backup."
    const val PASSPHRASE_NEW_TITLE = "Choose a passphrase"
    const val PASSPHRASE_EXISTING_TITLE = "Enter the passphrase"
    const val PASSPHRASE_SHARING = "Anyone who has the folder and the passphrase can read everything in it. Choose something long that only you know, such as a few words in a row."
    const val MISMATCH = "The two passphrases are different."
    const val CHECKING_FOLDER = "Looking at the folder…"
    const val DERIVING = "Working out the key from your passphrase. This takes a moment."
    const val PLANNING = "Working out what is new…"

    fun count(n: Int, noun: String, plural: String = noun + "s"): String = "$n ${if (n == 1) noun else plural}"

    /** Decimal units, as Android itself shows file sizes: "950 MB", "4.2 GB". */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1_000L -> "$b B"
            b < 1_000_000L -> "${b / 1_000} kB"
            b < 1_000_000_000L -> {
                val mb = b / 1_000_000.0
                if (mb >= 100) "${mb.toLong()} MB" else String.format(Locale.ROOT, "%.1f MB", mb)
            }
            b < 1_000_000_000_000L -> String.format(Locale.ROOT, "%.1f GB", b / 1_000_000_000.0)
            else -> String.format(Locale.ROOT, "%.1f TB", b / 1_000_000_000_000.0)
        }
    }

    enum class Severity { INFO, WARNING, PROBLEM }

    class Line(val severity: Severity, val text: String)

    /** Everything the preflight card is drawn from. Plain values, so the card's words are testable. */
    class PreflightFacts(
        val newFiles: Int,
        val newSourceBytes: Long,
        val estimatedBytes: Long,
        val packs: Int,
        val alreadyBackedUp: Int,
        val tooLargeNames: List<String>,
        val tooLargeCount: Int,
        val leftoverCount: Int,
        val leftoverBytes: Long,
        val freeBytes: Long?,
        val photosSeen: Int,
        val locationApplicable: Boolean,
        val locationGranted: Boolean,
        val onPhoneStorage: Boolean,
        val managedByAnotherApp: Boolean,
        val batteryLowAndNotCharging: Boolean,
        val newSet: Boolean,
        /** Files an earlier run stored without their location, which can be read now: they are copied again. */
        val locationRecopies: Int = 0,
        /** The folder also holds backup files of another set (or a newer version's); they are left alone. */
        val foreignCount: Int = 0,
        /** This phone's tags, collections, favourites or locks are fewer than the newest snapshot holds. */
        val sectionsFewer: Boolean = false,
    )

    fun preflight(f: PreflightFacts): List<Line> = buildList {
        if (f.newFiles == 0) {
            add(Line(Severity.INFO, UP_TO_DATE))
        } else {
            add(
                Line(
                    Severity.INFO,
                    "${count(f.newFiles, "file")} (${size(f.newSourceBytes)}) will be added, in ${count(f.packs, "pack")} " +
                        "of about ${size(f.estimatedBytes / f.packs.coerceAtLeast(1))}. " +
                        "Encrypted, that is about ${size(f.estimatedBytes)}.",
                ),
            )
        }
        if (f.alreadyBackedUp > 0) add(Line(Severity.INFO, "${count(f.alreadyBackedUp, "file")} already in the backup."))
        if (f.tooLargeCount > 0) {
            val named = f.tooLargeNames.take(MAX_NAMES).joinToString(", ")
            val more = if (f.tooLargeCount > MAX_NAMES) " and ${f.tooLargeCount - MAX_NAMES} more" else ""
            add(
                Line(
                    Severity.WARNING,
                    "${count(f.tooLargeCount, "file")} too large for a pack (over ${size(PackLimits.DEFAULT_HARD_CAP_BYTES)}) " +
                        "will NOT be backed up: $named$more.",
                ),
            )
        }
        if (f.newSet) add(Line(Severity.INFO, "This starts a new backup in the folder."))

        val free = f.freeBytes
        when {
            free == null -> add(Line(Severity.INFO, "Fotoz can't tell how much room is left in this folder."))
            f.estimatedBytes > free -> add(
                Line(
                    Severity.PROBLEM,
                    "Not enough room: about ${size(f.estimatedBytes)} needed, ${size(free)} free. " +
                        "The backup stops when the folder is full and carries on next time.",
                ),
            )
            else -> add(Line(Severity.INFO, "${size(free)} free in this folder."))
        }
        if (f.locationApplicable) {
            add(if (f.locationGranted) Line(Severity.INFO, LOCATION_INCLUDED) else Line(Severity.WARNING, LOCATION_MISSING))
        }
        if (f.locationRecopies > 0) {
            add(
                Line(
                    Severity.INFO,
                    "${count(f.locationRecopies, "file")} ${if (f.locationRecopies == 1) "was" else "were"} backed up without " +
                        "${if (f.locationRecopies == 1) "its" else "their"} location and will be copied again.",
                ),
            )
        }
        if (f.sectionsFewer) add(Line(Severity.INFO, SECTIONS_FEWER))
        if (f.onPhoneStorage) add(Line(Severity.WARNING, SAME_PHONE))
        if (f.managedByAnotherApp) add(Line(Severity.INFO, OTHER_APP_FOLDER))
        if (f.batteryLowAndNotCharging) add(Line(Severity.WARNING, LOW_BATTERY))
        if (f.leftoverCount > 0) {
            add(
                Line(
                    Severity.INFO,
                    "${count(f.leftoverCount, "leftover file")} (${size(f.leftoverBytes)}) from an interrupted backup cannot be used. " +
                        "They stay unless you remove them.",
                ),
            )
        }
        if (f.foreignCount > 0) {
            add(
                Line(
                    Severity.INFO,
                    "${count(f.foreignCount, "file")} in this folder ${if (f.foreignCount == 1) "belongs" else "belong"} to a different backup " +
                        "(or to a newer version of Fotoz) and ${if (f.foreignCount == 1) "is" else "are"} left alone.",
                ),
            )
        }
        add(Line(Severity.INFO, INCLUDED))
        add(Line(Severity.INFO, ONLY_ADDS))
    }

    /** What a finished run reports. Plural and singular both read as a sentence. */
    fun done(outcome: BackupOutcome): String = buildList {
        if (outcome.filesCopied == 0 && outcome.failed.isEmpty()) {
            add("Nothing new to back up. Your tags, collections and locks were saved again.")
        } else {
            add("Backed up ${count(outcome.filesCopied, "file")} (${size(outcome.bytesCopied)}) in ${count(outcome.packsWritten, "pack")}.")
        }
        if (outcome.alreadyBackedUp > 0) add("${outcome.alreadyBackedUp} were already in the backup.")
        if (outcome.failed.isNotEmpty()) {
            add("${count(outcome.failed.size, "file")} could not be copied (changed during the backup, or unreadable). They are tried again next time.")
        }
        if (outcome.tooLarge.isNotEmpty()) {
            add("${count(outcome.tooLarge.size, "file")} too large for a pack ${if (outcome.tooLarge.size == 1) "was" else "were"} not backed up.")
        }
        if (outcome.sizeMismatches > 0) {
            add("The folder reported a different size for ${count(outcome.sizeMismatches, "pack")}. Run Check a backup to be sure.")
        }
    }.joinToString(" ")

    /**
     * "Checked 1,204 files in 9 packs (8.3 GB): everything is intact." Only when the check saw all of
     * the set; when it fell back to an older snapshot, read finished packs no snapshot lists, or
     * left files unread, it says that in plain words instead of claiming more than it read.
     */
    fun verifyClean(report: VerifyReport): String {
        val read = "Checked ${count(report.filesVerified, "file")} in ${count(report.packsChecked, "pack")} (${size(report.bytesChecked)})"
        if (!report.hasCaveats) return "$read: everything is intact."
        return "$read: no damage found in what was read. ${verifyNotes(report).joinToString(" ")}"
    }

    /** What a check stood on, when that is not simply the newest snapshot and nothing else. Empty for a plain check. */
    fun verifyNotes(report: VerifyReport): List<String> = buildList {
        val finished = count(report.recoveredPacks, "finished pack")
        when {
            report.skippedManifests > 0 && report.generation > 0 -> add(
                "The newest snapshot could not be read; checked the one before it" +
                    (if (report.recoveredPacks > 0) " plus $finished." else "."),
            )
            report.skippedManifests > 0 -> add("No snapshot could be read; checked $finished on their own.")
            report.recoveredPacks > 0 -> add(
                "${count(report.recoveredPacks, "pack")} from the last backup ${if (report.recoveredPacks == 1) "is" else "are"} " +
                    "not in a snapshot yet; ${if (report.recoveredPacks == 1) "it was" else "they were"} checked too.",
            )
        }
        if (report.leftovers > 0) {
            add("${count(report.leftovers, "file")} in the folder cannot be used and ${if (report.leftovers == 1) "was" else "were"} not checked.")
        }
    }

    /** The problems of a check, at most [MAX_PROBLEMS] of them spelled out. Names are shown here only, never in a notification. */
    fun verifyProblems(report: VerifyReport): String {
        val shown = report.problems.take(MAX_PROBLEMS).joinToString("\n") { problem ->
            val what = problem.fileName ?: problem.packName
            "• $what: ${problem.detail}."
        }
        val more = if (report.problems.size > MAX_PROBLEMS) "\n…and ${report.problems.size - MAX_PROBLEMS} more." else ""
        val notes = verifyNotes(report).joinToString(" ").let { if (it.isEmpty()) "" else "$it\n" }
        return "Checked the backup and found ${count(report.problems.size, "problem")}.\n$shown$more\n$notes" +
            "Back up again to a fresh folder to be safe; this folder was not changed."
    }

    /** The line under the passphrase field while the person types a NEW passphrase. */
    fun strengthHint(strength: PassphraseStrength): String = when (strength) {
        PassphraseStrength.TOO_SHORT -> "At least ${BackupPassphrase.MIN_CODE_POINTS} characters. A few words in a row works well."
        PassphraseStrength.OK -> "Long enough. Longer is stronger."
        PassphraseStrength.GOOD -> "Good."
    }

    private const val MAX_NAMES = 3
    private const val MAX_PROBLEMS = 6
}

enum class PassphraseStrength {
    TOO_SHORT,
    OK,
    GOOD,
    ;

    companion object {
        /** Length only, counted in characters the way [BackupPassphrase] counts them. */
        fun of(passphrase: String): PassphraseStrength {
            val chars = passphrase.toCharArray()
            return try {
                when {
                    !BackupPassphrase.isLongEnough(chars) -> TOO_SHORT
                    passphrase.codePointCount(0, passphrase.length) >= GOOD_FROM -> GOOD
                    else -> OK
                }
            } finally {
                chars.fill('\u0000')
            }
        }

        private const val GOOD_FROM = 20
    }
}

/** What stops the passphrase form from going on, in the order the person should fix them. */
enum class PassphraseProblem { TOO_SHORT, MISMATCH, NOT_UNDERSTOOD, EMPTY }

object PassphraseForm {
    /**
     * Null when the form may be submitted. A NEW set needs the minimum length, the same text twice
     * and the ticked acknowledgement; an existing set only needs something typed (the length rule
     * is not applied when OPENING a set: a reader accepts whatever the writer accepted).
     */
    fun problem(passphrase: String, confirm: String, understood: Boolean, newSet: Boolean): PassphraseProblem? {
        if (passphrase.isEmpty()) return PassphraseProblem.EMPTY
        if (!newSet) return null
        if (PassphraseStrength.of(passphrase) == PassphraseStrength.TOO_SHORT) return PassphraseProblem.TOO_SHORT
        // Compared as NFC, the form the key is derived from, so the same word typed with a composed
        // and a decomposed accent is the same passphrase.
        if (nfc(passphrase) != nfc(confirm)) return PassphraseProblem.MISMATCH
        if (!understood) return PassphraseProblem.NOT_UNDERSTOOD
        return null
    }

    private fun nfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
}
