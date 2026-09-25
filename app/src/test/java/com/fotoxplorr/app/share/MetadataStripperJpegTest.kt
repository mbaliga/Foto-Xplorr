package com.fotoxplorr.app.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.core.metadata.MetadataStripper
import com.fotoxplorr.core.metadata.strip
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two [MetadataStripper] JPEG cases that need a genuinely decodable image, not just a
 * syntactically-valid byte layout: confirming the stripped output still decodes to the same
 * dimensions, and confirming a real photo's GPS is actually gone by both a structured re-read and
 * a raw byte scan. Built with [Bitmap.compress] rather than a JDK JPEG encoder -- this module's
 * Kotlin compilation does not expose `java.desktop` (`java.awt`/`javax.imageio` are unavailable
 * even in test sources), which is also why this needs NATIVE graphics mode: the legacy Robolectric
 * graphics shadow draws and decodes nothing real, the same reason
 * [MetadataWriterTest][com.fotoxplorr.app.metadata.MetadataWriterTest] and
 * [BitmapDecodingImageDecoderExifTest][com.fotoxplorr.app.media.BitmapDecodingImageDecoderExifTest]
 * use it. See [MetadataStripperTest] for the progressive-style multi-scan entropy-data case, which
 * does not need a real decodable image and so stays a plain, fast JUnit test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MetadataStripperJpegTest {

    private fun strip(input: ByteArray): Pair<MetadataStripper.StripResult, ByteArray> {
        val output = ByteArrayOutputStream()
        val result = MetadataStripper.strip(input.inputStream(), output)
        return result to output.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + payload
    }

    private fun realJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @Test
    fun `a real JPEG keeps its own bytes untouched around injected junk segments and still decodes to the same size after stripping`() {
        val original = realJpeg(6, 4)
        check(original.size >= 4 && original[0] == 0xFF.toByte() && original[1] == 0xD8.toByte()) {
            "test setup: Bitmap.compress did not produce a JPEG"
        }
        val iccPayload = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1) + ByteArray(8) { it.toByte() }
        val iccSegment = segment(0xE2, iccPayload)
        val injected = segment(0xE1, "Exif\u0000\u0000FAKE-EXIF-WITH-GPS".toByteArray()) +
            segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<FAKE XMP/>".toByteArray()) +
            iccSegment +
            segment(0xED, "FAKE PHOTOSHOP IPTC BLOCK".toByteArray()) +
            segment(0xFE, "a comment nobody needs to keep".toByteArray())
        val trailer = byteArrayOf(0x4D, 0x50, 0x46, 0x00, 0x01, 0x02, 0x03) // pretend motion-photo trailer after EOI

        // Everything from SOI up to (not including) the real APP0 gets replaced with the injected
        // junk segments -- the real content (APP0 onward through EOI) is untouched.
        val input = original.copyOfRange(0, 2) + injected + original.copyOfRange(2, original.size) + trailer

        val (result, output) = strip(input)

        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.JPEG), result)
        val expected = original.copyOfRange(0, 2) + iccSegment + original.copyOfRange(2, original.size)
        assertArrayEquals(expected, output)

        val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
        assertEquals(6, decoded.width)
        assertEquals(4, decoded.height)
    }

    @Test
    fun `a real photo's GPS is gone after stripping, confirmed by both a structured re-read and a raw byte scan`() {
        val file = createTempFile("fx-strip-source", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_MAKE, "ExampleCam")
            saveAttributes()
        }
        check(ExifInterface(file.absolutePath).latLong != null) { "test setup: GPS was not actually written" }

        val (result, output) = strip(file.readBytes())

        assertEquals(MetadataStripper.StripResult.Stripped(MetadataStripper.Format.JPEG), result)
        assertNull(ExifInterface(ByteArrayInputStream(output)).latLong)
        val scan = String(output, Charsets.ISO_8859_1)
        assertFalse(scan.contains("GPS"))
        assertFalse(scan.contains("ExampleCam"))

        val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
        assertEquals(6, decoded.width)
        assertEquals(4, decoded.height)
    }
}
