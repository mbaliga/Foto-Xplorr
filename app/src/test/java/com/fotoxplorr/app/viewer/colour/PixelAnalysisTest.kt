package com.fotoxplorr.app.viewer.colour

import com.fotoxplorr.app.viewer.colour.PixelAnalysis.HIGHLIGHT_COLOUR
import com.fotoxplorr.app.viewer.colour.PixelAnalysis.PEAK_COLOUR
import com.fotoxplorr.app.viewer.colour.PixelAnalysis.SHADOW_COLOUR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelAnalysisTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int) = rgb(v, v, v)

    @Test
    fun `luma is rec 709`() {
        assertEquals(0, PixelAnalysis.luma(grey(0)))
        assertEquals(255, PixelAnalysis.luma(grey(255)))
        assertEquals(54, PixelAnalysis.luma(rgb(255, 0, 0)))
        assertEquals(182, PixelAnalysis.luma(rgb(0, 255, 0)))
    }

    @Test
    fun `clipping counts blown and crushed pixels, a single hot channel is a blown highlight`() {
        val px = intArrayOf(grey(255), rgb(255, 10, 10), grey(0), grey(2), grey(128), grey(128), grey(128), grey(128), grey(128), grey(128))
        val c = PixelAnalysis.clipping(px)
        assertEquals(0.2f, c.highlights, 1e-6f)
        assertEquals(0.2f, c.shadows, 1e-6f)
        assertTrue(c.any)
    }

    @Test
    fun `clipping ignores transparent pixels and an empty image`() {
        assertEquals(PixelAnalysis.Clipping(0f, 0f), PixelAnalysis.clipping(intArrayOf(0, 0)))
        assertEquals(PixelAnalysis.Clipping(0f, 0f), PixelAnalysis.clipping(IntArray(0)))
        assertEquals(false, PixelAnalysis.clipping(intArrayOf(grey(100), grey(120))).any)
    }

    @Test
    fun `clipping mask paints highlights red, shadows blue, the rest clear`() {
        val m = PixelAnalysis.clippingMask(intArrayOf(grey(255), grey(0), grey(100), 0))
        assertEquals(HIGHLIGHT_COLOUR, m[0])
        assertEquals(SHADOW_COLOUR, m[1])
        assertEquals(0, m[2])
        assertEquals(0, m[3])
    }

    private fun image(w: Int, h: Int, f: (Int, Int) -> Int) = IntArray(w * h) { f(it % w, it / w) }

    @Test
    fun `peaking marks a hard edge and nothing on a flat field`() {
        val w = 32
        val h = 32
        val flat = image(w, h) { _, _ -> grey(120) }
        assertTrue(PixelAnalysis.peakingMask(flat, w, h).all { it == 0 })

        val edge = image(w, h) { x, _ -> if (x < 16) grey(30) else grey(220) }
        val m = PixelAnalysis.peakingMask(edge, w, h)
        val marked = m.indices.filter { m[it] == PEAK_COLOUR }
        assertTrue(marked.isNotEmpty())
        // every marked pixel sits on the edge columns 15/16
        assertTrue(marked.all { it % w in 15..16 })
    }

    @Test
    fun `a soft gradient shows fewer peaks than a hard edge`() {
        val w = 64
        val h = 16
        val hard = image(w, h) { x, _ -> if (x < 32) grey(40) else grey(210) }
        val soft = image(w, h) { x, _ -> grey(40 + x * 170 / 63) }
        val hardCount = PixelAnalysis.peakingMask(hard, w, h).count { it != 0 }
        val softCount = PixelAnalysis.peakingMask(soft, w, h).count { it != 0 }
        assertTrue("hard $hardCount soft $softCount", hardCount > 0 && softCount == 0)
    }

    @Test
    fun `tiny images do not crash the gradient`() {
        assertEquals(4, PixelAnalysis.gradient(IntArray(4), 2, 2).size)
        assertEquals(0, PixelAnalysis.gradient(IntArray(0), 0, 0).size)
    }
}
