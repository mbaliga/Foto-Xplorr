package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.formats.MediaFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
     * The dynamic-range badge on the mockup's EXIF card (P0-16). "ULTRA HDR" when the photo's
     * XMP actually declares the gain-map namespace ([com.fotoxplorr.app.metadata.XmpPacket.HDR_GAIN_MAP_NS])
     * a real Ultra
     * HDR file carries -- a genuine positive answer, not a container-type guess. Null otherwise,
     * so the row shows "--" the same way every other unanswered row on this card already does,
     * rather than a confident-sounding "STANDARD"/"HDR CAPABLE" this app cannot actually verify
     * (a HEIF or AVIF file is merely a container THAT CAN carry a gain map, not proof it does).
     */
    fun dynamicRangeBadge(hasHdrGainMap: Boolean): String? = if (hasHdrGainMap) "ULTRA HDR" else null

    /** Whether the flash glyph on the EXIF card should be lit. EXIF flash bit 0 = fired. */
    fun flashFired(exifFlash: Int?): Boolean = exifFlash != null && (exifFlash and 0x1) == 1
}
