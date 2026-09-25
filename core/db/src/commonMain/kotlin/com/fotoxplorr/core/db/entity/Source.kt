package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fotoxplorr.core.model.SourceId

/** ADR-011 §2: where files live. One row per MediaStore volume in Phase 1; SAF, USB and network
 *  sources arrive in Phase 3. */
@Entity(
    tableName = "source",
    indices = [Index(value = ["kind", "root_locator"], unique = true)],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "source_id") val sourceId: SourceId,
    /** MEDIASTORE_VOLUME | SAF_TREE | USB_SAF | MTP_DEVICE | SMB | SFTP | WEBDAV | IMMICH | S3 |
     *  LINUX_DIR | UT_CONTENT */
    @ColumnInfo(name = "kind") val kind: String,
    /** MediaStore volume name ('external_primary', '<uuid>'); tree URI; host path... */
    @ColumnInfo(name = "root_locator") val rootLocator: String,
    @ColumnInfo(name = "volume_uuid") val volumeUuid: String?,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** ONLINE | OFFLINE | REVOKED | ERROR */
    @ColumnInfo(name = "state") val state: String,
    /** MediaStore.getVersion(volume) */
    @ColumnInfo(name = "sync_version") val syncVersion: String?,
    /** MediaStore.getGeneration(volume) at the last complete pass */
    @ColumnInfo(name = "sync_generation") val syncGeneration: Long?,
    @ColumnInfo(name = "last_full_scan_ms") val lastFullScanMs: Long?,
    @ColumnInfo(name = "preview_policy", defaultValue = "NONE") val previewPolicy: String = "NONE",
    @ColumnInfo(name = "flags", defaultValue = "0") val flags: Long = 0,
)

/** [SourceEntity.kind] values this app writes in Phase 1. Later phases add the rest of ADR-011
 *  §2's list as they're implemented. */
object SourceKind {
    const val MEDIASTORE_VOLUME = "MEDIASTORE_VOLUME"
}

/** [SourceEntity.state] values. */
object SourceState {
    const val ONLINE = "ONLINE"
    const val OFFLINE = "OFFLINE"
    const val REVOKED = "REVOKED"
    const val ERROR = "ERROR"
}
