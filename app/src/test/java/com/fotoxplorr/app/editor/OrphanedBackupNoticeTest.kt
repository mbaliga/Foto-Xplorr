package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the person is told about safety copies a replace left behind, and how the dialog carries them across a recreation. */
class OrphanedBackupNoticeTest {

    private fun entry(file: String, photo: String = "") = RecoveryEntry(file, photo)

    @Test
    fun `nothing left is no notice`() {
        assertNull(recoveryNoticeMessage(emptyList()))
    }

    @Test
    fun `one copy with no sidecar keeps the old generic wording`() {
        val notice = recoveryNoticeMessage(listOf(entry("backup-123.bin")))!!
        assertEquals("An earlier save over a photo did not finish. Check the photos you edited last.", notice)
    }

    @Test
    fun `one copy names its photo`() {
        val notice = recoveryNoticeMessage(listOf(entry("backup-1.bin", "IMG_0042.jpg")))!!
        assertTrue(notice, "\"IMG_0042.jpg\"" in notice)
        assertTrue(notice, "did not finish" in notice && "may be damaged" in notice)
    }

    @Test
    fun `several copies are counted and every known name is listed`() {
        val notice = recoveryNoticeMessage(listOf(entry("a.1.bak", "a.jpg"), entry("backup-9.bin", "b.png"), entry("c.bak")))!!
        assertTrue(notice, notice.startsWith("3 earlier saves"))
        assertTrue(notice, "\"a.jpg\"" in notice && "\"b.png\"" in notice)
        assertTrue(notice, "and 1 more" in notice)
    }

    @Test
    fun `several unnamed copies are only counted`() {
        assertEquals(
            "2 earlier saves over a photo did not finish. Check the photos you edited last.",
            recoveryNoticeMessage(listOf(entry("a.1.bak"), entry("backup-9.bin"))),
        )
    }

    @Test
    fun `entries survive the saved-state string`() {
        val copies = listOf(
            SafetyCopy(java.io.File("backup-1.bin"), "IMG (2).jpg", "image/jpeg", "content://x/1", 5L),
            SafetyCopy(java.io.File("x.2.bak"), null, null, null, -1L),
        )

        val decoded = decodeRecoveryEntries(copies.toRecoveryEntries().encodeRecoveryEntries())

        assertEquals(listOf("backup-1.bin", "x.2.bak"), decoded.map { it.fileName })
        assertEquals(listOf("IMG (2).jpg", ""), decoded.map { it.photoName })
        assertTrue(decodeRecoveryEntries("").isEmpty())
    }

    @Test
    fun `entries built from safety copies never carry a separator in the photo name`() {
        val copy = SafetyCopy(java.io.File("backup-1.bin"), "a\tb\nc.jpg", "image/jpeg", "content://x", 5L)
        val encoded = listOf(copy).toRecoveryEntries().encodeRecoveryEntries()
        assertEquals(1, encoded.lines().size)
        assertEquals("a b c.jpg", decodeRecoveryEntries(encoded).single().photoName)
        assertFalse(encoded.substringAfter('\t').contains('\t'))
    }
}
