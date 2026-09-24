package com.fotoxplorr.app.index

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.fotoxplorr.core.formats.SVG_MIME_TYPE
import com.fotoxplorr.core.index.Source
import com.fotoxplorr.core.index.SourceCapabilities
import com.fotoxplorr.core.index.SourceEvent
import com.fotoxplorr.core.index.SourceHandle
import com.fotoxplorr.core.index.SourceItem
import com.fotoxplorr.core.index.SyncToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * WP1.4's real [Source]: one MediaStore volume (`volumeName`, one of `MediaStore.
 * getExternalVolumeNames()`, or the pre-Q legacy fallback `"external"` -- see [AndroidMediaSync]
 * for how that fallback is picked, matching `MigrationToV2.primarySourceId()` exactly so both
 * land on the same [com.fotoxplorr.core.db.entity.SourceEntity] row).
 *
 * Generation capability is API-gated on real, verified platform history (not guessed): `MediaStore
 * .getExternalVolumeNames`/`getVersion(Context, String)` since API 29 (Q); `MediaStore.
 * getGeneration(Context, String)` and `MediaColumns.GENERATION_MODIFIED` since API 30 (R). Below
 * API 30 there is no generation signal at all, so [enumerate] always does a full pass there --
 * [com.fotoxplorr.core.index.SyncToken.generation] stays null forever on those devices, which is
 * exactly what forces that every time (see `SyncEngine.sync`'s own `since` derivation).
 *
 * Deliberately does NOT reuse [com.fotoxplorr.app.media.AndroidMediaStoreScanner]'s
 * `buildSelection` (package-`internal`, but shaped around `ScanPlan`'s `DATE_MODIFIED`-bounded
 * delta, not a `GENERATION_MODIFIED` bound) -- the media-type/SVG clause fragment below is
 * duplicated from it on purpose rather than forcing a shared abstraction across two engines that
 * coexist only for the rest of Phase 1: WP1.5 deletes `MediaIndexer`/`AndroidMediaStoreScanner`/
 * `ScanPlan` outright (ADR-011 Consequences), at which point this becomes the only copy.
 */
class AndroidMediaStoreSource(
    private val appContext: Context,
    private val volumeName: String,
) : Source {

    override val capabilities = SourceCapabilities(canOpen = true, canThumbnail = false)

    override fun enumerate(since: SyncToken?): Flow<SourceEvent> = flow {
        try {
            val startVersion = currentVersion()
            val startGeneration = currentGeneration()
            // A version mismatch means whatever generation number `since` remembers is from
            // before a reset and is not comparable to this volume's current one -- MASTER-PLAN.md
            // §2.3: "MediaStore uses per-volume getGeneration/GENERATION_MODIFIED and getVersion
            // for resets". `startGeneration == null` covers every API < 30 device (see class doc)
            // and the (defensive, shouldn't happen once `since` is non-null) case of a volume that
            // stopped reporting a generation.
            val full = since == null || since.version != startVersion || since.generation == null || startGeneration == null

            queryMedia(minGeneration = if (full) null else since?.generation)?.use { cursor ->
                val columns = CursorColumns(cursor)
                val discovered = cursor.count
                var scanned = 0
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    emit(SourceEvent.ItemFound(columns.toItem(cursor)))
                    scanned += 1
                    if (scanned == discovered || scanned % PROGRESS_INTERVAL == 0) {
                        emit(SourceEvent.Progress(scanned))
                    }
                }
            }

            emit(SourceEvent.Completed(SyncToken(startVersion, startGeneration), fullyEnumerated = full))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(SourceEvent.Failed(error))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun open(locator: String): SourceHandle =
        SourceHandle(ContentUris.withAppendedId(MediaStore.Files.getContentUri(volumeName), locator.toLong()).toString())

    /** [SourceCapabilities.canThumbnail] is false -- there is no cheaper-than-[open] path here
     *  yet (`ContentResolver#loadThumbnail` is a real candidate, not built in this increment). */
    override suspend fun thumbnail(locator: String, size: Int): SourceHandle? = null

    private fun currentVersion(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getVersion(appContext, volumeName) else null

    private fun currentGeneration(): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(appContext, volumeName) else null

    private fun queryMedia(minGeneration: Long?): Cursor? {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri(volumeName)
        val query = buildSelection(minGeneration)
        val sortOrder = "${MediaStore.MediaColumns._ID} ASC"

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
        add(MediaStore.MediaColumns.DATE_ADDED)
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
            add(MediaStore.MediaColumns.IS_TRASHED)
            add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }
    }.toTypedArray()

    private inner class CursorColumns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        private val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        private val displayName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        private val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        private val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
        private val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        private val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        private val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        private val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        private val duration = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
        private val bucketId = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)
        private val bucketName = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
        private val relativePath = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        private val trashed = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
        private val generationModified = cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED)

        fun toItem(cursor: Cursor): SourceItem {
            val rawId = cursor.getLong(id)
            val type = cursor.getInt(mediaType)
            val modifiedSeconds = cursor.longOrZero(dateModified)
            val modifiedMs = modifiedSeconds * 1_000L
            val takenMillis = cursor.longOrZero(dateTaken).takeIf { it > 0L } ?: modifiedMs
            return SourceItem(
                locator = rawId.toString(),
                displayName = cursor.getString(displayName).orEmpty(),
                mime = cursor.getString(mimeType).orEmpty(),
                sizeBytes = cursor.longOrZero(size),
                width = cursor.intOrZero(width),
                height = cursor.intOrZero(height),
                durationMs = cursor.longOrZero(duration),
                dateTakenMs = takenMillis,
                dateModifiedMs = modifiedMs,
                dateAddedMs = cursor.longOrNull(dateAdded)?.let { it * 1_000L },
                relativePath = cursor.stringOrNull(relativePath),
                bucketId = cursor.longOrNull(bucketId),
                bucketName = cursor.stringOrNull(bucketName),
                contentUri = ContentUris.withAppendedId(baseUri(type), rawId).toString(),
                trashed = cursor.booleanOrFalse(trashed),
                generationModified = cursor.longOrNull(generationModified),
            )
        }

        // Same reasoning as AndroidMediaStoreScanner.CursorColumns.baseUri: only IMAGE/VIDEO
        // resolve through their own typed EXTERNAL_CONTENT_URI; everything else (in practice,
        // the SVG rows buildSelection's SVG clause newly admits, MEDIA_TYPE_NONE) must go through
        // the untyped Files collection or the built Uri 404s.
        private fun baseUri(type: Int): Uri = when (type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volumeName)
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volumeName)
            else -> MediaStore.Files.getContentUri(volumeName)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL = 64
    }
}

internal data class SelectionQuery(val clause: String, val args: List<String>)

/** The same media-type-plus-SVG semantics as [com.fotoxplorr.app.media.buildSelection] (see this
 *  file's own class doc for why it's a deliberate, temporary duplicate rather than a shared
 *  function), bounded by [MediaStore.MediaColumns.GENERATION_MODIFIED] instead of `DATE_MODIFIED`
 *  when [minGeneration] is non-null. `internal` (not `private`), matching that same function, so
 *  it's unit-testable without a real `ContentResolver` -- see `AndroidMediaStoreSourceSelectionTest`. */
internal fun buildSelection(minGeneration: Long?): SelectionQuery {
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
    return if (minGeneration == null) {
        SelectionQuery(selectionClause, baseArgs)
    } else {
        SelectionQuery(
            "$selectionClause AND ${MediaStore.MediaColumns.GENERATION_MODIFIED}>?",
            baseArgs + minGeneration.toString(),
        )
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
