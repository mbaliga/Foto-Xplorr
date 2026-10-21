package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

@Entity(tableName = "collection")
data class CollectionEntity(
    /** Keeps today's ids (UUID strings). */
    @PrimaryKey @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
)

@Entity(
    tableName = "collection_member",
    primaryKeys = ["collection_id", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["collection_id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Primary key (collection_id, asset_id) indexes collection_id as its leading column; this
    // covers "every collection an asset belongs to" and the permanent-delete cascade.
    indices = [Index(value = ["asset_id"])],
)
data class CollectionMemberEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "position", defaultValue = "0") val position: Int = 0,
)
