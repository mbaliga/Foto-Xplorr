package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.SourceId
import kotlinx.coroutines.flow.Flow

/**
 * WP1.3 deliberately keeps this DAO simple -- ADR-011's Consequences say the in-memory
 * `StateFlow<List<MediaAsset>>` mirror and its projections (`GalleryProjection`, destination/
 * smart-album queries) survive WP1.3 unchanged and are only replaced by real SQL projections in
 * WP1.5. This DAO's job is CRUD plus what the migration and the mirror-loading facade need, not
 * the paging/filter queries WP1.5 owns.
 */
@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(asset: AssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(assets: List<AssetEntity>): List<Long>

    @Update
    suspend fun update(asset: AssetEntity)

    @Query("SELECT * FROM asset WHERE asset_id = :assetId")
    suspend fun get(assetId: AssetId): AssetEntity?

    @Query("SELECT * FROM asset WHERE source_id = :sourceId AND locator = :locator")
    suspend fun findByLocator(sourceId: SourceId, locator: String): AssetEntity?

    /**
     * Same lookup as [findByLocator], minus the `source_id` join -- for the `com.fotoxplorr.core.
     * db.catalogue` facades, which bridge the app-wide, source-less [com.fotoxplorr.core.model.
     * MediaId] identity onto [AssetId]. Safe only because Phase 1 has exactly one source kind
     * (`MEDIASTORE_VOLUME`, ADR-011 §2) and this app has always treated MediaStore's own `_ID` as
     * a flat namespace with no per-volume disambiguation (every scanner queries the merged
     * `VOLUME_EXTERNAL` collection, whose `_ID` values are already unique across every physical
     * volume) -- see MASTER-PROGRESS.md's WP1.3 Decisions. `LIMIT 1` is belt-and-braces: the
     * `(source_id, locator)` unique index is what actually guarantees no collision within one
     * source, and a second `MEDIASTORE_VOLUME` source only exists for a real second physical
     * volume, which never shares `_ID`s with the first under that same assumption.
     */
    @Query("SELECT * FROM asset WHERE locator = :locator LIMIT 1")
    suspend fun findByLocatorAnySource(locator: String): AssetEntity?

    @Query("SELECT * FROM asset ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM asset ORDER BY date_taken_ms DESC, date_modified_ms DESC, asset_id DESC")
    suspend fun getAll(): List<AssetEntity>

    @Query("SELECT * FROM asset WHERE asset_id IN (:assetIds)")
    suspend fun getByIds(assetIds: List<AssetId>): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM asset")
    suspend fun count(): Int

    @Query("SELECT * FROM asset WHERE fingerprint = :fingerprint")
    suspend fun findByFingerprint(fingerprint: String): List<AssetEntity>

    /** Permanent delete ("shred", ADR-011 §6) -- cascades to every user/derived row via
     *  `ON DELETE CASCADE`. */
    @Query("DELETE FROM asset WHERE asset_id = :assetId")
    suspend fun deletePermanently(assetId: AssetId)

    @Query("DELETE FROM asset WHERE asset_id IN (:assetIds)")
    suspend fun deletePermanently(assetIds: List<AssetId>)
}
