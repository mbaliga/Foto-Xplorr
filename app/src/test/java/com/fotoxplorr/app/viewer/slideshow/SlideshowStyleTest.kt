package com.fotoxplorr.app.viewer.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowStyleTest {
    @Test
    fun `the cycle visits every transition and returns`() {
        var t = SlideshowTransition.NONE
        val seen = mutableSetOf(t)
        repeat(SlideshowTransition.values().size) { t = t.next(); seen += t }
        assertEquals(SlideshowTransition.values().toSet(), seen)
        assertEquals(SlideshowTransition.NONE, t)
    }

    @Test
    fun `system animations off means cut and no drift`() {
        val s = SlideshowStyle(SlideshowTransition.PUSH, kenBurns = true)
        assertEquals(SlideshowStyle(SlideshowTransition.NONE, false), s.effective(false))
        assertEquals(s, s.effective(true))
    }

    @Test
    fun `transition length is 600 ms but at most a quarter of the hold`() {
        val s = SlideshowStyle(SlideshowTransition.FADE)
        assertEquals(600, s.transitionMs(5))
        assertEquals(500, s.transitionMs(2))
        assertEquals(500, s.transitionMs(0)) // interval is floored at 2 s like the countdown
        assertEquals(0, SlideshowStyle(SlideshowTransition.NONE).transitionMs(10))
    }

    @Test
    fun `every transition starts on the leaver and ends on the newcomer`() {
        for (k in SlideshowTransition.values().filter { it != SlideshowTransition.NONE }) {
            val start = SlideshowTransitions.frame(k, 0f)
            assertEquals("$k out alpha at 0", 1f, start.outAlpha, 1e-6f)
            assertTrue("$k leaver visible", start.outgoingVisible)
            val end = SlideshowTransitions.frame(k, 1f)
            assertEquals(TransitionFrame.DONE, end)
            assertFalse(end.outgoingVisible)
        }
        assertEquals(TransitionFrame.DONE, SlideshowTransitions.frame(SlideshowTransition.NONE, 0f))
    }

    @Test
    fun `fade lowers the leaver monotonically`() {
        var last = 2f
        for (i in 0..10) {
            val a = SlideshowTransitions.frame(SlideshowTransition.FADE, i / 10f).outAlpha
            assertTrue(a <= last + 1e-6f)
            last = a
        }
    }

    @Test
    fun `push moves both photos and mirrors for a step back`() {
        val f = SlideshowTransitions.frame(SlideshowTransition.PUSH, 0.5f)
        assertEquals(0.5f, f.inShift, 1e-5f)
        assertEquals(-0.5f, f.outShift, 1e-5f)
        val b = SlideshowTransitions.frame(SlideshowTransition.PUSH, 0.25f, forward = false)
        assertTrue(b.inShift < 0f && b.outShift > 0f)
    }

    @Test
    fun `ease is exact at the ends and clamps`() {
        assertEquals(0f, SlideshowTransitions.ease(-1f), 0f)
        assertEquals(1f, SlideshowTransitions.ease(2f), 0f)
        assertEquals(0.5f, SlideshowTransitions.ease(0.5f), 1e-6f)
    }

    @Test
    fun `copy is one short sentence`() {
        for (c in listOf(SlideshowStyle.Copy.TRANSITION_CAPTION, SlideshowStyle.Copy.KEN_BURNS_CAPTION)) {
            assertTrue(c, c.endsWith(".") && c.count { it == '.' } == 1 && c.length <= 60)
        }
    }
}
