package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * The GIF writer, checked three ways: by this package's own decoder, by the JDK's GIF reader (an
 * independent implementation, reached through [Jdk]), and by hand-counting bytes. The scenes are
 * synthetic ([AnimTestKit]); nothing here says how a real photograph compresses.
 */
class GifEncoderTest {

    private fun decode(bytes: ByteArray): AnimSequence {
        val r = GifReader.decodeAll(bytes, browserDelays = false)
        assertTrue("decode problem: ${r.problem} ${r.detail}", r.complete)
        return r.sequence!!
    }

    /** What the decoded animation shows at each of [times] (ms from the start). Merged frames make index-by-index comparison wrong. */
    private fun shownAt(seq: AnimSequence, times: List<Long>): List<IntArray> {
        val starts = ArrayList<Long>()
        var t = 0L
        for (f in seq.frames) {
            starts.add(t)
            t += f.delayMs
        }
        return times.map { at -> seq.frames[starts.indexOfLast { it <= at }.coerceAtLeast(0)].pixels }
    }

    // ---- structure, as an independent reader sees it ----

    @Test
    fun `the JDK reads our file with the right size, frame count, delays and loop`() {
        val seq = AnimTestKit.photoSequence(48, 36, 6, delayMs = 120, plays = 0)
        val gif = Jdk.readGif(GifEncoder.encode(seq))
        assertEquals(48, gif.width)
        assertEquals(36, gif.height)
        assertEquals(6, gif.frames.size)
        assertTrue(gif.frames.all { it.delayCs == 12 })
        assertEquals(0, gif.loopCount)
    }

    @Test
    fun `our decoder and the JDK composite our file to exactly the same pictures`() {
        val seq = AnimTestKit.photoSequence(64, 48, 8)
        val bytes = GifEncoder.encode(seq)
        val ours = decode(bytes).frames.map { it.pixels }
        val theirs = Jdk.composite(Jdk.readGif(bytes))
        assertEquals(ours.size, theirs.size)
        for (i in ours.indices) assertArrayEquals("frame $i", theirs[i], ours[i])
    }

    @Test
    fun `an animated GIF written by the JDK decodes the same in our reader as in the JDK`() {
        val frames = List(4) { AnimTestKit.photoFrame(40, 30, 0, 8 + it * 7, 15, noise = 4) }
        val bytes = Jdk.writeGif(frames, 40, 30, delayCs = 9, loop = 2)
        val theirs = Jdk.composite(Jdk.readGif(bytes))
        val r = GifReader.decodeAll(bytes)
        assertTrue(r.detail, r.complete)
        val seq = r.sequence!!
        assertEquals(4, seq.frames.size)
        assertEquals(3, seq.plays) // loop count 2 means two repeats after the first play
        for (i in 0 until 4) {
            assertArrayEquals("frame $i", theirs[i], seq.frames[i].pixels)
            assertEquals(90, seq.frames[i].delayMs)
        }
    }

    @Test
    fun `a JDK written GIF with unquantised colour is within quantisation error of the source`() {
        val frames = List(2) { AnimTestKit.photoFrame(32, 24, 0, 10 + it * 9, 12, noise = 3) }
        val bytes = Jdk.writeGif(frames, 32, 24, 5, 0)
        val seq = GifReader.decodeAll(bytes).sequence!!
        for (i in 0 until 2) assertTrue(AnimTestKit.psnr(frames[i], seq.frames[i].pixels) > 22.0)
    }

    // ---- fidelity ----

    @Test
    fun `an image of at most 255 colours is reproduced exactly, with dithering on, in every palette mode`() {
        val seq = AnimTestKit.flatSequence(60, 40, 7)
        for (mode in PaletteMode.values()) {
            val back = decode(GifEncoder.encode(seq, GifOptions(paletteMode = mode, ditherStrength = 1f)))
            for (i in seq.frames.indices) assertArrayEquals("$mode frame $i", seq.frames[i].pixels, back.frames[i].pixels)
        }
    }

    @Test
    fun `a photo-like sequence survives with a stated PSNR floor, dithered and not`() {
        val seq = AnimTestKit.photoSequence(96, 72, 6)
        for ((dither, floor) in listOf(0f to 36.0, 1f to 30.0)) {
            val back = decode(GifEncoder.encode(seq, GifOptions(ditherStrength = dither)))
            for (i in seq.frames.indices) {
                val p = AnimTestKit.psnr(seq.frames[i].pixels, back.frames[i].pixels)
                assertTrue("dither=$dither frame $i psnr=$p", p >= floor)
            }
        }
    }

    @Test
    fun `fewer colours is worse, and never more distinct colours than asked for`() {
        val seq = AnimTestKit.photoSequence(64, 48, 3)
        var last = Double.MAX_VALUE
        for (n in listOf(255, 64, 16, 4)) {
            val back = decode(GifEncoder.encode(seq, GifOptions(maxColors = n, ditherStrength = 0f, paletteMode = PaletteMode.GLOBAL)))
            val distinct = back.frames.flatMap { it.pixels.toList() }.toSet().size
            assertTrue("n=$n distinct=$distinct", distinct <= n)
            val p = AnimTestKit.psnr(seq.frames[2].pixels, back.frames[2].pixels)
            assertTrue("n=$n psnr=$p should not beat $last", p <= last + 0.01)
            last = p
        }
    }

    @Test
    fun `dithering keeps the local average colour truer than flat quantising`() {
        // A smooth ramp with 4 colours: nearest-colour makes bands, dither keeps each 8x8 block's mean near the truth.
        val w = 128
        val h = 32
        val ramp = IntArray(w * h) { val v = (it % w) * 255 / (w - 1); AnimTestKit.argb(255, v, v, v) }
        val seq = AnimSequence(w, h, listOf(AnimFrame(ramp, 100)), 1)
        fun blockError(back: IntArray): Double {
            var sum = 0.0
            var n = 0
            for (by in 0 until h step 8) for (bx in 0 until w step 8) {
                var a = 0.0
                var b = 0.0
                for (y in by until by + 8) for (x in bx until bx + 8) {
                    a += ramp[y * w + x] and 255
                    b += back[y * w + x] and 255
                }
                sum += Math.abs(a - b) / 64
                n++
            }
            return sum / n
        }
        val flat = blockError(decode(GifEncoder.encode(seq, GifOptions(maxColors = 4, ditherStrength = 0f))).frames[0].pixels)
        val dith = blockError(decode(GifEncoder.encode(seq, GifOptions(maxColors = 4, ditherStrength = 1f))).frames[0].pixels)
        assertTrue("flat=$flat dither=$dith", dith < flat * 0.6)
    }

    // ---- size ----

    @Test
    fun `cropping to what changed makes the file much smaller and changes nothing visible`() {
        val seq = AnimTestKit.photoSequence(96, 72, 10)
        val delta = GifEncoder.encode(seq, GifOptions(deltaFrames = true, ditherStrength = 0f))
        val whole = GifEncoder.encode(seq, GifOptions(deltaFrames = false, ditherStrength = 0f))
        assertTrue("delta=${delta.size} whole=${whole.size}", delta.size < whole.size * 0.6)
        // The palettes differ (one is built from what moves, one from everything), so compare to the truth, not to each other.
        val a = decode(delta)
        val b = decode(whole)
        for (i in seq.frames.indices) {
            val pa = AnimTestKit.psnr(seq.frames[i].pixels, a.frames[i].pixels)
            val pb = AnimTestKit.psnr(seq.frames[i].pixels, b.frames[i].pixels)
            assertTrue("frame $i delta=$pa whole=$pb", pa >= pb - 1.0)
        }
    }

    @Test
    fun `frames drawn in a box are written as a box and not the whole canvas`() {
        val seq = AnimTestKit.flatSequence(80, 50, 5)
        val gif = Jdk.readGif(GifEncoder.encode(seq))
        assertEquals(80, gif.frames[0].image.width)
        assertEquals(50, gif.frames[0].image.height)
        for (f in gif.frames.drop(1)) assertTrue("box ${f.image.width} x ${f.image.height}", f.image.width * f.image.height < 80 * 50 / 2)
    }

    @Test
    fun `a frame identical to the one before is not written, its time is added instead`() {
        val a = AnimTestKit.photoFrame(30, 20, 0, 10, 10)
        val b = AnimTestKit.photoFrame(30, 20, 0, 14, 10)
        val seq = AnimSequence(30, 20, listOf(AnimFrame(a, 100), AnimFrame(a, 100), AnimFrame(a, 50), AnimFrame(b, 100), AnimFrame(b, 100)), 0)
        val gif = Jdk.readGif(GifEncoder.encode(seq))
        assertEquals(2, gif.frames.size)
        assertEquals(25, gif.frames[0].delayCs)
        assertEquals(20, gif.frames[1].delayCs)
        // And the decoded animation lasts as long as the original.
        assertEquals(450L, decode(GifEncoder.encode(seq)).durationMs)
    }

    @Test
    fun `a tolerance for noise shrinks a noisy clip, and stays within the tolerance of the truth`() {
        // A still scene with fresh sensor noise on every frame (the encoder cannot tell it from change at tolerance 0).
        val w = 80
        val h = 60
        val frames = List(6) { AnimFrame(AnimTestKit.photoFrame(w, h, it, 40, 30, noise = 3, seed = 5), 100) }
        val seq = AnimSequence(w, h, frames, 0)
        val exact = GifEncoder.encode(seq, GifOptions(deltaTolerance = 0, ditherStrength = 0f))
        val loose = GifEncoder.encode(seq, GifOptions(deltaTolerance = 8, ditherStrength = 0f))
        assertTrue("exact=${exact.size} loose=${loose.size}", loose.size < exact.size * 0.5)
        val back = shownAt(decode(loose), frames.indices.map { it * 100L })
        val worstDrift = frames.indices.maxOf { AnimTestKit.maxChannelError(frames[it].pixels, back[it]) }
        // Palette error (a few levels) plus the tolerance (8) is the most a pixel can be off.
        assertTrue("worst=$worstDrift", worstDrift <= 8 + 24)
    }

    @Test
    fun `a slow fade is followed, because pixels are compared to what was last painted`() {
        val w = 16
        val h = 16
        val frames = List(60) { i -> AnimFrame(IntArray(w * h) { AnimTestKit.argb(255, 100 + i, 100 + i, 100 + i) }, 50) }
        val seq = AnimSequence(w, h, frames, 1)
        val back = decode(GifEncoder.encode(seq, GifOptions(deltaTolerance = 5, ditherStrength = 0f)))
        // One step per frame is under the tolerance, so a comparison to the previous frame would never repaint.
        val last = back.frames.last().pixels[0] and 255
        assertTrue("last=$last", last >= 159 - 5 - 1)
    }

    // ---- palettes ----

    @Test
    fun `a stable palette stays global and a palette that changes completely gets its own`() {
        val w = 64
        val h = 48
        // Frame 0..3 reds, 4..7 blues, each a gradient with many colours so one palette cannot hold both.
        fun ramp(base: Int, shift: Int) = IntArray(w * h) {
            val x = it % w
            val y = it / w
            val v = (x * 3 + y) % 200
            if (base == 0) AnimTestKit.argb(255, 50 + v, 20 + v / 8, 10 + (v + shift) % 30) else AnimTestKit.argb(255, 10 + (v + shift) % 30, 20 + v / 8, 50 + v)
        }
        val frames = List(8) { AnimFrame(ramp(if (it < 4) 0 else 1, it), 100) }
        val seq = AnimSequence(w, h, frames, 0)
        val opt = { m: PaletteMode -> GifOptions(paletteMode = m, ditherStrength = 0f, maxColors = 48) }
        fun worst(m: PaletteMode): Double {
            val back = decode(GifEncoder.encode(seq, opt(m)))
            return frames.indices.maxOf { AnimTestKit.mse(frames[it].pixels, back.frames[it].pixels) }
        }
        val g = worst(PaletteMode.GLOBAL)
        val a = worst(PaletteMode.AUTO)
        val l = worst(PaletteMode.LOCAL)
        assertTrue("auto $a should beat global $g", a < g * 0.85)
        assertTrue("local $l is the best colour there is, auto $a", l <= a + 1e-9)
        // A steady scene: auto changes nothing from global.
        val steady = AnimTestKit.photoSequence(64, 48, 5)
        assertArrayEquals(
            GifEncoder.encode(steady, GifOptions(paletteMode = PaletteMode.GLOBAL)),
            GifEncoder.encode(steady, GifOptions(paletteMode = PaletteMode.AUTO)),
        )
    }

    // ---- transparency ----

    @Test
    fun `clear pixels stay clear and opaque ones keep their colour`() {
        val seq = AnimTestKit.stickerSequence(64, 48, 6)
        val bytes = GifEncoder.encode(seq, GifOptions(ditherStrength = 0f))
        val back = decode(bytes)
        for (i in seq.frames.indices) {
            val a = seq.frames[i].pixels
            val b = back.frames[i].pixels
            for (p in a.indices) assertEquals("frame $i px $p opaque", (a[p] ushr 24) != 0, (b[p] ushr 24) != 0)
            assertTrue("frame $i psnr", AnimTestKit.psnr(a, b) > 30.0)
        }
        // The JDK sees the same: transparent frames that are restored to background.
        val io = Jdk.readGif(bytes)
        assertTrue(io.frames.all { it.hasTransparency || it.image.argb.all { px -> (px ushr 24) == 255 } })
        assertTrue(io.frames.all { it.disposal == "restoreToBackgroundColor" })
        val comp = Jdk.composite(io)
        for (i in comp.indices) assertArrayEquals(back.frames[i].pixels, comp[i])
    }

    @Test
    fun `a frame with nothing visible in it is still a frame`() {
        val w = 8
        val h = 8
        val shown = IntArray(w * h) { AnimTestKit.argb(255, 200, 30, 30) }
        val empty = IntArray(w * h)
        val seq = AnimSequence(w, h, listOf(AnimFrame(shown, 100), AnimFrame(empty, 200), AnimFrame(shown, 100)), 0)
        val back = decode(GifEncoder.encode(seq))
        assertEquals(3, back.frames.size)
        assertTrue(back.frames[1].pixels.all { it == 0 })
        assertArrayEquals(shown, back.frames[2].pixels)
        assertEquals(400L, back.durationMs)
    }

    @Test
    fun `a pixel only half see-through is counted by the alpha cut-off`() {
        val w = 4
        val h = 1
        val px = intArrayOf(AnimTestKit.argb(127, 255, 0, 0), AnimTestKit.argb(128, 255, 0, 0), AnimTestKit.argb(255, 0, 255, 0), AnimTestKit.argb(0, 0, 0, 255))
        val back = decode(GifEncoder.encode(AnimSequence(w, h, listOf(AnimFrame(px, 100)), 1))).frames[0].pixels
        assertEquals(0, back[0])
        assertEquals(0xFFFF0000.toInt(), back[1])
        assertEquals(0xFF00FF00.toInt(), back[2])
        assertEquals(0, back[3])
    }

    // ---- timing ----

    @Test
    fun `delays are hundredths, carried so the total is right, and never under two`() {
        val w = 4
        val h = 4
        val frames = List(15) { AnimFrame(IntArray(w * h) { px -> AnimTestKit.argb(255, it * 10, px, 0) }, if (it % 3 == 2) 66 else 67) }
        val bytes = GifEncoder.encode(AnimSequence(w, h, frames, 0))
        val io = Jdk.readGif(bytes)
        val total = io.frames.sumOf { it.delayCs } * 10
        assertEquals(1000.0, total.toDouble(), 10.0) // within a hundredth of the true 1000 ms
        // Very short delays are lifted to 2.
        val quick = List(4) { AnimFrame(IntArray(w * h) { px -> AnimTestKit.argb(255, it * 40, px, 0) }, 5) }
        val q = Jdk.readGif(GifEncoder.encode(AnimSequence(w, h, quick, 0)))
        assertTrue(q.frames.all { it.delayCs == 2 })
    }

    @Test
    fun `plays map to the loop extension`() {
        fun loop(plays: Int): Int? = Jdk.readGif(GifEncoder.encode(AnimTestKit.flatSequence(10, 8, 2, plays = plays))).loopCount
        assertNull(loop(1))
        assertEquals(0, loop(0))
        assertEquals(1, loop(2))
        assertEquals(4, loop(5))
        // And they come back.
        assertEquals(5, decode(GifEncoder.encode(AnimTestKit.flatSequence(10, 8, 2, plays = 5))).plays)
        assertEquals(1, decode(GifEncoder.encode(AnimTestKit.flatSequence(10, 8, 2, plays = 1))).plays)
    }

    // ---- shapes and argument handling ----

    @Test
    fun `unusual canvas shapes round trip`() {
        for ((w, h) in listOf(1 to 1, 1 to 200, 300 to 1, 2 to 2, 3 to 5, 255 to 3, 65_535 to 1, 1 to 65_535)) {
            val rnd = Random((w * 31 + h).toLong())
            val frames = List(3) { AnimFrame(IntArray(w * h) { AnimTestKit.argb(255, rnd.nextInt(4) * 80, rnd.nextInt(4) * 80, 40) }, 100) }
            val back = decode(GifEncoder.encode(AnimSequence(w, h, frames, 0)))
            for (i in 0 until 3) assertArrayEquals("$w x $h frame $i", frames[i].pixels, back.frames[i].pixels)
        }
    }

    @Test
    fun `random sparse-change sequences round trip exactly, opaque and with clear pixels`() {
        for (trial in 0 until 120) {
            val rnd = Random(1000L + trial)
            val w = 1 + rnd.nextInt(24)
            val h = 1 + rnd.nextInt(24)
            val colors = IntArray(6) { AnimTestKit.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
            val clear = trial % 2 == 1
            var cur = IntArray(w * h) { colors[rnd.nextInt(colors.size)] }
            val frames = ArrayList<AnimFrame>()
            repeat(1 + rnd.nextInt(6)) {
                cur = cur.copyOf()
                repeat(rnd.nextInt(w * h / 2 + 1)) {
                    cur[rnd.nextInt(cur.size)] = if (clear && rnd.nextInt(3) == 0) 0 else colors[rnd.nextInt(colors.size)]
                }
                frames.add(AnimFrame(cur, 20 + rnd.nextInt(5) * 10))
            }
            val seq = AnimSequence(w, h, frames, rnd.nextInt(3))
            val mode = PaletteMode.values()[trial % 3]
            val back = decode(GifEncoder.encode(seq, GifOptions(paletteMode = mode, ditherStrength = 1f)))
            // Identical neighbours are merged, so compare what is on screen at each moment of the original.
            var t = 0L
            val starts = LongArray(frames.size) { val s = t; t += frames[it].delayMs; s }
            var tb = 0L
            val shown = ArrayList<Pair<Long, IntArray>>()
            for (f in back.frames) {
                shown.add(tb to f.pixels)
                tb += f.delayMs
            }
            for (i in frames.indices) {
                val at = shown.last { it.first <= starts[i] + 1 }
                assertArrayEquals("trial $trial frame $i ($mode, clear=$clear)", frames[i].pixels, at.second)
            }
        }
    }

    @Test
    fun `a stream with no palette sample falls back to frame palettes when the scene changes colour`() {
        val w = 64
        val h = 48
        fun ramp(red: Boolean, shift: Int) = IntArray(w * h) {
            val v = ((it % w) * 3 + it / w) % 200
            if (red) AnimTestKit.argb(255, 50 + v, 20 + v / 8, 10 + (v + shift) % 30) else AnimTestKit.argb(255, 10 + (v + shift) % 30, 20 + v / 8, 50 + v)
        }
        val frames = List(6) { ramp(it < 3, it) }
        fun worst(mode: PaletteMode): Double {
            val out = ByteArrayOutputStream()
            val enc = GifStreamEncoder(w, h, 0, false, GifOptions(paletteMode = mode, ditherStrength = 0f, maxColors = 48), out)
            for (f in frames) enc.addFrame(f, 100)
            enc.finish()
            val back = decode(out.toByteArray())
            return frames.indices.maxOf { AnimTestKit.mse(frames[it], back.frames[it].pixels) }
        }
        // The file palette was built from the first (red) frame alone, so the blue ones need their own.
        assertTrue(worst(PaletteMode.AUTO) < worst(PaletteMode.GLOBAL) * 0.85)
    }

    @Test
    fun `encoding is deterministic`() {
        val seq = AnimTestKit.photoSequence(48, 36, 5)
        assertArrayEquals(GifEncoder.encode(seq), GifEncoder.encode(seq))
    }

    @Test
    fun `bad arguments are refused`() {
        try {
            GifEncoder.encode(AnimSequence(2, 2, emptyList(), 0))
            fail()
        } catch (e: IllegalArgumentException) {
        }
        try {
            GifOptions(maxColors = 1)
            fail()
        } catch (e: IllegalArgumentException) {
        }
        try {
            GifOptions(maxColors = 256)
            fail()
        } catch (e: IllegalArgumentException) {
        }
        try {
            GifOptions(ditherStrength = 1.5f)
            fail()
        } catch (e: IllegalArgumentException) {
        }
        try {
            AnimSequence(2, 2, listOf(AnimFrame(IntArray(3), 10)), 0)
            fail()
        } catch (e: IllegalArgumentException) {
        }
        val enc = GifStreamEncoder(2, 2, 0, false, GifOptions(), ByteArrayOutputStream())
        try {
            enc.finish()
            fail()
        } catch (e: IllegalStateException) {
        }
    }

    @Test
    fun `progress reports every frame in order and a throw from it stops the encode`() {
        val seq = AnimTestKit.flatSequence(20, 10, 5)
        val seen = ArrayList<Int>()
        GifEncoder.encode(seq, progress = { done, total ->
            assertEquals(5, total)
            seen.add(done)
        })
        assertEquals(listOf(1, 2, 3, 4, 5), seen)
        try {
            GifEncoder.encode(seq, progress = { done, _ -> if (done == 2) throw java.util.concurrent.CancellationException("stop") })
            fail("expected the cancel to come out")
        } catch (e: java.util.concurrent.CancellationException) {
        }
    }

    @Test
    fun `the streaming encoder gives a valid file and keeps no reference to the caller's array`() {
        val out = ByteArrayOutputStream()
        val enc = GifStreamEncoder(30, 20, 0, false, GifOptions(), out)
        val scratch = AnimTestKit.photoFrame(30, 20, 0, 5, 10)
        val first = scratch.copyOf()
        enc.addFrame(scratch, 100)
        // Reuse and overwrite the same array for the next frame, as a video decoder would.
        val second = AnimTestKit.photoFrame(30, 20, 0, 20, 10)
        System.arraycopy(second, 0, scratch, 0, scratch.size)
        enc.addFrame(scratch, 100)
        enc.finish()
        val back = decode(out.toByteArray())
        assertEquals(2, back.frames.size)
        assertTrue(AnimTestKit.psnr(first, back.frames[0].pixels) > 28)
        assertTrue(AnimTestKit.psnr(second, back.frames[1].pixels) > 28)
        assertNotNull(Jdk.readGif(out.toByteArray()))
        assertFalse(back.frames[0].pixels.contentEquals(back.frames[1].pixels))
    }
}
