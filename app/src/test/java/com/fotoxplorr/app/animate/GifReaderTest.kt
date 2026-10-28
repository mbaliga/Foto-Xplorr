package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The GIF decoder against files built byte by byte by [HandGif] (which shares no code with it),
 * covering what the spec leaves to the decoder: interlace, disposal, transparency, palettes, clipping,
 * and what happens to a file that is cut short or broken.
 */
class GifReaderTest {

    private val RED = 0xFF0000
    private val GREEN = 0x00FF00
    private val BLUE = 0x0000FF
    private val WHITE = 0xFFFFFF
    private val opaque = { rgb: Int -> 0xFF000000.toInt() or rgb }

    private val palette = intArrayOf(WHITE, RED, GREEN, BLUE)

    @Test
    fun `a still image decodes to its palette colours`() {
        val gif = HandGif(4, 3, palette).gce(0, 5).image(0, 0, 4, 3, intArrayOf(0, 1, 2, 3, 3, 2, 1, 0, 1, 1, 2, 2)).trailer().bytes()
        val r = GifReader.decodeAll(gif)
        assertTrue(r.complete)
        val seq = r.sequence!!
        assertEquals(4, seq.width)
        assertEquals(3, seq.height)
        assertEquals(1, seq.frames.size)
        assertEquals(1, seq.plays) // no loop extension: plays once
        assertEquals(50, seq.frames[0].delayMs)
        val want = intArrayOf(0, 1, 2, 3, 3, 2, 1, 0, 1, 1, 2, 2).map { opaque(palette[it]) }.toIntArray()
        assertArrayEquals(want, seq.frames[0].pixels)
    }

    @Test
    fun `the loop extension is read as total plays`() {
        fun plays(loop: Int?): Int {
            val g = HandGif(1, 1, palette, loop).image(0, 0, 1, 1, intArrayOf(0)).trailer().bytes()
            return GifReader.decodeAll(g).sequence!!.plays
        }
        assertEquals(1, plays(null))
        assertEquals(0, plays(0)) // for ever
        assertEquals(2, plays(1)) // one repeat after the first play
        assertEquals(4, plays(3))
        assertEquals(65_536, plays(65_535))
    }

    @Test
    fun `interlaced rows land on their own picture rows for every height`() {
        val table = IntArray(32) { (it * 8) shl 16 or ((255 - it * 8) shl 8) or it }
        for (h in 1..24) {
            val w = 3
            // Picture row y is filled with colour index y; stored order is the four interlace passes.
            val order = ArrayList<Int>()
            for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
                var y = start
                while (y < h) {
                    order.add(y)
                    y += step
                }
            }
            val stored = IntArray(w * h)
            for ((n, y) in order.withIndex()) for (x in 0 until w) stored[n * w + x] = y
            val gif = HandGif(w, h, table).image(0, 0, w, h, stored, interlaced = true).trailer().bytes()
            val f = GifReader.decodeAll(gif).sequence!!.frames[0].pixels
            for (y in 0 until h) for (x in 0 until w) assertEquals("h=$h y=$y", opaque(table[y]), f[y * w + x])
        }
    }

    @Test
    fun `a transparent index leaves what was there showing through`() {
        val gif = HandGif(2, 1, palette)
            .gce(1, 10).image(0, 0, 2, 1, intArrayOf(1, 1))
            .gce(1, 10, transparentIndex = 0).image(0, 0, 2, 1, intArrayOf(0, 3))
            .trailer().bytes()
        val frames = GifReader.decodeAll(gif).sequence!!.frames
        assertArrayEquals(intArrayOf(opaque(RED), opaque(RED)), frames[0].pixels)
        assertArrayEquals(intArrayOf(opaque(RED), opaque(BLUE)), frames[1].pixels)
    }

    @Test
    fun `a transparent index on the first frame is a clear pixel, not black`() {
        val gif = HandGif(2, 1, palette).gce(0, 10, transparentIndex = 0).image(0, 0, 2, 1, intArrayOf(0, 2)).trailer().bytes()
        val px = GifReader.decodeAll(gif).sequence!!.frames[0].pixels
        assertEquals(0, px[0])
        assertEquals(opaque(GREEN), px[1])
    }

    @Test
    fun `disposal 2 clears the frame's own rectangle before the next frame`() {
        // Frame 0: whole 3x1 red. Frame 1: green at x=1, disposal 2. Frame 2: blue at x=0 (transparent elsewhere).
        val gif = HandGif(3, 1, palette)
            .gce(1, 10).image(0, 0, 3, 1, intArrayOf(1, 1, 1))
            .gce(2, 10).image(1, 0, 1, 1, intArrayOf(2))
            .gce(1, 10, transparentIndex = 0).image(0, 0, 3, 1, intArrayOf(3, 0, 0))
            .trailer().bytes()
        val f = GifReader.decodeAll(gif).sequence!!.frames
        assertArrayEquals(intArrayOf(opaque(RED), opaque(GREEN), opaque(RED)), f[1].pixels)
        // After frame 1's disposal, x=1 is clear; the rest stays red; frame 2 paints blue at x=0.
        assertArrayEquals(intArrayOf(opaque(BLUE), 0, opaque(RED)), f[2].pixels)
    }

    @Test
    fun `disposal 3 puts back what was there before the frame`() {
        val gif = HandGif(3, 1, palette)
            .gce(1, 10).image(0, 0, 3, 1, intArrayOf(1, 1, 1))
            .gce(3, 10).image(1, 0, 2, 1, intArrayOf(2, 2))
            .gce(1, 10, transparentIndex = 0).image(0, 0, 3, 1, intArrayOf(0, 0, 0))
            .trailer().bytes()
        val f = GifReader.decodeAll(gif).sequence!!.frames
        assertArrayEquals(intArrayOf(opaque(RED), opaque(GREEN), opaque(GREEN)), f[1].pixels)
        assertArrayEquals(intArrayOf(opaque(RED), opaque(RED), opaque(RED)), f[2].pixels)
    }

    @Test
    fun `disposal 0 and unspecified values above 3 leave the frame in place`() {
        for (d in listOf(0, 4, 5, 6, 7)) {
            val gif = HandGif(2, 1, palette)
                .gce(d, 10).image(0, 0, 2, 1, intArrayOf(1, 1))
                .gce(1, 10, transparentIndex = 0).image(0, 0, 2, 1, intArrayOf(0, 0))
                .trailer().bytes()
            val f = GifReader.decodeAll(gif).sequence!!.frames
            assertArrayEquals("d=$d", intArrayOf(opaque(RED), opaque(RED)), f[1].pixels)
        }
    }

    @Test
    fun `a local colour table overrides the global one for its frame only`() {
        val local = intArrayOf(BLUE, BLUE, BLUE, BLUE)
        val gif = HandGif(1, 1, palette)
            .gce(1, 10).image(0, 0, 1, 1, intArrayOf(1), localTable = local)
            .gce(1, 10).image(0, 0, 1, 1, intArrayOf(1))
            .trailer().bytes()
        val f = GifReader.decodeAll(gif).sequence!!.frames
        assertEquals(opaque(BLUE), f[0].pixels[0])
        assertEquals(opaque(RED), f[1].pixels[0])
    }

    @Test
    fun `a file with only local tables decodes`() {
        val gif = HandGif(2, 1, null).gce(0, 10).image(0, 0, 2, 1, intArrayOf(0, 1), localTable = intArrayOf(GREEN, RED)).trailer().bytes()
        val px = GifReader.decodeAll(gif).sequence!!.frames[0].pixels
        assertArrayEquals(intArrayOf(opaque(GREEN), opaque(RED)), px)
    }

    @Test
    fun `an index past the end of the palette paints nothing`() {
        val gif = HandGif(2, 1, palette).gce(0, 10).image(0, 0, 2, 1, intArrayOf(1, 3), minCode = 3).trailer().bytes()
        val ok = GifReader.decodeAll(gif).sequence!!.frames[0].pixels
        assertEquals(opaque(BLUE), ok[1])
        // Palette of 4, but a pixel value 6 (legal in a 3-bit stream): clear, not a crash.
        val bad = HandGif(2, 1, palette).gce(0, 10).image(0, 0, 2, 1, intArrayOf(1, 6), minCode = 3).trailer().bytes()
        val px = GifReader.decodeAll(bad).sequence!!.frames[0].pixels
        assertEquals(opaque(RED), px[0])
        assertEquals(0, px[1])
    }

    @Test
    fun `a frame that hangs off the canvas is clipped`() {
        val gif = HandGif(3, 2, palette).gce(0, 10).image(2, 1, 3, 3, IntArray(9) { 1 }).trailer().bytes()
        val r = GifReader.decodeAll(gif)
        val px = r.sequence!!.frames[0].pixels
        assertEquals(opaque(RED), px[1 * 3 + 2])
        assertEquals(0, px[0])
        assertEquals(6, px.size)
    }

    @Test
    fun `a frame smaller than the canvas leaves the rest clear`() {
        val gif = HandGif(3, 3, palette).gce(0, 10).image(1, 1, 1, 1, intArrayOf(2)).trailer().bytes()
        val px = GifReader.decodeAll(gif).sequence!!.frames[0].pixels
        for (i in px.indices) assertEquals(if (i == 4) opaque(GREEN) else 0, px[i])
    }

    @Test
    fun `delays follow browsers by default and the stored value on request`() {
        val gif = HandGif(1, 1, palette)
            .gce(0, 0).image(0, 0, 1, 1, intArrayOf(0))
            .gce(0, 1).image(0, 0, 1, 1, intArrayOf(0))
            .gce(0, 2).image(0, 0, 1, 1, intArrayOf(0))
            .gce(0, 250).image(0, 0, 1, 1, intArrayOf(0))
            .trailer().bytes()
        val browser = GifReader.decodeAll(gif).sequence!!.frames.map { it.delayMs }
        assertEquals(listOf(100, 100, 20, 2500), browser)
        val raw = GifReader.decodeAll(gif, browserDelays = false).sequence!!.frames.map { it.delayMs }
        assertEquals(listOf(0, 10, 20, 2500), raw)
    }

    @Test
    fun `comment, plain text and unknown extensions are skipped`() {
        val base = HandGif(1, 1, palette)
        val raw = base.gce(0, 10).image(0, 0, 1, 1, intArrayOf(2)).trailer().bytes()
        // Insert a comment extension and a junk application extension right after the colour table.
        val tableEnd = 13 + 3 * 4
        val comment = byteArrayOf(0x21, 0xFE.toByte(), 3, 'h'.code.toByte(), 'i'.code.toByte(), '!'.code.toByte(), 0)
        val junk = byteArrayOf(0x21, 0xFF.toByte(), 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 2, 9, 9, 0)
        val spiced = raw.copyOfRange(0, tableEnd) + comment + junk + raw.copyOfRange(tableEnd, raw.size)
        val r = GifReader.decodeAll(spiced)
        assertTrue(r.detail, r.complete)
        assertEquals(opaque(GREEN), r.sequence!!.frames[0].pixels[0])
    }

    @Test
    fun `stray zero bytes between blocks are tolerated`() {
        val raw = HandGif(1, 1, palette).gce(0, 10).image(0, 0, 1, 1, intArrayOf(2)).trailer().bytes()
        val withPad = raw.copyOfRange(0, raw.size - 1) + byteArrayOf(0, 0) + byteArrayOf(0x3B)
        assertTrue(GifReader.decodeAll(withPad).complete)
    }

    // ---- not a GIF, and damaged GIFs ----

    @Test
    fun `bytes that are not a GIF are refused with no frames`() {
        for (junk in listOf(ByteArray(0), byteArrayOf(1, 2, 3), "GIF90a".toByteArray() + ByteArray(20), "PNG".toByteArray())) {
            val r = GifReader.decodeAll(junk)
            assertEquals(DecodeProblem.NOT_THIS_FORMAT, r.problem)
            assertNull(r.sequence)
        }
    }

    @Test
    fun `a GIF87a header is accepted`() {
        val raw = HandGif(1, 1, palette).image(0, 0, 1, 1, intArrayOf(1)).trailer().bytes()
        raw[4] = '7'.code.toByte()
        assertTrue(GifReader.decodeAll(raw).complete)
    }

    @Test
    fun `a missing trailer is a truncation with every frame kept`() {
        val raw = HandGif(1, 1, palette).gce(0, 10).image(0, 0, 1, 1, intArrayOf(2)).bytes()
        val r = GifReader.decodeAll(raw)
        assertEquals(DecodeProblem.TRUNCATED, r.problem)
        assertEquals(1, r.sequence!!.frames.size)
    }

    @Test
    fun `cutting a file at every length never throws, never invents frames and keeps whole ones`() {
        val rnd = Random(4)
        val w = 9
        val h = 7
        val table = IntArray(8) { rnd.nextInt(0xFFFFFF) }
        val b = HandGif(w, h, table, loop = 0)
        repeat(3) { b.gce(1, 7 + it).image(0, 0, w, h, IntArray(w * h) { rnd.nextInt(8) }) }
        val full = b.trailer().bytes()
        val whole = GifReader.decodeAll(full)
        assertTrue(whole.complete)
        for (len in 0 until full.size) {
            val r = GifReader.decodeAll(full.copyOf(len))
            assertFalse("len=$len should be flagged", r.complete)
            val got = r.sequence?.frames ?: emptyList()
            assertTrue(got.size <= 3)
            // Every frame but a possibly part-painted last one equals the full decode's.
            for (i in 0 until got.size - 1) assertArrayEquals("len=$len frame $i", whole.sequence!!.frames[i].pixels, got[i].pixels)
        }
    }

    @Test
    fun `a frame whose data is cut mid-way comes back part painted and flagged`() {
        val w = 8
        val h = 8
        val px = IntArray(w * h) { 1 + (it % 3) }
        val raw = HandGif(w, h, palette).gce(0, 10).image(0, 0, w, h, px).trailer().bytes()
        val cut = raw.copyOf(raw.size - 14)
        val r = GifReader.decodeAll(cut)
        assertTrue(r.problem == DecodeProblem.TRUNCATED || r.problem == DecodeProblem.CORRUPT)
        val frame = r.sequence!!.frames[0].pixels
        assertEquals(opaque(palette[px[0]]), frame[0])
        assertEquals(0, frame[w * h - 1]) // the end never arrived
    }

    @Test
    fun `random byte damage never throws and is always reported or harmless`() {
        val sequence = AnimTestKit.photoSequence(24, 18, 4)
        val good = GifEncoder.encode(sequence)
        val rnd = Random(77)
        for (trial in 0 until 600) {
            val bad = good.copyOf()
            repeat(1 + rnd.nextInt(4)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
            val r = GifReader.decodeAll(bad)
            if (r.complete) assertNotNull(r.sequence)
        }
    }

    @Test
    fun `a header that claims a gigantic canvas is refused before anything is allocated`() {
        val raw = "GIF89a".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0, 0) + byteArrayOf(0x3B)
        val r = GifReader.decodeAll(raw)
        assertEquals(DecodeProblem.TOO_LARGE, r.problem)
        assertNull(r.sequence)
    }

    @Test
    fun `an image rectangle larger than the limit is refused`() {
        val b = HandGif(2, 2, palette)
        val raw = b.bytes() + byteArrayOf(0x2C, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0)
        val r = GifReader.decodeAll(raw)
        assertEquals(DecodeProblem.TOO_LARGE, r.problem)
        assertEquals(0, r.sequence!!.frames.size)
    }

    @Test
    fun `an unknown block type stops reading with what came before`() {
        val raw = HandGif(1, 1, palette).gce(0, 10).image(0, 0, 1, 1, intArrayOf(2)).bytes() + byteArrayOf(0x77, 1, 2, 3)
        val r = GifReader.decodeAll(raw)
        assertEquals(DecodeProblem.CORRUPT, r.problem)
        assertEquals(1, r.sequence!!.frames.size)
    }

    @Test
    fun `the frame and size limits stop reading and say so`() {
        val b = HandGif(2, 2, palette)
        repeat(5) { b.gce(1, 10).image(0, 0, 2, 2, intArrayOf(1, 1, 1, 1)) }
        val raw = b.trailer().bytes()
        val byCount = GifReader.decodeAll(raw, DecodeLimits(maxFrames = 3))
        assertEquals(DecodeProblem.TOO_LARGE, byCount.problem)
        assertEquals(3, byCount.sequence!!.frames.size)
        val byPixels = GifReader.decodeAll(raw, DecodeLimits(maxTotalPixels = 10))
        assertEquals(DecodeProblem.TOO_LARGE, byPixels.problem)
        assertEquals(2, byPixels.sequence!!.frames.size)
        // Exactly at the limit is fine.
        assertTrue(GifReader.decodeAll(raw, DecodeLimits(maxFrames = 5)).complete)
    }

    @Test
    fun `reading frame by frame gives the same frames as reading them all`() {
        val seq = AnimTestKit.photoSequence(20, 16, 5)
        val bytes = GifEncoder.encode(seq)
        val all = GifReader.decodeAll(bytes).sequence!!.frames
        val reader = GifReader(bytes)
        var i = 0
        while (true) {
            val f = reader.next() ?: break
            assertArrayEquals(all[i].pixels, f.pixels)
            i++
        }
        assertEquals(all.size, i)
        assertEquals(DecodeProblem.NONE, reader.problem)
        assertNull(reader.next()) // and stays finished
    }
}
