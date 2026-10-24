package com.fotoxplorr.app.editor

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.ExifCopier
import com.fotoxplorr.core.metadata.XmpPacket
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Save As ([EditedCopyWriter.saveTo]) exports to a location the user picked, so it must not carry
 * the photo's location out, while the other metadata survives; and the strip must be provable from
 * what actually reached disk. Both ends are served by a [FileBackedProvider], because Robolectric
 * cannot open a fabricated `content://media` uri for writing on its own.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditedCopyWriterLocationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val writer = EditedCopyWriter(context)
    private val made = mutableListOf<File>()
    private var nextId = 1

    init {
        FileBackedProvider.install("media")
        FileBackedProvider.install(DOCS_AUTHORITY)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @After
    fun cleanUp() = made.forEach { it.delete() }

    private fun newFile(prefix: String): File =
        File.createTempFile(prefix, ".jpg").also { made += it }

    private fun jpegWithMetadata(): File {
        val file = newFile("fx-loc-source")
        FileOutputStream(file).use {
            Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        val exif = ExifInterface(file.absolutePath)
        exif.setLatLong(51.5074, -0.1278)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:01 10:30:00")
        exif.setAttribute(ExifInterface.TAG_MAKE, "Fotoxplorr Cam Co")
        exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
        val xmp = XmpPacket.empty()
        xmp.setBag(XmpPacket.DC_NS, "dc", "subject", listOf("harbour"))
        xmp.setIntValue(XmpPacket.EXIF_NS, "exif", "GPSAltitude", 12)
        exif.setAttribute(ExifInterface.TAG_XMP, xmp.serialize())
        exif.saveAttributes()
        return file
    }

    private fun uriFor(authority: String, file: File): Uri {
        val id = "f${nextId++}"
        FileBackedProvider.serve(id, file)
        return Uri.parse("content://$authority/external/images/media/$id")
    }

    private fun assetFor(file: File) = MediaAsset(
        id = MediaId(1L),
        contentUriString = uriFor("media", file).toString(),
        displayName = "shot.jpg",
        mimeType = "image/jpeg",
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = 6,
        height = 6,
        sizeBytes = file.length(),
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    private fun saveToFile(source: File): File {
        val destination = newFile("fx-loc-dest")
        val edited = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888)
        val result = runBlocking {
            writer.saveTo(uriFor(DOCS_AUTHORITY, destination), assetFor(source), edited, OutputFormat.JPEG)
        }
        assertTrue("saveTo should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        return destination
    }

    @Test
    fun `the source fixture really carries a location, so the assertions below prove something`() {
        assertTrue(carriesLocation(jpegWithMetadata()))
    }

    @Test
    fun `Save As carries no GPS EXIF and no XMP location out, but keeps the rest`() {
        val destination = saveToFile(jpegWithMetadata())

        val exif = ExifInterface(destination.absolutePath)
        assertNull("no coordinate may reach a Save As export", exif.latLong)
        ExifCopier.GPS_TAGS.forEach { assertNull("$it must be absent", exif.getAttribute(it)) }
        assertFalse(carriesLocation(destination))

        assertEquals("2024:06:01 10:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("Fotoxplorr Cam Co", exif.getAttribute(ExifInterface.TAG_MAKE))
        val xmp = XmpPacket.parse(exif.getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals(listOf("harbour"), xmp.bag(XmpPacket.DC_NS, "subject"))
        assertNull(xmp.intValue(XmpPacket.EXIF_NS, "GPSAltitude"))
    }

    @Test
    fun `copying with keepLocation true still carries the location, as a copy beside the original does`() {
        val source = jpegWithMetadata()
        val target = newFile("fx-loc-target").also {
            FileOutputStream(it).use { out ->
                Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
        val targetExif = ExifInterface(target.absolutePath)
        ExifCopier.copy(ExifInterface(source.absolutePath), targetExif, keepLocation = true)
        targetExif.saveAttributes()

        assertNotNull(ExifInterface(target.absolutePath).latLong)
        assertTrue(carriesLocation(target))
    }

    @Test
    fun `an unparseable XMP packet is dropped from a Save As rather than risk leaking its location`() {
        val source = jpegWithMetadata()
        val exif = ExifInterface(source.absolutePath)
        exif.setAttribute(ExifInterface.TAG_XMP, "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><exif:GPSLatitude>51</exif:GPSLatitude><broken")
        exif.saveAttributes()

        val destination = saveToFile(source)

        assertFalse(carriesLocation(destination))
    }

    @Test
    fun `only a MediaStore uri read without the original on API 29 and later counts as redacted`() {
        val media = Uri.parse("content://media/external/images/media/7")
        val document = Uri.parse("content://com.example.documents/document/7")
        assertTrue(isRedactedView(34, media, readUnredacted = false))
        assertFalse(isRedactedView(34, media, readUnredacted = true))
        assertFalse(isRedactedView(28, media, readUnredacted = false))
        assertFalse(isRedactedView(34, document, readUnredacted = false))
    }

    private companion object {
        const val DOCS_AUTHORITY = "fx.test.documents"
    }
}
