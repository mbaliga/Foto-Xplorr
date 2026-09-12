package com.fotoxplorr.app.media

import android.net.Uri
import com.fotoxplorr.app.formats.MediaFormat

@JvmInline
value class MediaId(val value: Long)

data class MediaAsset(
    val id: MediaId,
    val contentUriString: String,
    val displayName: String,
    val mimeType: String,
    val bucketName: String?,
    val bucketId: Long? = null,
    val dateTakenMillis: Long,
    val dateModifiedSeconds: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val durationMillis: Long = 0L,
    val relativePath: String?,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
) {
    val contentUri: Uri
        get() = Uri.parse(contentUriString)

    val isVideo: Boolean
        get() = mimeType.startsWith("video/", ignoreCase = true)

    val isAnimated: Boolean
        get() = mimeType.equals("image/gif", ignoreCase = true) ||
            mimeType.equals("image/webp", ignoreCase = true) ||
            mimeType.equals("image/avif", ignoreCase = true)

    /**
     * What this file actually is -- RAW variant, SVG, GIF, HEIF, and so on -- and whether the
     * platform can be expected to decode it. Computed on read from [mimeType]/[displayName]
     * rather than stored: see [MediaFormat] for why that is the deliberate choice, not an
     * oversight.
     */
    val format: MediaFormat
        get() = MediaFormat.classify(mimeType = mimeType, fileName = displayName)

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}
