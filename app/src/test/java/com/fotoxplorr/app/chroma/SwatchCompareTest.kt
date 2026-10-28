package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwatchCompareTest {

    private fun lab(r: Int, g: Int, b: Int) = ColourSpace.lab(ChromaTestKit.argb(r, g, b))

    @Test
    fun theSameColourIsTheSame() {
        val v = SwatchCompare.compare(lab(100, 120, 140), lab(100, 120, 140), floor = 0.0)
        assertEquals(0.0, v.deltaE, 0.0)
        assertEquals(SwatchLevel.SAME, v.level)
        assertNull(v.detail)
    }

    @Test
    fun theStepsFollowTheDistance() {
        val base = Lab(50.0, 10.0, 10.0)
        fun level(dl: Double) = SwatchCompare.compare(base, Lab(50.0 + dl, 10.0, 10.0), floor = 0.0).level
        assertEquals(SwatchLevel.SAME, level(0.5))
        assertEquals(SwatchLevel.CLOSE, level(1.5))
        assertEquals(SwatchLevel.NOTICEABLE, level(5.0))
        assertEquals(SwatchLevel.DIFFERENT, level(15.0))
        assertEquals(SwatchLevel.VERY_DIFFERENT, level(40.0))
    }

    @Test
    fun blackAndWhiteAreVeryDifferent() {
        val v = SwatchCompare.compare(lab(0, 0, 0), lab(255, 255, 255))
        assertEquals(SwatchLevel.VERY_DIFFERENT, v.level)
        assertEquals(ChromaCopy.B_LIGHTER, v.detail)
        assertEquals(ChromaCopy.B_DARKER, SwatchCompare.compare(lab(255, 255, 255), lab(0, 0, 0)).detail)
    }

    @Test
    fun theDetailNamesTheBiggestKindOfDifference() {
        assertEquals(ChromaCopy.B_MORE_VIVID, SwatchCompare.compare(Lab(50.0, 20.0, 20.0), Lab(50.0, 45.0, 45.0)).detail)
        assertEquals(ChromaCopy.B_DULLER, SwatchCompare.compare(Lab(50.0, 45.0, 45.0), Lab(50.0, 20.0, 20.0)).detail)
        // Same lightness and chroma, a different way round the hue circle.
        assertEquals(ChromaCopy.HUE_DIFFERS, SwatchCompare.compare(Lab(50.0, 40.0, 0.0), Lab(50.0, 0.0, 40.0)).detail)
    }

    @Test
    fun anApproximateReadingCannotTellCloseColoursApart() {
        val a = Lab(50.0, 10.0, 10.0)
        val b = Lab(54.0, 10.0, 10.0)
        assertEquals(SwatchLevel.NOTICEABLE, SwatchCompare.compare(a, b, floor = 0.0).level)
        val v = SwatchCompare.compare(a, b, eitherApproximate = true)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, v.level)
        assertNull(v.detail)
        // But a big difference is still a big difference.
        assertEquals(SwatchLevel.VERY_DIFFERENT, SwatchCompare.compare(Lab(20.0, 0.0, 0.0), Lab(80.0, 0.0, 0.0), true).level)
    }

    @Test
    fun aCardReadingHasAFloorToo() {
        val a = Lab(50.0, 10.0, 10.0)
        // About 1.5 and 2.5 apart: the bare table says close and noticeable; the readings cannot tell.
        assertEquals(SwatchLevel.CLOSE, SwatchCompare.compare(a, Lab(51.5, 10.0, 10.0), floor = 0.0).level)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, SwatchCompare.compare(a, Lab(51.5, 10.0, 10.0)).level)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, SwatchCompare.compare(a, Lab(52.5, 10.0, 10.0)).level)
        assertEquals(SwatchLevel.NOTICEABLE, SwatchCompare.compare(a, Lab(56.0, 10.0, 10.0)).level)
        assertTrue(SwatchCompare.CALIBRATED_FLOOR < SwatchCompare.APPROXIMATE_FLOOR)
    }

    @Test
    fun lightnessCanBeLeftOutOfTheComparison() {
        // One colour at two brightnesses: its a* and b* grow with the light, but its shade does not change.
        val base = LinearRgb(0.30, 0.06, 0.03)
        val dark = ColourSpace.lab(base.map { it * 0.1 })
        val light = ColourSpace.lab(base)
        assertTrue(SwatchCompare.compare(dark, light, eitherApproximate = true).deltaE > 15.0)
        val v = SwatchCompare.compare(dark, light, eitherApproximate = true, ignoreLightness = true)
        assertEquals(0.0, v.deltaE, 1e-6)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, v.level)
        // A different shade at a different lightness is judged on the shade.
        val other = SwatchCompare.compare(Lab(20.0, 40.0, 0.0), Lab(80.0, 0.0, 40.0), eitherApproximate = true, ignoreLightness = true)
        assertTrue(other.level == SwatchLevel.VERY_DIFFERENT || other.level == SwatchLevel.DIFFERENT)
        assertTrue(other.detail != ChromaCopy.B_LIGHTER && other.detail != ChromaCopy.B_DARKER)
    }

    @Test
    fun everyVerdictHasAShortSentence() {
        for (l in SwatchLevel.values()) assertTrue(l.name, l.message.isNotBlank() && l.message.length < 80)
        for (r in Refusal.values()) assertTrue(r.name, r.message.isNotBlank() && r.message.length < 100)
        for (c in Caution.values()) assertTrue(c.name, c.message.isNotBlank() && c.message.length < 100)
    }
}
