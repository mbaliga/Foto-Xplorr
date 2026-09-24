package com.fotoxplorr.core.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * WP1.2's whole job for this module (ADR-010): prove the Room KMP + KSP + `BundledSQLiteDriver`
 * wiring compiles and runs, on Android and the JVM, before WP1.3 puts the real catalogue v2
 * schema (ADR-011) here. Nothing below is part of that real schema -- it exists only to be
 * deleted the moment WP1.3 lands.
 */
@Entity
data class SmokeRow(@PrimaryKey val id: Long, val value: String)

@Dao
interface SmokeDao {
    @Insert
    suspend fun insert(row: SmokeRow)

    @Query("SELECT * FROM SmokeRow WHERE id = :id")
    suspend fun get(id: Long): SmokeRow?
}

@Database(entities = [SmokeRow::class], version = 1, exportSchema = false)
@ConstructedBy(SmokeDatabaseConstructor::class)
abstract class SmokeDatabase : RoomDatabase() {
    abstract fun dao(): SmokeDao
}

/** Room KMP's per-platform hook for constructing the generated `_SmokeDatabase` implementation.
 *  Deliberately has **no** hand-written `actual` anywhere in this module: Room's KSP processor
 *  generates the `actual object` itself (`SmokeDatabaseConstructor.kt` under
 *  `build/generated/ksp/<target>/...`, `initialize() = SmokeDatabase_Impl()`) for every target
 *  that runs its KSP pass against this `expect` declaration. Writing one by hand collides with
 *  that generated one -- on `androidTarget()` it's a clean "Redeclaration" compile error; on the
 *  plain `jvm()` target the exact same collision instead surfaces, confusingly, as a KSP-time
 *  "The @ConstructedBy definition must be an 'expect' declaration" validation error that looks
 *  like a completely different problem. See MASTER-PROGRESS.md's Decisions for the full story of
 *  how that was root-caused. */
@Suppress("KotlinNoActualForExpect")
expect object SmokeDatabaseConstructor : RoomDatabaseConstructor<SmokeDatabase> {
    override fun initialize(): SmokeDatabase
}
