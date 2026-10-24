package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Back undoes next is a priority list across eight independent pieces of state. The order is
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
        mapOpen: Boolean = false,
        timeEditorShown: Boolean = false,
    ) = paneBackTarget(
        roomOpen, settingsOpen, legacyScreenOpen, selectionActive, searchVisible, routeIsRoot, mapOpen,
        timeEditorShown,
    )

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
    fun `the map alone is closed by Back and leaves the route where it was`() {
        assertEquals(PaneBackTarget.CLOSE_MAP, target(mapOpen = true))
        assertEquals(PaneBackTarget.CLOSE_MAP, target(mapOpen = true, routeIsRoot = false))
    }

    @Test
    fun `a search typed over the map is closed before the map`() {
        assertEquals(PaneBackTarget.CLOSE_SEARCH, target(mapOpen = true, searchVisible = true))
    }

    @Test
    fun `a room and a pane outrank the map`() {
        assertEquals(PaneBackTarget.CLOSE_ROOM, target(mapOpen = true, roomOpen = true))
        assertEquals(PaneBackTarget.CLOSE_SETTINGS, target(mapOpen = true, settingsOpen = true))
        assertEquals(PaneBackTarget.CLOSE_LEGACY_SCREEN, target(mapOpen = true, legacyScreenOpen = true))
    }

    @Test
    fun `the map is left before the album it is showing`() {
        val first = target(mapOpen = true, routeIsRoot = false)
        assertEquals(PaneBackTarget.CLOSE_MAP, first)
        // With the map closed, the same state's next Back leaves the route.
        assertEquals(PaneBackTarget.LEAVE_ROUTE, target(mapOpen = false, routeIsRoot = false))
    }

    @Test
    fun `a caller that predates the map still compiles and means no map`() {
        assertEquals(
            PaneBackTarget.NONE,
            paneBackTarget(
                roomOpen = false, settingsOpen = false, legacyScreenOpen = false,
                selectionActive = false, searchVisible = false, routeIsRoot = true,
            ),
        )
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
            target(mapOpen = true),
            target(timeEditorShown = true),
        )
        assertEquals(8, singles.toSet().size)
        assertFalse(PaneBackTarget.NONE in singles)
    }

    @Test
    fun `the time panel is closed before the grid state under it`() {
        assertEquals(PaneBackTarget.CLOSE_TIME_EDITOR, target(timeEditorShown = true, mapOpen = true))
        assertEquals(
            PaneBackTarget.CLOSE_TIME_EDITOR,
            target(timeEditorShown = true, searchVisible = true, routeIsRoot = false),
        )
        // And a room still outranks it.
        assertEquals(PaneBackTarget.CLOSE_ROOM, target(timeEditorShown = true, roomOpen = true))
    }

    @Test
    fun `the time panel is shown only where it is drawn`() {
        assertTrue(timeEditorShown(open = true, selectionActive = false, paneTakenOver = false, rangeApplies = true))
        assertFalse(timeEditorShown(open = false, selectionActive = false, paneTakenOver = false, rangeApplies = true))
        // Hidden by another route with the flag still set: Back must not be claimed by it.
        assertFalse(timeEditorShown(open = true, selectionActive = true, paneTakenOver = false, rangeApplies = true))
        assertFalse(timeEditorShown(open = true, selectionActive = false, paneTakenOver = true, rangeApplies = true))
        assertFalse(timeEditorShown(open = true, selectionActive = false, paneTakenOver = false, rangeApplies = false))
    }

    @Test
    fun `a flag left set under a selection does not swallow Back`() {
        val shown = timeEditorShown(open = true, selectionActive = true, paneTakenOver = false, rangeApplies = true)
        assertEquals(PaneBackTarget.CLEAR_SELECTION, target(selectionActive = true, timeEditorShown = shown))
        val pinnedSettings = timeEditorShown(open = true, selectionActive = false, paneTakenOver = true, rangeApplies = true)
        assertEquals(PaneBackTarget.CLOSE_SETTINGS, target(settingsOpen = true, timeEditorShown = pinnedSettings))
    }
}
