package com.fotoxplorr.app.spatial

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P0-02: the version 2 -> 3 migration must drop every "no location" row on upgrade -- they were
 * all written by code that never asked for the unredacted original, so on Android 10+ that
 * absence might just be the platform's own redaction, not the photo's real EXIF -- while keeping
 * every row that actually recorded a fix, hand-placed pins included.
 *
 * Builds a version-2 database directly with raw SQL (never through [GeoMetadataRepository]'s own
 * code, which only knows how to write the current schema) so the migration path under test is
 * exactly the one a real upgrading install goes through: `SQLiteOpenHelper` comparing the file's
 * stored version against the requested one and calling `onUpgrade`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoMetadataRepositoryMigrationTest {

    @Test
    fun `upgrading from version 2 drops no-location rows and keeps located and manual rows`() {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = context.getDatabasePath("foto_xplorr_geo.db")
        dbFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE geo_metadata (
                    media_id INTEGER PRIMARY KEY,
                    scanned INTEGER NOT NULL,
                    has_location INTEGER NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    altitude REAL,
                    direction REAL,
                    manual INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // Located: must survive.
            db.execSQL(
                "INSERT INTO geo_metadata (media_id, scanned, has_location, latitude, longitude, manual) " +
                    "VALUES (1, 1, 1, 12.5, 45.5, 0)",
            )
            // No location, written before this app could ask for an unredacted read: must be dropped.
            db.execSQL(
                "INSERT INTO geo_metadata (media_id, scanned, has_location, manual) VALUES (2, 1, 0, 0)",
            )
            // A hand-placed pin (always has_location = 1): must survive.
            db.execSQL(
                "INSERT INTO geo_metadata (media_id, scanned, has_location, latitude, longitude, manual) " +
                    "VALUES (3, 1, 1, 10.0, 20.0, 1)",
            )
            db.version = 2
        }

        val state = GeoMetadataRepository(context).observe().value

        assertEquals(setOf(MediaId(1L), MediaId(3L)), state.metadataById.keys)
        assertEquals(12.5, state.metadataById.getValue(MediaId(1L)).latitude, 0.0001)
        assertEquals(10.0, state.metadataById.getValue(MediaId(3L)).latitude, 0.0001)
    }
}
