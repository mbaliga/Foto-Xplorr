package com.fotoxplorr.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geometry of "tap a colour to name it": tap to pixel through fit, zoom, pan and turn; the patch; the label. */
class PhotoPointTest {

    private fun pixel(
        x: Float, y: Float, cw: Float = 1000f, ch: Float = 1000f, iw: Int = 100, ih: Int = 100,
        scale: Float = 1f, ox: Float = 0f, oy: Float = 0f, rot: Float = 0f,
    ) = PhotoPoint.toPixel(x, y, cw, ch, iw, ih, scale, ox, oy, rot)

    @Test
    fun `the middle of the box is the middle of the picture`() {
        assertEquals(PixelPoint(50, 50), pixel(500f, 500f))
    }

    @Test
    fun `corners map to corner pixels and just past them to nothing`() {
        assertEquals(PixelPoint(0, 0), pixel(0f, 0f))
        assertEquals(PixelPoint(99, 99), pixel(999.9f, 999.9f))
        assertNull(pixel(1000f, 500f))
        assertNull(pixel(-0.1f, 500f))
    }

    @Test
    fun `the black bars around a wide picture are not the picture`() {
        // 200x100 in a 1000x1000 box: fitted to 1000x500, centred, bars of 250 above and below.
        assertNull(PhotoPoint.toPixel(500f, 100f, 1000f, 1000f, 200, 100))
        assertNull(PhotoPoint.toPixel(500f, 900f, 1000f, 1000f, 200, 100))
        assertEquals(PixelPoint(0, 0), PhotoPoint.toPixel(0f, 250f, 1000f, 1000f, 200, 100))
        assertEquals(PixelPoint(100, 50), PhotoPoint.toPixel(500f, 500f, 1000f, 1000f, 200, 100))
        assertEquals(PixelPoint(199, 99), PhotoPoint.toPixel(999f, 749f, 1000f, 1000f, 200, 100))
    }

    @Test
    fun `a tall picture has bars at the sides`() {
        assertNull(PhotoPoint.toPixel(100f, 500f, 1000f, 1000f, 100, 200))
        assertEquals(PixelPoint(50, 100), PhotoPoint.toPixel(500f, 500f, 1000f, 1000f, 100, 200))
    }

    @Test
    fun `zoom is about the centre`() {
        // At 2x the point 100 px right of centre on screen is 50 px right of centre in the layer: pixel 55.
        assertEquals(PixelPoint(55, 50), pixel(600f, 500f, scale = 2f))
        // The centre does not move.
        assertEquals(PixelPoint(50, 50), pixel(500f, 500f, scale = 4f))
        // The old corner is off the picture once zoomed in with no pan... it is still inside: layer x = 500 + (0-500)/2 = 250 -> 25.
        assertEquals(PixelPoint(25, 25), pixel(0f, 0f, scale = 2f))
    }

    @Test
    fun `a pan moves the picture under the finger`() {
        // The layer is moved 100 px right, so the finger at 600 is on what was at 500.
        assertEquals(PixelPoint(50, 50), pixel(600f, 500f, ox = 100f))
        // Zoomed 2x and panned 200 left: finger at 300 -> (300-500-(-200))/2 = 0 -> still the centre.
        assertEquals(PixelPoint(50, 50), pixel(300f, 500f, scale = 2f, ox = -200f))
    }

    @Test
    fun `a quarter turn maps the finger back the right way`() {
        // Turned 90 degrees clockwise about the centre, what is drawn at the right of centre came
        // from the top of the picture: a finger 200 px right of centre is on the picture's pixel
        // 200 px ABOVE the centre, i.e. y = 50 - 20 = 30 on a 1000-px, 100-pixel fit.
        assertEquals(PixelPoint(50, 30), pixel(700f, 500f, rot = 90f))
        // And a finger below the centre is on what was at the right of the picture.
        assertEquals(PixelPoint(70, 50), pixel(500f, 700f, rot = 90f))
        // A half turn flips both.
        assertEquals(PixelPoint(30, 50), pixel(700f, 500f, rot = 180f))
    }

    @Test
    fun `a rotated picture's corner can fall outside the box and the picture`() {
        assertNull(pixel(0f, 0f, rot = 45f))
    }

    @Test
    fun `degenerate sizes and scales give nothing instead of a crash`() {
        assertNull(PhotoPoint.toPixel(1f, 1f, 0f, 100f, 10, 10))
        assertNull(PhotoPoint.toPixel(1f, 1f, 100f, 100f, 0, 10))
        assertNull(PhotoPoint.toPixel(50f, 50f, 100f, 100f, 10, 10, scale = 0f))
        assertNull(PhotoPoint.toPixel(50f, 50f, 100f, 100f, 10, 10, scale = Float.NaN))
    }

    // ---- the colour read ----

    private fun filled(w: Int, h: Int, colour: Int) = IntArray(w * h) { colour }

    @Test
    fun `a flat patch gives its own colour back`() {
        val argb = filled(9, 9, VisionTestKit.argb(200, 30, 90))
        assertEquals(VisionTestKit.argb(200, 30, 90), PhotoPoint.sample(argb, 9, 9, 4, 4))
    }

    @Test
    fun `a patch is averaged in linear light and ignores one stray pixel`() {
        val base = VisionTestKit.argb(120, 120, 120)
        val argb = filled(9, 9, base)
        argb[4 * 9 + 4] = VisionTestKit.argb(255, 0, 0)
        val got = PhotoPoint.sample(argb, 9, 9, 4, 4, radius = 2)
        // One pixel of 25 is red: the result is nearly the grey, red slightly up, green and blue slightly down.
        assertTrue(VisionTestKit.r(got) > 120)
        assertTrue(VisionTestKit.r(got) < 160)
        assertTrue(VisionTestKit.g(got) < 120)
        assertTrue(VisionTestKit.g(got) > 100)
    }

    @Test
    fun `radius zero is the single pixel`() {
        val argb = filled(5, 5, VisionTestKit.argb(10, 10, 10))
        argb[2 * 5 + 2] = VisionTestKit.argb(250, 100, 20)
        assertEquals(VisionTestKit.argb(250, 100, 20), PhotoPoint.sample(argb, 5, 5, 2, 2, radius = 0))
    }

    @Test
    fun `a patch at a corner is clipped to the picture`() {
        val argb = filled(6, 6, VisionTestKit.argb(60, 160, 60))
        assertEquals(VisionTestKit.argb(60, 160, 60), PhotoPoint.sample(argb, 6, 6, 0, 0))
        assertEquals(VisionTestKit.argb(60, 160, 60), PhotoPoint.sample(argb, 6, 6, 5, 5))
    }

    @Test
    fun `the sampled colour is opaque whatever the alpha`() {
        val argb = filled(3, 3, VisionTestKit.argb(60, 160, 60, a = 17))
        assertEquals(255, ColourSpace.alpha(PhotoPoint.sample(argb, 3, 3, 1, 1)))
    }

    // ---- the label ----

    private fun place(tapX: Float, tapY: Float) =
        LabelPlacement.place(tapX, tapY, labelWidth = 200f, labelHeight = 100f, containerWidth = 1000f, containerHeight = 2000f, margin = 150f, gap = 60f)

    @Test
    fun `the label sits centred above the finger`() {
        val p = place(500f, 1000f)
        assertEquals(400f, p.left, 0f)
        assertEquals(840f, p.top, 0f)
    }

    @Test
    fun `near the top the label drops below the finger`() {
        val p = place(500f, 200f)
        assertEquals(260f, p.top, 0f)
        assertTrue(p.top > 200f)
    }

    @Test
    fun `the label never enters the edge zones`() {
        for (x in listOf(0f, 10f, 500f, 990f, 1000f)) for (y in listOf(0f, 100f, 1000f, 1900f, 2000f)) {
            val p = place(x, y)
            assertTrue("left at ($x,$y)", p.left >= 150f)
            assertTrue("right at ($x,$y)", p.left + 200f <= 1000f - 150f)
            assertTrue("top at ($x,$y)", p.top >= 150f)
            assertTrue("bottom at ($x,$y)", p.top + 100f <= 2000f - 150f)
        }
    }

    @Test
    fun `a box too small for the label gets the margin on the top left`() {
        val p = LabelPlacement.place(10f, 10f, 500f, 500f, 300f, 300f, margin = 50f, gap = 10f)
        assertEquals(50f, p.left, 0f)
        assertEquals(50f, p.top, 0f)
        assertNotNull(p)
    }
}
