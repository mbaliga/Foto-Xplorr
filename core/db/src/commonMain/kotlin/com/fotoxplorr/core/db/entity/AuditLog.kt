package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** ADR-011 §2: destructive-operation audit log. WP1.6 writes it; the schema lands here. */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "asset_count") val assetCount: Int,
    @ColumnInfo(name = "detail") val detail: String?,
)
