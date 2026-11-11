package com.fotoxplorr.core.db.sources

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `fotoz-sources.db` (ADR-020 decision 1). Rebuildable from the folders themselves; losing it
 * loses no user data. Every primary key is AUTOINCREMENT so a row number is never reused, which
 * is what makes `-(2^33) - row` a collision-free media id. Column values for kind/state are the
 * wire strings of `com.fotoxplorr.core.index.sources.SourceKind` / `SourceState`.
 */
@Entity(tableName = "source", indices = [Index(value = ["kind", "root_locator"], unique = true)])
data class SourceRecord(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "source_row") val sourceRow: Long = 0,
    @ColumnInfo(name = "kind") val kind: String,
    /** Tree uri for SAF_TREE / USB_SAF. */
    @ColumnInfo(name = "root_locator") val rootLocator: String,
    @ColumnInfo(name = "volume_uuid") val volumeUuid: String?,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "last_full_scan_ms") val lastFullScanMs: Long? = null,
)

@Entity(
    tableName = "source_folder",
    foreignKeys = [ForeignKey(SourceRecord::class, ["source_row"], ["source_row"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["source_row", "document_id"], unique = true)],
)
data class SourceFolderRecord(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "folder_row") val folderRow: Long = 0,
    @ColumnInfo(name = "source_row") val sourceRow: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    /** Path inside the tree, no leading or trailing slash; empty for the root. */
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "last_seen_run") val lastSeenRun: Long = 0,
)

@Entity(
    tableName = "source_asset",
    foreignKeys = [ForeignKey(SourceRecord::class, ["source_row"], ["source_row"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["source_row", "document_id"], unique = true), Index(value = ["source_row", "last_seen_run"])],
)
data class SourceAssetRecord(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "asset_row") val assetRow: Long = 0,
    @ColumnInfo(name = "source_row") val sourceRow: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "date_taken_ms") val dateTakenMs: Long = 0,
    @ColumnInfo(name = "date_modified_ms") val dateModifiedMs: Long = 0,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "content_uri") val contentUri: String?,
    /** 1 present, 0 not seen by the last completed full walk. Rows are never deleted by a sweep. */
    @ColumnInfo(name = "present", defaultValue = "1") val present: Boolean = true,
    @ColumnInfo(name = "last_seen_run") val lastSeenRun: Long = 0,
    /** 0 not enriched, 1 enriched, 2 unreadable (retry later; "unreadable" is not "absent"). */
    @ColumnInfo(name = "enrich_state", defaultValue = "0") val enrichState: Int = 0,
    @ColumnInfo(name = "enrich_attempts", defaultValue = "0") val enrichAttempts: Int = 0,
)

/** One walk of one source and its resumable checkpoint. */
@Entity(
    tableName = "index_run",
    foreignKeys = [ForeignKey(SourceRecord::class, ["source_row"], ["source_row"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["source_row", "status"])],
)
data class IndexRunRecord(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "run_id") val runId: Long = 0,
    @ColumnInfo(name = "source_row") val sourceRow: Long,
    /** RUNNING | COMPLETED | FAILED | CANCELLED */
    @ColumnInfo(name = "status") val status: String,
    /** True when this walk intends to read the source's entire extent. */
    @ColumnInfo(name = "full") val full: Boolean,
    @ColumnInfo(name = "cursor") val cursor: String?,
    @ColumnInfo(name = "files_seen") val filesSeen: Long = 0,
    @ColumnInfo(name = "started_ms") val startedMs: Long,
    @ColumnInfo(name = "finished_ms") val finishedMs: Long? = null,
    @ColumnInfo(name = "error") val error: String? = null,
)
