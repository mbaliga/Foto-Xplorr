package com.fotoxplorr.app.media

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.fotoxplorr.app.formats.SVG_MIME_TYPE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AndroidMediaStoreScanner(
    private val resolver: ContentResolver,
) : MediaScanner {

    override fun scan(plan: ScanPlan): Flow<ScanEvent> = flow {
        emit(ScanEvent.Started(SOURCE_NAME))

        try {
            queryMedia(plan)?.use { cursor ->
                val columns = CursorColumns(cursor)
                val discovered = cursor.count
                var scanned = 0
                var newestModified = 0L

                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val asset = columns.toAsset(cursor)
                    if (asset.dateModifiedSeconds > newestModified) {
                        newestModified = asset.dateModifiedSeconds
                    }
                    emit(ScanEvent.AssetFound(asset))
                    scanned += 1

                    if (scanned == discovered || scanned % PROGRESS_INTERVAL == 0) {
                        emit(ScanEvent.Progress(scanned = scanned, discovered = discovered))
                    }
                }

                emit(
                    ScanEvent.Completed(
                        total = scanned,
                        plan = plan,
                        newestModifiedSeconds = newestModified.takeIf { it > 0L },
                    ),
                )
            } ?: emit(ScanEvent.Completed(total = 0, plan = plan, newestModifiedSeconds = null))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(ScanEvent.Failed(error))
        }
    }.flowOn(Dispatchers.IO)

    private fun queryMedia(plan: ScanPlan): Cursor? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val query = buildSelection(plan)
        val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.clause)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.args.toTypedArray())
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection(), queryArgs, null)
        } else {
            resolver.query(collection, projection(), query.clause, query.args.toTypedArray(), sortOrder)
        }
    }

    private fun projection(): Array<String> = buildList {
        add(MediaStore.MediaColumns._ID)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.Images.ImageColumns.DATE_TAKEN)
        add(MediaStore.MediaColumns.DATE_MODIFIED)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.Video.VideoColumns.DURATION)
        add(MediaStore.Images.ImageColumns.BUCKET_ID)
        add(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.MediaColumns.IS_FAVORITE)
            add(MediaStore.MediaColumns.IS_TRASHED)
        }
    }.toTypedArray()

    private class CursorColumns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        private val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        private val displayName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        private val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
        private val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        private val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        private val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val duration = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
        private val bucketId = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)
        private val bucketName = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
        private val relativePath = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        private val favorite = cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
        private val trashed = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)

        fun toAsset(cursor: Cursor): MediaAsset {
            val rawId = cursor.getLong(id)
            val type = cursor.getInt(mediaType)
            val modifiedSeconds = cursor.longOrZero(dateModified)
            val takenMillis = cursor.longOrZero(dateTaken).takeIf { it > 0L }
                ?: modifiedSeconds * 1_000L
            return MediaAsset(
                id = MediaId(rawId),
                contentUriString = ContentUris.withAppendedId(baseUri(type), rawId).toString(),
                displayName = cursor.getString(displayName).orEmpty(),
                mimeType = cursor.getString(mimeType).orEmpty(),
                bucketName = cursor.stringOrNull(bucketName),
                bucketId = cursor.longOrNull(bucketId),
                dateTakenMillis = takenMillis,
                dateModifiedSeconds = modifiedSeconds,
                width = cursor.intOrZero(width),
                height = cursor.intOrZero(height),
                sizeBytes = cursor.longOrZero(size),
                durationMillis = cursor.longOrZero(duration),
                relativePath = cursor.stringOrNull(relativePath),
                isFavorite = cursor.booleanOrFalse(favorite),
                isTrashed = cursor.booleanOrFalse(trashed),
            )
        }

        private fun baseUri(type: Int): Uri = when (type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            // Everything else -- in practice today, only the SVG rows the selection below newly
            // admits, which carry MEDIA_TYPE_NONE -- lands here. Images.Media.EXTERNAL_CONTENT_URI
            // is a VIEW filtered to media_type=image; a NONE-typed row's id does not resolve
            // through it at all, so building "images/media/<id>" for an SVG would produce a Uri
            // that 404s on openInputStream. That is a WORSE bug than never indexing the file: the
            // asset would appear in the library with a permanently broken thumbnail instead of
            // just not appearing. The raw Files collection has no such type filter -- every row
            // this query can see is openable through it -- so route anything non-image/video there.
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
    }

    private companion object {
        const val SOURCE_NAME = "MediaStore.Files(images+videos)"
        const val PROGRESS_INTERVAL = 64
    }
}

/** A selection clause plus its positional `?` arguments, kept together so the two can never
 *  drift out of sync with each other on the way to `ContentResolver.query`. */
internal data class SelectionQuery(val clause: String, val args: List<String>)

/**
 * The query selection for [AndroidMediaStoreScanner.scan] -- pulled out to a plain function
 * (rather than left inline in `queryMedia`) specifically so it is unit-testable without a real
 * `ContentResolver`: it only builds strings from `MediaStore`'s own constants, which are plain
 * compile-time-inlined `int`/`String` fields and need no Android runtime to read.
 *
 * ## Why filtering on `MEDIA_TYPE` alone misses SVG
 *
 * `MediaStore.Files.FileColumns.MEDIA_TYPE` buckets each row into IMAGE/VIDEO/AUDIO/NONE from
 * an explicit per-mime allowlist maintained by the platform's media scanner -- NOT simply by
 * checking whether the mime string starts with "image/". SVG is deliberately left off that
 * allowlist (it is an XML/vector document, not a raster photo the Photos-style pickers this
 * bucketing exists for are built around), so every SVG on the device is classified
 * `MEDIA_TYPE_NONE` regardless of its very-much-"image/svg+xml" mime type. The original query
 * here (`MEDIA_TYPE=IMAGE OR MEDIA_TYPE=VIDEO`) therefore did not just thumbnail SVGs badly --
 * it excluded every SVG row from the result set entirely, so no SVG ever reached this app's
 * index in the first place.
 *
 * The fix adds a second clause admitting rows whose [MediaStore.MediaColumns.MIME_TYPE] is
 * exactly `image/svg+xml`, or (defensively, for the rarer provider that leaves `MIME_TYPE`
 * null/blank for a row it doesn't recognise) whose display name ends in literally `.svg`. Both
 * arms are scoped to that one, exact case -- nothing else living in `MEDIA_TYPE_NONE` (PDFs,
 * .txt notes, arbitrary app-private files MediaStore happens to have indexed) matches either
 * arm, so this cannot sweep in unrelated non-media files.
 */
internal fun buildSelection(plan: ScanPlan): SelectionQuery {
    val mediaTypeClause =
        "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)"
    val svgClause =
        "(${MediaStore.MediaColumns.MIME_TYPE}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
    val selectionClause = "($mediaTypeClause OR $svgClause)"
    val baseArgs = listOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        SVG_MIME_TYPE,
        "%.svg",
    )

    // A delta narrows to rows touched since the (rewound) watermark. `>=` rather than `>` is
    // deliberate — see ScanPlan's note on second-granular timestamps.
    val clause = when (plan) {
        is ScanPlan.Full -> selectionClause
        is ScanPlan.Delta -> "$selectionClause AND ${MediaStore.MediaColumns.DATE_MODIFIED}>=?"
    }
    val args = when (plan) {
        is ScanPlan.Full -> baseArgs
        is ScanPlan.Delta -> baseArgs + plan.sinceSeconds.toString()
    }
    return SelectionQuery(clause, args)
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (index >= 0 && !isNull(index)) getString(index) else null

private fun Cursor.longOrNull(index: Int): Long? =
    if (index >= 0 && !isNull(index)) getLong(index) else null

private fun Cursor.longOrZero(index: Int): Long =
    if (index >= 0 && !isNull(index)) getLong(index) else 0L

private fun Cursor.intOrZero(index: Int): Int =
    if (index >= 0 && !isNull(index)) getInt(index) else 0

private fun Cursor.booleanOrFalse(index: Int): Boolean =
    index >= 0 && !isNull(index) && getInt(index) != 0
