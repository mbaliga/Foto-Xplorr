package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySelectionTest {
    @Test
    fun `toggle adds and removes ids`() {
        val first = MediaId(1)
        val selected = GallerySelection().toggle(first)

        assertTrue(selected.isActive)
        assertEquals(1, selected.count)
        assertFalse(selected.toggle(first).isActive)
    }

    @Test
    fun `retain available prunes removed media`() {
        val selection = GallerySelection(setOf(MediaId(1), MediaId(2)))

        assertEquals(
            setOf(MediaId(2)),
            selection.retainAvailable(setOf(MediaId(2), MediaId(3))).selectedIds,
        )
    }

    @Test
    fun `bulk action marks when any selected item is unmarked`() {
        assertEquals(
            BulkMarkAction.MARK,
            bulkMarkAction(
                selectedIds = setOf(MediaId(1), MediaId(2)),
                currentlyMarkedIds = setOf(MediaId(1)),
            ),
        )
    }

    @Test
    fun `bulk action unmarks when every selected item is marked`() {
        assertEquals(
            BulkMarkAction.UNMARK,
            bulkMarkAction(
                selectedIds = setOf(MediaId(1), MediaId(2)),
                currentlyMarkedIds = setOf(MediaId(1), MediaId(2), MediaId(3)),
            ),
        )
    }
}
