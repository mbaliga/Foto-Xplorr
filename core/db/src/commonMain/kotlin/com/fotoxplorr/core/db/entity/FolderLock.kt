package com.fotoxplorr.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** ADR-011 §2: replaces the private-folders prefs. */
@Entity(tableName = "folder_lock")
data class FolderLockEntity(
    /** Source-scoped key (ADR-011 §4) OR a legacy key (ADR-011 §5.4). */
    @PrimaryKey @ColumnInfo(name = "folder_key") val folderKey: String,
    @ColumnInfo(name = "salt") val salt: ByteArray,
    @ColumnInfo(name = "hash") val hash: ByteArray,
    @ColumnInfo(name = "iterations") val iterations: Int,
    @ColumnInfo(name = "legacy_key", defaultValue = "0") val legacyKey: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FolderLockEntity) return false
        return folderKey == other.folderKey &&
            salt.contentEquals(other.salt) &&
            hash.contentEquals(other.hash) &&
            iterations == other.iterations &&
            legacyKey == other.legacyKey
    }

    override fun hashCode(): Int {
        var result = folderKey.hashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        result = 31 * result + iterations
        result = 31 * result + legacyKey.hashCode()
        return result
    }
}
