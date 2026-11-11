package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `black on white is 21 to 1 and a colour on itself is 1 to 1`() {
        assertEquals(21.0, Contrast.ratio(black, white), 1e-9)
        assertEquals(21.0, Contrast.ratio(white, black), 1e-9)
        assertEquals(1.0, Contrast.ratio(0xFF336699.toInt(), 0xFF336699.toInt()), 1e-12)
    }

    @Test
    fun `luminance of the extremes and of the primaries`() {
        assertEquals(0.0, Contrast.luminance(black), 0.0)
        assertEquals(1.0, Contrast.luminance(white), 1e-9)
        assertEquals(0.2126, Contrast.luminance(0xFFFF0000.toInt()), 1e-9)
        assertEquals(0.7152, Contrast.luminance(0xFF00FF00.toInt()), 1e-9)
        assertEquals(0.0722, Contrast.luminance(0xFF0000FF.toInt()), 1e-9)
    }

    @Test
    fun `the well known grey that just passes AA is 767676 and 777777 just fails`() {
        assertEquals(4.54, Contrast.ratio(0xFF767676.toInt(), white), 0.01)
        assertEquals(4.48, Contrast.ratio(0xFF777777.toInt(), white), 0.01)
        assertEquals(ContrastLevel.AA, Contrast.level(Contrast.ratio(0xFF767676.toInt(), white)))
        assertEquals(ContrastLevel.AA_LARGE, Contrast.level(Contrast.ratio(0xFF777777.toInt(), white)))
    }

    @Test
    fun `pure red on white is about four to one`() {
        assertEquals(4.0, Contrast.ratio(0xFFFF0000.toInt(), white), 0.01)
    }

    @Test
    fun `levels change exactly at 3, 4,5 and 7`() {
        assertEquals(ContrastLevel.FAIL, Contrast.level(2.99))
        assertEquals(ContrastLevel.AA_LARGE, Contrast.level(3.0))
        assertEquals(ContrastLevel.AA_LARGE, Contrast.level(4.49))
        assertEquals(ContrastLevel.AA, Contrast.level(4.5))
        assertEquals(ContrastLevel.AA, Contrast.level(6.99))
        assertEquals(ContrastLevel.AAA, Contrast.level(7.0))
        assertEquals(ContrastLevel.AAA, Contrast.level(21.0))
        assertEquals(ContrastLevel.FAIL, Contrast.level(1.0))
    }

    @Test
    fun `plain text is black on light and white on dark`() {
        assertEquals(black, Contrast.plainTextOn(0xFFFFE000.toInt()))
        assertEquals(white, Contrast.plainTextOn(0xFF101E50.toInt()))
        assertEquals(black, Contrast.plainTextOn(white))
        assertEquals(white, Contrast.plainTextOn(black))
    }

    @Test
    fun `the matrix is square, symmetric and one to one on the diagonal`() {
        val m = ContrastMatrix(listOf(black, 0xFF808080.toInt(), white))
        assertEquals(3, m.size)
        for (t in 0 until 3) for (b in 0 until 3) {
            assertEquals(m.ratio(t, b), m.ratio(b, t), 1e-12)
            if (t == b) assertEquals(1.0, m.ratio(t, b), 1e-12)
        }
        assertEquals(21.0, m.ratio(0, 2), 1e-9)
    }

    @Test
    fun `pass and fail follow the level asked for`() {
        val m = ContrastMatrix(listOf(black, 0xFF808080.toInt(), white))
        assertTrue(m.passes(0, 2, ContrastLevel.AAA))
        assertFalse(m.passes(0, 0))
        // Mid grey is about 5.3 to 1 against black and 3.9 against white: good for text on one, big text only on the other.
        assertTrue(m.passes(1, 0))
        assertFalse(m.passes(1, 2))
        assertTrue(m.passes(1, 2, ContrastLevel.AA_LARGE))
        // Ordered pairs of different swatches that reach AA: black/white both ways, grey on black both ways.
        assertEquals(4, m.passCount())
    }

    @Test
    fun `the best text swatch is the one with the most contrast, and null when none reaches the level`() {
        val m = ContrastMatrix(listOf(black, 0xFF808080.toInt(), white))
        assertEquals(2, m.bestText(0))
        assertEquals(0, m.bestText(2))
        val flat = ContrastMatrix(listOf(0xFF808080.toInt(), 0xFF858585.toInt()))
        assertNull(flat.bestText(0))
    }

    @Test
    fun `a matrix of a palette uses its colours in order`() {
        val p = Palette.of("t", listOf(black, white))!!
        assertEquals(21.0, ContrastMatrix(p).ratio(0, 1), 1e-9)
    }
}
