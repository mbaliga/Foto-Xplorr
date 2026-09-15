package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's colour engine.
 *
 * Every one of these pins something that is invisible until a photograph goes through it and comes
 * out subtly wrong — an overshooting curve, an exposure slider applied in the wrong space, a
 * saturation that darkens reds. None of it can be checked by looking at the code, and all of it is
 * pure arithmetic, so it belongs here rather than on a device.
 */
class AdjustmentsTest {

    // ---- the identity, which is the contract every persisted edit depends on ----

    @Test
    fun `no adjustment changes nothing`() {
        val luts = Adjustments.NONE.toChannelLuts()
        for (v in 0..255) {
            assertEquals("red at $v", v, luts.red[v])
            assertEquals("green at $v", v, luts.green[v])
            assertEquals("blue at $v", v, luts.blue[v])
        }
        assertTrue(Adjustments.NONE.isIdentity)
    }

    @Test
    fun `every field defaults to a no-op`() {
        // The stack is persisted. A field whose neutral value was not zero would re-render every
        // previously saved edit the day it was added.
        assertTrue(Adjustments().isIdentity)
        assertTrue(Adjustments().tonalIsIdentity)
        assertTrue(Adjustments().spatialIsIdentity)
    }

    // ---- exposure ----

    @Test
    fun `exposure brightens and darkens monotonically`() {
        val up = Adjustments(exposure = 1f).toChannelLuts().red
        val down = Adjustments(exposure = -1f).toChannelLuts().red
        for (v in 1..254) {
            assertTrue("+1 stop must not darken $v", up[v] >= v)
            assertTrue("-1 stop must not brighten $v", down[v] <= v)
        }
    }

    @Test
    fun `exposure is applied in linear light, not on the gamma-encoded value`() {
        // The distinguishing test. Naively multiplying the encoded value by 2 sends mid-grey (128)
        // to 255 and clips. Doubling LINEAR light sends it to about 186 — a stop brighter, which
        // is what a stop means.
        val lut = Adjustments(exposure = 1f).toChannelLuts().red
        val midGrey = lut[128]
        assertTrue("mid-grey went to $midGrey, which looks like gamma-space maths", midGrey < 220)
        assertTrue("mid-grey went to $midGrey, too dark for a full stop", midGrey > 165)
    }

    @Test
    fun `black stays black under exposure`() {
        // Zero light times any gain is still zero. A lifted black is a sign the maths is an offset.
        assertEquals(0, Adjustments(exposure = 2f).toChannelLuts().red[0])
    }

    // ---- contrast ----

    @Test
    fun `contrast pivots on mid-grey rather than darkening the picture`() {
        val lut = Adjustments(contrast = 0.5f).toChannelLuts().red
        // 128 is the pivot; smoothstep(0.502) is 0.503, so it barely moves either way.
        assertTrue("mid-grey moved to ${lut[128]}", kotlin.math.abs(lut[128] - 128) <= 3)
        assertTrue("darks must go darker", lut[64] < 64)
        assertTrue("lights must go lighter", lut[192] > 192)
    }

    @Test
    fun `contrast does not clip the ends`() {
        // A straight-line contrast scale flattens the top and bottom quarters to solid white and
        // solid black. The S-curve must not.
        val lut = Adjustments(contrast = 1f).toChannelLuts().red
        val topFlat = (240..255).count { lut[it] == 255 }
        val bottomFlat = (0..15).count { lut[it] == 0 }
        assertTrue("top clipped in $topFlat of 16 steps", topFlat < 12)
        assertTrue("bottom clipped in $bottomFlat of 16 steps", bottomFlat < 12)
    }

    @Test
    fun `negative contrast flattens towards mid-grey`() {
        val lut = Adjustments(contrast = -1f).toChannelLuts().red
        assertTrue("darks should lift", lut[64] > 64)
        assertTrue("lights should fall", lut[192] < 192)
    }

    // ---- tone regions ----

    @Test
    fun `highlights leave the shadows alone and shadows leave the highlights alone`() {
        // The reason both are weighted rather than switched: a slider that moved everything would
        // just be exposure under another name.
        val highlights = Adjustments(highlights = -1f).toChannelLuts().red
        assertEquals("deep shadow must not move", 10, highlights[10])
        assertTrue("highlights must come down", highlights[230] < 230)

        val shadows = Adjustments(shadows = 1f).toChannelLuts().red
        assertEquals("highlight must not move", 245, shadows[245])
        assertTrue("shadows must lift", shadows[25] > 25)
    }

    @Test
    fun `the tone region ramp has no seam in it`() {
        // A hard cutoff puts a visible edge across every gradient in the picture. Adjacent inputs
        // must never jump far apart.
        val lut = Adjustments(highlights = -1f).toChannelLuts().red
        for (v in 1..255) {
            assertTrue("step at $v: ${lut[v - 1]} -> ${lut[v]}", kotlin.math.abs(lut[v] - lut[v - 1]) <= 4)
        }
    }

    // ---- white balance ----

    @Test
    fun `warming pushes red up and blue down, leaving green alone`() {
        val warm = Adjustments(temperature = 1f)
        val luts = warm.toChannelLuts()
        assertTrue("red must warm", luts.red[128] > 128)
        assertTrue("blue must cool", luts.blue[128] < 128)
        assertEquals("green carries luminance and must not move", 128, luts.green[128])
    }

    @Test
    fun `temperature is a gain, so it cannot tint pure black`() {
        // An offset-based temperature lifts black to a coloured haze. A gain cannot.
        val luts = Adjustments(temperature = 1f).toChannelLuts()
        assertEquals(0, luts.red[0])
        assertEquals(0, luts.blue[0])
    }

    @Test
    fun `tint moves green against magenta`() {
        val luts = Adjustments(tint = 1f).toChannelLuts()
        assertTrue("green must come down for magenta", luts.green[128] < 128)
        assertEquals("red is untouched by tint", 128, luts.red[128])
    }

    // ---- saturation and vibrance ----

    @Test
    fun `full negative saturation is greyscale`() {
        val pixels = intArrayOf(argb(255, 200, 30, 40), argb(255, 20, 180, 90))
        applyColour(pixels, Adjustments.NONE.toChannelLuts(), saturation = -1f, vibrance = 0f)
        pixels.forEach { pixel ->
            val r = pixel ushr 16 and 0xFF
            val g = pixel ushr 8 and 0xFF
            val b = pixel and 0xFF
            assertTrue("expected grey, got $r/$g/$b", kotlin.math.abs(r - g) <= 1 && kotlin.math.abs(g - b) <= 1)
        }
    }

    @Test
    fun `desaturating uses perceptual luma, so a red does not turn near-black`() {
        // An unweighted channel average sends pure red to 85 — far darker than the eye reads it.
        // Rec. 709 luma sends it to about 54... which is correct for red specifically, so use a
        // green, where the difference is unmistakable: average 85, luma 182.
        val pixels = intArrayOf(argb(255, 0, 255, 0))
        applyColour(pixels, Adjustments.NONE.toChannelLuts(), saturation = -1f, vibrance = 0f)
        val grey = pixels[0] ushr 8 and 0xFF
        assertTrue("pure green desaturated to $grey, which is a flat average not luma", grey > 150)
    }

    @Test
    fun `saturation preserves alpha`() {
        val pixels = intArrayOf(argb(128, 200, 30, 40))
        applyColour(pixels, Adjustments.NONE.toChannelLuts(), saturation = 1f, vibrance = 0f)
        assertEquals(128, pixels[0] ushr 24 and 0xFF)
    }

    @Test
    fun `vibrance moves a dull colour more than a vivid one`() {
        // The whole point of vibrance over saturation, and the reason skin tones survive it.
        val dull = intArrayOf(argb(255, 130, 120, 110))
        val vivid = intArrayOf(argb(255, 250, 20, 10))
        val dullBefore = chroma(dull[0])
        val vividBefore = chroma(vivid[0])

        applyColour(dull, Adjustments.NONE.toChannelLuts(), saturation = 0f, vibrance = 1f)
        applyColour(vivid, Adjustments.NONE.toChannelLuts(), saturation = 0f, vibrance = 1f)

        val dullGain = chroma(dull[0]) - dullBefore
        val vividGain = chroma(vivid[0]) - vividBefore
        assertTrue("dull gained $dullGain, vivid gained $vividGain", dullGain > vividGain)
    }

    @Test
    fun `a colour pass with nothing set leaves every pixel exactly as it was`() {
        val original = intArrayOf(argb(255, 12, 200, 77), argb(64, 0, 0, 0), argb(255, 255, 255, 255))
        val pixels = original.copyOf()
        applyColour(pixels, Adjustments.NONE.toChannelLuts(), saturation = 0f, vibrance = 0f)
        assertTrue(original.contentEquals(pixels))
    }

    // ---- vignette ----

    @Test
    fun `a vignette darkens the corners and leaves the centre alone`() {
        val width = 21
        val height = 21
        val pixels = IntArray(width * height) { argb(255, 200, 200, 200) }
        applyVignette(pixels, width, height, -1f)

        val centre = pixels[10 * width + 10] and 0xFF
        val corner = pixels[0] and 0xFF
        assertEquals("the centre must be untouched", 200, centre)
        assertTrue("the corner should be darkened, was $corner", corner < 120)
    }

    @Test
    fun `a positive vignette lightens instead`() {
        val pixels = IntArray(9 * 9) { argb(255, 100, 100, 100) }
        applyVignette(pixels, 9, 9, 1f)
        assertTrue("the corner should lift", (pixels[0] and 0xFF) > 100)
    }

    @Test
    fun `a zero vignette is free and changes nothing`() {
        val original = IntArray(16) { argb(255, 50, 60, 70) }
        val pixels = original.copyOf()
        applyVignette(pixels, 4, 4, 0f)
        assertTrue(original.contentEquals(pixels))
    }

    @Test
    fun `a degenerate size does not divide by zero`() {
        applyVignette(IntArray(0), 0, 0, -1f)
    }

    // ---- LUT equality, which caching depends on ----

    @Test
    fun `identical luts compare equal despite being arrays`() {
        // A data class holding IntArrays gets identity comparison for free and silently defeats
        // any caching keyed on it. These override equals for exactly that reason.
        assertEquals(Adjustments(exposure = 0.5f).toChannelLuts(), Adjustments(exposure = 0.5f).toChannelLuts())
        assertEquals(
            Adjustments(exposure = 0.5f).toChannelLuts().hashCode(),
            Adjustments(exposure = 0.5f).toChannelLuts().hashCode(),
        )
        assertNotEquals(Adjustments(exposure = 0.5f).toChannelLuts(), Adjustments(exposure = 0.6f).toChannelLuts())
    }

    // ---- gamma helpers ----

    @Test
    fun `encode and decode are inverses`() {
        for (step in 0..255) {
            val v = step / 255f
            assertEquals("round trip at $v", v, encode(decode(v)), 1e-4f)
        }
    }

    @Test
    fun `gamma helpers pin the ends exactly`() {
        assertEquals(0f, decode(0f), 1e-6f)
        assertEquals(1f, decode(1f), 1e-4f)
        assertEquals(0f, encode(0f), 1e-6f)
        assertEquals(1f, encode(1f), 1e-4f)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun chroma(pixel: Int): Int {
        val r = pixel ushr 16 and 0xFF
        val g = pixel ushr 8 and 0xFF
        val b = pixel and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }
}
