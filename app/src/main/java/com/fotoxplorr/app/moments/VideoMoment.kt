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
    val key: MomentKey get() = MomentKey(mediaId, positionMs)
}

/**
 * What identifies one moment: which video, and where in it.
 *
 * A named type rather than a `Pair`, because it is a map key in two places and `Pair`'s
 * `first`/`second` say nothing at a call site about which of the two is the position.
 */
data class MomentKey(val mediaId: MediaId, val positionMs: Long)

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
 * A person's verdict on an AUTO-detected moment: was flagging this spot the right call.
 *
 * Lives here rather than beside the pill that draws the two thumbs, because it is stored, and
 * because [MomentFeedbackFilter] gives it a consequence — both of which belong to this layer.
 * There is nowhere to send this and no model to retrain: it is read back by re-detection on this
 * device and nowhere else, which is the only thing an app with no network permission could
 * honestly do with it.
 */
enum class MomentFeedback { GOOD, BAD }

/**
 * What a thumbs-down actually does: keeps the detector from proposing the same spot again.
 *
 * Without this the two thumbs are decoration — a control that lights up and changes nothing, of
 * exactly the kind [KeyMomentDetector]'s own class doc argues against when it insists a video of
 * identical frames must report no moments at all. Rejecting a moment and having it return on the
 * next scan is worse than never being asked.
 *
 * Only [MomentFeedback.BAD] suppresses. A thumbs-up is not a request to pin anything: the moment
 * was already going to be proposed, so "yes, good" needs no mechanism to come true, and treating
 * it as one would quietly convert an opinion into a manual marker the person never placed.
 */
object MomentFeedbackFilter {

    /**
     * [items], minus anything whose position sits within [WINDOW_MS] of a position in [rejected].
     *
     * A window rather than exact equality on the millisecond. Re-detection is not guaranteed to
     * land on the same instant twice — [FrameSampler] steps by a computed interval, so a longer
     * video, a different build's sampling budget, or a re-encoded file will shift every position
     * slightly — and a rejection matched only exactly would then quietly stop applying to the
     * moment it was about.
     *
     * Generic over what it filters because both sides of the storage boundary need it: the
     * indexer holds [DetectedMoment]s and [VideoMomentStore.replaceAuto] holds [VideoMoment]s.
     * One implementation, so the two cannot disagree about what "the same moment" means.
     */
    fun <T> suppress(items: List<T>, rejected: Collection<Long>, positionOf: (T) -> Long): List<T> {
        if (rejected.isEmpty()) return items
        return items.filterNot { isRejected(positionOf(it), rejected) }
    }

    fun isRejected(positionMs: Long, rejected: Collection<Long>): Boolean =
        rejected.any { position -> kotlin.math.abs(positionMs - position) <= WINDOW_MS }

    /**
     * How near a rejected position a new detection has to be to count as the same moment.
     *
     * Comfortably under the detector's own 3s minimum spacing between reported moments, so this
     * can never swallow a genuinely different, adjacent moment: at that spacing the nearest
     * OTHER moment is always at least twice this far away.
     */
    const val WINDOW_MS = 1_200L
}

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
    private val feedback = MutableStateFlow<Map<MomentKey, MomentFeedback>>(emptyMap())

    fun observe(): StateFlow<Map<MediaId, List<VideoMoment>>> = moments.asStateFlow()

    /**
     * Thumbs verdicts, keyed by video and exact position.
     *
     * Its own flow rather than a field on [VideoMoment], because the two have different
     * lifetimes: re-detection rewrites every AUTO moment in a video, and a verdict has to
     * outlive that rewrite in order to still be suppressing anything on the other side of it.
     */
    fun observeFeedback(): StateFlow<Map<MomentKey, MomentFeedback>> = feedback.asStateFlow()

    /** Everything for one video, earliest first. Cheap: reads the already-loaded map. */
    fun momentsFor(mediaId: MediaId): List<VideoMoment> = moments.value[mediaId].orEmpty()

    fun feedbackFor(mediaId: MediaId, positionMs: Long): MomentFeedback? =
        feedback.value[MomentKey(mediaId, positionMs)]

    /**
     * Record, or clear, a verdict on one moment.
     *
     * Passing the verdict that is already stored CLEARS it — the "tap the lit thumb again to
     * un-say it" rule the favourite and sensitive toggles already use.
     *
     * ## Why a thumbs-down also deletes the marker
     * Because otherwise it does nothing a person can see. [markScanned] means a video is scanned
     * once and never again, so a rejection that only sat in a table waiting for the next
     * detection pass would wait for ever, leaving the marker exactly where it was — a control
     * that lights up and changes nothing. So the rejection is applied immediately as well as
     * remembered.
     *
     * The stored verdict is what makes this different from "Remove marker" one row up the same
     * menu. Both delete the marker now; only this one is still true after a re-index (see
     * [replaceAuto]), which is the difference between "not this one" and "not this one, ever".
     *
     * There is no undo, for the same reason "Remove marker" has none: with the marker gone the
     * pill is gone too, and with it the lit thumb there would be to tap again. Deliberate, and
     * the reason the two thumbs sit behind a menu rather than on the pill itself, where a
     * mis-tap while scrubbing would be easy.
     */
    suspend fun setFeedback(mediaId: MediaId, positionMs: Long, value: MomentFeedback) =
        withContext(Dispatchers.IO) {
            val key = MomentKey(mediaId, positionMs)
            mutex.withLock {
                if (feedback.value[key] == value) {
                    helper.clearFeedback(mediaId, positionMs)
                } else {
                    helper.putFeedback(mediaId, positionMs, value)
                    if (value == MomentFeedback.BAD) helper.removeAuto(mediaId, positionMs)
                }
            }
            reload()
        }

    suspend fun reload() = withContext(Dispatchers.IO) {
        moments.value = helper.readAll()
        feedback.value = helper.readFeedback()
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
     *
     * Moments the person has thumbed down are dropped here too, for the same reason and in the
     * same place: suppression that lived in the indexer instead would be skipped by any other
     * caller of this method, and "the moment I rejected came back" is not a failure anyone would
     * report as a bug — they would just stop using the thumbs. See [MomentFeedbackFilter].
     */
    suspend fun replaceAuto(mediaId: MediaId, detected: List<VideoMoment>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rejected = helper.rejectedPositions(mediaId)
            val keep = MomentFeedbackFilter.suppress(
                items = detected.filterNot { it.isManual },
                rejected = rejected,
                positionOf = { it.positionMs },
            )
            helper.replaceAuto(mediaId, keep)
        }
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
        db.execSQL(CREATE_FEEDBACK)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Manual markers are user-authored data, so a schema change migrates rather than drops.
        // Only the scan bookkeeping is disposable.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SCANNED")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SCANNED (media_id INTEGER PRIMARY KEY)")
        // Thumbs verdicts are user-authored too — a rejection is the only record that a person
        // ever looked at a moment and said no — so this is created if absent, never dropped.
        db.execSQL(CREATE_FEEDBACK)
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

    /**
     * Delete one moment, but only if it is an AUTO one.
     *
     * The source check is the safety rail: this runs off a thumbs-down, which the menu only
     * offers for detected moments — but "only the UI stops it" is exactly how a hand-placed
     * marker eventually gets deleted by a code path that was never meant to touch one.
     */
    fun removeAuto(mediaId: MediaId, positionMs: Long) {
        writableDatabase.delete(
            TABLE_MOMENTS,
            "media_id = ? AND position_ms = ? AND source = ?",
            arrayOf(mediaId.value.toString(), positionMs.toString(), MomentSource.AUTO.name),
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

    fun readFeedback(): Map<MomentKey, MomentFeedback> {
        val out = HashMap<MomentKey, MomentFeedback>()
        readableDatabase.query(
            TABLE_FEEDBACK,
            arrayOf("media_id", "position_ms", "verdict"),
            null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val verdict = if (cursor.getString(2) == MomentFeedback.GOOD.name) {
                    MomentFeedback.GOOD
                } else {
                    MomentFeedback.BAD
                }
                out[MomentKey(MediaId(cursor.getLong(0)), cursor.getLong(1))] = verdict
            }
        }
        return out
    }

    fun putFeedback(mediaId: MediaId, positionMs: Long, value: MomentFeedback) {
        writableDatabase.insertWithOnConflict(
            TABLE_FEEDBACK, null,
            ContentValues().apply {
                put("media_id", mediaId.value)
                put("position_ms", positionMs)
                put("verdict", value.name)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clearFeedback(mediaId: MediaId, positionMs: Long) {
        writableDatabase.delete(
            TABLE_FEEDBACK,
            "media_id = ? AND position_ms = ?",
            arrayOf(mediaId.value.toString(), positionMs.toString()),
        )
    }

    /** Positions in one video the person has thumbed down. Read inside replaceAuto's lock. */
    fun rejectedPositions(mediaId: MediaId): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.query(
            TABLE_FEEDBACK, arrayOf("position_ms"),
            "media_id = ? AND verdict = ?",
            arrayOf(mediaId.value.toString(), MomentFeedback.BAD.name),
            null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.getLong(0)
        }
        return out
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_moments.db"

        /** 2 added [TABLE_FEEDBACK]; see [MomentOpenHelper.onUpgrade] for what a bump may touch. */
        const val DATABASE_VERSION = 2
        const val TABLE_MOMENTS = "video_moment"
        const val TABLE_SCANNED = "video_scanned"
        const val TABLE_FEEDBACK = "moment_feedback"

        /**
         * Shared by create and upgrade so the two paths cannot define the table differently —
         * the classic way a fresh install and an upgraded one end up with schemas that diverge
         * for exactly as long as it takes someone to notice.
         */
        const val CREATE_FEEDBACK = """
            CREATE TABLE IF NOT EXISTS moment_feedback (
                media_id INTEGER NOT NULL,
                position_ms INTEGER NOT NULL,
                verdict TEXT NOT NULL,
                PRIMARY KEY (media_id, position_ms)
            )
        """
    }
}
