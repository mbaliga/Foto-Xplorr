package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.model.AssetId
import kotlinx.coroutines.flow.Flow

/** Backs the [com.fotoxplorr.core.db] facades over `FavoriteStore`, `SensitiveStore`, and the
 *  archive/caption slice of `LibraryStore` (ADR-011 Consequences). */
@Dao
interface AssetUserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AssetUserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<AssetUserEntity>)

    @Query("SELECT * FROM asset_user WHERE asset_id = :assetId")
    suspend fun get(assetId: AssetId): AssetUserEntity?

    @Query("SELECT * FROM asset_user")
    fun observeAll(): Flow<List<AssetUserEntity>>

    @Query("SELECT asset_id FROM asset_user WHERE favorite = 1")
    fun observeFavoriteIds(): Flow<List<AssetId>>

    @Query("SELECT asset_id FROM asset_user WHERE favorite = 1")
    suspend fun getFavoriteIds(): List<AssetId>

    @Query("UPDATE asset_user SET favorite = :favorite WHERE asset_id = :assetId")
    suspend fun setFavorite(assetId: AssetId, favorite: Boolean)

    @Query("UPDATE asset_user SET favorite = :favorite WHERE asset_id IN (:assetIds)")
    suspend fun setFavorite(assetIds: List<AssetId>, favorite: Boolean)

    @Query("SELECT asset_id FROM asset_user WHERE sensitive = 1")
    fun observeSensitiveIds(): Flow<List<AssetId>>

    @Query("SELECT asset_id FROM asset_user WHERE sensitive = 1")
    suspend fun getSensitiveIds(): List<AssetId>

    @Query("UPDATE asset_user SET sensitive = :sensitive WHERE asset_id = :assetId")
    suspend fun setSensitive(assetId: AssetId, sensitive: Boolean)

    @Query("UPDATE asset_user SET sensitive = :sensitive WHERE asset_id IN (:assetIds)")
    suspend fun setSensitive(assetIds: List<AssetId>, sensitive: Boolean)

    @Query("SELECT asset_id FROM asset_user WHERE archived = 1")
    fun observeArchivedIds(): Flow<List<AssetId>>

    @Query("SELECT asset_id FROM asset_user WHERE archived = 1")
    suspend fun getArchivedIds(): List<AssetId>

    @Query("UPDATE asset_user SET archived = :archived WHERE asset_id = :assetId")
    suspend fun setArchived(assetId: AssetId, archived: Boolean)

    @Query("UPDATE asset_user SET archived = :archived WHERE asset_id IN (:assetIds)")
    suspend fun setArchived(assetIds: List<AssetId>, archived: Boolean)

    @Query("SELECT asset_id FROM asset_user WHERE ever_unarchived = 1")
    suspend fun getEverUnarchivedIds(): List<AssetId>

    @Query("UPDATE asset_user SET ever_unarchived = 1 WHERE asset_id = :assetId")
    suspend fun markEverUnarchived(assetId: AssetId)

    @Query("SELECT asset_id FROM asset_user WHERE archive_suggestion_rejected = 1")
    suspend fun getArchiveSuggestionRejectedIds(): List<AssetId>

    @Query("UPDATE asset_user SET archive_suggestion_rejected = :rejected WHERE asset_id = :assetId")
    suspend fun setArchiveSuggestionRejected(assetId: AssetId, rejected: Boolean)

    @Query(
        "UPDATE asset_user SET caption = :caption, caption_is_machine = :isMachine, " +
            "caption_machine_suppressed = :machineSuppressed WHERE asset_id = :assetId",
    )
    suspend fun setCaption(assetId: AssetId, caption: String?, isMachine: Boolean, machineSuppressed: Boolean)

    @Query("SELECT * FROM asset_user WHERE caption IS NOT NULL")
    suspend fun getAllWithCaptions(): List<AssetUserEntity>
}
