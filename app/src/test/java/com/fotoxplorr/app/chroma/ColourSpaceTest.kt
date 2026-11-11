package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sRGB / linear / XYZ / Lab chain against well-known D65 values and against its own inverse. */
class ColourSpaceTest {

    private fun lab(r: Int, g: Int, b: Int) = ColourSpace.lab((0xFF shl 24) or (r shl 16) or (g shl 8) or b)

    @Test
    fun whiteIsOneHundredAndBlackIsZero() {
        val w = lab(255, 255, 255)
        assertEquals(100.0, w.l, 0.01)
        assertEquals(0.0, w.a, 0.01)
        assertEquals(0.0, w.b, 0.01)
        assertEquals(0.0, lab(0, 0, 0).l, 1e-9)
    }

    @Test
    fun theSrgbPrimariesHaveTheirPublishedLabValues() {
        val red = lab(255, 0, 0)
        assertEquals(53.24, red.l, 0.1); assertEquals(80.09, red.a, 0.1); assertEquals(67.20, red.b, 0.1)
        val green = lab(0, 255, 0)
        assertEquals(87.73, green.l, 0.1); assertEquals(-86.18, green.a, 0.1); assertEquals(83.18, green.b, 0.1)
        val blue = lab(0, 0, 255)
        assertEquals(32.30, blue.l, 0.1); assertEquals(79.19, blue.a, 0.1); assertEquals(-107.86, blue.b, 0.1)
    }

    @Test
    fun codesRoundTripThroughLinearLight() {
        for (code in 0..255) assertEquals(code, ColourSpace.encode(ColourSpace.decode(code)))
    }

    @Test
    fun everyGreyCodeRoundTripsThroughLab() {
        for (v in 0..255) {
            val argb = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            assertEquals(argb, ColourSpace.argbOf(ColourSpace.lab(argb)))
        }
    }

    @Test
    fun aSpreadOfColoursRoundTripsThroughLab() {
        var n = 0
        for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) {
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            assertEquals("#%02X%02X%02X".format(r, g, b), argb, ColourSpace.argbOf(ColourSpace.lab(argb)))
            n++
        }
        assertEquals(216, n)
    }

    @Test
    fun hexAndParse() {
        assertEquals("#1A2B3C", ColourSpace.hex(0xFF1A2B3C.toInt()))
        assertEquals(0xFF1A2B3C.toInt(), ColourSpace.parseHex("#1a2b3c"))
        assertEquals(0xFF1A2B3C.toInt(), ColourSpace.parseHex("1A2B3C"))
        assertNull(ColourSpace.parseHex("#12345"))
        assertNull(ColourSpace.parseHex("#GGGGGG"))
    }

    @Test
    fun quantisationStepIsTheSlopeOfTheCurve() {
        assertTrue(ColourSpace.stepAt(10) < ColourSpace.stepAt(200))
        assertEquals(ColourSpace.decode(101) - ColourSpace.decode(100), ColourSpace.stepAt(100), 1e-12)
    }
}
