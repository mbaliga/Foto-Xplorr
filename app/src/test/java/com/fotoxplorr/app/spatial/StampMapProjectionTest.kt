package com.fotoxplorr.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the stylized offline map.
 *
 * This is the whole reason the map can exist without a network: placing a photo is a projection,
 * not a tile fetch. That makes it pure, and pure code with no test is code nobody can trust when
 * the pins land somewhere odd on a device.
 */
class StampMapProjectionTest {

    // ---- bounds ----

    @Test
    fun `an empty set has no bounds`() {
        // The caller uses null to mean "there is no map to draw", so it must not be a zero box.
        assertNull(StampMapProjection.boundsOf(emptyList()))
    }

    @Test
    fun `a single photo still gets a window rather than a zero-width box`() {
        // Regression target: a degenerate box divides by zero in project() and every pin collapses.
        val bounds = StampMapProjection.boundsOf(listOf(48.8584 to 2.2945))
        assertNotNull(bounds)
        bounds!!
        assertTrue(bounds.maxLatitude > bounds.minLatitude)
        assertTrue(bounds.maxLongitude > bounds.minLongitude)
    }

    @Test
    fun `several photos at the identical spot behave like one`() {
        val same = List(20) { 12.9716 to 77.5946 }
        val bounds = StampMapProjection.boundsOf(same)!!
        assertTrue(bounds.maxLatitude - bounds.minLatitude > 0.0)
        val point = StampMapProjection.project(12.9716, 77.5946, bounds)
        // Dead centre of its own window.
        assertEquals(0.5f, point.x, 0.01f)
        assertEquals(0.5f, point.y, 0.01f)
    }

    @Test
    fun `bounds pad outward so no pin sits on the edge`() {
        val bounds = StampMapProjection.boundsOf(listOf(10.0 to 10.0, 20.0 to 20.0))!!
        assertTrue("min lat should be padded below 10", bounds.minLatitude < 10.0)
        assertTrue("max lat should be padded above 20", bounds.maxLatitude > 20.0)

        val corner = StampMapProjection.project(10.0, 10.0, bounds)
        assertTrue("padded pin must be inside, got ${corner.x}", corner.x > 0f && corner.x < 1f)
        assertTrue("padded pin must be inside, got ${corner.y}", corner.y > 0f && corner.y < 1f)
    }

    @Test
    fun `padding never escapes the coordinate system`() {
        // Photos at the extremes would otherwise pad past +/-180 longitude and past the Mercator
        // cutoff, producing a window no coordinate can be projected into.
        val bounds = StampMapProjection.boundsOf(listOf(-89.0 to -180.0, 89.0 to 180.0))!!
        assertTrue(bounds.minLongitude >= -180.0)
        assertTrue(bounds.maxLongitude <= 180.0)
        assertTrue(bounds.minLatitude >= -90.0)
        assertTrue(bounds.maxLatitude <= 90.0)
    }

    // ---- projection ----

    @Test
    fun `north is up`() {
        // The single most visible way to get a map wrong: y grows downward on a canvas and upward
        // in latitude, so a missing flip silently renders every map upside down.
        val bounds = StampMapProjection.boundsOf(listOf(0.0 to 0.0, 50.0 to 50.0))!!
        val north = StampMapProjection.project(50.0, 25.0, bounds)
        val south = StampMapProjection.project(0.0, 25.0, bounds)
        assertTrue("north ${north.y} should be above south ${south.y}", north.y < south.y)
    }

    @Test
    fun `east is right`() {
        val bounds = StampMapProjection.boundsOf(listOf(0.0 to 0.0, 50.0 to 50.0))!!
        val east = StampMapProjection.project(25.0, 50.0, bounds)
        val west = StampMapProjection.project(25.0, 0.0, bounds)
        assertTrue("east ${east.x} should be right of west ${west.x}", east.x > west.x)
    }

    @Test
    fun `longitude is linear across the window`() {
        val bounds = StampMapProjection.Bounds(-10.0, 10.0, -10.0, 10.0)
        assertEquals(0.0f, StampMapProjection.project(0.0, -10.0, bounds).x, 1e-4f)
        assertEquals(0.5f, StampMapProjection.project(0.0, 0.0, bounds).x, 1e-4f)
        assertEquals(1.0f, StampMapProjection.project(0.0, 10.0, bounds).x, 1e-4f)
    }

    @Test
    fun `the equator sits at the middle of a symmetric window`() {
        val bounds = StampMapProjection.Bounds(-40.0, 40.0, -40.0, 40.0)
        assertEquals(0.5f, StampMapProjection.project(0.0, 0.0, bounds).y, 1e-4f)
    }

    @Test
    fun `mercator stretches the high latitudes`() {
        // This is the point of using Mercator rather than raw latitude: equal degree steps must NOT
        // be equal pixel steps, otherwise everything far from the equator bunches up.
        val bounds = StampMapProjection.Bounds(0.0, 80.0, 0.0, 80.0)
        val low = StampMapProjection.project(10.0, 0.0, bounds).y -
            StampMapProjection.project(20.0, 0.0, bounds).y
        val high = StampMapProjection.project(60.0, 0.0, bounds).y -
            StampMapProjection.project(70.0, 0.0, bounds).y
        assertTrue("ten degrees near the pole ($high) should span more than near the equator ($low)", high > low)
    }

    @Test
    fun `the poles are clamped instead of diverging`() {
        // tan(pi/2) is infinite; without the clamp one polar photo sends the scale to infinity and
        // collapses every other pin onto a single line.
        val bounds = StampMapProjection.Bounds(-85.0, 85.0, -180.0, 180.0)
        listOf(90.0, 89.999, -90.0, -89.999).forEach { latitude ->
            val point = StampMapProjection.project(latitude, 0.0, bounds)
            assertTrue("$latitude produced ${point.y}", point.y.isFinite())
            assertTrue("$latitude produced ${point.y}", point.y in 0f..1f)
        }
    }

    @Test
    fun `every projected point stays inside the unit square`() {
        val bounds = StampMapProjection.boundsOf(listOf(-33.9 to 18.4, 64.1 to -21.9))!!
        listOf(
            0.0 to 0.0, -33.9 to 18.4, 64.1 to -21.9, 85.0 to 179.9, -85.0 to -179.9,
        ).forEach { (latitude, longitude) ->
            val point = StampMapProjection.project(latitude, longitude, bounds)
            assertTrue("$latitude,$longitude -> $point", point.x in 0f..1f && point.y in 0f..1f)
        }
    }

    // ---- thinning ----

    @Test
    fun `thinning caps the stamp count at one per cell`() {
        // 5000 photos in one city must not compose 5000 overlapping stamps.
        val crowd = List(5_000) { index ->
            12.90 + index % 50 * 0.0001 to 77.55 + index / 50 * 0.0001
        }
        val bounds = StampMapProjection.boundsOf(crowd)!!
        val kept = StampMapProjection.thin(crowd, 12) { (lat, lon) ->
            StampMapProjection.project(lat, lon, bounds)
        }
        assertTrue("kept ${kept.size}", kept.size <= 12 * 12)
        assertTrue("thinning must keep something", kept.isNotEmpty())
    }

    @Test
    fun `thinning keeps the first item of each cell and preserves order`() {
        // Input order decides the representative, so a newest-first caller gets the newest photo
        // standing for each place.
        val items = listOf(
            "a" to StampMapProjection.Point(0.1f, 0.1f),
            "b" to StampMapProjection.Point(0.11f, 0.11f), // same cell as a
            "c" to StampMapProjection.Point(0.9f, 0.9f),
        )
        val kept = StampMapProjection.thin(items, 4) { it.second }
        assertEquals(listOf("a", "c"), kept.map { it.first })
    }

    @Test
    fun `thinning is a no-op when nothing collides`() {
        val items = (0 until 4).map { it to StampMapProjection.Point(it / 4f + 0.01f, 0.5f) }
        assertEquals(items, StampMapProjection.thin(items, 4) { it.second })
    }

    @Test
    fun `a point exactly on the far edge does not fall out of the grid`() {
        // 1.0 * cells is cells, which is one past the last index; without the clamp this is an
        // off-by-one that quietly drops the easternmost and southernmost photos.
        val items = listOf("edge" to StampMapProjection.Point(1f, 1f))
        assertEquals(items, StampMapProjection.thin(items, 8) { it.second })
    }
}
