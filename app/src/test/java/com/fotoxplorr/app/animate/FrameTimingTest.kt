package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Frame-rate changes by picking frames, and the GIF delay table. */
class FrameTimingTest {

    private fun steady(n: Int, ms: Int) = IntArray(n) { ms }

    @Test
    fun `30 fps down to 10 keeps every third frame at 100 ms`() {
        val src = steady(30, 33).also { it[29] = 34 }
        val r = FrameTiming.resample(src, 10.0, collapse = true)
        assertEquals(10, r.size)
        assertArrayEquals(intArrayOf(0, 3, 6, 9, 12, 15, 18, 21, 24, 27), r.picks)
        assertTrue(r.delaysMs.all { it in 99..100 || it == 91 })
        // The source is 991 ms long; the result is exactly as long.
        assertEquals(src.sum(), r.delaysMs.sum())
    }

    @Test
    fun `a higher target than the source changes nothing when collapsing`() {
        val d = steady(10, 100)
        val r = FrameTiming.resample(d, 30.0, collapse = true)
        assertArrayEquals(IntArray(10) { it }, r.picks)
        assertEquals(1000, r.delaysMs.sum())
        for (x in r.delaysMs) assertEquals(100, x)
    }

    @Test
    fun `without collapsing every tick gets a frame, so a slow source repeats frames for video`() {
        val r = FrameTiming.resample(steady(10, 100), 30.0, collapse = false)
        assertEquals(30, r.size)
        assertArrayEquals(IntArray(30) { it / 3 }, r.picks)
        assertEquals(1000, r.delaysMs.sum())
        assertTrue(r.delaysMs.all { it in 33..34 })
    }

    @Test
    fun `uneven delays are respected`() {
        // 100 ms, 300 ms, 100 ms at 10 fps: the long frame covers three ticks and appears once.
        val r = FrameTiming.resample(intArrayOf(100, 300, 100), 10.0, collapse = true)
        assertArrayEquals(intArrayOf(0, 1, 2), r.picks)
        assertArrayEquals(intArrayOf(100, 300, 100), r.delaysMs)
        val v = FrameTiming.resample(intArrayOf(100, 300, 100), 10.0, collapse = false)
        assertArrayEquals(intArrayOf(0, 1, 1, 1, 2), v.picks)
    }

    @Test
    fun `a frame that is never on screen at a tick is dropped`() {
        // A 5 ms blip that starts at 95 ms and is gone by 100 ms, sampled at 10 fps (ticks at 0, 100, 200...).
        val r = FrameTiming.resample(intArrayOf(95, 5, 100, 100), 10.0, collapse = true)
        assertTrue(1 !in r.picks.toList())
    }

    @Test
    fun `15 fps does not drift`() {
        val r = FrameTiming.resample(steady(1000, 10), 15.0, collapse = false)
        assertEquals(150, r.size)
        assertEquals(10_000, r.delaysMs.sum())
        assertTrue(r.delaysMs.all { it in 66..67 })
        // A drifting 66 ms tick would be 9900 ms by now.
    }

    @Test
    fun `the result never runs longer than the source by more than a tick`() {
        for (fps in listOf(1.0, 4.0, 7.5, 12.0, 24.0, 60.0)) {
            for (collapse in listOf(true, false)) {
                val src = IntArray(57) { 20 + (it * 13) % 90 }
                val r = FrameTiming.resample(src, fps, collapse)
                val diff = Math.abs(r.delaysMs.sum() - src.sum())
                assertTrue("fps=$fps collapse=$collapse diff=$diff", diff <= 1000.0 / fps + 1)
                for (i in 1 until r.size) assertTrue(r.picks[i] >= r.picks[i - 1])
                assertTrue(r.picks.all { it in src.indices })
            }
        }
    }

    @Test
    fun `degenerate sources`() {
        assertEquals(0, FrameTiming.resample(IntArray(0), 10.0, true).size)
        val zero = FrameTiming.resample(intArrayOf(0, 0), 10.0, true)
        assertEquals(1, zero.size)
        try {
            FrameTiming.resample(steady(3, 10), 0.0, true)
            fail()
        } catch (e: IllegalArgumentException) {
        }
        try {
            FrameTiming.resample(steady(3, 10), Double.NaN, true)
            fail()
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `evenly spread picks include both ends and never repeat`() {
        for (count in listOf(2, 3, 10, 57, 200)) for (n in listOf(1, 2, 3, 7, count, count + 5)) {
            val p = FrameTiming.evenPicks(count, n)
            if (n >= count) {
                assertArrayEquals(IntArray(count) { it }, p)
                continue
            }
            assertEquals(n, p.size)
            assertEquals(0, p.first())
            if (n >= 2) assertEquals(count - 1, p.last())
            for (i in 1 until p.size) assertTrue("count=$count n=$n $i", p[i] > p[i - 1])
        }
    }

    @Test
    fun `gif delays round with the leftover carried and never go under 2`() {
        // 15 fps: 67, 67, 66 ms -> 7, 7, 7 (6.7 rounds up, carry -3, then 6.4...) but the total stays 200 ms +- 5.
        val d = IntArray(30) { if (it % 3 == 2) 66 else 67 }
        val cs = FrameTiming.gifDelaysCs(d)
        assertEquals(2000.0, cs.sum() * 10.0, 10.0)
        assertEquals(listOf(2, 2, 2), FrameTiming.gifDelaysCs(intArrayOf(0, 5, 19)).toList())
        assertEquals(listOf(10), FrameTiming.gifDelaysCs(intArrayOf(100)).toList())
        assertEquals(65_535, FrameTiming.gifDelaysCs(intArrayOf(Int.MAX_VALUE))[0])
        // The error does not run away when every frame is too short.
        val short = FrameTiming.gifDelaysCs(IntArray(50) { 5 })
        assertTrue(short.all { it == 2 })
    }

    @Test
    fun `gif delays agree with what the encoder writes`() {
        val w = 4
        val h = 4
        val delays = intArrayOf(33, 34, 33, 100, 5, 5, 250, 66, 67, 12, 12, 12)
        val frames = delays.mapIndexed { i, d -> AnimFrame(IntArray(w * h) { px -> AnimTestKit.argb(255, i * 20, px, 0) }, d) }
        val io = Jdk.readGif(GifEncoder.encode(AnimSequence(w, h, frames, 0)))
        assertArrayEquals(FrameTiming.gifDelaysCs(delays), io.frames.map { it.delayCs }.toIntArray())
    }
}
