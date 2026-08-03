package com.fotoxplorr.app.media

import android.net.Uri

@JvmInline
value class MediaId(val value: Long)

data class MediaAsset(
    val id: MediaId,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val bucketName: String?,
    val dateTakenMillis: Long,
    val dateModifiedSeconds: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val relativePath: String?,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
)
