package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.LegacyIdMapEntity
import com.fotoxplorr.core.db.entity.LegacyOrphanEntity
import com.fotoxplorr.core.db.entity.MigrationProgressEntity
import com.fotoxplorr.core.model.AssetId

@Dao
interface MigrationProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MigrationProgressEntity)

    @Query("SELECT * FROM migration_progress WHERE step = :step")
    suspend fun get(step: String): MigrationProgressEntity?

    @Query("SELECT * FROM migration_progress")
    suspend fun getAll(): List<MigrationProgressEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mapId(entry: LegacyIdMapEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mapIds(entries: List<LegacyIdMapEntity>)

    @Query("SELECT asset_id FROM legacy_id_map WHERE media_id = :mediaId")
    suspend fun assetIdFor(mediaId: Long): AssetId?

    @Query("SELECT media_id FROM legacy_id_map WHERE asset_id = :assetId")
    suspend fun mediaIdFor(assetId: AssetId): Long?

    @Query("SELECT * FROM legacy_id_map")
    suspend fun getIdMap(): List<LegacyIdMapEntity>

    @Query("SELECT COUNT(*) FROM legacy_id_map")
    suspend fun idMapCount(): Int

    /** [MigrationStep.CLEANUP] deletes this along with the old stores' live locations -- see
     *  ADR-011 §5 step 10. */
    @Query("DELETE FROM legacy_id_map")
    suspend fun clearIdMap()

    // IGNORE, not ABORT: the VERIFY step's catch-up pass re-runs USER_DATA/DERIVED (see
    // MigrationToV2.run), which would otherwise re-record the exact same orphan (kind, media_id,
    // detail) on every catch-up pass -- see LegacyOrphanEntity's own unique index.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addOrphan(orphan: LegacyOrphanEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addOrphans(orphans: List<LegacyOrphanEntity>)

    @Query("SELECT * FROM legacy_orphans")
    suspend fun getOrphans(): List<LegacyOrphanEntity>

    @Query("SELECT kind, COUNT(*) as count FROM legacy_orphans GROUP BY kind")
    suspend fun orphanCountsByKind(): List<OrphanCount>
}

data class OrphanCount(val kind: String, val count: Int)
