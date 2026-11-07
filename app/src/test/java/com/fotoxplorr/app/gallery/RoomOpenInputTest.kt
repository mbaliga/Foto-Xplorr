package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.GalleryShortcut
import com.fotoxplorr.app.adaptive.MoveDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An open room covers the grid exactly as a pane does: Escape leaves it by the same order as Back,
 * and every other shortcut is ignored so Delete, Ctrl+A and Enter cannot act on photos behind it.
 * Plus where the notification shade is drawn, which decides the top reserve.
 */
class RoomOpenInputTest {

    private val everyShortcut = listOf(
        GalleryShortcut.MoveSelection(MoveDirection.UP),
        GalleryShortcut.MoveSelection(MoveDirection.DOWN),
        GalleryShortcut.MoveSelection(MoveDirection.LEFT),
        GalleryShortcut.MoveSelection(MoveDirection.RIGHT),
        GalleryShortcut.OpenFocused,
        GalleryShortcut.CloseOrClear,
        GalleryShortcut.TrashSelected,
        GalleryShortcut.SelectAll,
        GalleryShortcut.FocusSearch,
        GalleryShortcut.ZoomIn,
        GalleryShortcut.ZoomOut,
    )

    @Test
    fun `with a room open only Escape acts`() {
        everyShortcut.forEach { shortcut ->
            assertEquals(
                "$shortcut over a room",
                shortcut == GalleryShortcut.CloseOrClear,
                shortcutActsOverPane(shortcut, paneTakenOver = false, roomOpen = true),
            )
        }
    }

    @Test
    fun `a room over a pane is still only Escape`() {
        everyShortcut.forEach { shortcut ->
            assertEquals(
                "$shortcut over a room over a pane",
                shortcut == GalleryShortcut.CloseOrClear,
                shortcutActsOverPane(shortcut, paneTakenOver = true, roomOpen = true),
            )
        }
    }

    @Test
    fun `with neither a pane nor a room every shortcut acts`() {
        everyShortcut.forEach {
            assertTrue("$it", shortcutActsOverPane(it, paneTakenOver = false, roomOpen = false))
        }
    }

    @Test
    fun `Escape follows the Back order when a room or a pane is up`() {
        assertTrue(escapeFollowsBackOrder(paneTakenOver = false, roomOpen = true))
        assertTrue(escapeFollowsBackOrder(paneTakenOver = true, roomOpen = false))
        assertTrue(escapeFollowsBackOrder(paneTakenOver = true, roomOpen = true))
        assertFalse(escapeFollowsBackOrder(paneTakenOver = false, roomOpen = false))
    }

    @Test
    fun `Escape with a room open and a selection closes the room first`() {
        // The target Escape hands to performBack when escapeFollowsBackOrder: the room, ahead of
        // the selection, the search and the route it would otherwise have cleared.
        assertEquals(
            PaneBackTarget.CLOSE_ROOM,
            paneBackTarget(
                roomOpen = true, settingsOpen = false, legacyScreenOpen = false,
                selectionActive = true, searchVisible = true, routeIsRoot = false,
            ),
        )
    }

    // ---- where the shade is drawn ----

    @Test
    fun `the shade is drawn on the root grid and on the panes`() {
        assertTrue(shadeIsDrawn(paneTakenOver = false, mapOpen = false, routeIsRoot = true))
        assertTrue(shadeIsDrawn(paneTakenOver = true, mapOpen = false, routeIsRoot = false))
        // A pane wins over whatever the covered grid is doing, the map included.
        assertTrue(shadeIsDrawn(paneTakenOver = true, mapOpen = true, routeIsRoot = true))
    }

    @Test
    fun `the shade is not drawn inside a route`() {
        assertFalse(shadeIsDrawn(paneTakenOver = false, mapOpen = false, routeIsRoot = false))
    }

    @Test
    fun `the shade is not drawn on the map even at the root`() {
        // The map is drawn in the grid's place with no shade over it; reserving the shade's height
        // there would leave a dead strip at the top where the top room could not be pulled down.
        assertFalse(shadeIsDrawn(paneTakenOver = false, mapOpen = true, routeIsRoot = true))
        assertTrue(shadeIsDrawn(paneTakenOver = false, mapOpen = false, routeIsRoot = true))
    }
}
