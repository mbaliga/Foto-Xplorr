package com.fotoxplorr.core.db.dao

import androidx.room.ColumnInfo
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

    /**
     * WP1.4's mark-and-sweep: the set `SyncEngine` compares a fully-enumerated source's found
     * locators against, to find rows that need to become [com.fotoxplorr.core.db.entity.
     * Availability.MISSING_CONFIRMED]. Takes a list of [availabilities], not one value: a
     * reconnecting source's full re-sync must reconcile both its still-`ONLINE` rows AND the
     * ones `markSourceOffline` bulk-marked `OFFLINE` while it was gone (found again -> `ONLINE`
     * via the normal upsert path; still missing -> `MISSING_CONFIRMED`, now that a complete
     * enumeration can actually confirm that, per ADR-011 §6). Projects only `asset_id`/`locator`
     * (not a full [AssetEntity] load) since a large library's whole online set can be sizeable;
     * the `asset_state (availability, trashed)` index covers this query's `WHERE` clause.
     */
    @Query("SELECT asset_id, locator FROM asset WHERE source_id = :sourceId AND availability IN (:availabilities)")
    suspend fun idsAndLocatorsForSource(sourceId: SourceId, availabilities: List<String>): List<AssetIdLocator>

    @Query("UPDATE asset SET availability = :availability WHERE asset_id IN (:assetIds)")
    suspend fun setAvailability(assetIds: List<AssetId>, availability: String)

    /** The whole-source case (ADR-011 §6 / TRAPS #8's "an absent source means OFFLINE"): a
     *  source that's gone entirely needs no enumeration and no per-row locator comparison --
     *  every one of its rows just flips state in one statement. */
    @Query("UPDATE asset SET availability = :availability WHERE source_id = :sourceId")
    suspend fun setAvailabilityForSource(sourceId: SourceId, availability: String)

    /** Permanent delete ("shred", ADR-011 §6) -- cascades to every user/derived row via
     *  `ON DELETE CASCADE`. */
    @Query("DELETE FROM asset WHERE asset_id = :assetId")
    suspend fun deletePermanently(assetId: AssetId)

    @Query("DELETE FROM asset WHERE asset_id IN (:assetIds)")
    suspend fun deletePermanently(assetIds: List<AssetId>)
}

data class AssetIdLocator(
    @ColumnInfo(name = "asset_id") val assetId: AssetId,
    @ColumnInfo(name = "locator") val locator: String,
)
