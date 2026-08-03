package com.fotoxplorr.app.ai

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class StoredEmbedding(
    val mediaId: MediaId,
    val sourceRevision: Long,
    val modelSha256: String,
    val vector: ByteArray,
    val signature: Int,
    val x: Float?,
    val y: Float?,
)

data class EmbeddingIndexState(
    val modelSha256: String? = null,
    val indexedCount: Int = 0,
    val laidOutCount: Int = 0,
)

data class SimilarityPoint(
    val mediaId: MediaId,
    val x: Float,
    val y: Float,
    val cluster: Int,
)

class EmbeddingRepository(context: Context) {
    private val helper = EmbeddingOpenHelper(context.applicationContext)
    private val mutex = Mutex()
    private val state = MutableStateFlow(helper.summary())

    fun observe(): StateFlow<EmbeddingIndexState> = state.asStateFlow()

    suspend fun missingAssets(
        assets: List<MediaAsset>,
        modelSha256: String,
    ): List<MediaAsset> = withContext(Dispatchers.IO) {
        val revisions = helper.revisions(modelSha256)
        assets.filter { asset ->
            !asset.isVideo && revisions[asset.id] != asset.sourceRevision()
        }
    }

    suspend fun upsertBatch(embeddings: List<StoredEmbedding>) = withContext(Dispatchers.IO) {
        if (embeddings.isEmpty()) return@withContext
        mutex.withLock {
            helper.upsert(embeddings)
            state.value = helper.summary()
        }
    }

    suspend fun readAll(modelSha256: String): List<StoredEmbedding> = withContext(Dispatchers.IO) {
        helper.readAll(modelSha256)
    }

    suspend fun readPoints(modelSha256: String): List<SimilarityPoint> = withContext(Dispatchers.IO) {
        helper.readPoints(modelSha256)
    }

    suspend fun updateLayout(
        modelSha256: String,
        points: List<SimilarityPoint>,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.updateLayout(modelSha256, points)
            state.value = helper.summary()
        }
    }

    suspend fun nearest(
        modelSha256: String,
        mediaId: MediaId,
        limit: Int = 48,
    ): List<Pair<MediaId, Float>> = withContext(Dispatchers.Default) {
        val all = helper.readAll(modelSha256)
        val query = all.firstOrNull { it.mediaId == mediaId } ?: return@withContext emptyList()
        val candidates = approximateCandidates(query, all)
        candidates.asSequence()
            .filterNot { it.mediaId == mediaId }
            .map { candidate -> candidate.mediaId to cosine(query.vector, candidate.vector) }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 256))
            .toList()
    }

    suspend fun removeMissing(availableIds: Set<MediaId>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.removeMissing(availableIds)
            state.value = helper.summary()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.clear()
            state.value = EmbeddingIndexState()
        }
    }

    private fun approximateCandidates(
        query: StoredEmbedding,
        all: List<StoredEmbedding>,
    ): List<StoredEmbedding> {
        val exactBucket = query.signature ushr SIGNATURE_BUCKET_SHIFT
        val close = all.filter { candidate ->
            val bucket = candidate.signature ushr SIGNATURE_BUCKET_SHIFT
            bucket == exactBucket || Integer.bitCount(candidate.signature xor query.signature) <= 3
        }
        return if (close.size >= MIN_NEIGHBOUR_CANDIDATES) close else all
    }

    companion object {
        fun quantize(vector: FloatArray): ByteArray {
            if (vector.isEmpty()) return byteArrayOf()
            var squared = 0.0
            vector.forEach { squared += it.toDouble() * it.toDouble() }
            val norm = sqrt(squared).takeIf { it > 0.0 } ?: 1.0
            return ByteArray(vector.size) { index ->
                ((vector[index] / norm * 127.0).toInt().coerceIn(-127, 127)).toByte()
            }
        }

        fun cosine(left: ByteArray, right: ByteArray): Float {
            if (left.isEmpty() || left.size != right.size) return -1f
            var dot = 0L
            var leftNorm = 0L
            var rightNorm = 0L
            for (index in left.indices) {
                val l = left[index].toInt()
                val r = right[index].toInt()
                dot += l.toLong() * r
                leftNorm += l.toLong() * l
                rightNorm += r.toLong() * r
            }
            if (leftNorm == 0L || rightNorm == 0L) return -1f
            return (dot / sqrt(leftNorm.toDouble() * rightNorm.toDouble())).toFloat()
        }

        fun signature(vector: ByteArray): Int {
            if (vector.isEmpty()) return 0
            var signature = 0
            for (bit in 0 until SIGNATURE_BITS) {
                var projection = 0L
                for (index in vector.indices step SIGNATURE_SAMPLE_STEP) {
                    val weight = if (mix(index, bit) and 1 == 0) 1 else -1
                    projection += vector[index].toInt() * weight
                }
                if (projection >= 0) signature = signature or (1 shl bit)
            }
            return signature
        }

        private fun mix(index: Int, bit: Int): Int {
            var value = index * -1640531527 + bit * -2048144789
            value = value xor (value ushr 16)
            value *= -1028477387
            return value xor (value ushr 13)
        }

        private const val SIGNATURE_BITS = 24
        private const val SIGNATURE_SAMPLE_STEP = 7
        private const val SIGNATURE_BUCKET_SHIFT = 16
        private const val MIN_NEIGHBOUR_CANDIDATES = 256
    }
}

private class EmbeddingOpenHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_EMBEDDINGS (
                $COL_MEDIA_ID INTEGER PRIMARY KEY,
                $COL_SOURCE_REVISION INTEGER NOT NULL,
                $COL_MODEL_SHA TEXT NOT NULL,
                $COL_VECTOR BLOB NOT NULL,
                $COL_SIGNATURE INTEGER NOT NULL,
                $COL_X REAL,
                $COL_Y REAL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX embedding_model_idx ON $TABLE_EMBEDDINGS($COL_MODEL_SHA)")
        db.execSQL("CREATE INDEX embedding_signature_idx ON $TABLE_EMBEDDINGS($COL_MODEL_SHA, $COL_SIGNATURE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun revisions(modelSha: String): Map<MediaId, Long> = readableDatabase.query(
        TABLE_EMBEDDINGS,
        arrayOf(COL_MEDIA_ID, COL_SOURCE_REVISION),
        "$COL_MODEL_SHA=?",
        arrayOf(modelSha),
        null,
        null,
        null,
    ).use { cursor ->
        buildMap(cursor.count) {
            val id = cursor.getColumnIndexOrThrow(COL_MEDIA_ID)
            val revision = cursor.getColumnIndexOrThrow(COL_SOURCE_REVISION)
            while (cursor.moveToNext()) put(MediaId(cursor.getLong(id)), cursor.getLong(revision))
        }
    }

    fun upsert(embeddings: List<StoredEmbedding>) {
        writableDatabase.transaction { db ->
            embeddings.forEach { embedding ->
                db.insertWithOnConflict(
                    TABLE_EMBEDDINGS,
                    null,
                    ContentValues(7).apply {
                        put(COL_MEDIA_ID, embedding.mediaId.value)
                        put(COL_SOURCE_REVISION, embedding.sourceRevision)
                        put(COL_MODEL_SHA, embedding.modelSha256)
                        put(COL_VECTOR, embedding.vector)
                        put(COL_SIGNATURE, embedding.signature)
                        if (embedding.x == null) putNull(COL_X) else put(COL_X, embedding.x)
                        if (embedding.y == null) putNull(COL_Y) else put(COL_Y, embedding.y)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun readAll(modelSha: String): List<StoredEmbedding> = readableDatabase.query(
        TABLE_EMBEDDINGS,
        ALL_COLUMNS,
        "$COL_MODEL_SHA=?",
        arrayOf(modelSha),
        null,
        null,
        "$COL_MEDIA_ID ASC",
    ).use { cursor ->
        buildList(cursor.count) {
            while (cursor.moveToNext()) add(cursor.toEmbedding())
        }
    }

    fun readPoints(modelSha: String): List<SimilarityPoint> = readableDatabase.query(
        TABLE_EMBEDDINGS,
        arrayOf(COL_MEDIA_ID, COL_SIGNATURE, COL_X, COL_Y),
        "$COL_MODEL_SHA=? AND $COL_X IS NOT NULL AND $COL_Y IS NOT NULL",
        arrayOf(modelSha),
        null,
        null,
        null,
    ).use { cursor ->
        val id = cursor.getColumnIndexOrThrow(COL_MEDIA_ID)
        val signature = cursor.getColumnIndexOrThrow(COL_SIGNATURE)
        val x = cursor.getColumnIndexOrThrow(COL_X)
        val y = cursor.getColumnIndexOrThrow(COL_Y)
        buildList(cursor.count) {
            while (cursor.moveToNext()) {
                add(
                    SimilarityPoint(
                        mediaId = MediaId(cursor.getLong(id)),
                        x = cursor.getFloat(x),
                        y = cursor.getFloat(y),
                        cluster = cursor.getInt(signature) ushr 20,
                    ),
                )
            }
        }
    }

    fun updateLayout(modelSha: String, points: List<SimilarityPoint>) {
        writableDatabase.transaction { db ->
            points.forEach { point ->
                db.update(
                    TABLE_EMBEDDINGS,
                    ContentValues(2).apply {
                        put(COL_X, point.x)
                        put(COL_Y, point.y)
                    },
                    "$COL_MEDIA_ID=? AND $COL_MODEL_SHA=?",
                    arrayOf(point.mediaId.value.toString(), modelSha),
                )
            }
        }
    }

    fun removeMissing(availableIds: Set<MediaId>) {
        val existing = readableDatabase.query(
            TABLE_EMBEDDINGS,
            arrayOf(COL_MEDIA_ID),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val id = cursor.getColumnIndexOrThrow(COL_MEDIA_ID)
            buildSet(cursor.count) { while (cursor.moveToNext()) add(MediaId(cursor.getLong(id))) }
        }
        val stale = existing - availableIds
        writableDatabase.transaction { db ->
            stale.chunked(SQLITE_BIND_LIMIT).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                db.delete(
                    TABLE_EMBEDDINGS,
                    "$COL_MEDIA_ID IN ($placeholders)",
                    batch.map { it.value.toString() }.toTypedArray(),
                )
            }
        }
    }

    fun summary(): EmbeddingIndexState = readableDatabase.rawQuery(
        "SELECT $COL_MODEL_SHA, COUNT(*), SUM(CASE WHEN $COL_X IS NOT NULL AND $COL_Y IS NOT NULL THEN 1 ELSE 0 END) FROM $TABLE_EMBEDDINGS GROUP BY $COL_MODEL_SHA ORDER BY COUNT(*) DESC LIMIT 1",
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use EmbeddingIndexState()
        EmbeddingIndexState(
            modelSha256 = cursor.getString(0),
            indexedCount = cursor.getInt(1),
            laidOutCount = cursor.getInt(2),
        )
    }

    fun clear() {
        writableDatabase.delete(TABLE_EMBEDDINGS, null, null)
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_embeddings.db"
        const val DATABASE_VERSION = 1
        const val TABLE_EMBEDDINGS = "embeddings"
        const val COL_MEDIA_ID = "media_id"
        const val COL_SOURCE_REVISION = "source_revision"
        const val COL_MODEL_SHA = "model_sha"
        const val COL_VECTOR = "vector"
        const val COL_SIGNATURE = "signature"
        const val COL_X = "x"
        const val COL_Y = "y"
        const val SQLITE_BIND_LIMIT = 900
        val ALL_COLUMNS = arrayOf(
            COL_MEDIA_ID,
            COL_SOURCE_REVISION,
            COL_MODEL_SHA,
            COL_VECTOR,
            COL_SIGNATURE,
            COL_X,
            COL_Y,
        )
    }
}

private fun Cursor.toEmbedding(): StoredEmbedding = StoredEmbedding(
    mediaId = MediaId(getLong(getColumnIndexOrThrow("media_id"))),
    sourceRevision = getLong(getColumnIndexOrThrow("source_revision")),
    modelSha256 = getString(getColumnIndexOrThrow("model_sha")),
    vector = getBlob(getColumnIndexOrThrow("vector")),
    signature = getInt(getColumnIndexOrThrow("signature")),
    x = getColumnIndexOrThrow("x").let { if (isNull(it)) null else getFloat(it) },
    y = getColumnIndexOrThrow("y").let { if (isNull(it)) null else getFloat(it) },
)

private fun SQLiteDatabase.transaction(block: (SQLiteDatabase) -> Unit) {
    beginTransaction()
    try {
        block(this)
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun MediaAsset.sourceRevision(): Long =
    (dateModifiedSeconds shl 17) xor sizeBytes xor width.toLong().shl(9) xor height.toLong()
