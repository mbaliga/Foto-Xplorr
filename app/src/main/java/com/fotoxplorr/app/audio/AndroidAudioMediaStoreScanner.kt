package com.fotoxplorr.app.audio

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.ScanPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * [com.fotoxplorr.app.media.AndroidMediaStoreScanner]'s exact shape and progress-event cadence,
 * querying `MediaStore.Audio.Media` directly rather than the unified `MediaStore.Files` collection
 * the photo/video scanner uses. Audio has none of that scanner's SVG-shaped bucketing problem —
 * every real audio file MediaStore knows about already carries `MEDIA_TYPE_AUDIO` correctly — so
 * there is no equivalent reason to route through `Files` here, and going straight to
 * `MediaStore.Audio.Media` is both simpler and the only way to reach audio-specific columns
 * (`ARTIST`, `ALBUM`, `TITLE`) the `Files` collection's generic projection never carries.
 */
class AndroidAudioMediaStoreScanner(
    private val resolver: ContentResolver,
) : AudioScanner {

    override fun scan(plan: ScanPlan): Flow<AudioScanEvent> = flow {
        emit(AudioScanEvent.Started(SOURCE_NAME))

        try {
            queryAudio(plan)?.use { cursor ->
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
                    emit(AudioScanEvent.AssetFound(asset))
                    scanned += 1

                    if (scanned == discovered || scanned % PROGRESS_INTERVAL == 0) {
                        emit(AudioScanEvent.Progress(scanned = scanned, discovered = discovered))
                    }
                }

                emit(
                    AudioScanEvent.Completed(
                        total = scanned,
                        plan = plan,
                        newestModifiedSeconds = newestModified.takeIf { it > 0L },
                    ),
                )
            } ?: emit(AudioScanEvent.Completed(total = 0, plan = plan, newestModifiedSeconds = null))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(AudioScanEvent.Failed(error))
        }
    }.flowOn(Dispatchers.IO)

    private fun queryAudio(plan: ScanPlan): Cursor? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val query = buildAudioSelection(plan)
        val sortOrder = "${MediaStore.Audio.AudioColumns.DATE_ADDED} DESC"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.clause)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.args.toTypedArray())
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }
            resolver.query(collection, projection(), queryArgs, null)
        } else {
            resolver.query(collection, projection(), query.clause, query.args.toTypedArray(), sortOrder)
        }
    }

    private fun projection(): Array<String> = arrayOf(
        MediaStore.Audio.AudioColumns._ID,
        MediaStore.Audio.AudioColumns.DISPLAY_NAME,
        MediaStore.Audio.AudioColumns.TITLE,
        MediaStore.Audio.AudioColumns.ARTIST,
        MediaStore.Audio.AudioColumns.ALBUM,
        MediaStore.Audio.AudioColumns.ALBUM_ID,
        MediaStore.Audio.AudioColumns.TRACK,
        MediaStore.Audio.AudioColumns.MIME_TYPE,
        MediaStore.Audio.AudioColumns.DURATION,
        MediaStore.Audio.AudioColumns.SIZE,
        MediaStore.Audio.AudioColumns.DATE_ADDED,
        MediaStore.Audio.AudioColumns.DATE_MODIFIED,
    )

    private class CursorColumns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns._ID)
        private val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DISPLAY_NAME)
        private val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TITLE)
        private val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)
        private val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM)
        private val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM_ID)
        private val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK)
        private val mimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.MIME_TYPE)
        private val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.SIZE)
        private val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATE_ADDED)
        private val dateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATE_MODIFIED)

        fun toAsset(cursor: Cursor): AudioAsset {
            val rawId = cursor.getLong(id)
            val displayNameValue = cursor.stringOrNull(displayName).orEmpty()
            // MediaStore's own TITLE is often exactly the display name minus its extension for a
            // file with no embedded tags -- but for one MediaStore genuinely could not read at
            // all (a corrupt tag block), TITLE can come back blank while DISPLAY_NAME cannot, so
            // the fallback is the file name, never an empty row a list would render as nothing.
            val titleValue = cursor.stringOrNull(title)?.takeIf(String::isNotBlank) ?: displayNameValue
            return AudioAsset(
                id = MediaId(rawId),
                contentUriString = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rawId).toString(),
                displayName = displayNameValue,
                title = titleValue,
                artist = cursor.stringOrNull(artist)?.takeIf(String::isNotBlank),
                album = cursor.stringOrNull(album)?.takeIf(String::isNotBlank),
                mimeType = cursor.stringOrNull(mimeType).orEmpty(),
                durationMillis = cursor.longOrZero(duration),
                sizeBytes = cursor.longOrZero(size),
                dateAddedSeconds = cursor.longOrZero(dateAdded),
                dateModifiedSeconds = cursor.longOrZero(dateModified),
                albumId = cursor.longOrNull(albumId),
                trackNumber = trackNumberFrom(cursor.longOrNull(track)),
            )
        }
    }

    private companion object {
        const val SOURCE_NAME = "MediaStore.Audio"
        const val PROGRESS_INTERVAL = 64
    }
}

/**
 * The query selection for [AndroidAudioMediaStoreScanner.scan] — pulled out to a plain function,
 * same reasoning as [com.fotoxplorr.app.media.buildSelection]: it only builds strings from
 * `MediaStore`'s own compile-time-inlined constants, so it needs no real `ContentResolver` (or
 * Robolectric) to test.
 *
 * Excludes ringtones, alarms and notification sounds by name (`IS_RINGTONE=0 AND IS_ALARM=0 AND
 * IS_NOTIFICATION=0`) rather than requiring `IS_MUSIC!=0` — the filter this replaced. `IS_MUSIC`
 * is MediaStore's own heuristic for "long enough and not a system sound to count as a song", and
 * it silently drops exactly the rows this app's audio destination is supposed to show: voice
 * memos, podcast episodes and audiobook chapters MediaStore does not consider "music" but which
 * are still real personal recordings someone would open this app to find. The three ringtone-
 * shaped booleans are the actual thing worth excluding, and are real columns since the same first
 * MediaStore audio table `IS_MUSIC` is, so this applies identically across every API level this
 * app supports.
 */
internal fun buildAudioSelection(plan: ScanPlan): AudioSelectionQuery {
    val notARingtoneClause = "${MediaStore.Audio.AudioColumns.IS_RINGTONE}=0" +
        " AND ${MediaStore.Audio.AudioColumns.IS_ALARM}=0" +
        " AND ${MediaStore.Audio.AudioColumns.IS_NOTIFICATION}=0"
    val clause = when (plan) {
        is ScanPlan.Full -> notARingtoneClause
        is ScanPlan.Delta -> "$notARingtoneClause AND ${MediaStore.Audio.AudioColumns.DATE_MODIFIED}>=?"
    }
    val args = when (plan) {
        is ScanPlan.Full -> emptyList()
        is ScanPlan.Delta -> listOf(plan.sinceSeconds.toString())
    }
    return AudioSelectionQuery(clause, args)
}

/** [com.fotoxplorr.app.media.SelectionQuery]'s exact shape, for the audio query. */
internal data class AudioSelectionQuery(val clause: String, val args: List<String>)

/**
 * MediaStore's `TRACK` column packs a disc number into the value as `discNumber * 1000 + track`
 * once a file's tags carry a disc number at all (e.g. disc 2 track 3 reads back as `2003`) — see
 * the column's own platform documentation. Every reader of [AudioAsset.trackNumber] wants the
 * plain in-album track position, not that packed number, so this unpacks it once here rather than
 * leaving every call site to rediscover the `% 1000` rule (or worse, not know it exists at all and
 * show "2003" as a track number).
 */
internal fun trackNumberFrom(rawTrack: Long?): Int? = rawTrack?.let { (it % 1000).toInt() }

private fun Cursor.stringOrNull(index: Int): String? =
    if (index >= 0 && !isNull(index)) getString(index) else null

private fun Cursor.longOrZero(index: Int): Long =
    if (index >= 0 && !isNull(index)) getLong(index) else 0L

private fun Cursor.longOrNull(index: Int): Long? =
    if (index >= 0 && !isNull(index)) getLong(index) else null
