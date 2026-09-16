package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.metadata.XmpPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * Bug #1's fix, against real files -- the same shape [MetadataWriterTest] uses, for the same
 * reason: this must be proven against a real [ExifInterface] and a real encoded/decoded JPEG, not
 * a mock of either.
 *
 * NATIVE graphics mode: pixel-level assertions need `Canvas`/`Bitmap.createBitmap(..., matrix,
 * true)` to actually rasterize, which the legacy Robolectric shadow does not do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExifOrientationTest {

    private fun jpegBytes(orientation: Int?): ByteArray {
        val file = createTempFile("fx-orientation", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file.readBytes()
    }

    // ---- readExifOrientation ----

    @Test
    fun `reads back the orientation actually stored in the file`() {
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, readExifOrientation(jpegBytes(ExifInterface.ORIENTATION_ROTATE_90)))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, readExifOrientation(jpegBytes(ExifInterface.ORIENTATION_ROTATE_270)))
    }

    @Test
    fun `a photo with no orientation tag reads as UNDEFINED, not a crash`() {
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, readExifOrientation(jpegBytes(null)))
    }

    @Test
    fun `garbage bytes read as UNDEFINED rather than throwing`() {
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, readExifOrientation(byteArrayOf(1, 2, 3)))
    }

    // ---- applyExifOrientation ----

    private fun twoByOne(left: Int, right: Int): Bitmap =
        Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, left)
            setPixel(1, 0, right)
        }

    @Test
    fun `NORMAL and UNDEFINED are returned unchanged, same instance, no copy`() {
        val bitmap = twoByOne(Color.RED, Color.BLUE)
        assertSame(bitmap, applyExifOrientation(bitmap, ExifInterface.ORIENTATION_NORMAL))
        assertSame(bitmap, applyExifOrientation(bitmap, ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `flipping horizontal mirrors left and right without changing dimensions`() {
        val bitmap = twoByOne(Color.RED, Color.BLUE)
        val flipped = applyExifOrientation(bitmap, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(2, flipped.width)
        assertEquals(1, flipped.height)
        assertEquals(Color.BLUE, flipped.getPixel(0, 0))
        assertEquals(Color.RED, flipped.getPixel(1, 0))
    }

    @Test
    fun `a 180 rotation reverses pixel order without changing dimensions`() {
        val bitmap = twoByOne(Color.RED, Color.BLUE)
        val rotated = applyExifOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(2, rotated.width)
        assertEquals(1, rotated.height)
        assertEquals(Color.BLUE, rotated.getPixel(0, 0))
        assertEquals(Color.RED, rotated.getPixel(1, 0))
    }

    @Test
    fun `90 and 270 rotations swap width and height, the almost-every-phone-camera case`() {
        val bitmap = twoByOne(Color.RED, Color.BLUE)
        val rotated90 = applyExifOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(1, rotated90.width)
        assertEquals(2, rotated90.height)

        val rotated270 = applyExifOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(1, rotated270.width)
        assertEquals(2, rotated270.height)
    }

    @Test
    fun `transpose and transverse both mirror and rotate, so they too swap dimensions`() {
        val bitmap = twoByOne(Color.RED, Color.BLUE)
        assertEquals(1, applyExifOrientation(bitmap, ExifInterface.ORIENTATION_TRANSPOSE).width)
        assertEquals(1, applyExifOrientation(bitmap, ExifInterface.ORIENTATION_TRANSVERSE).width)
    }

    // ---- copyPreservableExif ----

    private fun jpegWithTags(): File {
        val file = createTempFile("fx-preserve-src", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MAKE, "ExampleCam")
            setAttribute(ExifInterface.TAG_MODEL, "Mark IV")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:06:01 12:00:00")
            setLatLong(51.5074, -0.1278)
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            // A field this whitelist deliberately does NOT cover -- see EXIF_PRESERVED_TAGS's own
            // doc for why caption is MetadataWriter's surface, not "preserve everything".
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "a caption, not a camera fact")
            saveAttributes()
        }
        return file
    }

    private fun freshJpeg(): File {
        val file = createTempFile("fx-preserve-dest", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    @Test
    fun `camera facts and GPS survive the copy, orientation is reset to 1`() {
        val source = ExifInterface(jpegWithTags().absolutePath)
        val destinationFile = freshJpeg()
        val destination = ExifInterface(destinationFile.absolutePath)

        copyPreservableExif(source, destination)

        val reread = ExifInterface(destinationFile.absolutePath)
        assertEquals("ExampleCam", reread.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Mark IV", reread.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2026:06:01 12:00:00", reread.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val latLong = FloatArray(2)
        assertTrue(reread.getLatLong(latLong))
        assertEquals(51.5074, latLong[0].toDouble(), 0.001)
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            reread.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
        )
    }

    @Test
    fun `a field MetadataWriter owns is not pulled in by the camera-facts whitelist`() {
        val source = ExifInterface(jpegWithTags().absolutePath)
        val destinationFile = freshJpeg()
        copyPreservableExif(source, ExifInterface(destinationFile.absolutePath))

        assertNull(ExifInterface(destinationFile.absolutePath).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
    }

    @Test
    fun `the XMP packet is copied as exact UTF-8 bytes, not corrupted through ASCII`() {
        val sourceFile = createTempFile("fx-preserve-xmp-src", ".jpg").toFile()
        sourceFile.deleteOnExit()
        FileOutputStream(sourceFile).use {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        // Same technique MetadataWriterTest uses for the identical reason: this app's own
        // XmpPacket always escapes non-ASCII to numeric character references on the way out
        // (see its own doc), so it can never itself produce a packet containing real UTF-8
        // bytes to prove the byte-for-byte copy against. A placeholder of the SAME byte length
        // as the intended replacement keeps the JPEG's APP1 segment length field correct after
        // the raw substitution -- "XX" (2 ASCII bytes) becomes "é" (0xC3 0xA9, 2 UTF-8 bytes).
        val placeholder = XmpPacket.empty()
        placeholder.setLangAlt(XmpPacket.DC_NS, "dc", "description", "CafXX")
        ExifInterface(sourceFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_XMP, placeholder.serialize())
            saveAttributes()
        }
        val fileBytes = sourceFile.readBytes()
        val needle = "CafXX".toByteArray(Charsets.US_ASCII)
        val at = indexOfSubarray(fileBytes, needle)
        assertTrue("could not find the placeholder XMP text to patch", at >= 0)
        val replacement = "Caf".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        assertEquals(needle.size, replacement.size)
        System.arraycopy(replacement, 0, fileBytes, at, replacement.size)
        sourceFile.writeBytes(fileBytes)

        val destinationFile = freshJpeg()
        copyPreservableExif(ExifInterface(sourceFile.absolutePath), ExifInterface(destinationFile.absolutePath))

        val copiedBytes = ExifInterface(destinationFile.absolutePath).getAttributeBytes(ExifInterface.TAG_XMP)
        assertTrue("XMP was not copied at all", copiedBytes != null)
        val copied = XmpPacket.parse(copiedBytes!!.toString(Charsets.UTF_8))!!
        assertEquals("Café", copied.langAlt(XmpPacket.DC_NS, "description"))
    }

    private fun indexOfSubarray(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }
}
