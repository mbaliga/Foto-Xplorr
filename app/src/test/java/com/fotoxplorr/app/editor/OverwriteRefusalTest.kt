package com.fotoxplorr.app.editor

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * [EditedCopyWriter.overwrite] against real files served through a [FileBackedProvider]: the
 * refusals leave the original byte-for-byte untouched and say why with a typed
 * [OverwriteException.Refused], and a clean overwrite keeps the original's metadata.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OverwriteRefusalTest {

    private val context = RuntimeEnvironment.getApplication()
    private val writer = EditedCopyWriter(context)
    private val made = mutableListOf<File>()
    private var nextId = 1

    init {
        FileBackedProvider.install("media")
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @After
    fun cleanUp() = made.forEach { it.delete() }

    private fun jpeg(xmp: String? = null): File {
        val file = File.createTempFile("fx-overwrite", ".jpg").also { made += it }
        FileOutputStream(file).use {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        val exif = ExifInterface(file.absolutePath)
        exif.setLatLong(51.5074, -0.1278)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:01 10:30:00")
        xmp?.let { exif.setAttribute(ExifInterface.TAG_XMP, it) }
        exif.saveAttributes()
        return file
    }

    private fun assetFor(file: File, mime: String = "image/jpeg"): MediaAsset {
        val id = "o${nextId++}"
        FileBackedProvider.serve(id, file)
        return MediaAsset(
            id = MediaId(nextId.toLong()),
            contentUriString = Uri.parse("content://media/external/images/media/$id").toString(),
            displayName = file.name,
            mimeType = mime,
            bucketName = null,
            dateTakenMillis = 0L,
            dateModifiedSeconds = 0L,
            width = 8,
            height = 8,
            sizeBytes = file.length(),
            relativePath = null,
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun edited(): Bitmap = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)

    private fun overwrite(asset: MediaAsset): Result<Unit> = runBlocking { writer.overwrite(asset, edited()) }

    private fun refusal(result: Result<Unit>): OverwriteException.Refused {
        assertTrue("expected a failure", result.isFailure)
        return result.exceptionOrNull() as OverwriteException.Refused
    }

    @Test
    fun `a clean overwrite replaces the pixels and keeps the original's metadata`() {
        val file = jpeg()
        val result = overwrite(assetFor(file))

        assertTrue("overwrite should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertEquals(12, bounds.outWidth)
        assertEquals(10, bounds.outHeight)
        val exif = ExifInterface(file.absolutePath)
        assertEquals("2024:06:01 10:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNotNull("the original's location must survive a replace", exif.latLong)
        assertTrue("the safety copy is deleted after a good replace", File(context.cacheDir, "edit-backup").listFiles().orEmpty().isEmpty())
        assertTrue(File(context.filesDir, "overwrite-recovery").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `without photo-location access the replace is refused and the original is untouched`() {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val file = jpeg()
        val before = file.readBytes()

        val refused = refusal(overwrite(assetFor(file)))

        assertEquals(OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST, refused.reason)
        assertTrue(refused.message!!, "photo-location access" in refused.message!!)
        assertArrayEquals("a refused overwrite must not touch the original", before, file.readBytes())
        assertTrue("staging must be cleaned up", File(context.cacheDir, "edit-staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a Motion Photo is refused and the original is untouched`() {
        val file = jpeg(xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description GCamera:MotionPhoto=\"1\"/></x:xmpmeta>")
        val before = file.readBytes()

        val refused = refusal(overwrite(assetFor(file)))

        assertEquals(OverwriteException.Refused.Reason.WOULD_DESTROY_CONTENT, refused.reason)
        assertEquals(setOf(OverwriteHazard.MOTION_PHOTO), refused.hazards)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `an Ultra HDR JPEG is refused`() {
        val file = jpeg(xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\"/></x:xmpmeta>")
        val before = file.readBytes()

        val refused = refusal(overwrite(assetFor(file)))

        assertEquals(setOf(OverwriteHazard.ULTRA_HDR), refused.hazards)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `an animated WebP is refused`() {
        val file = File.createTempFile("fx-overwrite-anim", ".webp").also { made += it }
        file.writeBytes(
            "RIFF".toByteArray() + byteArrayOf(0x20, 0, 0, 0) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) +
                byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0) + ByteArray(32),
        )
        val before = file.readBytes()

        val refused = refusal(overwrite(assetFor(file, mime = "image/webp")))

        assertEquals(setOf(OverwriteHazard.ANIMATION), refused.hazards)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `a format with no in-place path is refused up front`() {
        val file = File.createTempFile("fx-overwrite-gif", ".gif").also { made += it }
        file.writeBytes(ByteArray(16))

        val refused = refusal(overwrite(assetFor(file, mime = "image/gif")))

        assertEquals(OverwriteException.Refused.Reason.FORMAT_UNSUPPORTED, refused.reason)
        assertFalse(refused.message.isNullOrBlank())
    }
}
