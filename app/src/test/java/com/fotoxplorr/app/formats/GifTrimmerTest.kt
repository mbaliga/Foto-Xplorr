package com.fotoxplorr.app.formats

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Builds a synthetic multi-frame GIF with [GifEncoder] (rather than shipping a binary test
 * fixture), trims it, and decodes the result -- so the whole decode-select-encode path this
 * feature is actually built from is exercised, not just the arithmetic of picking a sublist.
 */
class GifTrimmerTest {

    private fun solidFrame(color: Int, delay: Int) =
        GifFrame(IntArray(4) { color }, width = 2, height = 2, delayCentiseconds = delay)

    private val colors = listOf(
        0xFFFF0000.toInt(), // frame 0: red
        0xFF00FF00.toInt(), // frame 1: green
        0xFF0000FF.toInt(), // frame 2: blue
        0xFFFFFF00.toInt(), // frame 3: yellow
        0xFFFF00FF.toInt(), // frame 4: magenta
    )

    private fun fiveFrameGif(): ByteArray {
        val frames = colors.mapIndexed { index, color -> solidFrame(color, delay = (index + 1) * 10) }
        return GifEncoder.encode(frames)
    }

    @Test
    fun `trimming to the middle range keeps exactly those frames, in order, with their own delays`() {
        val source = fiveFrameGif()

        val result = GifTrimmer.trim(source, startFrame = 1, endFrame = 3)

        assertEquals(3, result.frameCount)
        assertEquals(5, result.originalFrameCount)

        val decoded = GifDecoder.decode(result.bytes)
        assertEquals(3, decoded.frames.size)
        // Frames 1..3 (green, blue, yellow) with delays 20, 30, 40 -- untouched by the trim.
        assertArrayEquals(IntArray(4) { colors[1] }, decoded.frames[0].argb)
        assertEquals(20, decoded.frames[0].delayCentiseconds)
        assertArrayEquals(IntArray(4) { colors[2] }, decoded.frames[1].argb)
        assertEquals(30, decoded.frames[1].delayCentiseconds)
        assertArrayEquals(IntArray(4) { colors[3] }, decoded.frames[2].argb)
        assertEquals(40, decoded.frames[2].delayCentiseconds)
    }

    @Test
    fun `trimming to a single frame produces a one-frame GIF`() {
        val source = fiveFrameGif()

        val result = GifTrimmer.trim(source, startFrame = 4, endFrame = 4)

        assertEquals(1, result.frameCount)
        val decoded = GifDecoder.decode(result.bytes)
        assertEquals(1, decoded.frames.size)
        assertArrayEquals(IntArray(4) { colors[4] }, decoded.frames[0].argb)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a start frame past the end of the source is rejected`() {
        GifTrimmer.trim(fiveFrameGif(), startFrame = 5, endFrame = 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an end frame before the start frame is rejected`() {
        GifTrimmer.trim(fiveFrameGif(), startFrame = 3, endFrame = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative start frame is rejected`() {
        GifTrimmer.trim(fiveFrameGif(), startFrame = -1, endFrame = 2)
    }
}
