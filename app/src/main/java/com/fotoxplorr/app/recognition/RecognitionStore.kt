package com.fotoxplorr.app.recognition

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
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
 * Face descriptors are stored as raw little-endian float32 blobs; no image data and no crop is
 * ever persisted.
 *
 * **OCR text IS persisted, as of the searchable-text work.** It previously was not, and the
 * change is worth stating plainly rather than leaving a comment that says the opposite of what
 * the code does: text found in photos is now written to this database, with its position in the
 * frame, because two features the owner asked for are impossible without it — searching for words
 * that appear in a photograph, and selecting text off a photograph.
 *
 * What that costs, honestly: a phone whose app-private storage is read (a rooted or forensically
 * imaged device) yields readable text from photographed documents, where before it yielded only a
 * verdict. What it does not do is leave the device — this database is app-private, and in the
 * `offline` flavour the process holds no INTERNET permission at all, so there is no path off the
 * phone even for code that wanted one.
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
                identity_verdict TEXT NOT NULL,
                labels TEXT NOT NULL DEFAULT ''
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
        db.execSQL(
            """
            CREATE TABLE $TABLE_TEXT (
                media_id INTEGER NOT NULL,
                block_index INTEGER NOT NULL,
                text TEXT NOT NULL,
                left REAL NOT NULL,
                top REAL NOT NULL,
                right REAL NOT NULL,
                bottom REAL NOT NULL,
                PRIMARY KEY (media_id, block_index)
            )
            """.trimIndent(),
        )
        // Searching text across a whole library is the one query here that scans rather than
        // looks up, so it gets the one index.
        db.execSQL("CREATE INDEX ${TABLE_TEXT}_media ON $TABLE_TEXT (media_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Recognition data is a derived cache: rebuilding is cheaper and safer than migrating.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TEXT")
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
        val blocks = HashMap<Long, MutableList<TextBlock>>()
        readableDatabase.query(
            TABLE_TEXT, arrayOf("media_id", "text", "left", "top", "right", "bottom"),
            null, null, null, null, "media_id ASC, block_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(0)
                blocks.getOrPut(mediaId) { mutableListOf() } += TextBlock(
                    text = cursor.getString(1),
                    left = cursor.getFloat(2),
                    top = cursor.getFloat(3),
                    right = cursor.getFloat(4),
                    bottom = cursor.getFloat(5),
                )
            }
        }
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
                arrayOf(
                    "media_id", "source_revision", "face_count", "pet_verdict", "identity_verdict",
                    "labels",
                ),
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
                            labels = decodeLabels(cursor.getString(5)),
                            textBlocks = blocks[mediaId].orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    fun upsert(rows: List<AssetRecognition>) {
        writableDatabase.transaction {
            rows.forEach { row ->
                insertWithOnConflict(
                    TABLE_ASSETS, null,
                    ContentValues().apply {
                        put("media_id", row.mediaId.value)
                        put("source_revision", row.sourceRevision)
                        put("face_count", row.faceCount)
                        put("pet_verdict", row.petVerdict.name)
                        put("identity_verdict", row.identityVerdict.name)
                        put("labels", encodeLabels(row.labels))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                delete(TABLE_TEXT, "media_id = ?", arrayOf(row.mediaId.value.toString()))
                row.textBlocks.forEachIndexed { blockIndex, block ->
                    insertWithOnConflict(
                        TABLE_TEXT, null,
                        ContentValues().apply {
                            put("media_id", row.mediaId.value)
                            put("block_index", blockIndex)
                            put("text", block.text)
                            put("left", block.left)
                            put("top", block.top)
                            put("right", block.right)
                            put("bottom", block.bottom)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                delete(TABLE_FACES, "media_id = ?", arrayOf(row.mediaId.value.toString()))
                row.faceDescriptors.forEach { face ->
                    insertWithOnConflict(
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
        }
    }

    fun removeMissing(availableIds: Set<MediaId>) {
        val known = revisions().keys
        val stale = known - availableIds
        if (stale.isEmpty()) return
        writableDatabase.transaction {
            stale.forEach { id ->
                val args = arrayOf(id.value.toString())
                delete(TABLE_TEXT, "media_id = ?", args)
                delete(TABLE_FACES, "media_id = ?", args)
                delete(TABLE_ASSETS, "media_id = ?", args)
            }
        }
    }

    fun clear() {
        writableDatabase.apply {
            delete(TABLE_TEXT, null, null)
            delete(TABLE_FACES, null, null)
            delete(TABLE_ASSETS, null, null)
        }
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_recognition.db"
        const val DATABASE_VERSION = 2
        const val TABLE_ASSETS = "asset_recognition"
        const val TABLE_FACES = "face_descriptor"
        const val TABLE_TEXT = "asset_text_block"

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

/**
 * AI labels as one delimited string rather than a fourth table.
 *
 * A label list is a handful of short words that are only ever read back whole, alongside the row
 * they belong to — the join a separate table would buy is a join nothing needs. The delimiter is
 * `` (unit separator) rather than a comma because label text is model-authored and may well
 * contain punctuation; a control character cannot collide with it.
 */
internal fun encodeLabels(labels: List<String>): String =
    labels.filter { it.isNotBlank() }.joinToString(LABEL_SEPARATOR)

internal fun decodeLabels(stored: String?): List<String> =
    stored?.split(LABEL_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

private const val LABEL_SEPARATOR = ""
