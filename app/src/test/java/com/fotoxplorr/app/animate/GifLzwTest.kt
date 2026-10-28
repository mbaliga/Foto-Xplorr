package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The LZW stage on its own: a published sample, round trips over awkward inputs, and bad data. */
class GifLzwTest {

    private fun roundTrip(indices: ByteArray, minCode: Int) {
        val packed = GifLzw.encode(indices, indices.size, minCode)
        val out = ByteArray(indices.size)
        val r = GifLzw.decode(packed, packed.size, minCode, out, indices.size)
        assertEquals("pixels decoded", indices.size, r.count)
        assertEquals(GifLzw.End.FULL, r.end)
        assertArrayEquals(indices, out)
    }

    @Test
    fun `the 10 by 10 sample from the published GIF walkthrough encodes to its published bytes`() {
        // Matthew Flickinger's "What's in a GIF" example: min code size 2, 100 indices, 22 bytes.
        val rows = listOf(
            "1111122222", "1111122222", "1111122222", "1110000222", "1110000222",
            "2220000111", "2220000111", "2222211111", "2222211111", "2222211111",
        )
        val idx = ByteArray(100) { (rows[it / 10][it % 10] - '0').toByte() }
        val expected = intArrayOf(
            0x8C, 0x2D, 0x99, 0x87, 0x2A, 0x1C, 0xDC, 0x33, 0xA0, 0x02, 0x75, 0xEC, 0x95, 0xFA, 0xA8, 0xDE, 0x60, 0x8C, 0x04, 0x91, 0x4C, 0x01,
        ).map { it.toByte() }.toByteArray()
        val packed = GifLzw.encode(idx, 100, 2)
        assertArrayEquals(expected, packed)
        // And it decodes back.
        val out = ByteArray(100)
        val r = GifLzw.decode(expected, expected.size, 2, out, 100)
        assertEquals(100, r.count)
        assertArrayEquals(idx, out)
    }

    @Test
    fun `round trip over every minimum code size with random data`() {
        val rnd = Random(11)
        for (mc in 2..8) {
            val limit = 1 shl mc
            for (len in listOf(1, 2, 3, 100, 5000)) {
                val d = ByteArray(len) { rnd.nextInt(limit).toByte() }
                roundTrip(d, mc)
            }
        }
    }

    @Test
    fun `round trip over long runs, which is where the table grows by one code at a time`() {
        for (mc in 2..8) {
            roundTrip(ByteArray(50_000) { 1 }, mc)
            roundTrip(ByteArray(70_000) { (it / 1000 % (1 shl mc)).toByte() }, mc)
        }
    }

    @Test
    fun `round trip across many table resets`() {
        // 300,000 mildly random pixels over 16 colours refill the 4096-entry table dozens of times.
        val rnd = Random(5)
        val d = ByteArray(300_000) { (rnd.nextInt(4) * 4 + rnd.nextInt(2)).toByte() }
        roundTrip(d, 4)
    }

    @Test
    fun `round trip when the very last code lands on a width boundary`() {
        // Every length around the points where the code width steps 9 -> 10 -> 11 bits for a 256-colour alphabet.
        val rnd = Random(21)
        for (len in 250..1200 step 7) roundTrip(ByteArray(len) { rnd.nextInt(256).toByte() }, 8)
        for (len in 1..80) roundTrip(ByteArray(len) { (it % 3).toByte() }, 2)
    }

    @Test
    fun `a deferred clear, a table that fills and then simply stops growing, still decodes`() {
        // Hand-written stream, literal codes only and NO clear code: the table fills after 3838 codes at
        // minimum code size 8, the width reaches 12 bits, and 600 more codes follow at 12 bits.
        val mc = 8
        val clear = 1 shl mc
        val rnd = Random(6)
        val pixels = IntArray(3838 + 600 + 1) { rnd.nextInt(256) }
        val out = java.io.ByteArrayOutputStream()
        var acc = 0L
        var n = 0
        fun put(code: Int, width: Int) {
            acc = acc or (code.toLong() shl n)
            n += width
            while (n >= 8) {
                out.write((acc and 255).toInt())
                acc = acc ushr 8
                n -= 8
            }
        }
        var bits = mc + 1
        var next = clear + 2
        put(clear, bits)
        for ((i, p) in pixels.withIndex()) {
            put(p, bits)
            if (i > 0 && next < 4096) {
                next++
                if (next == (1 shl bits) && bits < 12) bits++
            }
        }
        put(clear + 1, bits)
        if (n > 0) out.write((acc and 255).toInt())
        val data = out.toByteArray()
        assertEquals("table filled and width is 12", 4096, next)
        val dst = ByteArray(pixels.size)
        val r = GifLzw.decode(data, data.size, mc, dst, pixels.size)
        assertEquals(GifLzw.End.FULL, r.end)
        assertEquals(pixels.size, r.count)
        for (i in pixels.indices) assertEquals("pixel $i", pixels[i], dst[i].toInt() and 255)
    }

    @Test
    fun `compression actually compresses a flat image`() {
        val d = ByteArray(100_000) { 3 }
        val packed = GifLzw.encode(d, d.size, 2)
        assertTrue("size ${packed.size}", packed.size < 1500)
    }

    @Test
    fun `a code the table does not hold is reported and does not throw`() {
        // min code 2: clear=4, eoi=5, width 3. Send clear, 1, then code 7 (next entry is 6) -> bad.
        val bits = intArrayOf(4, 1, 7)
        var acc = 0L
        var n = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in bits) {
            acc = acc or (c.toLong() shl n)
            n += 3
            while (n >= 8) {
                out.write((acc and 255).toInt())
                acc = acc ushr 8
                n -= 8
            }
        }
        if (n > 0) out.write((acc and 255).toInt())
        val data = out.toByteArray()
        val dst = ByteArray(10)
        val r = GifLzw.decode(data, data.size, 2, dst, 10)
        assertEquals(GifLzw.End.BAD_CODE, r.end)
        assertEquals(1, r.count)
    }

    @Test
    fun `truncated data returns what was decoded`() {
        val rnd = Random(8)
        val d = ByteArray(5000) { rnd.nextInt(16).toByte() }
        val packed = GifLzw.encode(d, d.size, 4)
        val cut = packed.copyOf(packed.size / 2)
        val out = ByteArray(d.size)
        val r = GifLzw.decode(cut, cut.size, 4, out, d.size)
        assertEquals(GifLzw.End.DATA_END, r.end)
        assertTrue(r.count in 1 until d.size)
        for (i in 0 until r.count) assertEquals(d[i], out[i])
    }

    @Test
    fun `random bytes never throw`() {
        val rnd = Random(99)
        for (trial in 0 until 300) {
            val data = ByteArray(rnd.nextInt(400)).also { rnd.nextBytes(it) }
            val out = ByteArray(2000)
            GifLzw.decode(data, data.size, 1 + rnd.nextInt(8), out, out.size)
        }
    }

    @Test
    fun `an out of range minimum code size is refused, not trusted`() {
        val out = ByteArray(4)
        assertEquals(GifLzw.End.BAD_CODE, GifLzw.decode(byteArrayOf(1, 2, 3), 3, 0, out, 4).end)
        assertEquals(GifLzw.End.BAD_CODE, GifLzw.decode(byteArrayOf(1, 2, 3), 3, 12, out, 4).end)
    }
}
