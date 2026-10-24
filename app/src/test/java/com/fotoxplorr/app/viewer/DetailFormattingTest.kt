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
    fun `the byte line gives the exact count and the readable one`() {
        assertEquals("25,939 bytes (25.3 KB)", DetailFormatting.byteLine(25_939L, Locale.UK))
    }

    @Test
    fun `sub-kilobyte sizes are not restated in the same unit`() {
        // "512 bytes (512 B)" says the same thing twice, so the rounding is dropped below 1 KB.
        assertEquals("512 bytes", DetailFormatting.byteLine(512L, Locale.UK))
    }

    @Test
    fun `an unknown size says so rather than showing zero`() {
        assertEquals("Unknown size", DetailFormatting.byteLine(0L, Locale.UK))
        assertEquals("Unknown size", DetailFormatting.byteLine(-1L, Locale.UK))
    }

    @Test
    fun `durations are minutes and padded seconds`() {
        assertEquals("1:04", DetailFormatting.durationLine(64_000L))
        assertEquals("0:09", DetailFormatting.durationLine(9_400L))
        assertEquals("Unknown", DetailFormatting.durationLine(0L))
    }

    @Test
    fun `format badges map from mime types`() {
        assertEquals("HEIF", DetailFormatting.formatBadge("image/heic", "photo.heic"))
        assertEquals("HEIF", DetailFormatting.formatBadge("image/heif", "photo.heif"))
        assertEquals("JPEG", DetailFormatting.formatBadge("image/jpeg", "photo.jpg"))
        assertEquals("PNG", DetailFormatting.formatBadge("image/PNG", "photo.png"))
        assertEquals("MOV", DetailFormatting.formatBadge("video/quicktime", "clip.mov"))
    }

    @Test
    fun `an unknown mime type yields no badge rather than a shrug`() {
        assertNull(DetailFormatting.formatBadge("", "noext"))
        assertNull(DetailFormatting.formatBadge("image", "noext"))
        assertNull(DetailFormatting.formatBadge("image/", "noext"))
    }

    @Test
    fun `a RAW file names its vendor rather than a mime subtype`() {
        assertEquals("Canon RAW", DetailFormatting.formatBadge("image/x-canon-cr2", "photo.CR2"))
        assertEquals("Nikon RAW", DetailFormatting.formatBadge("application/octet-stream", "photo.nef"))
        assertEquals("Adobe DNG RAW", DetailFormatting.formatBadge("image/x-adobe-dng", "photo.dng"))
    }

    @Test
    fun `an unrecognised type falls back to the file extension, capped and upper-cased`() {
        assertEquals("MKV", DetailFormatting.formatBadge("application/octet-stream", "clip.mkv"))
        assertEquals(
            "ABCDE",
            DetailFormatting.formatBadge("application/x-weird", "odd.abcdefgh"),
        )
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
    fun `dynamic range badge only claims HDR when the XMP gain map is actually present`() {
        assertEquals("ULTRA HDR", DetailFormatting.dynamicRangeBadge(hasHdrGainMap = true))
        assertNull(DetailFormatting.dynamicRangeBadge(hasHdrGainMap = false))
        assertEquals("ULTRA HDR", DetailFormatting.dynamicRangeBadge(hasHdrGainMap = true, mimeType = "image/heic"))
    }

    @Test
    fun `STANDARD is claimed only for a JPEG, where the absence of a gain map is checkable`() {
        assertEquals("STANDARD", DetailFormatting.dynamicRangeBadge(hasHdrGainMap = false, mimeType = "image/jpeg"))
        assertEquals("STANDARD", DetailFormatting.dynamicRangeBadge(hasHdrGainMap = false, mimeType = "image/JPG"))
        // A HEIF or AVIF can carry HDR this check cannot see; no chip rather than a false one.
        assertNull(DetailFormatting.dynamicRangeBadge(hasHdrGainMap = false, mimeType = "image/heic"))
        assertNull(DetailFormatting.dynamicRangeBadge(hasHdrGainMap = false, mimeType = "image/avif"))
        assertNull(DetailFormatting.dynamicRangeBadge(hasHdrGainMap = false, mimeType = "image/png"))
    }

    @Test
    fun `exposure bias prints whole stops as integers and carries a sign off zero`() {
        assertEquals("0 ev", DetailFormatting.exposureBiasText(0.0))
        assertEquals("0 ev", DetailFormatting.exposureBiasText(-0.04))
        assertEquals("+1 ev", DetailFormatting.exposureBiasText(1.0))
        assertEquals("-0.3 ev", DetailFormatting.exposureBiasText(-0.33))
        assertEquals("+0.7 ev", DetailFormatting.exposureBiasText(0.67))
        assertEquals("-2 ev", DetailFormatting.exposureBiasText(-2.0))
        assertEquals("—", DetailFormatting.exposureBiasText(Double.NaN))
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

    // ── the detail sections' formatters ─────────────────────────────────────────────────────

    @Test
    fun `metering, white balance and exposure program say nothing for codes they do not know`() {
        assertEquals("Spot", DetailFormatting.meteringText(3))
        assertNull(DetailFormatting.meteringText(0))
        assertNull(DetailFormatting.meteringText(255))
        assertNull(DetailFormatting.meteringText(null))
        assertEquals("Auto", DetailFormatting.whiteBalanceText(0))
        assertNull(DetailFormatting.whiteBalanceText(2))
        assertEquals("Portrait", DetailFormatting.exposureProgramText(7))
        assertNull(DetailFormatting.exposureProgramText(0))
        assertNull(DetailFormatting.exposureProgramText(99))
    }

    @Test
    fun `orientation text covers the eight codes and nothing else`() {
        assertEquals("Upright", DetailFormatting.orientationText(1))
        assertEquals("Rotated 90° clockwise", DetailFormatting.orientationText(6))
        assertEquals((1..8).toList(), (1..8).filter { DetailFormatting.orientationText(it) != null })
        assertNull(DetailFormatting.orientationText(0))
        assertNull(DetailFormatting.orientationText(9))
    }

    @Test
    fun `altitude is above, below or at sea level`() {
        assertEquals("123 m above sea level", DetailFormatting.altitudeText(123.4))
        assertEquals("5 m below sea level", DetailFormatting.altitudeText(-4.6))
        assertEquals("At sea level", DetailFormatting.altitudeText(0.3))
        assertNull(DetailFormatting.altitudeText(Double.NaN))
        assertNull(DetailFormatting.altitudeText(null))
    }

    @Test
    fun `compass text names the wind and the whole degree`() {
        assertEquals("N 0°", DetailFormatting.compassText(0.0))
        assertEquals("NE 45°", DetailFormatting.compassText(45.0))
        assertEquals("E 90°", DetailFormatting.compassText(90.0))
        assertEquals("S 180°", DetailFormatting.compassText(180.0))
        assertEquals("NW 315°", DetailFormatting.compassText(315.2))
        // 359.6 rounds up to a full turn, which is north, and is not written as 360.
        assertEquals("N 0°", DetailFormatting.compassText(359.6))
        assertEquals("N 0°", DetailFormatting.compassText(360.0))
        assertNull(DetailFormatting.compassText(-1.0))
        assertNull(DetailFormatting.compassText(361.0))
        assertNull(DetailFormatting.compassText(Double.NaN))
        assertNull(DetailFormatting.compassText(null))
    }

    @Test
    fun `speed, bitrate and frame rate`() {
        assertEquals("12 km/h", DetailFormatting.speedText(12.4))
        assertEquals("0 km/h", DetailFormatting.speedText(0.0))
        assertNull(DetailFormatting.speedText(-3.0))
        assertEquals("12.5 Mbps", DetailFormatting.bitrateText(12_500_000L))
        assertEquals("640 kbps", DetailFormatting.bitrateText(640_000L))
        assertEquals("1 kbps", DetailFormatting.bitrateText(100L))
        assertNull(DetailFormatting.bitrateText(0L))
        assertNull(DetailFormatting.bitrateText(null))
        assertEquals("30 fps", DetailFormatting.fpsText(30.0))
        assertEquals("29.97 fps", DetailFormatting.fpsText(29.97))
        assertEquals("23.98 fps", DetailFormatting.fpsText(23.976))
        assertEquals("59.9 fps", DetailFormatting.fpsText(59.9))
        assertNull(DetailFormatting.fpsText(0.0))
        assertNull(DetailFormatting.fpsText(5_000.0))
        assertNull(DetailFormatting.fpsText(Double.NaN))
    }

    @Test
    fun `file age is in calendar terms and never negative`() {
        val now = ZonedDateTime.of(2026, 10, 21, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
        fun ago(year: Int, month: Int, day: Int, hour: Int = 9) =
            DetailFormatting.fileAgeText(ZonedDateTime.of(year, month, day, hour, 0, 0, 0, utc).toInstant().toEpochMilli(), now, utc)
        assertEquals("Today", ago(2026, 10, 21, 1))
        assertEquals("Yesterday", ago(2026, 10, 20, 23))
        assertEquals("3 days ago", ago(2026, 10, 18))
        assertEquals("2 weeks ago", ago(2026, 10, 5))
        assertEquals("1 month ago", ago(2026, 9, 21))
        assertEquals("5 months ago", ago(2026, 5, 10))
        assertEquals("1 year ago", ago(2025, 10, 21))
        assertEquals("3 years ago", ago(2023, 3, 3))
        assertNull("a photo from the future has no age", DetailFormatting.fileAgeText(now + 86_400_000L, now, utc))
        assertNull(DetailFormatting.fileAgeText(0L, now, utc))
    }

    @Test
    fun `an EXIF offset is a zone only when it parses`() {
        assertEquals(19_800, DetailFormatting.exifOffset("+05:30")!!.totalSeconds)
        assertEquals(-28_800, DetailFormatting.exifOffset(" -08:00 ")!!.totalSeconds)
        assertEquals(0, DetailFormatting.exifOffset("+00:00")!!.totalSeconds)
        assertNull(DetailFormatting.exifOffset("+15:00"))
        assertNull(DetailFormatting.exifOffset("+05:75"))
        assertNull(DetailFormatting.exifOffset("0530"))
        assertNull(DetailFormatting.exifOffset("banana"))
        assertNull(DetailFormatting.exifOffset(""))
        assertNull(DetailFormatting.exifOffset(null))
        assertEquals("UTC+05:30", DetailFormatting.offsetText(DetailFormatting.exifOffset("+05:30")!!))
        assertEquals("UTC-08:00", DetailFormatting.offsetText(DetailFormatting.exifOffset("-08:00")!!))
        assertEquals("UTC", DetailFormatting.offsetText(DetailFormatting.exifOffset("+00:00")!!))
    }

    @Test
    fun `EXIF wall-clock time parses, and the all-zero placeholder is unknown`() {
        assertEquals(
            java.time.LocalDateTime.of(2026, 8, 1, 22, 33, 0),
            DetailFormatting.exifDateTime("2026:08:01 22:33:00"),
        )
        assertNull(DetailFormatting.exifDateTime("0000:00:00 00:00:00"))
        assertNull(DetailFormatting.exifDateTime("2026-08-01"))
        assertNull(DetailFormatting.exifDateTime(null))
    }

    @Test
    fun `the taken line says whose clock it is`() {
        // 17:03 UTC is 22:33 at +05:30.
        val millis = ZonedDateTime.of(2026, 8, 1, 17, 3, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(
            "Saturday • 1 Aug 2026 • 10:33 PM (UTC+05:30)",
            DetailFormatting.takenLine(millis, DetailFormatting.exifOffset("+05:30"), utc, Locale.UK),
        )
        // No offset in the file: the device's clock, and the line admits it.
        assertEquals(
            "Saturday • 1 Aug 2026 • 5:03 PM (device time)",
            DetailFormatting.takenLine(millis, null, utc, Locale.UK),
        )
        assertEquals("Unknown date", DetailFormatting.takenLine(0L, null, utc, Locale.UK))
    }
}
