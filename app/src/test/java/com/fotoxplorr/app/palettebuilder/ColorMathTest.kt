package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    private val steps = listOf(0, 17, 51, 85, 128, 170, 204, 238, 255)

    @Test
    fun `white black and red have the textbook Lab values`() {
        val white = ColorMath.lab(0xFFFFFFFF.toInt())
        assertEquals(100.0, white.l, 0.01)
        assertEquals(0.0, white.a, 0.02)
        assertEquals(0.0, white.b, 0.02)
        assertEquals(0.0, ColorMath.lab(0xFF000000.toInt()).l, 1e-9)
        val red = ColorMath.lab(0xFFFF0000.toInt())
        assertEquals(53.24, red.l, 0.05)
        assertEquals(80.09, red.a, 0.1)
        assertEquals(67.20, red.b, 0.1)
    }

    @Test
    fun `Lab round trips every colour on a lattice exactly`() {
        for (r in steps) for (g in steps) for (b in steps) {
            val c = ColorMath.rgb(r, g, b)
            val lab = ColorMath.lab(c)
            assertEquals("$r $g $b", c, ColorMath.fromLab(lab.l, lab.a, lab.b))
        }
    }

    @Test
    fun `HSL round trips every colour on a lattice exactly`() {
        for (r in steps) for (g in steps) for (b in steps) {
            val c = ColorMath.rgb(r, g, b)
            val hsl = ColorMath.hsl(c)
            assertEquals("$r $g $b", c, ColorMath.fromHsl(hsl.h, hsl.s, hsl.l))
        }
    }

    @Test
    fun `HSL of the primaries`() {
        assertEquals(Hsl(0.0, 1.0, 0.5), ColorMath.hsl(0xFFFF0000.toInt()))
        assertEquals(120.0, ColorMath.hsl(0xFF00FF00.toInt()).h, 1e-9)
        assertEquals(240.0, ColorMath.hsl(0xFF0000FF.toInt()).h, 1e-9)
        assertEquals(0xFF00FFFF.toInt(), ColorMath.fromHsl(180.0, 1.0, 0.5))
        assertEquals(0xFFFF0000.toInt(), ColorMath.fromHsl(-360.0, 1.0, 0.5))
    }

    @Test
    fun `a colour outside sRGB is brought in at the same lightness and hue`() {
        val want = 140.0
        val c = ColorMath.fromLch(50.0, 200.0, want)
        assertEquals(0xFF, c ushr 24)
        val back = ColorMath.lch(c)
        assertEquals(50.0, back.l, 1.5)
        val diff = Math.abs(((back.h - want + 540) % 360) - 180)
        assertTrue("hue drifted by $diff", diff < 5.0)
    }

    @Test
    fun `hex formats upper case and parses the usual spellings`() {
        assertEquals("#FF8800", ColorMath.hex(0xFFFF8800.toInt()))
        assertEquals(0xFFFF8800.toInt(), ColorMath.parseHex("#ff8800"))
        assertEquals(0xFFFF8800.toInt(), ColorMath.parseHex("FF8800"))
        assertEquals(0xFFFF8800.toInt(), ColorMath.parseHex(" #f80 "))
    }

    @Test
    fun `hex that is not a colour is null`() {
        listOf("", "#", "#12", "#1234", "#GGGGGG", "#12345678", "red", "#-12345").forEach { assertNull(it, ColorMath.parseHex(it)) }
    }

    @Test
    fun `delta E is zero for the same colour and one hundred between black and white`() {
        assertEquals(0.0, ColorMath.deltaE(0xFF336699.toInt(), 0xFF336699.toInt()), 0.0)
        assertEquals(100.0, ColorMath.deltaE(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }
}
