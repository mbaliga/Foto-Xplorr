package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorBlindnessTest {

    @Test
    fun `greys, black and white pass through every simulation unchanged`() {
        for (type in VisionType.entries) for (v in 0..255) {
            val grey = ColorMath.rgb(v, v, v)
            val out = ColorBlindness.simulate(grey, type)
            for (channel in listOf(ColorMath.red(out), ColorMath.green(out), ColorMath.blue(out))) {
                assertTrue("$type $v -> $channel", Math.abs(channel - v) <= 1)
            }
        }
        assertEquals(0xFF000000.toInt(), ColorBlindness.simulate(0xFF000000.toInt(), VisionType.DEUTAN))
        assertEquals(0xFFFFFFFF.toInt(), ColorBlindness.simulate(0xFFFFFFFF.toInt(), VisionType.TRITAN))
    }

    @Test
    fun `pure red looks like these colours to protan and tritan eyes`() {
        // Worked out by hand from the published matrices (linear light, then the sRGB curve), not by this code.
        assertEquals(ColorMath.rgb(109, 95, 0), ColorBlindness.simulate(0xFFFF0000.toInt(), VisionType.PROTAN))
        assertEquals(ColorMath.rgb(255, 0, 15), ColorBlindness.simulate(0xFFFF0000.toInt(), VisionType.TRITAN))
    }

    @Test
    fun `a muted red and green blur together for green-weak eyes but not for others`() {
        val colors = listOf(ColorMath.rgb(200, 40, 40), ColorMath.rgb(40, 140, 40))
        val found = ColorBlindness.collisions(colors)
        assertEquals(listOf(VisionType.DEUTAN), found.map { it.type })
        assertEquals(0, found[0].first)
        assertEquals(1, found[0].second)
        assertEquals(7.36, found[0].simulatedDeltaE, 0.1)
        // They were very different to a typical eye, which is the point of the warning.
        assertTrue(found[0].normalDeltaE > 100.0)
        assertFalse(ColorBlindness.isDistinguishable(colors))
    }

    @Test
    fun `blue and yellow stay apart for every type`() {
        val colors = listOf(ColorMath.rgb(0, 0, 200), ColorMath.rgb(255, 220, 0))
        assertTrue(ColorBlindness.collisions(colors).isEmpty())
        assertTrue(ColorBlindness.isDistinguishable(colors))
    }

    @Test
    fun `two identical swatches always collide, once per type`() {
        val found = ColorBlindness.collisions(listOf(0xFF336699.toInt(), 0xFF336699.toInt()))
        assertEquals(VisionType.entries.toSet(), found.map { it.type }.toSet())
        assertEquals(3, found.size)
        assertTrue(found.all { it.normalDeltaE == 0.0 })
    }

    @Test
    fun `the threshold can be tightened or loosened`() {
        val colors = listOf(ColorMath.rgb(200, 40, 40), ColorMath.rgb(40, 140, 40))
        assertTrue(ColorBlindness.isDistinguishable(colors, minDeltaE = 5.0))
        assertFalse(ColorBlindness.isDistinguishable(colors, minDeltaE = 20.0))
    }

    @Test
    fun `a palette is checked through its colours`() {
        val p = Palette.of("t", listOf(0xFF336699.toInt(), 0xFF336699.toInt()))!!
        assertEquals(3, ColorBlindness.collisions(p).size)
    }
}
