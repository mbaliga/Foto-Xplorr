package com.fotoxplorr.app.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile

/**
 * [ExifCopier.copy] against two REAL JPEGs on disk, by file path -- the exact shape
 * [com.fotoxplorr.app.editor.EditedCopyWriter] uses it in (source read via an already-open
 * [ExifInterface], target a temp file on disk), proving the copy survives an actual file write and
 * re-read rather than an in-memory reassembly of [ExifCopier]'s pieces.
 *
 * NATIVE graphics mode -- see [MetadataWriterTest]'s identical note: the legacy Robolectric
 * graphics shadow draws nothing, so `Bitmap.compress` would produce an empty or invalid JPEG.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExifCopierTest {

    private fun jpeg(prefix: String, width: Int, height: Int): File {
        val file = createTempFile(prefix, ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    private fun exif(file: File) = ExifInterface(file.absolutePath)

    @Test
    fun `copy carries GPS, DateTimeOriginal, Make, Model and XMP onto an edited copy, resets orientation, keeps the copy's own dimensions`() {
        val source = jpeg("fx-exifcopier-source", width = 4, height = 4)
        val sourceExif = exif(source)
        sourceExif.setLatLong(51.5074, -0.1278)
        sourceExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:01 10:30:00")
        sourceExif.setAttribute(ExifInterface.TAG_MAKE, "Fotoxplorr Cam Co")
        sourceExif.setAttribute(ExifInterface.TAG_MODEL, "Model Nine")
        sourceExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        val sourceXmp = XmpPacket.empty()
        sourceXmp.setBag(XmpPacket.DC_NS, "dc", "subject", listOf("mountains", "sunrise"))
        sourceExif.setAttribute(ExifInterface.TAG_XMP, sourceXmp.serialize())
        sourceExif.saveAttributes()

        // A different size than the source -- standing in for the edited bitmap's own re-encode,
        // exactly what save()/overwrite() hand ExifCopier.copy in the real flow.
        val target = jpeg("fx-exifcopier-target", width = 8, height = 6)
        val targetExif = exif(target)
        ExifCopier.copy(exif(source), targetExif)
        targetExif.saveAttributes()

        val reread = exif(target)
        val latLong = FloatArray(2)
        assertTrue("GPS should have been copied", reread.getLatLong(latLong))
        assertEquals(51.5074, latLong[0].toDouble(), 0.001)
        assertEquals(-0.1278, latLong[1].toDouble(), 0.001)
        assertEquals("2024:06:01 10:30:00", reread.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("Fotoxplorr Cam Co", reread.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Model Nine", reread.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(
            "orientation must be reset to normal, not copied from the source's rotated value",
            ExifInterface.ORIENTATION_NORMAL,
            reread.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
        )

        val xmp = XmpPacket.parse(reread.getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals(listOf("mountains", "sunrise"), xmp.bag(XmpPacket.DC_NS, "subject"))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        assertEquals("the copy's own (edited) dimensions must survive, not the source's", 8, bounds.outWidth)
        assertEquals(6, bounds.outHeight)
    }

    @Test
    fun `an XMP packet with tiff Orientation is reset to 1, not copied verbatim`() {
        val source = jpeg("fx-exifcopier-xmp-orientation-source", width = 2, height = 2)
        val sourceExif = exif(source)
        val sourceXmp = XmpPacket.empty()
        sourceXmp.setIntValue(XmpPacket.TIFF_NS, "tiff", "Orientation", 6)
        sourceExif.setAttribute(ExifInterface.TAG_XMP, sourceXmp.serialize())
        sourceExif.saveAttributes()

        val target = jpeg("fx-exifcopier-xmp-orientation-target", width = 2, height = 2)
        val targetExif = exif(target)
        ExifCopier.copy(exif(source), targetExif)
        targetExif.saveAttributes()

        val xmp = XmpPacket.parse(exif(target).getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals(1, xmp.intValue(XmpPacket.TIFF_NS, "Orientation"))
    }

    @Test
    fun `a source with no XMP at all leaves the copy with no XMP, not an invented empty packet`() {
        val source = jpeg("fx-exifcopier-no-xmp-source", width = 2, height = 2)
        val target = jpeg("fx-exifcopier-no-xmp-target", width = 2, height = 2)
        val targetExif = exif(target)

        ExifCopier.copy(exif(source), targetExif)
        targetExif.saveAttributes()

        assertEquals(null, exif(target).getAttribute(ExifInterface.TAG_XMP))
    }
}
