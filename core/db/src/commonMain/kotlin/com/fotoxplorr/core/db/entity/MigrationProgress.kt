package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

/** ADR-011 §2/§5: migration bookkeeping -- one row per step, so the runner can resume after a
 *  kill at any point. */
@Entity(tableName = "migration_progress")
data class MigrationProgressEntity(
    @PrimaryKey @ColumnInfo(name = "step") val step: String,
    /** PENDING | RUNNING | DONE | FAILED */
    @ColumnInfo(name = "state") val state: String,
    /** Opaque, step-defined resume point (e.g. "the last media_id copied in this batch"). */
    @ColumnInfo(name = "cursor") val cursor: String?,
    @ColumnInfo(name = "updated_ms") val updatedMs: Long,
    /** VERIFY's counts/checks, or a failure's error text. */
    @ColumnInfo(name = "detail") val detail: String?,
)

/**
 * ADR-011 §5 step 4 (`ID_MAP`): despite the ADR's own prose calling this "a temp table", it is a
 * real, persisted Room table here, not a SQLite `TEMP TABLE` -- a literal temp table is
 * connection-scoped and would not survive the app process dying and the migration resuming later
 * (the kill-and-resume test requirement in ADR-011's own "Tests required" section), which a temp
 * table would silently break. "Temporary" here means "deleted once the migration reaches
 * `CLEANUP`", not "not really stored".
 */
@Entity(tableName = "legacy_id_map")
data class LegacyIdMapEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
)

/**
 * ADR-011 §5 step 5/7: user data whose old id has no [LegacyIdMapEntity] entry is **kept** here,
 * not dropped, so the gate report can count it. Not part of ADR-011 §2's binding DDL block (that
 * covers the live catalogue schema; this is migration-only bookkeeping), so its shape is this
 * migration's own design, not the ADR's.
 */
@Entity(
    tableName = "legacy_orphans",
    // The VERIFY step's catch-up pass re-runs USER_DATA/DERIVED (see MigrationToV2.run's own
    // comment), which would otherwise re-record the same orphan on every catch-up pass -- this
    // makes that insert idempotent (androidx.room.OnConflictStrategy.IGNORE) instead of
    // double-counting. (kind, media_id) alone isn't unique -- one asset can legitimately be an
    // orphan for several different tags/collections/moments, distinguished only by `detail`.
    indices = [androidx.room.Index(value = ["kind", "media_id", "detail"], unique = true)],
)
data class LegacyOrphanEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    /** FAVORITE | SENSITIVE | ARCHIVED | EVER_UNARCHIVED | REJECTED_ARCHIVE_SUGGESTION | TAG |
     *  REJECTED_AUTO_TAG | CAPTION | COLLECTION_MEMBER | GEO_MANUAL_PIN | VIDEO_MOMENT_MANUAL |
     *  VIDEO_MOMENT_FEEDBACK */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "detail") val detail: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
)

/** [MigrationProgressEntity.state] values. */
object MigrationStepState {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

/** [MigrationProgressEntity.step] values, in ADR-011 §5's order. */
object MigrationStep {
    const val EXPORT = "EXPORT"
    const val SOURCES = "SOURCES"
    const val ASSETS = "ASSETS"
    const val ID_MAP = "ID_MAP"
    const val USER_DATA = "USER_DATA"
    const val LOCKS = "LOCKS"
    const val DERIVED = "DERIVED"
    const val VERIFY = "VERIFY"
    const val CUTOVER = "CUTOVER"
    const val CLEANUP = "CLEANUP"

    val ORDER = listOf(EXPORT, SOURCES, ASSETS, ID_MAP, USER_DATA, LOCKS, DERIVED, VERIFY, CUTOVER, CLEANUP)
}

/** [LegacyOrphanEntity.kind] values. */
object OrphanKind {
    const val FAVORITE = "FAVORITE"
    const val SENSITIVE = "SENSITIVE"
    const val ARCHIVED = "ARCHIVED"
    const val EVER_UNARCHIVED = "EVER_UNARCHIVED"
    const val REJECTED_ARCHIVE_SUGGESTION = "REJECTED_ARCHIVE_SUGGESTION"
    const val TAG = "TAG"
    const val REJECTED_AUTO_TAG = "REJECTED_AUTO_TAG"
    const val CAPTION = "CAPTION"
    const val COLLECTION_MEMBER = "COLLECTION_MEMBER"
    const val GEO_MANUAL_PIN = "GEO_MANUAL_PIN"
    const val VIDEO_MOMENT_MANUAL = "VIDEO_MOMENT_MANUAL"
    const val VIDEO_MOMENT_FEEDBACK = "VIDEO_MOMENT_FEEDBACK"
}
