package com.fotoxplorr.app.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gallery's keyboard shortcut table -- see [galleryShortcutFor]'s own doc for the six. */
class KeyboardShortcutsTest {

    @Test
    fun `the four arrows move the keyboard cursor in the matching direction`() {
        assertEquals(GalleryShortcut.MoveSelection(MoveDirection.UP), galleryShortcutFor(GalleryShortcutKey.ARROW_UP, ctrlPressed = false))
        assertEquals(GalleryShortcut.MoveSelection(MoveDirection.DOWN), galleryShortcutFor(GalleryShortcutKey.ARROW_DOWN, ctrlPressed = false))
        assertEquals(GalleryShortcut.MoveSelection(MoveDirection.LEFT), galleryShortcutFor(GalleryShortcutKey.ARROW_LEFT, ctrlPressed = false))
        assertEquals(GalleryShortcut.MoveSelection(MoveDirection.RIGHT), galleryShortcutFor(GalleryShortcutKey.ARROW_RIGHT, ctrlPressed = false))
    }

    @Test
    fun `Enter opens, Escape closes or clears, Delete trashes`() {
        assertEquals(GalleryShortcut.OpenFocused, galleryShortcutFor(GalleryShortcutKey.ENTER, ctrlPressed = false))
        assertEquals(GalleryShortcut.CloseOrClear, galleryShortcutFor(GalleryShortcutKey.ESCAPE, ctrlPressed = false))
        assertEquals(GalleryShortcut.TrashSelected, galleryShortcutFor(GalleryShortcutKey.DELETE, ctrlPressed = false))
    }

    @Test
    fun `bare slash focuses search`() {
        assertEquals(GalleryShortcut.FocusSearch, galleryShortcutFor(GalleryShortcutKey.SLASH, ctrlPressed = false))
    }

    @Test
    fun `Ctrl+A selects all, but bare A is left alone for typing into search`() {
        assertEquals(GalleryShortcut.SelectAll, galleryShortcutFor(GalleryShortcutKey.LETTER_A, ctrlPressed = true))
        assertNull(galleryShortcutFor(GalleryShortcutKey.LETTER_A, ctrlPressed = false))
    }

    @Test
    fun `Ctrl held for a key with no modifier-specific meaning does not change the outcome`() {
        assertEquals(
            galleryShortcutFor(GalleryShortcutKey.ENTER, ctrlPressed = false),
            galleryShortcutFor(GalleryShortcutKey.ENTER, ctrlPressed = true),
        )
        assertEquals(
            galleryShortcutFor(GalleryShortcutKey.ESCAPE, ctrlPressed = false),
            galleryShortcutFor(GalleryShortcutKey.ESCAPE, ctrlPressed = true),
        )
    }
}
