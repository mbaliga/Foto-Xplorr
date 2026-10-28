package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words of the Select text mode: the status line, which is also its live region. */
class TextSelectCopyTest {

    private fun status(selected: Int = 0, wordLevel: Boolean = true, hasText: Boolean = true, working: Boolean = false) =
        TextSelectCopy.status(selected, wordLevel, hasText, working)

    @Test
    fun `a selection is counted in words, or in blocks before the word pass lands`() {
        assertEquals("1 word selected", status(selected = 1))
        assertEquals("5 words selected", status(selected = 5))
        assertEquals("1 block selected", status(selected = 1, wordLevel = false))
        assertEquals("3 blocks selected", status(selected = 3, wordLevel = false))
    }

    @Test
    fun `with nothing selected it says what to do`() {
        assertEquals(TextSelectCopy.HINT, status())
    }

    @Test
    fun `while the words are still being read the hint says so`() {
        val line = status(wordLevel = false, working = true)
        assertEquals("${TextSelectCopy.HINT}. ${TextSelectCopy.READING_WORDS}", line)
        // Once the word layout is in force there is nothing left to wait for.
        assertEquals(TextSelectCopy.HINT, status(wordLevel = true, working = true))
    }

    @Test
    fun `no text is a statement of fact, not a failure`() {
        assertEquals("No selectable text found", status(hasText = false))
        assertEquals("Looking for text…", status(hasText = false, working = true))
        // The bundled recogniser reads Latin script only: the line must not blame the photo or the app.
        assertFalse(TextSelectCopy.NOTHING_FOUND.contains("fail", ignoreCase = true))
        assertFalse(TextSelectCopy.NOTHING_FOUND.contains("error", ignoreCase = true))
        assertFalse(TextSelectCopy.NOTHING_FOUND.contains("couldn", ignoreCase = true))
    }

    @Test
    fun `a selection wins over every other state`() {
        assertEquals("2 words selected", status(selected = 2, hasText = false, working = true))
    }

    @Test
    fun `copied repeats the count`() {
        assertEquals("Copied 1 word", TextSelectCopy.copied(1, wordLevel = true))
        assertEquals("Copied 4 words", TextSelectCopy.copied(4, wordLevel = true))
        assertEquals("Copied 2 blocks", TextSelectCopy.copied(2, wordLevel = false))
    }

    @Test
    fun `the screen reader is told drawing is not available and where the way in is`() {
        val line = TextSelectCopy.OVERLAY_DESCRIPTION
        assertTrue(line.contains("Select all") && line.contains("Copy"))
    }
}
