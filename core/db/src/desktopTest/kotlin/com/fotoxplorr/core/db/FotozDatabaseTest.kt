package com.fotoxplorr.core.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.db.entity.Availability
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.FormatId
import com.fotoxplorr.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * ADR-011 §1: "Foreign keys are enabled in the driver's connection setup -- the Room equivalent
 * of `onConfigure` (TRAPS #10); verify that Room's KMP builder applies `PRAGMA foreign_keys=ON`
 * per connection, and add a test." [enforcement is real, not just reported on] is that test --
 * stronger than reading the raw pragma value back (Room's `@Query` parser rejects `PRAGMA`/
 * `pragma_foreign_keys()` syntax outright, so a read-only check isn't available here anyway):
 * if enforcement were off, the orphan insert below would silently succeed instead of throwing.
 * Plus the basic CRUD/cascade proof every other DAO in this module relies on being true.
 */
class FotozDatabaseTest {

    private lateinit var db: FotozDatabase

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<FotozDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `foreign keys are enforced, not just reported on`() = runBlocking {
        val orphanUser = AssetUserEntity(
            assetId = AssetId(999),
            favorite = false,
            rating = 0,
            flag = 0,
            colorLabel = 0,
            archived = false,
            everUnarchived = false,
            sensitive = false,
            caption = null,
            captionIsMachine = false,
            captionMachineSuppressed = false,
            archiveSuggestionRejected = false,
            sidecarState = null,
        )
        val ignored = assertFailsWith<Throwable> {
            db.assetUserDao().upsert(orphanUser)
        }
    }

    @Test
    fun `integrity_check and foreign_key_check run via RawQuery and report clean on a fresh db`() = runBlocking {
        val integrity = db.rawCheckDao().integrityCheck()
        assertEquals(listOf("ok"), integrity.map { it.result })

        val violations = db.rawCheckDao().foreignKeyCheck()
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `permanent delete cascades to asset_user`() = runBlocking {
        val sourceId = db.sourceDao().insert(testSource())
        val assetId = db.assetDao().insert(testAsset(SourceId(sourceId)))
        db.assetUserDao().upsert(testAssetUser(AssetId(assetId)))

        assertEquals(true, db.assetUserDao().get(AssetId(assetId))?.let { true } ?: false)

        db.assetDao().deletePermanently(AssetId(assetId))

        assertNull(db.assetDao().get(AssetId(assetId)))
        assertNull(db.assetUserDao().get(AssetId(assetId)))
    }

    private fun testSource() = SourceEntity(
        sourceId = SourceId(0),
        kind = SourceKind.MEDIASTORE_VOLUME,
        rootLocator = "external_primary",
        volumeUuid = null,
        displayName = "Internal storage",
        state = SourceState.ONLINE,
        syncVersion = null,
        syncGeneration = null,
        lastFullScanMs = null,
    )

    private fun testAsset(sourceId: SourceId) = AssetEntity(
        assetId = AssetId(0),
        sourceId = sourceId,
        locator = "1",
        contentUri = "content://media/external/images/media/1",
        displayName = "IMG_0001.jpg",
        mime = "image/jpeg",
        formatId = FormatId("jpeg"),
        sizeBytes = 1024,
        width = 100,
        height = 100,
        dateTakenMs = 0,
        dateModifiedMs = 0,
        dateAddedMs = null,
        relativePath = "DCIM/Camera/",
        folderKey = "1:path:dcim/camera",
        bucketId = null,
        bucketName = null,
        fingerprint = null,
        contentHash = null,
        xmpDocumentId = null,
        availability = Availability.ONLINE,
        trashedAtMs = null,
        msGenerationModified = null,
        revision = 0,
    )

    private fun testAssetUser(assetId: AssetId) = AssetUserEntity(
        assetId = assetId,
        favorite = false,
        rating = 0,
        flag = 0,
        colorLabel = 0,
        archived = false,
        everUnarchived = false,
        sensitive = false,
        caption = null,
        captionIsMachine = false,
        captionMachineSuppressed = false,
        archiveSuggestionRejected = false,
        sidecarState = null,
    )
}
