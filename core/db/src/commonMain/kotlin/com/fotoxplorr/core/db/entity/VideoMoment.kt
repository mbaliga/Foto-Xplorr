package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

@Entity(
    tableName = "video_moment",
    primaryKeys = ["asset_id", "position_ms"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoMomentEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    /** MomentSource.name: AUTO | MANUAL */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "label") val label: String,
)

@Entity(
    tableName = "video_moment_scan",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoMomentScanEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "revision") val revision: Long,
)

/**
 * Old `moment_feedback` re-keyed to asset_id (ADR-011 §2 leaves this table's columns out of the
 * DDL block; carried over unchanged from `foto_xplorr_moments.db`'s `moment_feedback`: `media_id
 * INTEGER NOT NULL, position_ms INTEGER NOT NULL, verdict TEXT NOT NULL, PRIMARY KEY(media_id,
 * position_ms)`). A feedback row often has no matching [VideoMomentEntity] row -- a BAD verdict
 * deletes the AUTO moment but keeps the verdict so it isn't immediately re-detected -- so this is
 * deliberately **not** foreign-keyed to `video_moment`, only to `asset`.
 */
@Entity(
    tableName = "video_moment_feedback",
    primaryKeys = ["asset_id", "position_ms"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoMomentFeedbackEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    /** MomentFeedback.name: GOOD | BAD */
    @ColumnInfo(name = "verdict") val verdict: String,
)

/** [VideoMomentEntity.source] values. */
object MomentSource {
    const val AUTO = "AUTO"
    const val MANUAL = "MANUAL"
}

/** [VideoMomentFeedbackEntity.verdict] values. */
object MomentFeedback {
    const val GOOD = "GOOD"
    const val BAD = "BAD"
}
