package com.fotoxplorr.core.db.catalogue

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.core.db.FotozDatabase
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.db.entity.Availability
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.FormatId
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CatalogueFavoriteStore] and [CatalogueSensitiveStore] are not wired into the live app yet (see
 * their own KDoc), but the [MediaId] <-> [AssetId] bridge they share via [MediaIdBridge] is the
 * exact mechanism the eventual store rewiring depends on, so it is pinned here against a real
 * (in-memory) [FotozDatabase] the same way [com.fotoxplorr.core.db.FotozDatabaseTest] pins the
 * schema itself.
 */
class CatalogueStoresTest {

    private lateinit var db: FotozDatabase
    private lateinit var favoriteStore: CatalogueFavoriteStore
    private lateinit var sensitiveStore: CatalogueSensitiveStore

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<FotozDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        favoriteStore = CatalogueFavoriteStore(db.assetDao(), db.assetUserDao())
        sensitiveStore = CatalogueSensitiveStore(db.assetDao(), db.assetUserDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `toggle flips favorite on a mapped MediaId and observe reflects it`() = runBlocking {
        val mediaId = MediaId(1L)
        seedAsset(mediaId)

        assertEquals(emptySet(), favoriteStore.observe().first())

        favoriteStore.toggle(mediaId)
        assertEquals(setOf(mediaId), favoriteStore.observe().first())

        favoriteStore.toggle(mediaId)
        assertEquals(emptySet(), favoriteStore.observe().first())
    }

    @Test
    fun `toggle on an unmapped MediaId is a silent no-op`() = runBlocking {
        // No asset row seeded for this id at all -- mirrors a photo taken after migration's
        // ASSETS step ran, before WP1.4's sync engine exists to pick it up.
        favoriteStore.toggle(MediaId(404L))
        assertEquals(emptySet(), favoriteStore.observe().first())
    }

    @Test
    fun `setFavorite adds and removes a subset, ignoring unmapped ids in the same call`() = runBlocking {
        val a = MediaId(1L)
        val b = MediaId(2L)
        seedAsset(a)
        seedAsset(b)

        favoriteStore.setFavorite(setOf(a, b, MediaId(999L)), favorite = true)
        assertEquals(setOf(a, b), favoriteStore.observe().first())

        favoriteStore.setFavorite(setOf(a), favorite = false)
        assertEquals(setOf(b), favoriteStore.observe().first())
    }

    @Test
    fun `setFavorite on an entirely unmapped set is a no-op, not a crash`() = runBlocking {
        favoriteStore.setFavorite(setOf(MediaId(123L)), favorite = true)
        assertEquals(emptySet(), favoriteStore.observe().first())
    }

    @Test
    fun `sensitive toggle, single-id setSensitive, and set-based setSensitive all resolve through the same bridge`() = runBlocking {
        val mediaId = MediaId(7L)
        seedAsset(mediaId)

        sensitiveStore.toggle(mediaId)
        assertTrue(mediaId in sensitiveStore.observe().first())

        sensitiveStore.setSensitive(mediaId, sensitive = false)
        assertEquals(emptySet(), sensitiveStore.observe().first())

        sensitiveStore.setSensitive(setOf(mediaId), sensitive = true)
        assertEquals(setOf(mediaId), sensitiveStore.observe().first())
    }

    @Test
    fun `favorite and sensitive are independent facades over the same asset_user row`() = runBlocking {
        val mediaId = MediaId(9L)
        seedAsset(mediaId)

        favoriteStore.toggle(mediaId)
        assertEquals(setOf(mediaId), favoriteStore.observe().first())
        assertEquals(emptySet(), sensitiveStore.observe().first())

        sensitiveStore.toggle(mediaId)
        assertEquals(setOf(mediaId), favoriteStore.observe().first())
        assertEquals(setOf(mediaId), sensitiveStore.observe().first())
    }

    private suspend fun seedAsset(mediaId: MediaId) {
        val sourceId = SourceId(
            db.sourceDao().findByLocator(SourceKind.MEDIASTORE_VOLUME, "external_primary")?.sourceId?.value
                ?: db.sourceDao().insert(testSource()),
        )
        val assetId = AssetId(db.assetDao().insert(testAsset(sourceId, mediaId)))
        db.assetUserDao().upsert(testAssetUser(assetId))
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

    private fun testAsset(sourceId: SourceId, mediaId: MediaId) = AssetEntity(
        assetId = AssetId(0),
        sourceId = sourceId,
        locator = mediaId.value.toString(),
        contentUri = "content://media/external/images/media/${mediaId.value}",
        displayName = "IMG_${mediaId.value}.jpg",
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
