package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Resizing and placing frames. */
class FrameScalerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = AnimTestKit.argb(a, r, g, b)

    private fun channelMean(px: IntArray, shift: Int): Double = px.sumOf { ((it shr shift) and 255).toDouble() } / px.size

    @Test
    fun `the same size is a copy`() {
        val px = AnimTestKit.photoFrame(10, 8, 0, 4, 4)
        val out = FrameScaler.scale(px, 10, 8, 10, 8)
        assertArrayEquals(px, out)
        assertNotSame(px, out)
    }

    @Test
    fun `halving averages each 2 by 2 block exactly`() {
        val w = 4
        val h = 2
        val px = intArrayOf(
            argb(255, 0, 0, 0), argb(255, 100, 100, 100), argb(255, 10, 20, 30), argb(255, 10, 20, 30),
            argb(255, 200, 200, 200), argb(255, 100, 100, 100), argb(255, 30, 20, 10), argb(255, 30, 20, 10),
        )
        val out = FrameScaler.scale(px, w, h, 2, 1)
        assertEquals(argb(255, 100, 100, 100), out[0])
        assertEquals(argb(255, 20, 20, 20), out[1])
    }

    @Test
    fun `a flat picture stays flat at awkward ratios`() {
        val c = argb(255, 12, 200, 99)
        for ((w, h, nw, nh) in listOf(listOf(100, 77, 33, 29), listOf(7, 5, 3, 2), listOf(50, 50, 49, 1), listOf(3, 3, 1000, 7))) {
            val out = FrameScaler.scale(IntArray(w * h) { c }, w, h, nw, nh)
            assertEquals(nw * nh, out.size)
            assertTrue("$w x $h -> $nw x $nh", out.all { it == c })
        }
    }

    @Test
    fun `shrinking keeps the average colour`() {
        val px = AnimTestKit.photoFrame(97, 61, 0, 40, 30)
        val out = FrameScaler.scale(px, 97, 61, 31, 19)
        for (shift in listOf(16, 8, 0)) assertEquals(channelMean(px, shift), channelMean(out, shift), 1.0)
    }

    @Test
    fun `a clear pixel does not tint its neighbours`() {
        // White and clear (which carries black): the mix is half-transparent WHITE, not half-transparent grey.
        val px = intArrayOf(argb(255, 255, 255, 255), argb(0, 0, 0, 0))
        val out = FrameScaler.scale(px, 2, 1, 1, 1)
        assertEquals(128, out[0] ushr 24)
        assertEquals(255, (out[0] shr 16) and 255)
        assertEquals(255, out[0] and 255)
        // All clear stays clear.
        assertEquals(0, FrameScaler.scale(IntArray(4), 2, 2, 1, 1)[0])
    }

    @Test
    fun `growing interpolates between pixel centres and clamps at the edges`() {
        val px = intArrayOf(argb(255, 0, 0, 0), argb(255, 200, 200, 200))
        val out = FrameScaler.scale(px, 2, 1, 4, 1)
        val v = out.map { it and 255 }
        assertEquals(0, v[0])
        assertEquals(200, v[3])
        assertTrue("monotone $v", v[0] <= v[1] && v[1] <= v[2] && v[2] <= v[3])
        assertEquals(50, v[1], )
    }

    @Test
    fun `one axis can shrink while the other grows`() {
        val px = IntArray(8 * 2) { argb(255, (it % 8) * 30, 0, 0) }
        val out = FrameScaler.scale(px, 8, 2, 2, 6)
        assertEquals(12, out.size)
        assertTrue(out.all { (it ushr 24) == 255 })
    }

    @Test
    fun `values never leave the range of the source`() {
        val rnd = Random(4)
        val px = IntArray(40 * 30) { argb(255, 50 + rnd.nextInt(100), 50 + rnd.nextInt(100), 50 + rnd.nextInt(100)) }
        for ((nw, nh) in listOf(13 to 9, 80 to 60, 40 to 7, 3 to 31)) {
            val out = FrameScaler.scale(px, 40, 30, nw, nh)
            for (p in out) for (shift in listOf(16, 8, 0)) assertTrue(((p shr shift) and 255) in 50..149)
        }
    }

    @Test
    fun `contain keeps the whole picture and fills the rest with the background`() {
        val red = argb(255, 255, 0, 0)
        val bg = argb(255, 1, 2, 3)
        val out = FrameScaler.fit(IntArray(40 * 20) { red }, 40, 20, 20, 20, FitMode.CONTAIN, bg)
        // 2:1 into a square: 20 x 10 centred, 5 rows of background above and below.
        for (y in 0 until 20) for (x in 0 until 20) assertEquals("($x,$y)", if (y in 5 until 15) red else bg, out[y * 20 + x])
    }

    @Test
    fun `cover fills the canvas and crops evenly`() {
        // Left half red, right half blue, 40 x 20 into 20 x 20: scale 1 (height 20 -> 20), crops 10 off each side, so a red/blue split at the middle.
        val px = IntArray(40 * 20) { if (it % 40 < 20) argb(255, 255, 0, 0) else argb(255, 0, 0, 255) }
        val out = FrameScaler.fit(px, 40, 20, 20, 20, FitMode.COVER, argb(255, 9, 9, 9))
        for (y in 0 until 20) {
            for (x in 0 until 10) assertEquals(argb(255, 255, 0, 0), out[y * 20 + x])
            for (x in 10 until 20) assertEquals(argb(255, 0, 0, 255), out[y * 20 + x])
        }
    }

    @Test
    fun `stretch ignores the shape`() {
        val out = FrameScaler.fit(IntArray(10 * 10) { argb(255, 5, 5, 5) }, 10, 10, 30, 6, FitMode.STRETCH)
        assertEquals(180, out.size)
        assertTrue(out.all { it == argb(255, 5, 5, 5) })
    }

    @Test
    fun `a see-through picture lands on the background by alpha`() {
        val half = argb(128, 255, 255, 255)
        val bg = argb(255, 0, 0, 0)
        val out = FrameScaler.fit(IntArray(4) { half }, 2, 2, 2, 2, FitMode.CONTAIN, bg)
        val v = out[0] and 255
        assertTrue("v=$v", v in 126..129)
        assertEquals(255, out[0] ushr 24)
    }

    @Test
    fun `the long edge cap never enlarges and keeps the shape`() {
        assertEquals(480 to 270, FrameScaler.capLongEdge(1920, 1080, 480))
        assertEquals(270 to 480, FrameScaler.capLongEdge(1080, 1920, 480))
        assertEquals(300 to 200, FrameScaler.capLongEdge(300, 200, 480))
        assertEquals(480 to 1, FrameScaler.capLongEdge(5000, 4, 480))
    }
}
