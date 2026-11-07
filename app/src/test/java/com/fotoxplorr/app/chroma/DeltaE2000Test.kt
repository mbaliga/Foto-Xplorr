package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CIEDE2000 against the 34 colour pairs published in Sharma, Wu and Dalal (2005), Table 1 of the
 * supplementary data (their answers are given to four decimals; ours must agree to 1e-4). The pairs
 * include the awkward cases the paper was written for: the blue region, the hue wrap at 180 degrees,
 * neutral pairs with a zero chroma, and tiny differences.
 */
class DeltaE2000Test {

    private val sharma = listOf(
        doubleArrayOf(50.0000, 2.6772, -79.7751, 50.0000, 0.0000, -82.7485, 2.0425),
        doubleArrayOf(50.0000, 3.1571, -77.2803, 50.0000, 0.0000, -82.7485, 2.8615),
        doubleArrayOf(50.0000, 2.8361, -74.0200, 50.0000, 0.0000, -82.7485, 3.4412),
        doubleArrayOf(50.0000, -1.3802, -84.2814, 50.0000, 0.0000, -82.7485, 1.0000),
        doubleArrayOf(50.0000, -1.1848, -84.8006, 50.0000, 0.0000, -82.7485, 1.0000),
        doubleArrayOf(50.0000, -0.9009, -85.5211, 50.0000, 0.0000, -82.7485, 1.0000),
        doubleArrayOf(50.0000, 0.0000, 0.0000, 50.0000, -1.0000, 2.0000, 2.3669),
        doubleArrayOf(50.0000, -1.0000, 2.0000, 50.0000, 0.0000, 0.0000, 2.3669),
        doubleArrayOf(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0009, 7.1792),
        doubleArrayOf(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0010, 7.1792),
        doubleArrayOf(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0011, 7.2195),
        doubleArrayOf(50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0012, 7.2195),
        doubleArrayOf(50.0000, -0.0010, 2.4900, 50.0000, 0.0009, -2.4900, 4.8045),
        doubleArrayOf(50.0000, -0.0010, 2.4900, 50.0000, 0.0010, -2.4900, 4.8045),
        doubleArrayOf(50.0000, -0.0010, 2.4900, 50.0000, 0.0011, -2.4900, 4.7461),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 50.0000, 0.0000, -2.5000, 4.3065),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 73.0000, 25.0000, -18.0000, 27.1492),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 61.0000, -5.0000, 29.0000, 22.8977),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 56.0000, -27.0000, -3.0000, 31.9030),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 58.0000, 24.0000, 15.0000, 19.4535),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 50.0000, 3.1736, 0.5854, 1.0000),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 50.0000, 3.2972, 0.0000, 1.0000),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 50.0000, 1.8634, 0.5757, 1.0000),
        doubleArrayOf(50.0000, 2.5000, 0.0000, 50.0000, 3.2592, 0.3350, 1.0000),
        doubleArrayOf(60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644),
        doubleArrayOf(63.0109, -31.0961, -5.8663, 62.8187, -29.7946, -4.0864, 1.2630),
        doubleArrayOf(61.2901, 3.7196, -5.3901, 61.4292, 2.2480, -4.9620, 1.8731),
        doubleArrayOf(35.0831, -44.1164, 3.7933, 35.0232, -40.0716, 1.5901, 1.8645),
        doubleArrayOf(22.7233, 20.0904, -46.6940, 23.0331, 14.9730, -42.5619, 2.0373),
        doubleArrayOf(36.4612, 47.8580, 18.3852, 36.2715, 50.5065, 21.2231, 1.4146),
        doubleArrayOf(90.8027, -2.0831, 1.4410, 91.1528, -1.6435, 0.0447, 1.4441),
        doubleArrayOf(90.9257, -0.5406, -0.9208, 88.6381, -0.8985, -0.7239, 1.5381),
        doubleArrayOf(6.7747, -0.2908, -2.4247, 5.8714, -0.0985, -2.2286, 0.6377),
        doubleArrayOf(2.0776, 0.0795, -1.1350, 0.9033, -0.0636, -0.5514, 0.9082),
    )

    @Test
    fun allThirtyFourPublishedPairsAgreeToFourDecimals() {
        assertEquals(34, sharma.size)
        for ((i, p) in sharma.withIndex()) {
            val a = Lab(p[0], p[1], p[2])
            val b = Lab(p[3], p[4], p[5])
            assertEquals("pair ${i + 1}", p[6], DeltaE2000.between(a, b), 1e-4)
            assertEquals("pair ${i + 1} reversed", p[6], DeltaE2000.between(b, a), 1e-4)
        }
    }

    @Test
    fun identicalColoursAreZeroApart() {
        val c = Lab(61.2, -20.0, 33.0)
        assertEquals(0.0, DeltaE2000.between(c, c), 0.0)
    }

    @Test
    fun theSignedPartsSayWhichWayTheSecondColourDiffers() {
        val base = Lab(50.0, 20.0, 20.0)
        assertTrue(DeltaE2000.parts(base, Lab(60.0, 20.0, 20.0)).lightness > 0)
        assertTrue(DeltaE2000.parts(base, Lab(40.0, 20.0, 20.0)).lightness < 0)
        assertTrue(DeltaE2000.parts(base, Lab(50.0, 30.0, 30.0)).chroma > 0)
        assertTrue(DeltaE2000.parts(base, Lab(50.0, 10.0, 10.0)).chroma < 0)
        val p = DeltaE2000.parts(base, Lab(50.0, 20.0, 20.0))
        assertEquals(0.0, p.total, 0.0)
    }
}
