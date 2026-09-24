package com.fotoxplorr.app.media

import android.net.Uri
import com.fotoxplorr.app.formats.MediaFormat

@JvmInline
value class MediaId(val value: Long) {
    companion object {
        /**
         * The id every ad-hoc asset gets: one built from an incoming `ACTION_VIEW` intent
         * (P0-18's `viewer.ExternalViewerActivity`) that the catalogue never scanned and never
         * will, since this app must never write anything about it to any store. Every REAL id
         * comes from MediaStore's own `_ID` column, which is never negative, so a negative id
         * already reads as "not really catalogued" everywhere this app checks one --
         * `favorites.FavoriteIdCodec` filters negative ids on both encode and decode for exactly
         * this reason. `Long.MIN_VALUE` specifically, not `-1`: `FotoXplorrActivity`'s own
         * `NO_MEDIA_ID = -1L` already means something else ("no selection") and the two must
         * never collide.
         */
        val EXTERNAL: MediaId = MediaId(Long.MIN_VALUE)
    }
}

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
