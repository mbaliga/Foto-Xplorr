package com.fotoxplorr.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pattern layer drawn from a plan: where ink goes, in what colour, at what size. */
class HatchOverlayTest {

    private val red = VisionTestKit.argb(200, 40, 40)
    private val green = VisionTestKit.argb(60, 150, 60)
    private val dark = VisionTestKit.argb(20, 30, 90)

    /** Left half cluster 0, right half cluster 1, [w] x [h]. */
    private fun halves(w: Int, h: Int, left: Int, right: Int): ColourIndexImage {
        val index = ByteArray(w * h) { if (it % w < w / 2) 0 else 1 }
        return ColourIndexImage(intArrayOf(left, right), index, w, h)
    }

    private fun plan(a: HatchPattern?, b: HatchPattern?) = HatchPlan(listOf(a, b), if (a != null || b != null) listOf(0 to 1) else emptyList())

    @Test
    fun `an empty plan draws nothing at all`() {
        val layer = HatchOverlay.render(halves(8, 8, red, green), HatchPlan(listOf(null, null), emptyList()))
        assertEquals(0, layer.width)
        assertEquals(0, layer.argb.size)
    }

    @Test
    fun `the layer is scaled to the long edge and keeps the shape`() {
        val layer = HatchOverlay.render(halves(40, 20, red, green), plan(HatchPattern.DIAGONAL, HatchPattern.DOTS), longEdge = 400)
        assertEquals(400, layer.width)
        assertEquals(200, layer.height)
        assertEquals(400 * 200, layer.argb.size)
        val tall = HatchOverlay.render(halves(20, 40, red, green), plan(HatchPattern.DIAGONAL, HatchPattern.DOTS), longEdge = 400)
        assertEquals(200, tall.width)
        assertEquals(400, tall.height)
    }

    @Test
    fun `ink falls only where the cluster's own pattern puts it`() {
        val image = halves(16, 16, red, green)
        val layer = HatchOverlay.render(image, plan(HatchPattern.HORIZONTAL, null), longEdge = 64, period = 8)
        var inkLeft = 0
        for (y in 0 until layer.height) for (x in 0 until layer.width) {
            val ink = layer.argb[y * layer.width + x] ushr 24 != 0
            if (x >= layer.width / 2) assertFalse("no pattern on the plain cluster at ($x,$y)", ink)
            else {
                assertEquals("horizontal lines at ($x,$y)", HatchPattern.HORIZONTAL.isInk(x, y, 8), ink)
                if (ink) inkLeft++
            }
        }
        assertTrue(inkLeft > 0)
    }

    @Test
    fun `each cluster gets its own pattern`() {
        val layer = HatchOverlay.render(halves(16, 16, red, green), plan(HatchPattern.VERTICAL, HatchPattern.HORIZONTAL), longEdge = 64, period = 8)
        val w = layer.width
        for (y in 0 until layer.height) for (x in 0 until w) {
            val pattern = if (x < w / 2) HatchPattern.VERTICAL else HatchPattern.HORIZONTAL
            assertEquals(pattern.isInk(x, y, 8), layer.argb[y * w + x] ushr 24 != 0)
        }
    }

    @Test
    fun `ink is dark over a light cluster and light over a dark one`() {
        val layer = HatchOverlay.render(halves(16, 16, VisionTestKit.argb(240, 240, 200), dark), plan(HatchPattern.CROSS, HatchPattern.CROSS), longEdge = 64)
        val w = layer.width
        val lightSide = layer.argb[0]
        val darkSide = layer.argb[w - 1] // the last pixel of row 0
        assertTrue("row 0 is ink on a cross", lightSide ushr 24 != 0)
        assertEquals("black ink on the light half", 0, lightSide and 0x00FFFFFF)
        assertTrue("last pixel of row 0 is ink on a cross", darkSide ushr 24 != 0)
        assertEquals("white ink on the dark half", 0x00FFFFFF, darkSide and 0x00FFFFFF)
    }

    @Test
    fun `the ink is mostly opaque but not solid`() {
        val layer = HatchOverlay.render(halves(8, 8, red, green), plan(HatchPattern.CROSS, HatchPattern.CROSS), longEdge = 32)
        val alpha = layer.argb[0] ushr 24
        assertTrue(alpha in 0xA0..0xF0)
    }

    @Test
    fun `an empty or degenerate picture gives an empty layer`() {
        val none = ColourIndexImage(IntArray(0), ByteArray(0), 0, 0)
        assertEquals(0, HatchOverlay.render(none, plan(HatchPattern.DOTS, HatchPattern.CROSS)).width)
    }

    @Test
    fun `the same input always gives the same layer`() {
        val a = HatchOverlay.render(halves(12, 9, red, green), plan(HatchPattern.DIAGONAL, HatchPattern.ANTI_DIAGONAL))
        val b = HatchOverlay.render(halves(12, 9, red, green), plan(HatchPattern.DIAGONAL, HatchPattern.ANTI_DIAGONAL))
        assertTrue(a.argb.contentEquals(b.argb))
    }
}
