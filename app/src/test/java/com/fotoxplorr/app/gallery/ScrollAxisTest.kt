package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.MoveDirection
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_LAST_GRID
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_MIN
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_STAMPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the sideways grid: when it applies, what the arrow keys do, the wording. */
class ScrollAxisTest {

    @Test
    fun `the grid scrolls down by default`() {
        assertEquals(ScrollAxis.VERTICAL, GalleryPreferencesState().scrollAxis)
    }

    @Test
    fun `every column rung scrolls sideways when asked`() {
        for (rung in ZOOM_RUNG_MIN..ZOOM_RUNG_LAST_GRID) {
            assertTrue("rung $rung", gridScrollsHorizontally(ScrollAxis.HORIZONTAL, rung, masonry = false))
            assertFalse("rung $rung", gridScrollsHorizontally(ScrollAxis.VERTICAL, rung, masonry = false))
        }
    }

    @Test
    fun `the stamp view stays vertical whatever the setting`() {
        assertFalse(gridScrollsHorizontally(ScrollAxis.HORIZONTAL, ZOOM_RUNG_STAMPS, masonry = false))
    }

    @Test
    fun `masonry stays vertical whatever the setting`() {
        assertFalse(gridScrollsHorizontally(ScrollAxis.HORIZONTAL, 3, masonry = true))
    }

    @Test
    fun `on a vertical grid up and down take the long stride and left and right one photo`() {
        assertEquals(-4, arrowKeyStep(MoveDirection.UP, 4, horizontal = false))
        assertEquals(4, arrowKeyStep(MoveDirection.DOWN, 4, horizontal = false))
        assertEquals(-1, arrowKeyStep(MoveDirection.LEFT, 4, horizontal = false))
        assertEquals(1, arrowKeyStep(MoveDirection.RIGHT, 4, horizontal = false))
    }

    @Test
    fun `on a sideways grid the strides swap`() {
        assertEquals(-1, arrowKeyStep(MoveDirection.UP, 4, horizontal = true))
        assertEquals(1, arrowKeyStep(MoveDirection.DOWN, 4, horizontal = true))
        assertEquals(-4, arrowKeyStep(MoveDirection.LEFT, 4, horizontal = true))
        assertEquals(4, arrowKeyStep(MoveDirection.RIGHT, 4, horizontal = true))
    }

    @Test
    fun `a stride is never less than one photo`() {
        assertEquals(-1, arrowKeyStep(MoveDirection.UP, 0, horizontal = false))
        assertEquals(1, arrowKeyStep(MoveDirection.RIGHT, -3, horizontal = true))
    }

    @Test
    fun `a strip along the top or bottom pairs with a sideways grid and a side strip with a vertical one`() {
        assertTrue(pairsNaturally(ScrollAxis.HORIZONTAL, TimelinePlacement.TOP))
        assertTrue(pairsNaturally(ScrollAxis.HORIZONTAL, TimelinePlacement.BOTTOM))
        assertTrue(pairsNaturally(ScrollAxis.VERTICAL, TimelinePlacement.LEFT))
        assertTrue(pairsNaturally(ScrollAxis.VERTICAL, TimelinePlacement.RIGHT))
        assertFalse(pairsNaturally(ScrollAxis.HORIZONTAL, TimelinePlacement.RIGHT))
        assertFalse(pairsNaturally(ScrollAxis.VERTICAL, TimelinePlacement.BOTTOM))
    }

    @Test
    fun `the date orders say left and right on a sideways grid and top and bottom on a vertical one`() {
        assertEquals("Latest at top", timelineSortLabel(GallerySort.NEWEST, horizontal = false))
        assertEquals("Latest at bottom", timelineSortLabel(GallerySort.OLDEST, horizontal = false))
        assertEquals("Latest at left", timelineSortLabel(GallerySort.NEWEST, horizontal = true))
        assertEquals("Latest at right", timelineSortLabel(GallerySort.OLDEST, horizontal = true))
    }
}
