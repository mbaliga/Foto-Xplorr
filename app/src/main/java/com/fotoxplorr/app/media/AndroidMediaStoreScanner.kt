package com.fotoxplorr.app.media

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

    override fun scan(): Flow<ScanEvent> = flow {
        emit(ScanEvent.Started(SOURCE_NAME))

        try {
            queryMedia()?.use { cursor ->
                val columns = CursorColumns(cursor)
                val discovered = cursor.count
                var scanned = 0

                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    emit(ScanEvent.AssetFound(columns.toAsset(cursor)))
                    scanned += 1

                    if (scanned == discovered || scanned % PROGRESS_INTERVAL == 0) {
                        emit(ScanEvent.Progress(scanned = scanned, discovered = discovered))
                    }
                }

                emit(ScanEvent.Completed(total = scanned))
            } ?: emit(ScanEvent.Completed(total = 0))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(ScanEvent.Failed(error))
        }
    }.flowOn(Dispatchers.IO)

    private fun queryMedia(): Cursor? {
        val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection(),
                args,
                null,
            )
        } else {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection(),
                null,
                null,
                sortOrder,
            )
        }
    }

    private fun projection(): Array<String> = buildList {
        add(MediaStore.MediaColumns._ID)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.Images.ImageColumns.DATE_TAKEN)
        add(MediaStore.MediaColumns.DATE_MODIFIED)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        add(MediaStore.MediaColumns.SIZE)
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
        private val displayName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        private val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
        private val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        private val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        private val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val bucketId = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)
        private val bucketName = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
        private val relativePath = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        private val favorite = cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
        private val trashed = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)

        fun toAsset(cursor: Cursor): MediaAsset {
            val rawId = cursor.getLong(id)
            return MediaAsset(
                id = MediaId(rawId),
                contentUriString = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    rawId,
                ).toString(),
                displayName = cursor.getString(displayName).orEmpty(),
                mimeType = cursor.getString(mimeType).orEmpty(),
                bucketName = cursor.stringOrNull(bucketName),
                bucketId = cursor.longOrNull(bucketId),
                dateTakenMillis = cursor.longOrZero(dateTaken),
                dateModifiedSeconds = cursor.longOrZero(dateModified),
                width = cursor.intOrZero(width),
                height = cursor.intOrZero(height),
                sizeBytes = cursor.longOrZero(size),
                relativePath = cursor.stringOrNull(relativePath),
                isFavorite = cursor.booleanOrFalse(favorite),
                isTrashed = cursor.booleanOrFalse(trashed),
            )
        }
    }

    private companion object {
        const val SOURCE_NAME = "MediaStore.Images"
        const val PROGRESS_INTERVAL = 64
    }
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
