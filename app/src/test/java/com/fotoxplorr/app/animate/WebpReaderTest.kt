package com.fotoxplorr.app.animate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [WebpReader]'s container handling against files built byte by byte by [HandWebp], with a stand-in
 * for the platform decoder. Whether the real decoder accepts the stand-alone files the reader builds
 * is [WebpDecodeAndroidTest].
 */
class WebpReaderTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val clear = 0

    private fun read(bytes: ByteArray, limits: DecodeLimits = DecodeLimits(), decode: (ByteArray) -> RawFrame? = HandWebp::fakeDecoder): Triple<List<AnimFrame>, WebpReader, Int> {
        val r = WebpReader(bytes, limits, decode)
        val frames = ArrayList<AnimFrame>()
        while (true) frames.add(r.next() ?: break)
        return Triple(frames, r, frames.size)
    }

    private fun threeFrames() = HandWebp(8, 6, loop = 3)
        .solid(0, 0, 8, 6, red, durationMs = 120)
        .solid(2, 2, 4, 2, green, durationMs = 80)
        .solid(0, 0, 2, 2, blue, durationMs = 40)
        .build()

    @Test
    fun `the canvas, the loop count, the delays and the frames are read`() {
        val (frames, r) = read(threeFrames()).let { it.first to it.second }
        assertEquals(3, frames.size)
        assertEquals(8, r.width)
        assertEquals(6, r.height)
        assertEquals(3, r.plays)
        assertEquals(DecodeProblem.NONE, r.problem)
        assertEquals(listOf(120, 80, 40), frames.map { it.delayMs })
        assertTrue("the first frame is the whole canvas", frames[0].pixels.all { it == red })
    }

    @Test
    fun `a loop count of zero is for ever`() {
        val bytes = HandWebp(4, 4, loop = 0).solid(0, 0, 4, 4, red).solid(0, 0, 4, 4, green).build()
        assertEquals(0, read(bytes).second.plays)
    }

    @Test
    fun `a later frame is drawn over the canvas with alpha-over when it blends`() {
        val half = 0x80FF0000.toInt()
        val bytes = HandWebp(4, 2).solid(0, 0, 4, 2, blue).solid(0, 0, 2, 2, half, blend = true).build()
        val f = read(bytes).first[1].pixels
        val over = f[0]
        assertEquals(255, over ushr 24)
        val r = (over shr 16) and 255
        val b = over and 255
        assertTrue("half red over blue: r $r b $b", r in 126..130 && b in 125..129)
        assertEquals("beside the frame the canvas is as it was", blue, f[3])
    }

    @Test
    fun `a frame that does not blend replaces what is under it, clear pixels included`() {
        val bytes = HandWebp(4, 2).solid(0, 0, 4, 2, blue).solid(0, 0, 2, 2, 0x00000000, blend = false).build()
        val f = read(bytes).first[1].pixels
        assertEquals(clear, f[0])
        assertEquals(blue, f[2])
    }

    @Test
    fun `dispose to background clears the frame's rectangle before the next one is drawn`() {
        val bytes = HandWebp(6, 2)
            .solid(0, 0, 6, 2, blue)
            .solid(2, 0, 2, 2, red, dispose = true)
            .solid(0, 0, 2, 2, green)
            .build()
        val frames = read(bytes).first
        assertEquals("frame 2 shows the red box", red, frames[1].pixels[2])
        val third = frames[2].pixels
        assertEquals("the red box was cleared to transparent, not to the blue under it", clear, third[2])
        assertEquals(green, third[0])
        assertEquals("outside the disposed box the canvas stays", blue, third[5])
    }

    @Test
    fun `without dispose the previous frame stays under the next`() {
        val bytes = HandWebp(6, 2).solid(0, 0, 6, 2, blue).solid(2, 0, 2, 2, red).solid(0, 0, 2, 2, green).build()
        assertEquals(red, read(bytes).first[2].pixels[2])
    }

    @Test
    fun `a duration of 10 ms or less is shown as 100 like a browser does for a GIF`() {
        val bytes = HandWebp(2, 2).solid(0, 0, 2, 2, red, durationMs = 0).solid(0, 0, 2, 2, green, durationMs = 10).solid(0, 0, 2, 2, blue, durationMs = 11).build()
        assertEquals(listOf(100, 100, 11), read(bytes).first.map { it.delayMs })
    }

    @Test
    fun `each frame is handed to the decoder as a stand-alone WebP made of its own chunks`() {
        val seen = ArrayList<ByteArray>()
        val bytes = HandWebp(10, 8)
            .solid(0, 0, 10, 8, red, lossy = true, alphaChunk = true)
            .solid(2, 4, 6, 2, green)
            .build()
        read(bytes) { seen.add(it); HandWebp.fakeDecoder(it) }
        assertEquals(2, seen.size)
        val first = seen[0]
        assertEquals("RIFF", String(first, 0, 4))
        assertEquals("riff size is the length minus the 8 byte header", first.size - 8, (0..3).sumOf { (first[4 + it].toInt() and 255) shl (8 * it) })
        assertEquals("WEBP", String(first, 8, 4))
        assertEquals("VP8X", String(first, 12, 4))
        assertEquals("alpha flagged because the frame has an ALPH chunk", 0x10, first[20].toInt() and 0x10)
        assertEquals("not flagged as an animation", 0, first[20].toInt() and 0x02)
        assertEquals(9, (0..2).sumOf { (first[24 + it].toInt() and 255) shl (8 * it) })
        assertEquals(7, (0..2).sumOf { (first[27 + it].toInt() and 255) shl (8 * it) })
        assertEquals("ALPH", String(first, 30, 4))
        val second = seen[1]
        assertEquals("a frame of 6 x 2 is sized so", 5, (0..2).sumOf { (second[24 + it].toInt() and 255) shl (8 * it) })
        assertEquals(1, (0..2).sumOf { (second[27 + it].toInt() and 255) shl (8 * it) })
        assertEquals("a lossless frame may carry alpha", 0x10, second[20].toInt() and 0x10)
        assertEquals("VP8L", String(second, 30, 4))
    }

    @Test
    fun `a lossy frame without an alpha chunk is not flagged as having alpha`() {
        val seen = ArrayList<ByteArray>()
        val bytes = HandWebp(4, 4).solid(0, 0, 4, 4, red, lossy = true).solid(0, 0, 4, 4, green, lossy = true).build()
        read(bytes) { seen.add(it); HandWebp.fakeDecoder(it) }
        assertEquals(0, seen[0][20].toInt() and 0x10)
    }

    // ---- what is not an animated WebP ---------------------------------------------------------------------

    @Test
    fun `a still WebP is not an animation`() {
        val still = HandWebp(4, 4, animated = false).build()
        val (frames, r) = read(still).let { it.first to it.second }
        assertEquals(0, frames.size)
        assertEquals(DecodeProblem.NOT_THIS_FORMAT, r.problem)
    }

    @Test
    fun `other files and empty input are not WebP`() {
        for (bytes in listOf(ByteArray(0), ByteArray(11), "GIF89a......".toByteArray(), "RIFF....WAVE".toByteArray(), ByteArray(100) { 7 })) {
            val (frames, r) = read(bytes).let { it.first to it.second }
            assertEquals(0, frames.size)
            assertEquals(DecodeProblem.NOT_THIS_FORMAT, r.problem)
        }
    }

    @Test
    fun `an animated header with no frames is damaged`() {
        val r = read(HandWebp(4, 4).build()).second
        assertEquals(DecodeProblem.CORRUPT, r.problem)
    }

    // ---- damage -----------------------------------------------------------------------------------------

    @Test
    fun `a frame outside the canvas stops the reading with the frames before it`() {
        val bytes = HandWebp(4, 4).solid(0, 0, 4, 4, red).solid(2, 2, 4, 4, green).build()
        val (frames, r) = read(bytes).let { it.first to it.second }
        assertEquals("the scan stops at the bad frame; the good one is returned", 1, frames.size)
        assertEquals(DecodeProblem.CORRUPT, r.problem)
    }

    @Test
    fun `a frame with no image chunk is damaged`() {
        val bytes = HandWebp(4, 4).solid(0, 0, 4, 4, red).frame(HandWebp.Frame(0, 0, 4, 4, 100, chunks = HandWebp.chunk("EXIF", byteArrayOf(1, 2)))).build()
        val (frames, r) = read(bytes).let { it.first to it.second }
        assertEquals(1, frames.size)
        assertEquals(DecodeProblem.CORRUPT, r.problem)
    }

    @Test
    fun `a decoder that returns nothing, or the wrong size, ends the reading`() {
        val bytes = threeFrames()
        val (nothing, r1) = read(bytes) { null }.let { it.first to it.second }
        assertEquals(0, nothing.size)
        assertEquals(DecodeProblem.CORRUPT, r1.problem)
        val (wrong, r2) = read(bytes) { RawFrame(3, 3, IntArray(9)) }.let { it.first to it.second }
        assertEquals(0, wrong.size)
        assertEquals(DecodeProblem.CORRUPT, r2.problem)
    }

    @Test
    fun `a decoder that throws is damage, not a crash`() {
        val (frames, r) = read(threeFrames()) { throw IllegalStateException("native decoder said no") }.let { it.first to it.second }
        assertEquals(0, frames.size)
        assertEquals(DecodeProblem.CORRUPT, r.problem)
    }

    @Test
    fun `a canvas past the limit is too large`() {
        val bytes = HandWebp(5000, 5000).solid(0, 0, 10, 10, red).solid(0, 0, 10, 10, green).build()
        val (frames, r) = read(bytes, DecodeLimits(maxCanvasPixels = 1_000_000)).let { it.first to it.second }
        assertEquals(0, frames.size)
        assertEquals(DecodeProblem.TOO_LARGE, r.problem)
    }

    @Test
    fun `cutting the file at every length never throws, and what comes back is a prefix of the frames`() {
        val whole = threeFrames()
        val full = read(whole).first.map { it.pixels.toList() }
        for (cut in 0 until whole.size) {
            val (frames, r) = read(whole.copyOf(cut)).let { it.first to it.second }
            assertTrue("cut at $cut gave ${frames.size} frames", frames.size <= 3)
            for ((i, f) in frames.withIndex()) assertEquals("frame $i at cut $cut", full[i], f.pixels.toList())
            assertTrue("cut at $cut must say something", r.problem != DecodeProblem.NONE)
        }
    }

    @Test
    fun `a cut inside the last frame keeps the frames before it and says the file ends early`() {
        val whole = threeFrames()
        val (frames, r) = read(whole.copyOf(whole.size - 3)).let { it.first to it.second }
        assertEquals(2, frames.size)
        assertEquals(DecodeProblem.TRUNCATED, r.problem)
    }

    @Test
    fun `random damage never throws`() {
        val whole = threeFrames()
        val rnd = Random(42)
        repeat(600) {
            val b = whole.copyOf()
            repeat(1 + rnd.nextInt(4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val (frames, _) = read(b).let { it.first to it.second }
            assertTrue(frames.size <= 3 + 64)
        }
    }

    @Test
    fun `random bytes behind a good header never throw`() {
        val rnd = Random(7)
        repeat(300) {
            val tail = ByteArray(rnd.nextInt(200)).also { rnd.nextBytes(it) }
            val b = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 3, 0, 0) + tail
            read(b)
        }
    }

    @Test
    fun `a riff size smaller than the file ignores the trailing bytes`() {
        val whole = threeFrames()
        val padded = whole + ByteArray(20) { 9 }
        val (frames, r) = read(padded).let { it.first to it.second }
        assertEquals(3, frames.size)
        assertEquals(DecodeProblem.NONE, r.problem)
    }

    @Test
    fun `unknown chunks and metadata between the header and the frames are skipped`() {
        val w = HandWebp(4, 4)
        w.extra.write(HandWebp.chunk("ICCP", ByteArray(7)))
        w.extra.write(HandWebp.chunk("XMP ", ByteArray(5)))
        w.solid(0, 0, 4, 4, red).solid(0, 0, 4, 4, green)
        val (frames, r) = read(w.build()).let { it.first to it.second }
        assertEquals(2, frames.size)
        assertEquals(DecodeProblem.NONE, r.problem)
        assertNotNull(frames[0])
    }

    @Test
    fun `a frame handed out is the caller's, and changing it does not change the next one`() {
        val r = WebpReader(threeFrames(), DecodeLimits(), HandWebp::fakeDecoder)
        val first = r.next()!!
        first.pixels.fill(0)
        val second = r.next()!!
        assertEquals("the green box over the red canvas", green, second.pixels[2 * 8 + 2])
        assertEquals("and red outside it, though the caller wiped the first frame", red, second.pixels[0])
    }
}
