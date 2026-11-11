package com.fotoxplorr.app.spatial

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [GeoIndexState.scannedCount] after [GeoMetadataRepository.indexMissing]. It was only ever set
 * when a repository was built, so a first-ever pass left it at 0 for the whole session and the
 * compass and the map kept asking to "read locations first" while the pins were already filling in.
 *
 * Videos are used because a video read is always a "checked" one (see `readMetadata`), so a clip
 * with no location is written to the index as a fact on any API level; Robolectric's metadata
 * retriever reports none for a source nobody registered. A still image would need a real file
 * descriptor, which Robolectric's EXIF path does not give.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoMetadataRepositoryScanCountTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun clipWithoutGps(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/video/media/$id",
        displayName = "c$id.mp4",
        mimeType = "video/mp4",
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = id,
        width = 4,
        height = 4,
        sizeBytes = 1L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `a first pass over items with no location counts them as scanned`() {
        val repo = GeoMetadataRepository(context)
        assertEquals(0, repo.observe().value.scannedCount)

        runBlocking { repo.indexMissing(listOf(clipWithoutGps(1), clipWithoutGps(2), clipWithoutGps(3))) }

        val state = repo.observe().value
        assertEquals("scanned", 3, state.scannedCount)
        assertEquals("total", 3, state.totalCount)
        assertEquals("located", 0, state.locatedCount)
        assertFalse(state.isIndexing)
    }

    @Test
    fun `a second pass with nothing new keeps the count`() {
        val repo = GeoMetadataRepository(context)
        val assets = listOf(clipWithoutGps(1), clipWithoutGps(2))
        runBlocking { repo.indexMissing(assets) }
        runBlocking { repo.indexMissing(assets) }
        assertEquals(2, repo.observe().value.scannedCount)
    }

    @Test
    fun `a later pass adds to the count`() {
        val repo = GeoMetadataRepository(context)
        val assets = listOf(clipWithoutGps(1), clipWithoutGps(2))
        runBlocking { repo.indexMissing(assets) }
        runBlocking { repo.indexMissing(assets + clipWithoutGps(3)) }
        assertEquals(3, repo.observe().value.scannedCount)
    }

    @Test
    fun `a pass with nothing missing reads the count from the index`() {
        val assets = listOf(clipWithoutGps(1), clipWithoutGps(2))
        runBlocking { GeoMetadataRepository(context).indexMissing(assets) }
        val late = GeoMetadataRepository(context)
        runBlocking { late.indexMissing(assets) }
        assertEquals(2, late.observe().value.scannedCount)
    }
}
