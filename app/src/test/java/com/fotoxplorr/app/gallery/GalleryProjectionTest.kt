package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryProjectionTest {
    @Test
    fun `bucket name takes precedence`() {
        assertEquals("Camera", resolveAlbumName(" Camera ", "DCIM/Screenshots/"))
    }

    @Test
    fun `relative path supplies album when bucket is missing`() {
        assertEquals("Screenshots", resolveAlbumName(null, "Pictures/Screenshots/"))
    }

    @Test
    fun `empty values map to other`() {
        assertEquals("Other", resolveAlbumName(" ", "/"))
    }

    @Test
    fun `search matches filename album and mime type`() {
        val camera = asset(1, "Sunset.JPG", "Camera", "image/jpeg", 10, 20)
        val screenshot = asset(2, "capture.png", "Screenshots", "image/png", 20, 10)

        assertEquals(
            listOf(camera),
            visibleAssets(listOf(camera, screenshot), emptySet(), GallerySection.PHOTOS, null, "sunset", GallerySort.NEWEST),
        )
        assertEquals(
            listOf(screenshot),
            visibleAssets(listOf(camera, screenshot), emptySet(), GallerySection.PHOTOS, null, "screenshots", GallerySort.NEWEST),
        )
    }

    @Test
    fun `size sort returns largest first`() {
        val small = asset(1, "small.jpg", "Camera", "image/jpeg", 10, 100)
        val large = asset(2, "large.jpg", "Camera", "image/jpeg", 20, 900)

        assertEquals(
            listOf(large, small),
            visibleAssets(listOf(small, large), emptySet(), GallerySection.PHOTOS, null, "", GallerySort.SIZE),
        )
    }

    @Test
    fun `locked folder media is hidden using stable key`() {
        val public = asset(1, "public.jpg", "Camera", "image/jpeg", 20, 100)
        val private = asset(2, "secret.jpg", "Private A", "image/jpeg", 30, 200, "Pictures/Private A/")
        val privateKey = folderIdentity(private).key.value

        assertEquals(
            listOf(public),
            visibleAssets(
                assets = listOf(public, private),
                favoriteIds = setOf(private.id),
                section = GallerySection.PHOTOS,
                selectedAlbum = null,
                query = "",
                sort = GallerySort.NEWEST,
                lockedFolders = setOf(privateKey),
            ),
        )
    }

    @Test
    fun `unlocked folder media returns to projections`() {
        val private = asset(2, "secret.jpg", "Private A", "image/jpeg", 30, 200, "Pictures/Private A/")
        val privateKey = folderIdentity(private).key.value

        assertEquals(
            listOf(private),
            visibleAssets(
                assets = listOf(private),
                favoriteIds = emptySet(),
                section = GallerySection.ALBUMS,
                selectedAlbum = privateKey,
                query = "",
                sort = GallerySort.NEWEST,
                lockedFolders = setOf(privateKey),
                unlockedFolders = setOf(privateKey),
            ),
        )
    }

    @Test
    fun `trashed media appears only in trash`() {
        val normal = asset(1, "normal.jpg", "Camera", "image/jpeg", 10, 100)
        val trashed = asset(2, "trashed.jpg", "Camera", "image/jpeg", 20, 100, trashed = true)

        assertEquals(
            listOf(normal),
            visibleAssets(listOf(normal, trashed), emptySet(), GallerySection.PHOTOS, null, "", GallerySort.NEWEST),
        )
        assertEquals(
            listOf(trashed),
            visibleAssets(listOf(normal, trashed), emptySet(), GallerySection.TRASH, null, "", GallerySort.NEWEST),
        )
    }

    private fun asset(
        id: Long,
        name: String,
        album: String,
        mimeType: String,
        dateTaken: Long,
        size: Long,
        relativePath: String? = null,
        trashed: Boolean = false,
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/$id",
        displayName = name,
        mimeType = mimeType,
        bucketName = album,
        dateTakenMillis = dateTaken,
        dateModifiedSeconds = dateTaken,
        width = 100,
        height = 100,
        sizeBytes = size,
        relativePath = relativePath,
        isFavorite = false,
        isTrashed = trashed,
    )
}
