package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneHistogramTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `an all-black image puts everything in bin 0 of every histogram`() {
        val h = ToneHistogram.of(IntArray(100) { argb(255, 0, 0, 0) })
        listOf(h.luma, h.red, h.green, h.blue).forEach { bins ->
            assertEquals(100, bins[0])
            assertEquals(100, bins.sum())
        }
    }

    @Test
    fun `pure red lands in red 255, the other channels 0, and luma at its Rec 709 weight`() {
        val h = ToneHistogram.of(IntArray(40) { argb(255, 255, 0, 0) })
        assertEquals(40, h.red[255])
        assertEquals(40, h.green[0])
        assertEquals(40, h.blue[0])
        assertEquals(40, h.luma[54]) // round(0.2126 * 255)
    }

    @Test
    fun `every histogram sums to the pixel count`() {
        val random = java.util.Random(3)
        val pixels = IntArray(5000) { argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val h = ToneHistogram.of(pixels)
        listOf(h.luma, h.red, h.green, h.blue).forEach { assertEquals(5000, it.sum()) }
        assertEquals(5000, h.total)
    }

    @Test
    fun `half black half white has equal mass at both ends`() {
        val pixels = IntArray(200) { if (it < 100) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }
        val h = ToneHistogram.of(pixels)
        assertEquals(100, h.luma[0])
        assertEquals(100, h.luma[255])
        assertEquals(h.red[0], h.red[255])
    }

    @Test
    fun `fully transparent pixels are not counted`() {
        val h = ToneHistogram.of(intArrayOf(argb(0, 0, 0, 0), argb(255, 10, 10, 10)))
        assertEquals(1, h.total)
        assertEquals(0, h.luma[0])
    }

    @Test
    fun `an empty image gives empty histograms`() {
        assertEquals(0, ToneHistogram.of(IntArray(0)).total)
    }

    @Test
    fun `normalised stays in 0 to 1 and tames a clipped end bin`() {
        val bins = IntArray(256).also { it[0] = 10_000; it[100] = 50; it[101] = 25 }
        val n = ToneHistogram.normalised(bins)
        assertEquals(256, n.size)
        assertTrue(n.all { it in 0f..1f })
        // The spike at 0 is clamped, so the real content is not flattened to a line.
        assertEquals(1f, n[0], 0f)
        assertEquals(1f, n[100], 1e-6f)
        assertTrue(n[101] > 0.5f)
    }

    @Test
    fun `normalised copes with an empty, all-zero, or end-only array`() {
        assertEquals(0, ToneHistogram.normalised(IntArray(0)).size)
        assertTrue(ToneHistogram.normalised(IntArray(256)).all { it == 0f })
        val endOnly = IntArray(256).also { it[255] = 9 }
        assertEquals(1f, ToneHistogram.normalised(endOnly)[255], 0f)
    }
}
