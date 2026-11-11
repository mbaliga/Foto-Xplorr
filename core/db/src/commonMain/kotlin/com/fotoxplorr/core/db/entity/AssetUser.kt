package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

/** ADR-011 §2: the user's own data. Always keyed by asset_id. `ON DELETE CASCADE` applies only
 *  on permanent delete (ADR-011 §6). */
@Entity(
    tableName = "asset_user",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AssetUserEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "favorite", defaultValue = "0") val favorite: Boolean = false,
    /** 0..5 (Phase 4 UI; column exists now). */
    @ColumnInfo(name = "rating", defaultValue = "0") val rating: Int = 0,
    /** -1 reject, 0 none, 1 pick -- see [com.fotoxplorr.core.model.Flag]. */
    @ColumnInfo(name = "flag", defaultValue = "0") val flag: Int = 0,
    @ColumnInfo(name = "color_label", defaultValue = "0") val colorLabel: Int = 0,
    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(name = "ever_unarchived", defaultValue = "0") val everUnarchived: Boolean = false,
    @ColumnInfo(name = "sensitive", defaultValue = "0") val sensitive: Boolean = false,
    @ColumnInfo(name = "caption") val caption: String?,
    @ColumnInfo(name = "caption_is_machine", defaultValue = "0") val captionIsMachine: Boolean = false,
    @ColumnInfo(name = "caption_machine_suppressed", defaultValue = "0") val captionMachineSuppressed: Boolean = false,
    @ColumnInfo(name = "archive_suggestion_rejected", defaultValue = "0") val archiveSuggestionRejected: Boolean = false,
    /** Phase 3/4: IN_SYNC | DIRTY | CONFLICT */
    @ColumnInfo(name = "sidecar_state") val sidecarState: String?,
)
