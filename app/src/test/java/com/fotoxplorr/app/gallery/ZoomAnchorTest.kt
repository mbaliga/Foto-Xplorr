package com.fotoxplorr.app.gallery

import com.fotoxplorr.core.organize.GridIndexMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A rung change must keep the photo at the top of the screen, not the item index. Under month
 * headers (Stamps) the two differ by one per header above, so the headerless columns would land
 * that many photos further on.
 */
class ZoomAnchorTest {

    // Three months of 4, 3 and 5 photos: grid items are h0 a0..a3, h1 a4..a6, h2 a7..a11, footer.
    private val sizes = listOf(4, 3, 5)
    private val stamps = GridIndexMap(sizes, hasHeaders = true, trailingFooter = true)
    private val columns = GridIndexMap(listOf(12), hasHeaders = false, trailingFooter = true)

    private fun toColumns(first: Int) = reanchoredGridIndex(stamps, true, columns, false, first)

    @Test
    fun `a month header at the top lands on the first photo of its month`() {
        // h1 is grid item 5; its first photo is asset 4, which is item 4 without headers.
        assertEquals(4, toColumns(5))
        assertEquals(0, toColumns(0))
        assertEquals(7, toColumns(9))
    }

    @Test
    fun `a photo at the top is left to the grid's key retention`() {
        assertNull(toColumns(1))
        assertNull(toColumns(6))
        assertNull(toColumns(14))
    }

    @Test
    fun `the footer at the top is left to the grid's key retention`() {
        assertNull(toColumns(stamps.itemCount - 1))
    }

    @Test
    fun `growing headers back needs no anchor because every top item is a photo`() {
        assertNull(reanchoredGridIndex(columns, false, stamps, true, 4))
    }

    @Test
    fun `layouts that both have or both lack headers need no anchor`() {
        assertNull(reanchoredGridIndex(stamps, true, stamps, true, 5))
        assertNull(reanchoredGridIndex(columns, false, columns, false, 4))
    }

    @Test
    fun `the anchor is the same photo in both directions`() {
        for (asset in 0 until 12) {
            val stampsIndex = stamps.gridIndexOf(asset)
            // From a photo the grid retains it by key; from the header just above it, the photo.
            val header = stampsIndex - 1
            val onHeader = stamps.assetIndexAt(header) == asset && stamps.gridIndexOf(asset) > header
            if (onHeader) assertEquals(columns.gridIndexOf(asset), toColumns(header))
            assertEquals(asset, columns.assetIndexAt(columns.gridIndexOf(asset)))
        }
    }

    @Test
    fun `headers of another grouping lose the old header's key, so the photo under it is the anchor`() {
        // The stamps are months and a column rung with day grouping is days: both have headers,
        // but a month header's key is gone in the other layout.
        val days = GridIndexMap(listOf(2, 2, 2, 2, 2, 2), hasHeaders = true, trailingFooter = true)
        val onMonthHeader = 5 // h1: its first photo is asset 4
        assertEquals(
            days.gridIndexOf(4),
            reanchoredGridIndex(stamps, true, days, true, onMonthHeader, headersDiffer = true),
        )
        // A photo at the top is still found by its key.
        assertNull(reanchoredGridIndex(stamps, true, days, true, 6, headersDiffer = true))
        // And two layouts that agree on the headers still need nothing.
        assertNull(reanchoredGridIndex(stamps, true, days, true, onMonthHeader, headersDiffer = false))
    }
}
