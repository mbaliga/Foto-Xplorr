package com.fotoxplorr.app.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map pane (and with it the street map's network fetch) waits for the rail to stop. */
class RailTurningTest {

    @Test
    fun `a scrub that rests on the Map word does not settle the pane`() {
        // Pinned rail: no room is open, the word was committed, the finger is still down.
        assertFalse(mapPaneSettled(atHome = true, railTurning = true))
    }

    @Test
    fun `an open room does not settle the pane`() {
        assertFalse(mapPaneSettled(atHome = false, railTurning = false))
        assertFalse(mapPaneSettled(atHome = false, railTurning = true))
    }

    @Test
    fun `at home with the rail at rest settles the pane`() {
        assertTrue(mapPaneSettled(atHome = true, railTurning = false))
    }
}
