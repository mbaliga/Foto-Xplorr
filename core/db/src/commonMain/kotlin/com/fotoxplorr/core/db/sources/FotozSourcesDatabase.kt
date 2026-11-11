package com.fotoxplorr.core.db.sources

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * ADR-020 decision 1: `fotoz-sources.db`, the third database of `:core:db`. Holds folders and
 * drives the person added (SAF trees, USB), their asset rows, and walk checkpoints. Separate from
 * `fotoz.db` and `fotoz-vectors.db`; joined to nothing by foreign key. A source going OFFLINE or
 * REVOKED never deletes its rows (only [SourcesDao.remove], a person action, does).
 */
@Database(
    entities = [SourceRecord::class, SourceFolderRecord::class, SourceAssetRecord::class, IndexRunRecord::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(FotozSourcesDatabaseConstructor::class)
abstract class FotozSourcesDatabase : RoomDatabase() {
    abstract fun sourcesDao(): SourcesDao
    abstract fun sourceFoldersDao(): SourceFoldersDao
    abstract fun sourceAssetsDao(): SourceAssetsDao
    abstract fun indexRunsDao(): IndexRunsDao
}

@Suppress("KotlinNoActualForExpect")
expect object FotozSourcesDatabaseConstructor : RoomDatabaseConstructor<FotozSourcesDatabase> {
    override fun initialize(): FotozSourcesDatabase
}
