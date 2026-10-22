package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewAreaTest {

    @Test
    fun `a street-level view is a small box with the right width and height`() {
        val box = ViewArea.boxOf(0.0, 10.0, 14.0, 400.0, 800.0)!!
        // 400 dp of 512 * 2^14 across the world is 0.01717 degrees of longitude.
        assertEquals(0.01717, box.widthDegrees, 0.0002)
        // At the equator Mercator is nearly square, so the height is two widths.
        assertEquals(0.03433, box.heightDegrees, 0.0004)
    }

    @Test
    fun `the box is centred on the camera`() {
        val box = ViewArea.boxOf(48.86, 2.35, 12.0, 400.0, 800.0)!!
        assertEquals(2.35, box.centreLongitude, 1e-9)
        assertTrue(box.contains(48.86, 2.35))
    }

    @Test
    fun `zooming in by one halves the width`() {
        val wide = ViewArea.boxOf(10.0, 10.0, 11.0, 400.0, 800.0)!!
        val narrow = ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0)!!
        assertEquals(wide.widthDegrees / 2.0, narrow.widthDegrees, 1e-9)
    }

    @Test
    fun `a fractional zoom lies between its neighbours`() {
        val a = ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0)!!.widthDegrees
        val b = ViewArea.boxOf(10.0, 10.0, 12.5, 400.0, 800.0)!!.widthDegrees
        val c = ViewArea.boxOf(10.0, 10.0, 13.0, 400.0, 800.0)!!.widthDegrees
        assertTrue(b < a && b > c)
    }

    @Test
    fun `a view wider than the world is the whole world`() {
        val box = ViewArea.boxOf(0.0, 0.0, 0.0, 1000.0, 400.0)!!
        assertEquals(360.0, box.widthDegrees, 0.0)
    }

    @Test
    fun `the poles are clamped to the Mercator limit`() {
        val box = ViewArea.boxOf(84.9, 0.0, 3.0, 400.0, 3000.0)!!
        assertEquals(OfflineMapLimits.MAX_MERCATOR_LATITUDE, box.north, 1e-9)
        assertTrue(box.south < box.north)
    }

    @Test
    fun `a camera past the date line is wrapped and may cross it`() {
        val box = ViewArea.boxOf(10.0, 179.995, 14.0, 400.0, 800.0)!!
        assertTrue(box.crossesAntimeridian)
        val panned = ViewArea.boxOf(10.0, 190.0, 12.0, 400.0, 800.0)!!
        assertEquals(-170.0, panned.centreLongitude, 1e-9)
    }

    @Test
    fun `bad inputs give null`() {
        assertNull(ViewArea.boxOf(Double.NaN, 0.0, 10.0, 400.0, 800.0))
        assertNull(ViewArea.boxOf(0.0, Double.POSITIVE_INFINITY, 10.0, 400.0, 800.0))
        assertNull(ViewArea.boxOf(0.0, 0.0, Double.NaN, 400.0, 800.0))
        assertNull(ViewArea.boxOf(0.0, 0.0, 10.0, 0.0, 800.0))
        assertNull(ViewArea.boxOf(0.0, 0.0, 10.0, 400.0, -1.0))
        assertNull(ViewArea.boxOf(0.0, 0.0, -0.5, 400.0, 800.0))
        assertNull(ViewArea.boxOf(0.0, 0.0, 25.0, 400.0, 800.0))
        assertNotNull(ViewArea.boxOf(0.0, 0.0, 24.0, 400.0, 800.0))
    }

    @Test
    fun `a city view fits the caps and a country view does not`() {
        val city = ViewArea.boxOf(48.86, 2.35, 12.0, 400.0, 800.0)!!
        assertTrue(RegionBudget.plan(city) is BudgetVerdict.Fits)
        val country = ViewArea.boxOf(46.8, 8.2, 5.0, 400.0, 800.0)!!
        assertTrue(RegionBudget.plan(country) is BudgetVerdict.TooLarge)
    }

    // ---- the camera's bearing ----

    @Test
    fun `no bearing is the same as north up`() {
        assertEquals(
            ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0),
            ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0, bearingDegrees = 0.0),
        )
    }

    @Test
    fun `a quarter turn swaps the width and the height of what is covered`() {
        val turned = ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0, bearingDegrees = 90.0)!!
        val landscape = ViewArea.boxOf(10.0, 10.0, 12.0, 800.0, 400.0)!!
        assertEquals(landscape.widthDegrees, turned.widthDegrees, 1e-9)
        assertEquals(landscape.heightDegrees, turned.heightDegrees, 1e-9)
    }

    @Test
    fun `at 45 degrees the box is the rotated rectangle's bounding square`() {
        val turned = ViewArea.boxOf(0.0, 10.0, 12.0, 400.0, 800.0, bearingDegrees = 45.0)!!
        // Both sides are (w + h) / sqrt(2) = 848.5 dp.
        val square = ViewArea.boxOf(0.0, 10.0, 12.0, 848.528137, 848.528137)!!
        assertEquals(square.widthDegrees, turned.widthDegrees, 1e-6)
        assertEquals(square.heightDegrees, turned.heightDegrees, 1e-6)
    }

    @Test
    fun `every corner of the turned screen lies inside the box, and the box touches the extreme ones`() {
        // On the equator at a high zoom a degree of latitude and of longitude are the same number of
        // dp, so the screen's corners can be placed by plain arithmetic.
        val zoom = 12.0
        val degreesPerDp = 360.0 / (ViewArea.TILE_PX * Math.pow(2.0, zoom))
        val (w, h) = 400.0 to 800.0
        for (bearing in listOf(10.0, 30.0, 45.0, 60.0, 89.0, 135.0, 200.0, 359.0)) {
            val box = ViewArea.boxOf(0.0, 0.0, zoom, w, h, bearingDegrees = bearing)!!
            val turn = Math.toRadians(bearing)
            val corners = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).map { (sx, sy) ->
                val x = sx * w / 2.0
                val y = sy * h / 2.0
                (x * Math.cos(turn) - y * Math.sin(turn)) * degreesPerDp to (x * Math.sin(turn) + y * Math.cos(turn)) * degreesPerDp
            }
            // Mercator is not quite linear, so the check has a micro-degree of slack.
            for ((east, north) in corners) {
                assertTrue("$bearing: corner ($east, $north) outside $box", east in (box.west - 1e-6)..(box.east + 1e-6))
                assertTrue("$bearing: corner ($east, $north) outside $box", north in (box.south - 1e-6)..(box.north + 1e-6))
            }
            assertEquals("$bearing", corners.maxOf { it.first }, box.east, 1e-6)
            assertEquals("$bearing", corners.maxOf { it.second }, box.north, 1e-6)
        }
    }

    @Test
    fun `opposite, full-turn and mirrored bearings give the same box`() {
        val base = ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0, bearingDegrees = 30.0)!!
        for (same in listOf(210.0, -30.0, 390.0, 150.0)) {
            val other = ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0, bearingDegrees = same)!!
            assertEquals("$same", base.widthDegrees, other.widthDegrees, 1e-9)
            assertEquals("$same", base.heightDegrees, other.heightDegrees, 1e-9)
        }
    }

    @Test
    fun `a bearing that is not a number is refused`() {
        assertNull(ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0, bearingDegrees = Double.NaN))
        assertNull(ViewArea.boxOf(10.0, 10.0, 12.0, 400.0, 800.0, bearingDegrees = Double.POSITIVE_INFINITY))
    }

    @Test
    fun `the rotated extent is width cos plus height sin by width sin plus height cos`() {
        val turn = Math.toRadians(30.0)
        val (w, h) = ViewArea.rotatedExtent(400.0, 800.0, 30.0)
        assertEquals(400.0 * Math.cos(turn) + 800.0 * Math.sin(turn), w, 1e-9)
        assertEquals(400.0 * Math.sin(turn) + 800.0 * Math.cos(turn), h, 1e-9)
        val (w0, h0) = ViewArea.rotatedExtent(400.0, 800.0, 0.0)
        assertEquals(400.0, w0, 1e-9)
        assertEquals(800.0, h0, 1e-9)
    }
}
