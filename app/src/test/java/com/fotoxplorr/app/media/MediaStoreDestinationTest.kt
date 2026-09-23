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
        assertEquals("Pictures/Foto Xplorr/", mediaStoreRelativePath("Download/", MediaKind.IMAGE))
    }

    @Test
    fun `a WhatsApp media folder is not allowed for images, so it falls back`() {
        assertEquals(
            "Pictures/Foto Xplorr/",
            mediaStoreRelativePath("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", MediaKind.IMAGE),
        )
    }

    @Test
    fun `a null source path falls back`() {
        assertEquals("Pictures/Foto Xplorr/", mediaStoreRelativePath(null, MediaKind.IMAGE))
    }

    @Test
    fun `Movies is allowed for video, so it keeps its exact path`() {
        assertEquals("Movies/", mediaStoreRelativePath("Movies/", MediaKind.VIDEO))
    }

    @Test
    fun `Movies is not allowed for images, so it falls back to the image fallback`() {
        assertEquals("Pictures/Foto Xplorr/", mediaStoreRelativePath("Movies/", MediaKind.IMAGE))
    }

    @Test
    fun `Documents is not allowed for any collection, so it falls back`() {
        assertEquals("Pictures/Foto Xplorr/", mediaStoreRelativePath("Documents/", MediaKind.IMAGE))
        assertEquals("Movies/Foto Xplorr/", mediaStoreRelativePath("Documents/", MediaKind.VIDEO))
        assertEquals("Music/Foto Xplorr/", mediaStoreRelativePath("Documents/", MediaKind.AUDIO))
    }

    @Test
    fun `DCIM is allowed for video too, not just images`() {
        assertEquals("DCIM/Camera/", mediaStoreRelativePath("DCIM/Camera/", MediaKind.VIDEO))
    }

    @Test
    fun `Music is allowed for audio, so it keeps its exact path`() {
        assertEquals("Music/Playlists/", mediaStoreRelativePath("Music/Playlists/", MediaKind.AUDIO))
    }

    @Test
    fun `Podcasts, Audiobooks, Ringtones, Notifications and Alarms are all allowed for audio`() {
        listOf("Podcasts/", "Audiobooks/", "Ringtones/", "Notifications/", "Alarms/").forEach { path ->
            assertEquals(path, mediaStoreRelativePath(path, MediaKind.AUDIO))
        }
    }

    @Test
    fun `Recordings is not allowed for audio below API 31`() {
        assertEquals("Music/Foto Xplorr/", mediaStoreRelativePath("Recordings/", MediaKind.AUDIO, sdkInt = 30))
    }

    @Test
    fun `Recordings is allowed for audio from API 31`() {
        assertEquals("Recordings/", mediaStoreRelativePath("Recordings/", MediaKind.AUDIO, sdkInt = 31))
    }

    @Test
    fun `a blank source path falls back the same as null`() {
        assertEquals("Pictures/Foto Xplorr/", mediaStoreRelativePath("   ", MediaKind.IMAGE))
    }
}
