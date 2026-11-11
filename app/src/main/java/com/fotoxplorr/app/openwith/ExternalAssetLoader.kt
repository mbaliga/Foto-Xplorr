package com.fotoxplorr.app.openwith

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whatever could be read off an external `content://` Uri without owning a MediaStore
 * row for it. Pure data -- [ExternalAssetLoader] is the only thing that populates one, by
 * querying [OpenableColumns], decoding [BitmapFactory.Options] bounds and (for video)
 * reading [MediaMetadataRetriever] -- kept apart from that I/O so the mapping into
 * [MediaAsset] can be pinned with plain JUnit.
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
 * The first non-null result of [attempts], tried in order and stopping at the first row found; a
 * later attempt never runs once an earlier one has answered, and one that throws counts as empty.
 * How [ExternalAssetLoader.readMediaStoreRow] falls back from the collection query to the row's
 * own Uri, kept apart from the ContentResolver so the order can be pinned with plain JUnit.
 */
internal fun firstRowOf(vararg attempts: () -> MediaAsset?): MediaAsset? {
    for (attempt in attempts) {
        val row = runCatching(attempt).getOrNull()
        if (row != null) return row
    }
    return null
}

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

/**
 * A plain-string equivalent of `Uri.parse(uri).lastPathSegment`, deliberately not using
 * [android.net.Uri] itself: this function backs [externalMediaAsset], which
 * this file keeps pure and Android-free on purpose so their mapping is pinnable with plain JUnit,
 * with no Robolectric needed just to call `Uri.parse`.
 */
private fun fallbackName(uri: String, isVideo: Boolean): String =
    uri.substringBefore('?').substringBefore('#').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: if (isVideo) "Video" else "Photo"

/**
 * Reads [ExternalMediaMetadata] off a `content://` Uri this app was handed rather than
 * one it scanned itself, and turns it into a [MediaAsset].
 *
 * `MediaMetadataRetriever` is released with `.release()`, not `.close()` -- `AutoCloseable` on
 * that class only arrived in API 29, and this app's minSdk is 26.
 */
class ExternalAssetLoader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun loadImage(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        requireContentUri(uri)
        val bounds = imageBounds(uri)
        val metadata = readOpenableMetadata(uri).copy(widthPx = bounds?.first, heightPx = bounds?.second)
        externalMediaAsset(uri.toString(), metadata, isVideo = false)
    }

    suspend fun loadVideo(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        requireContentUri(uri)
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

    /**
     * The stand-in for MediaStore row [rowId], read straight from MediaStore with the same
     * folder columns the library scan reads, or null when MediaStore will not describe it.
     *
     * Used only for a `content://media` Uri the catalogue has not scanned yet, to decide whether
     * the row sits in a locked folder (see [lookupUncataloguedRow]); the result is never shown.
     *
     * A query over the whole collection returns nothing for a row this app has no standing access
     * to (no media permission, or Android 14's selected-photos grant that does not include it)
     * even when the sender attached a per-Uri grant for it, so when that comes back empty the row's
     * own item Uri is asked, which such a grant does cover.
     */
    suspend fun readMediaStoreRow(rowId: Long): MediaAsset? = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        firstRowOf(
            { queryRow(collection, rowId, byId = true) },
            { queryRow(android.content.ContentUris.withAppendedId(collection, rowId), rowId, byId = false) },
        )
    }

    /** One attempt at the row: [byId] filters a collection down to it, an item Uri needs no filter. */
    private fun queryRow(source: Uri, rowId: Long, byId: Boolean): MediaAsset? = runCatching {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.Images.ImageColumns.BUCKET_ID)
            add(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_TRASHED)
        }.toTypedArray()
        val selection = if (byId) "${MediaStore.MediaColumns._ID}=?" else null
        val args = if (byId) arrayOf(rowId.toString()) else null
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                if (selection != null) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                }
                // A trashed row is hidden, not absent: it must come back so it can be refused.
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            appContext.contentResolver.query(source, projection, queryArgs, null)
        } else {
            appContext.contentResolver.query(source, projection, selection, args, null)
        }
        cursor?.use {
            if (!it.moveToFirst()) return@use null
            fun text(column: String) = it.getColumnIndex(column).takeIf { index -> index >= 0 }?.let(it::getString)
            fun number(column: String) = it.getColumnIndex(column).takeIf { index -> index >= 0 && !it.isNull(index) }
                ?.let(it::getLong)
            mediaStoreRowAsset(
                rowId = rowId,
                contentUri = android.content.ContentUris.withAppendedId(collection, rowId).toString(),
                displayName = text(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(),
                mimeType = text(MediaStore.MediaColumns.MIME_TYPE).orEmpty(),
                bucketId = number(MediaStore.Images.ImageColumns.BUCKET_ID),
                bucketName = text(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME),
                relativePath = text(MediaStore.MediaColumns.RELATIVE_PATH),
                isTrashed = number(MediaStore.MediaColumns.IS_TRASHED) == 1L,
            )
        }
    }.getOrNull()

    /** Backstop for [classifyIncomingIntent]'s content-only rule: nothing else may reach the
     *  readers below, which would open whatever path the Uri names with this app's permissions. */
    private fun requireContentUri(uri: Uri) {
        require(uri.scheme.equals("content", ignoreCase = true)) { "Only content:// Uris can be opened" }
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
