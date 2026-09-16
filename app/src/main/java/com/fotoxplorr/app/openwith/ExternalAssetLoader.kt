package com.fotoxplorr.app.openwith

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.fotoxplorr.app.audio.AudioAsset
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whatever could be read off an external `content://`/`file://` Uri without owning a MediaStore
 * row for it. Pure data -- [ExternalAssetLoader] is the only thing that populates one, by
 * querying [OpenableColumns], decoding [BitmapFactory.Options] bounds and (for video/audio)
 * reading [MediaMetadataRetriever] -- kept apart from that I/O so the mapping into
 * [MediaAsset]/[AudioAsset] can be pinned with plain JUnit.
 */
data class ExternalMediaMetadata(
    val displayName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val durationMillis: Long? = null,
    val lastModifiedMillis: Long? = null,
)

/**
 * A deterministic, negative [MediaId] for an external asset, derived from its Uri.
 *
 * Negative so it can never collide with a real MediaStore row id (always non-negative) -- every
 * place that keys a map or compares ids by value can keep doing so with no special case for "is
 * this actually in the library". Deterministic (not random) so re-opening the same external Uri
 * within one process life reuses the same id rather than minting a new one every time.
 */
fun syntheticNegativeMediaId(uri: String): MediaId {
    val hash = uri.hashCode().toLong()
    // Int.MIN_VALUE.toLong() is a positive number once negated by plain unary minus (its
    // magnitude does not fit in a positive Int), so abs() on the Int first, THEN widen to Long,
    // sidesteps that overflow entirely.
    val magnitude = abs(if (hash == Int.MIN_VALUE.toLong()) 0 else hash)
    return MediaId(-(magnitude + 1))
}

/** [uri] as a [MediaAsset], filling in whatever [metadata] could not answer with an honest
 *  "unknown" rather than a guess: zero for a missing dimension/size, the Uri's own last path
 *  segment for a missing name. */
fun externalMediaAsset(uri: String, metadata: ExternalMediaMetadata, isVideo: Boolean): MediaAsset {
    val modified = metadata.lastModifiedMillis ?: 0L
    return MediaAsset(
        id = syntheticNegativeMediaId(uri),
        contentUriString = uri,
        displayName = metadata.displayName?.takeIf { it.isNotBlank() } ?: fallbackName(uri, isVideo),
        mimeType = metadata.mimeType ?: if (isVideo) "video/*" else "image/*",
        bucketName = null,
        bucketId = null,
        dateTakenMillis = modified,
        dateModifiedSeconds = modified / 1000,
        width = metadata.widthPx ?: 0,
        height = metadata.heightPx ?: 0,
        sizeBytes = metadata.sizeBytes ?: 0L,
        durationMillis = metadata.durationMillis ?: 0L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
        isExternal = true,
    )
}

/** [uri] as an [AudioAsset], the audio counterpart of [externalMediaAsset]. */
fun externalAudioAsset(uri: String, metadata: ExternalMediaMetadata): AudioAsset {
    val modified = metadata.lastModifiedMillis ?: 0L
    val name = metadata.displayName?.takeIf { it.isNotBlank() } ?: fallbackName(uri, isVideo = false)
    return AudioAsset(
        id = syntheticNegativeMediaId(uri),
        contentUriString = uri,
        displayName = name,
        title = name,
        artist = null,
        album = null,
        mimeType = metadata.mimeType ?: "audio/*",
        durationMillis = metadata.durationMillis ?: 0L,
        sizeBytes = metadata.sizeBytes ?: 0L,
        dateAddedSeconds = modified / 1000,
        dateModifiedSeconds = modified / 1000,
    )
}

/**
 * A plain-string equivalent of `Uri.parse(uri).lastPathSegment`, deliberately not using
 * [android.net.Uri] itself: this function backs [externalMediaAsset]/[externalAudioAsset], which
 * this file keeps pure and Android-free on purpose so their mapping is pinnable with plain JUnit,
 * with no Robolectric needed just to call `Uri.parse`.
 */
private fun fallbackName(uri: String, isVideo: Boolean): String =
    uri.substringBefore('?').substringBefore('#').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: if (isVideo) "Video" else "Photo"

/**
 * Reads [ExternalMediaMetadata] off a `content://`/`file://` Uri this app was handed rather than
 * one it scanned itself, and turns it into a [MediaAsset] or [AudioAsset].
 *
 * `MediaMetadataRetriever` is released with `.release()`, not `.close()` -- `AutoCloseable` on
 * that class only arrived in API 29, and this app's minSdk is 26.
 */
class ExternalAssetLoader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun loadImage(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        val bounds = imageBounds(uri)
        val metadata = readOpenableMetadata(uri).copy(widthPx = bounds?.first, heightPx = bounds?.second)
        externalMediaAsset(uri.toString(), metadata, isVideo = false)
    }

    suspend fun loadVideo(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        val retrieverMetadata = readVideoMetadata(uri)
        val metadata = readOpenableMetadata(uri).let {
            it.copy(
                widthPx = retrieverMetadata.widthPx ?: it.widthPx,
                heightPx = retrieverMetadata.heightPx ?: it.heightPx,
                durationMillis = retrieverMetadata.durationMillis,
            )
        }
        externalMediaAsset(uri.toString(), metadata, isVideo = true)
    }

    suspend fun loadAudio(uri: Uri): AudioAsset = withContext(Dispatchers.IO) {
        val retrieverMetadata = readVideoMetadata(uri)
        val metadata = readOpenableMetadata(uri).copy(durationMillis = retrieverMetadata.durationMillis)
        externalAudioAsset(uri.toString(), metadata)
    }

    private fun readOpenableMetadata(uri: Uri): ExternalMediaMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        displayName = cursor.getString(it)
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                        if (!cursor.isNull(it)) sizeBytes = cursor.getLong(it)
                    }
                }
            }
        }
        val mimeType = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        return ExternalMediaMetadata(displayName = displayName, mimeType = mimeType, sizeBytes = sizeBytes)
    }

    private fun imageBounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        options.outWidth.takeIf { it > 0 }?.let { it to options.outHeight }
    }.getOrNull()

    private fun readVideoMetadata(uri: Uri): ExternalMediaMetadata = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ExternalMediaMetadata(widthPx = width, heightPx = height, durationMillis = duration)
        } finally {
            // Not .close(): AutoCloseable on MediaMetadataRetriever is API 29+, below this app's
            // minSdk of 26.
            retriever.release()
        }
    }.getOrDefault(ExternalMediaMetadata())
}
