package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words the export says: one short sentence each (owner feedback #6), and none that names the machinery. */
class ReelExportCopyTest {
    private val sentences = listOf(
        ReelCopy.SAVED, ReelCopy.SAVED_SMALLER, ReelCopy.SAVED_CUTS, ReelCopy.SAVED_SILENT, ReelCopy.PROTECTED, ReelCopy.NO_ENCODER,
        ReelCopy.SOURCE_UNREADABLE, ReelCopy.OUT_OF_MEMORY, ReelCopy.NO_SPACE, ReelCopy.SAVE_FAILED, ReelCopy.FAILED, ReelCopy.PLACE_LEFT_IN,
        ReelCopy.CANCELLED, ReelCopy.SOUND_UNREADABLE, ReelCopy.SOUND_STARTS_LATE, ReelCopy.SOUND_FAILED, ReelCopy.SOUND_NONE,
        ReelCopy.noLongerAvailable(3), ReelCopy.cannotRead(12),
    )

    @Test
    fun `each sentence is short and ends like a sentence`() {
        for (s in sentences) {
            assertTrue(s, s.endsWith(".") && s.length < 70 && !s.contains("  "))
            assertEquals("one sentence: $s", 1, s.count { it == '.' })
        }
        assertTrue(ReelCopy.SOUND_ASK.endsWith("?") && ReelCopy.SOUND_ASK.length < 70)
    }

    @Test
    fun `no sentence names the machinery`() {
        val banned = listOf("codec", "Media3", "Transformer", "mux", "MediaStore", "exception", "encoder ", "H.264", "AAC", "pixels", "IO ")
        for (s in sentences + listOf(ReelCopy.SOUND_ASK, ReelCopy.JOB_TITLE)) {
            for (word in banned) assertTrue("$s mentions $word", !s.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `the labels are short and say what they do`() {
        for (s in listOf(ReelCopy.SOUND_PICK, ReelCopy.SOUND_REMOVE, ReelCopy.CLIP_SOUND, ReelCopy.JOB_TITLE, ReelCopy.soundFile("Holiday.mp3"))) {
            assertTrue(s, s.length < 40 && s.isNotBlank())
        }
        assertEquals("Sound: Holiday.mp3", ReelCopy.soundFile("Holiday.mp3"))
        assertEquals("Item 3 is no longer available.", ReelCopy.noLongerAvailable(3))
        assertEquals("Item 12 could not be read.", ReelCopy.cannotRead(12))
    }

    @Test
    fun `a sound problem is told apart from a failure`() {
        assertTrue(ReelCopy.SOUND_UNREADABLE != ReelCopy.SOURCE_UNREADABLE)
        assertTrue(ReelCopy.SOUND_FAILED != ReelCopy.FAILED)
    }
}
