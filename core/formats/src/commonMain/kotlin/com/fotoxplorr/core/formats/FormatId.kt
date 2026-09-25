package com.fotoxplorr.core.formats

import com.fotoxplorr.core.model.FormatId

/**
 * ADR-011 §2: `asset.format_id`'s stable string form of [MediaFormat]. Each id is permanent once
 * shipped -- it's a stored database value, not a display string -- so an added [MediaFormat] case
 * needs a new, never-reused id here, not a renamed existing one.
 */
val MediaFormat.formatId: FormatId
    get() = FormatId(
        when (this) {
            MediaFormat.Jpeg -> "jpeg"
            MediaFormat.Png -> "png"
            MediaFormat.Bmp -> "bmp"
            MediaFormat.WebP -> "webp"
            MediaFormat.Gif -> "gif"
            MediaFormat.Heif -> "heif"
            MediaFormat.Svg -> "svg"
            is MediaFormat.Raw -> "raw:${variant.extension}"
            MediaFormat.Video -> "video"
            MediaFormat.Ico -> "ico"
            MediaFormat.Avif -> "avif"
            MediaFormat.JpegXl -> "jxl"
            MediaFormat.Tiff -> "tiff"
            MediaFormat.Psd -> "psd"
            MediaFormat.Exr -> "exr"
            MediaFormat.Hdr -> "hdr"
            MediaFormat.Pnm -> "pnm"
            MediaFormat.Jp2 -> "jp2"
            MediaFormat.Jxr -> "jxr"
            MediaFormat.Qoi -> "qoi"
            MediaFormat.Dds -> "dds"
            MediaFormat.Ktx -> "ktx"
            MediaFormat.Icns -> "icns"
            MediaFormat.Pcx -> "pcx"
            MediaFormat.Xcf -> "xcf"
            MediaFormat.Other -> "other"
        },
    )
