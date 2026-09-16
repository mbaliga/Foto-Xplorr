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
    /**
     * True for an asset handed over by another app (open-with/share-to) rather than found by
     * this app's own library scan -- see `com.fotoxplorr.app.openwith.ExternalAssetLoader`.
     *
     * A defaulted trailing field rather than a wrapper type: this class already reaches roughly
     * eighty call sites across the app, and every one of them that never sees an external asset
     * (which is most of them -- an external asset is opened directly into the viewer or editor,
     * never scanned into the library, favourited, trashed or filed into a collection) keeps
     * working unchanged. The few call sites that DO need to know -- the viewer and its actions
     * room -- read this to hide the actions that make no sense on a file this app does not own a
     * library record for.
     */
    val isExternal: Boolean = false,
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
