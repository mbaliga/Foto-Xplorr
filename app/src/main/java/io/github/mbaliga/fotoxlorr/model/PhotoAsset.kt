package io.github.mbaliga.fotoxlorr.model

import android.net.Uri

data class PhotoAsset(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateTakenMillis: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val relativePath: String?,
) {
    val isAnimated: Boolean
        get() = mimeType == "image/gif" || mimeType == "image/webp"
}
