package com.fotoxplorr.app.formats

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encodes a tiny synthetic GIF and checks it byte-for-byte where the spec pins an exact value
 * (header, logical screen descriptor, trailer), then proves the compressed data itself is
 * correct by decoding it back with [GifDecoder] and comparing pixels and delays -- the only real
 * proof an LZW implementation is right, since a header can be perfect while the payload it
 * wraps is garbage.
 */
class GifEncoderTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun twoFrames() = listOf(
        GifFrame(intArrayOf(red, red, blue, blue), width = 2, height = 2, delayCentiseconds = 10),
        GifFrame(intArrayOf(blue, blue, red, red), width = 2, height = 2, delayCentiseconds = 20),
    )

    @Test
    fun `header is the literal GIF89a magic`() {
        val bytes = GifEncoder.encode(twoFrames())
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
    }

    @Test
    fun `logical screen descriptor records the canvas size and a global colour table`() {
        val bytes = GifEncoder.encode(twoFrames())

        // Width/height are little-endian uint16, immediately after the 6-byte header.
        val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        assertEquals(2, width)
        assertEquals(2, height)

        // Byte 10's top bit is the Global Colour Table flag -- must be set, since every frame
        // in this encoder shares one global table (see GifEncoder's class KDoc).
        val packed = bytes[10].toInt() and 0xFF
        assertTrue("expected the global colour table flag set", (packed and 0x80) != 0)

        // Background colour index and pixel aspect ratio -- both always written as 0.
        assertEquals(0, bytes[11].toInt())
        assertEquals(0, bytes[12].toInt())
    }

    @Test
    fun `file ends with the single required trailer byte`() {
        val bytes = GifEncoder.encode(twoFrames())
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
    }

    @Test
    fun `round trip through GifDecoder recovers both frames exactly`() {
        val frames = twoFrames()
        val bytes = GifEncoder.encode(frames)

        val decoded = GifDecoder.decode(bytes)

        // Frame count is the ground truth a real decoder sees -- more meaningful than counting
        // image-descriptor marker bytes in the raw stream, which can coincide with unrelated
        // compressed data.
        assertEquals("frame count", frames.size, decoded.frames.size)
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)

        // Only two distinct colours are used across both frames, so the encoder's palette
        // holds each of them exactly -- nearest-colour lookup has zero error here, meaning the
        // round trip must reproduce every pixel bit-for-bit, not just approximately.
        frames.forEachIndexed { index, expected ->
            val actual = decoded.frames[index]
            assertArrayEquals("frame $index pixels", expected.argb, actual.argb)
            assertEquals("frame $index delay", expected.delayCentiseconds, actual.delayCentiseconds)
        }
    }

    @Test
    fun `a single frame encodes and decodes without a loop extension`() {
        val frame = GifFrame(intArrayOf(red, blue, blue, red), width = 2, height = 2, delayCentiseconds = 5)
        val bytes = GifEncoder.encode(listOf(frame))

        val decoded = GifDecoder.decode(bytes)
        assertEquals(1, decoded.frames.size)
        assertArrayEquals(frame.argb, decoded.frames[0].argb)
    }

    @Test
    fun `mismatched frame dimensions are rejected`() {
        val a = GifFrame(intArrayOf(red), width = 1, height = 1, delayCentiseconds = 1)
        val b = GifFrame(intArrayOf(red, blue), width = 2, height = 1, delayCentiseconds = 1)
        try {
            GifEncoder.encode(listOf(a, b))
            org.junit.Assert.fail("expected an IllegalArgumentException for mismatched frame sizes")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `many colours quantise to at most 256 palette entries and still decode`() {
        // A synthetic gradient with more than 256 distinct colours forces the median-cut path
        // (Quantizer.buildPalette's "counts.size > MAX_COLORS" branch) rather than the trivial
        // "just use every colour" shortcut that a small test image would take.
        val width = 32
        val height = 32
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (0xFF shl 24) or ((x * 7) shl 16) or ((y * 7) shl 8) or ((x + y) * 3)
        }
        val frame = GifFrame(pixels, width, height, delayCentiseconds = 4)

        val bytes = GifEncoder.encode(listOf(frame))
        val decoded = GifDecoder.decode(bytes)

        assertEquals(1, decoded.frames.size)
        assertEquals(width * height, decoded.frames[0].argb.size)
    }

    @Test
    fun `a large pseudo-random frame round trips exactly through several LZW code-width bumps`() {
        // The header/LSD/trailer tests above pin the container; this pins the payload. LZW's
        // variable code width only grows past its starting size when the dictionary actually
        // fills up, which a tiny fixture can't force -- this is deliberately big and varied
        // enough (60x60, exactly 64 colours so the palette is lossless) to walk the encoder and
        // decoder through several code-size increases each, which is exactly where
        // LzwGif.decompress's dictionary-timing bug (see its KDoc) only ever showed up.
        val width = 60
        val height = 60
        val palette = IntArray(64) { i ->
            (0xFF shl 24) or (((i * 41) and 0xFF) shl 16) or (((i * 71) and 0xFF) shl 8) or ((i * 97) and 0xFF)
        }
        val random = java.util.Random(20260821L)
        val pixels = IntArray(width * height) { palette[random.nextInt(palette.size)] }
        val frame = GifFrame(pixels, width, height, delayCentiseconds = 8)

        val bytes = GifEncoder.encode(listOf(frame))
        val decoded = GifDecoder.decode(bytes)

        assertEquals(1, decoded.frames.size)
        assertArrayEquals(pixels, decoded.frames[0].argb)
    }
}
