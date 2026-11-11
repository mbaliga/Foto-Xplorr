package com.fotoxplorr.app.ui

import dev.aarso.cellshell.RoomEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The two rules the rooms share beyond the table itself: which rooms survive a pane taking over
 * the place the photos are, and which system bars a room clears on each edge.
 */
class RoomGeographyPaneTest {

    private val slots = roomSlots(navigation = "nav", details = "details", actions = "actions", viewOptions = "options")

    @Test
    fun `over a pane only the navigation room remains`() {
        val over = slots.navigationOnlyWhen(paneTakenOver = true)
        assertEquals("nav", over.left)
        assertNull(over.top)
        assertNull(over.right)
        assertNull(over.bottom)
    }

    @Test
    fun `with no pane every room is kept`() {
        assertSame(slots, slots.navigationOnlyWhen(paneTakenOver = false))
    }

    @Test
    fun `a top room clears the status bar and a bottom room the navigation bar`() {
        assertEquals(SystemBarsCleared.STATUS, systemBarsClearedBy(RoomEdge.TOP))
        assertEquals(SystemBarsCleared.NAVIGATION, systemBarsClearedBy(RoomEdge.BOTTOM))
    }

    @Test
    fun `a side room runs the full height so it clears both bars`() {
        assertEquals(SystemBarsCleared.BOTH, systemBarsClearedBy(RoomEdge.LEFT))
        assertEquals(SystemBarsCleared.BOTH, systemBarsClearedBy(RoomEdge.RIGHT))
    }

    @Test
    fun `every kind's room clears what its edge needs`() {
        assertEquals(SystemBarsCleared.STATUS, systemBarsClearedBy(RoomGeography.Details))
        assertEquals(SystemBarsCleared.NAVIGATION, systemBarsClearedBy(RoomGeography.Actions))
        assertEquals(SystemBarsCleared.BOTH, systemBarsClearedBy(RoomGeography.ViewOptions))
        assertEquals(SystemBarsCleared.BOTH, systemBarsClearedBy(RoomGeography.Navigation))
    }
}
