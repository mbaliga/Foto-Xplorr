package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.IndexFailureEntity
import com.fotoxplorr.core.model.AssetId

@Dao
interface IndexFailureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: IndexFailureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<IndexFailureEntity>)

    @Query("SELECT * FROM index_failure WHERE asset_id = :assetId AND indexer = :indexer")
    suspend fun get(assetId: AssetId, indexer: String): IndexFailureEntity?

    @Query("DELETE FROM index_failure WHERE asset_id = :assetId AND indexer = :indexer")
    suspend fun clear(assetId: AssetId, indexer: String)

    @Query("SELECT * FROM index_failure WHERE indexer = :indexer")
    suspend fun allFor(indexer: String): List<IndexFailureEntity>
}
