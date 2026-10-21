package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Back undoes next is a priority list across seven independent pieces of state. The order is
 * the behaviour: a pane left on top of a closed room, or a search cleared before the pane over it,
 * is a Back press that does the wrong thing.
 */
class PaneBackTargetTest {

    private fun target(
        roomOpen: Boolean = false,
        settingsOpen: Boolean = false,
        legacyScreenOpen: Boolean = false,
        selectionActive: Boolean = false,
        searchVisible: Boolean = false,
        routeIsRoot: Boolean = true,
    ) = paneBackTarget(roomOpen, settingsOpen, legacyScreenOpen, selectionActive, searchVisible, routeIsRoot)

    @Test
    fun `a bare grid leaves Back to the system`() {
        assertEquals(PaneBackTarget.NONE, target())
    }

    @Test
    fun `an open room is closed before anything else`() {
        assertEquals(
            PaneBackTarget.CLOSE_ROOM,
            target(
                roomOpen = true,
                settingsOpen = true,
                legacyScreenOpen = true,
                selectionActive = true,
                searchVisible = true,
                routeIsRoot = false,
            ),
        )
    }

    @Test
    fun `settings is handed back before the grid state under it`() {
        assertEquals(
            PaneBackTarget.CLOSE_SETTINGS,
            target(settingsOpen = true, selectionActive = true, searchVisible = true, routeIsRoot = false),
        )
    }

    @Test
    fun `a destination screen is closed before selection search and route`() {
        assertEquals(
            PaneBackTarget.CLOSE_LEGACY_SCREEN,
            target(legacyScreenOpen = true, selectionActive = true, searchVisible = true, routeIsRoot = false),
        )
    }

    @Test
    fun `selection then search then route`() {
        assertEquals(
            PaneBackTarget.CLEAR_SELECTION,
            target(selectionActive = true, searchVisible = true, routeIsRoot = false),
        )
        assertEquals(PaneBackTarget.CLOSE_SEARCH, target(searchVisible = true, routeIsRoot = false))
        assertEquals(PaneBackTarget.LEAVE_ROUTE, target(routeIsRoot = false))
    }

    @Test
    fun `either pane counts as a takeover and neither does not`() {
        assertFalse(paneTakenOver(settingsOpen = false, legacyScreenOpen = false))
        assertTrue(paneTakenOver(settingsOpen = true, legacyScreenOpen = false))
        assertTrue(paneTakenOver(settingsOpen = false, legacyScreenOpen = true))
    }

    @Test
    fun `every state that is not the bare grid has a Back target`() {
        // BackHandler is enabled on exactly "target != NONE" for the non-room states; a new state
        // added to the list but not to the handler's enabled condition would swallow nothing and
        // exit the app instead. Enumerate the single-flag cases so a new one cannot be forgotten.
        val singles = listOf(
            target(roomOpen = true),
            target(settingsOpen = true),
            target(legacyScreenOpen = true),
            target(selectionActive = true),
            target(searchVisible = true),
            target(routeIsRoot = false),
        )
        assertEquals(6, singles.toSet().size)
        assertFalse(PaneBackTarget.NONE in singles)
    }
}
