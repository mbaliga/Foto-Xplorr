package com.fotoxplorr.app.recognition

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0-12: the version 3 -> 4 migration is additive -- it must keep every existing
 * asset/face/text row (recognition data is normally a derived cache that gets dropped and
 * rebuilt across a version bump, but the brief is explicit that this particular step must not),
 * while making the new `recognition_failure` table usable.
 *
 * v5 (colour palettes) is additive for the reason `RecognitionOpenHelper.onUpgrade` gives: the
 * palette is derived, but dropping the rows to get it would re-run three detectors per photo for
 * the sake of one cheap decode. So a v4 database must come up with every row intact, an empty
 * palette table, and exactly those rows waiting for the palette-only pass.
 *
 * Builds the old-version databases directly with raw SQL (never through [RecognitionStore]'s own
 * code, which only knows how to write the current schema) so the migration path under test is
 * exactly the one a real upgrading install goes through -- the same approach
 * `GeoMetadataRepositoryMigrationTest` uses for its own repository's migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionStoreMigrationTest {

    private fun asset(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = id,
        dateModifiedSeconds = 1_000L,
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    private fun databaseFile(): File {
        val context = RuntimeEnvironment.getApplication()
        return context.getDatabasePath("foto_xplorr_recognition.db").also { it.parentFile?.mkdirs() }
    }

    /** The three tables every version since 3 has had, exactly as v3 created them. */
    private fun createVersion3Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE asset_recognition (
                media_id INTEGER PRIMARY KEY,
                source_revision INTEGER NOT NULL,
                face_count INTEGER NOT NULL,
                pet_verdict TEXT NOT NULL,
                identity_verdict TEXT NOT NULL,
                labels TEXT NOT NULL DEFAULT '',
                categories TEXT NOT NULL DEFAULT '',
                caption TEXT NOT NULL DEFAULT '',
                hashtags TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE face_descriptor (
                media_id INTEGER NOT NULL,
                face_index INTEGER NOT NULL,
                relative_area REAL NOT NULL,
                vector BLOB NOT NULL,
                PRIMARY KEY (media_id, face_index)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE asset_text_block (
                media_id INTEGER NOT NULL,
                block_index INTEGER NOT NULL,
                text TEXT NOT NULL,
                left REAL NOT NULL,
                top REAL NOT NULL,
                right REAL NOT NULL,
                bottom REAL NOT NULL,
                PRIMARY KEY (media_id, block_index)
            )
            """.trimIndent(),
        )
    }

    /** The table v4 (P0-12) added, exactly as v4 created it. */
    private fun createVersion4FailureTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE recognition_failure (
                media_id INTEGER PRIMARY KEY,
                revision INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                last_attempt_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun insertDogRow(db: SQLiteDatabase, mediaId: Long, revision: Long) {
        db.execSQL(
            "INSERT INTO asset_recognition (media_id, source_revision, face_count, pet_verdict, identity_verdict) " +
                "VALUES ($mediaId, $revision, 0, 'DOG', 'NONE')",
        )
    }

    private fun tableNames(dbFile: File): Set<String> =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun count(dbFile: File, table: String): Int =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    @Test
    fun `upgrading from version 3 keeps existing rows and adds a usable failure table`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = databaseFile()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion3Tables(db)
            insertDogRow(db, mediaId = 1, revision = 42)
            db.version = 3
        }

        val store = RecognitionStore(context)
        store.reload()

        // The pre-existing row survived the upgrade untouched -- an additive migration, not a
        // drop-and-recreate.
        assertEquals(setOf(MediaId(1L)), store.observe().value.petMediaIds)

        // The new failure table exists and behaves like any freshly created one: three failures
        // exclude, and a success clears them.
        val target = asset(2)
        val revision = target.recognitionRevision()
        repeat(3) { store.recordFailure(target.id, revision) }
        assertFalse(target in store.pendingAssets(listOf(target)))
        store.clearFailure(target.id)
        assertTrue(target in store.pendingAssets(listOf(target)))

        // v3 -> 5 in one step lands on the full v5 schema too.
        assertTrue("asset_palette" in tableNames(dbFile))
        assertEquals(0, count(dbFile, "asset_palette"))
    }

    @Test
    fun `upgrading from version 4 keeps every row, adds an empty palette table, and queues those rows for the palette-only pass`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = databaseFile()
        val legacy = asset(1)
        val revision = legacy.recognitionRevision()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createVersion3Tables(db)
            createVersion4FailureTable(db)
            insertDogRow(db, mediaId = 1, revision = revision)
            db.execSQL(
                "INSERT INTO asset_text_block (media_id, block_index, text, left, top, right, bottom) " +
                    "VALUES (1, 0, 'EXIT', 0.1, 0.1, 0.5, 0.2)",
            )
            // A failure record for a different photo: the one kind of row v4 promised would survive.
            db.execSQL("INSERT INTO recognition_failure (media_id, revision, attempts, last_attempt_ms) VALUES (9, 5, 2, 1000)")
            db.version = 4
        }

        val store = RecognitionStore(context)
        store.reload()

        // Every pre-existing row survived: recognition, text, and the failure bookkeeping.
        val index = store.observe().value
        assertEquals(setOf(MediaId(1L)), index.petMediaIds)
        assertEquals("EXIT", index.textOf(MediaId(1L)))
        assertEquals(1, count(dbFile, "asset_recognition"))
        assertEquals(1, count(dbFile, "asset_text_block"))
        assertEquals(1, count(dbFile, "recognition_failure"))

        // The palette table exists, at version 5, and is empty -- nothing was invented for old rows.
        assertTrue("asset_palette" in tableNames(dbFile))
        assertEquals(0, count(dbFile, "asset_palette"))
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(5, it.version) }
        assertTrue(index.paletteFor(legacy.id).isEmpty())

        // The upgraded row is exactly what the palette-only pass must pick up -- and NOT what the
        // detector pass must: its revision still matches, so faces/labels/text are not redone.
        assertEquals(listOf(legacy), store.pendingPaletteOnly(listOf(legacy)))
        assertTrue(store.pendingAssets(listOf(legacy)).isEmpty())

        // And the new table is usable: a palette written for the legacy row reads back and settles it.
        store.upsertPalettes(mapOf(legacy.id to listOf(PaletteSwatch(0xFF112233.toInt(), 1f))))
        store.reload()
        assertEquals(listOf(PaletteSwatch(0xFF112233.toInt(), 1f)), store.observe().value.paletteFor(legacy.id))
        assertTrue(store.pendingPaletteOnly(listOf(legacy)).isEmpty())
    }
}
