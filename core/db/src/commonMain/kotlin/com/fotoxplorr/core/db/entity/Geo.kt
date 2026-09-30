package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

/** ADR-011 §2: derived data, always keyed (asset_id, revision), rebuildable, dropped only on
 *  permanent delete. */
@Entity(
    tableName = "geo",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GeoEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "revision") val revision: Long?,
    @ColumnInfo(name = "has_location") val hasLocation: Boolean,
    @ColumnInfo(name = "latitude") val latitude: Double?,
    @ColumnInfo(name = "longitude") val longitude: Double?,
    @ColumnInfo(name = "altitude") val altitude: Double?,
    @ColumnInfo(name = "direction") val direction: Double?,
    @ColumnInfo(name = "manual", defaultValue = "0") val manual: Boolean = false,
    @ColumnInfo(name = "checked_with_original", defaultValue = "0") val checkedWithOriginal: Boolean = false,
)
