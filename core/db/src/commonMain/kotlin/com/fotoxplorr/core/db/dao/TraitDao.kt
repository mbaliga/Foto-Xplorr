package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.TraitEntity
import com.fotoxplorr.core.model.AssetId
import kotlinx.coroutines.flow.Flow

/** Backs `AnimationIndex`'s facade (ADR-011 Consequences). Only [TraitEntity.animated] is
 *  populated in Phase 1 -- see that entity's own doc. */
@Dao
interface TraitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TraitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TraitEntity>)

    @Query("SELECT asset_id FROM trait WHERE animated = 1")
    fun observeAnimatedIds(): Flow<List<AssetId>>

    @Query("SELECT COUNT(*) FROM trait")
    suspend fun count(): Int

    @Query("SELECT asset_id FROM trait WHERE animated = 1")
    suspend fun getAnimatedIds(): List<AssetId>

    @Query("DELETE FROM trait WHERE asset_id NOT IN (:keepAssetIds)")
    suspend fun pruneExcept(keepAssetIds: List<AssetId>)
}
