package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStepTest {
    private fun clock(fps: Float) = FrameStep.clockFor(fps)

    @Test
    fun `declared rate gives the frame duration, nonsense falls back and says estimated`() {
        assertEquals(1000.0 / 24, FrameStep.clockFor(24f).frameMs, 1e-9)
        assertFalse(FrameStep.clockFor(24f).estimated)
        for (bad in listOf(null, 0f, -5f, Float.NaN, 1000f)) {
            val c = FrameStep.clockFor(bad)
            assertTrue("$bad", c.estimated)
            assertEquals(1000.0 / 30, c.frameMs, 1e-9)
        }
    }

    @Test
    fun `stepping forward 300 times at 29 97 fps lands on the true grid with no drift`() {
        val c = clock(29.97f)
        var pos = 0L
        repeat(300) { pos = FrameStep.targetMs(pos, 1, 0L, c) }
        // 300 frames * 33.3667 ms = 10010 ms; a naive +33 ms would be 9900.
        assertEquals(10_010L, pos)
    }

    @Test
    fun `stepping back undoes stepping forward`() {
        val c = clock(59.94f)
        var pos = FrameStep.targetMs(0L, 60, 0L, c) // a position ON the frame grid
        val trail = mutableListOf(pos)
        repeat(50) { pos = FrameStep.targetMs(pos, 1, 0L, c); trail += pos }
        repeat(50) {
            pos = FrameStep.targetMs(pos, -1, 0L, c)
            trail.removeAt(trail.lastIndex)
            assertEquals(trail.last(), pos)
        }
    }

    @Test
    fun `every step moves at 240 fps even though a frame is 4 ms`() {
        val c = clock(240f)
        var pos = 0L
        repeat(1000) {
            val next = FrameStep.targetMs(pos, 1, 0L, c)
            assertTrue("stalled at $pos", next > pos)
            pos = next
        }
        assertEquals(4_167L, pos)
    }

    @Test
    fun `clamps at the start and at the end`() {
        val c = clock(30f)
        assertEquals(0L, FrameStep.targetMs(10L, -1, 5_000L, c))
        assertEquals(0L, FrameStep.targetMs(0L, -1, 5_000L, c))
        assertEquals(5_000L, FrameStep.targetMs(4_990L, 1, 5_000L, c))
        assertEquals(5_000L, FrameStep.targetMs(5_000L, 1, 5_000L, c))
    }

    @Test
    fun `unknown duration clamps only at zero`() {
        val c = clock(30f)
        assertEquals(33L, FrameStep.targetMs(0L, 1, 0L, c))
        assertEquals(0L, FrameStep.targetMs(0L, -1, -1L, c))
    }

    @Test
    fun `multi frame and zero steps`() {
        val c = clock(25f)
        assertEquals(400L, FrameStep.targetMs(0L, 10, 0L, c))
        assertEquals(123L, FrameStep.targetMs(123L, 0, 0L, c))
    }

    @Test
    fun `can step flags`() {
        val c = clock(30f)
        assertFalse(FrameStep.canStepBack(0))
        assertTrue(FrameStep.canStepBack(1))
        assertTrue(FrameStep.canStepForward(1_000L, 5_000L, c))
        assertFalse(FrameStep.canStepForward(5_000L, 5_000L, c))
        assertTrue(FrameStep.canStepForward(5_000L, 0L, c))
    }

    @Test
    fun `hold repeat starts after a pause and speeds up`() {
        assertEquals(450L, FrameStep.repeatDelayMs(0))
        assertEquals(50L, FrameStep.repeatDelayMs(400))
        assertEquals(140L, FrameStep.repeatDelayMs(450))
        assertEquals(70L, FrameStep.repeatDelayMs(2_000))
        assertEquals(35L, FrameStep.repeatDelayMs(10_000))
    }

    @Test
    fun `copy is one sentence`() {
        assertTrue(FrameStep.Copy.ESTIMATED.endsWith(".") && FrameStep.Copy.ESTIMATED.count { it == '.' } == 1)
    }
}
