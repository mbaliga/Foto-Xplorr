package com.fotoxplorr.app.curate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurDetectorTest {

    @Test
    fun `too narrow or too short a frame has no interior pixels and returns null`() {
        assertNull(BlurDetector.sharpness(IntArray(2 * 2), width = 2, height = 2))
        assertNull(BlurDetector.sharpness(IntArray(1 * 10), width = 1, height = 10))
    }

    @Test
    fun `a pixel array that does not match width times height returns null`() {
        assertNull(BlurDetector.sharpness(IntArray(5), width = 3, height = 3))
    }

    @Test
    fun `a perfectly uniform frame scores exactly zero`() {
        val pixels = IntArray(10 * 10) { gray(128) }
        val score = BlurDetector.sharpness(pixels, width = 10, height = 10)
        assertEquals(0f, score!!, 0.0001f)
    }

    @Test
    fun `fine detail scores sharper than the identical contrast smoothed into coarse blocks`() {
        val size = 32
        // Same two brightness levels, same overall contrast -- the only difference is how often
        // the frame switches between them. That isolates exactly what variance-of-Laplacian is
        // supposed to measure: high-frequency content, not brightness range.
        val sharp = checkerboard(size, low = 90, high = 150, blockSize = 1)
        val blurry = checkerboard(size, low = 90, high = 150, blockSize = 8)

        val sharpScore = BlurDetector.sharpness(sharp, size, size)!!
        val blurryScore = BlurDetector.sharpness(blurry, size, size)!!

        assertTrue("sharp=$sharpScore should exceed blurry=$blurryScore", sharpScore > blurryScore)
    }

    @Test
    fun `works on a non-square frame`() {
        val width = 12
        val height = 6
        val pixels = IntArray(width * height) { i -> gray(if (i % 2 == 0) 40 else 220) }
        val score = BlurDetector.sharpness(pixels, width, height)
        assertTrue(score != null && score > 0f)
    }

    /** Opaque, neutral grey at [level] on every channel -- luma then equals [level] exactly. */
    private fun gray(level: Int): Int = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    private fun checkerboard(size: Int, low: Int, high: Int, blockSize: Int): IntArray {
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val onHigh = ((x / blockSize) + (y / blockSize)) % 2 == 0
                pixels[y * size + x] = gray(if (onHigh) high else low)
            }
        }
        return pixels
    }
}
