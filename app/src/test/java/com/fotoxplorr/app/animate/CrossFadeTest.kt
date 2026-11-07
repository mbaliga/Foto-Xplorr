package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Easing, cross-fades between stills, and the morph that strings them together. */
class CrossFadeTest {

    private fun grey(v: Int) = AnimTestKit.argb(255, v, v, v)
    private val BLACK = intArrayOf(grey(0))
    private val WHITE = intArrayOf(grey(255))

    @Test
    fun `every easing runs from 0 to 1 and never goes backwards`() {
        for (e in Easing.values()) {
            assertEquals("$e start", 0.0, e.apply(0.0), 1e-12)
            assertEquals("$e end", 1.0, e.apply(1.0), 1e-12)
            var last = -1.0
            for (i in 0..200) {
                val v = e.apply(i / 200.0)
                assertTrue("$e at $i: $v after $last", v >= last - 1e-12 && v in 0.0..1.0)
                last = v
            }
        }
    }

    @Test
    fun `the easings have their shapes`() {
        assertEquals(0.5, Easing.LINEAR.apply(0.5), 1e-12)
        assertTrue(Easing.EASE_IN.apply(0.5) < 0.5)
        assertTrue(Easing.EASE_OUT.apply(0.5) > 0.5)
        assertEquals(0.5, Easing.EASE_IN_OUT.apply(0.5), 1e-12)
        assertEquals(0.5, Easing.SMOOTHER.apply(0.5), 1e-12)
        assertTrue(Easing.EASE_IN_OUT.apply(0.1) < 0.1)
        assertTrue(Easing.SMOOTHER.apply(0.1) < Easing.EASE_IN_OUT.apply(0.1))
        // Out-of-range input is clamped, not extrapolated.
        assertEquals(0.0, Easing.LINEAR.apply(-3.0), 0.0)
        assertEquals(1.0, Easing.EASE_IN.apply(9.0), 0.0)
    }

    @Test
    fun `the ends of a blend are the pictures themselves`() {
        val a = AnimTestKit.photoFrame(12, 9, 0, 3, 3)
        val b = AnimTestKit.photoFrame(12, 9, 0, 8, 6)
        assertArrayEquals(a, CrossFade.blend(a, b, 0.0))
        assertArrayEquals(b, CrossFade.blend(a, b, 1.0))
        assertArrayEquals(a, CrossFade.blend(a, a, 0.37))
    }

    @Test
    fun `half way between black and white is grey 128 in gamma values and brighter in linear light`() {
        val mid = CrossFade.blend(BLACK, WHITE, 0.5)[0] and 255
        assertTrue("gamma mid=$mid", mid in 127..128)
        val lin = CrossFade.blend(BLACK, WHITE, 0.5, linearLight = true)[0] and 255
        assertTrue("linear mid=$lin", lin in 186..190)
    }

    @Test
    fun `a fade from a colour to nothing keeps the colour and only lowers the alpha`() {
        val red = intArrayOf(AnimTestKit.argb(255, 255, 0, 0))
        val none = intArrayOf(0)
        for (t in listOf(0.25, 0.5, 0.75)) {
            val px = CrossFade.blend(red, none, t)[0]
            assertEquals("t=$t", Math.round(255 * (1 - t)).toInt(), px ushr 24)
            assertEquals(255, (px shr 16) and 255)
            assertEquals(0, px and 255)
        }
        assertEquals(0, CrossFade.blend(none, none, 0.5)[0])
    }

    @Test
    fun `in-betweens exclude both ends and rise steadily`() {
        val mids = CrossFade.between(BLACK, WHITE, 5, Easing.LINEAR)
        assertEquals(5, mids.size)
        val v = mids.map { it[0] and 255 }
        for (i in 1 until v.size) assertTrue("$v", v[i] > v[i - 1])
        assertTrue(v.first() > 0 && v.last() < 255)
        // Linear, 5 in-betweens: sixths of the way.
        assertEquals(255 / 6.0, v[0].toDouble(), 1.5)
        assertEquals(0, CrossFade.between(BLACK, WHITE, 0).size)
    }

    @Test
    fun `eased in-betweens bunch up near the ends`() {
        val lin = CrossFade.between(BLACK, WHITE, 9, Easing.LINEAR).map { it[0] and 255 }
        val ease = CrossFade.between(BLACK, WHITE, 9, Easing.EASE_IN_OUT).map { it[0] and 255 }
        assertTrue(ease.first() < lin.first())
        assertTrue(ease.last() > lin.last())
    }

    @Test
    fun `a morph holds each still once and fades to the next, and loops back to the first`() {
        val w = 6
        val h = 4
        val stills = List(3) { IntArray(w * h) { px -> grey(it * 100 + px) } }
        val looped = CrossFade.morph(w, h, stills, holdMs = 800, transitionFrames = 4, frameMs = 50)
        assertEquals(3 * (1 + 4), looped.frames.size)
        assertEquals(0, looped.plays)
        assertEquals(3 * (800 + 4 * 50).toLong(), looped.durationMs)
        assertArrayEquals(stills[0], looped.frames[0].pixels)
        assertEquals(800, looped.frames[0].delayMs)
        assertEquals(50, looped.frames[1].delayMs)
        assertArrayEquals(stills[1], looped.frames[5].pixels)
        assertArrayEquals(stills[2], looped.frames[10].pixels)
        // The last transition heads back to still 0: the last in-between is nearer the first still than the third.
        val lastMid = looped.frames[14].pixels[0] and 255
        assertTrue(Math.abs(lastMid - (stills[0][0] and 255)) < Math.abs(lastMid - (stills[2][0] and 255)))
        val once = CrossFade.morph(w, h, stills, 800, 4, 50, loop = false)
        assertEquals(3 + 2 * 4, once.frames.size)
        assertArrayEquals(stills[2], once.frames.last().pixels)
        assertEquals(1, CrossFade.morph(w, h, stills.take(1), 500, 4, 50).frames.size)
    }

    @Test
    fun `a morph is a sequence the encoders take, hold frames costing no more than one frame`() {
        val w = 40
        val h = 30
        val stills = List(3) { AnimTestKit.photoFrame(w, h, 0, 8 + it * 12, 15) }
        val seq = CrossFade.morph(w, h, stills, holdMs = 1000, transitionFrames = 5, frameMs = 60)
        val gif = GifEncoder.encode(seq)
        val back = GifReader.decodeAll(gif, browserDelays = false).sequence!!
        assertEquals(seq.frames.size, back.frames.size)
        assertEquals(seq.durationMs, back.durationMs)
        val apng = ApngEncoder.encode(seq)
        val back2 = ApngReader.decodeAll(apng).sequence!!
        assertEquals(seq.frames.size, back2.frames.size)
        for (i in seq.frames.indices) assertArrayEquals(seq.frames[i].pixels, back2.frames[i].pixels)
    }

    @Test
    fun `mismatched sizes and empty input are refused`() {
        try {
            CrossFade.blend(IntArray(3), IntArray(4), 0.5)
            fail()
        } catch (e: IllegalArgumentException) {
        }
        try {
            CrossFade.morph(2, 2, listOf(IntArray(4), IntArray(5)), 100, 1, 50)
            fail()
        } catch (e: IllegalArgumentException) {
        }
        try {
            CrossFade.morph(2, 2, emptyList(), 100, 1, 50)
            fail()
        } catch (e: IllegalArgumentException) {
        }
    }
}
