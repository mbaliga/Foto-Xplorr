package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.fotoxplorr.core.db.entity.FolderLockEntity

/** Backs `PrivateFolderStore`'s facade (ADR-011 Consequences). */
@Dao
interface FolderLockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lock: FolderLockEntity)

    @Query("DELETE FROM folder_lock WHERE folder_key = :folderKey")
    suspend fun remove(folderKey: String)

    @Query("SELECT * FROM folder_lock WHERE folder_key = :folderKey")
    suspend fun get(folderKey: String): FolderLockEntity?

    @Query("SELECT * FROM folder_lock")
    fun observeAll(): Flow<List<FolderLockEntity>>

    @Query("SELECT * FROM folder_lock")
    suspend fun getAll(): List<FolderLockEntity>
}
