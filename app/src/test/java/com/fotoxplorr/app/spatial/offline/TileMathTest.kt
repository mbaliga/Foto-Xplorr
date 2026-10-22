package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    private fun box(west: Double, south: Double, east: Double, north: Double): GeoBox =
        GeoBox.clamped(west, south, east, north)!!

    @Test
    fun `the whole world is one tile at zoom 0`() {
        val ranges = TileMath.tileRanges(box(-180.0, -85.0, 180.0, 85.0), 0)
        assertEquals(1, ranges.size)
        assertEquals(1L, ranges.single().count)
    }

    @Test
    fun `the equator and the prime meridian sit on the middle of the grid`() {
        assertEquals(1.0, TileMath.lonToTileX(0.0, 1), 1e-9)
        assertEquals(1.0, TileMath.latToTileY(0.0, 1), 1e-9)
        assertEquals(8192.0, TileMath.lonToTileX(0.0, 14), 1e-9)
        assertEquals(8192.0, TileMath.latToTileY(0.0, 14), 1e-6)
    }

    @Test
    fun `north is a small y and the Mercator limit is the top edge`() {
        assertTrue(TileMath.latToTileY(60.0, 5) < TileMath.latToTileY(-60.0, 5))
        assertEquals(0.0, TileMath.latToTileY(OfflineMapLimits.MAX_MERCATOR_LATITUDE, 6), 1e-6)
        assertEquals(64.0, TileMath.latToTileY(-OfflineMapLimits.MAX_MERCATOR_LATITUDE, 6), 1e-6)
    }

    @Test
    fun `a small box at the equator covers a known block of tiles at zoom 14`() {
        // One zoom 14 tile is about 0.022 degrees; plus and minus 0.04 straddles the middle by about
        // 1.8 tiles each way, so columns 8190 to 8193 and rows 8190 to 8193.
        val range = TileMath.tileRanges(box(-0.04, -0.04, 0.04, 0.04), 14).single()
        assertEquals(8190, range.xMin)
        assertEquals(8193, range.xMax)
        assertEquals(8190, range.yMin)
        assertEquals(8193, range.yMax)
        assertEquals(16L, range.count)
    }

    @Test
    fun `a box that only touches a tile edge does not take the next tile`() {
        // East edge on longitude 0 is the left edge of column 1 at zoom 1.
        val range = TileMath.tileRanges(box(-10.0, 10.0, 0.0, 20.0), 1).single()
        assertEquals(0, range.xMin)
        assertEquals(0, range.xMax)
    }

    @Test
    fun `a box across zero longitude takes tiles from both sides`() {
        val range = TileMath.tileRanges(box(-0.01, 10.0, 0.01, 10.01), 14).single()
        assertEquals(8191, range.xMin)
        assertEquals(8192, range.xMax)
    }

    @Test
    fun `an antimeridian box is counted as two ranges with no tile shared`() {
        val ranges = TileMath.tileRanges(box(179.9, -0.04, -179.9, 0.04), 14)
        assertEquals(2, ranges.size)
        assertEquals(16383, ranges[0].xMax)
        assertEquals(0, ranges[1].xMin)
        // 5 columns on each side of the date line by 4 rows.
        assertEquals(40L, ranges.sumOf { it.count })
        assertEquals(40L, TileMath.tileCount(box(179.9, -0.04, -179.9, 0.04), 14, 14))
    }

    @Test
    fun `a polar box is clamped to the top rows and never goes negative`() {
        val range = TileMath.tileRanges(box(-10.0, 80.0, 10.0, 90.0), 4).single()
        assertEquals(0, range.yMin)
        assertTrue(range.yMax >= range.yMin)
        assertTrue(range.xMin >= 0)
    }

    @Test
    fun `the far south edge stays inside the grid`() {
        val range = TileMath.tileRanges(box(-10.0, -90.0, 10.0, -80.0), 5).single()
        assertEquals(31, range.yMax)
    }

    @Test
    fun `a point box is one tile per zoom`() {
        val point = box(10.0, 10.0, 10.0, 10.0)
        assertEquals(15L, TileMath.tileCount(point, 0, 14))
    }

    @Test
    fun `a tiny box is one tile at every zoom from 0 to 14`() {
        val tiny = box(2.2945, 48.8584, 2.2946, 48.8585)
        assertEquals(15L, TileMath.tileCount(tiny, 0, 14))
    }

    @Test
    fun `the whole world counts every tile of every zoom`() {
        val world = box(-180.0, -85.0511287798, 180.0, 85.0511287798)
        assertEquals(21L, TileMath.tileCount(world, 0, 2))
        // 4^14 tiles at the top zoom, no overflow.
        assertEquals(268_435_456L, TileMath.tileCount(world, 14, 14))
    }

    @Test
    fun `the count never goes down as the top zoom rises`() {
        val city = box(2.25, 48.82, 2.42, 48.9)
        var previous = 0L
        for (maxZoom in 0..14) {
            val count = TileMath.tileCount(city, 0, maxZoom)
            assertTrue("zoom $maxZoom: $count < $previous", count > previous)
            previous = count
        }
    }

    @Test
    fun `an empty zoom range counts nothing`() {
        assertEquals(0L, TileMath.tileCount(box(0.0, 0.0, 1.0, 1.0), 5, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative zoom is a programming error`() {
        TileMath.tileCount(box(0.0, 0.0, 1.0, 1.0), -1, 3)
    }
}
