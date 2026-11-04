package com.fotoxplorr.app.media

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TileSizing], [BoundedBytes] and [TiffRawHint]: the pure parts of [RouteCoilDecoder]. */
class TileSizingTest {

    @Test
    fun `fill covers the box with the short edge and fit stays inside it`() {
        assertEquals(450 to 300, TileSizing.target(6000, 4000, 300, 300, fill = true))
        assertEquals(300 to 200, TileSizing.target(6000, 4000, 300, 300, fill = false))
        // A 3:1 panorama in a square Crop tile.
        assertEquals(900 to 300, TileSizing.target(9000, 3000, 300, 300, fill = true))
        // A non-square box: fill takes the larger of the two scales.
        assertEquals(400 to 300, TileSizing.target(4000, 3000, 200, 300, fill = true))
        assertEquals(200 to 150, TileSizing.target(4000, 3000, 200, 300, fill = false))
    }

    @Test
    fun `never larger than the source, and an unsized request fits the default edge`() {
        assertEquals(60 to 40, TileSizing.target(60, 40, 300, 300, fill = true))
        assertEquals(2048 to 1365, TileSizing.target(6000, 4000, null, null, fill = true))
        assertEquals(600 to 400, TileSizing.target(6000, 4000, null, 400, fill = false))
        assertEquals(TileSizing.MAX_EDGE, TileSizing.target(20000, 10000, 30000, 30000, fill = true).first)
    }

    @Test
    fun `the limits for the raw preview give exactly the target size`() {
        for ((w, h) in listOf(6000 to 4000, 4000 to 6000, 9000 to 3000, 1234 to 987)) {
            for (fill in listOf(true, false)) {
                val want = TileSizing.target(w, h, 300, 300, fill)
                assertEquals("$w x $h fill=$fill", want, targetSize(w, h, TileSizing.limitsFor(w, h, 300, 300, fill)))
            }
        }
    }

    @Test
    fun `a tile reads under much lower caps than the viewer`() {
        assertTrue(TileSizing.isTile(300, 300))
        assertTrue(TileSizing.isTile(300, null))
        assertFalse(TileSizing.isTile(1080, 2400))
        assertFalse(TileSizing.isTile(null, null))
        assertEquals(32L shl 20, TileSizing.simpleLimits(300, 300).maxInputBytes)
        assertEquals(12_000_000L, TileSizing.simpleLimits(300, 300).maxPixels)
        assertEquals(192L shl 20, TileSizing.simpleLimits(1080, 2400).maxInputBytes)
    }

    @Test
    fun `downscale averages each box and keeps a transparent pixel from darkening its neighbour`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        // 4x2, left half red, right half blue -> 2x1.
        val argb = intArrayOf(red, red, blue, blue, red, red, blue, blue)
        assertArrayEquals(intArrayOf(red, blue), TileSizing.downscale(argb, 4, 2, 2, 1))
        // White next to fully transparent black: the colour stays white, the alpha halves.
        val out = TileSizing.downscale(intArrayOf(0xFFFFFFFF.toInt(), 0x00000000), 2, 1, 1, 1)
        assertEquals(0x80FFFFFF.toInt(), out[0])
        // A fully transparent box stays fully transparent.
        assertEquals(0, TileSizing.downscale(IntArray(4), 2, 2, 1, 1)[0])
    }

    @Test
    fun `downscale to an uneven size covers every source pixel once`() {
        val argb = IntArray(7 * 5) { 0xFF000000.toInt() or (it * 7) }
        val out = TileSizing.downscale(argb, 7, 5, 3, 2)
        assertEquals(6, out.size)
        assertTrue(out.all { it ushr 24 == 0xFF })
    }

    // ---- BoundedBytes -------------------------------------------------------------------------------------

    /** Counts the bytes handed out, so a test can see that nothing past the cap was read. */
    private class CountingStream(private val inner: InputStream) : InputStream() {
        var served = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) served++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) served += it }
    }

    @Test
    fun `the bytes are read into one array of the declared length`() {
        val data = ByteArray(200_000) { it.toByte() }
        assertArrayEquals(data, BoundedBytes.read(ByteArrayInputStream(data), data.size.toLong(), 1L shl 20))
    }

    @Test
    fun `a file over the cap is refused before a byte is read`() {
        val stream = CountingStream(ByteArrayInputStream(ByteArray(4096)))
        assertNull(BoundedBytes.read(stream, 4096, cap = 1024))
        assertEquals(0L, stream.served)
    }

    @Test
    fun `an unknown length or a stream that ends early is refused`() {
        assertNull(BoundedBytes.read(ByteArrayInputStream(ByteArray(10)), -1, 1024))
        assertNull(BoundedBytes.read(ByteArrayInputStream(ByteArray(10)), 20, 1024))
    }

    // ---- TiffRawHint ----------------------------------------------------------------------------------------

    private fun tiffHeader(le: Boolean, vararg entries: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(256).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        if (le) buf.put(0, 'I'.code.toByte()).put(1, 'I'.code.toByte()) else buf.put(0, 'M'.code.toByte()).put(1, 'M'.code.toByte())
        buf.putShort(2, 42).putInt(4, 8).putShort(8, entries.size.toShort())
        entries.forEachIndexed { i, (tag, type, value) ->
            val p = 10 + i * 12
            buf.putShort(p, tag.toShort()).putShort(p + 2, type.toShort()).putInt(p + 4, 1)
            if (type == 3) buf.putShort(p + 8, value.toShort()) else buf.putInt(p + 8, value)
        }
        return buf.array()
    }

    @Test
    fun `a reduced-resolution or DNG IFD0 says raw, in either byte order`() {
        assertTrue(TiffRawHint.looksLikeRaw(tiffHeader(true, intArrayOf(254, 4, 1), intArrayOf(256, 3, 160))))
        assertTrue(TiffRawHint.looksLikeRaw(tiffHeader(false, intArrayOf(254, 4, 1))))
        assertTrue(TiffRawHint.looksLikeRaw(tiffHeader(false, intArrayOf(254, 3, 1))))
        assertTrue(TiffRawHint.looksLikeRaw(tiffHeader(true, intArrayOf(256, 3, 160), intArrayOf(0xC612, 1, 0x01040000))))
    }

    @Test
    fun `a plain tiff, a full-resolution IFD0 and junk do not`() {
        assertFalse(TiffRawHint.looksLikeRaw(tiffHeader(true, intArrayOf(256, 3, 160), intArrayOf(257, 3, 120))))
        assertFalse(TiffRawHint.looksLikeRaw(tiffHeader(true, intArrayOf(254, 4, 0))))
        assertFalse(TiffRawHint.looksLikeRaw(tiffHeader(true, intArrayOf(254, 4, 2))))
        assertFalse(TiffRawHint.looksLikeRaw(ByteArray(64)))
        assertFalse(TiffRawHint.looksLikeRaw(tiffHeader(true, intArrayOf(254, 4, 1)).copyOf(12)))
    }
}
