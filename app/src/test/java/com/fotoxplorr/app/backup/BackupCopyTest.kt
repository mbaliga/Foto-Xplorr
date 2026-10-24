package com.fotoxplorr.app.backup

import com.fotoxplorr.app.ui.HelpCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCopyTest {
    private fun facts(
        newFiles: Int = 120,
        sourceBytes: Long = 3_400_000_000,
        estimated: Long = 3_450_000_000,
        packs: Int = 4,
        already: Int = 0,
        tooLarge: List<String> = emptyList(),
        tooLargeCount: Int = tooLarge.size,
        leftovers: Int = 0,
        free: Long? = 64_000_000_000,
        locationApplicable: Boolean = true,
        locationGranted: Boolean = true,
        onPhone: Boolean = false,
        otherApp: Boolean = false,
        battery: Boolean = false,
        newSet: Boolean = true,
    ) = BackupCopy.PreflightFacts(
        newFiles = newFiles, newSourceBytes = sourceBytes, estimatedBytes = estimated, packs = packs, alreadyBackedUp = already,
        tooLargeNames = tooLarge, tooLargeCount = tooLargeCount, leftoverCount = leftovers, leftoverBytes = leftovers * 1_000_000L,
        freeBytes = free, photosSeen = newFiles + already, locationApplicable = locationApplicable, locationGranted = locationGranted,
        onPhoneStorage = onPhone, managedByAnotherApp = otherApp, batteryLowAndNotCharging = battery, newSet = newSet,
    )

    private fun texts(f: BackupCopy.PreflightFacts) = BackupCopy.preflight(f).map { it.text }
    private fun severities(f: BackupCopy.PreflightFacts) = BackupCopy.preflight(f).map { it.severity }

    @Test
    fun `sizes are decimal and never show a bare long number`() {
        assertEquals("0 B", BackupCopy.size(0))
        assertEquals("999 B", BackupCopy.size(999))
        assertEquals("1 kB", BackupCopy.size(1_000))
        assertEquals("12.3 MB", BackupCopy.size(12_300_000))
        assertEquals("250 MB", BackupCopy.size(250_000_000))
        assertEquals("1.0 GB", BackupCopy.size(1_000_000_000))
        assertEquals("4.3 GB", BackupCopy.size(4_278_190_080))
        assertEquals("2.5 TB", BackupCopy.size(2_500_000_000_000))
        assertEquals("0 B", BackupCopy.size(-5))
    }

    @Test
    fun `the preflight says what will be added, how big, in how many packs`() {
        val lines = texts(facts())
        assertTrue(lines.first(), lines.first().startsWith("120 files (3.4 GB) will be added, in 4 packs"))
        assertTrue(lines.first(), lines.first().contains("about 3.5 GB"))
        assertTrue(lines.any { it == "64.0 GB free in this folder." })
        assertTrue(lines.contains(BackupCopy.LOCATION_INCLUDED))
        assertTrue(lines.contains(BackupCopy.ONLY_ADDS))
        assertTrue(lines.contains(BackupCopy.INCLUDED))
    }

    @Test
    fun `a single file and a single pack read as singular`() {
        val line = texts(facts(newFiles = 1, packs = 1, sourceBytes = 5_000, estimated = 6_000)).first()
        assertTrue(line, line.startsWith("1 file (5 kB) will be added, in 1 pack of"))
    }

    @Test
    fun `nothing new says everything is backed up and still counts the files already there`() {
        val lines = texts(facts(newFiles = 0, packs = 0, already = 40, newSet = false))
        assertEquals(BackupCopy.UP_TO_DATE, lines.first())
        assertTrue(lines.contains("40 files already in the backup."))
        assertFalse(lines.any { it.contains("starts a new backup") })
    }

    @Test
    fun `a missing location permission is a warning that says the GPS will be left out`() {
        val f = facts(locationGranted = false)
        assertTrue(texts(f).contains(BackupCopy.LOCATION_MISSING))
        assertEquals(BackupCopy.Severity.WARNING, BackupCopy.preflight(f).first { it.text == BackupCopy.LOCATION_MISSING }.severity)
        assertTrue(BackupCopy.LOCATION_MISSING.contains("NOT"))
        assertTrue(BackupCopy.LOCATION_MISSING.contains("GPS"))
        // Below Android 10 nothing is redacted, so nothing is said.
        val old = texts(facts(locationApplicable = false, locationGranted = false))
        assertFalse(old.contains(BackupCopy.LOCATION_MISSING))
        assertFalse(old.contains(BackupCopy.LOCATION_INCLUDED))
    }

    @Test
    fun `not enough room is a problem and unknown room is said to be unknown`() {
        val tight = BackupCopy.preflight(facts(estimated = 10_000_000_000, free = 2_000_000_000))
        val problem = tight.first { it.severity == BackupCopy.Severity.PROBLEM }
        assertTrue(problem.text, problem.text.startsWith("Not enough room: about 10.0 GB needed, 2.0 GB free."))
        assertTrue(problem.text.contains("carries on next time"))
        val unknown = texts(facts(free = null))
        assertTrue(unknown.contains("Fotoz can't tell how much room is left in this folder."))
        assertFalse(severities(facts(free = null)).contains(BackupCopy.Severity.PROBLEM))
    }

    @Test
    fun `files too large for a pack are named, and the list is cut after three`() {
        val lines = texts(facts(tooLarge = listOf("a.mov", "b.mov", "c.mov", "d.mov"), tooLargeCount = 6))
        val line = lines.first { it.contains("too large for a pack") }
        assertTrue(line, line.startsWith("6 files too large for a pack (over 4.3 GB) will NOT be backed up: a.mov, b.mov, c.mov and 3 more."))
        assertFalse(line.contains("d.mov"))
    }

    @Test
    fun `storage warnings appear only when they apply`() {
        val plain = texts(facts())
        assertFalse(plain.contains(BackupCopy.SAME_PHONE))
        assertFalse(plain.contains(BackupCopy.OTHER_APP_FOLDER))
        assertFalse(plain.contains(BackupCopy.LOW_BATTERY))
        val all = texts(facts(onPhone = true, otherApp = true, battery = true, leftovers = 2))
        assertTrue(all.contains(BackupCopy.SAME_PHONE))
        assertTrue(all.contains(BackupCopy.OTHER_APP_FOLDER))
        assertTrue(all.contains(BackupCopy.LOW_BATTERY))
        assertTrue(all.any { it.startsWith("2 leftover files (2.0 MB) from an interrupted backup cannot be used.") })
    }

    @Test
    fun `no line of the card names a protected photo count`() {
        // The card says protected and sensitive photos are included without counting them.
        texts(facts()).forEach { assertFalse(it, Regex("protected", RegexOption.IGNORE_CASE).containsMatchIn(it) && Regex("\\d+ protected").containsMatchIn(it)) }
        assertTrue(BackupCopy.INCLUDED.contains("protected folders"))
    }

    @Test
    fun `a job's title names no file and no count`() {
        listOf(BackupCopy.JOB_TITLE, BackupCopy.CHECK_JOB_TITLE).forEach { title ->
            assertFalse(title, title.any { it.isDigit() })
            assertFalse(title, title.contains("photo", ignoreCase = true))
            assertFalse(title, title.contains("protected", ignoreCase = true))
        }
    }

    private fun outcome(copied: Int = 10, bytes: Long = 5_000_000, packs: Int = 1, already: Int = 0, failed: Int = 0, tooLarge: Int = 0, mismatches: Int = 0) =
        BackupOutcome(
            filesCopied = copied, bytesCopied = bytes, packsWritten = packs, alreadyBackedUp = already,
            failed = (1..failed).map { FailedCopy(JobFixtures.candidate(it.toLong(), size = 5), CopyFailure.UNREADABLE) },
            tooLarge = (1..tooLarge).map { SkippedFile(JobFixtures.candidate(it.toLong(), size = 5), SkipReason.TOO_LARGE_FOR_PACK) },
            generation = 1, sizeMismatches = mismatches,
        )

    @Test
    fun `a finished run reports what it did and what it could not`() {
        assertEquals("Backed up 10 files (5.0 MB) in 1 pack.", BackupCopy.done(outcome()))
        val busy = BackupCopy.done(outcome(copied = 1, packs = 2, already = 8, failed = 2, tooLarge = 1, mismatches = 1))
        assertTrue(busy, busy.startsWith("Backed up 1 file (5.0 MB) in 2 packs."))
        assertTrue(busy, busy.contains("8 were already in the backup."))
        assertTrue(busy, busy.contains("2 files could not be copied"))
        assertTrue(busy, busy.contains("1 file too large for a pack was not backed up."))
        assertTrue(busy, busy.contains("Run Check a backup"))
        assertTrue(BackupCopy.done(outcome(copied = 0, packs = 0, already = 5)).startsWith("Nothing new to back up."))
    }

    @Test
    fun `a run that left something out needs the person's attention and a clean one does not`() {
        assertFalse(outcome().needsAttention)
        assertFalse(outcome(copied = 0, packs = 0, already = 5).needsAttention)
        assertTrue(outcome(failed = 1).needsAttention)
        assertTrue(outcome(tooLarge = 1).needsAttention)
        assertTrue(outcome(mismatches = 1).needsAttention)
    }

    @Test
    fun `strength is by length alone and counts characters, not bytes`() {
        assertEquals(PassphraseStrength.TOO_SHORT, PassphraseStrength.of(""))
        assertEquals(PassphraseStrength.TOO_SHORT, PassphraseStrength.of("short one"))
        assertEquals(PassphraseStrength.TOO_SHORT, PassphraseStrength.of("11 letters!"))
        assertEquals(PassphraseStrength.OK, PassphraseStrength.of("twelve chars"))
        assertEquals(PassphraseStrength.GOOD, PassphraseStrength.of("a long passphrase of words"))
        // Twelve Japanese characters are twelve characters, though thirty-six bytes.
        assertEquals(PassphraseStrength.OK, PassphraseStrength.of("日本語日本語日本語日本語"))
        // A decomposed accent is one character after NFC, so it does not pad the count.
        assertEquals(PassphraseStrength.TOO_SHORT, PassphraseStrength.of("café café c"))
        assertTrue(BackupCopy.strengthHint(PassphraseStrength.TOO_SHORT).contains("12"))
    }

    @Test
    fun `a new passphrase needs length, the same text twice and the tick, in that order`() {
        val good = "correct horse battery"
        assertEquals(PassphraseProblem.EMPTY, PassphraseForm.problem("", "", false, newSet = true))
        assertEquals(PassphraseProblem.TOO_SHORT, PassphraseForm.problem("short", "short", true, newSet = true))
        assertEquals(PassphraseProblem.MISMATCH, PassphraseForm.problem(good, "correct horse batter", true, newSet = true))
        assertEquals(PassphraseProblem.MISMATCH, PassphraseForm.problem(good, "", true, newSet = true))
        assertEquals(PassphraseProblem.NOT_UNDERSTOOD, PassphraseForm.problem(good, good, false, newSet = true))
        assertNull(PassphraseForm.problem(good, good, true, newSet = true))
    }

    @Test
    fun `the same word typed composed and decomposed is the same passphrase`() {
        val composed = "Café Zürich 12345"
        val decomposed = "Café Zürich 12345"
        assertNull(PassphraseForm.problem(composed, decomposed, true, newSet = true))
    }

    @Test
    fun `opening an existing backup asks only for something typed, whatever its length`() {
        assertNull(PassphraseForm.problem("a", "", false, newSet = false))
        assertEquals(PassphraseProblem.EMPTY, PassphraseForm.problem("", "", false, newSet = false))
    }

    @Test
    fun `the check's report reads clean or lists problems with the cut shown`() {
        val clean = VerifyReport(1, 3, 120, 5_000_000, emptyList(), 0)
        assertEquals("Checked 120 files in 3 packs (5.0 MB): everything is intact.", BackupCopy.verifyClean(clean))
        val problems = (1..9).map { VerifyProblem(ProblemKind.FILE_MISMATCH, "pack-0001.fzbp", "IMG_$it.jpg", "Its bytes are not the ones the backup recorded") }
        val text = BackupCopy.verifyProblems(VerifyReport(1, 3, 100, 5, problems, 0))
        assertTrue(text, text.startsWith("Checked the backup and found 9 problems."))
        assertTrue(text, text.contains("IMG_1.jpg"))
        assertFalse(text, text.contains("IMG_9.jpg"))
        assertTrue(text, text.contains("…and 3 more."))
    }

    @Test
    fun `the no-recovery sentence is shown to the person who is choosing a passphrase`() {
        // The words themselves are pinned in HelpCopyTest; this checks the dialog's copy leans on them.
        assertTrue(HelpCopy.Note.BACKUP_NO_RECOVERY.contains("nobody, including us, can open the backup"))
        assertTrue(HelpCopy.Hint.BACKUP_UNDERSTAND.startsWith("I understand"))
        assertTrue(BackupCopy.PASSPHRASE_SHARING.contains("Anyone who has the folder and the passphrase"))
    }
}
