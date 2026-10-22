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
}
