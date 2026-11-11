package com.fotoxplorr.app.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [mediaStoreRelativePath]'s exact cases from the P0-07 brief. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreDestinationTest {

    @Test
    fun `a camera photo's own folder is allowed for images, so it keeps its exact path`() {
        assertEquals("DCIM/Camera/", mediaStoreRelativePath("DCIM/Camera/", MediaKind.IMAGE))
    }

    @Test
    fun `a screenshot's own folder is allowed for images, so it keeps its exact path`() {
        assertEquals("Pictures/Screenshots/", mediaStoreRelativePath("Pictures/Screenshots/", MediaKind.IMAGE))
    }

    @Test
    fun `Download is not an allowed image folder, so it falls back`() {
        assertEquals("Pictures/Fotoz/", mediaStoreRelativePath("Download/", MediaKind.IMAGE))
    }

    @Test
    fun `a WhatsApp media folder is not allowed for images, so it falls back`() {
        assertEquals(
            "Pictures/Fotoz/",
            mediaStoreRelativePath("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", MediaKind.IMAGE),
        )
    }

    @Test
    fun `a null source path falls back`() {
        assertEquals("Pictures/Fotoz/", mediaStoreRelativePath(null, MediaKind.IMAGE))
    }

    @Test
    fun `Movies is allowed for video, so it keeps its exact path`() {
        assertEquals("Movies/", mediaStoreRelativePath("Movies/", MediaKind.VIDEO))
    }

    @Test
    fun `Movies is not allowed for images, so it falls back to the image fallback`() {
        assertEquals("Pictures/Fotoz/", mediaStoreRelativePath("Movies/", MediaKind.IMAGE))
    }

    @Test
    fun `Documents is not allowed for either collection, so it falls back`() {
        assertEquals("Pictures/Fotoz/", mediaStoreRelativePath("Documents/", MediaKind.IMAGE))
        assertEquals("Movies/Fotoz/", mediaStoreRelativePath("Documents/", MediaKind.VIDEO))
    }

    @Test
    fun `DCIM is allowed for video too, not just images`() {
        assertEquals("DCIM/Camera/", mediaStoreRelativePath("DCIM/Camera/", MediaKind.VIDEO))
    }

    @Test
    fun `a source already in the legacy Foto Xplorr folder keeps its exact path`() {
        // Folders created by builds before the rename stay recognised: they are inside an
        // allowed top folder, so an edit of an old copy is saved beside it, not in Pictures/Fotoz/.
        assertEquals("Pictures/Foto Xplorr/", mediaStoreRelativePath("Pictures/Foto Xplorr/", MediaKind.IMAGE))
        assertEquals("Movies/Foto Xplorr/", mediaStoreRelativePath("Movies/Foto Xplorr/", MediaKind.VIDEO))
    }

    @Test
    fun `a blank source path falls back the same as null`() {
        assertEquals("Pictures/Fotoz/", mediaStoreRelativePath("   ", MediaKind.IMAGE))
    }
}
