package com.fotoxplorr.app.recognition

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.core.model.MediaId
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
 *
 * **Palettes are persisted too** (v5): up to five dominant colours per photo, each a packed ARGB
 * int and a share of the image. That is the whole of what `colour:` search and the details room's
 * tappable swatches need, and it says nothing about the photo a thumbnail does not already say.
 */
class RecognitionStore(context: Context) {
    private val helper = RecognitionOpenHelper(context.applicationContext)
    private val mutex = Mutex()
    private val index = MutableStateFlow(RecognitionIndex.EMPTY)
    private val progress = MutableStateFlow(RecognitionProgress())

    /** Guarded by [mutex]; see [upsert]'s own note on why this is throttled, not per-batch. */
    private var lastReloadAtMs = 0L

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

    /**
     * Assets whose recognition result is absent or computed from an older file revision --
     * excluding one whose last [MAX_FAILURE_ATTEMPTS] analysis attempts, at its CURRENT
     * revision, all failed (P0-12). A changed file (a new [MediaAsset.recognitionRevision]) is
     * still retried regardless of a past failure count, since it is effectively a new item.
     */
    suspend fun pendingAssets(assets: List<MediaAsset>): List<MediaAsset> = withContext(Dispatchers.IO) {
        val revisions = helper.revisions()
        val failures = helper.failures()
        assets.filter { asset ->
            if (asset.isVideo || asset.isTrashed) return@filter false
            val revision = asset.recognitionRevision()
            if (revisions[asset.id] == revision) return@filter false
            val failure = failures[asset.id]
            !(failure != null && failure.revision == revision && failure.attempts >= MAX_FAILURE_ATTEMPTS)
        }
    }

    /**
     * Assets already recognised at their current revision whose palette has never been sampled --
     * in practice, every photo indexed before palettes were stored (v5). They need one cheap
     * decode each, not a second run of the three detectors, which is why this is a separate
     * question from [pendingAssets] and the indexer answers it in a separate pass.
     *
     * "Never sampled" means no row at all in the palette table: a photo whose sample turned out
     * to contain nothing visible is still recorded (see the helper's `writePalette`), so it is
     * asked once, not on every pass. The same bounded-retry rule as [pendingAssets] applies, so a
     * file that cannot be decoded for its palette is also given up on after
     * [MAX_FAILURE_ATTEMPTS].
     */
    suspend fun pendingPaletteOnly(assets: List<MediaAsset>): List<MediaAsset> = withContext(Dispatchers.IO) {
        val revisions = helper.revisions()
        val sampled = helper.paletteMediaIds()
        val failures = helper.failures()
        assets.filter { asset ->
            if (asset.isVideo || asset.isTrashed) return@filter false
            val revision = asset.recognitionRevision()
            if (revisions[asset.id] != revision || asset.id in sampled) return@filter false
            val failure = failures[asset.id]
            !(failure != null && failure.revision == revision && failure.attempts >= MAX_FAILURE_ATTEMPTS)
        }
    }

    /**
     * Records that analysing [mediaId] at [revision] failed: a repeat failure at the SAME
     * revision increments the attempt count, while a revision change (the file was replaced)
     * resets it to 1, since a different file deserves a fresh three attempts (P0-12).
     */
    suspend fun recordFailure(mediaId: MediaId, revision: Long) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.recordFailure(mediaId, revision, System.currentTimeMillis()) }
    }

    /** Clears any recorded failure for [mediaId] -- called once analysis succeeds (P0-12). */
    suspend fun clearFailure(mediaId: MediaId) = withContext(Dispatchers.IO) {
        mutex.withLock { helper.clearFailure(mediaId) }
    }

    /**
     * P0-12: previously called [reload] -- a full-table read plus a from-scratch
     * [RecognitionIndex.from] re-clustering of the whole library -- after EVERY batch this is
     * called with (every 24 photos during a pass). [RecognitionIndex] is a key of
     * `GalleryScreen`'s own projection memo, so each of those reloads forced a full
     * re-derivation of the whole catalogue on the main thread, several times a second on a fast
     * device -- the actual defect (see the brief's own "Why"), independent of how expensive
     * computing a fresh index is.
     *
     * A true incremental update ([RecognitionIndex.plus]) cannot fix this on its own: it is
     * proven equal to [RecognitionIndex.from] (see `RecognitionIndexPlusTest`) but pays the same
     * global-reclustering cost, since [FaceClustering.cluster] and the FRIENDS_FAMILY category
     * are both library-wide, not per-row. So the fix taken here is the brief's own documented
     * fallback: [reload] runs at most once every [RELOAD_THROTTLE_MS], with
     * [RecognitionIndexer.index] making one further, unthrottled call once the whole pass ends
     * so the final state is never left stale.
     */
    suspend fun upsert(rows: List<AssetRecognition>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val dueForReload = mutex.withLock {
            helper.upsert(rows)
            reloadDue()
        }
        if (dueForReload) reload()
    }

    /**
     * Writes palettes for photos whose recognition rows already exist -- the palette-only pass's
     * own upsert. Replaces whatever palette rows those photos had; touches nothing else about
     * them. Throttled exactly as [upsert] is, for exactly the same reason.
     */
    suspend fun upsertPalettes(palettes: Map<MediaId, List<PaletteSwatch>>) = withContext(Dispatchers.IO) {
        if (palettes.isEmpty()) return@withContext
        val dueForReload = mutex.withLock {
            helper.upsertPalettes(palettes)
            reloadDue()
        }
        if (dueForReload) reload()
    }

    /** Guarded by [mutex]: has [RELOAD_THROTTLE_MS] passed since the last reload this allowed? */
    private fun reloadDue(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastReloadAtMs >= RELOAD_THROTTLE_MS).also { due -> if (due) lastReloadAtMs = now }
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

    private companion object {
        const val MAX_FAILURE_ATTEMPTS = 3
        const val RELOAD_THROTTLE_MS = 2_000L
    }
}

/** A recorded analysis failure (P0-12): see [RecognitionStore.recordFailure]. */
internal data class RecognitionFailure(val revision: Long, val attempts: Int)

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
                labels TEXT NOT NULL DEFAULT '',
                categories TEXT NOT NULL DEFAULT '',
                caption TEXT NOT NULL DEFAULT '',
                hashtags TEXT NOT NULL DEFAULT ''
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
        createFailureTable(db)
        createPaletteTable(db)
    }

    /**
     * v4 (P0-12) added [TABLE_FAILURES], bookkeeping for how many times analysing an asset has
     * failed. Unlike the asset/face/text tables above, this is NOT a derived cache to rebuild --
     * it exists specifically so a failure survives a process restart -- so the v3->v4 step is
     * additive: it only adds the new table, keeping every existing asset/face/text row. An
     * upgrade from further back (pre-v3) still drops and rebuilds those three, exactly as
     * before, since that policy predates and is unrelated to this task.
     *
     * v5 added [TABLE_PALETTE], and is additive for a different reason: the palette IS a derived
     * cache, but dropping the asset/face/text rows to get it would cost every photo a second run
     * of three detectors it already sat through, when all a palette needs is one 100px decode.
     * So the step creates the (empty) palette table and keeps every existing row; the indexer's
     * palette-only pass then fills the table in without touching anything else -- see
     * [RecognitionStore.pendingPaletteOnly]. Both `CREATE TABLE IF NOT EXISTS` calls are
     * idempotent, so any v3+ start version lands on the same v5 schema.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            // Recognition data is a derived cache: rebuilding is cheaper and safer than migrating.
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TEXT")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_FACES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ASSETS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PALETTE")
            onCreate(db)
            return
        }
        createFailureTable(db)
        createPaletteTable(db)
    }

    /**
     * One row per stored colour. `argb` is the packed opaque colour, `proportion` its share of
     * the sampled image in 0..1. A photo that was sampled and found to have nothing visible gets
     * a single row with `proportion = 0` -- the "asked, nothing there" marker that keeps it out of
     * the palette-only pass; see [paletteMediaIds] and [writePalette].
     */
    private fun createPaletteTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PALETTE (
                media_id INTEGER NOT NULL,
                swatch_index INTEGER NOT NULL,
                argb INTEGER NOT NULL,
                proportion REAL NOT NULL,
                PRIMARY KEY (media_id, swatch_index)
            )
            """.trimIndent(),
        )
    }

    private fun createFailureTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FAILURES (
                media_id INTEGER PRIMARY KEY,
                revision INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                last_attempt_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    fun revisions(): Map<MediaId, Long> = buildMap {
        readableDatabase.query(
            TABLE_ASSETS, arrayOf("media_id", "source_revision"),
            null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) put(MediaId(cursor.getLong(0)), cursor.getLong(1))
        }
    }

    fun failures(): Map<MediaId, RecognitionFailure> = buildMap {
        readableDatabase.query(
            TABLE_FAILURES, arrayOf("media_id", "revision", "attempts"),
            null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                put(MediaId(cursor.getLong(0)), RecognitionFailure(cursor.getLong(1), cursor.getInt(2)))
            }
        }
    }

    fun recordFailure(mediaId: MediaId, revision: Long, atMs: Long) {
        val previous = readableDatabase.query(
            TABLE_FAILURES, arrayOf("revision", "attempts"),
            "media_id = ?", arrayOf(mediaId.value.toString()), null, null, null,
        ).use { cursor -> if (cursor.moveToFirst()) RecognitionFailure(cursor.getLong(0), cursor.getInt(1)) else null }
        val attempts = if (previous?.revision == revision) previous.attempts + 1 else 1
        writableDatabase.insertWithOnConflict(
            TABLE_FAILURES, null,
            ContentValues().apply {
                put("media_id", mediaId.value)
                put("revision", revision)
                put("attempts", attempts)
                put("last_attempt_ms", atMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clearFailure(mediaId: MediaId) {
        writableDatabase.delete(TABLE_FAILURES, "media_id = ?", arrayOf(mediaId.value.toString()))
    }

    /** Every photo with ANY palette row -- including the "sampled, nothing visible" marker row. */
    fun paletteMediaIds(): Set<MediaId> = buildSet {
        readableDatabase.query(
            true, TABLE_PALETTE, arrayOf("media_id"),
            null, null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) add(MediaId(cursor.getLong(0)))
        }
    }

    /**
     * Every stored palette by media id, in swatch order. Omits photos whose only row is the
     * nothing-visible marker.
     *
     * Never throws on a bad row. A photo whose rows cannot be read as a palette -- a NULL where a
     * number belongs, a share that is not finite or is above 1 -- comes back with no palette at
     * all, rather than a half-garbage one or an exception that takes the whole index down with
     * it. A share of zero or less is skipped silently: that is the marker row, and it is also what
     * SQLite hands back for text that is not a number, which is the right answer for both.
     */
    private fun readPalettes(): Map<Long, List<PaletteSwatch>> {
        val palettes = HashMap<Long, MutableList<PaletteSwatch>>()
        val malformed = HashSet<Long>()
        readableDatabase.query(
            TABLE_PALETTE, arrayOf("media_id", "argb", "proportion"),
            null, null, null, null, "media_id ASC, swatch_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(0)
                if (cursor.isNull(1) || cursor.isNull(2)) {
                    malformed += mediaId
                    continue
                }
                val proportion = cursor.getFloat(2)
                if (proportion.isNaN() || proportion > MAX_PROPORTION) {
                    malformed += mediaId
                    continue
                }
                if (proportion <= 0f) continue
                palettes.getOrPut(mediaId) { mutableListOf() } +=
                    PaletteSwatch(argb = cursor.getInt(1) or OPAQUE, proportion = proportion)
            }
        }
        malformed.forEach(palettes::remove)
        return palettes
    }

    fun upsertPalettes(palettes: Map<MediaId, List<PaletteSwatch>>) {
        writableDatabase.transaction {
            palettes.forEach { (mediaId, palette) -> writePalette(mediaId, palette) }
        }
    }

    /**
     * Replaces [mediaId]'s palette rows. An empty palette still writes one marker row (share 0),
     * so "sampled, nothing visible" is told apart from "never sampled" -- without it every fully
     * transparent PNG in the library would be decoded again on every pass, forever.
     */
    private fun SQLiteDatabase.writePalette(mediaId: MediaId, palette: List<PaletteSwatch>) {
        delete(TABLE_PALETTE, "media_id = ?", arrayOf(mediaId.value.toString()))
        if (palette.isEmpty()) {
            insertWithOnConflict(
                TABLE_PALETTE, null,
                ContentValues().apply {
                    put("media_id", mediaId.value)
                    put("swatch_index", 0)
                    put("argb", 0)
                    put("proportion", 0f)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            return
        }
        palette.forEachIndexed { index, swatch ->
            insertWithOnConflict(
                TABLE_PALETTE, null,
                ContentValues().apply {
                    put("media_id", mediaId.value)
                    put("swatch_index", index)
                    put("argb", swatch.argb)
                    put("proportion", swatch.proportion)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun readAll(): List<AssetRecognition> {
        val palettes = readPalettes()
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
                    "labels", "categories", "caption", "hashtags",
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
                            categories = decodeCategories(cursor.getString(6)),
                            caption = cursor.getString(7).orEmpty(),
                            hashtags = decodeLabels(cursor.getString(8)),
                            palette = palettes[mediaId].orEmpty(),
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
                        put("categories", encodeCategories(row.categories))
                        put("caption", row.caption)
                        put("hashtags", encodeLabels(row.hashtags))
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
                writePalette(row.mediaId, row.palette)
            }
        }
    }

    fun removeMissing(availableIds: Set<MediaId>) {
        // Union with failures().keys (P0-12): an asset that has ONLY ever failed has no row in
        // TABLE_ASSETS at all, so revisions().keys alone would never see it as "known" and its
        // failure bookkeeping would never be cleaned up even once the asset itself is long gone.
        val known = revisions().keys + failures().keys
        val stale = known - availableIds
        if (stale.isEmpty()) return
        writableDatabase.transaction {
            stale.forEach { id ->
                val args = arrayOf(id.value.toString())
                delete(TABLE_TEXT, "media_id = ?", args)
                delete(TABLE_FACES, "media_id = ?", args)
                delete(TABLE_PALETTE, "media_id = ?", args)
                delete(TABLE_ASSETS, "media_id = ?", args)
                delete(TABLE_FAILURES, "media_id = ?", args)
            }
        }
    }

    fun clear() {
        writableDatabase.apply {
            delete(TABLE_TEXT, null, null)
            delete(TABLE_FACES, null, null)
            delete(TABLE_PALETTE, null, null)
            delete(TABLE_ASSETS, null, null)
            delete(TABLE_FAILURES, null, null)
        }
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_recognition.db"
        // v3 added categories/caption/hashtags for the on-device scene-recognition and
        // captioning work; v4 (P0-12) added TABLE_FAILURES, additively -- see onUpgrade above.
        // Recognition data in the first three tables is a derived cache, so bumping across THOSE
        // is a version bump and a wider CREATE TABLE, not a migration; TABLE_FAILURES itself is
        // not a derived cache (a failure needs to survive a process restart), which is exactly
        // why its own arrival is additive rather than another drop-and-recreate. v5 added
        // TABLE_PALETTE, also additively -- see onUpgrade for why a derived cache still earns an
        // additive step this time.
        const val DATABASE_VERSION = 5
        const val TABLE_ASSETS = "asset_recognition"
        const val TABLE_FACES = "face_descriptor"
        const val TABLE_FAILURES = "recognition_failure"
        const val TABLE_PALETTE = "asset_palette"
        const val TABLE_TEXT = "asset_text_block"

        /** A share above this cannot have come from the quantiser; the row is garbage. */
        const val MAX_PROPORTION = 1.0001f
        const val OPAQUE = 0xFF shl 24

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

// Reused as-is for AssetRecognition.hashtags below: a hashtag list has exactly the same shape
// as a label list -- a short list of short strings, read back whole alongside the same row --
// so a second, identically-bodied function with a different name would be duplication for its
// own sake.

/**
 * [SceneCategory] set as the same delimited string [encodeLabels] uses -- a category is exactly
 * the same shape as a label for storage purposes (a short machine word, no delimiter char in
 * it), so this reuses the same separator rather than inventing a fifth encoding.
 *
 * Decoding looks names up with `SceneCategory.entries.firstOrNull` rather than
 * `SceneCategory.valueOf`, the same defensive choice [enumOrNone] makes for pet/identity
 * verdicts: a row written by a build with a category this build no longer has must be dropped
 * silently, not crash the whole read.
 */
internal fun encodeCategories(categories: Set<SceneCategory>): String =
    categories.joinToString(LABEL_SEPARATOR) { it.name }

internal fun decodeCategories(stored: String?): Set<SceneCategory> =
    stored?.split(LABEL_SEPARATOR)
        ?.filter { it.isNotBlank() }
        ?.mapNotNullTo(linkedSetOf()) { name -> SceneCategory.entries.firstOrNull { it.name == name } }
        .orEmpty()

private const val LABEL_SEPARATOR = ""
