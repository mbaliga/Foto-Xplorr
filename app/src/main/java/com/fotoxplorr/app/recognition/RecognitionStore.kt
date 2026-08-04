package com.fotoxplorr.app.recognition

import android.content.ContentValues
import android.content.Context
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * App-private, on-device storage for recognition results.
 *
 * Face descriptors are stored as raw little-endian float32 blobs. No image data, no crop and
 * no OCR text is persisted -- OCR text is scored by [IdentityDocumentHeuristics] in memory
 * and dropped, so a stolen database yields no readable document contents.
 */
class RecognitionStore(context: Context) {
    private val helper = RecognitionOpenHelper(context.applicationContext)
    private val mutex = Mutex()
    private val index = MutableStateFlow(RecognitionIndex.EMPTY)
    private val progress = MutableStateFlow(RecognitionProgress())

    fun observe(): StateFlow<RecognitionIndex> = index.asStateFlow()

    fun observeProgress(): StateFlow<RecognitionProgress> = progress.asStateFlow()

    internal fun publishProgress(update: RecognitionProgress) {
        progress.value = update
    }

    /** Loads everything from disk and republishes the derived index. Safe to call repeatedly. */
    suspend fun reload() = withContext(Dispatchers.IO) {
        val rows = helper.readAll()
        index.value = RecognitionIndex.from(rows)
        progress.value = progress.value.copy(indexedCount = rows.size)
    }

    /** Assets whose recognition result is absent or computed from an older file revision. */
    suspend fun pendingAssets(assets: List<MediaAsset>): List<MediaAsset> = withContext(Dispatchers.IO) {
        val revisions = helper.revisions()
        assets.filter { asset ->
            !asset.isVideo && !asset.isTrashed && revisions[asset.id] != asset.recognitionRevision()
        }
    }

    suspend fun upsert(rows: List<AssetRecognition>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        mutex.withLock { helper.upsert(rows) }
        reload()
    }

    suspend fun removeMissing(availableIds: Set<MediaId>) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.removeMissing(availableIds) }
        reload()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { helper.clear() }
        index.value = RecognitionIndex.EMPTY
        progress.value = RecognitionProgress()
    }
}

/** Coarse state of the background recognition pass, for the destinations' empty states. */
data class RecognitionProgress(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val indexedCount: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
)

/**
 * Cheap change-detector for a media file. Two different files essentially never collide on
 * all of (mtime, size, width, height), and any edit changes at least mtime, so a mismatch is
 * a reliable "recompute this".
 */
fun MediaAsset.recognitionRevision(): Long =
    (dateModifiedSeconds shl 19) xor sizeBytes xor (width.toLong() shl 11) xor height.toLong()

private class RecognitionOpenHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ASSETS (
                media_id INTEGER PRIMARY KEY,
                source_revision INTEGER NOT NULL,
                face_count INTEGER NOT NULL,
                pet_verdict TEXT NOT NULL,
                identity_verdict TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_FACES (
                media_id INTEGER NOT NULL,
                face_index INTEGER NOT NULL,
                relative_area REAL NOT NULL,
                vector BLOB NOT NULL,
                PRIMARY KEY (media_id, face_index)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Recognition data is a derived cache: rebuilding is cheaper and safer than migrating.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FACES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ASSETS")
        onCreate(db)
    }

    fun revisions(): Map<MediaId, Long> = buildMap {
        readableDatabase.query(
            TABLE_ASSETS, arrayOf("media_id", "source_revision"),
            null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) put(MediaId(cursor.getLong(0)), cursor.getLong(1))
        }
    }

    fun readAll(): List<AssetRecognition> {
        val faces = HashMap<Long, MutableList<FaceDescriptor>>()
        readableDatabase.query(
            TABLE_FACES, arrayOf("media_id", "face_index", "relative_area", "vector"),
            null, null, null, null, "media_id ASC, face_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(0)
                faces.getOrPut(mediaId) { mutableListOf() } += FaceDescriptor(
                    mediaId = MediaId(mediaId),
                    faceIndex = cursor.getInt(1),
                    relativeArea = cursor.getFloat(2),
                    vector = decodeVector(cursor.getBlob(3)),
                )
            }
        }
        return buildList {
            readableDatabase.query(
                TABLE_ASSETS,
                arrayOf("media_id", "source_revision", "face_count", "pet_verdict", "identity_verdict"),
                null, null, null, null, null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(0)
                    add(
                        AssetRecognition(
                            mediaId = MediaId(mediaId),
                            sourceRevision = cursor.getLong(1),
                            faceCount = cursor.getInt(2),
                            faceDescriptors = faces[mediaId].orEmpty(),
                            petVerdict = enumOrNone(cursor.getString(3), PetVerdict.entries, PetVerdict.NONE),
                            identityVerdict = enumOrNone(
                                cursor.getString(4), IdentityVerdict.entries, IdentityVerdict.NONE,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun upsert(rows: List<AssetRecognition>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                db.insertWithOnConflict(
                    TABLE_ASSETS, null,
                    ContentValues().apply {
                        put("media_id", row.mediaId.value)
                        put("source_revision", row.sourceRevision)
                        put("face_count", row.faceCount)
                        put("pet_verdict", row.petVerdict.name)
                        put("identity_verdict", row.identityVerdict.name)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                db.delete(TABLE_FACES, "media_id = ?", arrayOf(row.mediaId.value.toString()))
                row.faceDescriptors.forEach { face ->
                    db.insertWithOnConflict(
                        TABLE_FACES, null,
                        ContentValues().apply {
                            put("media_id", row.mediaId.value)
                            put("face_index", face.faceIndex)
                            put("relative_area", face.relativeArea)
                            put("vector", encodeVector(face.vector))
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeMissing(availableIds: Set<MediaId>) {
        val known = revisions().keys
        val stale = known - availableIds
        if (stale.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            stale.forEach { id ->
                val args = arrayOf(id.value.toString())
                db.delete(TABLE_FACES, "media_id = ?", args)
                db.delete(TABLE_ASSETS, "media_id = ?", args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.apply {
            delete(TABLE_FACES, null, null)
            delete(TABLE_ASSETS, null, null)
        }
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_recognition.db"
        const val DATABASE_VERSION = 1
        const val TABLE_ASSETS = "asset_recognition"
        const val TABLE_FACES = "face_descriptor"

        fun <T : Enum<T>> enumOrNone(stored: String?, values: List<T>, fallback: T): T =
            values.firstOrNull { it.name == stored } ?: fallback
    }
}

internal fun encodeVector(vector: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    vector.forEach(buffer::putFloat)
    return buffer.array()
}

internal fun decodeVector(bytes: ByteArray?): FloatArray {
    if (bytes == null || bytes.size < Float.SIZE_BYTES) return FloatArray(0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
}
