package com.fotoxplorr.app.viewer

import com.fotoxplorr.core.formats.MediaFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure formatting for the image-detail screen, matching the owner's detail mockup exactly.
 * Kept out of the composable so every string is unit-testable.
 */
object DetailFormatting {

    /** "Saturday • 1 Aug 2026 • 10:33 PM", the date line from the mockup. */
    fun dateLine(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (epochMillis <= 0L) return "Unknown date"
        val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        val day = DateTimeFormatter.ofPattern("EEEE", locale).format(dateTime)
        val date = DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(dateTime)
        // JDK 9+ CLDR data renders the day-period lowercase ("pm") for many locales; the
        // mockup shows it uppercase, and uppercasing only the marker keeps the rest of the
        // line locale-correct.
        val hourMinute = DateTimeFormatter.ofPattern("h:mm", locale).format(dateTime)
        val marker = DateTimeFormatter.ofPattern("a", locale).format(dateTime).uppercase(locale)
        return "$day • $date • $hourMinute $marker"
    }

    /**
     * The short format badge shown on the EXIF card ("HEIF" in the mockup). A RAW file names its
     * vendor ("Canon RAW"), from the same [MediaFormat] classifier the search `type:raw` term
     * uses (P0-16) rather than a MIME check of its own. Anything else this function does not
     * specifically recognise falls back to the file extension, upper-cased and capped at 5
     * characters -- never a truncated MIME subtype string like "X-CANO", which named nothing a
     * person would recognise.
     */
    fun formatBadge(mimeType: String, fileName: String): String? {
        val format = MediaFormat.classify(mimeType, fileName)
        if (format is MediaFormat.Raw) return "${format.variant.vendor} RAW"
        val subtype = mimeType.substringAfter('/', "").lowercase().trim()
        return when (subtype) {
            "heic", "heif", "heic-sequence", "heif-sequence" -> "HEIF"
            "jpeg", "jpg" -> "JPEG"
            "png" -> "PNG"
            "webp" -> "WEBP"
            "gif" -> "GIF"
            "avif" -> "AVIF"
            "tiff", "x-tiff" -> "TIFF"
            "mp4", "x-m4v" -> "MP4"
            "quicktime" -> "MOV"
            "webm" -> "WEBM"
            else -> fileName.substringAfterLast('.', missingDelimiterValue = "")
                .uppercase(Locale.ROOT).take(5).ifEmpty { null }
        }
    }

    /**
     * "Ultra Wide Camera — 13 mm ƒ2.2" from the mockup: lens name, em dash, focal length,
     * aperture with the typographic f-stop glyph. Any part may be missing; the line is
     * assembled from whatever is actually present, and is null when nothing is.
     */
    fun lensLine(lensModel: String?, focalLengthMm: Double?, aperture: Double?): String? {
        val name = lensModel?.trim()?.takeIf(String::isNotEmpty)
        val optics = listOfNotNull(
            focalLengthMm?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} mm" },
            aperture?.takeIf { it > 0.0 }?.let { apertureText(it) },
        ).joinToString(" ")
        return when {
            name != null && optics.isNotEmpty() -> "$name — $optics"
            name != null -> name
            optics.isNotEmpty() -> optics
            else -> null
        }
    }

    /** "ƒ2.2" / "ƒ11" -- the trailing zero is dropped for whole stops, as on the mockup. */
    fun apertureText(aperture: Double): String {
        val rounded = (aperture * 10).roundToInt() / 10.0
        return if (rounded == rounded.toInt().toDouble()) "ƒ${rounded.toInt()}" else "ƒ$rounded"
    }

    /** "12 MP • 3024 × 4032 • 2.1 MB", the dimensions line from the mockup. */
    fun dimensionsLine(width: Int, height: Int, sizeBytes: Long): String = listOfNotNull(
        megapixels(width, height),
        if (width > 0 && height > 0) "$width × $height" else null,
        formatBytes(sizeBytes),
    ).joinToString(" • ").ifEmpty { "Unknown dimensions" }

    fun megapixels(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val mp = (width.toLong() * height) / 1_000_000.0
        return if (mp < 1.0) null else "${mp.roundToInt()} MP"
    }

    fun formatBytes(bytes: Long): String? {
        if (bytes <= 0L) return null
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.roundToInt()} ${units[unitIndex]}"
        } else {
            "%.1f %s".format(Locale.US, value, units[unitIndex])
        }
    }

    /**
     * "25,939 bytes (29 KB)" — the size as the owner's file-info screenshot writes it: the exact
     * count first, the human-readable rounding second.
     *
     * Both halves are load-bearing. The rounded figure is the one anyone actually reads, and the
     * exact one is the only figure that can be compared between two files that both round to
     * "29 KB". Deliberately not "on disk": that is a filesystem allocation figure, and this app
     * reads the logical size from MediaStore, so borrowing the phrasing would borrow a claim.
     */
    fun byteLine(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0L) return "Unknown size"
        val exact = java.text.NumberFormat.getIntegerInstance(locale).format(bytes)
        val rounded = formatBytes(bytes)
        return if (rounded == null || bytes < 1024L) "$exact bytes" else "$exact bytes ($rounded)"
    }

    /** "1:04" — a video's running time, for the room's information block. */
    fun durationLine(durationMillis: Long): String {
        if (durationMillis <= 0L) return "Unknown"
        val totalSeconds = durationMillis / 1_000L
        return "%d:%02d".format(Locale.US, totalSeconds / 60L, totalSeconds % 60L)
    }

    /**
     * The dynamic-range chip on the camera card (P0-16). "ULTRA HDR" when the photo's XMP actually
     * declares the gain-map namespace ([com.fotoxplorr.core.metadata.XmpPacket.HDR_GAIN_MAP_NS]) a
     * real Ultra HDR file carries -- a genuine positive answer, not a container-type guess.
     *
     * "STANDARD" (the owner's reference chip) is claimed only where it is a genuine NEGATIVE: a
     * JPEG with no gain-map namespace. Ultra HDR is the one way a JPEG carries HDR, so its absence
     * there is checkable. For any other container the answer is null and the chip is simply not
     * drawn: a HEIF or AVIF file can carry HDR through a PQ/HLG transfer or an Apple-style
     * auxiliary gain map that this check cannot see, and "STANDARD" on an iPhone HDR HEIC -- the
     * very file the reference shows -- would be a confident falsehood.
     */
    fun dynamicRangeBadge(hasHdrGainMap: Boolean, mimeType: String = ""): String? = when {
        hasHdrGainMap -> "ULTRA HDR"
        mimeType.substringAfter('/', "").lowercase().trim() in setOf("jpeg", "jpg") -> "STANDARD"
        else -> null
    }

    /**
     * "0 ev" / "-0.3 ev" / "+1 ev": the exposure strip's bias cell. Whole stops print as integers,
     * fractional ones to a tenth, and anything but zero carries its sign, because "0.7 ev" without
     * one would read as either direction.
     */
    fun exposureBiasText(ev: Double): String {
        if (ev.isNaN() || ev.isInfinite()) return "—"
        val tenths = (ev * 10).roundToInt()
        if (tenths == 0) return "0 ev"
        val sign = if (tenths > 0) "+" else "-"
        val magnitude = kotlin.math.abs(tenths)
        return if (magnitude % 10 == 0) "$sign${magnitude / 10} ev" else "$sign${magnitude / 10}.${magnitude % 10} ev"
    }

    /** Whether the flash glyph on the EXIF card should be lit. EXIF flash bit 0 = fired. */
    fun flashFired(exifFlash: Int?): Boolean = exifFlash != null && (exifFlash and 0x1) == 1

    /**
     * The EXIF MeteringMode code in words. Null for 0 ("unknown"), 255 ("other") and any code the
     * standard does not define: a label like "Other" would be noise, and the row is then omitted.
     */
    fun meteringText(code: Int?): String? = when (code) {
        1 -> "Average"
        2 -> "Centre-weighted"
        3 -> "Spot"
        4 -> "Multi-spot"
        5 -> "Multi-segment"
        6 -> "Partial"
        else -> null
    }

    /** EXIF WhiteBalance: 0 auto, 1 manual; anything else is nothing to say. */
    fun whiteBalanceText(code: Int?): String? = when (code) {
        0 -> "Auto"
        1 -> "Manual"
        else -> null
    }

    /** EXIF ExposureProgram. 0 ("not defined") and unknown codes give null. */
    fun exposureProgramText(code: Int?): String? = when (code) {
        1 -> "Manual"
        2 -> "Normal program"
        3 -> "Aperture priority"
        4 -> "Shutter priority"
        5 -> "Creative"
        6 -> "Action"
        7 -> "Portrait"
        8 -> "Landscape"
        else -> null
    }

    /** The EXIF Orientation code (1..8) as how the stored pixels must be turned to be upright. */
    fun orientationText(code: Int?): String? = when (code) {
        1 -> "Upright"
        2 -> "Mirrored"
        3 -> "Rotated 180°"
        4 -> "Mirrored upside down"
        5 -> "Mirrored, rotated 270° clockwise"
        6 -> "Rotated 90° clockwise"
        7 -> "Mirrored, rotated 90° clockwise"
        8 -> "Rotated 270° clockwise"
        else -> null
    }

    /** "123 m above sea level" / "5 m below sea level" / "At sea level"; null for NaN or infinity. */
    fun altitudeText(meters: Double?): String? {
        if (meters == null || !meters.isFinite()) return null
        val rounded = meters.roundToInt()
        return when {
            rounded == 0 -> "At sea level"
            rounded > 0 -> "$rounded m above sea level"
            else -> "${-rounded} m below sea level"
        }
    }

    /**
     * "NE 45°": the eight-wind compass point, then the bearing rounded to a whole degree. A bearing
     * outside 0..360 (or NaN) is a corrupt tag and gives null. 359.6 rounds to 360 and is shown as
     * "N 0°", never "N 360°".
     */
    fun compassText(degrees: Double?): String? {
        if (degrees == null || !degrees.isFinite() || degrees < 0.0 || degrees > 360.0) return null
        val whole = degrees.roundToInt() % 360
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return "${points[((whole + 22) / 45) % 8]} $whole°"
    }

    /** "12 km/h"; null for a negative or non-finite speed. */
    fun speedText(kmh: Double?): String? {
        if (kmh == null || !kmh.isFinite() || kmh < 0.0) return null
        return "${kmh.roundToInt()} km/h"
    }

    /** "12.5 Mbps" / "640 kbps"; null for zero or negative. */
    fun bitrateText(bitsPerSecond: Long?): String? {
        if (bitsPerSecond == null || bitsPerSecond <= 0L) return null
        return if (bitsPerSecond >= 1_000_000L) {
            "%.1f Mbps".format(Locale.US, bitsPerSecond / 1_000_000.0)
        } else {
            "${(bitsPerSecond / 1_000.0).roundToInt().coerceAtLeast(1)} kbps"
        }
    }

    /**
     * "30 fps" / "29.97 fps" / "23.98 fps": whole rates print as integers, others to two decimals
     * with trailing zeros dropped. Null for a rate that is not positive or not believable (above
     * 2000 fps, which is a unit mix-up rather than a camera).
     */
    fun fpsText(fps: Double?): String? {
        if (fps == null || !fps.isFinite() || fps <= 0.0 || fps > 2_000.0) return null
        val hundredths = (fps * 100).roundToInt()
        val text = if (hundredths % 100 == 0) {
            (hundredths / 100).toString()
        } else {
            "%.2f".format(Locale.US, hundredths / 100.0).trimEnd('0')
        }
        return "$text fps"
    }

    /**
     * How long ago a photo was taken, in the calendar terms a person uses: "Today", "Yesterday",
     * "3 days ago", "2 weeks ago", "5 months ago", "1 year ago". Null when the date is unknown
     * or lies in the future (a camera with a wrong clock is not "-3 days ago").
     */
    fun fileAgeText(takenMillis: Long, nowMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String? {
        if (takenMillis <= 0L || nowMillis < takenMillis) return null
        val taken = Instant.ofEpochMilli(takenMillis).atZone(zoneId).toLocalDate()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val days = ChronoUnit.DAYS.between(taken, now)
        if (days <= 0L) return "Today"
        if (days == 1L) return "Yesterday"
        val period = Period.between(taken, now)
        return when {
            period.years >= 1 -> plural(period.years, "year")
            period.months >= 1 -> plural(period.months, "month")
            days >= 14L -> plural((days / 7).toInt(), "week")
            else -> plural(days.toInt(), "day")
        }
    }

    private fun plural(count: Int, unit: String): String = if (count == 1) "1 $unit ago" else "$count ${unit}s ago"

    /**
     * An EXIF `OffsetTimeOriginal` ("+05:30", "-08:00", "+00:00") as a [ZoneOffset], or null when
     * the file has none or it does not parse. A malformed offset is "unknown", never "UTC".
     */
    fun exifOffset(raw: String?): ZoneOffset? {
        val text = raw?.trim() ?: return null
        val match = EXIF_OFFSET.matchEntire(text) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].toInt()
        if (hours > 14 || minutes > 59) return null
        return runCatching { ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60)) }.getOrNull()
    }

    private val EXIF_OFFSET = Regex("([+-])(\\d{2}):(\\d{2})")

    /** "UTC+05:30", "UTC-08:00", and plain "UTC" for zero. */
    fun offsetText(offset: ZoneOffset): String =
        if (offset.totalSeconds == 0) "UTC" else "UTC$offset"

    /**
     * EXIF DateTimeOriginal ("2026:08:01 22:33:00") as a wall-clock time, or null if it does not
     * parse. Cameras write all-zero placeholders ("0000:00:00 00:00:00") when the clock was never
     * set; those fail to parse and so read as unknown.
     */
    fun exifDateTime(raw: String?): LocalDateTime? {
        val text = raw?.trim() ?: return null
        return runCatching { LocalDateTime.parse(text, EXIF_DATE_TIME) }.getOrNull()
    }

    private val EXIF_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)

    /**
     * [dateLine] for the File section, with the zone said out loud. When the photo's own [offset]
     * is known the moment is shown on that clock and labelled ("... (UTC+05:30)"): the time the
     * photographer saw. When it is not, the line is the device's clock, which can be hours off for a
     * photo taken in another zone, so it says "(device time)" instead of passing it off as local.
     */
    fun takenLine(
        epochMillis: Long,
        offset: ZoneOffset?,
        deviceZone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (epochMillis <= 0L) return "Unknown date"
        return if (offset != null) {
            "${dateLine(epochMillis, offset, locale)} (${offsetText(offset)})"
        } else {
            "${dateLine(epochMillis, deviceZone, locale)} (device time)"
        }
    }
}
