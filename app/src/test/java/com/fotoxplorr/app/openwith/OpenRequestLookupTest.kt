package com.fotoxplorr.app.openwith

import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An outside request to open a catalogued photo (a redirect extra, an open-with Uri, the viewer
 * restored after process death) must be held to the same "may be shown" rule as the gallery, and
 * a hidden photo must be refused rather than shown as an external file.
 */
class OpenRequestLookupTest {

    private val normal = asset(1)
    private val archived = asset(2)
    private val trashed = asset(3, trashed = true)
    private val locked = asset(4, path = "Pictures/Private/")
    private val sensitive = asset(5)
    private val all = listOf(normal, archived, trashed, locked, sensitive)
    private val lockedKey = folderIdentity(locked).key.value

    private fun browsable(
        unlocked: Set<String> = emptySet(),
        hideSensitive: Boolean = true,
    ) = browsableAssets(
        assets = all,
        archivedIds = setOf(archived.id),
        sensitiveIds = setOf(sensitive.id),
        lockedFolders = setOf(lockedKey),
        unlockedFolders = unlocked,
        hideSensitive = hideSensitive,
    )

    @Test
    fun `a browsable photo is found`() {
        assertEquals(OpenRequestLookup.Found(normal), lookupOpenRequest(all, browsable(), 1))
    }

    @Test
    fun `an archived photo is hidden`() {
        assertEquals(OpenRequestLookup.Hidden, lookupOpenRequest(all, browsable(), 2))
    }

    @Test
    fun `a trashed photo is hidden`() {
        assertEquals(OpenRequestLookup.Hidden, lookupOpenRequest(all, browsable(), 3))
    }

    @Test
    fun `a photo in a locked folder is hidden until the folder is unlocked`() {
        assertEquals(OpenRequestLookup.Hidden, lookupOpenRequest(all, browsable(), 4))
        assertEquals(
            OpenRequestLookup.Found(locked),
            lookupOpenRequest(all, browsable(unlocked = setOf(lockedKey)), 4),
        )
    }

    @Test
    fun `a sensitive photo is hidden only while hide sensitive is on`() {
        assertEquals(OpenRequestLookup.Hidden, lookupOpenRequest(all, browsable(), 5))
        assertEquals(OpenRequestLookup.Found(sensitive), lookupOpenRequest(all, browsable(hideSensitive = false), 5))
    }

    @Test
    fun `an id the catalogue has not got is unknown, not hidden`() {
        assertEquals(OpenRequestLookup.Unknown, lookupOpenRequest(all, browsable(), 99))
        assertEquals(OpenRequestLookup.Unknown, lookupOpenRequest(emptyList(), emptyList(), 1))
    }

    @Test
    fun `restoring the viewer reopens the saved run in order`() {
        val other = asset(6)
        val everything = listOf(normal, other)
        val run = restoreViewerRun(everything, savedIds = listOf(6, 1), openId = 1)
        assertEquals(listOf(other, normal), run)
    }

    @Test
    fun `restoring the viewer drops photos that are hidden now`() {
        val run = restoreViewerRun(browsable(), savedIds = listOf(1, 4, 2, 3), openId = 1)
        assertEquals(listOf(normal), run)
    }

    @Test
    fun `restoring the viewer reopens nothing when the open photo is hidden`() {
        assertNull(restoreViewerRun(browsable(), savedIds = listOf(1, 4), openId = 4))
        assertNull(restoreViewerRun(browsable(), savedIds = emptyList(), openId = 4))
    }

    @Test
    fun `restoring falls back to just the open photo when the saved run is gone`() {
        assertEquals(listOf(normal), restoreViewerRun(browsable(), savedIds = emptyList(), openId = 1))
    }

    @Test
    fun `restoring is null when the open photo no longer exists`() {
        assertNull(restoreViewerRun(browsable(), savedIds = listOf(77), openId = 77))
    }

    @Test
    fun `a media store row uri yields its id`() {
        assertEquals(42L, mediaStoreRowId("content://media/external/images/media/42"))
        assertEquals(7L, mediaStoreRowId("content://media/external_primary/video/media/7?foo=1"))
    }

    @Test
    fun `a user id prefix on the authority is still the media provider`() {
        assertEquals(42L, mediaStoreRowId("content://0@media/external/images/media/42"))
    }

    @Test
    fun `other authorities and photo picker uris are not rows`() {
        assertNull(mediaStoreRowId("content://com.example.files/media/42"))
        assertNull(mediaStoreRowId("content://media.example/external/images/media/42"))
        assertNull(mediaStoreRowId("content://media/picker/0/com.android.providers.media.photopicker/media/42"))
        assertNull(mediaStoreRowId("file:///sdcard/DCIM/42"))
        assertNull(mediaStoreRowId("content://media/external/images/media/notanumber"))
        assertNull(mediaStoreRowId("content://media"))
    }

    private fun asset(
        id: Long,
        path: String = "DCIM/Camera/",
        trashed: Boolean = false,
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        bucketName = path.trimEnd('/').substringAfterLast('/'),
        bucketId = id,
        dateTakenMillis = id * 100,
        dateModifiedSeconds = id,
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        durationMillis = 0,
        relativePath = path,
        isFavorite = false,
        isTrashed = trashed,
    )
}
