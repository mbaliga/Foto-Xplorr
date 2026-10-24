package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The hazards the first guard missed: transparency and lossless WebP that a re-encode destroys, and
 *  the generic markers for extra images in a JPEG. SDK versions are passed explicitly: the answer
 *  for WebP depends on the platform encoder. */
class OverwriteHazardsExtendedTest {

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun chunk(type: String, data: ByteArray) = ascii(type) + le32(data.size) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ascii("WEBP") + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return ascii("RIFF") + le32(body.size) + body
    }

    /** VP8X: flags byte then 3 reserved, 3 width-1, 3 height-1. */
    private fun vp8x(flags: Int) = chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0, 7, 0, 0, 7, 0, 0))

    /** VP8L header: signature 0x2F then 14+14 bits of size, alpha bit, version. */
    private fun vp8l(alpha: Boolean) =
        chunk("VP8L", byteArrayOf(0x2F, 7, 0xC0.toByte(), 1, if (alpha) 0x10 else 0x00))

    private fun vp8() = chunk("VP8 ", ByteArray(10))

    private fun jpegWithXmp(xmp: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x01, 0x00) +
            ascii("http://ns.adobe.com/xap/1.0/\u0000$xmp") + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun hazards(head: ByteArray, mime: String, sdk: Int = 34, tail: ByteArray = ByteArray(0)) =
        overwriteHazards(head, tail, mime, sdk)

    // ---- transparency ----

    @Test
    fun `a WebP that declares alpha is a transparency hazard where the encoder is lossy-only`() {
        val withAlpha = riff(vp8x(0x10), vp8())
        assertEquals(setOf(OverwriteHazard.TRANSPARENCY), hazards(withAlpha, "image/webp", sdk = 34))
        assertEquals(setOf(OverwriteHazard.TRANSPARENCY), hazards(withAlpha, "image/webp", sdk = 30))
    }

    @Test
    fun `before API 30 the WebP encoder keeps alpha so it is not a hazard`() {
        assertTrue(hazards(riff(vp8x(0x10), vp8()), "image/webp", sdk = 29).isEmpty())
    }

    @Test
    fun `an opaque lossy WebP is not a hazard`() {
        assertTrue(hazards(riff(vp8x(0x00), vp8()), "image/webp").isEmpty())
        assertTrue(hazards(riff(vp8()), "image/webp").isEmpty())
    }

    @Test
    fun `a PNG with alpha is not a hazard because PNG is re-encoded as PNG`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            byteArrayOf(0, 0, 0, 13) + ascii("IHDR") + byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0) + ByteArray(4)
        assertTrue(hazards(png, "image/png").isEmpty())
    }

    // ---- lossless ----

    @Test
    fun `a lossless WebP is a hazard whether or not it is wrapped in VP8X`() {
        assertEquals(setOf(OverwriteHazard.LOSSLESS), hazards(riff(vp8l(alpha = false)), "image/webp"))
        assertEquals(setOf(OverwriteHazard.LOSSLESS), hazards(riff(vp8x(0x00), vp8l(alpha = false)), "image/webp"))
    }

    @Test
    fun `a lossless WebP with alpha is both`() {
        assertEquals(
            setOf(OverwriteHazard.TRANSPARENCY, OverwriteHazard.LOSSLESS),
            hazards(riff(vp8l(alpha = true)), "image/webp"),
        )
    }

    @Test
    fun `the message names what would be destroyed`() {
        assertEquals("transparency and lossless quality", setOf(OverwriteHazard.TRANSPARENCY, OverwriteHazard.LOSSLESS).describe())
    }

    // ---- extra images in a JPEG ----

    /** An APP2 MPF block: `MPF\0`, a TIFF header, one IFD with the NumberOfImages entry. */
    private fun mpf(images: Int, little: Boolean = true): ByteArray {
        fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (little) le32(v) else le32(v).reversedArray()
        val tiff = ascii(if (little) "II" else "MM") + u16(42) + u32(8) +
            u16(1) + u16(0xB001) + u16(4) + u32(1) + u32(images) + u32(0)
        val payload = ascii("MPF\u0000") + tiff
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE2.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    @Test
    fun `an MPF block announcing more than one image is a hazard in either byte order`() {
        assertEquals(setOf(OverwriteHazard.EXTRA_IMAGES), hazards(mpf(2), "image/jpeg"))
        assertEquals(setOf(OverwriteHazard.EXTRA_IMAGES), hazards(mpf(3, little = false), "image/jpeg"))
    }

    @Test
    fun `an MPF block with a single image is not`() {
        assertTrue(hazards(mpf(1), "image/jpeg").isEmpty())
    }

    @Test
    fun `an MPF block does not add a second hazard to a file already known to be Ultra HDR`() {
        val hdr = mpf(2) + ascii("hdrgm:Version")
        assertEquals(setOf(OverwriteHazard.ULTRA_HDR), hazards(hdr, "image/jpeg"))
    }

    @Test
    fun `a container item that is not the primary image is a hazard`() {
        val depth = "<Container:Item Item:Semantic=\"Depth\" Item:Mime=\"image/jpeg\"/>"
        assertEquals(setOf(OverwriteHazard.EXTRA_IMAGES), hazards(jpegWithXmp(depth), "image/jpeg"))
    }

    @Test
    fun `single quotes and the primary item are read correctly`() {
        assertTrue(hazards(jpegWithXmp("<Container:Item Item:Semantic=\"Primary\"/>"), "image/jpeg").isEmpty())
        assertEquals(
            setOf(OverwriteHazard.ULTRA_HDR),
            hazards(jpegWithXmp("<Container:Item Item:Semantic=\"Primary\"/><Container:Item Item:Semantic='GainMap'/>"), "image/jpeg"),
        )
    }

    @Test
    fun `Apple's auxiliary gain map URN is Ultra HDR`() {
        assertEquals(
            setOf(OverwriteHazard.ULTRA_HDR),
            hazards(jpegWithXmp("<rdf:Description aux:Type=\"urn:com:apple:photo:2020:aux:hdrgainmap\"/>"), "image/jpeg"),
        )
    }

    @Test
    fun `a Google depth map is a hazard`() {
        assertEquals(
            setOf(OverwriteHazard.EXTRA_IMAGES),
            hazards(jpegWithXmp("<rdf:Description xmlns:GDepth=\"http://ns.google.com/photos/1.0/depthmap/\" GDepth:Format=\"RangeInverse\"/>"), "image/jpeg"),
        )
    }

    @Test
    fun `a plain JPEG has none of these`() {
        assertTrue(hazards(jpegWithXmp("<rdf:Description dc:title=\"x\"/>"), "image/jpeg").isEmpty())
    }

    // ---- the window reader ----

    @Test
    fun `asking for no tail reads only the head`() {
        val data = ByteArray(1000) { it.toByte() }
        val (head, tail) = ByteArrayInputStream(data).readHeadAndTail(headBytes = 100, tailBytes = 0)
        assertEquals(100, head.size)
        assertEquals(0, tail.size)
    }
}
