package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoBoxTest {

    private val delta = 1e-9

    @Test
    fun `an ordinary box keeps its edges and has no antimeridian split`() {
        val box = GeoBox.clamped(west = 2.0, south = 48.0, east = 3.0, north = 49.0)!!
        assertEquals(2.0, box.west, delta)
        assertEquals(3.0, box.east, delta)
        assertFalse(box.crossesAntimeridian)
        assertEquals(1.0, box.widthDegrees, delta)
        assertEquals(1.0, box.heightDegrees, delta)
        assertEquals(listOf(box), box.split())
    }

    @Test
    fun `a box across the antimeridian splits into two and keeps its width`() {
        val box = GeoBox.clamped(west = 170.0, south = -10.0, east = -170.0, north = 10.0)!!
        assertTrue(box.crossesAntimeridian)
        assertEquals(20.0, box.widthDegrees, delta)
        val parts = box.split()
        assertEquals(2, parts.size)
        assertEquals(170.0, parts[0].west, delta)
        assertEquals(180.0, parts[0].east, delta)
        assertEquals(-180.0, parts[1].west, delta)
        assertEquals(-170.0, parts[1].east, delta)
        assertEquals(20.0, parts.sumOf { it.widthDegrees }, delta)
    }

    @Test
    fun `an unwrapped camera box whose east is past 180 is wrapped`() {
        // A map that has been panned across the date line reports east as 190, not -170.
        val box = GeoBox.clamped(west = 170.0, south = 0.0, east = 190.0, north = 1.0)!!
        assertTrue(box.crossesAntimeridian)
        assertEquals(170.0, box.west, delta)
        assertEquals(-170.0, box.east, delta)
        assertEquals(20.0, box.widthDegrees, delta)
    }

    @Test
    fun `an unwrapped camera box far to the west is wrapped too`() {
        val box = GeoBox.clamped(west = -190.0, south = 0.0, east = -170.0, north = 1.0)!!
        assertEquals(170.0, box.west, delta)
        assertEquals(-170.0, box.east, delta)
        assertEquals(20.0, box.widthDegrees, delta)
    }

    @Test
    fun `a box that crosses zero longitude is not an antimeridian box`() {
        val box = GeoBox.clamped(west = -1.0, south = 51.0, east = 1.0, north = 52.0)!!
        assertFalse(box.crossesAntimeridian)
        assertEquals(2.0, box.widthDegrees, delta)
        assertTrue(box.contains(51.5, 0.0))
    }

    @Test
    fun `a box of 360 degrees or more is the whole world not a sliver`() {
        val world = GeoBox.clamped(west = -200.0, south = -10.0, east = 200.0, north = 10.0)!!
        assertEquals(-180.0, world.west, delta)
        assertEquals(180.0, world.east, delta)
        assertEquals(360.0, world.widthDegrees, delta)
        assertFalse(world.crossesAntimeridian)
    }

    @Test
    fun `a box ending exactly on 180 does not become crossing`() {
        val box = GeoBox.clamped(west = 170.0, south = 0.0, east = 180.0, north = 1.0)!!
        assertFalse(box.crossesAntimeridian)
        assertEquals(180.0, box.east, delta)
    }

    @Test
    fun `a west edge of 180 is the same meridian as minus 180`() {
        val box = GeoBox.clamped(west = 180.0, south = 0.0, east = -170.0, north = 1.0)!!
        assertEquals(-180.0, box.west, delta)
        assertEquals(-170.0, box.east, delta)
        assertFalse(box.crossesAntimeridian)
        assertEquals(1, box.split().size)
    }

    @Test
    fun `latitude is clamped at the Mercator limit at both poles`() {
        val box = GeoBox.clamped(west = -10.0, south = -90.0, east = 10.0, north = 90.0)!!
        assertEquals(-OfflineMapLimits.MAX_MERCATOR_LATITUDE, box.south, delta)
        assertEquals(OfflineMapLimits.MAX_MERCATOR_LATITUDE, box.north, delta)
    }

    @Test
    fun `a polar cap beyond the limit collapses to a zero height strip`() {
        val box = GeoBox.clamped(west = 0.0, south = 88.0, east = 10.0, north = 90.0)!!
        assertEquals(0.0, box.heightDegrees, delta)
        assertTrue(box.isZeroArea)
    }

    @Test
    fun `a zero width and a zero height box are zero area`() {
        assertTrue(GeoBox.clamped(5.0, 10.0, 5.0, 11.0)!!.isZeroArea)
        assertTrue(GeoBox.clamped(5.0, 10.0, 6.0, 10.0)!!.isZeroArea)
        assertTrue(GeoBox.clamped(5.0, 10.0, 5.0, 10.0)!!.isZeroArea)
        assertFalse(GeoBox.clamped(5.0, 10.0, 6.0, 11.0)!!.isZeroArea)
    }

    @Test
    fun `an inverted or non finite box is refused`() {
        assertNull(GeoBox.clamped(0.0, 10.0, 1.0, 5.0))
        assertNull(GeoBox.clamped(Double.NaN, 0.0, 1.0, 1.0))
        assertNull(GeoBox.clamped(0.0, Double.NEGATIVE_INFINITY, 1.0, 1.0))
        assertNull(GeoBox.clamped(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0))
    }

    @Test
    fun `the strict factory refuses what the lenient one repairs`() {
        assertNull(GeoBox.orNull(west = 0.0, south = 0.0, east = 190.0, north = 1.0))
        assertNull(GeoBox.orNull(west = 0.0, south = -90.0, east = 1.0, north = 1.0))
        assertNull(GeoBox.orNull(west = 0.0, south = 5.0, east = 1.0, north = 4.0))
        assertNotNull(GeoBox.orNull(west = -180.0, south = -85.0, east = 180.0, north = 85.0))
    }

    @Test
    fun `the strict factory normalises an east edge of minus 180`() {
        // 170 to -180 is 170 to 180, and must not split off a zero width piece.
        val box = GeoBox.orNull(west = 170.0, south = 0.0, east = -180.0, north = 1.0)!!
        assertEquals(180.0, box.east, delta)
        assertEquals(1, box.split().size)
    }

    @Test
    fun `contains follows the arc across the antimeridian`() {
        val box = GeoBox.clamped(170.0, -10.0, -170.0, 10.0)!!
        assertTrue(box.contains(0.0, 179.0))
        assertTrue(box.contains(0.0, -179.0))
        assertTrue(box.contains(0.0, 180.0))
        assertFalse(box.contains(0.0, 0.0))
        assertFalse(box.contains(20.0, 179.0))
    }

    @Test
    fun `the centre of a crossing box is on the far side of the world`() {
        val box = GeoBox.clamped(170.0, -10.0, -160.0, 10.0)!!
        assertEquals(-175.0, box.centreLongitude, delta)
        assertEquals(0.0, box.centreLatitude, delta)
    }

    @Test
    fun `padding grows every side and stays inside the world`() {
        val box = GeoBox.clamped(10.0, 20.0, 12.0, 24.0)!!.pad(0.25)
        assertEquals(9.5, box.west, delta)
        assertEquals(12.5, box.east, delta)
        assertEquals(19.0, box.south, delta)
        assertEquals(25.0, box.north, delta)

        val edge = GeoBox.clamped(-180.0, -85.0, 180.0, 85.0)!!.pad(0.5)
        assertEquals(360.0, edge.widthDegrees, delta)
        assertTrue(edge.north <= OfflineMapLimits.MAX_MERCATOR_LATITUDE)
    }

    @Test
    fun `padding a crossing box keeps it crossing`() {
        val box = GeoBox.clamped(170.0, 0.0, -170.0, 1.0)!!.pad(0.5)
        assertTrue(box.crossesAntimeridian)
        assertEquals(40.0, box.widthDegrees, delta)
        assertEquals(160.0, box.west, delta)
        assertEquals(-160.0, box.east, delta)
    }

    @Test
    fun `equal boxes are equal`() {
        assertEquals(GeoBox.clamped(1.0, 2.0, 3.0, 4.0), GeoBox.clamped(1.0, 2.0, 3.0, 4.0))
        assertEquals(GeoBox.clamped(1.0, 2.0, 3.0, 4.0).hashCode(), GeoBox.clamped(1.0, 2.0, 3.0, 4.0).hashCode())
    }
}
