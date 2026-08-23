package com.fotoxplorr.app.editor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotHealTest {

    private val width = 200
    private val height = 200

    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    /** A flat mid-gray field with a bright square blemish at the centre. */
    private fun blemishedField(): IntArray {
        val pixels = IntArray(width * height) { gray(128) }
        for (y in 95..105) {
            for (x in 95..105) {
                pixels[y * width + x] = gray(255)
            }
        }
        return pixels
    }

    @Test
    fun `a blemish on a flat field heals to the field`() {
        val pixels = blemishedField()
        SpotHeal.apply(pixels, width, height, listOf(HealSpot(0.5f, 0.5f, 0.08f)))
        // The centre pixel must now read as the surrounding field, not the blemish.
        val centre = red(pixels[100 * width + 100])
        assertTrue("centre healed to $centre, expected ~128", abs(centre - 128) <= 8)
    }

    @Test
    fun `healing is deterministic`() {
        val first = blemishedField()
        val second = blemishedField()
        val spots = listOf(HealSpot(0.5f, 0.5f, 0.08f))
        SpotHeal.apply(first, width, height, spots)
        SpotHeal.apply(second, width, height, spots)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `pixels outside the spot are untouched`() {
        val pixels = blemishedField()
        val before = pixels.copyOf()
        SpotHeal.apply(pixels, width, height, listOf(HealSpot(0.5f, 0.5f, 0.05f)))
        // A corner far from the spot must be bit-identical.
        assertEquals(before[10 * width + 10], pixels[10 * width + 10])
        assertEquals(before[190 * width + 190], pixels[190 * width + 190])
    }

    @Test
    fun `a corner spot on a flat field changes nothing`() {
        // Two properties in one: donors that would run off the edge are skipped rather than
        // smearing the border in, and cloning flat field over flat field is bit-identical
        // (the feathered blend of equal colours must not drift by rounding).
        val pixels = IntArray(width * height) { gray(128) }
        val before = pixels.copyOf()
        SpotHeal.apply(pixels, width, height, listOf(HealSpot(0.01f, 0.01f, 0.15f)))
        assertTrue(before.contentEquals(pixels))
    }

    @Test
    fun `heals participate in recipe identity`() {
        assertTrue(EditRecipe().isIdentity)
        val healed = EditRecipe(heals = listOf(HealSpot(0.5f, 0.5f, HealSpot.DEFAULT_RADIUS)))
        assertTrue(!healed.isIdentity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a centre outside the image is refused`() {
        HealSpot(1.2f, 0.5f, 0.05f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a mismatched buffer is refused`() {
        SpotHeal.apply(IntArray(10), width, height, listOf(HealSpot(0.5f, 0.5f, 0.05f)))
    }
}
