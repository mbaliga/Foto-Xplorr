package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryProjectionTest {
    @Test
    fun `bucket name takes precedence`() {
        assertEquals(
            "Camera",
            resolveAlbumName(
                bucketName = " Camera ",
                relativePath = "DCIM/Screenshots/",
            ),
        )
    }

    @Test
    fun `relative path supplies album when bucket is missing`() {
        assertEquals(
            "Screenshots",
            resolveAlbumName(
                bucketName = null,
                relativePath = "Pictures/Screenshots/",
            ),
        )
    }

    @Test
    fun `empty values map to other`() {
        assertEquals(
            "Other",
            resolveAlbumName(
                bucketName = " ",
                relativePath = "/",
            ),
        )
    }
}
