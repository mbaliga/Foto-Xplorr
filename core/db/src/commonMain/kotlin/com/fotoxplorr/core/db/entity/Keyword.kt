package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

/**
 * ADR-011 §2. `UNIQUE(parent_id, name)` is the ADR's own binding DDL, but SQLite treats every
 * NULL in a UNIQUE constraint as distinct from every other NULL -- so this constraint does
 * **not** dedupe root-level keywords (`parent_id IS NULL`, which is every one of today's flat
 * tags once migrated). Callers (the migration, and any future "create keyword" path) must query
 * for an existing root keyword by name before inserting one, rather than relying on this
 * constraint to reject a duplicate -- see [KeywordDao.findRoot].
 */
@Entity(
    tableName = "keyword",
    foreignKeys = [
        ForeignKey(
            entity = KeywordEntity::class,
            parentColumns = ["keyword_id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["parent_id", "name"], unique = true)],
)
data class KeywordEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "keyword_id") val keywordId: Long,
    @ColumnInfo(name = "parent_id") val parentId: Long?,
    @ColumnInfo(name = "name") val name: String,
)

@Entity(
    tableName = "asset_keyword",
    primaryKeys = ["asset_id", "keyword_id"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KeywordEntity::class,
            parentColumns = ["keyword_id"],
            childColumns = ["keyword_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // The primary key (asset_id, keyword_id) already indexes asset_id as its leading column;
    // this covers lookups/cascades keyed on keyword_id alone (e.g. every asset carrying a given
    // keyword) without a full table scan.
    indices = [Index(value = ["keyword_id"])],
)
data class AssetKeywordEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "keyword_id") val keywordId: Long,
    /** USER | AUTO */
    @ColumnInfo(name = "origin") val origin: String,
)

/** "The auto-tagger may not re-add this." */
@Entity(
    tableName = "asset_keyword_rejection",
    primaryKeys = ["asset_id", "keyword_id"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KeywordEntity::class,
            parentColumns = ["keyword_id"],
            childColumns = ["keyword_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["keyword_id"])],
)
data class AssetKeywordRejectionEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "keyword_id") val keywordId: Long,
)

/** [AssetKeywordEntity.origin] values. */
object KeywordOrigin {
    const val USER = "USER"
    const val AUTO = "AUTO"
}
