package com.fotoxplorr.app.formats

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.AnimationSniffer
import com.fotoxplorr.core.model.MediaId
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which media ids actually animate, backed by real bytes ([AnimationSniffer]) rather than MIME
 * type (P0-14's own named defect in the now-deleted [MediaAsset.isAnimated]). Its own small
 * database, separate from every other store in this app -- [DATABASE_NAME] is deliberately named
 * for per-photo TRAITS in general, not just this one, since a future boolean fact about a photo
 * that is likewise cheap to sniff and irrelevant to the recognition/geo/library stores' own
 * schemas belongs alongside this one, not folded into any of them.
 */
class AnimationIndex(private val context: Context) {
    private val helper = AnimationOpenHelper(context.applicationContext)
    private val mutex = Mutex()
    private val animatedIds = MutableStateFlow<Set<MediaId>>(emptySet())

    fun observeAnimatedIds(): StateFlow<Set<MediaId>> = animatedIds.asStateFlow()

    /** Loads everything from disk and republishes. Safe to call repeatedly. */
    suspend fun reload() = withContext(Dispatchers.IO) {
        animatedIds.value = mutex.withLock { helper.animatedIds() }
    }

    /**
     * Sniffs every asset in [assets] whose format could plausibly animate (GIF, WebP, PNG, AVIF,
     * HEIF, HEIC) and whose stored row is missing or stale -- a different [MediaAsset.dateModifiedSeconds]
     * than the row this index already has, meaning the file changed on disk since it was last
     * checked. Reads a bounded prefix through the content resolver for each one (64 KiB, 1 MiB
     * for GIF -- see [AnimationSniffer.sniff]'s own doc on why GIF alone can need to look further
     * into the file to be sure), publishing the updated animated-id set after each batch rather
     * than only once at the end, so a library-wide pass shows results as they arrive instead of
     * making every screen that reads [observeAnimatedIds] wait for the whole thing to finish.
     */
    suspend fun sniffPending(assets: List<MediaAsset>) = withContext(Dispatchers.IO) {
        val known = mutex.withLock { helper.knownRevisions() }
        val pending = assets.filter { asset ->
            asset.mimeType.lowercase() in SNIFFABLE_MIME_TYPES && known[asset.id] != asset.dateModifiedSeconds
        }
        if (pending.isEmpty()) return@withContext
        pending.chunked(BATCH_SIZE).forEach { batch ->
            val results = batch.mapNotNull(::sniffOne)
            if (results.isNotEmpty()) {
                mutex.withLock { helper.upsert(results) }
                reload()
            }
        }
    }

    /** Drops rows for ids no longer in the catalogue at all, mirroring the same cleanup
     *  [com.fotoxplorr.app.recognition.RecognitionStore.removeMissing]/`EmbeddingRepository`'s own
     *  already fix (P0-12) so this index does not grow a leak of the identical shape. */
    suspend fun removeMissing(availableIds: Set<MediaId>) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.removeMissing(availableIds) }
        reload()
    }

    private fun sniffOne(asset: MediaAsset): AnimationRow? {
        val mime = asset.mimeType.lowercase()
        val cap = if (mime == "image/gif") GIF_HEAD_BYTES else HEAD_BYTES
        val head = runCatching {
            context.contentResolver.openInputStream(asset.contentUri)?.use { readBounded(it, cap) }
        }.getOrNull() ?: return null
        val animated = AnimationSniffer.sniff(head, mime) ?: return null
        return AnimationRow(asset.id, asset.dateModifiedSeconds, animated)
    }

    /** [InputStream.readNBytes] needs API 33; this app's minSdk is 26 -- see
     *  [com.fotoxplorr.app.metadata.WritableFormat]'s own identical loop for the same reason. */
    private fun readBounded(input: InputStream, cap: Int): ByteArray {
        val buffer = ByteArray(cap)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return if (total == buffer.size) buffer else buffer.copyOf(total)
    }

    private companion object {
        const val HEAD_BYTES = 64 * 1024
        const val GIF_HEAD_BYTES = 1024 * 1024
        const val BATCH_SIZE = 200
        val SNIFFABLE_MIME_TYPES = setOf(
            "image/gif", "image/webp", "image/png", "image/avif", "image/heif", "image/heic",
        )
    }
}

internal data class AnimationRow(val mediaId: MediaId, val dateModifiedSeconds: Long, val animated: Boolean)

private class AnimationOpenHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                media_id INTEGER PRIMARY KEY,
                date_modified INTEGER NOT NULL,
                animated INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first version of this database; nothing to migrate yet.
    }

    fun animatedIds(): Set<MediaId> {
        readableDatabase.rawQuery("SELECT media_id FROM $TABLE WHERE animated = 1", null).use { cursor ->
            val ids = mutableSetOf<MediaId>()
            while (cursor.moveToNext()) ids += MediaId(cursor.getLong(0))
            return ids
        }
    }

    fun knownRevisions(): Map<MediaId, Long> {
        readableDatabase.rawQuery("SELECT media_id, date_modified FROM $TABLE", null).use { cursor ->
            val revisions = mutableMapOf<MediaId, Long>()
            while (cursor.moveToNext()) revisions[MediaId(cursor.getLong(0))] = cursor.getLong(1)
            return revisions
        }
    }

    fun upsert(rows: List<AnimationRow>) {
        writableDatabase.transaction {
            rows.forEach { row ->
                val values = ContentValues().apply {
                    put("media_id", row.mediaId.value)
                    put("date_modified", row.dateModifiedSeconds)
                    put("animated", if (row.animated) 1 else 0)
                }
                insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun removeMissing(availableIds: Set<MediaId>) {
        val stale = knownRevisions().keys - availableIds
        if (stale.isEmpty()) return
        writableDatabase.transaction {
            stale.forEach { id -> delete(TABLE, "media_id = ?", arrayOf(id.value.toString())) }
        }
    }

    companion object {
        const val DATABASE_NAME = "foto_xplorr_traits.db"
        const val DATABASE_VERSION = 1
        const val TABLE = "animation"
    }
}
