package com.fotoxplorr.app.aquarium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionProfileTest {

    @Test
    fun `normal animations run the tank at full motion`() {
        val p = motionProfile(animatorDurationScale = 1f, calm = false)
        assertEquals(MotionProfile.NORMAL, p)
        assertFalse(p.startPaused)
        assertEquals(1f, p.speedScale, 0f)
        assertEquals(1f, p.waveScale, 0f)
    }

    @Test
    fun `animations switched off start paused and calm`() {
        val p = motionProfile(animatorDurationScale = 0f, calm = false)
        assertTrue(p.startPaused)
        assertTrue(p.speedScale < 1f && p.waveScale < 1f)
        assertEquals(MotionProfile.CALM.speedScale, p.speedScale, 0f)
    }

    @Test
    fun `calm alone slows and softens without pausing`() {
        val p = motionProfile(animatorDurationScale = 1f, calm = true)
        assertFalse(p.startPaused)
        assertTrue(p.speedScale < 1f && p.waveScale < 1f)
    }

    @Test
    fun `an unreadable or odd animator scale is treated as normal or off`() {
        assertEquals(MotionProfile.NORMAL, motionProfile(Float.NaN, false))
        assertEquals(MotionProfile.NORMAL, motionProfile(Float.POSITIVE_INFINITY, false))
        assertTrue(motionProfile(-1f, false).startPaused)
        assertEquals(MotionProfile.NORMAL, motionProfile(0.5f, false))
        assertEquals(MotionProfile.NORMAL, motionProfile(10f, false))
    }

    @Test
    fun `scales are within zero and one`() {
        for (p in listOf(MotionProfile.NORMAL, MotionProfile.CALM)) {
            assertTrue(p.speedScale in 0f..1f)
            assertTrue(p.waveScale in 0f..1f)
        }
    }
}
