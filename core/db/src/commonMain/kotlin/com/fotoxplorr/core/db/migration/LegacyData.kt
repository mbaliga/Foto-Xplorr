package com.fotoxplorr.core.db.migration

/**
 * ADR-011 §5: the shape of every old store, read-only, as the migration needs it. Field names
 * and semantics come straight from the pre-migration survey of the real Android code (see
 * MASTER-PROGRESS.md's WP1.3 Decisions) -- not from ADR-011's own DDL, which describes the *new*
 * schema. This is deliberately a plain data model, not the old stores' own row/entity types, so
 * the same [MigrationToV2] logic can run against a JVM test fixture (via [LegacySource]
 * implementations backed by `org.xerial:sqlite-jdbc` + a fake prefs map) and against the real
 * Android stores (via `:app`'s [LegacySource] implementations), unchanged.
 */

/** `foto_xplorr_catalogue.db`'s `media` table. `dateModifiedSeconds` is genuinely seconds (the
 *  old table's own unit) -- multiply by 1000 when writing `asset.date_modified_ms`. */
data class LegacyMediaRow(
    val mediaId: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val bucketName: String?,
    val bucketId: Long?,
    val dateTakenMs: Long,
    val dateModifiedSeconds: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val durationMillis: Long,
    val relativePath: String?,
    /** MediaStore's own IS_FAVORITE (API 30+), already folded into this row by the old scanner;
     *   0/false on API < 30. ADR-011 requires OR-ing this into `asset_user.favorite` alongside
     *  the `FavoriteStore` id set -- see [LegacyPreferencesSource.favoriteIds]. */
    val isFavorite: Boolean,
    val isTrashed: Boolean,
)

/** `foto_xplorr_geo.db`'s `geo_metadata`. A manual pin is `manual = true, hasLocation = true`
 *  (see [GeoMetadataRepository.upsertManual] in the survey) -- `sourceRevisionSeconds` is null
 *  for those (the repository only ever sets it while indexing, not on a manual pin). */
data class LegacyGeoRow(
    val mediaId: Long,
    val hasLocation: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val direction: Double?,
    val manual: Boolean,
    val checkedWithOriginal: Boolean,
    val sourceRevisionSeconds: Long?,
)

data class LegacyRecognitionRow(
    val mediaId: Long,
    val sourceRevision: Long,
    val faceCount: Int,
    val petVerdict: String,
    val identityVerdict: String,
    /** Already split on the old U+001F separator -- callers re-join with
     *  [com.fotoxplorr.core.db.entity.encodeRecognitionList] for the new row. */
    val labels: List<String>,
    val categories: List<String>,
    val caption: String,
    val hashtags: List<String>,
)

data class LegacyFaceRow(val mediaId: Long, val faceIndex: Int, val relativeArea: Double, val vector: ByteArray)

data class LegacyTextBlockRow(
    val mediaId: Long,
    val blockIndex: Int,
    val text: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/** Covers both `recognition_failure` and `embedding_failure` -- the latter also carries
 *  [modelSha]. */
data class LegacyFailureRow(val mediaId: Long, val modelSha: String?, val revision: Long, val attempts: Int, val lastAttemptMs: Long)

data class LegacyTraitRow(val mediaId: Long, val dateModifiedSeconds: Long, val animated: Boolean)

data class LegacyEmbeddingRow(
    val mediaId: Long,
    val sourceRevision: Long,
    val modelSha: String,
    val vector: ByteArray,
    val signature: Long,
    val x: Double?,
    val y: Double?,
)

data class LegacyVideoMomentRow(val mediaId: Long, val positionMs: Long, val source: String, val confidence: Double, val label: String)

data class LegacyMomentFeedbackRow(val mediaId: Long, val positionMs: Long, val verdict: String)

/** `LibraryStore`'s full contents (ADR-011 §5 step 5). [tagMembers]/[autoTagMembers] are keyed
 *  by the raw, case-preserved tag name; every key in [tagMembers] becomes a root [keyword],
 *  including one with an empty member set (a tag that lost its last photo but is still recorded
 *  in `tag_names`, per the survey). [autoTagMembers] entries not present in the matching
 *  [tagMembers] set are a known existing-data anomaly (survey trap: `importJson` never
 *  intersects them) -- [MigrationToV2] treats them as `AUTO`-origin members anyway and links
 *  them, since the alternative (silently dropping user-visible auto-tags) is worse. */
data class LegacyLibraryData(
    val collections: List<LegacyCollection>,
    val tagMembers: Map<String, Set<Long>>,
    val autoTagMembers: Map<String, Set<Long>>,
    val rejectedAutoTagMembers: Map<String, Set<Long>>,
    val archivedIds: Set<Long>,
    val everUnarchivedIds: Set<Long>,
    val rejectedArchiveSuggestionIds: Set<Long>,
    val captions: Map<Long, String>,
    val machineCaptionIds: Set<Long>,
    val suppressedMachineCaptionIds: Set<Long>,
)

data class LegacyCollection(val id: String, val name: String, val createdAtMillis: Long, val mediaIds: Set<Long>)

/** `PrivateFolderStore`'s locked-folder entries. [iterations] isn't actually stored per entry
 *  today (`ITERATIONS = 210_000` is a compile-time constant, per the survey) -- callers pass
 *  that constant in, this type exists only so [MigrationToV2] doesn't hardcode it itself. */
data class LegacyFolderLock(val folderKey: String, val salt: ByteArray, val hash: ByteArray, val iterations: Int)

/**
 * What the migration reads from the seven old stores plus MediaStore itself. [:app] provides
 * the real implementation (SQLite files + SharedPreferences + a live `MediaStore` query); JVM
 * tests provide one backed by `org.xerial:sqlite-jdbc` and an in-memory prefs map, per ADR-011's
 * "Tests required (JVM unless noted)".
 */
interface LegacySource {
    /** Ordered by `media_id` ascending, `mediaId > afterId`, at most `limit` rows -- ADR-011 §5
     *  step 3's "batches of 2,000", and the resume point after a kill. */
    suspend fun mediaRowsAfter(afterId: Long, limit: Int): List<LegacyMediaRow>

    /** The MediaStore volume a media id's row belongs to (API 29+ only) -- null if unknown
     *  (API 26-28, or the id no longer resolves), in which case the row goes to the primary
     *  source (ADR-011 §5 step 3). */
    suspend fun volumeForMediaId(mediaId: Long): String?

    /** Every external volume name MediaStore currently reports, for the SOURCES step -- API 29+
     *  only; empty on API 26-28 (the primary source covers everything there). */
    suspend fun externalVolumeNames(): List<String>

    suspend fun geoRows(): List<LegacyGeoRow>
    suspend fun recognitionRows(): List<LegacyRecognitionRow>
    suspend fun faceRows(): List<LegacyFaceRow>
    suspend fun textBlockRows(): List<LegacyTextBlockRow>
    suspend fun recognitionFailures(): List<LegacyFailureRow>
    suspend fun traitRows(): List<LegacyTraitRow>
    suspend fun embeddingRows(): List<LegacyEmbeddingRow>
    suspend fun embeddingFailures(): List<LegacyFailureRow>
    suspend fun videoMomentRows(): List<LegacyVideoMomentRow>
    suspend fun videoScannedIds(): Set<Long>
    suspend fun videoMomentFeedbackRows(): List<LegacyMomentFeedbackRow>

    suspend fun favoriteIds(): Set<Long>
    suspend fun sensitiveIds(): Set<Long>
    suspend fun libraryData(): LegacyLibraryData
    suspend fun folderLocks(): List<LegacyFolderLock>

    /** The current time, injected so tests are deterministic -- never `Clock.System.now()`
     *  called directly from migration logic. */
    fun nowMs(): Long
}
