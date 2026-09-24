package com.fotoxplorr.app.migration

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import com.fotoxplorr.app.favorites.FavoriteIdCodec
import com.fotoxplorr.app.media.buildSelection
import com.fotoxplorr.app.organize.decodeIds
import com.fotoxplorr.app.privacy.SensitiveIdCodec
import com.fotoxplorr.core.db.entity.decodeRecognitionList
import com.fotoxplorr.core.db.migration.LegacyCollection
import com.fotoxplorr.core.db.migration.LegacyEmbeddingRow
import com.fotoxplorr.core.db.migration.LegacyFaceRow
import com.fotoxplorr.core.db.migration.LegacyFailureRow
import com.fotoxplorr.core.db.migration.LegacyFolderLock
import com.fotoxplorr.core.db.migration.LegacyGeoRow
import com.fotoxplorr.core.db.migration.LegacyLibraryData
import com.fotoxplorr.core.db.migration.LegacyMediaRow
import com.fotoxplorr.core.db.migration.LegacyMomentFeedbackRow
import com.fotoxplorr.core.db.migration.LegacyRecognitionRow
import com.fotoxplorr.core.db.migration.LegacySource
import com.fotoxplorr.core.db.migration.LegacyTextBlockRow
import com.fotoxplorr.core.db.migration.LegacyTraitRow
import com.fotoxplorr.core.db.migration.LegacyVideoMomentRow
import com.fotoxplorr.core.organize.ScanPlan
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The real [LegacySource] for ADR-011's migration. It reads the six old SQLite files, the four
 * asset-keyed SharedPreferences files and MediaStore's own volume column.
 *
 * ## Read-only, and never through the old stores' own helpers
 * Each old database is opened directly with [SQLiteDatabase.OPEN_READONLY] and closed again per
 * call. It never goes through a `SQLiteOpenHelper` because a helper would run `onUpgrade` and
 * stamp its own version onto the file, and a read-write connection can switch a live file's
 * journal mode. Android skips both on a read-only connection. The live stores keep their own
 * connections open throughout, since the UI runs on them until cutover. SQLite handles one
 * writer and several readers across connections, so a reader here sees a committed snapshot.
 *
 * A database FILE that doesn't exist (a feature never used on this install) reads as empty, and
 * so does a TABLE that doesn't exist or a COLUMN an older schema version never had (see
 * [readTable] and [column]). A file that exists but can't be opened throws. Returning empty
 * there would silently drop user-authored rows (manual geo pins, moment feedback), so the step
 * fails instead and is retried on the next run.
 *
 * ## SharedPreferences
 * These are read through `Context.getSharedPreferences`, which hands back the process-wide
 * instance the live stores also use. A write still waiting in an `apply()` is therefore already
 * visible here. Decoding reuses each store's own codec where one is reachable ([FavoriteIdCodec],
 * [SensitiveIdCodec], LibraryStore's [decodeIds]), so negative-id filtering matches the live
 * store exactly: favourites and library sets drop negative ids, sensitive doesn't.
 *
 * Construct with any [Context]. Only the application context is kept, which matches how the
 * other Android glue classes here ([com.fotoxplorr.app.LibraryBackgroundWork],
 * [com.fotoxplorr.app.media.SqliteMediaRepository]) take their dependency.
 */
class AndroidLegacySource(context: Context) : LegacySource {
    private val appContext = context.applicationContext

    // volumeForMediaId is called once per migrated row, so one MediaStore query per call would
    // be 100k content-provider round trips on a big library. The first call loads every id's
    // volume in one query instead, and later calls read the cache. Guarded by volumeLock.
    private val volumeLock = Mutex()
    private var volumeById: HashMap<Long, String>? = null
    private var volumeSnapshotMaxId = Long.MAX_VALUE
    private val volumeLookups = HashMap<Long, String?>()

    // ---- foto_xplorr_catalogue.db ----------------------------------------------------------

    override suspend fun mediaRowsAfter(afterId: Long, limit: Int): List<LegacyMediaRow> {
        if (limit <= 0) return emptyList()
        return withContext(Dispatchers.IO) {
            readTable(LegacyStoreFiles.CATALOGUE_DB, "media", emptyList()) { db, columns ->
                // Both numbers are Kotlin Longs/Ints inlined directly, so there's nothing to
                // inject. Inlining also avoids Android's bind-everything-as-TEXT rawQuery args
                // reaching `LIMIT`.
                val sql = "SELECT " + listOf(
                    column(columns, "id"),
                    column(columns, "content_uri"),
                    column(columns, "display_name"),
                    column(columns, "mime_type"),
                    column(columns, "bucket_name"),
                    column(columns, "bucket_id"), // added in catalogue v2
                    column(columns, "date_taken", "0"),
                    column(columns, "date_modified", "0"),
                    column(columns, "width", "0"),
                    column(columns, "height", "0"),
                    column(columns, "size_bytes", "0"),
                    column(columns, "duration_millis", "0"), // added in catalogue v3
                    column(columns, "relative_path"),
                    column(columns, "is_favorite", "0"),
                    column(columns, "is_trashed", "0"),
                ).joinToString() + " FROM media WHERE id > $afterId ORDER BY id ASC LIMIT $limit"
                db.rawQuery(sql, null).use { cursor ->
                    buildList(cursor.count) {
                        while (cursor.moveToNext()) {
                            add(
                                LegacyMediaRow(
                                    mediaId = cursor.getLong(0),
                                    contentUri = cursor.getString(1).orEmpty(),
                                    displayName = cursor.getString(2).orEmpty(),
                                    mimeType = cursor.getString(3).orEmpty(),
                                    bucketName = cursor.stringOrNull(4),
                                    bucketId = cursor.longOrNull(5),
                                    dateTakenMs = cursor.getLong(6),
                                    // Genuinely seconds: the old table's own unit (MediaStore's
                                    // DATE_MODIFIED), see LegacyMediaRow's KDoc.
                                    dateModifiedSeconds = cursor.getLong(7),
                                    width = cursor.getInt(8),
                                    height = cursor.getInt(9),
                                    sizeBytes = cursor.getLong(10),
                                    durationMillis = cursor.getLong(11),
                                    relativePath = cursor.stringOrNull(12),
                                    isFavorite = cursor.getInt(13) != 0,
                                    isTrashed = cursor.getInt(14) != 0,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- MediaStore volumes (ADR-011 §3: VOLUME_NAME is API 29+) ---------------------------

    override suspend fun volumeForMediaId(mediaId: Long): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return volumeLock.withLock {
            val all = volumeById ?: withContext(Dispatchers.IO) { queryVolumes(onlyId = null) }.also {
                volumeById = it
                // An empty snapshot means MediaStore shows this app nothing at all. Probing
                // every row one by one would find nothing either, just slower.
                volumeSnapshotMaxId = it.keys.maxOrNull() ?: Long.MAX_VALUE
            }
            all[mediaId] ?: when {
                // MediaStore hands out _IDs in increasing order, so a missing id at or below the
                // snapshot's highest one is a file MediaStore no longer shows this app. It was
                // deleted, it's on a volume that has since been detached, or it's outside
                // Android 14's partial grant. A per-row query would only confirm that, and under
                // a partial grant that would mean one query per unselected photo.
                mediaId <= volumeSnapshotMaxId -> null
                // Newer than the snapshot: the scanner catalogued it after the snapshot was
                // taken, so one targeted query is worth it. The answer is cached, null included.
                mediaId in volumeLookups -> volumeLookups[mediaId]
                else -> withContext(Dispatchers.IO) { queryVolumes(onlyId = mediaId)[mediaId] }
                    .also { volumeLookups[mediaId] = it }
            }
        }
    }

    override suspend fun externalVolumeNames(): List<String> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(appContext).sorted()
        } else {
            emptyList()
        }
    }

    /**
     * `_ID -> VOLUME_NAME` over the same row set `AndroidMediaStoreScanner` catalogues. It uses
     * that scanner's own [buildSelection] and, on API 30+, the same `MATCH_TRASHED = INCLUDE`,
     * so a trashed row the old catalogue holds still resolves to its real volume. Without that
     * it would read as "unknown", and `MigrationToV2` would mark it OFFLINE on the primary
     * source.
     *
     * A missing media permission surfaces as the provider's own SecurityException and fails the
     * ASSETS step, instead of quietly mapping the whole library to "unknown volume". Under
     * Android 14's partial ("selected photos") grant, only the selected rows are visible, so every
     * other row comes back null.
     *
     * Empty below API 29, where the VOLUME_NAME column doesn't exist. Callers already return
     * early there, and the check is repeated here so the guard sits next to the API use.
     */
    private fun queryVolumes(onlyId: Long?): HashMap<Long, String> {
        val result = HashMap<Long, String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return result
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME)
        val base = buildSelection(ScanPlan.Full)
        val clause = if (onlyId == null) base.clause else "(${base.clause}) AND ${MediaStore.MediaColumns._ID}=?"
        val args = (if (onlyId == null) base.args else base.args + onlyId.toString()).toTypedArray()
        val resolver = appContext.contentResolver
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, clause)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection, queryArgs, null)
        } else {
            resolver.query(collection, projection, clause, args, null)
        }
        // A null cursor means the provider couldn't be reached. That's a transient failure, not a
        // library with no volumes, so fail the step and let it retry. Mapping every row to
        // "unknown" instead would turn the whole library OFFLINE.
        checkNotNull(cursor) { "MediaStore returned no cursor for the volume lookup" }
        // A handful of distinct volume names across the whole library, so each row shares one
        // String instance instead of carrying its own copy.
        val interned = HashMap<String, String>()
        cursor.use {
            while (it.moveToNext()) {
                if (it.isNull(1)) continue
                val volume = it.getString(1)
                result[it.getLong(0)] = interned.getOrPut(volume) { volume }
            }
        }
        return result
    }

    // ---- foto_xplorr_geo.db ----------------------------------------------------------------

    override suspend fun geoRows(): List<LegacyGeoRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.GEO_DB, "geo_metadata", emptyList()) { db, columns ->
            // `scanned` is deliberately not read: it has no column in the new schema. Every row
            // here was written with scanned = 1 anyway (see GeoOpenHelper.upsert/upsertManual).
            val sql = "SELECT " + listOf(
                column(columns, "media_id"),
                column(columns, "has_location", "0"),
                column(columns, "latitude"),
                column(columns, "longitude"),
                column(columns, "altitude"),
                column(columns, "direction"),
                column(columns, "manual", "0"), // added in geo v2
                column(columns, "checked_with_original", "0"), // added in geo v3
                column(columns, "source_revision"), // added in geo v3
            ).joinToString() + " FROM geo_metadata ORDER BY media_id"
            db.rawQuery(sql, null).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyGeoRow(
                                mediaId = cursor.getLong(0),
                                hasLocation = cursor.getInt(1) != 0,
                                latitude = cursor.doubleOrNull(2),
                                longitude = cursor.doubleOrNull(3),
                                altitude = cursor.doubleOrNull(4),
                                direction = cursor.doubleOrNull(5),
                                manual = cursor.getInt(6) != 0,
                                checkedWithOriginal = cursor.getInt(7) != 0,
                                sourceRevisionSeconds = cursor.longOrNull(8),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- foto_xplorr_recognition.db --------------------------------------------------------

    override suspend fun recognitionRows(): List<LegacyRecognitionRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.RECOGNITION_DB, "asset_recognition", emptyList()) { db, columns ->
            val sql = "SELECT " + listOf(
                column(columns, "media_id"),
                column(columns, "source_revision", "0"),
                column(columns, "face_count", "0"),
                column(columns, "pet_verdict", "'NONE'"),
                column(columns, "identity_verdict", "'NONE'"),
                column(columns, "labels", "''"),
                column(columns, "categories", "''"), // categories/caption/hashtags: v3
                column(columns, "caption", "''"),
                column(columns, "hashtags", "''"),
            ).joinToString() + " FROM asset_recognition ORDER BY media_id"
            db.rawQuery(sql, null).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyRecognitionRow(
                                mediaId = cursor.getLong(0),
                                sourceRevision = cursor.getLong(1),
                                faceCount = cursor.getInt(2),
                                // Carried verbatim, including a name this build's enum no longer
                                // has. The old reader maps that to NONE on read (enumOrNone), and
                                // the new reader can make the same call.
                                petVerdict = cursor.getString(3) ?: "NONE",
                                identityVerdict = cursor.getString(4) ?: "NONE",
                                // Split on U+001F with :core:db's own decoder, the exact inverse
                                // of the encodeRecognitionList MigrationToV2 re-joins with. Blank
                                // entries are dropped, as the old decodeLabels does. Category
                                // names are kept raw: the old decodeCategories also drops names
                                // its SceneCategory enum doesn't know, which is a read-time
                                // choice rather than something the stored data says.
                                labels = decodeRecognitionList(cursor.getString(5).orEmpty()),
                                categories = decodeRecognitionList(cursor.getString(6).orEmpty()),
                                caption = cursor.getString(7).orEmpty(),
                                hashtags = decodeRecognitionList(cursor.getString(8).orEmpty()),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun faceRows(): List<LegacyFaceRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.RECOGNITION_DB, "face_descriptor", emptyList()) { db, _ ->
            db.rawQuery(
                "SELECT media_id, face_index, relative_area, vector FROM face_descriptor ORDER BY media_id, face_index",
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyFaceRow(
                                mediaId = cursor.getLong(0),
                                faceIndex = cursor.getInt(1),
                                relativeArea = cursor.getDouble(2),
                                // Passed through byte-for-byte. Both the old column and the new
                                // FaceEntity.vector use the same encoding (little-endian float32
                                // per dimension, no header, see RecognitionStore's
                                // encodeVector), so decoding and re-encoding would only add a
                                // place to get it wrong.
                                vector = cursor.getBlob(3) ?: ByteArray(0),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun textBlockRows(): List<LegacyTextBlockRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.RECOGNITION_DB, "asset_text_block", emptyList()) { db, _ ->
            // LEFT and RIGHT are SQL keywords, so they're quoted as identifiers.
            db.rawQuery(
                "SELECT media_id, block_index, text, \"left\", \"top\", \"right\", \"bottom\" " +
                    "FROM asset_text_block ORDER BY media_id, block_index",
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyTextBlockRow(
                                mediaId = cursor.getLong(0),
                                blockIndex = cursor.getInt(1),
                                text = cursor.getString(2).orEmpty(),
                                left = cursor.getDouble(3),
                                top = cursor.getDouble(4),
                                right = cursor.getDouble(5),
                                bottom = cursor.getDouble(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** `recognition_failure` exists from recognition v4 on. An install whose recognition DB
     *  hasn't been opened since v3 simply has none. */
    override suspend fun recognitionFailures(): List<LegacyFailureRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.RECOGNITION_DB, "recognition_failure", emptyList()) { db, _ ->
            db.rawQuery(
                "SELECT media_id, revision, attempts, last_attempt_ms FROM recognition_failure ORDER BY media_id",
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyFailureRow(
                                mediaId = cursor.getLong(0),
                                modelSha = null,
                                revision = cursor.getLong(1),
                                attempts = cursor.getInt(2),
                                lastAttemptMs = cursor.getLong(3),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- foto_xplorr_traits.db -------------------------------------------------------------

    override suspend fun traitRows(): List<LegacyTraitRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.TRAITS_DB, "animation", emptyList()) { db, _ ->
            db.rawQuery("SELECT media_id, date_modified, animated FROM animation ORDER BY media_id", null).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyTraitRow(
                                mediaId = cursor.getLong(0),
                                dateModifiedSeconds = cursor.getLong(1),
                                animated = cursor.getInt(2) != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- foto_xplorr_embeddings.db ---------------------------------------------------------

    override suspend fun embeddingRows(): List<LegacyEmbeddingRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.EMBEDDINGS_DB, "embeddings", emptyList()) { db, _ ->
            db.rawQuery(
                "SELECT media_id, source_revision, model_sha, vector, signature, x, y FROM embeddings ORDER BY media_id",
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyEmbeddingRow(
                                mediaId = cursor.getLong(0),
                                sourceRevision = cursor.getLong(1),
                                modelSha = cursor.getString(2).orEmpty(),
                                // int8-quantised, one byte per dimension, no header. Passed
                                // through unchanged (EmbeddingRepository.quantize wrote it).
                                vector = cursor.getBlob(3) ?: ByteArray(0),
                                // Stored from an Int, so this widens it losslessly and keeps
                                // the sign.
                                signature = cursor.getLong(4),
                                x = cursor.doubleOrNull(5),
                                y = cursor.doubleOrNull(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** `embedding_failure` exists from embeddings v2 on. */
    override suspend fun embeddingFailures(): List<LegacyFailureRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.EMBEDDINGS_DB, "embedding_failure", emptyList()) { db, _ ->
            db.rawQuery(
                "SELECT media_id, model_sha, revision, attempts, last_attempt_ms FROM embedding_failure ORDER BY media_id, model_sha",
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyFailureRow(
                                mediaId = cursor.getLong(0),
                                modelSha = cursor.stringOrNull(1),
                                revision = cursor.getLong(2),
                                attempts = cursor.getInt(3),
                                lastAttemptMs = cursor.getLong(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- foto_xplorr_moments.db ------------------------------------------------------------

    override suspend fun videoMomentRows(): List<LegacyVideoMomentRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.MOMENTS_DB, "video_moment", emptyList()) { db, _ ->
            db.rawQuery(
                "SELECT media_id, position_ms, source, confidence, label FROM video_moment ORDER BY media_id, position_ms",
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyVideoMomentRow(
                                mediaId = cursor.getLong(0),
                                positionMs = cursor.getLong(1),
                                // "AUTO" | "MANUAL" (MomentSource.name), carried verbatim.
                                source = cursor.getString(2).orEmpty(),
                                confidence = cursor.getDouble(3),
                                label = cursor.getString(4).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun videoScannedIds(): Set<Long> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.MOMENTS_DB, "video_scanned", emptySet()) { db, _ ->
            db.rawQuery("SELECT media_id FROM video_scanned ORDER BY media_id", null).use { cursor ->
                buildSet(cursor.count) { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
        }
    }

    /** `moment_feedback` exists from moments v2 on. */
    override suspend fun videoMomentFeedbackRows(): List<LegacyMomentFeedbackRow> = withContext(Dispatchers.IO) {
        readTable(LegacyStoreFiles.MOMENTS_DB, "moment_feedback", emptyList()) { db, _ ->
            db.rawQuery(
                "SELECT media_id, position_ms, verdict FROM moment_feedback ORDER BY media_id, position_ms",
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            LegacyMomentFeedbackRow(
                                mediaId = cursor.getLong(0),
                                positionMs = cursor.getLong(1),
                                // "GOOD" | "BAD" (MomentFeedback.name), carried verbatim.
                                verdict = cursor.getString(2).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ---- SharedPreferences -----------------------------------------------------------------

    override suspend fun favoriteIds(): Set<Long> = withContext(Dispatchers.IO) {
        val preferences = prefs(LegacyStoreFiles.FAVORITES_PREFS)
        // FavoriteStore.KEY_FAVORITES. The codec drops negative ids, exactly as FavoriteStore.load
        // does.
        FavoriteIdCodec.decode(preferences.getStringSet("favorite_media_ids", emptySet()))
            .mapTo(linkedSetOf()) { it.value }
    }

    override suspend fun sensitiveIds(): Set<Long> = withContext(Dispatchers.IO) {
        val preferences = prefs(LegacyStoreFiles.SENSITIVE_PREFS)
        // SensitiveStore.KEY_SENSITIVE_IDS. SensitiveIdCodec does NOT filter negative ids, unlike
        // the favourites codec. Matched as-is.
        SensitiveIdCodec.decode(preferences.getStringSet("sensitive_media_ids", emptySet()).orEmpty())
            .mapTo(linkedSetOf()) { it.value }
    }

    /**
     * LibraryStore's whole preferences file, read the way `LibraryStore.load()` reads it:
     * - A tag exists when it's in `tag_names`. Its members are `tag_media:<b64>`, and its
     *   auto-provenance subset is `auto_tag_media:<b64>`. A `tag_media:` key whose name isn't
     *   indexed is invisible to the app, so it's ignored here too. An indexed tag with no members
     *   left is kept with an empty set (see LegacyLibraryData's KDoc).
     * - Rejected auto-tags use their own `rejected_auto_tag_names` index, since a rejected tag may
     *   no longer be in `tag_names`.
     * - A collection needs its `collection_name:` entry (load() drops one without it).
     *   `collection_created:` defaults to 0.
     * - A caption exists when its id is in `caption_media_ids` and `caption:<id>` holds non-empty
     *   text.
     *
     * The base64 is `NO_WRAP or URL_SAFE` over UTF-8 (see LibraryStore.tagMediaKey). That's not
     * the standard alphabet PrivateFolderStore uses, and mixing them up would find no members for
     * any tag containing a character whose encoding lands on '+' or '/'.
     */
    override suspend fun libraryData(): LegacyLibraryData = withContext(Dispatchers.IO) {
        val preferences = prefs(LegacyStoreFiles.LIBRARY_PREFS)
        fun names(key: String): List<String> =
            preferences.getStringSet(key, emptySet()).orEmpty().sorted()
        fun ids(key: String): Set<Long> =
            decodeIds(preferences.getStringSet(key, emptySet())).mapTo(linkedSetOf()) { it.value }
        fun tagKey(prefix: String, tag: String): String =
            prefix + Base64.encodeToString(tag.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

        val tagNames = names(LIBRARY_KEY_TAG_NAMES)
        val tagMembers = tagNames.associateWith { ids(tagKey(LIBRARY_TAG_MEDIA_PREFIX, it)) }
        val autoTagMembers = tagNames
            .associateWith { ids(tagKey(LIBRARY_AUTO_TAG_MEDIA_PREFIX, it)) }
            .filterValues { it.isNotEmpty() }
        val rejectedAutoTagMembers = names(LIBRARY_KEY_REJECTED_AUTO_TAG_NAMES)
            .associateWith { ids(tagKey(LIBRARY_REJECTED_AUTO_TAG_MEDIA_PREFIX, it)) }
            .filterValues { it.isNotEmpty() }

        val collections = names(LIBRARY_KEY_COLLECTION_IDS).mapNotNull { id ->
            val name = preferences.getString("collection_name:$id", null) ?: return@mapNotNull null
            LegacyCollection(
                id = id,
                name = name,
                createdAtMillis = preferences.getLong("collection_created:$id", 0L),
                mediaIds = ids("collection_media:$id"),
            )
        }.sortedWith(compareBy<LegacyCollection> { it.name.lowercase() }.thenBy { it.createdAtMillis })

        val captions = ids(LIBRARY_KEY_CAPTION_MEDIA_IDS)
            .associateWith { id -> preferences.getString("$LIBRARY_CAPTION_PREFIX$id", null).orEmpty() }
            .filterValues { it.isNotEmpty() }

        LegacyLibraryData(
            collections = collections,
            tagMembers = tagMembers,
            autoTagMembers = autoTagMembers,
            rejectedAutoTagMembers = rejectedAutoTagMembers,
            archivedIds = ids(LIBRARY_KEY_ARCHIVED_IDS),
            everUnarchivedIds = ids(LIBRARY_KEY_EVER_UNARCHIVED_IDS),
            rejectedArchiveSuggestionIds = ids(LIBRARY_KEY_REJECTED_ARCHIVE_SUGGESTION_IDS),
            captions = captions,
            machineCaptionIds = ids(LIBRARY_KEY_MACHINE_CAPTION_IDS),
            suppressedMachineCaptionIds = ids(LIBRARY_KEY_SUPPRESSED_MACHINE_CAPTION_IDS),
        )
    }

    /**
     * PrivateFolderStore's entries. A folder counts as locked when it has a `hash:` key, which is
     * what `loadFolderNames()` checks, so every `hash:` key becomes a lock. A lock whose salt is
     * missing or whose base64 doesn't decode can't be unlocked by the live store either (unlock()
     * returns false or throws). It's still migrated, with the bytes that did decode (possibly
     * empty), so the folder stays locked instead of silently becoming visible. Neither case is
     * reachable through PrivateFolderStore's own writes, which put both keys in one `apply()`.
     */
    override suspend fun folderLocks(): List<LegacyFolderLock> = withContext(Dispatchers.IO) {
        val entries = prefs(LegacyStoreFiles.PRIVATE_FOLDERS_PREFS).all
        entries.keys
            .filter { it.startsWith(PRIVATE_HASH_PREFIX) }
            .sorted()
            .map { key ->
                val folderKey = key.removePrefix(PRIVATE_HASH_PREFIX)
                LegacyFolderLock(
                    folderKey = folderKey,
                    salt = decodeStandardBase64(entries["$PRIVATE_SALT_PREFIX$folderKey"] as? String),
                    hash = decodeStandardBase64(entries[key] as? String),
                    iterations = PRIVATE_FOLDER_PBKDF2_ITERATIONS,
                )
            }
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    // ---- helpers ---------------------------------------------------------------------------

    private fun prefs(name: String) = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * Opens [databaseName] read-only, checks [table] exists, and hands [block] the database plus
     * the table's column names. Returns [whenAbsent] when the file or table doesn't exist.
     */
    private inline fun <T> readTable(
        databaseName: String,
        table: String,
        whenAbsent: T,
        block: (SQLiteDatabase, Set<String>) -> T,
    ): T {
        val file = appContext.getDatabasePath(databaseName)
        if (!file.isFile || file.length() == 0L) return whenAbsent
        // NO_LOCALIZED_COLLATORS: none of the old schemas use COLLATE LOCALIZED, and skipping it
        // keeps the open from doing any locale setup work on a connection that only reads.
        val db = SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        return try {
            val columns = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
            }
            if (columns.isEmpty()) whenAbsent else block(db, columns)
        } finally {
            db.close()
        }
    }

    /** `"name"`, or `fallback AS "name"` when an older schema version lacks the column. */
    private fun column(columns: Set<String>, name: String, fallback: String = "NULL"): String =
        if (name in columns) "\"$name\"" else "$fallback AS \"$name\""

    private fun decodeStandardBase64(value: String?): ByteArray =
        value?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: ByteArray(0)

    private companion object {
        // LibraryStore's private keys, copied verbatim (organize/LibraryStore.kt companion).
        const val LIBRARY_KEY_COLLECTION_IDS = "collection_ids"
        const val LIBRARY_KEY_TAG_NAMES = "tag_names"
        const val LIBRARY_KEY_ARCHIVED_IDS = "archived_ids"
        const val LIBRARY_TAG_MEDIA_PREFIX = "tag_media:"
        const val LIBRARY_AUTO_TAG_MEDIA_PREFIX = "auto_tag_media:"
        const val LIBRARY_REJECTED_AUTO_TAG_MEDIA_PREFIX = "rejected_auto_tag_media:"
        const val LIBRARY_KEY_REJECTED_AUTO_TAG_NAMES = "rejected_auto_tag_names"
        const val LIBRARY_KEY_CAPTION_MEDIA_IDS = "caption_media_ids"
        const val LIBRARY_CAPTION_PREFIX = "caption:"
        const val LIBRARY_KEY_MACHINE_CAPTION_IDS = "machine_caption_ids"
        const val LIBRARY_KEY_SUPPRESSED_MACHINE_CAPTION_IDS = "suppressed_machine_caption_ids"
        const val LIBRARY_KEY_EVER_UNARCHIVED_IDS = "ever_unarchived_ids"
        const val LIBRARY_KEY_REJECTED_ARCHIVE_SUGGESTION_IDS = "rejected_archive_suggestion_ids"

        // PrivateFolderStore's private keys (privacy/PrivateFolderStore.kt companion).
        const val PRIVATE_HASH_PREFIX = "hash:"
        const val PRIVATE_SALT_PREFIX = "salt:"

        /**
         * `PrivateFolderStore.ITERATIONS`. It's a private compile-time constant there and is not
         * stored per entry, so every legacy lock was derived with exactly this count (PBKDF2 with
         * HMAC-SHA256, 256-bit key, 16-byte salt). If that constant ever changes before cutover,
         * this must change with it, or migrated locks will never unlock.
         */
        const val PRIVATE_FOLDER_PBKDF2_ITERATIONS = 210_000
    }
}

private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

private fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
