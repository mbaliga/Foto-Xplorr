package com.fotoxplorr.app.metadata

import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [isMetadataWritable] (P0-08 item 1): sniffed by real magic bytes through a shadowed
 * [android.content.ContentResolver], the same fixture shape [share.SharePreparerTest] already
 * uses -- not a mock that would only prove this function calls the detectors it is told to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WritableFormatTest {

    private val context = RuntimeEnvironment.getApplication()
    private val shadowResolver = shadowOf(context.contentResolver)
    private var nextId = 1L

    private fun registerAsset(bytes: ByteArray, mimeType: String): MediaAsset {
        val id = nextId++
        val uri = Uri.parse("content://writable-format-test/$id")
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return MediaAsset(
            id = MediaId(id),
            contentUriString = uri.toString(),
            displayName = "asset",
            mimeType = mimeType,
            bucketName = null,
            dateTakenMillis = 0L,
            dateModifiedSeconds = 0L,
            width = 0,
            height = 0,
            sizeBytes = bytes.size.toLong(),
            relativePath = null,
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun writable(bytes: ByteArray, mimeType: String = "application/octet-stream") =
        runBlocking { isMetadataWritable(context.contentResolver, registerAsset(bytes, mimeType)) }

    @Test
    fun `a JPEG's magic bytes are writable regardless of what MIME type the row claims`() {
        assertTrue(writable(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), mimeType = "application/octet-stream"))
    }

    @Test
    fun `a PNG's magic bytes are writable`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(writable(png))
    }

    @Test
    fun `a WebP's magic bytes are writable`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertTrue(writable(webp))
    }

    @Test
    fun `a HEIC's real bytes are not writable even if the row's own MIME type claims JPEG`() {
        // ftyp box header, roughly -- not a real HEIC, but definitely not JPEG/PNG/WebP magic bytes.
        val heicish = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray()
        assertFalse(writable(heicish, mimeType = "image/jpeg"))
    }

    @Test
    fun `a byte read that fails falls back to MIME type`() {
        val id = nextId++
        val uri = Uri.parse("content://writable-format-test/$id")
        // No registerInputStreamSupplier at all -- openInputStream returns null, exactly like an
        // asset whose grant was revoked or whose row no longer resolves.
        val asset = MediaAsset(
            id = MediaId(id), contentUriString = uri.toString(), displayName = "asset",
            mimeType = "image/png", bucketName = null, dateTakenMillis = 0L, dateModifiedSeconds = 0L,
            width = 0, height = 0, sizeBytes = 0L, relativePath = null, isFavorite = false, isTrashed = false,
        )

        assertTrue(runBlocking { isMetadataWritable(context.contentResolver, asset) })
    }

    @Test
    fun `a byte read that fails falls back to MIME and correctly says no for an unwritable type`() {
        val id = nextId++
        val uri = Uri.parse("content://writable-format-test/$id")
        val asset = MediaAsset(
            id = MediaId(id), contentUriString = uri.toString(), displayName = "asset",
            mimeType = "image/heic", bucketName = null, dateTakenMillis = 0L, dateModifiedSeconds = 0L,
            width = 0, height = 0, sizeBytes = 0L, relativePath = null, isFavorite = false, isTrashed = false,
        )

        assertFalse(runBlocking { isMetadataWritable(context.contentResolver, asset) })
    }
}
