package com.fotoxplorr.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow
import kotlin.random.Random

/** The sRGB transfer functions behind every filter: the 8-bit encode must be the rounded curve, and fast. */
class ColourSpaceTest {

    /** The textbook encode, with pow, written out separately from the code under test. */
    private fun reference(linear: Double): Int {
        val c = linear.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0 + 0.5).toInt()
    }

    @Test
    fun `every 8-bit value survives a decode and an encode`() {
        for (v in 0..255) assertEquals(v, ColourSpace.encode8(ColourSpace.decode8(v)))
    }

    @Test
    fun `encode8 is the rounded curve for values spread over 0 to 1`() {
        val rng = Random(5)
        var worst = 0
        repeat(200_000) {
            // Half of the draws are squared so the dark end, where one step is a hair of linear light, gets its share.
            val v = rng.nextDouble().let { if (rng.nextBoolean()) it * it * it else it }
            val got = ColourSpace.encode8(v)
            worst = maxOf(worst, kotlin.math.abs(got - reference(v)))
        }
        assertEquals("no value may round to a different step than the curve gives", 0, worst)
    }

    @Test
    fun `encode8 rounds at the half step on either side of every threshold`() {
        for (k in 0..254) {
            val edge = ColourSpace.toLinear((k + 0.5) / 255.0)
            assertEquals("just below the edge of $k", k, ColourSpace.encode8(edge - 1e-9))
            assertEquals("just above the edge of $k", k + 1, ColourSpace.encode8(edge + 1e-9))
        }
    }

    @Test
    fun `encode8 clamps and does not trip on odd numbers`() {
        assertEquals(0, ColourSpace.encode8(-3.0))
        assertEquals(0, ColourSpace.encode8(0.0))
        assertEquals(255, ColourSpace.encode8(1.0))
        assertEquals(255, ColourSpace.encode8(7.5))
        assertEquals(0, ColourSpace.encode8(Double.NaN))
        assertEquals(255, ColourSpace.encode8(Double.POSITIVE_INFINITY))
        assertEquals(0, ColourSpace.encode8(Double.NEGATIVE_INFINITY))
    }
}
