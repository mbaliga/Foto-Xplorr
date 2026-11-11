package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the Select text gestures and entry, pinned without a screen. */
class TextSelectGesturesTest {

    @Test
    fun `touch slop is a screen distance, so it shrinks by the zoom in the zoom layer's pixels`() {
        assertEquals(16f, textSelectSlop(16f, 1f), 0.0001f)
        assertEquals(4f, textSelectSlop(16f, 4f), 0.0001f)
        assertEquals(16f / 6f, textSelectSlop(16f, 6f), 0.0001f)
    }

    @Test
    fun `a zoom of zero or less cannot blow the slop up`() {
        val floor = 16f / TextSelectTouch.MIN_ZOOM
        assertEquals(floor, textSelectSlop(16f, 0f), 0.0001f)
        assertEquals(floor, textSelectSlop(16f, -3f), 0.0001f)
    }

    @Test
    fun `Select text needs a readable photo and something to supply its words`() {
        assertTrue(selectTextOffered(canLift = true, hasLayoutProvider = true, storedBlockCount = 0))
        assertTrue(selectTextOffered(canLift = true, hasLayoutProvider = false, storedBlockCount = 2))
        assertTrue(selectTextOffered(canLift = true, hasLayoutProvider = true, storedBlockCount = 2))
        // A photo from another app in a host with no reader: the mode could only say "none found".
        assertFalse(selectTextOffered(canLift = true, hasLayoutProvider = false, storedBlockCount = 0))
        assertFalse(selectTextOffered(canLift = false, hasLayoutProvider = true, storedBlockCount = 2))
    }
}
