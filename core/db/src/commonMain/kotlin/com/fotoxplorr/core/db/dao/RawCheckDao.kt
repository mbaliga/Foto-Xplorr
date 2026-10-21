package com.fotoxplorr.core.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.RoomRawQuery

/**
 * ADR-011 §5 step 8 (`VERIFY`): `PRAGMA integrity_check` and `PRAGMA foreign_key_check`, run
 * after COMMIT, outside any transaction (TRAPS #11). Room's `@Query` compile-time SQL parser
 * rejects bare `PRAGMA ...` statements and SQLite's table-valued pragma-function form
 * (`pragma_foreign_keys()`) alike ("no viable alternative") -- `@RawQuery` bypasses that parser
 * entirely and forwards the SQL straight to the driver.
 */
@Dao
interface RawCheckDao {
    @RawQuery
    suspend fun integrityCheck(query: RoomRawQuery = RoomRawQuery("PRAGMA integrity_check")): List<IntegrityCheckRow>

    @RawQuery
    suspend fun foreignKeyCheck(query: RoomRawQuery = RoomRawQuery("PRAGMA foreign_key_check")): List<ForeignKeyViolationRow>
}

data class IntegrityCheckRow(@ColumnInfo(name = "integrity_check") val result: String)

data class ForeignKeyViolationRow(
    @ColumnInfo(name = "table") val table: String,
    @ColumnInfo(name = "rowid") val rowId: Long?,
    @ColumnInfo(name = "parent") val parent: String,
    @ColumnInfo(name = "fkid") val fkId: Long,
)
