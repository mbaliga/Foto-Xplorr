package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the Select text toolbar's own actions can be used, pinned without a screen. */
class TextSelectActionStatesTest {

    private fun states(
        selected: Boolean = true,
        search: Boolean = true,
        speaking: Boolean = false,
        ready: Boolean = true,
    ) = SelectionActionStates.of(
        hasSelection = selected,
        searchAvailable = search,
        speaking = speaking,
        listenReady = ready,
    )

    @Test
    fun `with a selection everything is usable`() {
        val s = states()
        assertTrue(s.shareEnabled)
        assertEquals(true, s.searchEnabled)
        assertTrue(s.listenEnabled)
        assertFalse(s.listenIsStop)
        assertTrue(s.translateEnabled)
    }

    @Test
    fun `with nothing selected each waits for words`() {
        val s = states(selected = false)
        assertFalse(s.shareEnabled)
        assertEquals(false, s.searchEnabled)
        assertFalse(s.listenEnabled)
        assertFalse(s.translateEnabled)
    }

    @Test
    fun `Search is left out, not greyed, when the host has no library search`() {
        assertNull(states(search = false).searchEnabled)
        assertNull(states(search = false, selected = false).searchEnabled)
    }

    @Test
    fun `a voice proven missing turns Listen off but not the other actions`() {
        val s = states(ready = false)
        assertFalse(s.listenEnabled)
        assertTrue(s.shareEnabled)
        assertTrue(s.translateEnabled)
    }

    @Test
    fun `while reading the button is Stop and stays usable whatever the selection did`() {
        val s = states(selected = false, speaking = true, ready = false)
        assertTrue(s.listenIsStop)
        assertTrue(s.listenEnabled)
    }
}
