package com.fotoxplorr.core.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.fotoxplorr.core.db.dao.EmbeddingDao
import com.fotoxplorr.core.db.entity.EmbeddingEntity

/**
 * ADR-011 §1: `fotoz-vectors.db`. Large, rebuildable, kept out of any catalogue backup, joined
 * to `fotoz.db` by asset_id in application code -- explicitly without foreign keys, since a
 * foreign key can't cross database files.
 */
@Database(entities = [EmbeddingEntity::class], version = 1, exportSchema = true)
@ConstructedBy(FotozVectorsDatabaseConstructor::class)
abstract class FotozVectorsDatabase : RoomDatabase() {
    abstract fun embeddingDao(): EmbeddingDao
}

@Suppress("KotlinNoActualForExpect")
expect object FotozVectorsDatabaseConstructor : RoomDatabaseConstructor<FotozVectorsDatabase> {
    override fun initialize(): FotozVectorsDatabase
}
