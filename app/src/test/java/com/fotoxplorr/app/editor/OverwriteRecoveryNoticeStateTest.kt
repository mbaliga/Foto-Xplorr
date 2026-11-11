package com.fotoxplorr.app.editor

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which safety copies are still news to the person, and letting go of them. */
class OverwriteRecoveryNoticeStateTest {

    private val dir: File = Files.createTempDirectory("fx-recovery").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, now: Long, ageMillis: Long = 1000): File =
        File(dir, name).also {
            it.writeBytes(byteArrayOf(1))
            it.setLastModified(now - ageMillis)
        }

    @Test
    fun `a copy already answered is not news but a new one is`() {
        val now = 1_000_000_000_000L
        val a = file("IMG_1.jpg.1.bak", now)
        val b = file("IMG_2.jpg.2.bak", now)

        assertEquals(listOf(b.name), unresolvedSafetyCopies(dir, setOf(a.name)).map { it.file.name })
        assertEquals(setOf(a.name, b.name), unresolvedSafetyCopies(dir, emptySet()).map { it.file.name }.toSet())
    }

    @Test
    fun `files that are not safety copies are never news`() {
        val now = 1_000_000_000_000L
        file("notes.txt", now)
        file("backup-1.tmp", now)
        file("IMG_1.jpg.1.bak.meta", now)

        assertTrue(unresolvedSafetyCopies(dir, emptySet()).isEmpty())
    }

    @Test
    fun `a copy a running replace owns is not news however young or old`() {
        val now = 1_000_000_000_000L
        val live = file("backup-live.bin", now, ageMillis = 5)
        val orphan = file("backup-dead.bin", now, ageMillis = 5)

        val news = unresolvedSafetyCopies(dir, emptySet()) { it.name == live.name }

        assertEquals(listOf(orphan.name), news.map { it.file.name })
    }

    @Test
    fun `listing is free of side effects`() {
        val now = 1_000_000_000_000L
        file("IMG_1.jpg.1.bak", now)

        val first = unresolvedSafetyCopies(dir, emptySet()).map { it.file.name }
        val second = unresolvedSafetyCopies(dir, emptySet()).map { it.file.name }

        assertEquals(first, second)
        assertEquals(1, first.size)
    }

    @Test
    fun `deleting safety copies removes every copy and its sidecar and nothing else`() {
        val now = 1_000_000_000_000L
        val bak = file("IMG_1.jpg.1.bak", now)
        val bin = file("backup-1.bin", now)
        writeSidecar(bak, "content://m/1", "IMG_1.jpg", "image/jpeg", 1L)
        val other = file("notes.txt", now, ageMillis = 100_000_000)

        assertEquals(2, deleteSafetyCopies(dir))

        assertFalse(bak.exists() || bin.exists() || sidecarOf(bak).exists())
        assertTrue(other.exists())
    }

    @Test
    fun `deleting only the named copies leaves the others and never touches a live one`() {
        val now = 1_000_000_000_000L
        val a = file("a.1.bak", now)
        val b = file("b.2.bak", now)
        val live = file("backup-live.bin", now)

        assertEquals(1, deleteSafetyCopies(dir, only = setOf(a.name, live.name)) { it.name == live.name })

        assertFalse(a.exists())
        assertTrue(b.exists() && live.exists())
    }

    @Test
    fun `deleting safety copies in a directory that does not exist is zero and does not throw`() {
        assertEquals(0, deleteSafetyCopies(File(dir, "nowhere")))
    }
}
