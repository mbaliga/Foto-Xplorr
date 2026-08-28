package com.fotoxplorr.app.moments

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Key moments in a video: the points worth jumping to.
 *
 * Two kinds, and keeping them apart is the whole design. An [MomentSource.AUTO] moment is the
 * detector's guess and may be wrong, so re-running detection is allowed to replace the lot. A
 * [MomentSource.MANUAL] moment is something a person deliberately marked, and nothing automatic is
 * ever permitted to move or delete it — a feature that quietly discards a user's own markers when
 * it re-scans is one they will stop trusting after the first time it happens.
 */
enum class MomentSource { AUTO, MANUAL }

/**
 * One marked point in one video.
 *
 * [positionMs] is the identity within a video: two moments at the same millisecond are the same
 * moment, which is what makes "remove this marker" expressible without inventing a row id that the
 * UI would then have to carry around.
 */
data class VideoMoment(
    val mediaId: MediaId,
    val positionMs: Long,
    val source: MomentSource,
    /** 0..1, how sure the detector is. Always 0 for a hand-placed marker: it is certain by nature. */
    val confidence: Float = 0f,
    /** Short reason, e.g. "Scene change". Empty for manual markers, which need no justification. */
    val label: String = "",
) {
    val isManual: Boolean get() = source == MomentSource.MANUAL
}

/**
 * A moment the detector found, before it is attached to a video.
 *
 * Separate from [VideoMoment] so the detector itself can be a pure function with no notion of which
 * asset it is looking at — which is what lets it be unit-tested against synthetic frame data on the
 * JVM instead of needing a real video file and a device.
 */
data class DetectedMoment(
    val positionMs: Long,
    val confidence: Float,
    val label: String,
)

/**
 * App-private storage for key moments.
 *
 * Its own database rather than a table inside the recognition one, because the two have opposite
 * durability rules: recognition results are a derived cache whose upgrade path is "drop it and
 * recompute", and doing that here would throw away markers a person placed by hand. Sharing a file
 * would mean one careless schema bump destroying the only data in this app the user authored
 * directly.
 */
class VideoMomentStore(context: Context) {
    private val helper = MomentOpenHelper(context.applicationContext)
    private val mutex = Mutex()
    private val moments = MutableStateFlow<Map<MediaId, List<VideoMoment>>>(emptyMap())

    fun observe(): StateFlow<Map<MediaId, List<VideoMoment>>> = moments.asStateFlow()

    /** Everything for one video, earliest first. Cheap: reads the already-loaded map. */
    fun momentsFor(mediaId: MediaId): List<VideoMoment> = moments.value[mediaId].orEmpty()

    suspend fun reload() = withContext(Dispatchers.IO) {
        moments.value = helper.readAll()
    }

    suspend fun add(moment: VideoMoment) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.upsert(listOf(moment)) }
        reload()
    }

    suspend fun remove(mediaId: MediaId, positionMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.remove(mediaId, positionMs) }
        reload()
    }

    /**
     * Replace this video's AUTO moments, leaving every manual marker untouched.
     *
     * The scoping to AUTO is the point, and it is enforced here rather than trusted to callers:
     * re-detection happens in the background, possibly while the user is looking at markers they
     * placed themselves, and a delete-everything-then-insert would make their work vanish
     * mid-session.
     */
    suspend fun replaceAuto(mediaId: MediaId, detected: List<VideoMoment>) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.replaceAuto(mediaId, detected.filterNot { it.isManual }) }
        reload()
    }

    /** True when this video has already been through auto-detection, successful or not. */
    suspend fun hasBeenScanned(mediaId: MediaId): Boolean = withContext(Dispatchers.IO) {
        helper.isScanned(mediaId)
    }

    suspend fun markScanned(mediaId: MediaId) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.markScanned(mediaId) }
    }
}

private class MomentOpenHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MOMENTS (
                media_id INTEGER NOT NULL,
                position_ms INTEGER NOT NULL,
                source TEXT NOT NULL,
                confidence REAL NOT NULL,
                label TEXT NOT NULL,
                PRIMARY KEY (media_id, position_ms)
            )
            """.trimIndent(),
        )
        // Which videos have been through the detector, so a video with genuinely no key moments is
        // not re-scanned on every open. Without this, "found nothing" and "never looked" are the
        // same state, and the expensive pass runs for ever on the same file.
        db.execSQL("CREATE TABLE $TABLE_SCANNED (media_id INTEGER PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Manual markers are user-authored data, so a schema change migrates rather than drops.
        // Only the scan bookkeeping is disposable.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SCANNED")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SCANNED (media_id INTEGER PRIMARY KEY)")
    }

    fun readAll(): Map<MediaId, List<VideoMoment>> {
        val out = HashMap<MediaId, MutableList<VideoMoment>>()
        readableDatabase.query(
            TABLE_MOMENTS,
            arrayOf("media_id", "position_ms", "source", "confidence", "label"),
            null, null, null, null, "media_id ASC, position_ms ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = MediaId(cursor.getLong(0))
                out.getOrPut(id) { mutableListOf() } += VideoMoment(
                    mediaId = id,
                    positionMs = cursor.getLong(1),
                    source = if (cursor.getString(2) == MomentSource.MANUAL.name) {
                        MomentSource.MANUAL
                    } else {
                        MomentSource.AUTO
                    },
                    confidence = cursor.getFloat(3),
                    label = cursor.getString(4).orEmpty(),
                )
            }
        }
        return out
    }

    fun upsert(rows: List<VideoMoment>) {
        writableDatabase.transaction {
            rows.forEach { moment ->
                insertWithOnConflict(
                    TABLE_MOMENTS, null,
                    ContentValues().apply {
                        put("media_id", moment.mediaId.value)
                        put("position_ms", moment.positionMs)
                        put("source", moment.source.name)
                        put("confidence", moment.confidence)
                        put("label", moment.label)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun remove(mediaId: MediaId, positionMs: Long) {
        writableDatabase.delete(
            TABLE_MOMENTS,
            "media_id = ? AND position_ms = ?",
            arrayOf(mediaId.value.toString(), positionMs.toString()),
        )
    }

    fun replaceAuto(mediaId: MediaId, rows: List<VideoMoment>) {
        writableDatabase.transaction {
            delete(
                TABLE_MOMENTS,
                "media_id = ? AND source = ?",
                arrayOf(mediaId.value.toString(), MomentSource.AUTO.name),
            )
            rows.forEach { moment ->
                insertWithOnConflict(
                    TABLE_MOMENTS, null,
                    ContentValues().apply {
                        put("media_id", moment.mediaId.value)
                        put("position_ms", moment.positionMs)
                        put("source", MomentSource.AUTO.name)
                        put("confidence", moment.confidence)
                        put("label", moment.label)
                    },
                    // IGNORE, not REPLACE: a manual marker already sitting on this millisecond
                    // wins. The primary key is (media_id, position_ms), so REPLACE here would
                    // overwrite a hand-placed marker with a guess that happened to land on it.
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }
    }

    fun isScanned(mediaId: MediaId): Boolean =
        readableDatabase.query(
            TABLE_SCANNED, arrayOf("media_id"),
            "media_id = ?", arrayOf(mediaId.value.toString()),
            null, null, null,
        ).use { it.moveToFirst() }

    fun markScanned(mediaId: MediaId) {
        writableDatabase.insertWithOnConflict(
            TABLE_SCANNED, null,
            ContentValues().apply { put("media_id", mediaId.value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_moments.db"
        const val DATABASE_VERSION = 1
        const val TABLE_MOMENTS = "video_moment"
        const val TABLE_SCANNED = "video_scanned"
    }
}
