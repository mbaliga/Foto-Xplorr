package com.fotoxplorr.app.audio

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [com.fotoxplorr.app.media.SqliteMediaRepository]'s exact shape and Mutex/StateFlow discipline,
 * for [AudioAsset] — the persisted counterpart of [InMemoryAudioRepository] (kept for tests, and
 * for anything still built against the in-memory contract).
 *
 * [InMemoryAudioRepository]'s own doc argued persistence was not worth a schema for a plain
 * `MediaStore.Audio.Media` re-query. This class exists anyway, for background playback: a
 * [com.fotoxplorr.app.playback.MediaSession] auto-advancing between tracks, or a system media
 * notification's "skip" action, can run with no `AudioLibraryScreen` composition on screen (and
 * therefore no [InMemoryAudioRepository] to have populated) at all — a cold [PlaybackService]
 * process needs a queue's [AudioAsset]s available the instant it starts, not after a fresh
 * MediaStore scan finishes. That is a genuinely different requirement from the read-heavy list
 * screen the earlier doc was reasoning about, not a reversal of it.
 *
 * Deliberately NOT [com.fotoxplorr.app.media.mergeIntoSortedCatalogue]'s O(n) linear-merge upsert:
 * that optimisation exists for a library that can reach hundreds of thousands of rows: a music
 * library on a personal device is smaller by orders of magnitude, so a plain rebuild-and-resort on
 * each upsert (matching [InMemoryAudioRepository]'s own ordering logic) costs nothing worth a
 * second, harder-to-verify merge algorithm.
 */
class SqliteAudioRepository(context: Context) : AudioRepository {
    private val helper = AudioCatalogueOpenHelper(context.applicationContext)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<AudioAsset>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // See SqliteMediaRepository.initialLoad / awaitLoaded for why this is a handle kept around,
    // not fire-and-forget: a StateFlow hands a fresh collector its CURRENT value immediately,
    // which for the first moments of this object's life is the empty list it starts with.
    //
    // Guarded by the SAME mutex as every mutation, unlike SqliteMediaRepository's own
    // initialLoad: this coroutine is launched on a separate scope/dispatcher from any caller
    // that immediately follows construction with a replaceAll/upsert, so without the mutex its
    // read-and-assign can interleave BETWEEN two such calls and briefly overwrite a newer write
    // with a stale snapshot. Serialising it against every mutation removes that race entirely --
    // wherever in the sequence this actually runs, `helper.readAll()` only ever executes once
    // every mutation ahead of it in mutex order has already committed, so its snapshot can never
    // be older than `state.value` already is at that point.
    private val initialLoad = scope.launch { mutex.withLock { state.value = helper.readAll() } }

    override fun observeAll(): Flow<List<AudioAsset>> = state.asStateFlow()

    suspend fun awaitLoaded(): List<AudioAsset> {
        initialLoad.join()
        return state.value
    }

    override suspend fun replaceAll(items: List<AudioAsset>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val normalized = normalize(items)
            helper.replaceAll(normalized)
            state.value = normalized
        }
    }

    override suspend fun upsert(items: List<AudioAsset>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        mutex.withLock {
            helper.upsert(items)
            val merged = state.value.associateByTo(linkedMapOf()) { it.id }
            items.forEach { merged[it.id] = it }
            state.value = normalize(merged.values)
        }
    }

    override suspend fun remove(ids: Set<MediaId>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        mutex.withLock {
            helper.remove(ids)
            state.value = state.value.filterNot { it.id in ids }
        }
    }

    override suspend fun count(): Int = state.value.size

    private fun normalize(items: Collection<AudioAsset>): List<AudioAsset> = normalizeAudioCatalogue(items)
}

/** Same ordering as [InMemoryAudioRepository] — title, case-insensitively. Total (ids are unique),
 *  same reasoning as [com.fotoxplorr.app.media.CATALOGUE_ORDER]. */
internal val AUDIO_ORDER: Comparator<AudioAsset> =
    compareBy<AudioAsset> { it.title.lowercase() }.thenBy { it.id.value }

internal fun normalizeAudioCatalogue(items: Collection<AudioAsset>): List<AudioAsset> =
    items.distinctBy { it.id }.sortedWith(AUDIO_ORDER)

private class AudioCatalogueOpenHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_AUDIO (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_CONTENT_URI TEXT NOT NULL,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_ARTIST TEXT,
                $COL_ALBUM TEXT,
                $COL_MIME_TYPE TEXT NOT NULL,
                $COL_DURATION INTEGER NOT NULL,
                $COL_SIZE_BYTES INTEGER NOT NULL,
                $COL_DATE_ADDED INTEGER NOT NULL,
                $COL_DATE_MODIFIED INTEGER NOT NULL,
                $COL_ALBUM_ID INTEGER,
                $COL_TRACK_NUMBER INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX audio_title_idx ON $TABLE_AUDIO($COL_TITLE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No prior schema version exists yet -- this is here so the FIRST real migration this
        // table ever needs has a place to land, matching SqliteMediaRepository's own onUpgrade
        // shape rather than leaving a future column addition with no established pattern to
        // follow in this file.
    }

    fun readAll(): List<AudioAsset> = readableDatabase.query(
        TABLE_AUDIO,
        ALL_COLUMNS,
        null,
        null,
        null,
        null,
        "$COL_TITLE COLLATE NOCASE ASC, $COL_ID ASC",
    ).use { cursor ->
        buildList(cursor.count) {
            while (cursor.moveToNext()) add(cursor.toAsset())
        }
    }

    fun replaceAll(items: List<AudioAsset>) {
        writableDatabase.inTransaction { db ->
            db.delete(TABLE_AUDIO, null, null)
            items.forEach { db.insertOrThrow(TABLE_AUDIO, null, it.toValues()) }
        }
    }

    fun upsert(items: List<AudioAsset>) {
        writableDatabase.inTransaction { db ->
            items.forEach {
                db.insertWithOnConflict(TABLE_AUDIO, null, it.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun remove(ids: Set<MediaId>) {
        writableDatabase.inTransaction { db ->
            ids.chunked(SQLITE_BIND_LIMIT).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(TABLE_AUDIO, "$COL_ID IN ($placeholders)", chunk.map { it.value.toString() }.toTypedArray())
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_audio_catalogue.db"
        const val DATABASE_VERSION = 1
        const val TABLE_AUDIO = "audio"
        const val COL_ID = "id"
        const val COL_CONTENT_URI = "content_uri"
        const val COL_DISPLAY_NAME = "display_name"
        const val COL_TITLE = "title"
        const val COL_ARTIST = "artist"
        const val COL_ALBUM = "album"
        const val COL_MIME_TYPE = "mime_type"
        const val COL_DURATION = "duration_millis"
        const val COL_SIZE_BYTES = "size_bytes"
        const val COL_DATE_ADDED = "date_added_seconds"
        const val COL_DATE_MODIFIED = "date_modified_seconds"
        const val COL_ALBUM_ID = "album_id"
        const val COL_TRACK_NUMBER = "track_number"
        const val SQLITE_BIND_LIMIT = 900

        val ALL_COLUMNS = arrayOf(
            COL_ID, COL_CONTENT_URI, COL_DISPLAY_NAME, COL_TITLE, COL_ARTIST, COL_ALBUM,
            COL_MIME_TYPE, COL_DURATION, COL_SIZE_BYTES, COL_DATE_ADDED, COL_DATE_MODIFIED,
            COL_ALBUM_ID, COL_TRACK_NUMBER,
        )
    }
}

private fun SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> Unit) {
    beginTransaction()
    try {
        block(this)
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun AudioAsset.toValues(): ContentValues = ContentValues(13).apply {
    put("id", id.value)
    put("content_uri", contentUriString)
    put("display_name", displayName)
    put("title", title)
    put("artist", artist)
    put("album", album)
    put("mime_type", mimeType)
    put("duration_millis", durationMillis)
    put("size_bytes", sizeBytes)
    put("date_added_seconds", dateAddedSeconds)
    put("date_modified_seconds", dateModifiedSeconds)
    put("album_id", albumId)
    put("track_number", trackNumber)
}

private fun Cursor.toAsset(): AudioAsset = AudioAsset(
    id = MediaId(getLong(getColumnIndexOrThrow("id"))),
    contentUriString = getString(getColumnIndexOrThrow("content_uri")),
    displayName = getString(getColumnIndexOrThrow("display_name")),
    title = getString(getColumnIndexOrThrow("title")),
    artist = stringOrNull("artist"),
    album = stringOrNull("album"),
    mimeType = getString(getColumnIndexOrThrow("mime_type")),
    durationMillis = getLong(getColumnIndexOrThrow("duration_millis")),
    sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
    dateAddedSeconds = getLong(getColumnIndexOrThrow("date_added_seconds")),
    dateModifiedSeconds = getLong(getColumnIndexOrThrow("date_modified_seconds")),
    albumId = longOrNull("album_id"),
    trackNumber = intOrNull("track_number"),
)

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.intOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}
