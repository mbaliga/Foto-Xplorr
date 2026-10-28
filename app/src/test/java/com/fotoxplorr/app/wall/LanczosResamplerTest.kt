package com.fotoxplorr.app.wall

import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.editor.applyUnsharpMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * The enlarging maths with no Android in it: the weight tables, the banded two-pass resample, the
 * sharpen that follows it, and the arithmetic of the size caps. What this cannot say is how an
 * enlargement LOOKS (ringing, softness, the sharpen amount): that is a device check.
 */
class LanczosResamplerTest {

    private class Collected(val width: Int, val height: Int) : RowSink {
        val pixels = IntArray(width * height)
        val bands = mutableListOf<Pair<Int, Int>>()

        override fun write(firstRow: Int, rows: Int, argb: IntArray, offset: Int) {
            bands += firstRow to rows
            System.arraycopy(argb, offset, pixels, firstRow * width, rows * width)
        }
    }

    /** Remembers which rows were asked for, so a row read twice shows. */
    private class CountingRows(val inner: IntArrayRows) : PixelRows {
        override val width get() = inner.width
        override val height get() = inner.height
        val reads = IntArray(inner.height)

        override fun readRow(y: Int, into: IntArray) {
            reads[y]++
            inner.readRow(y, into)
        }
    }

    private fun randomImage(w: Int, h: Int, seed: Long = 1L): IntArrayRows {
        val random = Random(seed)
        return IntArrayRows(IntArray(w * h) { 0xFF000000.toInt() or random.nextInt(0x1000000) }, w, h)
    }

    private fun resample(
        source: PixelRows,
        w: Int,
        h: Int,
        sharpen: Float = 0f,
        bandRows: Int = bandRowsFor(w),
    ): IntArray = Collected(w, h).also { lanczosResample(source, w, h, it, sharpen = sharpen, bandRows = bandRows) }.pixels

    private fun channel(p: Int, shift: Int) = p ushr shift and 0xFF

    // --- the kernel and the weight tables ---

    @Test
    fun `the kernel is one at the centre, zero at every whole step and beyond three, and symmetric`() {
        assertEquals(1.0, Lanczos.kernel(0.0), 0.0)
        for (n in 1..4) assertEquals("at $n", 0.0, Lanczos.kernel(n.toDouble()), 1e-12)
        assertEquals(0.0, Lanczos.kernel(3.5), 0.0)
        for (x in listOf(0.1, 0.5, 1.3, 2.9)) assertEquals(Lanczos.kernel(x), Lanczos.kernel(-x), 0.0)
        // The first side lobe is negative: that is the sharpness, and the ringing.
        assertTrue(Lanczos.kernel(1.5) < 0.0)
    }

    @Test
    fun `the weights of every output index add up to one`() {
        for ((src, dst) in listOf(40 to 100, 40 to 41, 7 to 91, 1000 to 3333, 100 to 100, 2 to 50, 1 to 9, 100 to 37, 640 to 20)) {
            val table = Lanczos.table(src, dst)
            for (i in 0 until dst) {
                var sum = 0.0
                for (k in 0 until table.count[i]) sum += table.weights[i * table.stride + k]
                assertEquals("$src to $dst at $i", 1.0, sum, 1e-5)
            }
        }
    }

    @Test
    fun `the weights are symmetric, the last output mirrors the first`() {
        for ((src, dst) in listOf(40 to 100, 41 to 100, 7 to 91, 100 to 37)) {
            val table = Lanczos.table(src, dst)
            for (i in 0 until dst) {
                val mirror = dst - 1 - i
                assertEquals("count at $i of $src to $dst", table.count[mirror], table.count[i])
                // Source index j of output i is source index (src - 1 - j) of the mirrored output.
                assertEquals(src - 1 - (table.first[i] + table.count[i] - 1), table.first[mirror])
                for (k in 0 until table.count[i]) {
                    val here = table.weights[i * table.stride + k]
                    val there = table.weights[mirror * table.stride + table.count[i] - 1 - k]
                    assertEquals("at $i tap $k of $src to $dst", here.toDouble(), there.toDouble(), 1e-6)
                }
            }
        }
    }

    @Test
    fun `every tap is a pixel of the source, in order, and never more than the table's stride`() {
        for ((src, dst) in listOf(40 to 100, 3 to 200, 1 to 5, 500 to 12, 12 to 12)) {
            val table = Lanczos.table(src, dst)
            for (i in 0 until dst) {
                assertTrue(table.count[i] in 1..table.stride)
                assertTrue(table.first[i] >= 0)
                assertTrue(table.first[i] + table.count[i] <= src)
                if (i > 0) {
                    assertTrue(table.first[i] >= table.first[i - 1])
                    assertTrue(table.first[i] + table.count[i] >= table.first[i - 1] + table.count[i - 1])
                }
            }
        }
    }

    @Test
    fun `at scale one a table is a single tap of weight one`() {
        val table = Lanczos.table(50, 50)
        assertEquals(1, table.stride)
        for (i in 0 until 50) {
            assertEquals(i, table.first[i])
            assertEquals(1, table.count[i])
            assertEquals(1f, table.weights[i], 0f)
        }
    }

    @Test
    fun `a table with nothing in it is refused`() {
        for ((src, dst) in listOf(0 to 5, 5 to 0, -1 to 3)) {
            try {
                Lanczos.table(src, dst)
                fail("$src to $dst should be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // --- what a resample must keep ---

    @Test
    fun `at scale one the picture comes back exactly`() {
        val source = randomImage(37, 23)
        assertArrayEquals(source.pixels, resample(source, 37, 23))
        // Sharpening is off here: that one is not meant to be the identity.
    }

    @Test
    fun `one axis at scale one is also exact on that axis`() {
        // Same width, taller: every column of the result is that column of the source, enlarged.
        val source = IntArrayRows(IntArray(5 * 4) { if (it % 5 == 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }, 5, 4)
        val out = resample(source, 5, 11)
        for (y in 0 until 11) for (x in 0 until 5) {
            val p = out[y * 5 + x]
            assertEquals("column $x row $y", if (x == 2) 255 else 0, channel(p, 16))
        }
    }

    @Test
    fun `a flat colour stays that colour, at the ends of the range too`() {
        for (colour in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F3A9C.toInt(), 0xFF010203.toInt(), 0xFFFEFDFC.toInt())) {
            val source = IntArrayRows(IntArray(13 * 9) { colour }, 13, 9)
            for ((w, h) in listOf(40 to 30, 14 to 10, 13 * 7 to 9 * 7)) {
                assertTrue("$colour at $w x $h", resample(source, w, h).all { it == colour })
                // A mask over a flat area finds nothing to sharpen.
                assertTrue("$colour sharpened at $w x $h", resample(source, w, h, sharpen = 0.25f).all { it == colour })
            }
        }
    }

    @Test
    fun `a straight ramp stays straight, along either axis`() {
        // 50 px wide, 10 + 4 x. Output pixel x is at source position (x + 0.5) / 2.5 - 0.5.
        val ramp = IntArrayRows(IntArray(50 * 6) { val v = 10 + 4 * (it % 50); 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v }, 50, 6)
        val out = resample(ramp, 125, 15)
        for (x in 10 until 115) {
            val expected = 10 + 4 * ((x + 0.5) / 2.5 - 0.5)
            assertEquals("column $x", expected, channel(out[7 * 125 + x], 16).toDouble(), 1.0)
        }
        // The same ramp turned on its side, through the vertical pass.
        val tall = IntArrayRows(IntArray(6 * 50) { val v = 10 + 4 * (it / 6); 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v }, 6, 50)
        val outTall = resample(tall, 15, 125)
        for (y in 10 until 115) {
            val expected = 10 + 4 * ((y + 0.5) / 2.5 - 0.5)
            assertEquals("row $y", expected, channel(outTall[y * 15 + 7], 16).toDouble(), 1.0)
        }
    }

    @Test
    fun `a hard edge overshoots a little and is clamped to the range`() {
        val edge = IntArrayRows(IntArray(20 * 3) { if (it % 20 < 10) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }, 20, 3)
        val out = resample(edge, 80, 12)
        // Ringing is real for this filter, and the clamp keeps it a valid colour (no wrap-around).
        for (p in out) for (shift in intArrayOf(16, 8, 0)) assertTrue(channel(p, shift) in 0..255)
        assertEquals(0, channel(out[0], 16))
        assertEquals(255, channel(out[79], 16))
        // Away from the edge the two sides are flat.
        assertEquals(0, channel(out[20], 16))
        assertEquals(255, channel(out[60], 16))
    }

    @Test
    fun `the channels are kept apart`() {
        val source = IntArrayRows(IntArray(8 * 8) { 0xFF2060E0.toInt() }, 8, 8)
        val out = resample(source, 21, 21)
        assertTrue(out.all { channel(it, 24) == 255 && channel(it, 16) == 0x20 && channel(it, 8) == 0x60 && channel(it, 0) == 0xE0 })
    }

    @Test
    fun `a one pixel source and a one row source do not crash`() {
        val one = IntArrayRows(intArrayOf(0xFF336699.toInt()), 1, 1)
        assertTrue(resample(one, 7, 5).all { it == 0xFF336699.toInt() })

        val row = IntArrayRows(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 3, 1)
        val out = resample(row, 12, 6)
        for (y in 1 until 6) for (x in 0 until 12) assertEquals(out[x], out[y * 12 + x])
    }

    // --- bands ---

    @Test
    fun `the result is the same whatever the band height, byte for byte`() {
        val source = randomImage(37, 23)
        val whole = resample(source, 91, 58, bandRows = 58)
        for (rows in listOf(1, 2, 7, 13, 57, 1000)) {
            assertArrayEquals("bands of $rows", whole, resample(source, 91, 58, bandRows = rows))
        }
    }

    @Test
    fun `sharpened, the result is also the same whatever the band height`() {
        val source = randomImage(31, 29, seed = 5)
        val whole = resample(source, 77, 70, sharpen = 0.2f, bandRows = 70)
        for (rows in listOf(1, 3, 8, 33)) {
            assertArrayEquals("bands of $rows", whole, resample(source, 77, 70, sharpen = 0.2f, bandRows = rows))
        }
    }

    @Test
    fun `sharpening band by band is the editor's mask over the whole picture`() {
        // The halo is what makes this true; it also pins that the editor's mask still reaches no further than the halo.
        val source = randomImage(31, 29, seed = 9)
        val plain = resample(source, 77, 70, bandRows = 70)
        val expected = plain.copyOf().also { applyUnsharpMask(it, 77, 70, 0.2f, 0f) }
        assertFalse(plain.contentEquals(expected))
        for (rows in listOf(1, 4, 11, 70)) {
            assertArrayEquals("bands of $rows", expected, resample(source, 77, 70, sharpen = 0.2f, bandRows = rows))
        }
    }

    @Test
    fun `each source row is read once, however the bands fall`() {
        for (rows in listOf(1, 5, 20, 1000)) {
            val counting = CountingRows(randomImage(30, 41, seed = 3))
            resample(counting, 75, 103, bandRows = rows)
            assertTrue("bands of $rows: ${counting.reads.toList()}", counting.reads.all { it <= 1 })
            assertTrue(counting.reads.all { it == 1 })
        }
        // Sharpened, the halo reaches into the next band's rows; they are still only read once.
        val counting = CountingRows(randomImage(30, 41, seed = 3))
        resample(counting, 75, 103, sharpen = 0.2f, bandRows = 6)
        assertTrue(counting.reads.all { it == 1 })
    }

    @Test
    fun `the bands are in order, cover every row once, and are no taller than asked`() {
        val sink = Collected(40, 100)
        lanczosResample(randomImage(10, 25), 40, 100, sink, bandRows = 30)
        assertEquals(listOf(0 to 30, 30 to 30, 60 to 30, 90 to 10), sink.bands)
    }

    @Test
    fun `progress only goes forward and ends at one`() {
        val seen = mutableListOf<Float>()
        lanczosResample(randomImage(10, 25), 40, 100, Collected(40, 100), bandRows = 7, onProgress = { seen += it })
        assertEquals(1f, seen.last(), 0f)
        assertEquals(seen.sorted(), seen)
        assertTrue(seen.first() > 0f)
    }

    // --- cancelling ---

    @Test
    fun `a cancellation is honoured between bands and nothing more is written`() {
        val sink = Collected(40, 100)
        var asked = 0
        try {
            lanczosResample(randomImage(10, 25), 40, 100, sink, bandRows = 10, checkCancelled = { if (++asked == 4) throw kotlinx.coroutines.CancellationException("stop") })
            fail("should have been cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        // Asked before each band: the fourth ask refused the fourth band.
        assertEquals(listOf(0 to 10, 10 to 10, 20 to 10), sink.bands)
    }

    @Test
    fun `cancelled before the first band, nothing is read and nothing written`() {
        val counting = CountingRows(randomImage(10, 25))
        val sink = Collected(40, 100)
        try {
            lanczosResample(counting, 40, 100, sink, checkCancelled = { throw kotlinx.coroutines.CancellationException("stop") })
            fail("should have been cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertTrue(sink.bands.isEmpty())
        assertTrue(counting.reads.all { it == 0 })
    }

    // --- the caps ---

    @Test
    fun `the cap is 8192 on an edge and 64 million pixels, and the boundary is exact`() {
        assertEquals(8192, MAX_EXPORT_EDGE)
        assertEquals(64_000_000L, MAX_EXPORT_PIXELS)
        assertTrue(fitsEnlargeCap(8000, 8000))
        assertTrue(fitsEnlargeCap(8192, 7812)) // 63,995,904
        assertFalse(fitsEnlargeCap(8192, 7813)) // 64,004,096
        assertFalse(fitsEnlargeCap(8193, 10))
        assertFalse(fitsEnlargeCap(10, 8193))
        assertFalse(fitsEnlargeCap(0, 10))
        assertFalse(fitsEnlargeCap(10, -1))
        // Two edges that are each fine but whose product overflows an Int: still refused, not wrapped.
        assertFalse(fitsEnlargeCap(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `a target over the cap is refused before anything is allocated`() {
        val source = randomImage(4, 4)
        for ((w, h) in listOf(8193 to 8, 8 to 8193, 8192 to 8192, Int.MAX_VALUE to 2, 0 to 5)) {
            val sink = Collected(1, 1)
            try {
                lanczosResample(source, w, h, sink)
                fail("$w x $h should be refused")
            } catch (_: IllegalArgumentException) {
            }
            assertTrue(sink.bands.isEmpty())
        }
    }

    @Test
    fun `a band's buffers fit an int and the budget at every width`() {
        for (w in listOf(1, 2, 100, 4096, 8191, 8192)) {
            val rows = bandRowsFor(w)
            assertTrue("rows at $w", rows in 1..LanczosBand.MAX_ROWS)
            val ints = (rows + 2L * LanczosBand.HALO_ROWS) * w
            assertTrue("ints at $w", ints <= Int.MAX_VALUE)
            // Three int buffers (the band, the mask's copy and its scratch) stay near the budget; a single row may exceed it.
            assertTrue("budget at $w", rows == 1 || 3L * ints * 4 <= LanczosBand.BUDGET_BYTES * 2)
        }
        assertEquals(1, bandRowsFor(Int.MAX_VALUE))
    }

    @Test
    fun `the working rows are bounded by the band, not by the picture`() {
        // The ring never holds more than a band's source rows (plus the taps), however tall the picture is.
        val bands = LanczosBand.sourceRowsHeld(Lanczos.table(4000, 8000), bandRows = 128, halo = LanczosBand.HALO_ROWS)
        assertTrue("held $bands", bands < 128)
        val downscale = LanczosBand.sourceRowsHeld(Lanczos.table(8000, 1000), bandRows = 128, halo = LanczosBand.HALO_ROWS)
        assertTrue("held $downscale", downscale > 1000 && downscale < 8000)
    }

    @Test
    fun `the sharpen grows with the enlargement and stays gentle`() {
        assertEquals(0f, enlargeSharpen(1.0), 0f)
        assertEquals(0f, enlargeSharpen(0.5), 0f)
        assertTrue(enlargeSharpen(2.0) > 0f)
        assertTrue(enlargeSharpen(3.0) > enlargeSharpen(2.0))
        assertEquals(enlargeSharpen(8.0), enlargeSharpen(100.0), 0f)
        assertTrue(enlargeSharpen(100.0) <= 0.25f)
        assertEquals(0f, enlargeSharpen(Double.NaN), 0f)
    }
}
