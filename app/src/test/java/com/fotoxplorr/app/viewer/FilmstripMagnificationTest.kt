package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [filmstripMagnification] is the loupe effect's whole shape: how much a thumbnail scales up as
 * it nears the strip's fixed centre. It has to be smooth (a kink reads as a seam under a slow
 * drag), it has to peak exactly at the centre, and it has to fall all the way back to 1x by the
 * radius -- otherwise thumbnails far from the centre would sit at a permanently wrong size.
 */
class FilmstripMagnificationTest {

    @Test
    fun `peaks exactly at the centre`() {
        assertEquals(1.4f, filmstripMagnification(0f, radiusPx = 100f, peakScale = 1.4f), 1e-4f)
    }

    @Test
    fun `is symmetric either side of the centre`() {
        val left = filmstripMagnification(-40f, radiusPx = 100f, peakScale = 1.4f)
        val right = filmstripMagnification(40f, radiusPx = 100f, peakScale = 1.4f)
        assertEquals(left, right, 1e-5f)
    }

    @Test
    fun `falls all the way back to 1x at the radius and beyond`() {
        assertEquals(1f, filmstripMagnification(100f, radiusPx = 100f, peakScale = 1.4f), 1e-4f)
        assertEquals(1f, filmstripMagnification(250f, radiusPx = 100f, peakScale = 1.4f), 1e-4f)
        assertEquals(1f, filmstripMagnification(-500f, radiusPx = 100f, peakScale = 1.4f), 1e-4f)
    }

    @Test
    fun `decreases monotonically from centre to radius`() {
        // A loupe that bumps back up part-way out would look like a rendering glitch, not depth.
        val radius = 120f
        var previous = Float.MAX_VALUE
        var distance = 0f
        while (distance <= radius) {
            val scale = filmstripMagnification(distance, radiusPx = radius, peakScale = 1.5f)
            assertTrue("distance=$distance scale=$scale previous=$previous", scale <= previous + 1e-5f)
            previous = scale
            distance += 5f
        }
    }

    @Test
    fun `never scales below 1x or above the peak`() {
        val radius = 80f
        val peak = 1.4f
        var distance = -300f
        while (distance <= 300f) {
            val scale = filmstripMagnification(distance, radiusPx = radius, peakScale = peak)
            assertTrue("distance=$distance scale=$scale", scale in 1f..peak + 1e-4f)
            distance += 7f
        }
    }

    @Test
    fun `eases in and out rather than switching on at the radius`() {
        // The whole point of a raised cosine over a linear or hard-cutoff falloff: the slope at
        // both ends is (near) zero, so there is no visible kink where the effect "turns on".
        // Approximate the derivative numerically just inside and outside the radius boundary.
        val radius = 100f
        val h = 0.5f
        val justInside = filmstripMagnification(radius - h, radiusPx = radius, peakScale = 1.4f)
        val atRadius = filmstripMagnification(radius, radiusPx = radius, peakScale = 1.4f)
        val slopeNearRadius = (atRadius - justInside) / h
        assertTrue("slope near the radius should be nearly flat, was $slopeNearRadius", kotlin.math.abs(slopeNearRadius) < 0.01f)

        val nearCentre = filmstripMagnification(h, radiusPx = radius, peakScale = 1.4f)
        val atCentre = filmstripMagnification(0f, radiusPx = radius, peakScale = 1.4f)
        val slopeNearCentre = (atCentre - nearCentre) / h
        assertTrue(
            "slope near the centre should be nearly flat, was $slopeNearCentre",
            kotlin.math.abs(slopeNearCentre) < 0.01f,
        )
    }

    @Test
    fun `a peak scale of 1x is a genuine no-op everywhere`() {
        var distance = -100f
        while (distance <= 100f) {
            assertEquals(1f, filmstripMagnification(distance, radiusPx = 100f, peakScale = 1f), 1e-5f)
            distance += 10f
        }
    }

    @Test
    fun `a zero radius does not divide by zero`() {
        // Guards the division in the main branch; reachable if a caller ever computes radius
        // from a zero-width container.
        assertEquals(1.4f, filmstripMagnification(0f, radiusPx = 0f, peakScale = 1.4f), 1e-4f)
        assertEquals(1f, filmstripMagnification(5f, radiusPx = 0f, peakScale = 1.4f), 1e-4f)
    }
}
