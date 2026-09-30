package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fotoxplorr.core.db.entity.VideoMomentEntity
import com.fotoxplorr.core.db.entity.VideoMomentFeedbackEntity
import com.fotoxplorr.core.db.entity.VideoMomentScanEntity
import com.fotoxplorr.core.model.AssetId
import kotlinx.coroutines.flow.Flow

/** Backs `VideoMomentStore`'s facade (ADR-011 Consequences). */
@Dao
interface VideoMomentDao {
    @Query("SELECT * FROM video_moment")
    fun observeAll(): Flow<List<VideoMomentEntity>>

    @Query("SELECT COUNT(*) FROM video_moment")
    suspend fun countMoments(): Int

    @Query("SELECT * FROM video_moment WHERE asset_id = :assetId ORDER BY position_ms")
    suspend fun momentsFor(assetId: AssetId): List<VideoMomentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(moment: VideoMomentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(moments: List<VideoMomentEntity>)

    @Query("DELETE FROM video_moment WHERE asset_id = :assetId AND position_ms = :positionMs")
    suspend fun remove(assetId: AssetId, positionMs: Long)

    /** `replaceAuto`: keeps MANUAL moments, drops every AUTO one for this asset before the
     *  caller re-inserts the newly detected set. */
    @Query("DELETE FROM video_moment WHERE asset_id = :assetId AND source = 'AUTO'")
    suspend fun clearAuto(assetId: AssetId)

    @Query("SELECT * FROM video_moment_feedback")
    fun observeFeedback(): Flow<List<VideoMomentFeedbackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFeedback(feedback: VideoMomentFeedbackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFeedbackAll(feedback: List<VideoMomentFeedbackEntity>)

    @Query("DELETE FROM video_moment_feedback WHERE asset_id = :assetId AND position_ms = :positionMs")
    suspend fun clearFeedback(assetId: AssetId, positionMs: Long)

    @Query("SELECT COUNT(*) > 0 FROM video_moment_scan WHERE asset_id = :assetId")
    suspend fun hasBeenScanned(assetId: AssetId): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markScanned(row: VideoMomentScanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markScannedAll(rows: List<VideoMomentScanEntity>)
}
