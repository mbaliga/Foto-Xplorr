package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.EmbeddingEntity
import com.fotoxplorr.core.model.AssetId

/** Backs `EmbeddingRepository`'s facade (ADR-011 Consequences), over `fotoz-vectors.db`. */
@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<EmbeddingEntity>)

    @Query("SELECT * FROM embedding WHERE model_sha = :modelSha")
    suspend fun readAll(modelSha: String): List<EmbeddingEntity>

    @Query("SELECT COUNT(*) FROM embedding")
    suspend fun count(): Int

    @Query("SELECT asset_id, x, y FROM embedding WHERE model_sha = :modelSha AND x IS NOT NULL AND y IS NOT NULL")
    suspend fun readPoints(modelSha: String): List<EmbeddingPoint>

    @Query("UPDATE embedding SET x = :x, y = :y WHERE asset_id = :assetId AND model_sha = :modelSha")
    suspend fun updateLayout(assetId: AssetId, modelSha: String, x: Double, y: Double)

    @Query("DELETE FROM embedding WHERE asset_id NOT IN (:keepAssetIds)")
    suspend fun removeMissing(keepAssetIds: List<AssetId>)

    @Query("DELETE FROM embedding WHERE asset_id = :assetId")
    suspend fun remove(assetId: AssetId)
}

data class EmbeddingPoint(
    @androidx.room.ColumnInfo(name = "asset_id") val assetId: AssetId,
    val x: Double,
    val y: Double,
)
