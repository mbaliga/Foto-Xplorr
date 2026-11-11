package com.fotoxplorr.app.viewer.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KenBurnsTest {
    @Test
    fun `no photo ever pans outside itself`() {
        for (seed in 0L..2_000L) {
            assertTrue("seed $seed", KenBurns.staysInside(KenBurns.plan(seed)))
        }
        for (seed in listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L)) {
            assertTrue(KenBurns.staysInside(KenBurns.plan(seed)))
        }
    }

    @Test
    fun `the same photo always drifts the same way, different photos differ`() {
        assertEquals(KenBurns.plan(42), KenBurns.plan(42))
        assertNotEquals(KenBurns.plan(42), KenBurns.plan(43))
    }

    @Test
    fun `zoom in and zoom out both occur and stay within the ceiling`() {
        val paths = (0L..200L).map { KenBurns.plan(it) }
        assertTrue(paths.any { it.toScale > it.fromScale })
        assertTrue(paths.any { it.toScale < it.fromScale })
        assertTrue(paths.all { maxOf(it.fromScale, it.toScale) <= KenBurns.MAX_ZOOM + 1e-6f })
        assertTrue(paths.all { minOf(it.fromScale, it.toScale) >= 1f })
    }

    @Test
    fun `an axis the photo does not fill is never panned`() {
        for (seed in 0L..200L) {
            val p = KenBurns.plan(seed, filledX = false, filledY = true)
            assertEquals(0f, p.fromPanX, 0f); assertEquals(0f, p.toPanX, 0f)
            val q = KenBurns.plan(seed, filledX = true, filledY = false)
            assertEquals(0f, q.fromPanY, 0f); assertEquals(0f, q.toPanY, 0f)
        }
    }

    @Test
    fun `motion is linear and clamped in time`() {
        val p = KenBurns.plan(7)
        assertEquals(p.fromScale, p.at(-1f).scale, 0f)
        assertEquals(p.toScale, p.at(2f).scale, 0f)
        assertEquals((p.fromScale + p.toScale) / 2f, p.at(0.5f).scale, 1e-6f)
    }
}
