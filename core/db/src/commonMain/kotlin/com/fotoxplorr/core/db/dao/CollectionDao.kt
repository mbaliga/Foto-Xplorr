package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.CollectionEntity
import com.fotoxplorr.core.db.entity.CollectionMemberEntity
import com.fotoxplorr.core.model.AssetId
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(collection: CollectionEntity)

    @Query("SELECT * FROM collection ORDER BY sort_order, created_ms")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("DELETE FROM collection WHERE collection_id = :collectionId")
    suspend fun delete(collectionId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMember(member: CollectionMemberEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMembers(members: List<CollectionMemberEntity>)

    @Query("DELETE FROM collection_member WHERE collection_id = :collectionId AND asset_id = :assetId")
    suspend fun removeMember(collectionId: String, assetId: AssetId)

    @Query("SELECT asset_id FROM collection_member WHERE collection_id = :collectionId ORDER BY position")
    suspend fun members(collectionId: String): List<AssetId>
}
