package com.fotoxplorr.app.palettebuilder

import com.fotoxplorr.app.palette.PaletteSwatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteMergeTest {

    private val red = 0xFFFF0000.toInt()
    private val nearRed = 0xFFFA0505.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00A000.toInt()

    private fun photo(vararg pairs: Pair<Int, Float>, weight: Float = 1f) =
        WeightedSwatches(pairs.map { PaletteSwatch(it.first, it.second) }, weight)

    @Test
    fun `two almost equal reds from two photos become one swatch carrying both weights`() {
        val out = PaletteMerge.merge(listOf(photo(red to 0.6f, blue to 0.4f), photo(nearRed to 0.5f, green to 0.5f)), 5)
        assertEquals(3, out.size)
        assertEquals(red, out[0].argb)
        assertEquals(1.1f / 2f, out[0].proportion, 1e-5f)
        assertEquals(1f, out.sumOf { it.proportion.toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun `colours that really differ stay apart, heaviest first`() {
        val out = PaletteMerge.merge(listOf(photo(blue to 0.2f, red to 0.5f, green to 0.3f)), 5)
        assertEquals(listOf(red, green, blue), out.map { it.argb })
    }

    @Test
    fun `the group shows the heaviest member's exact colour`() {
        val out = PaletteMerge.merge(listOf(photo(nearRed to 0.3f), photo(red to 0.7f)), 3)
        assertEquals(listOf(red), out.map { it.argb })
        assertEquals(1f, out[0].proportion, 1e-6f)
    }

    @Test
    fun `a photo's weight scales its share`() {
        val out = PaletteMerge.merge(listOf(photo(red to 1f, weight = 3f), photo(blue to 1f, weight = 1f)), 5)
        assertEquals(red, out[0].argb)
        assertEquals(0.75f, out[0].proportion, 1e-6f)
        assertEquals(0.25f, out[1].proportion, 1e-6f)
    }

    @Test
    fun `a photo of weight zero or less, or a swatch with no usable share, is ignored`() {
        val out = PaletteMerge.merge(
            listOf(
                photo(blue to 1f, weight = 0f),
                photo(green to 1f, weight = -2f),
                photo(red to Float.NaN, blue to 0f, nearRed to 0.5f),
                photo(green to 1f, weight = Float.NaN),
            ),
            5,
        )
        assertEquals(listOf(nearRed), out.map { it.argb })
    }

    @Test
    fun `the cap keeps the heaviest and rescales`() {
        val out = PaletteMerge.merge(listOf(photo(red to 0.5f, green to 0.3f, blue to 0.2f)), 2)
        assertEquals(listOf(red, green), out.map { it.argb })
        assertEquals(0.625f, out[0].proportion, 1e-6f)
        assertEquals(0.375f, out[1].proportion, 1e-6f)
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertTrue(PaletteMerge.merge(emptyList(), 5).isEmpty())
        assertTrue(PaletteMerge.merge(listOf(photo()), 5).isEmpty())
        assertTrue(PaletteMerge.merge(listOf(photo(red to 1f)), 0).isEmpty())
    }

    @Test
    fun `the order the photos arrive in does not change the answer`() {
        val a = photo(red to 0.5f, green to 0.5f)
        val b = photo(nearRed to 0.4f, blue to 0.6f)
        assertEquals(PaletteMerge.merge(listOf(a, b), 5), PaletteMerge.merge(listOf(b, a), 5))
    }

    @Test
    fun `merging from pixels runs the extractor on each photo and pools`() {
        val reds = IntArray(100) { red }
        val blues = IntArray(100) { blue }
        val out = PaletteMerge.extractMerged(listOf(WeightedPixels(reds), WeightedPixels(blues), WeightedPixels(reds.copyOf())), 5)
        assertEquals(listOf(red, blue), out.map { it.argb })
        assertEquals(2f / 3f, out[0].proportion, 1e-5f)
    }

    @Test
    fun `a transparent photo adds nothing`() {
        val clear = IntArray(50) { 0x00000000 }
        val out = PaletteMerge.extractMerged(listOf(WeightedPixels(clear), WeightedPixels(IntArray(10) { blue })), 5)
        assertEquals(listOf(blue), out.map { it.argb })
    }

    @Test
    fun `a palette needs two distinct colours`() {
        assertNull(PaletteMerge.toPalette("x", PaletteMerge.merge(listOf(photo(red to 1f, nearRed to 1f)), 5)))
        val two = PaletteMerge.toPalette("Mix", PaletteMerge.merge(listOf(photo(red to 1f, blue to 1f)), 5))
        assertNotNull(two)
        assertEquals(2, two!!.size)
    }
}
