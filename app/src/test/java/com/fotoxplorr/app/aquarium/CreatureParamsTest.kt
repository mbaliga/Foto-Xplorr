package com.fotoxplorr.app.aquarium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class CreatureParamsTest {

    @Test
    fun `the long edge is the same length for every aspect`() {
        for (aspect in listOf(0.1f, 0.5f, 1f, 2f, 5f, 15f)) {
            val p = CreatureParams.forAspect(aspect)
            assertEquals("aspect $aspect", p.length, max(p.width, p.height), 1e-5f)
            assertEquals(aspect, p.width / p.height, aspect * 1e-3f)
        }
    }

    @Test
    fun `speed amplitude and frequency stay in their ranges across all aspects`() {
        var aspect = 0.01f
        while (aspect < 50f) {
            val p = CreatureParams.forAspect(aspect)
            assertTrue(p.speed in CreatureParams.MIN_SPEED..CreatureParams.MAX_SPEED)
            assertTrue(p.swim.amplitude in 0.04f..0.12f)
            assertTrue(p.swim.waveHz in 0.8f..2.2f)
            assertTrue(p.swim.wavelengths in 0.5f..2f)
            aspect *= 1.3f
        }
    }

    @Test
    fun `longer bodies sway a little more and undulate more slowly`() {
        val round = CreatureParams.forAspect(1f)
        val long = CreatureParams.forAspect(3f)
        assertTrue(long.swim.amplitude >= round.swim.amplitude)
        assertTrue(long.swim.waveHz <= round.swim.waveHz)
        assertTrue(long.swim.wavelengths >= round.swim.wavelengths)
    }

    @Test
    fun `a bad aspect falls back to a round creature`() {
        for (bad in listOf(Float.NaN, 0f, -2f, Float.POSITIVE_INFINITY)) {
            val p = CreatureParams.forAspect(bad)
            assertTrue(p.aspect.isFinite() && p.aspect > 0f)
            assertTrue(p.width > 0f && p.height > 0f)
        }
    }

    @Test
    fun `the head side is carried through`() {
        assertFalse(CreatureParams.forAspect(1f).headLeft)
        assertTrue(CreatureParams.forAspect(1f, headLeft = true).headLeft)
    }

    @Test
    fun `depth scale is monotonic and bounded`() {
        assertEquals(0.6f, Creature.depthScale(0f), 1e-6f)
        assertEquals(1.2f, Creature.depthScale(1f), 1e-6f)
        assertEquals(Creature.depthScale(0f), Creature.depthScale(-4f), 0f)
        assertEquals(Creature.depthScale(0.5f), Creature.depthScale(Float.NaN), 0f)
        assertTrue(Creature.depthScale(0.7f) > Creature.depthScale(0.3f))
    }
}
