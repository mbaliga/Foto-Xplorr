package com.fotoxplorr.app.media

import android.net.Uri

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

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}
