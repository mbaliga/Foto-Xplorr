package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The minimap's maths, with no container and no gesture: at 1x the whole image is visible, a
 * zoom shrinks the visible fraction by exactly the scale, a pan slides it the right way, and
 * nothing a file can report -- zero dimensions, a zero container -- divides by zero.
 */
class ZoomViewportTest {

    @Test
    fun `at 1x the whole image is visible, whatever the letterboxing`() {
        // A square image in a tall container: fitted to width, so vertically the container
        // shows more than the image -- which still clamps to the whole image.
        val f = ZoomViewport.fractions(900, 900, 440f, 956f, scale = 1f, offsetX = 0f, offsetY = 0f)
        assertEquals(0f, f.left, 1e-4f)
        assertEquals(1f, f.right, 1e-4f)
        assertEquals(0f, f.top, 1e-4f)
        assertEquals(1f, f.bottom, 1e-4f)
    }

    @Test
    fun `zooming about the centre shows the central fraction equal to one over the scale`() {
        val f = ZoomViewport.fractions(900, 900, 440f, 956f, scale = 2.5f, offsetX = 0f, offsetY = 0f)
        assertEquals(1f / 2.5f, f.width, 1e-4f)
        assertEquals(0.5f - 0.2f, f.left, 1e-4f)
        assertEquals(0.5f + 0.2f, f.right, 1e-4f)
        // Vertically the fitted height is 440 of a 956 container, so the visible fraction is
        // (956 / 440) / 2.5 = 0.869 of the image, centred.
        assertEquals((956f / 440f) / 2.5f, f.height, 1e-3f)
    }

    @Test
    fun `panning the image right shows more of its left`() {
        val centred = ZoomViewport.fractions(900, 900, 440f, 956f, 3f, 0f, 0f)
        val panned = ZoomViewport.fractions(900, 900, 440f, 956f, 3f, offsetX = 200f, offsetY = 0f)
        assertTrue("left edge should move toward 0 when the image is dragged right", panned.left < centred.left)
        assertEquals(centred.width, panned.width, 1e-4f)
    }

    @Test
    fun `fractions never leave the unit square or invert`() {
        val f = ZoomViewport.fractions(900, 900, 440f, 956f, 6f, offsetX = -5000f, offsetY = 9000f)
        assertTrue(f.left in 0f..1f && f.right in 0f..1f && f.top in 0f..1f && f.bottom in 0f..1f)
        assertTrue(f.right >= f.left && f.bottom >= f.top)
    }

    @Test
    fun `unknown dimensions and a zero container are survivable`() {
        assertEquals(1f, ZoomViewport.aspect(0, 0), 1e-6f)
        assertEquals(1f, ZoomViewport.aspect(-5, 100), 1e-6f)
        val whole = ZoomViewport.fractions(0, 0, 0f, 0f, 2f, 0f, 0f)
        assertEquals(0f, whole.left, 1e-6f)
        assertEquals(1f, whole.right, 1e-6f)
        val nan = ZoomViewport.fractions(100, 100, 440f, 956f, Float.NaN, 0f, 0f)
        assertEquals(1f, nan.bottom, 1e-6f)
    }

    @Test
    fun `the box follows the image's aspect on its long edge and clamps extremes`() {
        assertEquals(82f to 82f, ZoomViewport.boxSizeDp(900, 900, 82f))
        val (w, h) = ZoomViewport.boxSizeDp(3024, 4032, 82f)
        assertEquals(82f, h, 1e-4f)
        assertEquals(82f * 0.75f, w, 1e-4f)
        val (pw, ph) = ZoomViewport.boxSizeDp(10_000, 1_000, 82f)
        assertEquals(82f, pw, 1e-4f)
        assertEquals(82f / 2.5f, ph, 1e-4f)
    }
}
