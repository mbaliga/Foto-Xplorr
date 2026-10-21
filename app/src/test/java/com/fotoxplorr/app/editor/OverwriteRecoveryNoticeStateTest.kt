package com.fotoxplorr.app.editor

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which safety copies are news to the person, and letting go of them. */
class OverwriteRecoveryNoticeStateTest {

    private val dir: File = Files.createTempDirectory("fx-recovery").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, ageMillis: Long, now: Long): File =
        File(dir, name).also {
            it.writeBytes(byteArrayOf(1))
            it.setLastModified(now - ageMillis)
        }

    @Test
    fun `a copy already announced is not news but a new one is`() {
        val a = File("IMG_1.jpg.1.bak")
        val b = File("IMG_2.jpg.2.bak")

        assertTrue(unseenSafetyCopies(listOf(a), setOf(a.name)).isEmpty())
        assertEquals(listOf(b), unseenSafetyCopies(listOf(a, b), setOf(a.name)))
        assertEquals(listOf(a), unseenSafetyCopies(listOf(a), emptySet()))
    }

    @Test
    fun `files that are not safety copies are never news`() {
        assertTrue(unseenSafetyCopies(listOf(File("notes.txt"), File("backup-1.tmp")), emptySet()).isEmpty())
    }

    @Test
    fun `the notice counts only safety copies`() {
        assertNull(orphanedBackupNotice(listOf(File("notes.txt"))))
        assertEquals(
            "2 earlier saves over a photo did not finish. Check the photos you edited last.",
            orphanedBackupNotice(listOf(File("a.1.bak"), File("backup-9.bin"), File("x.txt"))),
        )
    }

    @Test
    fun `deleting safety copies removes every bak and old in-flight copy and nothing else`() {
        val now = 1_000_000_000_000L
        val bak = file("IMG_1.jpg.1.bak", ageMillis = 1000, now = now)
        val oldBin = file("backup-1.bin", ageMillis = 2 * IN_FLIGHT_GRACE_MS, now = now)
        val other = file("notes.txt", ageMillis = 100 * IN_FLIGHT_GRACE_MS, now = now)

        assertEquals(2, deleteSafetyCopies(dir, now))

        assertFalse(bak.exists() || oldBin.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `deleting safety copies leaves a young in-flight copy a running replace may need`() {
        val now = 1_000_000_000_000L
        val young = file("backup-2.bin", ageMillis = IN_FLIGHT_GRACE_MS / 2, now = now)

        assertEquals(0, deleteSafetyCopies(dir, now))

        assertTrue(young.exists())
    }

    @Test
    fun `deleting safety copies in a directory that does not exist is zero and does not throw`() {
        assertEquals(0, deleteSafetyCopies(File(dir, "nowhere"), 0L))
    }
}
