package io.github.mbaliga.fotoxlorr.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class BearingMathTest {
    @Test
    fun northIsZeroDegrees() {
        assertEquals(0.0, BearingMath.initialBearingDegrees(0.0, 0.0, 1.0, 0.0), 0.001)
    }

    @Test
    fun eastIsNinetyDegrees() {
        assertEquals(90.0, BearingMath.initialBearingDegrees(0.0, 0.0, 0.0, 1.0), 0.001)
    }

    @Test
    fun relativeBearingUsesShortestSignedAngle() {
        assertEquals(-20.0, BearingMath.relativeBearingDegrees(350.0, 10.0), 0.001)
        assertEquals(20.0, BearingMath.relativeBearingDegrees(10.0, 350.0), 0.001)
    }
}
