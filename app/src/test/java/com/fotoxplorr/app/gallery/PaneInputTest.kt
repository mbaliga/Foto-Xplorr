package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.GalleryShortcut
import com.fotoxplorr.app.adaptive.MoveDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions that keep a pane honest: the rail's way back out of it, and a grid that nobody can
 * see being inert while it is covered. Pure, because every one of them was a hole in a screen too
 * large to drive on a device.
 */
class PaneInputTest {

    // ---- the rail's way back from a pane ----

    @Test
    fun `reselecting the word you came from leaves an open pane and closes the room`() {
        assertEquals(
            RailReselectEffect(leavePane = true, closeRoom = true),
            railReselectEffect(inRoom = true, paneTakenOver = true),
        )
    }

    @Test
    fun `reselecting with no pane over the grid only closes the room`() {
        assertEquals(
            RailReselectEffect(leavePane = false, closeRoom = true),
            railReselectEffect(inRoom = true, paneTakenOver = false),
        )
    }

    @Test
    fun `the pinned rail leaves a pane and has no room to close`() {
        assertEquals(
            RailReselectEffect(leavePane = true, closeRoom = false),
            railReselectEffect(inRoom = false, paneTakenOver = true),
        )
        assertEquals(
            RailReselectEffect(leavePane = false, closeRoom = false),
            railReselectEffect(inRoom = false, paneTakenOver = false),
        )
    }

    @Test
    fun `a wheel resting on the selected word means leave the pane only when a pane is open`() {
        assertEquals(RailLanding.NONE, railLanding(nearest = 4, selectedIndex = 4, paneOpen = false))
        assertEquals(RailLanding.LEAVE_PANE, railLanding(nearest = 4, selectedIndex = 4, paneOpen = true))
    }

    @Test
    fun `a wheel resting on another word selects it whether or not a pane is open`() {
        assertEquals(RailLanding.SELECT, railLanding(nearest = 2, selectedIndex = 4, paneOpen = false))
        assertEquals(RailLanding.SELECT, railLanding(nearest = 2, selectedIndex = 4, paneOpen = true))
        // An unknown selection (-1) makes every row a change.
        assertEquals(RailLanding.SELECT, railLanding(nearest = 0, selectedIndex = -1, paneOpen = true))
    }

    @Test
    fun `the debounce waits on the selected word only while a pane is over it`() {
        assertNull(railPendingCommit(focusIndex = 4, selectedIndex = 4, dragging = true))
        assertEquals(4, railPendingCommit(focusIndex = 4, selectedIndex = 4, dragging = true, paneOpen = true))
        // Never for a wheel nobody is dragging, pane or not.
        assertNull(railPendingCommit(focusIndex = 4, selectedIndex = 4, dragging = false, paneOpen = true))
        assertEquals(2, railPendingCommit(focusIndex = 2, selectedIndex = 4, dragging = true, paneOpen = true))
    }

    // ---- the covered grid ----

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
    )

    @Test
    fun `over a pane only Escape acts`() {
        everyShortcut.forEach { shortcut ->
            assertEquals(
                "$shortcut over a pane",
                shortcut == GalleryShortcut.CloseOrClear,
                shortcutActsOverPane(shortcut, paneTakenOver = true),
            )
        }
    }

    @Test
    fun `with the grid showing every shortcut acts`() {
        everyShortcut.forEach { assertTrue("$it", shortcutActsOverPane(it, paneTakenOver = false)) }
    }

    @Test
    fun `Escape over a pane follows the same order as Back`() {
        // CloseOrClear hands the pane to paneBackTarget, so a pane is left by Escape exactly as it
        // is by the Back gesture: a room first, then the pane, ahead of the grid's own state.
        assertEquals(
            PaneBackTarget.CLOSE_SETTINGS,
            paneBackTarget(
                roomOpen = false, settingsOpen = true, legacyScreenOpen = false,
                selectionActive = true, searchVisible = true, routeIsRoot = false,
            ),
        )
        assertEquals(
            PaneBackTarget.CLOSE_LEGACY_SCREEN,
            paneBackTarget(
                roomOpen = false, settingsOpen = false, legacyScreenOpen = true,
                selectionActive = false, searchVisible = false, routeIsRoot = true,
            ),
        )
        assertFalse(paneTakenOver(settingsOpen = false, legacyScreenOpen = false))
    }
}
