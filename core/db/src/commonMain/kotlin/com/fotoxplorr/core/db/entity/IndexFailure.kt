package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.AssetId

/** ADR-011 §2: one failure table for every derived indexer, replacing the per-DB failure tables
 *  (only `recognition` and `embedding:<model sha>` have one today -- geo, trait and moments never
 *  did). */
@Entity(
    tableName = "index_failure",
    primaryKeys = ["asset_id", "indexer"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["asset_id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IndexFailureEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    /** GEO | RECOGNITION | EMBEDDING:<model sha> | TRAIT | MOMENTS */
    @ColumnInfo(name = "indexer") val indexer: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_attempt_ms") val lastAttemptMs: Long,
)

/** [IndexFailureEntity.indexer] values that aren't `EMBEDDING:<model sha>` (a runtime-built
 *  string, not a fixed constant). */
object Indexer {
    const val GEO = "GEO"
    const val RECOGNITION = "RECOGNITION"
    const val TRAIT = "TRAIT"
    const val MOMENTS = "MOMENTS"
    fun embedding(modelSha: String) = "EMBEDDING:$modelSha"
}
