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
}
