package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class PaletteRecolourTest {

    private val warm = listOf(0xFFB5381C.toInt(), 0xFFF2B134.toInt(), 0xFF1F3A5F.toInt(), 0xFFEDE6D6.toInt())

    private fun seededPixels(n: Int, seed: Long = 7L): IntArray {
        val rnd = Random(seed)
        return IntArray(n) { (rnd.nextInt(256) shl 24) or (rnd.nextInt() and 0x00FFFFFF) }
    }

    @Test
    fun `a pixel that is exactly a swatch maps to exactly that swatch`() {
        val r = PaletteRecolour(warm)
        warm.forEach { assertEquals(ColorMath.hex(it), it, r.mapPixel(it)) }
    }

    @Test
    fun `pure primaries in a primaries palette come back unchanged`() {
        val prim = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val r = PaletteRecolour(prim)
        assertArrayEquals(prim.toIntArray(), r.mapAll(prim.toIntArray()))
    }

    @Test
    fun `a pixel near a swatch is pulled onto it`() {
        val r = PaletteRecolour(warm)
        assertEquals(warm[0], r.mapPixel(0xFFBA3C20.toInt()))
        assertEquals(warm[2], r.mapPixel(0xFF223C60.toInt()))
    }

    @Test
    fun `halfway between two swatches the pixel blends them, not snaps`() {
        val r = PaletteRecolour(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        val mid = r.mapPixel(0xFF777777.toInt())
        for (ch in listOf(ColorMath.red(mid), ColorMath.green(mid), ColorMath.blue(mid))) assertTrue("mid $ch", ch in 100..155)
    }

    @Test
    fun `along a grey ramp the answer rises steadily and crosses over smoothly`() {
        val r = PaletteRecolour(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        var prev = -1
        var biggestJump = 0
        val seen = HashSet<Int>()
        for (v in 0..255) {
            val out = ColorMath.red(r.mapPixel(ColorMath.rgb(v, v, v)))
            assertTrue("falls at $v", out >= prev)
            if (prev >= 0) biggestJump = maxOf(biggestJump, out - prev)
            prev = out
            seen += out
        }
        assertTrue("jump $biggestJump", biggestJump < 40)
        assertTrue("only ${seen.size} distinct answers", seen.size > 6)
    }

    @Test
    fun `zero softness is a hard nearest pick`() {
        val r = PaletteRecolour(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), softness = 0.0)
        assertEquals(0xFF000000.toInt(), r.mapPixel(0xFF707070.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), r.mapPixel(0xFF808080.toInt()))
    }

    @Test
    fun `a one colour palette turns everything into that colour`() {
        val r = PaletteRecolour(listOf(0xFF336699.toInt()))
        assertTrue(seededPixels(500).all { (r.mapPixel(it) and 0x00FFFFFF) == 0x336699 })
    }

    @Test
    fun `alpha is carried through untouched`() {
        val r = PaletteRecolour(warm)
        assertEquals(0x80, r.mapPixel(0x80B5381C.toInt()) ushr 24)
        assertEquals(0x00, r.mapPixel(0x00123456) ushr 24)
    }

    @Test
    fun `amount mixes the answer back toward the original`() {
        val white = listOf(0xFFFFFFFF.toInt())
        val black = 0xFF000000.toInt()
        assertEquals(black, PaletteRecolour(white, amount = 0f).mapPixel(black))
        assertEquals(0xFFFFFFFF.toInt(), PaletteRecolour(white, amount = 1f).mapPixel(black))
        assertEquals(0xFF808080.toInt(), PaletteRecolour(white, amount = 0.5f).mapPixel(black))
    }

    @Test
    fun `keeping lightness keeps the shading while taking the palette's colour`() {
        val r = PaletteRecolour(listOf(0xFFD02020.toInt(), 0xFF2020D0.toInt()), keepLightness = 1f)
        for (v in listOf(60, 110, 160, 210)) {
            val src = ColorMath.rgb(v, v, v)
            val out = r.mapPixel(src)
            assertEquals("lightness at $v", ColorMath.lab(src).l, ColorMath.lab(out).l, 2.5)
            // It is no longer grey.
            assertTrue(Math.abs(ColorMath.red(out) - ColorMath.blue(out)) > 20)
        }
    }

    @Test
    fun `the same pixels always give the same answer`() {
        val px = seededPixels(2000)
        assertArrayEquals(PaletteRecolour(warm).mapAll(px), PaletteRecolour(warm).mapAll(px))
    }

    @Test
    fun `in place gives the same answer as into a new array`() {
        val px = seededPixels(1000)
        val r = PaletteRecolour(warm, keepLightness = 0.3f, amount = 0.8f)
        val expected = r.mapAll(px)
        val inPlace = px.copyOf()
        r.mapInto(inPlace, inPlace)
        assertArrayEquals(expected, inPlace)
    }

    @Test
    fun `a repeated pixel gets the same answer as a fresh one`() {
        val r = PaletteRecolour(warm)
        val px = intArrayOf(0xFF808080.toInt(), 0xFF808080.toInt(), 0xFF808080.toInt(), 0xFF123456.toInt(), 0xFF808080.toInt())
        val out = r.mapAll(px)
        assertEquals(r.mapPixel(px[0]), out[0])
        assertEquals(out[0], out[2])
        assertEquals(out[0], out[4])
        assertEquals(r.mapPixel(px[3]), out[3])
    }

    @Test
    fun `working in bands gives the whole picture's answer`() {
        val w = 37
        val h = 53
        val px = seededPixels(w * h, seed = 99L)
        val r = PaletteRecolour(warm, keepLightness = 0.25f)
        val whole = r.mapAll(px)
        val banded = IntArray(px.size)
        for (rows in PaletteRecolour.bands(w, h, maxPixelsPerBand = 400)) {
            val start = rows.first * w
            val count = (rows.last - rows.first + 1) * w
            val band = px.copyOfRange(start, start + count)
            r.mapInto(band, band, count)
            band.copyInto(banded, start)
        }
        assertArrayEquals(whole, banded)
    }

    @Test
    fun `bands tile the rows with no gap and no overlap`() {
        val bands = PaletteRecolour.bands(100, 10, maxPixelsPerBand = 250)
        assertEquals(listOf(0..1, 2..3, 4..5, 6..7, 8..9), bands)
        val ragged = PaletteRecolour.bands(100, 7, maxPixelsPerBand = 250)
        assertEquals(0, ragged.first().first)
        assertEquals(6, ragged.last().last)
        for (i in 1 until ragged.size) assertEquals(ragged[i - 1].last + 1, ragged[i].first)
    }

    @Test
    fun `a row wider than the budget is still one band per row`() {
        assertEquals(listOf(0..0, 1..1, 2..2), PaletteRecolour.bands(5000, 3, maxPixelsPerBand = 100))
    }

    @Test
    fun `no pixels give no bands`() {
        assertTrue(PaletteRecolour.bands(0, 10).isEmpty())
        assertTrue(PaletteRecolour.bands(10, 0).isEmpty())
    }

    @Test
    fun `an empty palette is refused`() {
        try {
            PaletteRecolour(emptyList())
            fail("needs a colour")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `a count that does not fit the arrays is refused`() {
        val r = PaletteRecolour(warm)
        try {
            r.mapInto(IntArray(3), IntArray(2), 3)
            fail("destination too small")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `a palette object is accepted directly`() {
        val p = Palette.of("t", warm)!!
        assertEquals(warm[1], PaletteRecolour(p).mapPixel(warm[1]))
    }
}
