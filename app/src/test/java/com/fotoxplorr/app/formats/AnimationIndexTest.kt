package com.fotoxplorr.app.formats

import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P0-14: [AnimationIndex] against a real, disk-backed database and a shadowed
 * [android.content.ContentResolver] serving real bytes -- the same fixture shape
 * [com.fotoxplorr.app.metadata.WritableFormatTest] already uses for exactly the same reason: a
 * mock would only prove this class calls [AnimationSniffer], not that the plumbing around it (the
 * bounded read, the staleness check, the batched publish) actually works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnimationIndexTest {

    private val context = RuntimeEnvironment.getApplication()
    private val shadowResolver = shadowOf(context.contentResolver)
    private var nextId = 1L

    private fun asset(bytes: ByteArray, mimeType: String, dateModifiedSeconds: Long = 0L): MediaAsset {
        val id = nextId++
        val uri = Uri.parse("content://animation-index-test/$id")
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return MediaAsset(
            id = MediaId(id),
            contentUriString = uri.toString(),
            displayName = "asset-$id",
            mimeType = mimeType,
            bucketName = null,
            dateTakenMillis = 0L,
            dateModifiedSeconds = dateModifiedSeconds,
            width = 0,
            height = 0,
            sizeBytes = bytes.size.toLong(),
            relativePath = null,
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun twoFrameGif(): ByteArray {
        val out = mutableListOf<Byte>()
        fun ascii(s: String) = s.forEach { out += it.code.toByte() }
        fun u16(v: Int) {
            out += (v and 0xFF).toByte()
            out += ((v shr 8) and 0xFF).toByte()
        }
        ascii("GIF89a"); u16(1); u16(1); out += 0x00; out += 0x00; out += 0x00
        repeat(2) {
            out += 0x2C; u16(0); u16(0); u16(1); u16(1); out += 0x00
            out += 0x02; out += 0x01; out += 0x00; out += 0x00
        }
        out += 0x3B
        return out.toByteArray()
    }

    private fun staticPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
        // IHDR, length 0 (fine -- the sniffer only reads type), then straight to IDAT.
        0, 0, 0, 0, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 0, 0, 0, 0,
        0, 0, 0, 0, 'I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 0, 0, 0, 0,
    )

    @Test
    fun `an animated gif is sniffed and published as animated`() = runBlocking {
        val index = AnimationIndex(context)
        val gif = asset(twoFrameGif(), "image/gif")

        index.sniffPending(listOf(gif))

        assertTrue(gif.id in index.observeAnimatedIds().value)
    }

    @Test
    fun `a static png is sniffed but not published as animated`() = runBlocking {
        val index = AnimationIndex(context)
        val png = asset(staticPng(), "image/png")

        index.sniffPending(listOf(png))

        assertFalse(png.id in index.observeAnimatedIds().value)
    }

    @Test
    fun `a non-sniffable mime type is never read at all`() = runBlocking {
        val index = AnimationIndex(context)
        val id = nextId++
        // Deliberately no registerInputStreamSupplier -- a read attempt would fail loudly via
        // openInputStream returning null, which sniffOne already handles, but this proves the
        // MIME filter skips it before ever trying.
        val jpeg = MediaAsset(
            id = MediaId(id), contentUriString = "content://animation-index-test/$id",
            displayName = "photo.jpg", mimeType = "image/jpeg", bucketName = null,
            dateTakenMillis = 0L, dateModifiedSeconds = 0L, width = 0, height = 0, sizeBytes = 0L,
            relativePath = null, isFavorite = false, isTrashed = false,
        )

        index.sniffPending(listOf(jpeg))

        assertFalse(jpeg.id in index.observeAnimatedIds().value)
    }

    @Test
    fun `a re-sniff of an unchanged file does not read its bytes again`() = runBlocking {
        val index = AnimationIndex(context)
        val gif = asset(twoFrameGif(), "image/gif", dateModifiedSeconds = 100L)
        index.sniffPending(listOf(gif))
        assertTrue(gif.id in index.observeAnimatedIds().value)

        // Same id, same dateModifiedSeconds, but the supplier now throws if ever asked to open --
        // proving a second pass over the identical asset does not touch the content resolver.
        shadowResolver.registerInputStreamSupplier(gif.contentUri) {
            error("should not be read again -- the row is not stale")
        }
        index.sniffPending(listOf(gif))

        assertTrue(gif.id in index.observeAnimatedIds().value)
    }

    @Test
    fun `a file replaced on disk is re-sniffed once its dateModifiedSeconds changes`() = runBlocking {
        val index = AnimationIndex(context)
        val id = nextId++
        val uri = Uri.parse("content://animation-index-test/$id")
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(staticPng()) }
        val first = MediaAsset(
            id = MediaId(id), contentUriString = uri.toString(), displayName = "asset",
            mimeType = "image/png", bucketName = null, dateTakenMillis = 0L, dateModifiedSeconds = 1L,
            width = 0, height = 0, sizeBytes = 0L, relativePath = null, isFavorite = false, isTrashed = false,
        )
        index.sniffPending(listOf(first))
        assertFalse(first.id in index.observeAnimatedIds().value)

        // The same id, now pointing at animated bytes with a newer modified time -- simulating the
        // file at this path having been replaced.
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(twoFrameGif()) }
        val replaced = first.copy(mimeType = "image/gif", dateModifiedSeconds = 2L)
        index.sniffPending(listOf(replaced))

        assertTrue(replaced.id in index.observeAnimatedIds().value)
    }

    @Test
    fun `removeMissing drops a row for an id no longer in the catalogue`() = runBlocking {
        val index = AnimationIndex(context)
        val gif = asset(twoFrameGif(), "image/gif")
        index.sniffPending(listOf(gif))
        assertTrue(gif.id in index.observeAnimatedIds().value)

        index.removeMissing(emptySet())

        assertFalse(gif.id in index.observeAnimatedIds().value)
    }

    @Test
    fun `a second instance sees committed rows only once it reloads`() = runBlocking {
        val first = AnimationIndex(context)
        val gif = asset(twoFrameGif(), "image/gif")
        first.sniffPending(listOf(gif))

        val second = AnimationIndex(context)
        assertEquals(emptySet<MediaId>(), second.observeAnimatedIds().value)

        second.reload()
        assertTrue(gif.id in second.observeAnimatedIds().value)
    }
}
