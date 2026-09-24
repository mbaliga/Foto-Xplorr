package com.fotoxplorr.app.metadata

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile

/**
 * P0-08 item 4: `ExifInterface.TAG_XMP` is documented as a BYTE-format tag, so
 * `getAttribute(TAG_XMP)` decoding those bytes as something other than UTF-8 could turn "Zürich"
 * or "東京" into U+FFFD (the replacement character) the moment this app re-reads and re-saves a
 * file some OTHER tool wrote with real Unicode keywords -- a genuine, silent data-loss risk if
 * true, distinct from [MetadataWriter]'s own OUTPUT-side ASCII limitation on classic EXIF string
 * tags ([setAsciiAttributeOrLeave]), which is unrelated: this is about whether reading an
 * XMP packet that ALREADY contains raw (non-entity-escaped) UTF-8 bytes -- exactly what a
 * Lightroom or Capture One export actually writes -- comes back correct.
 *
 * The brief's own instruction was to write this test FIRST and let it decide whether a fix is
 * needed, not assume either way. **Result: the test FAILED against the original code** --
 * `getAttribute(TAG_XMP)` decoded "Zürich"/"東京"/"José" as U+FFFD replacement characters, a real,
 * confirmed defect, not a hypothetical one. Fixed by [ExifInterface.xmpAttributeUtf8] (see its own
 * doc), which [readXmpAttribute] and [com.fotoxplorr.app.metadata.ExifCopier.copy] now both read
 * through instead of `getAttribute`. The WRITE side needed no equivalent fix: every packet this
 * app itself produces goes through [XmpPacket.serialize]'s numeric-character-reference escaping
 * first, which is pure ASCII and so survives `setAttribute(TAG_XMP, String)` unchanged regardless
 * of which charset it uses internally -- only reading a THIRD PARTY tool's genuine raw UTF-8 bytes
 * (exactly what this test constructs) was ever at risk.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Utf8XmpPreservationTest {

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + payload
    }

    /** A real JPEG (SOI + a genuine [Bitmap.compress] body) with a hand-built APP1 XMP segment
     *  spliced in right after SOI -- genuine raw UTF-8 bytes in the packet, not the numeric
     *  character references [XmpPacket.serialize] would produce, since the whole point is testing
     *  what a THIRD PARTY tool's real UTF-8 output reads back as. */
    private fun jpegWithRawUtf8Xmp(keywords: List<String>): File {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val body = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        check(body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xD8.toByte()) {
            "test setup: Bitmap.compress did not produce a JPEG"
        }

        val items = keywords.joinToString(separator = "") { "<rdf:li>$it</rdf:li>" }
        val packet = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:subject><rdf:Bag>$items</rdf:Bag></dc:subject>" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>" +
            "<?xpacket end=\"w\"?>"
        val xmpPayload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) + packet.toByteArray(Charsets.UTF_8)
        val xmpSegment = segment(0xE1, xmpPayload)

        val file = createTempFile("fx-utf8-xmp", ".jpg").toFile()
        file.deleteOnExit()
        FileOutputStream(file).use { it.write(body.copyOfRange(0, 2) + xmpSegment + body.copyOfRange(2, body.size)) }
        return file
    }

    @Test
    fun `real UTF-8 keywords already in a file's XMP read back exact, and survive an unrelated rating edit`() {
        val keywords = listOf("Zürich", "東京", "José")
        val file = jpegWithRawUtf8Xmp(keywords)

        val readBack = readXmpAttribute(ExifInterface(file.absolutePath))!!
        assertEquals("xmpAttributeUtf8() must decode raw XMP bytes as UTF-8, unlike getAttribute", keywords, readBack.bag(XmpPacket.DC_NS, "subject"))

        applyMetadataEdit(ExifInterface(file.absolutePath), MetadataEdit(rating = 4))

        val afterEdit = readXmpAttribute(ExifInterface(file.absolutePath))!!
        assertEquals(
            "an edit to an unrelated field (rating) must not corrupt existing UTF-8 keywords",
            keywords,
            afterEdit.bag(XmpPacket.DC_NS, "subject"),
        )
        assertEquals(4, afterEdit.intValue(XmpPacket.XMP_NS, "Rating"))
    }

    /** Pins the actual defect this whole file exists to catch: [ExifInterface.getAttribute] really
     *  does mangle real UTF-8 XMP, so a future reader isn't left wondering why this app never
     *  calls it directly for [ExifInterface.TAG_XMP]. */
    @Test
    fun `getAttribute TAG_XMP mangles real UTF-8, confirming xmpAttributeUtf8 is not redundant`() {
        val file = jpegWithRawUtf8Xmp(listOf("Zürich"))

        val mangled = ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_XMP)!!

        assertFalse("this pins the defect, not a fix -- a passing assertion here means androidx changed behavior", mangled.contains("Zürich"))
    }

    @Test
    fun `ExifCopier copy also reads XMP correctly, not just readXmpAttribute`() {
        val source = jpegWithRawUtf8Xmp(listOf("Zürich", "東京"))
        val target = createTempFile("fx-utf8-xmp-target", ".jpg").toFile().also { it.deleteOnExit() }
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
            FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }

        val targetExif = ExifInterface(target.absolutePath)
        ExifCopier.copy(ExifInterface(source.absolutePath), targetExif)
        targetExif.saveAttributes()

        val copied = XmpPacket.parse(ExifInterface(target.absolutePath).getAttribute(ExifInterface.TAG_XMP)!!)!!
        assertEquals(listOf("Zürich", "東京"), copied.bag(XmpPacket.DC_NS, "subject"))
    }
}
