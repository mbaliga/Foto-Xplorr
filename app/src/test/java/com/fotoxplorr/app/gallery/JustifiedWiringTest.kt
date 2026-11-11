package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.ZOOM_RUNG_STAMPS
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.organize.GridIndexMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The justified grid's hookup to the screen: when it is on, and the index space it hands the scrubber. */
class JustifiedWiringTest {
    private fun asset(i: Int, w: Int = 3000, h: Int = 2000) = MediaAsset(
        id = MediaId(i.toLong()), contentUriString = "content://m/$i", displayName = "$i.jpg",
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0,
        width = w, height = h, sizeBytes = 1, relativePath = null, isFavorite = false, isTrashed = false,
    )

    @Test
    fun `justified is opt-in, column rungs only, never with masonry`() {
        assertFalse(justifiedActive(setting = false, masonry = false, rung = 3))
        assertTrue(justifiedActive(setting = true, masonry = false, rung = 3))
        assertFalse(justifiedActive(setting = true, masonry = true, rung = 3))
        assertFalse(justifiedActive(setting = true, masonry = false, rung = ZOOM_RUNG_STAMPS))
    }

    @Test
    fun `the rows are the index space when the snapshot is for the same list`() {
        val assets = List(40) { asset(it) }
        val layout = JustifiedLayout.build(assets.size, { assets[it].aspectRatio }, 1000f, 200f, 4f)
        val map = GridIndexMap(listOf(assets.size), hasHeaders = false, trailingFooter = true)
        val space = gridIndexSpace(map, JustifiedSnapshot(assets, layout), assets)
        assertEquals(layout.rows + 1, space.itemCount)
        for (i in assets.indices) {
            val row = space.gridIndexOf(i)
            assertTrue(i in layout.rowItems(row))
        }
        assertEquals(layout.rowItems(1).first, space.assetIndexAt(1))
        // the footer and anything past it read as the last row's first photo
        assertEquals(layout.rowItems(layout.rows - 1).first, space.assetIndexAt(layout.rows + 5))
    }

    @Test
    fun `a stale snapshot falls back to the grid map`() {
        val assets = List(10) { asset(it) }
        val other = assets.toList()
        val layout = JustifiedLayout.build(assets.size, { 1.5f }, 1000f, 200f, 4f)
        val map = GridIndexMap(listOf(assets.size), hasHeaders = false, trailingFooter = true)
        val space = gridIndexSpace(map, JustifiedSnapshot(assets, layout), other)
        assertEquals(map.itemCount, space.itemCount)
        assertEquals(7, space.gridIndexOf(7))
        assertEquals(map.itemCount, gridIndexSpace(map, null, assets).itemCount)
    }

    @Test
    fun `an empty justified layout does not throw`() {
        val layout = JustifiedLayout.build(0, { 1f }, 1000f, 200f, 4f)
        val space = JustifiedIndexSpace(layout)
        assertEquals(1, space.itemCount)
        assertEquals(0, space.gridIndexOf(3))
        assertEquals(0, space.assetIndexAt(0))
    }
}
