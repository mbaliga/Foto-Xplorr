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
            MediaFormat.Other -> "other"
        },
    )
