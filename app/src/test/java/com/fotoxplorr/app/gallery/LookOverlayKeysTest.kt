package com.fotoxplorr.app.gallery

import androidx.compose.ui.input.key.Key
import com.fotoxplorr.app.adaptive.GalleryShortcut
import com.fotoxplorr.app.adaptive.MoveDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quick Look, compare/survey and culling sit inside the grid's focused key box, so a key they do
 * not consume bubbles up to the grid's shortcut table. While one is open it owns the keyboard:
 * Backspace must not trash the hidden selection, Enter must not open the viewer over the cull and
 * Ctrl+A must not select the library behind it.
 */
class LookOverlayKeysTest {

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
    fun `no grid shortcut acts while a look overlay is open, not even Escape`() {
        everyShortcut.forEach { shortcut ->
            assertFalse("$shortcut over a look", shortcutActsOverPane(shortcut, paneTakenOver = false, lookOpen = true))
            assertFalse("$shortcut over a look over a room", shortcutActsOverPane(shortcut, false, roomOpen = true, lookOpen = true))
        }
    }

    @Test
    fun `with no overlay the grid keeps every shortcut`() {
        everyShortcut.forEach { assertTrue("$it", shortcutActsOverPane(it, paneTakenOver = false, lookOpen = false)) }
    }

    @Test
    fun `keys the overlay left unused are swallowed before the grid table`() {
        listOf(Key.Backspace, Key.Delete, Key.Enter, Key.A, Key.DirectionUp, Key.DirectionDown, Key.Slash, Key.Equals, Key.Minus, Key.Spacebar)
            .forEach { assertTrue("$it", lookOverlaySwallows(it, lookOpen = true)) }
    }

    @Test
    fun `system keys pass an open overlay`() {
        listOf(Key.Back, Key.Tab, Key.VolumeUp, Key.VolumeDown, Key.SystemHome)
            .forEach { assertFalse("$it", lookOverlaySwallows(it, lookOpen = true)) }
    }

    @Test
    fun `nothing is swallowed with no overlay open`() {
        listOf(Key.Backspace, Key.Delete, Key.Enter, Key.A).forEach { assertFalse("$it", lookOverlaySwallows(it, lookOpen = false)) }
    }
}
