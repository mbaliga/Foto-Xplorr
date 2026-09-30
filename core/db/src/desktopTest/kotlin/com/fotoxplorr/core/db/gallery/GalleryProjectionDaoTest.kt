package com.fotoxplorr.core.db.gallery

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.core.db.FotozDatabase
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.AssetKeywordEntity
import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.db.entity.Availability
import com.fotoxplorr.core.db.entity.KeywordEntity
import com.fotoxplorr.core.db.entity.KeywordOrigin
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.db.entity.TraitEntity
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.FormatId
import com.fotoxplorr.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GalleryProjectionDao]'s own correctness tests -- pins each smart-album query against a small,
 * hand-built fixture covering its distinguishing predicate, plus owner decision 2 (archived
 * exclusion) and the two deliberate exceptions to it ([GalleryProjectionDao.observeArchived]
 * itself, [GalleryProjectionDao.observeTrash]).
 */
class GalleryProjectionDaoTest {

    private lateinit var db: FotozDatabase
    private lateinit var dao: GalleryProjectionDao
    private var nextLocator = 1L

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<FotozDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        dao = db.galleryProjectionDao()
        runBlocking { db.sourceDao().insert(testSource()) }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `everyday excludes trashed and archived, respects the video toggle`() = runBlocking {
        val plain = seed()
        val trashed = seed(trashed = true)
        val archived = seed(archived = true)
        val video = seed(mime = "video/mp4")

        assertIds(setOf(plain, video), dao.everyday(hideSensitive = false, showVideos = true, limit = 100, offset = 0))
        assertIds(setOf(plain), dao.everyday(hideSensitive = false, showVideos = false, limit = 100, offset = 0))
        assertTrue(trashed !in ids(dao.everyday(hideSensitive = false, showVideos = true, limit = 100, offset = 0)))
        assertTrue(archived !in ids(dao.everyday(hideSensitive = false, showVideos = true, limit = 100, offset = 0)))
    }

    @Test
    fun `everyday hides sensitive only when asked to`() = runBlocking {
        val plain = seed()
        val sensitive = seed(sensitive = true)

        assertIds(setOf(plain, sensitive), dao.everyday(hideSensitive = false, showVideos = true, limit = 100, offset = 0))
        assertIds(setOf(plain), dao.everyday(hideSensitive = true, showVideos = true, limit = 100, offset = 0))
    }

    @Test
    fun `favorites -- owner decision 2, an archived favorite is excluded`() = runBlocking {
        val favorite = seed(favorite = true)
        val archivedFavorite = seed(favorite = true, archived = true)
        seed(favorite = false)

        assertIds(setOf(favorite), dao.observeFavorites(hideSensitive = false, showVideos = true, limit = 100, offset = 0))
        assertTrue(archivedFavorite !in ids(dao.observeFavorites(hideSensitive = false, showVideos = true, limit = 100, offset = 0)))
    }

    @Test
    fun `recent is bounded by dateTakenMs and excludes archived`() = runBlocking {
        val recent = seed(dateTakenMs = 2_000_000L)
        val old = seed(dateTakenMs = 500_000L)
        val archivedRecent = seed(dateTakenMs = 2_000_000L, archived = true)

        val result = dao.observeRecent(sinceMs = 1_000_000L, hideSensitive = false, showVideos = true, limit = 100, offset = 0)
        assertIds(setOf(recent), result)
        assertTrue(old !in ids(result))
        assertTrue(archivedRecent !in ids(result))
    }

    @Test
    fun `videos matches only video mime and excludes archived`() = runBlocking {
        val video = seed(mime = "video/mp4")
        val archivedVideo = seed(mime = "video/mp4", archived = true)
        seed(mime = "image/jpeg")

        val result = dao.observeVideos(hideSensitive = false, limit = 100, offset = 0)
        assertIds(setOf(video), result)
        assertTrue(archivedVideo !in ids(result))
    }

    @Test
    fun `screenshots match by name or folder, case-insensitively, and exclude archived`() = runBlocking {
        val byName = seed(displayName = "Screenshot_20260924.png")
        val byFolder = seed(bucketName = "Screenshots")
        val archivedScreenshot = seed(displayName = "SCREENSHOT.png", archived = true)
        seed(displayName = "IMG_0001.jpg", bucketName = "Camera")

        val result = dao.observeScreenshots(hideSensitive = false, showVideos = true, limit = 100, offset = 0)
        assertIds(setOf(byName, byFolder), result)
        assertTrue(archivedScreenshot !in ids(result))
    }

    @Test
    fun `animated reads trait_animated, not a mime guess, and excludes archived`() = runBlocking {
        val animated = seed()
        db.traitDao().upsert(testTrait(animated, animated = true))
        val archivedAnimated = seed(archived = true)
        db.traitDao().upsert(testTrait(archivedAnimated, animated = true))
        val notAnimated = seed()
        db.traitDao().upsert(testTrait(notAnimated, animated = false))

        val result = dao.observeAnimated(hideSensitive = false, showVideos = true, limit = 100, offset = 0)
        assertIds(setOf(animated), result)
    }

    @Test
    fun `large files matches the size threshold and excludes archived`() = runBlocking {
        val large = seed(sizeBytes = 50_000_000L)
        val archivedLarge = seed(sizeBytes = 50_000_000L, archived = true)
        val small = seed(sizeBytes = 1_000L)

        val result = dao.observeLargeFiles(
            thresholdBytes = GalleryProjectionDefaults.LARGE_FILE_THRESHOLD_BYTES,
            hideSensitive = false, showVideos = true, limit = 100, offset = 0,
        )
        assertIds(setOf(large), result)
        assertTrue(archivedLarge !in ids(result))
        assertTrue(small !in ids(result))
    }

    @Test
    fun `duplicates groups by size-width-height-mime, keeping only the earliest as the keeper`() = runBlocking {
        // A 3-member group: earliest (by dateTaken) is the keeper, the other two are candidates.
        val keeper = seed(sizeBytes = 2048, width = 100, height = 100, mime = "image/jpeg", dateTakenMs = 1_000L)
        val dup1 = seed(sizeBytes = 2048, width = 100, height = 100, mime = "image/jpeg", dateTakenMs = 2_000L)
        val dup2 = seed(sizeBytes = 2048, width = 100, height = 100, mime = "image/jpeg", dateTakenMs = 3_000L)
        // A unique item (group size 1) must never appear.
        val unique = seed(sizeBytes = 9999, width = 50, height = 50, mime = "image/png")
        // An archived duplicate is excluded by owner decision 2.
        val archivedDup = seed(sizeBytes = 2048, width = 100, height = 100, mime = "image/jpeg", dateTakenMs = 4_000L, archived = true)

        val result = dao.observeDuplicates(hideSensitive = false, showVideos = true, limit = 100, offset = 0)
        assertIds(setOf(dup1, dup2), result)
        assertTrue(keeper !in ids(result), "the earliest member of a group is the keeper, not a candidate")
        assertTrue(unique !in ids(result))
        assertTrue(archivedDup !in ids(result))
    }

    @Test
    fun `sensitive shows sensitive items regardless of the hideSensitive toggle, excludes archived`() = runBlocking {
        val sensitive = seed(sensitive = true)
        val archivedSensitive = seed(sensitive = true, archived = true)
        seed(sensitive = false)

        val result = dao.observeSensitive(showVideos = true, limit = 100, offset = 0)
        assertIds(setOf(sensitive), result)
        assertTrue(archivedSensitive !in ids(result))
    }

    @Test
    fun `archived is the one album that is NOT filtered by owner decision 2 -- it IS the archived set`() = runBlocking {
        val archived = seed(archived = true)
        seed(archived = false)

        assertIds(setOf(archived), dao.observeArchived(hideSensitive = false, showVideos = true, limit = 100, offset = 0))
    }

    @Test
    fun `trash is the deliberate exception -- an archived, trashed item still shows there`() = runBlocking {
        val trashed = seed(trashed = true)
        val archivedAndTrashed = seed(trashed = true, archived = true)
        seed(trashed = false)

        assertIds(setOf(trashed, archivedAndTrashed), dao.observeTrash(hideSensitive = false, showVideos = true, limit = 100, offset = 0))
    }

    @Test
    fun `untagged has no asset_keyword row at all, and excludes archived`() = runBlocking {
        val untagged = seed()
        val archivedUntagged = seed(archived = true)
        val tagged = seed()
        val keywordId = db.keywordDao().insert(KeywordEntity(keywordId = 0, parentId = null, name = "Beach"))
        db.keywordDao().link(AssetKeywordEntity(assetId = tagged, keywordId = keywordId, origin = KeywordOrigin.USER))

        val result = dao.observeUntagged(hideSensitive = false, showVideos = true, limit = 100, offset = 0)
        assertIds(setOf(untagged), result)
        assertTrue(tagged !in ids(result))
        assertTrue(archivedUntagged !in ids(result))
    }

    @Test
    fun `folder drill-down matches folder_key and excludes archived`() = runBlocking {
        val inFolder = seed(folderKey = "1:path:dcim/camera")
        val archivedInFolder = seed(folderKey = "1:path:dcim/camera", archived = true)
        val elsewhere = seed(folderKey = "1:path:dcim/screenshots")

        val result = dao.observeFolder("1:path:dcim/camera", showVideos = true, limit = 100, offset = 0)
        assertIds(setOf(inFolder), result)
        assertTrue(archivedInFolder !in ids(result))
        assertTrue(elsewhere !in ids(result))
    }

    @Test
    fun `limit and offset page through results in the same order`() = runBlocking {
        val a = seed(dateTakenMs = 3_000L)
        val b = seed(dateTakenMs = 2_000L)
        val c = seed(dateTakenMs = 1_000L)

        val page1 = dao.everyday(hideSensitive = false, showVideos = true, limit = 2, offset = 0)
        val page2 = dao.everyday(hideSensitive = false, showVideos = true, limit = 2, offset = 2)
        assertEquals(listOf(a, b), page1.map { it.assetId })
        assertEquals(listOf(c), page2.map { it.assetId })
    }

    // ---- fixture helpers ---------------------------------------------------------------------

    private suspend fun seed(
        displayName: String = "IMG_${nextLocator}.jpg",
        mime: String = "image/jpeg",
        sizeBytes: Long = 1024,
        width: Int = 100,
        height: Int = 100,
        dateTakenMs: Long = 1_000_000L,
        bucketName: String? = "Camera",
        folderKey: String = "1:path:dcim/camera",
        trashed: Boolean = false,
        favorite: Boolean = false,
        archived: Boolean = false,
        sensitive: Boolean = false,
    ): AssetId {
        val locator = (nextLocator++).toString()
        val assetId = AssetId(
            db.assetDao().insert(
                AssetEntity(
                    assetId = AssetId(0),
                    sourceId = SourceId(1),
                    locator = locator,
                    contentUri = "content://media/external/images/media/$locator",
                    displayName = displayName,
                    mime = mime,
                    formatId = FormatId("jpeg"),
                    sizeBytes = sizeBytes,
                    width = width,
                    height = height,
                    durationMs = 0,
                    dateTakenMs = dateTakenMs,
                    dateModifiedMs = dateTakenMs,
                    dateAddedMs = null,
                    relativePath = "DCIM/Camera/",
                    folderKey = folderKey,
                    bucketId = 1,
                    bucketName = bucketName,
                    fingerprint = null,
                    contentHash = null,
                    xmpDocumentId = null,
                    availability = Availability.ONLINE,
                    trashed = trashed,
                    trashedAtMs = null,
                    msGenerationModified = null,
                    revision = 0,
                ),
            ),
        )
        db.assetUserDao().upsert(testAssetUser(assetId, favorite = favorite, archived = archived, sensitive = sensitive))
        return assetId
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

    private fun testAssetUser(assetId: AssetId, favorite: Boolean, archived: Boolean, sensitive: Boolean) = AssetUserEntity(
        assetId = assetId,
        favorite = favorite,
        rating = 0,
        flag = 0,
        colorLabel = 0,
        archived = archived,
        everUnarchived = false,
        sensitive = sensitive,
        caption = null,
        captionIsMachine = false,
        captionMachineSuppressed = false,
        archiveSuggestionRejected = false,
        sidecarState = null,
    )

    private fun testTrait(assetId: AssetId, animated: Boolean) = TraitEntity(
        assetId = assetId,
        revision = 0,
        animated = animated,
        hdrGainmap = null,
        motionPhoto = null,
        panorama = null,
        depth = null,
        stereo = null,
        burstId = null,
    )

    private fun ids(assets: List<AssetEntity>): Set<AssetId> = assets.mapTo(linkedSetOf()) { it.assetId }

    private fun assertIds(expected: Set<AssetId>, actual: List<AssetEntity>) {
        assertEquals(expected, ids(actual))
    }
}
