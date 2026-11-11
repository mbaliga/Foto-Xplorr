package com.fotoxplorr.app.editor

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The overwrite metadata gate against files whose metadata [com.fotoxplorr.app.metadata.ExifCopier]
 * cannot carry across: a replace that would silently drop any of it must be refused with the
 * original byte-for-byte untouched, while a file whose metadata does travel still replaces.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OverwriteMetadataGateTest {

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

    private fun baseJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** A JPEG segment: marker, big-endian length (which counts itself), payload. */
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun xmpSegment(packet: ByteArray) = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + packet)

    /** An APP13 Photoshop block holding one IPTC-IIM caption (record 2, dataset 120). */
    private fun iptcSegment(caption: String): ByteArray {
        val text = caption.toByteArray()
        val dataset = byteArrayOf(0x1C, 0x02, 120, (text.size shr 8).toByte(), text.size.toByte()) + text
        val padded = if (dataset.size % 2 == 0) dataset else dataset + 0
        val resource = "8BIM".toByteArray() + byteArrayOf(0x04, 0x04, 0, 0) +
            byteArrayOf(0, 0, (dataset.size shr 8).toByte(), dataset.size.toByte()) + padded
        return segment(0xED, "Photoshop 3.0\u0000".toByteArray() + resource)
    }

    private fun extendedXmpSegment(): ByteArray =
        segment(0xE1, "http://ns.adobe.com/xmp/extension/\u0000".toByteArray() + ByteArray(40) + "<x/>".toByteArray())

    /** The test JPEG with [extra] segments spliced in straight after the SOI marker. */
    private fun jpegWith(vararg extra: ByteArray): File {
        val base = baseJpeg()
        val bytes = base.copyOfRange(0, 2) + extra.fold(ByteArray(0)) { acc, s -> acc + s } + base.copyOfRange(2, base.size)
        val file = File.createTempFile("fx-gate", ".jpg").also { made += it }
        file.writeBytes(bytes)
        val exif = ExifInterface(file.absolutePath)
        exif.setLatLong(51.5074, -0.1278)
        exif.saveAttributes()
        return file
    }

    private fun assetFor(file: File): MediaAsset {
        val id = "g${nextId++}"
        FileBackedProvider.serve(id, file)
        return MediaAsset(
            id = MediaId(nextId.toLong()),
            contentUriString = Uri.parse("content://media/external/images/media/$id").toString(),
            displayName = file.name,
            mimeType = "image/jpeg",
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

    private fun overwrite(asset: MediaAsset): Result<Unit> =
        runBlocking { writer.overwrite(asset, Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)) }

    private fun assertRefusedAsMetadataLoss(file: File) {
        val before = file.readBytes()
        val result = overwrite(assetFor(file))
        assertTrue("expected a failure", result.isFailure)
        val refused = result.exceptionOrNull() as OverwriteException.Refused
        assertEquals(OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST, refused.reason)
        assertArrayEquals("a refused overwrite must not touch the original", before, file.readBytes())
        assertTrue(File(context.filesDir, "overwrite-recovery").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an XMP packet that cannot be parsed and has non-ASCII text is not silently dropped by a replace`() {
        val broken = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><dc:creator>José García</dc:creator><broken".toByteArray(Charsets.UTF_8)
        assertRefusedAsMetadataLoss(jpegWith(xmpSegment(broken)))
    }

    @Test
    fun `an IPTC-IIM caption block is not silently dropped by a replace`() {
        assertRefusedAsMetadataLoss(jpegWith(iptcSegment("Harbour at dawn")))
    }

    @Test
    fun `extended XMP is not silently dropped by a replace`() {
        val main = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>"
        assertRefusedAsMetadataLoss(jpegWith(xmpSegment(main.toByteArray()), extendedXmpSegment()))
    }

    @Test
    fun `a parseable XMP packet still travels through a replace`() {
        val packet = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:subject><rdf:Bag><rdf:li>harbour</rdf:li></rdf:Bag></dc:subject></rdf:Description>" +
            "</rdf:RDF></x:xmpmeta>"
        val file = jpegWith(xmpSegment(packet.toByteArray()))

        val result = overwrite(assetFor(file))

        assertTrue("overwrite should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val xmp = ExifInterface(file.absolutePath).getAttributeBytes(ExifInterface.TAG_XMP)
        assertNotNull("the packet must still be there after the replace", xmp)
        assertTrue("harbour" in xmp!!.toString(Charsets.UTF_8))
    }
}
