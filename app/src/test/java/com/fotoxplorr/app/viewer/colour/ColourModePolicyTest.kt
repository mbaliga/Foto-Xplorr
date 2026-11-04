package com.fotoxplorr.app.viewer.colour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColourModePolicyTest {
    private val hdrScreen = ScreenColourFacts(sdk = 35, screenHdr = true, screenWideGamut = true)
    private val sdrScreen = ScreenColourFacts(sdk = 35, screenHdr = false, screenWideGamut = false)

    @Test
    fun `an ultra hdr photo on an hdr screen asks for hdr`() {
        val d = ColourModePolicy.decide(PhotoColourFacts(true, "sRGB IEC61966-2.1"), hdrScreen)
        assertEquals(WindowColourMode.HDR, d.mode)
        assertEquals(ColourModePolicy.Copy.SHOWING_HDR, d.sentence)
    }

    @Test
    fun `never asks a screen for hdr it cannot show, and says so`() {
        val d = ColourModePolicy.decide(PhotoColourFacts(true, null), sdrScreen)
        assertEquals(WindowColourMode.DEFAULT, d.mode)
        assertEquals(ColourModePolicy.Copy.SCREEN_NOT_HDR, d.sentence)
    }

    @Test
    fun `android 13 and older never get hdr`() {
        val d = ColourModePolicy.decide(PhotoColourFacts(true, null), hdrScreen.copy(sdk = 33))
        assertEquals(WindowColourMode.DEFAULT, d.mode)
        assertEquals(ColourModePolicy.Copy.OLD_ANDROID, d.sentence)
    }

    @Test
    fun `a pq or hlg profile counts as hdr without a gain map`() {
        assertEquals(WindowColourMode.HDR, ColourModePolicy.decide(PhotoColourFacts(false, "Rec. 2100 PQ"), hdrScreen).mode)
        assertEquals(WindowColourMode.HDR, ColourModePolicy.decide(PhotoColourFacts(false, "Rec.2100 HLG"), hdrScreen).mode)
    }

    @Test
    fun `display p3 on a wide gamut screen asks for wide colour`() {
        val d = ColourModePolicy.decide(PhotoColourFacts(false, "Display P3"), hdrScreen)
        assertEquals(WindowColourMode.WIDE_COLOR_GAMUT, d.mode)
    }

    @Test
    fun `wide photo on a standard screen stays default with a sentence`() {
        val d = ColourModePolicy.decide(PhotoColourFacts(false, "Adobe RGB (1998)"), sdrScreen)
        assertEquals(WindowColourMode.DEFAULT, d.mode)
        assertNotNull(d.sentence)
    }

    @Test
    fun `wide colour needs android 8`() {
        val d = ColourModePolicy.decide(PhotoColourFacts(false, "Display P3"), hdrScreen.copy(sdk = 25))
        assertEquals(WindowColourMode.DEFAULT, d.mode)
    }

    @Test
    fun `an ordinary srgb photo changes nothing and says nothing`() {
        val d = ColourModePolicy.decide(PhotoColourFacts(false, "sRGB IEC61966-2.1"), hdrScreen)
        assertEquals(WindowColourMode.DEFAULT, d.mode)
        assertNull(d.sentence)
        assertEquals(WindowColourMode.DEFAULT, ColourModePolicy.decide(PhotoColourFacts(false, null), hdrScreen).mode)
    }

    @Test
    fun `profile word matching`() {
        assertTrue(ColourModePolicy.isWideProfile("Display P3"))
        assertFalse(ColourModePolicy.isWideProfile("sRGB"))
        assertFalse(ColourModePolicy.isWideProfile(null))
        assertTrue(ColourModePolicy.isHdrTransferProfile("ITU-R BT.2100 PQ"))
        assertFalse(ColourModePolicy.isHdrTransferProfile("Display P3"))
    }

    @Test
    fun `every sentence is one short sentence`() {
        listOf(
            ColourModePolicy.Copy.SHOWING_HDR, ColourModePolicy.Copy.SHOWING_WIDE, ColourModePolicy.Copy.SCREEN_NOT_HDR,
            ColourModePolicy.Copy.SCREEN_NOT_WIDE, ColourModePolicy.Copy.OLD_ANDROID,
        ).forEach {
            assertTrue(it, it.endsWith(".") && it.count { c -> c == '.' } == 1 && it.length <= 60)
        }
    }
}
