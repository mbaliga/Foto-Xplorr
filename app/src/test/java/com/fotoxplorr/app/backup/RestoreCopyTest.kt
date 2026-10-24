package com.fotoxplorr.app.backup

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the restore tells the person, from real preparations and outcomes. No file name is ever in it. */
class RestoreCopyTest {
    private lateinit var dir: File

    @Before
    fun folder() {
        dir = Files.createTempDirectory("fotoz-restorecopy-test").toFile()
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private val facts = RestoreCopy.ReviewFacts(freeBytes = 10_000_000, locationApplicable = true, locationGranted = true)

    private fun texts(lines: List<BackupCopy.Line>) = lines.map { it.text }

    @Test
    fun `the first card says what the backup holds`() {
        val made = RestoreFixtures.backup(dir, count = 4)
        val lines = texts(RestoreCopy.summary(made.set().info))

        assertTrue(lines.toString(), lines[0].startsWith("This backup"))
        assertTrue(lines[0], lines[0].contains("by Fotoz 9.9.9"))
        assertTrue(lines[0], lines[0].contains("4 photos"))
        assertTrue(lines.toString(), lines.any { it.startsWith("Tags and collections:") && it.contains("1 collection") })
    }

    @Test
    fun `the first card warns about files whose pack is gone`() {
        val made = RestoreFixtures.backup(dir, count = 9)
        File(dir, made.packs()[0]).delete()
        val lines = RestoreCopy.summary(made.set().info)
        assertTrue(lines.any { it.severity == BackupCopy.Severity.WARNING && it.text.contains("pack is not in the folder") })
    }

    @Test
    fun `the review says what will be copied, what is here, what is renamed and that nothing is replaced`() {
        val made = RestoreFixtures.backup(dir, count = 5)
        val phone = FakePhone()
        phone.put(700, "IMG_1.jpg", "DCIM/Camera/", made.source.bytes(1))
        phone.put(701, "IMG_2.jpg", "DCIM/Camera/", byteArrayOf(1, 2, 3))
        val lines = texts(RestoreCopy.review(RestoreFixtures.prepare(made.set(), phone), facts))

        assertTrue(lines.toString(), lines.any { it.contains("4 files") && it.contains("will be copied back") })
        assertTrue(lines.toString(), lines.any { it.contains("1 already is on this phone") })
        assertTrue(lines.toString(), lines.any { it.contains("1 will be renamed") && it.contains("(restored)") })
        assertTrue(lines.contains(RestoreCopy.NEVER_OVERWRITES))
        assertTrue(lines.contains(RestoreCopy.SAFE_TO_REPEAT))
    }

    @Test
    fun `the review warns when there is not room`() {
        val made = RestoreFixtures.backup(dir, count = 3)
        val lines = RestoreCopy.review(RestoreFixtures.prepare(made.set(), FakePhone()), facts.let { RestoreCopy.ReviewFacts(100, true, true) })
        assertTrue(lines.any { it.severity == BackupCopy.Severity.PROBLEM && it.text.contains("Not enough room") })
    }

    @Test
    fun `the review warns that a missing location permission can make copies`() {
        val made = RestoreFixtures.backup(dir, count = 3)
        val prep = RestoreFixtures.prepare(made.set(), FakePhone())
        val without = RestoreCopy.review(prep, RestoreCopy.ReviewFacts(10_000_000, locationApplicable = true, locationGranted = false))
        assertTrue(without.any { it.text == RestoreCopy.LOCATION_MISSING_RESTORE })
        val with = RestoreCopy.review(prep, facts)
        assertFalse(with.any { it.text == RestoreCopy.LOCATION_MISSING_RESTORE })
        val before10 = RestoreCopy.review(prep, RestoreCopy.ReviewFacts(10_000_000, locationApplicable = false, locationGranted = false))
        assertFalse(before10.any { it.text == RestoreCopy.LOCATION_MISSING_RESTORE })
    }

    @Test
    fun `the tags-only review says no photo is copied and that locks come with everything`() {
        val made = RestoreFixtures.backup(dir, count = 3)
        val phone = FakePhone()
        made.candidates.forEachIndexed { i, c -> phone.put(500L + i, c.displayName, c.relativePath!!, made.source.bytes(c.mediaId), taken = c.dateTakenMillis) }
        val lines = texts(RestoreCopy.review(RestoreFixtures.prepare(made.set(), phone, RestoreScope.TAGS_ONLY), facts))

        assertTrue(lines.toString(), lines.any { it.contains("3 photos from the backup were found on this phone") })
        assertTrue(lines.contains(RestoreCopy.TAGS_MERGED))
        assertTrue(lines.contains(RestoreCopy.ONLY_WITH_EVERYTHING))
        assertFalse(lines.toString(), lines.any { it.contains("copied back") })
    }

    @Test
    fun `the result reports restored, already here, renamed, missing and skipped`() {
        val made = RestoreFixtures.backup(dir, count = 9)
        File(dir, made.packs()[1]).delete()
        val phone = FakePhone()
        phone.put(700, "IMG_1.jpg", "DCIM/Camera/", made.source.bytes(1))
        phone.put(701, "IMG_2.jpg", "DCIM/Camera/", byteArrayOf(5))
        val outcome = RestoreFixtures.restore(made, phone)
        val text = RestoreCopy.done(outcome)

        assertTrue(text, text.startsWith("Restored "))
        assertTrue(text, text.contains("was already on the phone") || text.contains("were already on the phone"))
        assertTrue(text, text.contains("came back as \"name (restored)\""))
        assertTrue(text, text.contains("pack is not in the folder") || text.contains("pack is missing"))
        assertTrue(outcome.needsAttention)
    }

    @Test
    fun `a clean restore needs no attention and says what was put back`() {
        val made = RestoreFixtures.backup(dir, count = 3)
        val outcome = RestoreFixtures.restore(made, FakePhone())
        assertFalse(outcome.needsAttention)
        val text = RestoreCopy.done(outcome)
        assertTrue(text, text.startsWith("Restored 3 files"))
        assertTrue(text, text.contains("Added "))
    }

    @Test
    fun `nothing in any card or result names a file`() {
        val made = RestoreFixtures.backup(dir, count = 5)
        File(dir, made.packs()[0]).delete()
        val phone = FakePhone()
        phone.put(701, "IMG_5.jpg", "DCIM/Camera/", byteArrayOf(5))
        val prep = RestoreFixtures.prepare(made.set(), phone)
        val everything = RestoreCopy.summary(made.set().info).map { it.text } +
            RestoreCopy.review(prep, facts).map { it.text } +
            RestoreCopy.done(RestoreFixtures.restore(made, phone))
        for (text in everything) {
            assertFalse(text, text.contains("IMG_"))
            assertFalse(text, text.contains(".jpg") && !text.contains("name (restored).jpg"))
            assertFalse(text, text.contains("DCIM"))
        }
    }

    @Test
    fun `job titles carry no file name and no count`() {
        assertEquals("Restoring your library", RestoreCopy.JOB_TITLE)
        assertTrue(isBackupJob(RestoreCopy.JOB_TITLE))
        assertTrue(isBackupJob(BackupCopy.JOB_TITLE))
        assertTrue(isBackupJob(BackupCopy.CHECK_JOB_TITLE))
        assertFalse(isBackupJob("Saving tags and collections"))
    }

    @Test
    fun `plural and singular both read as sentences`() {
        val made = RestoreFixtures.backup(dir, count = 1)
        val text = RestoreCopy.done(RestoreFixtures.restore(made, FakePhone()))
        assertTrue(text, text.startsWith("Restored 1 file "))
    }
}
