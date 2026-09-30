package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.FaceEntity
import com.fotoxplorr.core.db.entity.RecognitionEntity
import com.fotoxplorr.core.db.entity.TextBlockEntity
import com.fotoxplorr.core.model.AssetId
import kotlinx.coroutines.flow.Flow

/** Backs `RecognitionStore`'s facade (ADR-011 Consequences). */
@Dao
interface RecognitionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecognition(row: RecognitionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecognitionAll(rows: List<RecognitionEntity>)

    @Query("SELECT * FROM recognition")
    fun observeAll(): Flow<List<RecognitionEntity>>

    @Query("SELECT COUNT(*) FROM recognition")
    suspend fun countRecognition(): Int

    @Query("SELECT COUNT(*) FROM face")
    suspend fun countFaces(): Int

    @Query("SELECT * FROM recognition WHERE asset_id = :assetId")
    suspend fun get(assetId: AssetId): RecognitionEntity?

    @Query("DELETE FROM face WHERE asset_id = :assetId")
    suspend fun clearFaces(assetId: AssetId)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<FaceEntity>)

    @Query("SELECT * FROM face WHERE asset_id = :assetId ORDER BY face_index")
    suspend fun facesFor(assetId: AssetId): List<FaceEntity>

    @Query("SELECT * FROM face")
    suspend fun getAllFaces(): List<FaceEntity>

    @Query("DELETE FROM text_block WHERE asset_id = :assetId")
    suspend fun clearTextBlocks(assetId: AssetId)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTextBlocks(blocks: List<TextBlockEntity>)

    @Query("SELECT * FROM text_block WHERE asset_id = :assetId ORDER BY block_index")
    suspend fun textBlocksFor(assetId: AssetId): List<TextBlockEntity>

    @Query("DELETE FROM recognition WHERE asset_id NOT IN (:keepAssetIds)")
    suspend fun pruneExcept(keepAssetIds: List<AssetId>)
}
