package com.fotoxplorr.app.gallery

import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryProjectionTest {
    @Test
    fun `bucket name takes precedence`() {
        assertEquals(
            "Camera",
            resolveAlbumName(" Camera ", "DCIM/Screenshots/"),
        )
    }

    @Test
    fun `relative path supplies album when bucket is missing`() {
        assertEquals(
            "Screenshots",
            resolveAlbumName(null, "Pictures/Screenshots/"),
        )
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
            visibleAssets(
                assets = listOf(camera, screenshot),
                favoriteIds = emptySet(),
                section = GallerySection.PHOTOS,
                selectedAlbum = null,
                query = "sunset",
                sort = GallerySort.NEWEST,
            ),
        )
        assertEquals(
            listOf(screenshot),
            visibleAssets(
                assets = listOf(camera, screenshot),
                favoriteIds = emptySet(),
                section = GallerySection.PHOTOS,
                selectedAlbum = null,
                query = "screenshots",
                sort = GallerySort.NEWEST,
            ),
        )
    }

    @Test
    fun `size sort returns largest first`() {
        val small = asset(1, "small.jpg", "Camera", "image/jpeg", 10, 100)
        val large = asset(2, "large.jpg", "Camera", "image/jpeg", 20, 900)

        assertEquals(
            listOf(large, small),
            visibleAssets(
                assets = listOf(small, large),
                favoriteIds = emptySet(),
                section = GallerySection.PHOTOS,
                selectedAlbum = null,
                query = "",
                sort = GallerySort.SIZE,
            ),
        )
    }

    private fun asset(
        id: Long,
        name: String,
        album: String,
        mimeType: String,
        dateTaken: Long,
        size: Long,
    ) = MediaAsset(
        id = MediaId(id),
        contentUri = Uri.parse("content://media/$id"),
        displayName = name,
        mimeType = mimeType,
        bucketName = album,
        dateTakenMillis = dateTaken,
        dateModifiedSeconds = dateTaken,
        width = 100,
        height = 100,
        sizeBytes = size,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )
}
