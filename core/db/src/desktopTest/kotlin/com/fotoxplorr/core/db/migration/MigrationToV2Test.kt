package com.fotoxplorr.core.db.migration

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.core.db.FotozDatabase
import com.fotoxplorr.core.db.FotozVectorsDatabase
import com.fotoxplorr.core.db.entity.MigrationStep
import com.fotoxplorr.core.db.entity.MigrationStepState
import com.fotoxplorr.core.model.AssetId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MigrationToV2]'s own correctness tests, against a small hand-built [FakeLegacySource] rather
 * than ADR-011's separate 100k [com.fotoxplorr.core.db] synthetic fixture (that one lives
 * alongside `SyntheticCatalogue`'s extension, see MASTER-PROGRESS.md). These exercise every
 * step's actual logic directly and cheaply -- the "Tests required" ADR-011 asks for (zero lost
 * user data, orphan accounting, FK check clean, kill-and-resume, catch-up, delete semantics),
 * just at a scale a hand-written fixture can express clearly instead of the 100k one's arithmetic
 * generation.
 */
class MigrationToV2Test {

    private lateinit var db: FotozDatabase
    private lateinit var vectorsDb: FotozVectorsDatabase

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<FotozDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        vectorsDb = Room.inMemoryDatabaseBuilder<FotozVectorsDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        vectorsDb.close()
    }

    private fun richLegacySource(): FakeLegacySource {
        val legacy = FakeLegacySource()
        legacy.addMedia(
            LegacyMediaRow(
                mediaId = 1, contentUri = "content://media/external/images/media/1", displayName = "IMG_0001.jpg",
                mimeType = "image/jpeg", bucketName = "Camera", bucketId = 100, dateTakenMs = 1_600_000_000_000,
                dateModifiedSeconds = 1_600_000_100, width = 100, height = 100, sizeBytes = 2048, durationMillis = 0,
                relativePath = "DCIM/Camera/", isFavorite = true, isTrashed = false,
            ),
        )
        legacy.addMedia(
            LegacyMediaRow(
                mediaId = 2, contentUri = "content://media/external/images/media/2", displayName = "IMG_0002.jpg",
                mimeType = "image/jpeg", bucketName = "Camera", bucketId = 100, dateTakenMs = 1_600_000_001_000,
                dateModifiedSeconds = 1_600_000_101, width = 100, height = 100, sizeBytes = 4096, durationMillis = 0,
                relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = true,
            ),
        )
        // mediaId 3 is deliberately NOT added -- old user-data references to it (below) exercise
        // orphan accounting.
        legacy.favorites = setOf(1, 3)
        legacy.sensitive = setOf(2)
        legacy.library = LegacyLibraryData(
            collections = listOf(LegacyCollection(id = "c1", name = "Trip", createdAtMillis = 1_600_000_000_000, mediaIds = setOf(1, 2))),
            tagMembers = mapOf("Beach" to setOf(1, 2), "family" to emptySet()),
            autoTagMembers = mapOf("Beach" to setOf(1)),
            rejectedAutoTagMembers = mapOf("Sunset" to setOf(2)),
            archivedIds = setOf(2),
            everUnarchivedIds = setOf(1),
            rejectedArchiveSuggestionIds = setOf(1),
            captions = mapOf(1L to "A day at the beach"),
            machineCaptionIds = setOf(1),
            suppressedMachineCaptionIds = setOf(3),
        )
        legacy.locks = listOf(LegacyFolderLock(folderKey = "path:dcim/camera", salt = byteArrayOf(1, 2, 3), hash = byteArrayOf(4, 5, 6), iterations = 210_000))
        legacy.geo = listOf(
            LegacyGeoRow(mediaId = 1, hasLocation = true, latitude = 1.0, longitude = 2.0, altitude = null, direction = null, manual = true, checkedWithOriginal = false, sourceRevisionSeconds = null),
            LegacyGeoRow(mediaId = 2, hasLocation = true, latitude = 3.0, longitude = 4.0, altitude = 10.0, direction = 90.0, manual = false, checkedWithOriginal = true, sourceRevisionSeconds = 1_600_000_101),
        )
        legacy.recognition = listOf(
            LegacyRecognitionRow(mediaId = 2, sourceRevision = 1, faceCount = 1, petVerdict = "NONE", identityVerdict = "NONE", labels = listOf("beach"), categories = listOf("outdoor"), caption = "", hashtags = listOf("#beach")),
        )
        legacy.faces = listOf(LegacyFaceRow(mediaId = 2, faceIndex = 0, relativeArea = 0.2, vector = byteArrayOf(1, 2, 3, 4)))
        legacy.traits = listOf(LegacyTraitRow(mediaId = 1, dateModifiedSeconds = 1_600_000_100, animated = false))
        legacy.videoMoments = listOf(LegacyVideoMomentRow(mediaId = 2, positionMs = 500, source = "AUTO", confidence = 0.9, label = "highlight"))
        legacy.videoMomentFeedback = listOf(
            LegacyMomentFeedbackRow(mediaId = 2, positionMs = 500, verdict = "GOOD"),
            LegacyMomentFeedbackRow(mediaId = 3, positionMs = 100, verdict = "BAD"), // orphaned: mediaId 3 was never migrated
        )
        legacy.embeddings = listOf(LegacyEmbeddingRow(mediaId = 1, sourceRevision = 1, modelSha = "sha1", vector = byteArrayOf(5, 6), signature = 42, x = 0.1, y = 0.2))
        return legacy
    }

    @Test
    fun `end to end migration preserves every kind of user data and accounts for orphans`() = runBlocking {
        val legacy = richLegacySource()
        val environment = FakeMigrationEnvironment()
        val migration = MigrationToV2(db, vectorsDb, legacy, environment, AlwaysParityCheck())

        val result = migration.run()
        assertTrue(result is MigrationToV2.Result.Completed, "expected Completed, got $result")
        assertTrue(environment.isCutoverComplete())
        assertTrue(environment.copiedOldStoreFiles)
        assertEquals(1, environment.exportedJson.size)

        assertEquals(2, db.assetDao().count())
        val asset1 = requireNotNull(db.migrationProgressDao().assetIdFor(1))
        val asset2 = requireNotNull(db.migrationProgressDao().assetIdFor(2))

        // Favorites: FavoriteStore id 1, plus MediaStore's own is_favorite already on row 1 --
        // both should agree; row 2 should NOT be favorite.
        val user1 = requireNotNull(db.assetUserDao().get(asset1))
        assertTrue(user1.favorite)
        assertEquals("A day at the beach", user1.caption)
        assertTrue(user1.everUnarchived)
        assertTrue(user1.archiveSuggestionRejected)
        assertTrue(!user1.archived)

        val user2 = requireNotNull(db.assetUserDao().get(asset2))
        assertTrue(!user2.favorite)
        assertTrue(user2.sensitive)
        assertTrue(user2.archived)

        // Trashed asset 2 kept its own asset_user and derived rows (ADR-011 §6: trash is not
        // deletion) -- recognition/face rows below prove this.
        assertNotNull(db.recognitionDao().get(asset2))
        assertEquals(1, db.recognitionDao().facesFor(asset2).size)

        // Tags: "Beach" links both assets (1 as AUTO+USER-visible via auto_tag_media, 2 as USER);
        // "family" exists as a keyword with zero members.
        val beachLinks = db.keywordDao().linksFor(asset1) + db.keywordDao().linksFor(asset2)
        assertEquals(2, beachLinks.count { true })
        assertNotNull(db.keywordDao().findRoot("family"))
        assertNotNull(db.keywordDao().findRoot("Sunset")) // created for the rejection even with no members

        // Collection membership.
        assertEquals(listOf(asset1, asset2).sortedBy { it.value }, db.collectionDao().members("c1").sortedBy { it.value })

        // Locks carried over under their legacy key.
        val lock = requireNotNull(db.folderLockDao().get("path:dcim/camera"))
        assertTrue(lock.legacyKey)
        assertEquals(210_000, lock.iterations)

        // Geo: manual pin on asset1, indexed row on asset2.
        val geo1 = requireNotNull(db.geoDao().get(asset1))
        assertTrue(geo1.manual)
        assertNull(geo1.revision)
        val geo2 = requireNotNull(db.geoDao().get(asset2))
        assertTrue(!geo2.manual)
        assertNotNull(geo2.revision)

        // Video moments + feedback, including the orphaned one.
        assertEquals(1, db.videoMomentDao().momentsFor(asset2).size)
        val orphans = db.migrationProgressDao().getOrphans()
        assertTrue(orphans.any { it.kind == com.fotoxplorr.core.db.entity.OrphanKind.VIDEO_MOMENT_FEEDBACK && it.mediaId == 3L })
        assertTrue(orphans.any { it.kind == com.fotoxplorr.core.db.entity.OrphanKind.FAVORITE && it.mediaId == 3L })

        // Embeddings landed in the SEPARATE vectors database.
        assertEquals(1, vectorsDb.embeddingDao().readAll("sha1").size)

        // VERIFY actually ran clean (no failure recorded).
        val verifyProgress = requireNotNull(db.migrationProgressDao().get(MigrationStep.VERIFY))
        assertEquals(MigrationStepState.DONE, verifyProgress.state)
    }

    @Test
    fun `kill and resume does not duplicate work and reaches the same end state`() = runBlocking {
        val legacy = richLegacySource()
        val environment = FakeMigrationEnvironment()
        val flakyParity = FlakyParityCheck(failFirstAttempts = 1)
        val migration = MigrationToV2(db, vectorsDb, legacy, environment, flakyParity)

        val firstResult = migration.run()
        assertTrue(firstResult is MigrationToV2.Result.VerifyFailed, "expected the induced VERIFY failure, got $firstResult")
        assertTrue(!environment.isCutoverComplete())

        // Every step before VERIFY is already DONE -- resuming must not re-insert assets (their
        // DAO uses OnConflictStrategy.ABORT, so a naive full re-run would throw here).
        val secondResult = migration.run()
        assertTrue(secondResult is MigrationToV2.Result.Completed, "expected Completed on resume, got $secondResult")
        assertTrue(environment.isCutoverComplete())
        assertEquals(2, db.assetDao().count())
        assertEquals(1, environment.exportedJson.size, "EXPORT must not re-run on resume")
    }

    @Test
    fun `catch up picks up an old-store write made between the first pass and VERIFY`() = runBlocking {
        val legacy = richLegacySource()
        val environment = FakeMigrationEnvironment()
        val flakyParity = FlakyParityCheck(failFirstAttempts = 1)
        val migration = MigrationToV2(db, vectorsDb, legacy, environment, flakyParity)

        val firstResult = migration.run()
        assertTrue(firstResult is MigrationToV2.Result.VerifyFailed)

        // A write lands in the old FavoriteStore after the first USER_DATA pass but before this
        // migration reaches CUTOVER -- exactly ADR-011's "the user can keep using the app during
        // steps 1-8" scenario.
        legacy.favorites = legacy.favorites + 2L

        val secondResult = migration.run()
        assertTrue(secondResult is MigrationToV2.Result.Completed)
        val asset2 = requireNotNull(db.migrationProgressDao().assetIdFor(2))
        assertTrue(requireNotNull(db.assetUserDao().get(asset2)).favorite, "the late favorite write should have been caught up before VERIFY")
    }

    @Test
    fun `catch up removes a favorite, a tag and a lock removed between the first pass and VERIFY`() = runBlocking {
        val legacy = richLegacySource()
        val environment = FakeMigrationEnvironment()
        val flakyParity = FlakyParityCheck(failFirstAttempts = 1)
        val migration = MigrationToV2(db, vectorsDb, legacy, environment, flakyParity)

        val firstResult = migration.run()
        assertTrue(firstResult is MigrationToV2.Result.VerifyFailed)
        val asset1 = requireNotNull(db.migrationProgressDao().assetIdFor(1))
        assertTrue(requireNotNull(db.assetUserDao().get(asset1)).favorite, "sanity check: asset1 starts favorite")
        assertTrue(db.keywordDao().linksFor(asset1).isNotEmpty(), "sanity check: asset1 starts tagged")
        assertNotNull(db.folderLockDao().get("path:dcim/camera"), "sanity check: the lock starts present")

        // Un-favorite, remove asset1's membership in the still-existing "Beach" tag (removing
        // the TAG ITSELF is a separate, documented gap -- see runUserData's own doc -- this
        // tests a plain membership change, which reconciliation does cover), and unlock the
        // folder in the OLD stores -- all three must be reflected in fotoz.db too once catch-up
        // runs, not just left as they were on the first pass (the bug this rewrite fixes).
        legacy.favorites = legacy.favorites - 1L
        legacy.library = legacy.library.copy(tagMembers = legacy.library.tagMembers + ("Beach" to setOf(2L)), autoTagMembers = emptyMap())
        legacy.locks = emptyList()

        val secondResult = migration.run()
        assertTrue(secondResult is MigrationToV2.Result.Completed, "expected Completed, got $secondResult")

        assertTrue(!requireNotNull(db.assetUserDao().get(asset1)).favorite, "the un-favorite should have been caught up before VERIFY")
        assertTrue(db.keywordDao().linksFor(asset1).none { it.keywordId == requireNotNull(db.keywordDao().findRoot("Beach")).keywordId }, "the removed tag membership should have been unlinked")
        assertNull(db.folderLockDao().get("path:dcim/camera"), "the removed lock should have been deleted")
    }

    @Test
    fun `catch up does not double count an orphan across repeated VERIFY attempts`() = runBlocking {
        val legacy = richLegacySource()
        val environment = FakeMigrationEnvironment()
        val flakyParity = FlakyParityCheck(failFirstAttempts = 2) // fails VERIFY twice, so catch-up (and its orphan re-recording) runs three times total.
        val migration = MigrationToV2(db, vectorsDb, legacy, environment, flakyParity)

        migration.run() // fails
        migration.run() // fails again
        val finalResult = migration.run() // succeeds
        assertTrue(finalResult is MigrationToV2.Result.Completed, "expected Completed, got $finalResult")

        // mediaId 3 (never migrated) is a FAVORITE orphan every one of the three USER_DATA passes
        // recorded -- it must appear exactly once, not three times.
        val favoriteOrphansForId3 = db.migrationProgressDao().getOrphans().count {
            it.kind == com.fotoxplorr.core.db.entity.OrphanKind.FAVORITE && it.mediaId == 3L
        }
        assertEquals(1, favoriteOrphansForId3, "the same orphan must not be recorded again on each catch-up pass")
    }

    @Test
    fun `permanent delete cascades every derived row a migrated asset accumulated`() = runBlocking {
        val legacy = richLegacySource()
        val environment = FakeMigrationEnvironment()
        val migration = MigrationToV2(db, vectorsDb, legacy, environment, AlwaysParityCheck())
        migration.run()

        val asset2 = requireNotNull(db.migrationProgressDao().assetIdFor(2))
        assertNotNull(db.recognitionDao().get(asset2))
        assertNotNull(db.geoDao().get(asset2))

        db.assetDao().deletePermanently(asset2)

        assertNull(db.assetDao().get(asset2))
        assertNull(db.assetUserDao().get(asset2))
        assertNull(db.recognitionDao().get(asset2))
        assertNull(db.geoDao().get(asset2))
        assertEquals(0, db.recognitionDao().facesFor(asset2).size)
    }
}

/** Fails parity for the first [failFirstAttempts] calls to [newFingerprints], then reports
 *  parity -- lets a test force [MigrationToV2.run] to stop at `VERIFY` once, deterministically,
 *  to exercise kill-and-resume and catch-up without needing a real interrupted coroutine. */
class FlakyParityCheck(private val failFirstAttempts: Int) : ProjectionParityCheck {
    private var attempts = 0
    override suspend fun oldFingerprints(): Map<String, String> = mapOf("k" to "v")
    override suspend fun newFingerprints(): Map<String, String> {
        attempts++
        return if (attempts <= failFirstAttempts) mapOf("k" to "different") else mapOf("k" to "v")
    }
}
