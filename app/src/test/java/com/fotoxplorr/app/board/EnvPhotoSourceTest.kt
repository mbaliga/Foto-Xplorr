package com.fotoxplorr.app.board

import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The seam to the Experimental host, with a stand-in for `env.decode` and `env.mayExport` (the real
 * ones are covered by the host's own tests): which photo is found, which is refused, and that
 * nothing is decoded that may not be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvPhotoSourceTest {

    private fun asset(id: Long, name: String = "IMG_$id.jpg", size: Long = id * 1000, taken: Long = 5_000L + id) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = name,
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = taken, dateModifiedSeconds = 0,
        width = 4000, height = 3000, sizeBytes = size, relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )

    private val decoded = mutableListOf<Pair<Long, DecodeLimits>>()

    private fun source(assets: List<MediaAsset>, refs: List<PhotoRef>, mayExport: (MediaAsset) -> Boolean = { true }) =
        EnvPhotoSource(assets, refs, mayExport) { a, limits ->
            decoded += a.id.value to limits
            DecodedBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), 4000, 3000, true)
        }

    @Test
    fun `a reference is made from an asset with its identity and size`() {
        val a = asset(7)
        val ref = PhotoRefs.of(a)
        assertEquals(7L, ref.mediaId)
        assertEquals(4000, ref.widthPx)
        assertEquals(3000, ref.heightPx)
        assertEquals("IMG_7.jpg", ref.identity!!.displayName)
        assertEquals(7000L, ref.identity!!.sizeBytes)
        assertEquals(5_007L, ref.identity!!.dateTakenMillis)
        assertEquals("DCIM/Camera/", ref.identity!!.relativePath)
    }

    @Test
    fun `a photo in the list is available and decoded through the host at the limits asked`() {
        val a = asset(1)
        val s = source(listOf(a), listOf(PhotoRefs.of(a)))
        assertTrue(s.isAvailable(PhotoRefs.of(a)))
        val bmp = runBlocking { s.decode(PhotoRefs.of(a), DecodeLimits(300, 90_000)) }
        assertNotNull(bmp)
        assertEquals(listOf(1L to DecodeLimits(300, 90_000)), decoded)
    }

    @Test
    fun `a photo that is not in the host's list is missing and never decoded`() {
        val s = source(listOf(asset(1)), listOf(PhotoRefs.of(asset(2))))
        assertFalse(s.isAvailable(PhotoRefs.of(asset(2))))
        assertNull(runBlocking { s.decode(PhotoRefs.of(asset(2)), DecodeLimits(300, 90_000)) })
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `a photo the host will not export is not available`() {
        val a = asset(1)
        val s = source(listOf(a), listOf(PhotoRefs.of(a)), mayExport = { false })
        assertFalse(s.isAvailable(PhotoRefs.of(a)))
    }

    @Test
    fun `a photo that moved is found under its new id`() {
        val old = PhotoRefs.of(asset(1))
        val now = asset(40, name = "IMG_1.jpg", size = 1000, taken = 5_001L)
        val s = source(listOf(now), listOf(old))
        assertEquals(PhotoResolution.Moved(40), s.resolutions[1L])
        assertTrue(s.isAvailable(old))
        runBlocking { s.decode(old, DecodeLimits(100, 10_000)) }
        assertEquals(40L, decoded.single().first)
    }

    @Test
    fun `a reference the source was not made with is still resolved`() {
        val a = asset(1)
        val s = source(listOf(a), emptyList())
        assertTrue(s.isAvailable(PhotoRefs.of(a)))
        assertFalse(s.isAvailable(PhotoRefs.of(asset(9))))
    }

    @Test
    fun `an id reused by another photo is not the photo`() {
        val old = PhotoRefs.of(asset(1))
        val reused = asset(1, name = "SOMETHING_ELSE.jpg", size = 5)
        val s = source(listOf(reused), listOf(old))
        assertFalse(s.isAvailable(old))
    }
}
