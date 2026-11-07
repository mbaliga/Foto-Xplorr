package com.fotoxplorr.core.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AlphaSniffer.sniff] decides from a file's own bytes whether it declares transparency. Every
 * case is a hand-built, minimal container: only the framing the sniffer actually reads. Test names
 * avoid commas because Kotlin/Native rejects them.
 */
class AlphaSnifferTest {

    // ---- MIME-only answers ----

    @Test
    fun `an unrecognised mime type is unknown`() {
        assertNull(AlphaSniffer.sniff(ByteArray(0), "image/tiff"))
        assertNull(AlphaSniffer.sniff(ByteArray(0), ""))
    }

    @Test
    fun `jpeg and bmp never have alpha`() {
        assertEquals(false, AlphaSniffer.sniff(ByteArray(0), "image/jpeg"))
        assertEquals(false, AlphaSniffer.sniff(ByteArray(0), "image/bmp"))
    }

    @Test
    fun `ico and svg are transparent without reading bytes`() {
        assertEquals(true, AlphaSniffer.sniff(ByteArray(0), "image/x-icon"))
        assertEquals(true, AlphaSniffer.sniff(ByteArray(0), "image/vnd.microsoft.icon"))
        assertEquals(true, AlphaSniffer.sniff(ByteArray(0), "image/svg+xml"))
    }

    @Test
    fun `mime matching ignores case and parameters`() {
        assertEquals(true, AlphaSniffer.sniff(png(colourType = 6), "IMAGE/PNG; charset=binary"))
    }

    // ---- PNG ----

    @Test
    fun `png rgba is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(png(colourType = 6), "image/png"))
    }

    @Test
    fun `png grey alpha is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(png(colourType = 4), "image/png"))
    }

    @Test
    fun `png truecolour without trns is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(png(colourType = 2), "image/png"))
    }

    @Test
    fun `png palette with trns before idat is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(png(colourType = 3, trns = true), "image/png"))
    }

    @Test
    fun `png palette without trns is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(png(colourType = 3), "image/png"))
    }

    @Test
    fun `png truecolour with trns is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(png(colourType = 2, trns = true), "image/png"))
    }

    @Test
    fun `png whose prefix ends before idat is unknown`() {
        val head = png(colourType = 2)
        // Cut inside the IDAT chunk header region: signature + IHDR (25) + CRC leaves no IDAT.
        assertNull(AlphaSniffer.sniff(head.copyOfRange(0, 8 + 8 + 13 + 4), "image/png"))
    }

    @Test
    fun `png with a wrong signature is unknown`() {
        assertNull(AlphaSniffer.sniff("NOTAPNGATALL1234".encodeToByteArray(), "image/png"))
    }

    // ---- WebP ----

    @Test
    fun `webp extended with the alpha flag is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(webpVp8x(flags = 0x10), "image/webp"))
    }

    @Test
    fun `webp extended without the alpha flag is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(webpVp8x(flags = 0x02), "image/webp"))
    }

    @Test
    fun `webp lossless with alpha is used bit set is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(webpVp8l(alphaUsed = true), "image/webp"))
    }

    @Test
    fun `webp lossless without alpha is used bit is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(webpVp8l(alphaUsed = false), "image/webp"))
    }

    @Test
    fun `webp lossy simple is opaque`() {
        val head = riff("VP8 ", ByteArray(10))
        assertEquals(false, AlphaSniffer.sniff(head, "image/webp"))
    }

    @Test
    fun `webp truncated before its flags is unknown`() {
        assertNull(AlphaSniffer.sniff(webpVp8x(flags = 0x10).copyOfRange(0, 18), "image/webp"))
    }

    @Test
    fun `webp with a wrong signature is unknown`() {
        assertNull(AlphaSniffer.sniff(ByteArray(40), "image/webp"))
    }

    // ---- GIF ----

    @Test
    fun `gif with a transparent graphic control is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(gif(transparent = true), "image/gif"))
    }

    @Test
    fun `gif with an opaque graphic control that reaches its trailer is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(gif(transparent = false), "image/gif"))
    }

    @Test
    fun `gif with no graphic control at all is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(gif(transparent = null), "image/gif"))
    }

    @Test
    fun `gif cut off before the trailer without any transparency is unknown`() {
        assertNull(AlphaSniffer.sniff(gif(transparent = false, trailer = false), "image/gif"))
    }

    @Test
    fun `gif with a wrong signature is unknown`() {
        assertNull(AlphaSniffer.sniff("NOTAGIFATALL1234".encodeToByteArray(), "image/gif"))
    }

    // ---- AVIF ----

    @Test
    fun `avif with an alpha auxc is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(avif(ALPHA_URN), "image/avif"))
    }

    @Test
    fun `avif with the hevc style alpha urn is transparent`() {
        assertEquals(true, AlphaSniffer.sniff(avif("urn:mpeg:hevc:2015:auxid:1"), "image/avif"))
    }

    @Test
    fun `avif whose only auxc is a depth map is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(avif("urn:mpeg:mpegB:cicp:systems:auxiliary:depth"), "image/avif"))
    }

    @Test
    fun `avif without any auxc is opaque`() {
        assertEquals(false, AlphaSniffer.sniff(avif(null), "image/avif"))
    }

    @Test
    fun `avif cut off inside the meta box is unknown`() {
        // The ipma box (16 bytes) trails ipco, so 30 bytes off the end lands inside the auxC.
        val whole = avif("urn:mpeg:mpegB:cicp:systems:auxiliary:depth")
        assertNull(AlphaSniffer.sniff(whole.copyOfRange(0, whole.size - 30), "image/avif"))
    }

    @Test
    fun `avif cut off after the alpha auxc is still transparent`() {
        val whole = avif(ALPHA_URN)
        assertEquals(true, AlphaSniffer.sniff(whole.copyOfRange(0, whole.size - 10), "image/avif"))
    }

    @Test
    fun `avif without a meta box in the prefix is unknown`() {
        assertNull(AlphaSniffer.sniff(box("ftyp", "avif".encodeToByteArray() + ByteArray(8)), "image/avif"))
    }

    // ---- limits ----

    @Test
    fun `only the first 64 KiB is read`() {
        // A GIF whose transparent graphic control sits past the 64 KiB mark: with the cap in force
        // the walk runs out of bytes first, so the answer is unknown rather than true.
        val padding = ByteArray(AlphaSniffer.MAX_READ_BYTES)
        val head = gifHeader() + commentExtension(padding) + graphicControl(transparent = true) + byteArrayOf(0x3B)
        assertNull(AlphaSniffer.sniff(head, "image/gif"))
    }

    // ---- builders ----

    private fun png(colourType: Int, trns: Boolean = false): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = ByteArray(13)
        ihdr[3] = 1 // width 1
        ihdr[7] = 1 // height 1
        ihdr[8] = 8 // bit depth
        ihdr[9] = colourType.toByte()
        var out = signature + pngChunk("IHDR", ihdr)
        if (colourType == 3) out += pngChunk("PLTE", ByteArray(3))
        if (trns) out += pngChunk("tRNS", ByteArray(if (colourType == 2) 6 else 1))
        return out + pngChunk("IDAT", ByteArray(4)) + pngChunk("IEND", ByteArray(0))
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray =
        int32(data.size) + type.encodeToByteArray() + data + ByteArray(4) // CRC is never checked

    private fun webpVp8x(flags: Int): ByteArray {
        val data = ByteArray(10)
        data[0] = flags.toByte()
        return riff("VP8X", data)
    }

    private fun webpVp8l(alphaUsed: Boolean): ByteArray {
        val data = ByteArray(10)
        data[0] = 0x2F
        if (alphaUsed) data[4] = 0x10
        return riff("VP8L", data)
    }

    private fun riff(chunkType: String, data: ByteArray): ByteArray {
        val chunk = chunkType.encodeToByteArray() + int32le(data.size) + data
        return "RIFF".encodeToByteArray() + int32le(4 + chunk.size) + "WEBP".encodeToByteArray() + chunk
    }

    private fun gifHeader(): ByteArray =
        // "GIF89a", width 1, height 1, packed 0 (no global table), background 0, aspect 0.
        "GIF89a".encodeToByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0)

    private fun graphicControl(transparent: Boolean): ByteArray =
        byteArrayOf(0x21, 0xF9.toByte(), 4, if (transparent) 1 else 0, 0, 0, 0, 0)

    private fun commentExtension(payload: ByteArray): ByteArray {
        var out = byteArrayOf(0x21, 0xFE.toByte())
        var offset = 0
        while (offset < payload.size) {
            val n = minOf(255, payload.size - offset)
            out += byteArrayOf(n.toByte()) + payload.copyOfRange(offset, offset + n)
            offset += n
        }
        return out + byteArrayOf(0)
    }

    /** [transparent] null means no graphic control extension at all. */
    private fun gif(transparent: Boolean?, trailer: Boolean = true): ByteArray {
        var out = gifHeader()
        if (transparent != null) out += graphicControl(transparent)
        // One 1x1 image: separator, left, top, width, height, packed 0, LZW min code size, one data
        // sub-block of 2 bytes, terminator.
        out += byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0)
        if (trailer) out += byteArrayOf(0x3B)
        return out
    }

    /** [auxType] null builds an ipco with only an unrelated property. */
    private fun avif(auxType: String?): ByteArray {
        val ispe = box("ispe", ByteArray(12))
        val properties = if (auxType == null) {
            ispe
        } else {
            ispe + box("auxC", ByteArray(4) + auxType.encodeToByteArray() + byteArrayOf(0))
        }
        val iprp = box("iprp", box("ipco", properties) + box("ipma", ByteArray(8)))
        val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(24)) + iprp)
        return box("ftyp", "avif".encodeToByteArray() + ByteArray(4) + "avif".encodeToByteArray()) + meta
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        int32(8 + payload.size) + type.encodeToByteArray() + payload

    private fun int32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun int32le(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private companion object {
        const val ALPHA_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"
    }
}
