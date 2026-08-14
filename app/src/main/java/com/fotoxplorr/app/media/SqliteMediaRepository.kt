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
            state.value = mergeIntoSortedCatalogue(state.value, items)
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

    private fun normalize(items: Collection<MediaAsset>): List<MediaAsset> =
        normalizeCatalogue(items)
}

/**
 * The catalogue's one ordering.
 *
 * Hoisted to a single constant because [mergeIntoSortedCatalogue] must compare against exactly
 * the order the list it is merging into is already in. A second, separately written comparator
 * that drifted from this one would corrupt the catalogue silently -- no crash, just photos in
 * the wrong places.
 *
 * Total, not merely consistent: ids are unique, so the final tiebreak admits no equal pairs and
 * the sorted result is therefore unique. That uniqueness is what lets the merge below claim it
 * produces the same list a full re-sort would.
 */
internal val CATALOGUE_ORDER: Comparator<MediaAsset> =
    compareByDescending<MediaAsset> { it.dateTakenMillis }
        .thenByDescending { it.dateModifiedSeconds }
        .thenByDescending { it.id.value }

internal fun normalizeCatalogue(items: Collection<MediaAsset>): List<MediaAsset> = items
    .distinctBy { it.id }
    .sortedWith(CATALOGUE_ORDER)

/**
 * Fold a small batch into the already-sorted catalogue in one linear pass.
 *
 * This replaces a full rebuild-and-re-sort of the whole catalogue per batch. A scan arrives in
 * batches, so on a large library that ran hundreds of times, each run allocating a map of every
 * asset and then re-sorting every asset -- work quadratic in the library size across a single
 * scan, on the IO thread, feeding a StateFlow the UI is actively collecting. That is a direct
 * contributor to a scan making the whole app stutter.
 *
 * [current] is already in [CATALOGUE_ORDER] and a batch is a few dozen items, so merging is
 * linear. The result is identical to re-sorting: [CATALOGUE_ORDER] is total, so exactly one
 * correct output exists and both routes produce it.
 *
 * Top-level and internal rather than a private method purely so it can be unit-tested without a
 * Context -- it is the kind of index arithmetic that is easy to get subtly wrong and impossible
 * to notice by eye.
 */
internal fun mergeIntoSortedCatalogue(
    current: List<MediaAsset>,
    incoming: List<MediaAsset>,
): List<MediaAsset> {
    if (incoming.isEmpty()) return current
    // Within a single batch the LAST entry for an id wins, matching the map-put semantics of the
    // rebuild this replaced -- a later row in the same batch is the fresher read of that file.
    // `distinctBy` would keep the FIRST and silently disagree; the equivalence tests catch it.
    val batch = normalizeCatalogue(
        incoming.associateByTo(LinkedHashMap(incoming.size * 2)) { it.id }.values,
    )
    // Anything the batch carries supersedes the copy already held, so stale entries are dropped
    // as the merge walks past them rather than in a separate filtering pass.
    val superseded = incoming.mapTo(HashSet(incoming.size * 2)) { it.id }
    val merged = ArrayList<MediaAsset>(current.size + batch.size)
    var i = 0
    var j = 0
    while (i < current.size && j < batch.size) {
        val held = current[i]
        if (held.id in superseded) {
            i += 1
            continue
        }
        if (CATALOGUE_ORDER.compare(held, batch[j]) <= 0) {
            merged += held
            i += 1
        } else {
            merged += batch[j]
            j += 1
        }
    }
    while (i < current.size) {
        val held = current[i]
        if (held.id !in superseded) merged += held
        i += 1
    }
    while (j < batch.size) {
        merged += batch[j]
        j += 1
    }
    return merged
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
