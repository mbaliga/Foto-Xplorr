package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HslTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.02f) {
        assertTrue("expected $expected, was $actual", abs(expected - actual) < tolerance)
    }

    // ---- RGB <-> HSL round trip on known colours ----

    @Test
    fun `pure red converts to hue 0, full saturation, half lightness`() {
        val hsl = rgbToHsl(1f, 0f, 0f)
        assertClose(0f, hsl.hue)
        assertClose(1f, hsl.saturation)
        assertClose(0.5f, hsl.lightness)
    }

    @Test
    fun `pure green and pure blue land at their textbook hues`() {
        assertClose(120f, rgbToHsl(0f, 1f, 0f).hue)
        assertClose(240f, rgbToHsl(0f, 0f, 1f).hue)
    }

    @Test
    fun `grey has zero saturation regardless of how light it is`() {
        assertClose(0f, rgbToHsl(0.5f, 0.5f, 0.5f).saturation)
        assertClose(0f, rgbToHsl(0.9f, 0.9f, 0.9f).saturation)
    }

    @Test
    fun `HSL round-trips back to the same RGB`() {
        val original = Triple(0.8f, 0.3f, 0.1f)
        val hsl = rgbToHsl(original.first, original.second, original.third)
        val rgb = hslToRgb(hsl.hue, hsl.saturation, hsl.lightness)
        assertClose(original.first, rgb.red)
        assertClose(original.second, rgb.green)
        assertClose(original.third, rgb.blue)
    }

    // ---- band weighting ----

    @Test
    fun `a pixel exactly on a band's centre is weighted 1 for that band`() {
        assertClose(1f, bandWeight(HueBand.RED.centerDegrees, HueBand.RED))
        assertClose(1f, bandWeight(HueBand.GREEN.centerDegrees, HueBand.GREEN))
    }

    @Test
    fun `a band's weight falls to zero at its own centre plus 180 degrees`() {
        assertClose(0f, bandWeight(HueBand.RED.centerDegrees + 180f, HueBand.RED), tolerance = 0.05f)
    }

    @Test
    fun `circular distance wraps correctly across the 0-360 seam`() {
        assertClose(20f, circularHueDistance(350f, 10f))
        assertClose(0f, circularHueDistance(0f, 360f))
    }

    // ---- applyHsl on known colours ----

    private fun pixelOf(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `raising Red band luminance brightens a red pixel but leaves a green one alone`() {
        val pixels = intArrayOf(pixelOf(200, 40, 40), pixelOf(40, 200, 40))
        val hsl = HslAdjustments.NONE.with(HueBand.RED, HslBandAdjustment(luminance = 0.6f))
        applyHsl(pixels, hsl)

        val redLightness = rgbToHsl(
            (pixels[0] ushr 16 and 0xFF) / 255f,
            (pixels[0] ushr 8 and 0xFF) / 255f,
            (pixels[0] and 0xFF) / 255f,
        ).lightness
        val originalRedLightness = rgbToHsl(200 / 255f, 40 / 255f, 40 / 255f).lightness
        assertTrue("expected the red pixel to have brightened", redLightness > originalRedLightness)

        // The green pixel is ~120 degrees from red's centre (0), well past the band's own reach,
        // so it must be untouched -- bit-identical, not just "close".
        assertEquals(pixelOf(40, 200, 40), pixels[1])
    }

    @Test
    fun `a fully grey pixel is never touched by any band`() {
        val pixels = intArrayOf(pixelOf(128, 128, 128))
        val hsl = HslAdjustments(
            red = HslBandAdjustment(hue = 1f, saturation = 1f, luminance = 1f),
            green = HslBandAdjustment(hue = -1f, saturation = -1f, luminance = -1f),
        )
        applyHsl(pixels, hsl)
        assertEquals(pixelOf(128, 128, 128), pixels[0])
    }

    @Test
    fun `driving Red saturation to -1 sharply reduces a pure red pixel's chroma`() {
        val pixels = intArrayOf(pixelOf(220, 40, 40))
        val before = rgbToHsl(220 / 255f, 40 / 255f, 40 / 255f)
        applyHsl(pixels, HslAdjustments.NONE.with(HueBand.RED, HslBandAdjustment(saturation = -1f)))
        val after = rgbToHsl(
            (pixels[0] ushr 16 and 0xFF) / 255f,
            (pixels[0] ushr 8 and 0xFF) / 255f,
            (pixels[0] and 0xFF) / 255f,
        )
        // Not driven all the way to zero: ORANGE's own band also has nonzero weight this close
        // to RED's centre (see bandWeight's own doc on why bands overlap by design), diluting
        // the -1 slider's effect on saturation the same way it would in a real Lightroom-style
        // panel -- the point of this test is "sharply reduced", not "exactly zero".
        assertTrue("expected saturation to drop sharply, was ${after.saturation} (from ${before.saturation})", after.saturation < before.saturation * 0.2f)
    }

    @Test
    fun `an identity HslAdjustments changes nothing at all`() {
        val pixels = intArrayOf(pixelOf(10, 200, 90), pixelOf(0, 0, 0), pixelOf(255, 255, 255))
        val before = pixels.copyOf()
        applyHsl(pixels, HslAdjustments.NONE)
        assertEquals(before.toList(), pixels.toList())
    }
}
