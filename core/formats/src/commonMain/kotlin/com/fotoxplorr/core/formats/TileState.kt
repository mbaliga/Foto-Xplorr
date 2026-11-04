package com.fotoxplorr.core.formats

/** How far the image request for a tile has got. */
enum class LoadResult { Pending, Loaded, Failed }

/**
 * What a tile (or the viewer's photo area) draws. There is no third "nothing": the acceptance
 * line for WP1.7 is that a black or empty tile is impossible, so every input maps to one of these.
 */
sealed interface TileState {

    /** Hand the file to the image loader and draw what comes back. */
    data object Show : TileState

    /**
     * A neutral tile with a short [badge] ("CR3"), a one-line [reason] (the viewer shows it, small
     * tiles leave it out) and the [description] a screen reader announces.
     */
    data class Placeholder(val badge: String, val reason: String, val description: String) : TileState
}

/** User-facing sentences for the placeholder, in one place. Each is one short sentence. */
object FormatCopy {
    const val NO_DECODER = "This type of file can't be shown here."
    const val COULD_NOT_READ = "This file couldn't be read."
    const val PROTECTED = "This photo is protected."

    const val RAW_NO_PREVIEW = "Raw photos can't be previewed yet."

    fun notOnThisPhone(kind: String): String = "This phone can't show $kind files."
    fun needsPack(kind: String): String = "$kind files need an add-on to show."
    fun needsNewerAndroid(kind: String, release: String): String = "$kind files need Android $release or newer."
}

/** Short, honest names for a format. */
object FormatLabels {

    /** At most five characters, upper case: what the badge on the tile says. */
    fun badge(format: MediaFormat): String = when (format) {
        is MediaFormat.Raw -> format.variant.extension.uppercase()
        MediaFormat.Jpeg -> "JPG"
        MediaFormat.Png -> "PNG"
        MediaFormat.Bmp -> "BMP"
        MediaFormat.WebP -> "WEBP"
        MediaFormat.Gif -> "GIF"
        MediaFormat.Heif -> "HEIC"
        MediaFormat.Svg -> "SVG"
        MediaFormat.Video -> "VIDEO"
        MediaFormat.Ico -> "ICO"
        MediaFormat.Avif -> "AVIF"
        MediaFormat.JpegXl -> "JXL"
        MediaFormat.Tiff -> "TIFF"
        MediaFormat.Psd -> "PSD"
        MediaFormat.Psb -> "PSB"
        MediaFormat.Exr -> "EXR"
        MediaFormat.Hdr -> "HDR"
        MediaFormat.Pnm -> "PNM"
        MediaFormat.Jp2 -> "JP2"
        MediaFormat.Jxr -> "JXR"
        MediaFormat.Qoi -> "QOI"
        MediaFormat.Dds -> "DDS"
        MediaFormat.Ktx -> "KTX"
        MediaFormat.Icns -> "ICNS"
        MediaFormat.Pcx -> "PCX"
        MediaFormat.Xcf -> "XCF"
        MediaFormat.Apng -> "APNG"
        MediaFormat.Ora -> "ORA"
        MediaFormat.Kra -> "KRA"
        MediaFormat.Other -> "FILE"
    }

    /** A name for a sentence: "CR3 raw photo", "JPEG XL image", "file". */
    fun kind(format: MediaFormat): String = when (format) {
        is MediaFormat.Raw -> "${format.variant.extension.uppercase()} raw photo"
        MediaFormat.Video -> "video"
        MediaFormat.Other -> "file"
        MediaFormat.JpegXl -> "JPEG XL image"
        else -> "${badge(format)} image"
    }
}

/**
 * Decides what a tile draws. Pure, so the whole table is a JVM test.
 *
 *  - A protected item never reaches the image loader here: it is a placeholder that names nothing
 *    about the file (a locked photo shows no thumbnail, and no format badge either).
 *  - A verdict of [DecoderVerdict.NotDecodable] is a placeholder without trying the loader at all,
 *    which also saves a decode that would only fail.
 *  - Otherwise the loader runs: [LoadResult.Pending] and [LoadResult.Loaded] keep showing it,
 *    [LoadResult.Failed] swaps to the placeholder.
 */
fun tileState(
    format: MediaFormat,
    verdict: DecoderVerdict,
    load: LoadResult,
    isProtected: Boolean = false,
): TileState {
    if (isProtected) {
        return TileState.Placeholder(badge = "", reason = FormatCopy.PROTECTED, description = FormatCopy.PROTECTED)
    }
    val reason = when {
        verdict is DecoderVerdict.NotDecodable -> verdict.reason
        load == LoadResult.Failed -> FormatCopy.COULD_NOT_READ
        else -> return TileState.Show
    }
    return TileState.Placeholder(
        badge = FormatLabels.badge(format),
        reason = reason,
        description = "${FormatLabels.kind(format).replaceFirstChar { it.uppercase() }}, can't be shown here.",
    )
}

/**
 * Which format a tile should treat a file as. [MediaFormat.classify] trusts mime and filename and
 * costs nothing; only when it comes back [MediaFormat.Other] (MediaStore had no useful type) is it
 * worth reading the file's first [FormatSniffer.REQUIRED_HEADER_BYTES] bytes, so a grid of
 * ordinary photos never opens a single file for this.
 */
object FormatResolution {

    fun shouldSniff(classified: MediaFormat): Boolean = classified == MediaFormat.Other

    fun resolve(classified: MediaFormat, sniffed: MediaFormat?): MediaFormat =
        if (shouldSniff(classified)) sniffed ?: classified else classified
}
