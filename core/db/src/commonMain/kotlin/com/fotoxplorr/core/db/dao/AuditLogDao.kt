package com.fotoxplorr.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.fotoxplorr.core.db.entity.AuditLogEntity

/** WP1.6 writes this; the DAO lands with the schema (ADR-011 §2). */
@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(row: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY at_ms DESC")
    suspend fun getAll(): List<AuditLogEntity>
}
