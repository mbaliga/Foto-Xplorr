package com.fotoxplorr.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpatialPhotoLayoutTest {
    @Test
    fun `bearing uses north as zero and increases clockwise`() {
        assertClose(0.0, SpatialPhotoLayout.initialBearingDegrees(0.0, 0.0, 1.0, 0.0), 0.01)
        assertClose(90.0, SpatialPhotoLayout.initialBearingDegrees(0.0, 0.0, 0.0, 1.0), 0.01)
        assertClose(180.0, SpatialPhotoLayout.initialBearingDegrees(0.0, 0.0, -1.0, 0.0), 0.01)
        assertClose(270.0, SpatialPhotoLayout.initialBearingDegrees(0.0, 0.0, 0.0, -1.0), 0.01)
    }

    @Test
    fun `haversine distance matches known equatorial degree`() {
        val distance = SpatialPhotoLayout.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertTrue(distance in 111_000.0..111_300.0)
    }

    @Test
    fun `distance is symmetric and zero for identical coordinates`() {
        val forward = SpatialPhotoLayout.distanceMeters(17.385, 78.4867, 19.076, 72.8777)
        val reverse = SpatialPhotoLayout.distanceMeters(19.076, 72.8777, 17.385, 78.4867)
        assertEquals(forward, reverse, 0.001)
        assertEquals(0.0, SpatialPhotoLayout.distanceMeters(17.385, 78.4867, 17.385, 78.4867), 0.001)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        val circularDifference = minOf(abs(expected - actual), 360.0 - abs(expected - actual))
        assertTrue("expected $expected, got $actual", circularDifference <= tolerance)
    }
}
