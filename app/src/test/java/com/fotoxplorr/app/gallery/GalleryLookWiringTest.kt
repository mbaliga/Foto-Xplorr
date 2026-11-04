package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarFlag
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the look and cull wiring in GalleryScreen and GalleryLookViews. */
class GalleryLookWiringTest {
    private fun asset(i: Int, w: Int = 4032, h: Int = 3024, size: Long = 3_250_000) = MediaAsset(
        id = MediaId(i.toLong()), contentUriString = "content://m/$i", displayName = "$i.jpg",
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0,
        width = w, height = h, sizeBytes = size, relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )

    @Test
    fun `the pane takes a third of the window within readable bounds`() {
        assertEquals(280, previewPaneWidthDp(600))
        assertEquals(420, previewPaneWidthDp(1200))
        assertEquals(480, previewPaneWidthDp(3000))
    }

    @Test
    fun `pane facts name the size and the bytes, and skip what is unknown`() {
        assertEquals("4032 × 3024  ·  3.1 MB", previewFacts(asset(1)))
        assertEquals("", previewFacts(asset(1, w = 0, h = 0, size = 0)))
    }

    @Test
    fun `the culling status is words, not colour`() {
        assertEquals("Unflagged  ·  ☆☆☆☆☆", cullStatusText(null))
        assertEquals(
            "Picked  ·  ★★★★☆  ·  Blue",
            cullStatusText(SidecarAnnotations(rating = 4, label = "Blue", flag = SidecarFlag.PICK)),
        )
        assertEquals("Rejected  ·  ★★★★★", cullStatusText(SidecarAnnotations(rating = 9, label = "", flag = SidecarFlag.REJECT)))
    }

    @Test
    fun `with sidecars off the cull keeps values in memory and reports nothing durable`() {
        val photos = listOf(asset(1), asset(2))
        val store = cullingStore(photos, sidecars = false)
        val s = CullState(photos.map { it.id.value }).apply(CullAction.Flag(SidecarFlag.PICK), store)
        assertEquals(SidecarFlag.PICK, store.get(1)?.flag)
        assertFalse(store.lastPutDurable)
        assertEquals(0, store.durableCount)
        assertEquals(1, s.index)
    }

    @Test
    fun `write-through says whether the last write stuck`() {
        val store = WriteThroughAnnotationStore(InMemoryAnnotationStore())
        assertTrue(store.lastPutDurable)
        store.put(1, SidecarAnnotations(rating = 2))
        assertTrue(store.lastPutDurable)
    }

    @Test
    fun `Quick Look keys come from the Compose keys and leave the rest`() {
        assertEquals(QuickLookKey.SPACE, quickLookKeyFor(androidx.compose.ui.input.key.Key.Spacebar))
        assertEquals(QuickLookKey.ENTER, quickLookKeyFor(androidx.compose.ui.input.key.Key.Enter))
        assertNull(quickLookKeyFor(androidx.compose.ui.input.key.Key.P))
    }
}
