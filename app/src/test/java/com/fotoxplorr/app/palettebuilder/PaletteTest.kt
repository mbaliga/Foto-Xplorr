package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PaletteTest {

    private fun palette(n: Int, name: String = "Test") =
        Palette.of(name, List(n) { ColorMath.rgb(it * 20, 100, 200 - it * 10) })!!

    @Test
    fun `a palette holds two to twelve colours`() {
        assertNull(Palette.of("x", emptyList()))
        assertNull(Palette.of("x", listOf(0xFF112233.toInt())))
        assertNotNull(Palette.of("x", List(2) { 0xFF112233.toInt() }))
        assertNotNull(Palette.of("x", List(12) { 0xFF112233.toInt() }))
        assertNull(Palette.of("x", List(13) { 0xFF112233.toInt() }))
    }

    @Test
    fun `the constructor refuses a size outside the range`() {
        try {
            Palette("x", listOf(PaletteColor(0xFF000000.toInt())))
            fail("one colour must be refused")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `a colour with alpha is made opaque`() {
        val p = Palette.of("x", listOf(0x00FF8800, 0x80112233.toInt()))!!
        assertEquals(listOf(0xFFFF8800.toInt(), 0xFF112233.toInt()), p.argbs)
    }

    @Test
    fun `names are folded to one tidy line`() {
        assertEquals("a b c", Palette.cleanName("  a \n\t b\u0000  c  "))
        assertEquals(PaletteBuilderCopy.DEFAULT_NAME, Palette.cleanName("  \n "))
        assertEquals(Palette.MAX_NAME_LENGTH, Palette.cleanName("x".repeat(100)).length)
    }

    @Test
    fun `a cut name never ends in half a surrogate pair`() {
        val raw = "x".repeat(Palette.MAX_NAME_LENGTH - 1) + "🎨"
        val cut = Palette.cleanName(raw)
        assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals(cut, Palette.cleanName(cut))
    }

    @Test
    fun `adding and removing stop at the limits`() {
        assertNull(palette(12).added(0xFF000000.toInt()))
        assertEquals(12, palette(11).added(0xFF000000.toInt())!!.size)
        assertNull(palette(2).removed(0))
        assertEquals(2, palette(3).removed(1)!!.size)
        assertNull(palette(5).removed(9))
    }

    @Test
    fun `a lock survives an edit of the colour and a rename`() {
        val p = palette(3).withLock(1, true).withColor(1, 0xFFABCDEF.toInt()).renamed("New")
        assertTrue(p.colors[1].locked)
        assertEquals(0xFFABCDEF.toInt(), p.colors[1].argb)
        assertEquals("New", p.name)
        assertFalse(p.toggledLock(1).colors[1].locked)
    }

    @Test
    fun `an out of range index changes nothing`() {
        val p = palette(3)
        assertSame(p, p.withColor(7, 0))
        assertSame(p, p.withLock(-1, true))
        assertSame(p, p.moved(0, 9))
    }

    @Test
    fun `moving a swatch is the keyboard form of a reorder`() {
        val p = palette(4)
        val moved = p.moved(0, 2)
        assertEquals(listOf(p.colors[1], p.colors[2], p.colors[0], p.colors[3]), moved.colors)
    }
}
