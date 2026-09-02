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
        assertEquals(0, selected.toggle(first).count)
    }

    @Test
    fun `un-picking the last item stays in selection mode`() {
        // A deliberate change of contract, and this test used to assert the opposite: removing
        // the last id ended selection, because being active WAS having something selected.
        //
        // That made sense while long press was the only way in -- the mode began with an item, so
        // it could reasonably end with the last one. It is now entered explicitly from the actions
        // room ("Select photos"), and dropping the user out because they changed their mind about
        // one photo would undo an intent they stated separately. Leaving is the X, which is
        // clear().
        val selection = GallerySelection().beginSelecting().toggle(MediaId(1)).toggle(MediaId(1))

        assertTrue("still selecting after un-picking the last item", selection.isActive)
        assertEquals(0, selection.count)
        assertFalse("clear is the way out", selection.clear().isActive)
    }

    @Test
    fun `selecting can begin with nothing picked`() {
        // The state that was previously unrepresentable, and the reason for the `selecting` flag:
        // long press now holds a preview instead of starting a selection, so the mode has to be
        // enterable before any photo has been chosen.
        val selection = GallerySelection().beginSelecting()

        assertTrue(selection.isActive)
        assertEquals(0, selection.count)
        assertTrue(selection.selectedIds.isEmpty())
    }

    @Test
    fun `a fresh selection is not active`() {
        assertFalse(GallerySelection().isActive)
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
