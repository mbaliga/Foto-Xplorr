package com.fotoxplorr.app.recognition

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.app.media.MediaAsset
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

/**
 * P0-12: the version 3 -> 4 migration is additive -- it must keep every existing
 * asset/face/text row (recognition data is normally a derived cache that gets dropped and
 * rebuilt across a version bump, but the brief is explicit that this particular step must not),
 * while making the new `recognition_failure` table usable.
 *
 * Builds a version-3 database directly with raw SQL (never through [RecognitionStore]'s own
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

    @Test
    fun `upgrading from version 3 keeps existing rows and adds a usable failure table`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = context.getDatabasePath("foto_xplorr_recognition.db")
        dbFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
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
            db.execSQL(
                "INSERT INTO asset_recognition (media_id, source_revision, face_count, pet_verdict, identity_verdict) " +
                    "VALUES (1, 42, 0, 'DOG', 'NONE')",
            )
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
    }
}
