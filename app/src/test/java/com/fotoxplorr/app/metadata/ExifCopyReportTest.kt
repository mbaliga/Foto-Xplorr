package com.fotoxplorr.app.metadata

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** [ExifCopier.copy] must say when it left an XMP packet behind, so a caller that cannot afford the
 *  loss (replacing the original) is not told the copy was complete. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExifCopyReportTest {

    private val made = mutableListOf<File>()

    @After
    fun cleanUp() = made.forEach { it.delete() }

    private fun baseJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun jpegWithXmp(packet: ByteArray?): File {
        val base = baseJpeg()
        val segment = packet?.let {
            val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + it
            val length = payload.size + 2
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        } ?: ByteArray(0)
        return File.createTempFile("fx-report", ".jpg").also {
            made += it
            it.writeBytes(base.copyOfRange(0, 2) + segment + base.copyOfRange(2, base.size))
        }
    }

    private fun copy(source: File, keepLocation: Boolean = true): Pair<ExifCopyReport, ExifInterface> {
        val target = File.createTempFile("fx-report-target", ".jpg").also { made += it; it.writeBytes(baseJpeg()) }
        val targetExif = ExifInterface(target.absolutePath)
        val report = ExifCopier.copy(ExifInterface(source.absolutePath), targetExif, keepLocation)
        targetExif.saveAttributes()
        return report to ExifInterface(target.absolutePath)
    }

    @Test
    fun `an unparseable packet with non-ASCII text is skipped and reported`() {
        val broken = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><dc:creator>José</dc:creator><broken".toByteArray(Charsets.UTF_8)

        val (report, target) = copy(jpegWithXmp(broken))

        assertTrue(report.xmpSkipped)
        assertNull(target.getAttributeBytes(ExifInterface.TAG_XMP))
    }

    @Test
    fun `an unparseable ASCII packet is passed through untouched and not reported`() {
        val (report, target) = copy(jpegWithXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><broken".toByteArray()))

        assertFalse(report.xmpSkipped)
        assertNotNull(target.getAttributeBytes(ExifInterface.TAG_XMP))
    }

    @Test
    fun `a packet that parses is carried and not reported`() {
        val packet = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>"

        val (report, target) = copy(jpegWithXmp(packet.toByteArray()))

        assertFalse(report.xmpSkipped)
        assertNotNull(target.getAttributeBytes(ExifInterface.TAG_XMP))
    }

    @Test
    fun `a source with no XMP has nothing to skip`() {
        val (report, _) = copy(jpegWithXmp(null))

        assertFalse(report.xmpSkipped)
    }

    @Test
    fun `an unparseable packet is skipped and reported when location has to be stripped too`() {
        val (report, target) = copy(jpegWithXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><broken".toByteArray()), keepLocation = false)

        assertTrue(report.xmpSkipped)
        assertNull(target.getAttributeBytes(ExifInterface.TAG_XMP))
    }
}
