package com.fotoxplorr.app.viewer.colour

import com.fotoxplorr.app.viewer.ImageExifDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProInfoLinesTest {
    private val none = ColourDecision(WindowColourMode.DEFAULT, null)

    @Test
    fun `an empty file gives only what is true`() {
        val lines = ProInfoLines.of(ImageExifDetails(), "image/png", 0, 0, 0L, none)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a full ultra hdr photo lists range colour screen size file exposure lens`() {
        val d = ImageExifDetails(
            hasHdrGainMap = true, colorProfile = "Display P3", aperture = 1.8, shutterSpeed = "1/120", iso = "64",
            exposureBiasEv = -0.3, lensModel = "Main", focalLengthMm = 24.0, displayWidth = 4000, displayHeight = 3000,
        )
        val lines = ProInfoLines.of(d, "image/jpeg", 3000, 4000, 3_000_000L, ColourDecision(WindowColourMode.HDR, "Showing this photo in HDR."))
        val map = lines.associate { it.label to it.value }
        assertEquals("ULTRA HDR", map["Range"])
        assertEquals("Display P3", map["Colour"])
        assertEquals("Showing this photo in HDR.", map["Screen"])
        assertEquals("12 MP • 4000 × 3000", map["Size"])
        assertEquals("ƒ1.8  1/120  ISO 64", map["Exposure"])
        assertEquals("-0.3 ev", map["Bias"])
        assertEquals("Main — 24 mm ƒ1.8", map["Lens"])
        assertEquals(listOf("Range", "Colour", "Screen", "Size", "File", "Exposure", "Bias", "Lens"), lines.map { it.label })
    }

    @Test
    fun `a plain jpeg is standard range and falls back to the stored size`() {
        val lines = ProInfoLines.of(ImageExifDetails(), "image/jpeg", 2000, 1000, 0L, none)
        assertEquals("STANDARD", lines.first { it.label == "Range" }.value)
        assertEquals("2 MP • 2000 × 1000", lines.first { it.label == "Size" }.value)
        assertNull(lines.firstOrNull { it.label == "File" })
    }
}
