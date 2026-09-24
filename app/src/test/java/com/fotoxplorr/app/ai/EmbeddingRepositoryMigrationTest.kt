package com.fotoxplorr.app.ai

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
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
 * P0-12: the version 1 -> 2 migration -- this database's first real migration -- must keep
 * every existing embedding row while making the new `embedding_failure` table usable, with the
 * same bounded-retry rule [RecognitionStore] uses (see `RecognitionStoreTest`/
 * `RecognitionStoreMigrationTest`).
 *
 * Builds a version-1 database directly with raw SQL (never through [EmbeddingRepository]'s own
 * code, which only knows how to write the current schema), the same approach
 * `GeoMetadataRepositoryMigrationTest` uses for its own repository's migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddingRepositoryMigrationTest {

    private val modelSha = "abc123"

    /** Mirrors EmbeddingRepository.kt's own private `MediaAsset.sourceRevision()` exactly. */
    private fun MediaAsset.testRevision(): Long =
        (dateModifiedSeconds shl 17) xor sizeBytes xor width.toLong().shl(9) xor height.toLong()

    private fun asset(id: Long, dateModifiedSeconds: Long = 1_000L) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = id,
        dateModifiedSeconds = dateModifiedSeconds,
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `upgrading from version 1 keeps existing embeddings and adds a usable failure table`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = context.getDatabasePath("foto_xplorr_embeddings.db")
        dbFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE embeddings (
                    media_id INTEGER PRIMARY KEY,
                    source_revision INTEGER NOT NULL,
                    model_sha TEXT NOT NULL,
                    vector BLOB NOT NULL,
                    signature INTEGER NOT NULL,
                    x REAL,
                    y REAL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO embeddings (media_id, source_revision, model_sha, vector, signature) " +
                    "VALUES (1, 42, '$modelSha', X'01020304', 0)",
            )
            db.version = 1
        }

        val repository = EmbeddingRepository(context)

        // The pre-existing row survived the upgrade untouched -- an additive migration.
        val rows = repository.readAll(modelSha)
        assertEquals(listOf(MediaId(1L)), rows.map { it.mediaId })
        assertEquals(42L, rows.single().sourceRevision)

        // The new failure table exists and behaves like a freshly created one: three failures at
        // the same revision exclude the asset, and a file change (new revision) retries it.
        val target = asset(2)
        val revision = target.testRevision()
        repeat(3) { repository.recordFailure(target.id, modelSha, revision) }
        assertFalse(target in repository.missingAssets(listOf(target), modelSha))

        val changed = asset(2, dateModifiedSeconds = 9_999L)
        assertTrue(changed in repository.missingAssets(listOf(changed), modelSha))
    }
}
