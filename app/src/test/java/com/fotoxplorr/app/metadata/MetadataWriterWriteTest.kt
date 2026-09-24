package com.fotoxplorr.app.metadata

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [MetadataWriter.write] itself (P0-08 items 2-3), not just [applyMetadataEdit] --
 * [MetadataWriterTest] already covers the pure edit logic against a plain file path. This covers
 * what happens BEFORE that: the `ACCESS_MEDIA_LOCATION` refusal (which returns before any I/O at
 * all) and staging-cleanup on a failure that happens after the temp file exists but before the
 * final write-back.
 *
 * What this file does NOT cover: an end-to-end successful write that lands in the original
 * MediaStore row. Confirmed empirically, not assumed -- [ShadowContentResolver.registerInputStreamSupplier]
 * (the fixture [share.SharePreparerTest]/[share.ZipExporterTest] already use for a READ-only fake)
 * makes `openInputStream` on a fabricated `content://media/...` uri work, including through
 * `MediaStore.setRequireOriginal`'s rewritten uri once that is also registered -- but `write`'s
 * final step, `ContentResolver.openFileDescriptor(uri, "wt")`, has no such fixture in this
 * Robolectric version: it throws `FileNotFoundException: No content provider: content://media/...`
 * for any uri not backed by a real, attached `ContentProvider` component. This is the same,
 * already-documented sandbox limitation P0-07's own `EditedCopyWriter.save`/`overwrite` hit (see
 * that item's own progress-log note) -- no device or emulator here, and Robolectric's MediaStore
 * shadow does not implement a real writable-row round trip. A genuinely successful write is left
 * as a P0-19 device check rather than faked with a mock that would only prove this function calls
 * the methods it is told to.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MetadataWriterWriteTest {

    private val context = RuntimeEnvironment.getApplication()
    private val writer = MetadataWriter(context)
    private val shadowResolver = shadowOf(context.contentResolver)
    private var nextId = 1L

    private fun insertRealJpeg(width: Int = 4, height: Int = 4): Pair<Uri, MediaAsset> {
        val id = nextId++
        val uri = Uri.withAppendedPath(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        registerBytes(uri, bytes)
        val asset = MediaAsset(
            id = MediaId(id),
            contentUriString = uri.toString(),
            displayName = "test.jpg",
            mimeType = "image/jpeg",
            bucketName = null,
            dateTakenMillis = 0L,
            dateModifiedSeconds = 0L,
            width = width,
            height = height,
            sizeBytes = bytes.size.toLong(),
            relativePath = null,
            isFavorite = false,
            isTrashed = false,
        )
        return uri to asset
    }

    /**
     * Registers [bytes] under [uri] AND under `MediaStore.setRequireOriginal(uri)` --
     * [com.fotoxplorr.app.media.uriForLocationRead] opens THAT uri once `ACCESS_MEDIA_LOCATION`
     * is granted, a genuinely different [Uri] value (an appended query parameter) that
     * [ShadowContentResolver.registerInputStreamSupplier] would otherwise not match.
     */
    private fun registerBytes(uri: Uri, bytes: ByteArray) {
        val supplier = { java.io.ByteArrayInputStream(bytes) }
        shadowResolver.registerInputStreamSupplier(uri, supplier)
        shadowResolver.registerInputStreamSupplier(android.provider.MediaStore.setRequireOriginal(uri), supplier)
    }

    private fun readBytes(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun grantMediaLocation() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @Test
    fun `staging never leaves a temp file behind when the staged bytes are not a real image`() {
        grantMediaLocation()
        val id = nextId++
        val uri = Uri.withAppendedPath(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        registerBytes(uri, byteArrayOf(1, 2, 3, 4)) // not a real JPEG/PNG/WebP -- ExifInterface must reject it
        val asset = MediaAsset(
            id = MediaId(id), contentUriString = uri.toString(), displayName = "broken.jpg",
            mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0L, dateModifiedSeconds = 0L,
            width = 0, height = 0, sizeBytes = 4L, relativePath = null, isFavorite = false, isTrashed = false,
        )
        val stagingDir = File(context.cacheDir, "metadata-staging")

        val outcome = runBlocking { writer.write(asset, MetadataEdit(caption = "caption")) }

        assertTrue("write should have failed against bytes that are not a real image", outcome.isFailure)
        assertTrue(stagingDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun `on API 29+ a write refuses outright without ACCESS_MEDIA_LOCATION, before touching the file`() {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val (uri, asset) = insertRealJpeg()
        val before = readBytes(uri)

        val outcome = runBlocking { writer.write(asset, MetadataEdit(caption = "caption")) }

        assertTrue("write should have refused without the permission", outcome.isFailure)
        assertTrue("the original file must be untouched when the write refuses", before.contentEquals(readBytes(uri)))
    }

    @Test
    fun `a no-op edit refuses to do nothing without even checking the permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val (_, asset) = insertRealJpeg()

        val outcome = runBlocking { writer.write(asset, MetadataEdit()) }

        assertTrue("an empty edit must succeed as a no-op even with no permission at all", outcome.isSuccess)
    }
}
