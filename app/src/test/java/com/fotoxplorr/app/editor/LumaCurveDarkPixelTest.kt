package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The luma curve near black. A lifted-black curve (the Fade and Matte presets) makes the ratio
 * form of the luma shift enormous for the darkest non-black pixels, so the additive/ratio hand-off
 * has to be continuous or dark gradients grow a hard saturated contour.
 */
class LumaCurveDarkPixelTest {

    private val lifted = Adjustments(lumaCurve = CurvePreset.FADE.curve)

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun run(r: Int, g: Int, b: Int): IntArray {
        val pixels = intArrayOf(argb(r, g, b))
        applyColour(pixels, lifted.toChannelLuts(), saturation = 0f, vibrance = 0f)
        val p = pixels[0]
        return intArrayOf(p ushr 16 and 0xFF, p ushr 8 and 0xFF, p and 0xFF)
    }

    @Test
    fun `a dark red stays close to its neighbour under a lifted-black luma curve`() {
        val four = run(4, 0, 0)
        val five = run(5, 0, 0)
        for (c in 0..2) {
            assertTrue(
                "channel $c jumped from ${four[c]} to ${five[c]} between (4,0,0) and (5,0,0)",
                kotlin.math.abs(four[c] - five[c]) <= 6,
            )
        }
    }

    @Test
    fun `sweeping dark reds is continuous and never darker than the one before`() {
        var previous = run(0, 0, 0)
        for (n in 1..64) {
            val now = run(n, 0, 0)
            for (c in 0..2) {
                assertTrue("red $n channel $c: ${previous[c]} -> ${now[c]}", kotlin.math.abs(now[c] - previous[c]) <= 6)
            }
            assertTrue("red $n got darker: ${previous[0]} -> ${now[0]}", now[0] >= previous[0] - 1)
            previous = now
        }
    }

    @Test
    fun `sweeping dark greys is monotonic and lands on the curve`() {
        var previous = -1
        for (n in 0..64) {
            val out = run(n, n, n)
            assertEquals(out[0], out[1]); assertEquals(out[1], out[2])
            assertTrue("grey $n: $previous -> ${out[0]}", out[0] >= previous)
            previous = out[0]
        }
        // Black lifts to the curve's 0.08 floor rather than staying black.
        assertEquals(20f, run(0, 0, 0)[0].toFloat(), 2f)
    }

    @Test
    fun `hue is still preserved once the pixel is bright enough for the ratio form`() {
        val out = run(120, 60, 30)
        assertEquals(2f, out[0].toFloat() / out[1], 0.15f)
    }
}
