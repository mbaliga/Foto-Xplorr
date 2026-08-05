package com.fotoxplorr.app.media

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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

class SqliteMediaRepository(context: Context) : MediaRepository {
    private val helper = CatalogueOpenHelper(context.applicationContext)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<MediaAsset>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { state.value = helper.readAll() }
    }

    override fun observeAll(): Flow<List<MediaAsset>> = state.asStateFlow()

    override suspend fun replaceAll(items: List<MediaAsset>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val normalized = normalize(items)
            helper.replaceAll(normalized)
            state.value = normalized
        }
    }

    override suspend fun upsert(items: List<MediaAsset>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        mutex.withLock {
            helper.upsert(items)
            state.value = normalize(
                state.value.associateByTo(linkedMapOf()) { it.id }
                    .apply { items.forEach { put(it.id, it) } }
                    .values,
            )
        }
    }

    override suspend fun remove(ids: Set<MediaId>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        mutex.withLock {
            helper.remove(ids)
            state.value = state.value.filterNot { it.id in ids }
        }
    }

    // Reads the in-memory mirror rather than the database: it is populated from disk at
    // construction and kept in step by every mutation above, so it is authoritative and free.
    override suspend fun count(): Int = state.value.size

    private fun normalize(items: Collection<MediaAsset>): List<MediaAsset> = items
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<MediaAsset> { it.dateTakenMillis }
                .thenByDescending { it.dateModifiedSeconds }
                .thenByDescending { it.id.value },
        )
}

private class CatalogueOpenHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MEDIA (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_CONTENT_URI TEXT NOT NULL,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_MIME_TYPE TEXT NOT NULL,
                $COL_BUCKET_NAME TEXT,
                $COL_BUCKET_ID INTEGER,
                $COL_DATE_TAKEN INTEGER NOT NULL,
                $COL_DATE_MODIFIED INTEGER NOT NULL,
                $COL_WIDTH INTEGER NOT NULL,
                $COL_HEIGHT INTEGER NOT NULL,
                $COL_SIZE_BYTES INTEGER NOT NULL,
                $COL_DURATION INTEGER NOT NULL DEFAULT 0,
                $COL_RELATIVE_PATH TEXT,
                $COL_FAVORITE INTEGER NOT NULL,
                $COL_TRASHED INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX media_order_idx ON $TABLE_MEDIA($COL_DATE_TAKEN DESC, $COL_DATE_MODIFIED DESC)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_MEDIA ADD COLUMN $COL_BUCKET_ID INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_MEDIA ADD COLUMN $COL_DURATION INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun readAll(): List<MediaAsset> = readableDatabase.query(
        TABLE_MEDIA,
        ALL_COLUMNS,
        null,
        null,
        null,
        null,
        "$COL_DATE_TAKEN DESC, $COL_DATE_MODIFIED DESC, $COL_ID DESC",
    ).use { cursor ->
        buildList(cursor.count) {
            while (cursor.moveToNext()) add(cursor.toAsset())
        }
    }

    fun replaceAll(items: List<MediaAsset>) {
        writableDatabase.inTransaction { db ->
            db.delete(TABLE_MEDIA, null, null)
            items.forEach { db.insertOrThrow(TABLE_MEDIA, null, it.toValues()) }
        }
    }

    fun upsert(items: List<MediaAsset>) {
        writableDatabase.inTransaction { db ->
            items.forEach {
                db.insertWithOnConflict(
                    TABLE_MEDIA,
                    null,
                    it.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun remove(ids: Set<MediaId>) {
        writableDatabase.inTransaction { db ->
            ids.chunked(SQLITE_BIND_LIMIT).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(
                    TABLE_MEDIA,
                    "$COL_ID IN ($placeholders)",
                    chunk.map { it.value.toString() }.toTypedArray(),
                )
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_catalogue.db"
        const val DATABASE_VERSION = 3
        const val TABLE_MEDIA = "media"
        const val COL_ID = "id"
        const val COL_CONTENT_URI = "content_uri"
        const val COL_DISPLAY_NAME = "display_name"
        const val COL_MIME_TYPE = "mime_type"
        const val COL_BUCKET_NAME = "bucket_name"
        const val COL_BUCKET_ID = "bucket_id"
        const val COL_DATE_TAKEN = "date_taken"
        const val COL_DATE_MODIFIED = "date_modified"
        const val COL_WIDTH = "width"
        const val COL_HEIGHT = "height"
        const val COL_SIZE_BYTES = "size_bytes"
        const val COL_DURATION = "duration_millis"
        const val COL_RELATIVE_PATH = "relative_path"
        const val COL_FAVORITE = "is_favorite"
        const val COL_TRASHED = "is_trashed"
        const val SQLITE_BIND_LIMIT = 900

        val ALL_COLUMNS = arrayOf(
            COL_ID,
            COL_CONTENT_URI,
            COL_DISPLAY_NAME,
            COL_MIME_TYPE,
            COL_BUCKET_NAME,
            COL_BUCKET_ID,
            COL_DATE_TAKEN,
            COL_DATE_MODIFIED,
            COL_WIDTH,
            COL_HEIGHT,
            COL_SIZE_BYTES,
            COL_DURATION,
            COL_RELATIVE_PATH,
            COL_FAVORITE,
            COL_TRASHED,
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

private fun MediaAsset.toValues(): ContentValues = ContentValues(15).apply {
    put("id", id.value)
    put("content_uri", contentUriString)
    put("display_name", displayName)
    put("mime_type", mimeType)
    put("bucket_name", bucketName)
    put("bucket_id", bucketId)
    put("date_taken", dateTakenMillis)
    put("date_modified", dateModifiedSeconds)
    put("width", width)
    put("height", height)
    put("size_bytes", sizeBytes)
    put("duration_millis", durationMillis)
    put("relative_path", relativePath)
    put("is_favorite", if (isFavorite) 1 else 0)
    put("is_trashed", if (isTrashed) 1 else 0)
}

private fun Cursor.toAsset(): MediaAsset = MediaAsset(
    id = MediaId(getLong(getColumnIndexOrThrow("id"))),
    contentUriString = getString(getColumnIndexOrThrow("content_uri")),
    displayName = getString(getColumnIndexOrThrow("display_name")),
    mimeType = getString(getColumnIndexOrThrow("mime_type")),
    bucketName = stringOrNull("bucket_name"),
    bucketId = longOrNull("bucket_id"),
    dateTakenMillis = getLong(getColumnIndexOrThrow("date_taken")),
    dateModifiedSeconds = getLong(getColumnIndexOrThrow("date_modified")),
    width = getInt(getColumnIndexOrThrow("width")),
    height = getInt(getColumnIndexOrThrow("height")),
    sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
    durationMillis = getLong(getColumnIndexOrThrow("duration_millis")),
    relativePath = stringOrNull("relative_path"),
    isFavorite = getInt(getColumnIndexOrThrow("is_favorite")) != 0,
    isTrashed = getInt(getColumnIndexOrThrow("is_trashed")) != 0,
)

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}
