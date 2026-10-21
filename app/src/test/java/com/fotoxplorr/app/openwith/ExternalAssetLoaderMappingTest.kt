package com.fotoxplorr.app.openwith

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAssetLoaderMappingTest {

    @Test
    fun `synthetic ids are always negative`() {
        val uris = listOf("content://a", "content://b/c", "file:///storage/x.jpg", "")
        uris.forEach { uri -> assertTrue(uri, syntheticNegativeMediaId(uri).value < 0) }
    }

    @Test
    fun `synthetic ids are deterministic for the same uri`() {
        assertEquals(syntheticNegativeMediaId("content://media/1"), syntheticNegativeMediaId("content://media/1"))
    }

    @Test
    fun `different uris get different synthetic ids`() {
        assertNotEquals(syntheticNegativeMediaId("content://media/1"), syntheticNegativeMediaId("content://media/2"))
    }

    @Test
    fun `an external image asset is marked external and takes the loaded dimensions`() {
        val asset = externalMediaAsset(
            uri = "content://media/1",
            metadata = ExternalMediaMetadata(
                displayName = "sunset.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 2048,
                widthPx = 1920,
                heightPx = 1080,
            ),
            isVideo = false,
        )

        assertTrue(asset.isExternal)
        assertEquals("sunset.jpg", asset.displayName)
        assertEquals("image/jpeg", asset.mimeType)
        assertEquals(1920, asset.width)
        assertEquals(1080, asset.height)
        assertEquals(2048L, asset.sizeBytes)
        assertTrue(asset.id.value < 0)
    }

    @Test
    fun `a missing display name falls back to the uri's own last segment`() {
        val asset = externalMediaAsset(
            uri = "content://media/external/images/media/482",
            metadata = ExternalMediaMetadata(),
            isVideo = false,
        )
        assertEquals("482", asset.displayName)
    }

    @Test
    fun `an external video defaults its mime type when none was resolved`() {
        val asset = externalMediaAsset("content://media/2", ExternalMediaMetadata(), isVideo = true)
        assertEquals("video/*", asset.mimeType)
        assertTrue(asset.isExternal)
    }

    @Test
    fun `an external audio asset uses its display name as the title`() {
        val asset = externalAudioAsset(
            "content://media/3",
            ExternalMediaMetadata(displayName = "voice-memo.m4a", durationMillis = 5_000),
        )
        assertEquals("voice-memo.m4a", asset.title)
        assertEquals(5_000L, asset.durationMillis)
        assertTrue(asset.id.value < 0)
    }
}
