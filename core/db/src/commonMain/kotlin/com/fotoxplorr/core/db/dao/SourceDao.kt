package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.model.SourceId
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("SELECT * FROM source WHERE kind = :kind AND root_locator = :rootLocator")
    suspend fun findByLocator(kind: String, rootLocator: String): SourceEntity?

    @Query("SELECT * FROM source WHERE source_id = :sourceId")
    suspend fun get(sourceId: SourceId): SourceEntity?

    @Query("SELECT * FROM source ORDER BY source_id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source ORDER BY source_id")
    suspend fun getAll(): List<SourceEntity>

    /** WP1.4: a source going `ONLINE` <-> `OFFLINE` (a MediaStore volume appearing/disappearing
     *  from `MediaStore.getExternalVolumeNames()`) is the whole-source case TRAPS #8 names --
     *  narrower than [update] so `SyncEngine` doesn't need to read-modify-write the whole row
     *  just to flip this one column. */
    @Query("UPDATE source SET state = :state WHERE source_id = :sourceId")
    suspend fun setState(sourceId: SourceId, state: String)

    /** The per-volume generation bookkeeping ADR-011 §2 / MASTER-PLAN.md §2.3 call for --
     *  `MediaStore.getVersion`/`getGeneration` at the end of a pass that read the volume, so the
     *  next pass knows whether it's looking at a reset (`sync_version` changed) or can trust a
     *  `GENERATION_MODIFIED >` delta query against `sync_generation`. */
    @Query(
        "UPDATE source SET sync_version = :syncVersion, sync_generation = :syncGeneration, " +
            "last_full_scan_ms = :lastFullScanMs WHERE source_id = :sourceId",
    )
    suspend fun setSyncState(sourceId: SourceId, syncVersion: String?, syncGeneration: Long?, lastFullScanMs: Long?)
}
