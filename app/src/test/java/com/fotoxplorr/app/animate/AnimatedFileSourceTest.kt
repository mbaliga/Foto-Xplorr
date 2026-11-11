package com.fotoxplorr.app.animate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [AnimatedFileSource] over GIF, APNG and WebP files made by this package's own writers (and the hand
 * writers), behind a fake library. Real-world files from other programs are on the device checklist.
 */
class AnimatedFileSourceTest {
    private val asset = AnimateKit.asset(5, "party.gif", mime = "image/gif")

    private fun host(bytes: ByteArray?): FakeAnimateHost = FakeAnimateHost().apply {
        if (bytes != null) files[5L] = bytes
        webpDecoder = HandWebp::fakeDecoder
    }

    private fun source(bytes: ByteArray?, h: FakeAnimateHost = host(bytes)) = AnimatedFileSource(h, asset)

    private fun sequence(n: Int = 5, w: Int = 40, h: Int = 30, delay: Int = 80, plays: Int = 0) = AnimTestKit.photoSequence(w, h, n, delay, noise = 0, plays = plays)

    private fun gif(seq: AnimSequence = sequence()) = GifEncoder.encode(seq, GifOptions(ditherStrength = 0f))

    private fun open(s: FrameSource): SourceInfo = runBlocking { s.open() }

    private fun fail(s: FrameSource): String {
        try {
            open(s)
        } catch (e: AnimSourceException) {
            return e.message.orEmpty()
        }
        fail("expected a refusal")
        return ""
    }

    // ---- GIF --------------------------------------------------------------------------------------------

    @Test
    fun `a GIF is described by its size, frame delays, plays and name`() {
        val seq = sequence(plays = 3)
        val info = open(source(gif(seq)))
        assertEquals(40, info.width)
        assertEquals(30, info.height)
        assertEquals(5, info.delaysMs.size)
        assertTrue(info.delaysMs.all { it == 80 })
        assertEquals(3, info.plays)
        assertEquals("party.gif", info.name)
        assertEquals(asset.dateTakenMillis, info.dateTakenMillis)
        assertEquals(Transparency.NONE, info.transparency)
        assertEquals(DecodeProblem.NONE, info.problem)
    }

    @Test
    fun `frames come back as the decoder composites them, at the size asked`() {
        val bytes = gif()
        val reference = GifReader.decodeAll(bytes).sequence!!
        val s = source(bytes)
        open(s)
        for (i in 0 until 5) {
            val px = runBlocking { s.frame(i, 40, 30) }
            assertArrayEquals("frame $i", reference.frames[i].pixels, px)
        }
        val small = runBlocking { s.frame(2, 20, 15) }
        assertEquals(20 * 15, small.size)
        assertArrayEquals(FrameScaler.scale(reference.frames[2].pixels, 40, 30, 20, 15), small)
    }

    @Test
    fun `frames may be asked for out of order, repeatedly, and the same each time`() {
        val bytes = gif()
        val reference = GifReader.decodeAll(bytes).sequence!!
        val s = source(bytes)
        open(s)
        for (i in listOf(3, 3, 1, 4, 0, 2, 2, 4)) {
            assertArrayEquals("frame $i", reference.frames[i].pixels, runBlocking { s.frame(i, 40, 30) })
        }
    }

    @Test
    fun `the array handed out is the caller's to change`() {
        val s = source(gif())
        open(s)
        val a = runBlocking { s.frame(1, 40, 30) }
        a.fill(0)
        val again = runBlocking { s.frame(1, 40, 30) }
        assertTrue("a second ask is not the wiped array", again.any { it != 0 })
    }

    @Test
    fun `a delay of 10 ms or less is read as 100 ms`() {
        // The encoder floors a GIF's delay at 20 ms, so build the file by hand with a stored delay of 1 cs.
        val bytes = HandGif(2, 2, intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
            .gce(0, 1).image(0, 0, 2, 2, IntArray(4))
            .gce(0, 1).image(0, 0, 2, 2, IntArray(4) { 1 })
            .trailer().bytes()
        val info = open(source(bytes))
        assertEquals(listOf(100, 100), info.delaysMs.toList())
    }

    @Test
    fun `see-through pixels are reported, clear ones as clear and soft ones as partial`() {
        val sticker = AnimTestKit.stickerSequence(32, 24, 4)
        assertEquals(Transparency.CLEAR, open(source(GifEncoder.encode(sticker))).transparency)
        // Frames that differ, so the encoder does not merge them into one.
        val soft = AnimSequence(8, 8, List(3) { k -> AnimFrame(IntArray(64) { (0x80 shl 24) or ((k * 40) shl 8) }, 100) }, 0)
        assertEquals(Transparency.PARTIAL, open(source(ApngEncoder.encode(soft))).transparency)
    }

    // ---- APNG -------------------------------------------------------------------------------------------

    @Test
    fun `an APNG is read the same way`() {
        val seq = sequence(n = 4, delay = 120, plays = 2)
        val bytes = ApngEncoder.encode(seq)
        val s = source(bytes)
        val info = open(s)
        assertEquals(4, info.delaysMs.size)
        assertEquals(2, info.plays)
        assertTrue(info.delaysMs.all { it == 120 })
        for (i in 0 until 4) assertArrayEquals(seq.frames[i].pixels, runBlocking { s.frame(i, 40, 30) })
    }

    @Test
    fun `a plain PNG is not an animation`() {
        val still = ApngEncoder.encode(AnimSequence(10, 10, listOf(AnimFrame(IntArray(100) { 0xFF123456.toInt() }, 100)), 0))
        assertEquals(AnimateCopy.NOT_ANIMATED, fail(source(still)))
    }

    // ---- WebP -------------------------------------------------------------------------------------------

    @Test
    fun `an animated WebP is read through the host's image decoder`() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val bytes = HandWebp(8, 6, loop = 4).solid(0, 0, 8, 6, red, 90).solid(0, 0, 4, 6, green, 60).build()
        val h = host(bytes)
        val s = source(bytes, h)
        val info = open(s)
        assertEquals(8, info.width)
        assertEquals(listOf(90, 60), info.delaysMs.toList())
        assertEquals(4, info.plays)
        val second = runBlocking { s.frame(1, 8, 6) }
        assertEquals(green, second[0])
        assertEquals(red, second[7])
        assertTrue("the host's decoder did the pixels", h.webpDecodes.size >= 2)
    }

    // ---- refusals ---------------------------------------------------------------------------------------

    @Test
    fun `a protected file is refused without being read`() {
        val h = host(gif())
        h.protectedIds += 5L
        try {
            open(source(null, h))
            fail("expected a refusal")
        } catch (e: AnimSourceProtectedException) {
            assertEquals(AnimateCopy.PROTECTED, e.message)
        }
    }

    @Test
    fun `a file that is gone is one sentence`() {
        assertEquals(AnimateCopy.FILE_UNREADABLE, fail(source(null)))
    }

    @Test
    fun `a file over the size limit says so`() {
        val h = host(gif())
        h.fileLimitBytes = 10
        assertEquals(AnimateCopy.FILE_TOO_BIG, fail(source(null, h)))
    }

    @Test
    fun `a JPEG, text and nothing are not animated pictures`() {
        for (bytes in listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 74, 70, 73, 70), "hello, world".toByteArray(), ByteArray(0))) {
            assertEquals(AnimateCopy.NOT_ANIMATED, fail(source(bytes)))
        }
    }

    @Test
    fun `a single frame GIF is not an animation`() {
        val one = GifEncoder.encode(sequence(n = 1))
        assertEquals(AnimateCopy.NOT_ANIMATED, fail(source(one)))
    }

    @Test
    fun `a GIF cut after its second frame is used as far as it goes and says so`() {
        val seq = sequence(n = 6)
        val info = open(source(AnimateKit.cutGif(gif(seq))))
        assertTrue("only some frames: ${info.delaysMs.size}", info.delaysMs.size in 2..5)
        assertEquals(DecodeProblem.TRUNCATED, info.problem)
    }

    @Test
    fun `a GIF damaged in its first frame says the file is damaged`() {
        val whole = gif()
        val cut = whole.copyOf(40)
        val message = fail(source(cut))
        assertTrue(message, message == AnimateCopy.TRUNCATED || message == AnimateCopy.CORRUPT || message == AnimateCopy.FILE_UNREADABLE)
    }

    @Test
    fun `a file with more frames than the limit is cut there and flagged`() {
        val n = AnimatedFileSource.MAX_FRAMES + 5
        // A different colour every frame, so the encoder does not merge neighbours.
        val frames = List(n) { i -> AnimFrame(IntArray(4) { 0xFF000000.toInt() or ((i * 37) and 255) or (((i * 11) and 255) shl 8) or (((i / 7) and 255) shl 16) }, 20) }
        val bytes = GifEncoder.encode(AnimSequence(2, 2, frames, 0), GifOptions(maxColors = 255))
        val info = open(source(bytes))
        assertEquals(AnimatedFileSource.MAX_FRAMES, info.delaysMs.size)
        assertEquals(DecodeProblem.TOO_LARGE, info.problem)
    }

    @Test
    fun `asking for a frame that is not there is a programming error, not a sentence`() {
        val s = source(gif())
        open(s)
        try {
            runBlocking { s.frame(99, 40, 30) }
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            // fine
        }
    }

    @Test
    fun `the sniffer knows the three formats by their first bytes`() {
        assertEquals(AnimatedFormat.GIF, AnimatedFormat.sniff("GIF89a....".toByteArray()))
        assertEquals(AnimatedFormat.GIF, AnimatedFormat.sniff("GIF87a....".toByteArray()))
        assertEquals(AnimatedFormat.APNG, AnimatedFormat.sniff(PngIo.SIGNATURE + ByteArray(4)))
        assertEquals(AnimatedFormat.WEBP, AnimatedFormat.sniff("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)))
        assertNull(AnimatedFormat.sniff("GIF90a".toByteArray()))
        assertNull(AnimatedFormat.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertNull(AnimatedFormat.sniff(ByteArray(3)))
        assertFalse(AnimatedFormat.sniff(ByteArray(0)) != null)
    }
}
