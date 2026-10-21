package com.fotoxplorr.app.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The pure half of the overwrite guard: what [overwriteHazards] recognises, how the head and tail
 *  windows are read, and which metadata outcomes refuse. */
class OverwriteGuardTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun jpegWithXmp(xmp: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x01, 0x00) +
            ascii("http://ns.adobe.com/xap/1.0/\u0000$xmp") +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** RIFF header, then VP8X whose flags byte (offset 20) is [flags]. */
    private fun webp(flags: Int): ByteArray =
        ascii("RIFF") + byteArrayOf(0x20, 0, 0, 0) + ascii("WEBPVP8X") + byteArrayOf(10, 0, 0, 0) +
            byteArrayOf(flags.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)

    // ---- animation ----

    @Test
    fun `a WebP whose VP8X header declares animation is a hazard`() {
        assertEquals(setOf(OverwriteHazard.ANIMATION), overwriteHazards(webp(0x02), ByteArray(0), "image/webp"))
    }

    @Test
    fun `a still WebP is not`() {
        assertTrue(overwriteHazards(webp(0x10), ByteArray(0), "image/webp").isEmpty())
        // The simple (non-VP8X) layout cannot animate at all.
        val simple = ascii("RIFF") + byteArrayOf(0x20, 0, 0, 0) + ascii("WEBPVP8 ") + ByteArray(16)
        assertTrue(overwriteHazards(simple, ByteArray(0), "image/webp").isEmpty())
    }

    @Test
    fun `an APNG is a hazard and a plain PNG is not`() {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        fun chunk(type: String, length: Int) =
            byteArrayOf(0, 0, 0, length.toByte()) + ascii(type) + ByteArray(length) + ByteArray(4)
        val apng = signature + chunk("IHDR", 13) + chunk("acTL", 8) + chunk("IDAT", 4)
        val plain = signature + chunk("IHDR", 13) + chunk("IDAT", 4)
        assertEquals(setOf(OverwriteHazard.ANIMATION), overwriteHazards(apng, ByteArray(0), "image/png"))
        assertTrue(overwriteHazards(plain, ByteArray(0), "image/png").isEmpty())
    }

    // ---- Ultra HDR ----

    @Test
    fun `a JPEG whose XMP declares the gain map namespace is Ultra HDR`() {
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\"/></x:xmpmeta>"
        assertEquals(setOf(OverwriteHazard.ULTRA_HDR), overwriteHazards(jpegWithXmp(xmp), ByteArray(0), "image/jpeg"))
    }

    @Test
    fun `a JPEG whose container lists a GainMap item is Ultra HDR`() {
        val xmp = "<Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\"/>"
        assertEquals(setOf(OverwriteHazard.ULTRA_HDR), overwriteHazards(jpegWithXmp(xmp), ByteArray(0), "image/jpeg"))
    }

    // ---- Motion Photo ----

    @Test
    fun `Google's Motion Photo flags in the XMP are recognised`() {
        assertEquals(
            setOf(OverwriteHazard.MOTION_PHOTO),
            overwriteHazards(jpegWithXmp("<rdf:Description GCamera:MotionPhoto=\"1\"/>"), ByteArray(0), "image/jpeg"),
        )
        assertEquals(
            setOf(OverwriteHazard.MOTION_PHOTO),
            overwriteHazards(jpegWithXmp("<rdf:Description GCamera:MicroVideo=\"1\"/>"), ByteArray(0), "image/jpeg"),
        )
        assertEquals(
            setOf(OverwriteHazard.MOTION_PHOTO),
            overwriteHazards(jpegWithXmp("<Container:Item Item:Semantic=\"MotionPhoto\"/>"), ByteArray(0), "image/jpeg"),
        )
    }

    @Test
    fun `a trailing MP4 box or Samsung's trailer tag in the tail is recognised`() {
        val head = jpegWithXmp("<x:xmpmeta/>")
        val video = byteArrayOf(0, 0, 0, 0x18) + ascii("ftypmp42") + ByteArray(32)
        assertEquals(setOf(OverwriteHazard.MOTION_PHOTO), overwriteHazards(head, ByteArray(100) + video, "image/jpeg"))
        assertEquals(
            setOf(OverwriteHazard.MOTION_PHOTO),
            overwriteHazards(head, ByteArray(100) + ascii("MotionPhoto_Data") + ByteArray(8), "image/jpeg"),
        )
    }

    @Test
    fun `a file shorter than the head window is checked for trailer markers in the head`() {
        val small = jpegWithXmp("<x:xmpmeta/>") + ascii("MotionPhoto_Data")
        assertEquals(setOf(OverwriteHazard.MOTION_PHOTO), overwriteHazards(small, ByteArray(0), "image/jpeg"))
    }

    @Test
    fun `an ordinary JPEG, a still PNG and an unknown type are not hazards`() {
        val plain = jpegWithXmp("<x:xmpmeta><rdf:Description dc:title=\"Beach\"/></x:xmpmeta>")
        assertTrue(overwriteHazards(plain, ByteArray(200), "image/jpeg").isEmpty())
        assertTrue(overwriteHazards(ByteArray(64), ByteArray(64), "application/octet-stream").isEmpty())
    }

    @Test
    fun `the JPEG markers do not fire for other formats`() {
        // A WebP that merely mentions the string must not be refused as a Motion Photo.
        assertTrue(overwriteHazards(webp(0x10) + ascii("GCamera:MotionPhoto"), ByteArray(0), "image/webp").isEmpty())
    }

    // ---- windows ----

    @Test
    fun `head and tail windows come back exact for a long stream`() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val (head, tail) = ByteArrayInputStream(data).readHeadAndTail(headBytes = 100, tailBytes = 50)
        assertArrayEquals(data.copyOfRange(0, 100), head)
        assertArrayEquals(data.copyOfRange(950, 1000), tail)
    }

    @Test
    fun `a stream shorter than head plus tail gives a tail that does not overlap the head`() {
        val data = ByteArray(120) { it.toByte() }
        val (head, tail) = ByteArrayInputStream(data).readHeadAndTail(headBytes = 100, tailBytes = 50)
        assertArrayEquals(data.copyOfRange(0, 100), head)
        assertArrayEquals(data.copyOfRange(100, 120), tail)
    }

    @Test
    fun `a stream shorter than the head window has no tail`() {
        val data = ByteArray(30) { it.toByte() }
        val (head, tail) = ByteArrayInputStream(data).readHeadAndTail(headBytes = 100, tailBytes = 50)
        assertArrayEquals(data, head)
        assertEquals(0, tail.size)
    }

    // ---- refusals ----

    @Test
    fun `only a complete metadata copy lets an overwrite go ahead`() {
        assertNull(overwriteMetadataRefusal(MetadataCopy.COPIED))
        assertNull(overwriteMetadataRefusal(MetadataCopy.SKIPPED_HEIC))
        for (incomplete in listOf(MetadataCopy.REDACTED, MetadataCopy.SOURCE_UNREADABLE, MetadataCopy.FAILED)) {
            val refusal = overwriteMetadataRefusal(incomplete)!!
            assertEquals(OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST, refusal.reason)
        }
    }

    @Test
    fun `the redacted refusal points at the exact fix`() {
        val message = overwriteMetadataRefusal(MetadataCopy.REDACTED)!!.message!!
        assertTrue(message, "photo-location access" in message)
        assertTrue(message, "save a copy" in message)
    }

    @Test
    fun `hazards read as a plain list in the refusal message`() {
        assertEquals("animation", setOf(OverwriteHazard.ANIMATION).describe())
        assertEquals(
            "HDR gain map and motion video",
            setOf(OverwriteHazard.MOTION_PHOTO, OverwriteHazard.ULTRA_HDR).describe(),
        )
        assertTrue("animation" in setOf(OverwriteHazard.ANIMATION).refusalMessage())
    }

    @Test
    fun `the saved-copy note speaks only when something was missed`() {
        assertEquals("", MetadataCopy.COPIED.savedNote())
        assertEquals("", MetadataCopy.SKIPPED_HEIC.savedNote())
        assertTrue("location" in MetadataCopy.REDACTED.savedNote())
        assertTrue(MetadataCopy.FAILED.savedNote().isNotEmpty())
        assertTrue(MetadataCopy.SOURCE_UNREADABLE.savedNote().isNotEmpty())
    }
}
