package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip's geometry beyond the loupe curve ([FilmstripMagnificationTest] has that): the
 * resting band matches the owner's reference, the bulge is a clean interpolation that is exactly
 * flat at rest, and "which thumbnail is under the centre" picks the right one at every offset a
 * drag can stop at.
 */
class FilmstripGeometryTest {

    @Test
    fun `the resting band is the reference's 74 dp of 58 dp thumbnails plus 16 dp under them`() {
        assertEquals(74, FilmstripGeometry.REST_BAND_DP)
        assertEquals(74f, FilmstripGeometry.bandHeightDp(0f), 1e-5f)
        assertEquals(53, FilmstripGeometry.THUMB_WIDTH_DP)
        assertEquals(58, FilmstripGeometry.THUMB_HEIGHT_DP)
        assertEquals(1, FilmstripGeometry.THUMB_GAP_DP)
    }

    @Test
    fun `the bulged band is taller, and fits a thumbnail at peak scale`() {
        val bulged = FilmstripGeometry.bandHeightDp(1f)
        assertTrue("bulged band $bulged should clearly exceed the resting 74", bulged >= 100f)
        val tallestThumb = FilmstripGeometry.THUMB_HEIGHT_DP * FilmstripGeometry.BULGE_PEAK_SCALE
        assertTrue(
            "a $tallestThumb dp thumbnail must fit in a $bulged dp band with the bottom pad",
            tallestThumb + FilmstripGeometry.BOTTOM_PAD_DP <= bulged,
        )
    }

    @Test
    fun `the band and the peak interpolate linearly and clamp`() {
        assertEquals(74f + (FilmstripGeometry.BULGE_BAND_DP - 74f) / 2f, FilmstripGeometry.bandHeightDp(0.5f), 1e-4f)
        assertEquals(FilmstripGeometry.BULGE_BAND_DP.toFloat(), FilmstripGeometry.bandHeightDp(7f), 1e-5f)
        assertEquals(74f, FilmstripGeometry.bandHeightDp(-1f), 1e-5f)
        // Exactly 1x at rest: the resting strip has NO loupe, matching the reference.
        assertEquals(1f, FilmstripGeometry.peakScale(0f), 1e-6f)
        assertEquals(FilmstripGeometry.BULGE_PEAK_SCALE, FilmstripGeometry.peakScale(1f), 1e-6f)
        assertEquals(1f + (FilmstripGeometry.BULGE_PEAK_SCALE - 1f) / 2f, FilmstripGeometry.peakScale(0.5f), 1e-6f)
    }

    @Test
    fun `at rest every thumbnail is 1x whatever its distance`() {
        val peak = FilmstripGeometry.peakScale(0f)
        for (distance in listOf(0f, 10f, 40f, 81f, 200f)) {
            assertEquals(1f, FilmstripGeometry.magnification(distance, radiusPx = 82f, peakScale = peak), 1e-6f)
        }
    }

    @Test
    fun `nearest to centre picks the item whose middle is closest`() {
        val items = listOf(
            FilmstripGeometry.VisibleItem(index = 3, offset = 100, size = 53),
            FilmstripGeometry.VisibleItem(index = 4, offset = 154, size = 53),
            FilmstripGeometry.VisibleItem(index = 5, offset = 208, size = 53),
        )
        // Viewport 0..400 -> centre 200; item 4's middle is 180.5, item 5's is 234.5.
        assertEquals(4, FilmstripGeometry.nearestToCentre(0, 400, items))
        // Shift the viewport so the centre lands on item 5.
        assertEquals(5, FilmstripGeometry.nearestToCentre(60, 400, items))
    }

    @Test
    fun `ties resolve to the earlier item and emptiness to null`() {
        val items = listOf(
            FilmstripGeometry.VisibleItem(index = 1, offset = 0, size = 100),
            FilmstripGeometry.VisibleItem(index = 2, offset = 100, size = 100),
        )
        // Centre at 100: item 1's middle is 50, item 2's is 150 -- a dead tie.
        assertEquals(1, FilmstripGeometry.nearestToCentre(0, 200, items))
        assertNull(FilmstripGeometry.nearestToCentre(0, 200, emptyList()))
    }

    @Test
    fun `nearest to centre survives a zero-size viewport and zero-size items`() {
        val items = listOf(FilmstripGeometry.VisibleItem(index = 0, offset = 0, size = 0))
        assertEquals(0, FilmstripGeometry.nearestToCentre(0, 0, items))
    }

    @Test
    fun `the accessor form agrees with the list form`() {
        val items = listOf(
            FilmstripGeometry.VisibleItem(index = 6, offset = -30, size = 53),
            FilmstripGeometry.VisibleItem(index = 7, offset = 24, size = 53),
            FilmstripGeometry.VisibleItem(index = 8, offset = 78, size = 53),
        )
        for (end in listOf(0, 60, 120, 200)) {
            val viaList = FilmstripGeometry.nearestToCentre(0, end, items)
            val viaAccessors = FilmstripGeometry.nearestToCentre(
                viewportStart = 0,
                viewportEnd = end,
                count = items.size,
                indexAt = { items[it].index },
                offsetAt = { items[it].offset },
                sizeAt = { items[it].size },
            )
            assertEquals("viewport end $end", viaList, viaAccessors)
        }
        assertNull(FilmstripGeometry.nearestToCentre(0, 100, count = 0, indexAt = { 0 }, offsetAt = { 0 }, sizeAt = { 0 }))
    }
}
