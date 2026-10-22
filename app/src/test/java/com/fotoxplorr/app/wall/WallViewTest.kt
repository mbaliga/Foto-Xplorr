package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallViewTest {
    // A 1600 x 1200 photo in a 800 x 800 area: scale 0.5, 800 x 600 drawn, 100 px of room above and below.
    private val view = WallView(1600, 1200, 800f, 800f, wallWidthCm = 320f)

    @Test
    fun `the photo is fitted whole and centred`() {
        assertEquals(0.5f, view.scale, 0f)
        assertEquals(0f, view.offX, 0f)
        assertEquals(100f, view.offY, 0f)
        assertTrue(view.contains(10f, 150f))
        assertFalse(view.contains(10f, 50f))
    }

    @Test
    fun `area, photo pixel and share coordinates agree`() {
        val (ax, ay) = view.toArea(0.5f, 0.25f)
        assertEquals(400f, ax, 1e-3f)
        assertEquals(100f + 0.25f * 1600 * 0.5f, ay, 1e-3f)
        val (sx, sy) = view.toShare(ax, ay)
        assertEquals(0.5f, sx, 1e-5f)
        assertEquals(0.25f, sy, 1e-5f)
        val (bx, by) = view.toBitmap(400f, 400f)
        assertEquals(800f, bx, 1e-3f)
        assertEquals(600f, by, 1e-3f)
    }

    @Test
    fun `pixels per cm follow the calibration and the fit`() {
        // 1600 photo px over 320 cm is 5 px/cm, halved by the fit.
        assertEquals(2.5f, view.pxPerCm, 1e-5f)
        assertEquals(240f, view.wallHeightCm, 1e-3f)
        assertEquals(0f, WallView(1600, 1200, 800f, 800f, 0f).pxPerCm, 0f)
    }

    @Test
    fun `a position in cm is the share times the wall width`() {
        val (x, y) = view.toCm(400f, 400f)
        assertEquals(160f, x, 1e-3f)
        assertEquals(120f, y, 1e-3f)
    }

    @Test
    fun `the topmost frame under the finger wins, and a miss is null`() {
        val under = WallFrame(1, 0.5f, 0.3f, 40f, 50f)
        val over = WallFrame(2, 0.5f, 0.3f, 20f, 20f)
        val (x, y) = view.toArea(0.5f, 0.3f)
        assertEquals(1, view.frameAt(listOf(under, over), 0f, 0f, x, y))
        assertEquals(0, view.frameAt(listOf(under, over), 0f, 0f, x + 2.5f * 15, y))
        assertNull(view.frameAt(listOf(under, over), 0f, 0f, x + 2.5f * 60, y))
    }

    @Test
    fun `a little slop catches a frame the finger just missed`() {
        val f = WallFrame(1, 0.5f, 0.3f, 10f, 10f)
        val (x, y) = view.toArea(0.5f, 0.3f)
        val missBy8cm = x + 2.5f * 13
        assertNull(view.frameAt(listOf(f), 0f, 0f, missBy8cm, y))
        assertEquals(0, view.frameAt(listOf(f), 0f, 10f, missBy8cm, y))
    }

    @Test
    fun `nothing is hit before the wall is calibrated`() {
        val raw = WallView(1600, 1200, 800f, 800f, 0f)
        assertNull(raw.frameAt(listOf(WallFrame(1, 0.5f, 0.3f, 40f, 50f)), 0f, 0f, 400f, 300f))
    }
}
