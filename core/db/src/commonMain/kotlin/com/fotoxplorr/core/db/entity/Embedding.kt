package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.fotoxplorr.core.model.AssetId

/** ADR-011 §1/§2: lives in the second file, `fotoz-vectors.db` -- large, rebuildable, kept out of
 *  any catalogue backup, joined by asset_id in application code. Deliberately **no** foreign key
 *  to `asset` (that table lives in a different database file; Room/SQLite foreign keys can't
 *  cross files) -- an orphaned embedding after a permanent delete is cleaned up the same way
 *  `removeMissing` does today. */
@Entity(
    tableName = "embedding",
    primaryKeys = ["asset_id", "model_sha"],
    indices = [
        Index(value = ["model_sha"], name = "embedding_model_idx"),
        Index(value = ["model_sha", "signature"], name = "embedding_signature_idx"),
    ],
)
data class EmbeddingEntity(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "model_sha") val modelSha: String,
    @ColumnInfo(name = "revision") val revision: Long,
    /** int8-quantised, one byte per dimension -- carried over unchanged from the old
     *  `quantize()`/vector format. */
    @ColumnInfo(name = "vector") val vector: ByteArray,
    /** 24-bit LSH value computed from the quantised vector. */
    @ColumnInfo(name = "signature") val signature: Long,
    @ColumnInfo(name = "x") val x: Double?,
    @ColumnInfo(name = "y") val y: Double?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return assetId == other.assetId && modelSha == other.modelSha && revision == other.revision &&
            vector.contentEquals(other.vector) && signature == other.signature && x == other.x && y == other.y
    }

    override fun hashCode(): Int {
        var result = assetId.hashCode()
        result = 31 * result + modelSha.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + (x?.hashCode() ?: 0)
        result = 31 * result + (y?.hashCode() ?: 0)
        return result
    }
}
