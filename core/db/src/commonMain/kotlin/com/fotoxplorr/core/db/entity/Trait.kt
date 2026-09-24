package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

/** NULL = unknown; only [animated] is filled in Phase 1. */
@Entity(
    tableName = "trait",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TraitEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "animated") val animated: Boolean?,
    @ColumnInfo(name = "hdr_gainmap") val hdrGainmap: Boolean?,
    @ColumnInfo(name = "motion_photo") val motionPhoto: Boolean?,
    @ColumnInfo(name = "panorama") val panorama: Boolean?,
    @ColumnInfo(name = "depth") val depth: Boolean?,
    @ColumnInfo(name = "stereo") val stereo: Boolean?,
    @ColumnInfo(name = "burst_id") val burstId: String?,
)
