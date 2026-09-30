package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.GeoEntity
import com.fotoxplorr.core.model.AssetId
import kotlinx.coroutines.flow.Flow

/** Backs `GeoMetadataRepository`'s facade (ADR-011 Consequences). */
@Dao
interface GeoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GeoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<GeoEntity>)

    @Query("SELECT * FROM geo")
    fun observeAll(): Flow<List<GeoEntity>>

    @Query("SELECT COUNT(*) FROM geo")
    suspend fun count(): Int

    @Query("SELECT * FROM geo WHERE asset_id = :assetId")
    suspend fun get(assetId: AssetId): GeoEntity?

    @Query(
        "UPDATE geo SET has_location = 1, latitude = :latitude, longitude = :longitude, manual = 1, " +
            "altitude = NULL, direction = NULL, revision = NULL, checked_with_original = 0 " +
            "WHERE asset_id = :assetId",
    )
    suspend fun setManualLocationOnExisting(assetId: AssetId, latitude: Double, longitude: Double): Int

    @Query(
        "UPDATE geo SET has_location = 0, latitude = NULL, longitude = NULL, manual = 0 " +
            "WHERE asset_id = :assetId AND manual = 1",
    )
    suspend fun clearManual(assetId: AssetId)

    @Query("DELETE FROM geo WHERE asset_id NOT IN (:keepAssetIds)")
    suspend fun pruneExcept(keepAssetIds: List<AssetId>)
}
