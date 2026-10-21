package com.fotoxplorr.app.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the person is told at app start about safety copies the sweep left behind. */
class OrphanedBackupNoticeTest {

    @Test
    fun `nothing left is no notice`() {
        assertNull(orphanedBackupNotice(emptyList()))
    }

    @Test
    fun `files that are not safety copies are no notice`() {
        assertNull(orphanedBackupNotice(listOf(File("notes.txt"), File("backup-1.tmp"))))
    }

    @Test
    fun `one remaining copy of either kind is one notice that promises no retrieval`() {
        listOf("backup-123.bin", "IMG_0001.jpg.1700000000000.bak").forEach { name ->
            val notice = orphanedBackupNotice(listOf(File(name)))!!
            assertTrue(notice, "did not finish" in notice)
            assertFalse(notice, "restore" in notice || "recover" in notice || "open it" in notice)
        }
    }

    @Test
    fun `several copies are counted`() {
        val notice = orphanedBackupNotice(listOf(File("backup-1.bin"), File("a.1.bak"), File("other.txt")))
        assertEquals("2 earlier saves over a photo did not finish. Check the photos you edited last.", notice)
    }
}
