package com.fotoxplorr.core.formats.simple

import com.fotoxplorr.core.formats.DecoderRegistry
import com.fotoxplorr.core.formats.DecoderVerdict
import com.fotoxplorr.core.formats.FormatCopy
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.SizeHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimpleDecodersTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun le16(v: Int) = bytes(v and 0xFF, v shr 8)
    private fun le32(v: Long) = bytes((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())
    private fun be32(v: Long) = le32(v).reversedArray()
    private fun be16(v: Int) = le16(v).reversedArray()
    private fun text(s: String) = ascii(s)

    private fun ok(format: MediaFormat, data: ByteArray, limits: SimpleLimits = SimpleLimits()): DecodedImage {
        val r = SimpleFormatDecoders.decode(format, data, limits)
        assertIs<SimpleResult.Success>(r, (r as? SimpleResult.Failure)?.reason)
        return r.image
    }

    private fun refused(format: MediaFormat, data: ByteArray, limits: SimpleLimits = SimpleLimits()) =
        assertIs<SimpleResult.Failure>(SimpleFormatDecoders.decode(format, data, limits))

    // ---- PCX
    private fun pcxHeader(bits: Int, planes: Int, w: Int, h: Int, bpl: Int): ByteArray {
        val hd = ByteArray(128)
        hd[0] = 10; hd[2] = 1; hd[3] = bits.toByte()
        le16(w - 1).copyInto(hd, 8); le16(h - 1).copyInto(hd, 10)
        hd[65] = planes.toByte(); le16(bpl).copyInto(hd, 66)
        return hd
    }

    @Test fun `pcx 8-bit palette with RLE and 24-bit planar`() {
        val pal = ByteArray(768); pal[3] = 10; pal[4] = 20; pal[5] = 30 // index 1
        // 2x1, bpl 2: index 1, index 0 -> raw bytes 1,0
        val img = ok(MediaFormat.Pcx, pcxHeader(8, 1, 2, 1, 2) + bytes(1, 0) + bytes(0x0C) + pal)
        assertEquals(0xFF0A141E.toInt(), img.argb[0])
        assertEquals(0xFF000000.toInt(), img.argb[1])
        // RLE: run of 2 x value 1 (0xC2, 1)
        val rle = ok(MediaFormat.Pcx, pcxHeader(8, 1, 2, 1, 2) + bytes(0xC2, 1) + bytes(0x0C) + pal)
        assertEquals(0xFF0A141E.toInt(), rle.argb[1])
        // 24-bit: planes R,G,B each bpl 2, 2x1
        val rgb = ok(MediaFormat.Pcx, pcxHeader(8, 3, 2, 1, 2) + bytes(1, 2, 3, 4, 5, 6))
        assertEquals(0xFF010305.toInt(), rgb.argb[0])
        assertEquals(0xFF020406.toInt(), rgb.argb[1])
    }

    @Test fun `pcx hostile dimensions and truncation are refused`() {
        refused(MediaFormat.Pcx, pcxHeader(8, 3, 60000, 60000, 60000))
        refused(MediaFormat.Pcx, pcxHeader(8, 3, 4, 4, 4) + bytes(1, 2, 3))
        refused(MediaFormat.Pcx, pcxHeader(4, 1, 4, 4, 2) + ByteArray(40))
    }

    // ---- DDS
    private fun ddsHeader(w: Int, h: Int, flags: Int, bits: Int, r: Long, g: Long, b: Long, a: Long, fourCc: String = ""): ByteArray {
        val hd = ByteArray(128)
        text("DDS ").copyInto(hd); le32(124).copyInto(hd, 4)
        le32(h.toLong()).copyInto(hd, 12); le32(w.toLong()).copyInto(hd, 16)
        le32(32).copyInto(hd, 76); le32(flags.toLong()).copyInto(hd, 80)
        if (fourCc.isNotEmpty()) text(fourCc).copyInto(hd, 84)
        le32(bits.toLong()).copyInto(hd, 88)
        le32(r).copyInto(hd, 92); le32(g).copyInto(hd, 96); le32(b).copyInto(hd, 100); le32(a).copyInto(hd, 104)
        return hd
    }

    @Test fun `dds bgra8 rgb565 luminance and alpha only`() {
        val bgra = ok(MediaFormat.Dds, ddsHeader(1, 1, 0x41, 32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000) + bytes(30, 20, 10, 128))
        assertEquals(0x800A141E.toInt(), bgra.argb[0])
        // 565: pure green is 0x07E0
        val g565 = ok(MediaFormat.Dds, ddsHeader(1, 1, 0x40, 16, 0xF800, 0x7E0, 0x1F, 0) + le16(0x07E0))
        assertEquals(0xFF00FF00.toInt(), g565.argb[0])
        val lum = ok(MediaFormat.Dds, ddsHeader(1, 1, 0x20, 8, 0xFF, 0, 0, 0) + bytes(77))
        assertEquals(0xFF4D4D4D.toInt(), lum.argb[0])
        val alpha = ok(MediaFormat.Dds, ddsHeader(1, 1, 0x2, 8, 0, 0, 0, 0xFF) + bytes(0))
        assertEquals(0x00FFFFFF, alpha.argb[0])
    }

    @Test fun `dds compressed and short files are refused`() {
        refused(MediaFormat.Dds, ddsHeader(4, 4, 0x4, 0, 0, 0, 0, 0, "DXT1") + ByteArray(8))
        refused(MediaFormat.Dds, ddsHeader(100, 100, 0x40, 32, 0xFF0000, 0xFF00, 0xFF, 0) + ByteArray(16))
        refused(MediaFormat.Dds, ddsHeader(100000, 100000, 0x40, 32, 0xFF0000, 0xFF00, 0xFF, 0))
    }

    // ---- TGA
    @Test fun `tga greyscale 8-bit top-left origin and colour-mapped`() {
        val hdr = bytes(0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) + le16(2) + le16(1) + bytes(8, 0x20)
        val img = ok(MediaFormat.Tga, hdr + bytes(10, 200))
        assertEquals(0xFF0A0A0A.toInt(), img.argb[0])
        assertEquals(0xFFC8C8C8.toInt(), img.argb[1])
        // colour-mapped: 2 entries 24-bit, indices 1,0
        val cm = bytes(0, 1, 1) + le16(0) + le16(2) + bytes(24, 0, 0, 0, 0) + le16(2) + le16(1) + bytes(8, 0x20)
        val img2 = ok(MediaFormat.Tga, cm + bytes(1, 2, 3, 40, 50, 60) + bytes(1, 0))
        assertEquals(0xFF3C3228.toInt(), img2.argb[0]) // entry 1 = B 40, G 50, R 60 -> r=60 g=50 b=40
        assertEquals(0xFF030201.toInt(), img2.argb[1])
    }

    @Test fun `tga rle that runs past the image or the file is refused`() {
        val hdr = bytes(0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0) + le16(4) + le16(4) + bytes(24, 0x20)
        refused(MediaFormat.Tga, hdr + bytes(0x00, 1, 2, 3)) // one pixel of sixteen, then EOF
        refused(MediaFormat.Tga, bytes(0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF, 24, 0)) // 65535 x 65535
        refused(MediaFormat.Tga, ByteArray(10))
    }

    // ---- QOI / PNM / HDR / PSD / ICNS edge cases
    @Test fun `qoi with huge declared size is refused before allocating`() {
        refused(MediaFormat.Qoi, text("qoif") + be32(0x7FFFFFFF) + be32(0x7FFFFFFF) + bytes(3, 0) + ByteArray(16))
        refused(MediaFormat.Qoi, text("qoif") + be32(100) + be32(100) + bytes(3, 0) + bytes(0xFE, 1, 2, 3) + ByteArray(8))
    }

    @Test fun `qoi rgba index diff and luma ops`() {
        val data = text("qoif") + be32(4) + be32(1) + bytes(4, 0) +
            bytes(0xFF, 100, 100, 100, 255) + // RGBA
            bytes(0x40 or (3 shl 4) or (2 shl 2) or 1) + // DIFF +1 0 -1
            bytes(0x80 or (32 + 2), 0x00 or 0x00) + // LUMA dg=+2, dr-dg=-8, db-dg=-8
            bytes(0xC0) + // RUN 1 (two pixels total)
            ByteArray(7) + bytes(1)
        // pixels: p0, p1, p2 and then a run needs 1 more but image has 4: p0,p1,p2,p2
        val img = ok(MediaFormat.Qoi, data)
        assertEquals(0xFF646464.toInt(), img.argb[0])
        assertEquals(0xFF656463.toInt(), img.argb[1])
        assertEquals(argb(255, 101 + 2 - 8, 100 + 2, 99 + 2 - 8), img.argb[2])
        assertEquals(img.argb[2], img.argb[3])
    }

    @Test fun `pnm comments maxval scaling and truncation`() {
        val img = ok(MediaFormat.Pnm, text("P3\n# a comment\n2 1\n15\n15 0 0  0 15 0\n"))
        assertEquals(0xFFFF0000.toInt(), img.argb[0])
        assertEquals(0xFF00FF00.toInt(), img.argb[1])
        val sixteen = ok(MediaFormat.Pnm, text("P5\n1 1\n65535\n") + bytes(0x80, 0x00))
        assertEquals(0xFF808080.toInt(), sixteen.argb[0].toInt() or 0) // 0x8000 / 65535 -> 128
        refused(MediaFormat.Pnm, text("P6\n1000 1000\n255\n") + ByteArray(10))
        refused(MediaFormat.Pnm, text("P6\n99999999 99999999\n255\n"))
        refused(MediaFormat.Pnm, text("P2\n2 2\n255\n1 2 3\n"))
        refused(MediaFormat.Pnm, text("P6\n-3 4\n255\n"))
    }

    @Test fun `hdr rle scanline`() {
        // 8 wide, 1 high; new-style RLE: each component one run of 8.
        val scan = bytes(2, 2, 0, 8) + bytes(128 + 8, 128) + bytes(128 + 8, 0) + bytes(128 + 8, 0) + bytes(128 + 8, 129)
        val img = ok(MediaFormat.Hdr, text("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n") + scan)
        assertEquals(0xFFFF0000.toInt(), img.argb[0])
        assertEquals(0xFFFF0000.toInt(), img.argb[7])
        refused(MediaFormat.Hdr, text("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n") + bytes(2, 2, 0, 8, 200, 1))
        refused(MediaFormat.Hdr, text("#?RADIANCE\n\n-Y 100000 +X 100000\n"))
        refused(MediaFormat.Hdr, text("#?RADIANCE\n\n+X 4 +Y 4\n"))
    }

    @Test fun `psd greyscale and cmyk composites and refusals`() {
        fun head(channels: Int, h: Int, w: Int, depth: Int, mode: Int) =
            text("8BPS") + be16(1) + ByteArray(6) + be16(channels) + be32(h.toLong()) + be32(w.toLong()) + be16(depth) + be16(mode) +
                be32(0) + be32(0) + be32(0)
        val grey = ok(MediaFormat.Psd, head(1, 1, 2, 8, 1) + be16(0) + bytes(10, 20))
        assertEquals(0xFF141414.toInt(), grey.argb[1])
        val cmyk = ok(MediaFormat.Psd, head(4, 1, 1, 8, 4) + be16(0) + bytes(255, 255, 255, 255)) // no ink = white
        assertEquals(0xFFFFFFFF.toInt(), cmyk.argb[0])
        val sixteen = ok(MediaFormat.Psd, head(3, 1, 1, 16, 3) + be16(0) + bytes(0x80, 0, 0x40, 0, 0xFF, 0))
        assertEquals(0xFF8040FF.toInt(), sixteen.argb[0])
        refused(MediaFormat.Psd, head(3, 1, 1, 8, 3) + be16(2) + bytes(1, 2, 3)) // ZIP composite
        refused(MediaFormat.Psd, head(3, 1, 1, 8, 3) + be16(0) + bytes(1)) // truncated
        refused(MediaFormat.Psd, head(3, 90000, 90000, 8, 3) + be16(0))
        refused(MediaFormat.Psd, head(3, 1, 1, 8, 9) + be16(0) + bytes(1, 2, 3)) // Lab/other mode
        refused(MediaFormat.Psb, ByteArray(40))
    }

    @Test fun `icns picks the largest PNG and legacy RLE elements work and jp2 only is refused`() {
        // legacy: is32 16x16 with each plane one run of 16*16 as runs of 130 (k=255 -> 128 copies)
        fun plane(v: Int): ByteArray { // 256 pixels = 128 + 128
            return bytes(253, v, 253, v)
        }
        val colour = plane(10) + plane(20) + plane(30)
        val mask = ByteArray(256) { 0x7F }
        fun element(t: String, d: ByteArray) = text(t) + be32(d.size + 8L) + d
        val body = element("is32", colour) + element("s8mk", mask)
        val file = text("icns") + be32(body.size + 8L) + body
        val img = ok(MediaFormat.Icns, file)
        assertEquals(16, img.width)
        assertEquals(argb(0x7F, 10, 20, 30), img.argb[255])
        val jp2 = element("ic07", bytes(0, 0, 0, 12, 0x6A, 0x50, 0x20, 0x20) + ByteArray(40))
        refused(MediaFormat.Icns, text("icns") + be32(jp2.size + 8L) + jp2)
        refused(MediaFormat.Icns, text("icns") + be32(9999) + text("ic07") + be32(0xFFFFFFF0))
    }

    // ---- inflate / zip / archives
    @Test fun `inflate handles stored fixed and refuses bombs and garbage`() {
        // stored block "abc"
        val stored = bytes(0x01, 3, 0, 0xFC, 0xFF, 'a'.code, 'b'.code, 'c'.code)
        assertEquals("abc", Inflate.raw(stored, 0, stored.size, 100).decodeToString())
        // fixed-huffman "a" (from zlib): 4B 04 00
        assertEquals("a", Inflate.raw(bytes(0x4B, 0x04, 0x00), 0, 3, 100).decodeToString())
        // the same stream with a 0-byte output cap is a bomb
        refusedInflate { Inflate.raw(stored, 0, stored.size, 2) }
        refusedInflate { Inflate.raw(bytes(0xFF, 0xFF, 0xFF), 0, 3, 100) }
        refusedInflate { Inflate.zlib(bytes(0x78, 0x9C), 0, 2, 100) }
        refusedInflate { Inflate.zlib(bytes(1, 2, 3, 4), 0, 4, 100) }
    }

    private fun refusedInflate(block: () -> Unit) {
        try { block() } catch (e: InvalidImageException) { return }
        throw AssertionError("expected InvalidImageException")
    }

    @Test fun `zip without a merged image and zip64 or encrypted entries are refused`() {
        refused(MediaFormat.Ora, ByteArray(200))
        refused(MediaFormat.Kra, bytes(0x50, 0x4B, 0x05, 0x06) + ByteArray(18))
    }

    // ---- TIFF
    @Test fun `tiff tiled jpeg and float files are refused`() {
        fun tiff(vararg entries: Triple<Int, Int, Long>): ByteArray {
            val head = text("II") + le16(42) + le32(8)
            var d = le16(entries.size)
            for ((tag, type, v) in entries) d += le16(tag) + le16(type) + le32(1) + le32(v)
            return head + d + le32(0)
        }
        val base = arrayOf(Triple(256, 4, 2L), Triple(257, 4, 2L), Triple(258, 3, 8L), Triple(262, 3, 1L), Triple(273, 4, 200L), Triple(279, 4, 4L))
        refused(MediaFormat.Tiff, tiff(*base, Triple(259, 3, 7L))) // JPEG
        refused(MediaFormat.Tiff, tiff(*base, Triple(322, 4, 16L))) // tiled
        refused(MediaFormat.Tiff, tiff(*base, Triple(339, 3, 3L))) // float
        refused(MediaFormat.Tiff, tiff(Triple(256, 4, 0xFFFFFFL), Triple(257, 4, 0xFFFFFFL)))
        refused(MediaFormat.Tiff, tiff(*base, Triple(259, 3, 1L))) // strip beyond the file
    }

    @Test fun `tiff 8-bit grey with min-is-white and predictor`() {
        fun tiffGrey(photo: Long, predictor: Long?, pixels: ByteArray): ByteArray {
            val entries = mutableListOf(
                Triple(256, 4, 3L), Triple(257, 4, 1L), Triple(258, 3, 8L), Triple(259, 3, 1L), Triple(262, 3, photo),
                Triple(273, 4, 200L), Triple(278, 4, 1L), Triple(279, 4, 3L),
            )
            if (predictor != null) entries.add(Triple(317, 3, predictor))
            entries.sortBy { it.first }
            var d = le16(entries.size)
            for ((tag, type, v) in entries) d += le16(tag) + le16(type) + le32(1) + le32(v)
            val head = text("II") + le16(42) + le32(8) + d + le32(0)
            return head + ByteArray(200 - head.size) + pixels
        }
        val white = ok(MediaFormat.Tiff, tiffGrey(0, null, bytes(0, 255, 100)))
        assertEquals(0xFFFFFFFF.toInt(), white.argb[0])
        assertEquals(0xFF000000.toInt(), white.argb[1])
        val pred = ok(MediaFormat.Tiff, tiffGrey(1, 2, bytes(10, 5, 5)))
        assertEquals(0xFF0F0F0F.toInt(), pred.argb[1])
        assertEquals(0xFF141414.toInt(), pred.argb[2])
    }

    // ---- limits, provider, registry
    @Test fun `limits cap pixels and side before any allocation`() {
        val tight = SimpleLimits(maxPixels = 100, maxSide = 50)
        refused(MediaFormat.Pnm, text("P5\n11 10\n255\n") + ByteArray(110), tight)
        refused(MediaFormat.Pnm, text("P5\n60 1\n255\n") + ByteArray(60), tight)
        ok(MediaFormat.Pnm, text("P5\n10 10\n255\n") + ByteArray(100), tight)
        refused(MediaFormat.Pnm, text("P5\n1 1\n255\n") + ByteArray(1), SimpleLimits(maxInputBytes = 5))
    }

    @Test fun `formats that are not handled here are refused`() {
        refused(MediaFormat.Jpeg, ByteArray(10))
        refused(MediaFormat.Other, ByteArray(10))
    }

    @Test fun `provider says yes for its formats and no with a reason for others`() {
        val p = SimpleDecoderProvider()
        for (f in SimpleFormatDecoders.formats) assertEquals(DecoderVerdict.Decodable, p.probe(f, ""), f.toString())
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER), p.probe(MediaFormat.Psb, ""))
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.NO_DECODER), p.probe(MediaFormat.Exr, ""))
        assertEquals(DecoderVerdict.NotDecodable(FormatCopy.TOO_LARGE), p.probe(MediaFormat.Tiff, "", SizeHint(20000, 20000)))
        assertEquals(DecoderVerdict.Decodable, p.probe(MediaFormat.Tiff, "", SizeHint(4000, 3000, 1L shl 20)))
    }

    @Test fun `registry prefers the simple provider over a refusal and keeps its slot`() {
        val no = object : com.fotoxplorr.core.formats.DecoderProvider {
            override val id = "platform"
            override val priority = 10
            override fun probe(format: MediaFormat, mime: String, sizeHint: SizeHint?) =
                DecoderVerdict.NotDecodable(FormatCopy.needsPack("TIFF image"))
        }
        val registry = DecoderRegistry(listOf(no, SimpleDecoderProvider()))
        assertEquals(DecoderVerdict.Decodable, registry.probe(MediaFormat.Tiff))
        assertEquals(DecoderVerdict.Decodable, registry.probe(MediaFormat.Tga))
        assertTrue(registry.probe(MediaFormat.Jp2) is DecoderVerdict.NotDecodable)
        assertEquals(listOf("platform", "simple-pure"), registry.providerIds)
    }
}
