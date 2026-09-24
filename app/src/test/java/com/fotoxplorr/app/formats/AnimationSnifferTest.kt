package com.fotoxplorr.app.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P0-14: [AnimationSniffer.sniff] is the one place a file's own bytes -- not its MIME type --
 * decide whether it animates. Every case here is a hand-built, minimal-but-structurally-real
 * container: no full image data, only the header/chunk framing the sniffer actually reads.
 */
class AnimationSnifferTest {

    @Test
    fun `an unsupported mime type is unknown, not a confident false`() {
        assertNull(AnimationSniffer.sniff(ByteArray(0), "image/jpeg"))
    }

    // ---- GIF ----

    @Test
    fun `a single-frame gif that reaches its own trailer is not animated`() {
        assertEquals(false, AnimationSniffer.sniff(gif(frames = 1), "image/gif"))
    }

    @Test
    fun `a two-frame gif is animated`() {
        assertEquals(true, AnimationSniffer.sniff(gif(frames = 2), "image/gif"))
    }

    @Test
    fun `a many-frame gif is animated`() {
        assertEquals(true, AnimationSniffer.sniff(gif(frames = 12), "image/gif"))
    }

    @Test
    fun `a gif that runs out of buffer before a second frame or the trailer is conservatively animated`() {
        // Real file may have more after this -- the caller only ever hands over a bounded prefix.
        assertEquals(true, AnimationSniffer.sniff(gif(frames = 1, includeTrailer = false), "image/gif"))
    }

    @Test
    fun `a gif with an extension block before its single frame is still read correctly`() {
        assertEquals(false, AnimationSniffer.sniff(gif(frames = 1, leadingExtension = true), "image/gif"))
    }

    @Test
    fun `gif magic bytes that do not match GIF87a or GIF89a are treated conservatively`() {
        val bogus = "NOTAGIF".toByteArray(Charsets.US_ASCII)
        assertEquals(true, AnimationSniffer.sniff(bogus, "image/gif"))
    }

    // ---- WebP ----

    @Test
    fun `simple webp with no VP8X chunk at all cannot be animated`() {
        assertEquals(false, AnimationSniffer.sniff(webpSimple(), "image/webp"))
    }

    @Test
    fun `extended webp with VP8X present but the animation flag clear is not animated`() {
        assertEquals(false, AnimationSniffer.sniff(webpExtended(animated = false), "image/webp"))
    }

    @Test
    fun `extended webp with VP8X and the animation flag set is animated`() {
        assertEquals(true, AnimationSniffer.sniff(webpExtended(animated = true), "image/webp"))
    }

    // ---- PNG / APNG ----

    @Test
    fun `a plain PNG with only IDAT and no acTL is not animated`() {
        assertEquals(false, AnimationSniffer.sniff(png("IHDR", "IDAT"), "image/png"))
    }

    @Test
    fun `an APNG whose acTL comes before the first IDAT is animated`() {
        assertEquals(true, AnimationSniffer.sniff(png("IHDR", "acTL", "fcTL", "IDAT"), "image/png"))
    }

    @Test
    fun `a png missing its own signature is not animated`() {
        assertEquals(false, AnimationSniffer.sniff(ByteArray(20), "image/png"))
    }

    // ---- HEIF / HEIC / AVIF ----

    @Test
    fun `a plain still HEIC major brand is not animated`() {
        assertEquals(false, AnimationSniffer.sniff(isoBmff(majorBrand = "heic"), "image/heic"))
    }

    @Test
    fun `an avis major brand is an animated AVIF image sequence`() {
        assertEquals(true, AnimationSniffer.sniff(isoBmff(majorBrand = "avis"), "image/avif"))
    }

    @Test
    fun `an msf1 compatible brand marks an animated HEIF sequence even with an ordinary major brand`() {
        assertEquals(
            true,
            AnimationSniffer.sniff(isoBmff(majorBrand = "mif1", compatibleBrands = listOf("heic", "msf1")), "image/heif"),
        )
    }

    @Test
    fun `compatible brands with no animated marker leave a still image not animated`() {
        assertEquals(
            false,
            AnimationSniffer.sniff(isoBmff(majorBrand = "heic", compatibleBrands = listOf("heix", "mif1")), "image/heic"),
        )
    }

    // ---- fixtures ----

    private fun gif(frames: Int, includeTrailer: Boolean = true, leadingExtension: Boolean = false): ByteArray {
        val out = mutableListOf<Byte>()
        fun ascii(s: String) = s.forEach { out += it.code.toByte() }
        fun u16(v: Int) {
            out += (v and 0xFF).toByte()
            out += ((v shr 8) and 0xFF).toByte()
        }
        ascii("GIF89a")
        u16(1); u16(1) // logical screen width/height
        out += 0x00 // packed: no global color table
        out += 0x00 // background color index
        out += 0x00 // pixel aspect ratio
        if (leadingExtension) {
            out += 0x21 // extension introducer
            out += 0xF9.toByte() // graphic control label
            out += 0x04 // block size
            repeat(4) { out += 0x00 }
            out += 0x00 // sub-block terminator
        }
        repeat(frames) {
            out += 0x2C // image separator
            u16(0); u16(0) // left, top
            u16(1); u16(1) // width, height
            out += 0x00 // packed: no local color table
            out += 0x02 // LZW minimum code size
            out += 0x01 // one sub-block, 1 byte
            out += 0x00 // that byte (content irrelevant -- never decompressed)
            out += 0x00 // sub-block terminator
        }
        if (includeTrailer) out += 0x3B
        return out.toByteArray()
    }

    private fun webpSimple(): ByteArray {
        val out = mutableListOf<Byte>()
        fun ascii(s: String) = s.forEach { out += it.code.toByte() }
        fun u32le(v: Int) {
            out += (v and 0xFF).toByte()
            out += ((v shr 8) and 0xFF).toByte()
            out += ((v shr 16) and 0xFF).toByte()
            out += ((v shr 24) and 0xFF).toByte()
        }
        ascii("RIFF"); u32le(20); ascii("WEBP")
        ascii("VP8 "); u32le(4); repeat(4) { out += 0x00 }
        return out.toByteArray()
    }

    private fun webpExtended(animated: Boolean): ByteArray {
        val out = mutableListOf<Byte>()
        fun ascii(s: String) = s.forEach { out += it.code.toByte() }
        fun u32le(v: Int) {
            out += (v and 0xFF).toByte()
            out += ((v shr 8) and 0xFF).toByte()
            out += ((v shr 16) and 0xFF).toByte()
            out += ((v shr 24) and 0xFF).toByte()
        }
        ascii("RIFF"); u32le(30); ascii("WEBP")
        ascii("VP8X"); u32le(10)
        out += (if (animated) 0x02 else 0x00).toByte()
        repeat(9) { out += 0x00 } // reserved(3) + width-1(3) + height-1(3)
        return out.toByteArray()
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(vararg chunkTypes: String): ByteArray {
        val out = mutableListOf<Byte>()
        out += pngSignature.toList()
        fun u32be(v: Int) {
            out += ((v shr 24) and 0xFF).toByte()
            out += ((v shr 16) and 0xFF).toByte()
            out += ((v shr 8) and 0xFF).toByte()
            out += (v and 0xFF).toByte()
        }
        chunkTypes.forEach { type ->
            u32be(0) // no chunk data needed -- the sniffer only reads type + length
            type.forEach { out += it.code.toByte() }
            u32be(0) // fake CRC, never checked
        }
        return out.toByteArray()
    }

    private fun isoBmff(majorBrand: String, compatibleBrands: List<String> = emptyList()): ByteArray {
        val out = mutableListOf<Byte>()
        fun ascii(s: String) = s.forEach { out += it.code.toByte() }
        fun u32be(v: Int) {
            out += ((v shr 24) and 0xFF).toByte()
            out += ((v shr 16) and 0xFF).toByte()
            out += ((v shr 8) and 0xFF).toByte()
            out += (v and 0xFF).toByte()
        }
        val boxSize = 16 + compatibleBrands.size * 4
        u32be(boxSize)
        ascii("ftyp")
        ascii(majorBrand)
        u32be(0) // minor version
        compatibleBrands.forEach { ascii(it) }
        return out.toByteArray()
    }
}
