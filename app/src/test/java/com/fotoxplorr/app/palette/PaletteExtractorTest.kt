package com.fotoxplorr.app.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Palette extraction, checked against synthetic images whose correct answer is known by
 * construction — the same discipline [com.fotoxplorr.app.editor.AutoFixTest] uses, and for the
 * same reason: a real photograph only has opinions about what its "dominant colour" should be, a
 * synthetic one has an exact, assertable answer.
 */
class PaletteExtractorTest {

    private fun argb(r: Int, g: Int, b: Int, a: Int = 0xFF): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private val red = argb(255, 0, 0)
    private val blue = argb(0, 0, 255)
    private val green = argb(0, 255, 0)

    @Test
    fun `half red half blue yields exactly those two colours at half each`() {
        val pixels = IntArray(10_000) { if (it < 5_000) red else blue }

        val swatches = PaletteExtractor.extract(pixels)

        assertEquals(2, swatches.size)
        assertTrue("both swatches should be within a hair of 0.5", swatches.all { it.proportion in 0.49f..0.51f })
        val colours = swatches.map { it.argb }.toSet()
        assertEquals(setOf(red, blue), colours)
    }

    @Test
    fun `a solid-colour image yields exactly one dominant colour at full proportion`() {
        val pixels = IntArray(4_096) { argb(30, 140, 200) }

        val swatches = PaletteExtractor.extract(pixels)

        assertEquals(1, swatches.size)
        assertEquals(argb(30, 140, 200), swatches.single().argb)
        assertEquals(1.0f, swatches.single().proportion, 0.0001f)
    }

    @Test
    fun `an empty pixel array does not crash or divide by zero`() {
        val swatches = PaletteExtractor.extract(IntArray(0))
        assertEquals(emptyList<PaletteSwatch>(), swatches)
    }

    @Test
    fun `a fully transparent image yields no swatches rather than a false black`() {
        // Alpha 0 everywhere: nothing in the image is actually visible, so a naive quantiser that
        // ignored alpha would report "100% black" for a photo that is not black at all.
        val pixels = IntArray(1_000) { argb(0, 0, 0, a = 0) }
        assertEquals(emptyList<PaletteSwatch>(), PaletteExtractor.extract(pixels))
    }

    @Test
    fun `three unequal colours come back sorted by share, proportions summing to one`() {
        // 50% red, 30% green, 20% blue.
        val pixels = IntArray(1_000) { i ->
            when {
                i < 500 -> red
                i < 800 -> green
                else -> blue
            }
        }

        val swatches = PaletteExtractor.extract(pixels, maxColors = 3)

        assertEquals(3, swatches.size)
        assertEquals(listOf(red, green, blue), swatches.map { it.argb })
        assertEquals(0.50f, swatches[0].proportion, 0.01f)
        assertEquals(0.30f, swatches[1].proportion, 0.01f)
        assertEquals(0.20f, swatches[2].proportion, 0.01f)
        assertEquals(1.0f, swatches.sumOf { it.proportion.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `asking for more colours than the image has does not invent extras`() {
        val pixels = IntArray(2_000) { if (it < 1_000) red else blue }

        val swatches = PaletteExtractor.extract(pixels, maxColors = 8)

        // Only two colours exist in this image; the quantiser must not manufacture six more
        // near-duplicate slices just to fill the requested count.
        assertEquals(2, swatches.size)
    }

    @Test
    fun `maxColors must be positive`() {
        val error = runCatching { PaletteExtractor.extract(IntArray(1) { red }, maxColors = 0) }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `hex is upper-case RRGGBB regardless of alpha`() {
        val swatch = PaletteSwatch(argb = argb(26, 188, 240), proportion = 1f)
        assertEquals("#1ABCF0", swatch.hex)
    }

    @Test
    fun `a large uniformly-random-looking spread still respects the requested count and total mass`() {
        // Not truly random (arithmetic, so the test is deterministic across runs and Kotlin
        // versions — the same discipline SyntheticCatalogue documents for its own generator) but
        // varied enough to exercise real recursive splitting rather than the two-bucket case.
        val pixels = IntArray(20_000) { i ->
            argb((i * 37) % 256, (i * 91) % 256, (i * 193) % 256)
        }

        val swatches = PaletteExtractor.extract(pixels, maxColors = 5)

        // Varied input, so the exact bucket count isn't the point (the earlier tests already pin
        // that down precisely for the cases where it must be exact) -- what must always hold is
        // that it never exceeds what was asked for, and that the shares it does report account
        // for the whole image.
        assertTrue("expected 1..5 swatches, got ${swatches.size}", swatches.size in 1..5)
        assertEquals(1.0f, swatches.sumOf { it.proportion.toDouble() }.toFloat(), 0.001f)
        // Descending by proportion, as the segmented bar draws them (widest segment first).
        for (i in 0 until swatches.size - 1) {
            assertTrue(swatches[i].proportion >= swatches[i + 1].proportion)
        }
    }
}
