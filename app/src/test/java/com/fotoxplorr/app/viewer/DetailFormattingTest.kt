package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class DetailFormattingTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `date line matches the mockup exactly`() {
        val millis = ZonedDateTime.of(2026, 8, 1, 22, 33, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(
            "Saturday • 1 Aug 2026 • 10:33 PM",
            DetailFormatting.dateLine(millis, utc, Locale.UK),
        )
    }

    @Test
    fun `a missing date says so rather than showing the epoch`() {
        assertEquals("Unknown date", DetailFormatting.dateLine(0L, utc, Locale.UK))
        assertEquals("Unknown date", DetailFormatting.dateLine(-1L, utc, Locale.UK))
    }

    @Test
    fun `format badges map from mime types`() {
        assertEquals("HEIF", DetailFormatting.formatBadge("image/heic"))
        assertEquals("HEIF", DetailFormatting.formatBadge("image/heif"))
        assertEquals("JPEG", DetailFormatting.formatBadge("image/jpeg"))
        assertEquals("PNG", DetailFormatting.formatBadge("image/PNG"))
        assertEquals("DNG", DetailFormatting.formatBadge("image/x-adobe-dng"))
        assertEquals("MOV", DetailFormatting.formatBadge("video/quicktime"))
    }

    @Test
    fun `an unknown mime type yields no badge rather than a shrug`() {
        assertNull(DetailFormatting.formatBadge(""))
        assertNull(DetailFormatting.formatBadge("image"))
        assertNull(DetailFormatting.formatBadge("image/"))
    }

    @Test
    fun `lens line matches the mockup phrasing`() {
        assertEquals(
            "Ultra Wide Camera — 13 mm ƒ2.2",
            DetailFormatting.lensLine("Ultra Wide Camera", 13.0, 2.2),
        )
    }

    @Test
    fun `lens line degrades gracefully when parts are missing`() {
        assertEquals("Ultra Wide Camera", DetailFormatting.lensLine("Ultra Wide Camera", null, null))
        assertEquals("24 mm ƒ1.8", DetailFormatting.lensLine(null, 24.0, 1.8))
        assertEquals("ƒ1.8", DetailFormatting.lensLine("   ", null, 1.8))
        assertNull(DetailFormatting.lensLine(null, null, null))
        assertNull(DetailFormatting.lensLine(null, 0.0, 0.0))
    }

    @Test
    fun `aperture drops the trailing zero on whole stops`() {
        assertEquals("ƒ2.2", DetailFormatting.apertureText(2.2))
        assertEquals("ƒ2", DetailFormatting.apertureText(2.0))
        assertEquals("ƒ11", DetailFormatting.apertureText(11.0))
        assertEquals("ƒ1.8", DetailFormatting.apertureText(1.79))
    }

    @Test
    fun `dimensions line matches the mockup`() {
        assertEquals(
            "12 MP • 3024 × 4032 • 2.1 MB",
            DetailFormatting.dimensionsLine(3024, 4032, 2_202_010L),
        )
    }

    @Test
    fun `dimensions line omits parts it cannot establish`() {
        assertEquals("800 × 600", DetailFormatting.dimensionsLine(800, 600, 0L))
        assertEquals("Unknown dimensions", DetailFormatting.dimensionsLine(0, 0, 0L))
    }

    @Test
    fun `sub-megapixel images do not claim 0 MP`() {
        assertNull(DetailFormatting.megapixels(400, 400))
        assertNull(DetailFormatting.megapixels(0, 1000))
        assertEquals("12 MP", DetailFormatting.megapixels(3024, 4032))
    }

    @Test
    fun `byte formatting steps through units`() {
        assertEquals("512 B", DetailFormatting.formatBytes(512L))
        assertEquals("1.0 KB", DetailFormatting.formatBytes(1024L))
        assertEquals("2.1 MB", DetailFormatting.formatBytes(2_202_010L))
        assertNull(DetailFormatting.formatBytes(0L))
    }

    @Test
    fun `dynamic range badge only claims HDR for capable containers`() {
        assertEquals("STANDARD", DetailFormatting.dynamicRangeBadge("image/jpeg"))
        assertEquals("STANDARD", DetailFormatting.dynamicRangeBadge("image/png"))
        assertEquals("HDR CAPABLE", DetailFormatting.dynamicRangeBadge("image/heic"))
        assertEquals("HDR CAPABLE", DetailFormatting.dynamicRangeBadge("image/avif"))
    }

    @Test
    fun `flash glyph follows EXIF bit zero`() {
        assertEquals(false, DetailFormatting.flashFired(null))
        assertEquals(false, DetailFormatting.flashFired(0))
        assertEquals(true, DetailFormatting.flashFired(1))
        // 0x19 = fired, compulsory, return light detected.
        assertEquals(true, DetailFormatting.flashFired(0x19))
        // 0x10 = flash suppressed, did not fire.
        assertEquals(false, DetailFormatting.flashFired(0x10))
    }

    @Test
    fun `shutter speed formats as a fraction below one second`() {
        assertEquals("1/33 s", formatShutterSpeed(1.0 / 33.0))
        assertEquals("2.0 s", formatShutterSpeed(2.0))
        assertEquals("—", formatShutterSpeed(0.0))
    }
}
