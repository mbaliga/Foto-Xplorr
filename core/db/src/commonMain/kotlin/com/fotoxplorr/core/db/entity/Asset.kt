package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.FormatId
import com.fotoxplorr.core.model.SourceId

/** ADR-011 §2: one row per file the app knows about, in any state. */
@Entity(
    tableName = "asset",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_id", "locator"], unique = true),
        Index(value = ["date_taken_ms", "date_modified_ms", "asset_id"], name = "asset_timeline"),
        Index(value = ["folder_key", "date_taken_ms"], name = "asset_folder"),
        Index(value = ["fingerprint"], name = "asset_fingerprint"),
        Index(value = ["availability", "trashed"], name = "asset_state"),
    ],
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "source_id") val sourceId: SourceId,
    /** MediaStore _ID as text; document id; path; remote id. */
    @ColumnInfo(name = "locator") val locator: String,
    /** Cached openable URI (MediaStore content URI). */
    @ColumnInfo(name = "content_uri") val contentUri: String?,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime") val mime: String,
    /** From :core:formats (sniffed where possible). */
    @ColumnInfo(name = "format_id") val formatId: FormatId,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "orientation", defaultValue = "0") val orientation: Int = 0,
    @ColumnInfo(name = "duration_ms", defaultValue = "0") val durationMs: Long = 0,
    @ColumnInfo(name = "date_taken_ms") val dateTakenMs: Long,
    @ColumnInfo(name = "date_modified_ms") val dateModifiedMs: Long,
    @ColumnInfo(name = "date_added_ms") val dateAddedMs: Long?,
    @ColumnInfo(name = "relative_path") val relativePath: String?,
    /** Source-scoped: see ADR-011 §4. */
    @ColumnInfo(name = "folder_key") val folderKey: String,
    @ColumnInfo(name = "bucket_id") val bucketId: Long?,
    @ColumnInfo(name = "bucket_name") val bucketName: String?,
    /** size + xxHash64(first 64 KiB) + xxHash64(last 64 KiB); filled lazily (WP1.4). */
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    /** Full hash, lazy, only when needed (duplicates, backup). */
    @ColumnInfo(name = "content_hash") val contentHash: String?,
    @ColumnInfo(name = "xmp_document_id") val xmpDocumentId: String?,
    /** ONLINE | OFFLINE | MISSING_CONFIRMED | UNREADABLE */
    @ColumnInfo(name = "availability") val availability: String,
    @ColumnInfo(name = "trashed", defaultValue = "0") val trashed: Boolean = false,
    @ColumnInfo(name = "trashed_at_ms") val trashedAtMs: Long?,
    /** MediaStore GENERATION_MODIFIED at the last read. */
    @ColumnInfo(name = "ms_generation_modified") val msGenerationModified: Long?,
    /** Bumps whenever size/date_modified/content changes; derived-data key. */
    @ColumnInfo(name = "revision") val revision: Long,
)

/** [AssetEntity.availability] values. */
object Availability {
    const val ONLINE = "ONLINE"
    const val OFFLINE = "OFFLINE"
    const val MISSING_CONFIRMED = "MISSING_CONFIRMED"
    const val UNREADABLE = "UNREADABLE"
}
