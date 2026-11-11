package com.fotoxplorr.core.formats.simple

import com.fotoxplorr.core.formats.MediaFormat
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Crafted hostile headers (each must be refused quickly and without a large allocation) and the
 * correctness fixes of the review round: HDR old RLE, TIFF ExtraSamples, 16-bit TIFF, padded deflate
 * strips, TGA colour-map alpha, ICNS largest-PNG choice, multi-IDAT PNG. Allocation is measured with
 * the JVM's per-thread allocation counter.
 */
class SimpleHostileHeaderTest {

    // ---- helpers
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun be16(v: Int) = le16(v).reversedArray()
    private fun be32(v: Long) = le32(v).reversedArray()
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Runs [block], returning its result with the bytes this thread allocated and the millis taken. */
    private fun <T> measured(block: () -> T): Triple<T, Long, Long> {
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        val t0 = System.nanoTime()
        val r = block()
        return Triple(r, threads.getThreadAllocatedBytes(id) - before, (System.nanoTime() - t0) / 1_000_000)
    }

    private fun refusedCheaply(format: MediaFormat, data: ByteArray, maxAlloc: Long = 16L shl 20, limits: SimpleLimits = SimpleLimits()) {
        val (r, alloc, ms) = measured { SimpleFormatDecoders.decode(format, data, limits) }
        assertIs<SimpleResult.Failure>(r)
        assertTrue(alloc < maxAlloc, "allocated $alloc bytes for a hostile $format header")
        assertTrue(ms < 5_000, "took $ms ms")
    }

    private fun ok(format: MediaFormat, data: ByteArray): DecodedImage {
        val r = SimpleFormatDecoders.decode(format, data)
        assertIs<SimpleResult.Success>(r, (r as? SimpleResult.Failure)?.reason)
        return r.image
    }

    private fun zlib(data: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32().apply { update(type.toByteArray()); update(data) }.value
        return be32(data.size.toLong()) + type.toByteArray() + data + be32(crc)
    }

    /** A PNG of [w] x [h] grey 8-bit pixels from [pixel], its zlib stream split into [parts] IDATs. */
    private fun png(w: Int, h: Int, parts: Int = 1, pixel: (Int, Int) -> Int = { _, _ -> 0 }): ByteArray {
        val raw = ByteArray(h * (w + 1))
        for (y in 0 until h) for (x in 0 until w) raw[y * (w + 1) + 1 + x] = pixel(x, y).toByte()
        val z = zlib(raw)
        val ihdr = be32(w.toLong()) + be32(h.toLong()) + bytes(8, 0, 0, 0, 0)
        var body = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr)
        val step = (z.size + parts - 1) / parts
        var i = 0
        while (i < z.size) { body += chunk("IDAT", z.copyOfRange(i, minOf(z.size, i + step))); i += step }
        return body + chunk("IEND", ByteArray(0))
    }

    private class Entry(val tag: Int, val type: Int, val values: LongArray)

    /** A little-endian TIFF: IFD at 8, out-of-line values after it, then [tail] (strip data). */
    private fun tiff(entries: List<Entry>, tail: ByteArray, tailAt: Int = 1024): ByteArray {
        val sorted = entries.sortedBy { it.tag }
        val ifdSize = 2 + sorted.size * 12 + 4
        val extra = ByteArrayOutputStream()
        var ifd = le16(sorted.size)
        for (e in sorted) {
            val size = when (e.type) { 1 -> 1; 3 -> 2; else -> 4 }
            fun enc(v: Long) = when (size) { 1 -> bytes(v.toInt()); 2 -> le16(v.toInt()); else -> le32(v) }
            val payload = e.values.fold(ByteArray(0)) { acc, v -> acc + enc(v) }
            ifd += le16(e.tag) + le16(e.type) + le32(e.values.size.toLong())
            ifd += if (payload.size <= 4) payload + ByteArray(4 - payload.size) else le32(8L + ifdSize + extra.size()).also { extra.write(payload) }
        }
        val head = bytes('I'.code, 'I'.code) + le16(42) + le32(8) + ifd + le32(0) + extra.toByteArray()
        require(head.size <= tailAt)
        return head + ByteArray(tailAt - head.size) + tail
    }

    private fun e(tag: Int, type: Int, vararg v: Long) = Entry(tag, type, v)

    // ---- hostile headers
    @Test fun tiffDirectoryOfHugeUnreadTagsIsSkippedWithoutAllocating() {
        // 65535 distinct tags, each BYTE x 1,000,000 sharing one data range: about 524 GB before the fix.
        val n = 65535
        val b = ByteArray(8 + 2 + n * 12 + 4 + 1_000_100)
        bytes('I'.code, 'I'.code, 42, 0, 8, 0, 0, 0).copyInto(b)
        le16(n).copyInto(b, 8)
        for (i in 0 until n) {
            val at = 10 + i * 12
            le16(400 + i).copyInto(b, at); le16(1).copyInto(b, at + 2); le32(1_000_000).copyInto(b, at + 4); le32(8).copyInto(b, at + 8)
        }
        refusedCheaply(MediaFormat.Tiff, b, maxAlloc = 64L shl 20)
    }

    @Test fun tiffStripTableOverItsCapIsRefused() {
        val b = ByteArray(8 + 2 + 12 + 4 + 4_000_100)
        bytes('I'.code, 'I'.code, 42, 0, 8, 0, 0, 0).copyInto(b)
        le16(1).copyInto(b, 8)
        le16(273).copyInto(b, 10); le16(4).copyInto(b, 12); le32(1_000_000).copyInto(b, 14); le32(8).copyInto(b, 18)
        refusedCheaply(MediaFormat.Tiff, b, maxAlloc = 4L shl 20)
    }

    @Test fun tiffHeaderWithoutStripDataIsRefusedBeforeTheRowsBuffer() {
        // 24 MP RGB 8-bit raw whose single strip points past the end of a 2 KB file.
        val t = tiff(listOf(
            e(256, 4, 6000), e(257, 4, 4000), e(258, 3, 8, 8, 8), e(259, 3, 1), e(262, 3, 2), e(277, 3, 3),
            e(273, 4, 1024), e(278, 4, 4000), e(279, 4, 72_000_000),
        ), ByteArray(16))
        refusedCheaply(MediaFormat.Tiff, t)
    }

    @Test fun psdHeaderWithoutPixelDataIsRefusedBeforeAllocating() {
        fun head(compression: Int) = ascii("8BPS") + be16(1) + ByteArray(6) + be16(4) + be32(732) + be32(32768) +
            be16(16) + be16(3) + be32(0) + be32(0) + be32(0) + be16(compression)
        refusedCheaply(MediaFormat.Psd, head(0) + ByteArray(64))
        // RLE: a full counts table claiming rows of 60000 bytes that are not there.
        val counts = ByteArray(4 * 732 * 2) { if (it % 2 == 0) 0xEA.toByte() else 0x60 }
        refusedCheaply(MediaFormat.Psd, head(1) + counts + ByteArray(64))
    }

    @Test fun archiveDeflateBombIsRefusedBeforeInflating() {
        // mergedimage.png declared and deflated to 256 MB of zeros (about 250 KB on disk).
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.setLevel(9)
            z.putNextEntry(ZipEntry("mergedimage.png"))
            val zeros = ByteArray(1 shl 20)
            repeat(256) { z.write(zeros) }
            z.closeEntry()
        }
        refusedCheaply(MediaFormat.Ora, zip.toByteArray(), maxAlloc = 32L shl 20)
    }

    @Test fun inflateNeverPresizesBeyondWhatTheInputCanHold() {
        // A 3-byte stream that claims a 1.5 GB output must not allocate it.
        val (r, alloc, _) = measured { Inflate.raw(bytes(0x4B, 0x04, 0x00), 0, 3, 1L shl 31, 1_500_000_000L) }
        assertEquals("a", r.decodeToString())
        assertTrue(alloc < 1L shl 20, "allocated $alloc")
    }

    @Test fun inflateWithAnExactExpectedSizeAllocatesTheOutputOnce() {
        val data = ByteArray(8 shl 20) { (it % 251).toByte() }
        val z = zlib(data)
        val (r, alloc, _) = measured { Inflate.zlib(z, 0, z.size, 16L shl 20, data.size.toLong()) }
        assertTrue(r.contentEquals(data))
        assertTrue(alloc < data.size + (1L shl 20), "allocated $alloc for ${data.size} bytes of output")
    }

    @Test fun icnsWithManyLargePngsDecodesOnlyTheLargest() {
        val big = png(1024, 1024)
        fun element(t: String, d: ByteArray) = ascii(t) + be32(d.size + 8L) + d
        var body = element("ic07", png(16, 16) { _, _ -> 9 })
        repeat(60) { body += element("ic10", big) }
        val file = ascii("icns") + be32(body.size + 8L) + body
        val (r, alloc, _) = measured { SimpleFormatDecoders.decode(MediaFormat.Icns, file) }
        assertIs<SimpleResult.Success>(r)
        assertEquals(1024, r.image.width)
        // One 1 MP decode is about 4 MB of pixels and 1 MB of rows; sixty would be over 300 MB.
        assertTrue(alloc < 64L shl 20, "allocated $alloc")
    }

    @Test fun icnsFallsBackToTheNextLargestPngWhenTheLargestIsBroken() {
        fun element(t: String, d: ByteArray) = ascii(t) + be32(d.size + 8L) + d
        val good = png(32, 32) { _, _ -> 77 }
        val broken = png(64, 64).let { p -> p.copyOf().also { it[41] = 0x55; it[42] = 0x55 } } // corrupt the zlib header
        val body = element("ic11", good) + element("ic12", broken)
        val img = ok(MediaFormat.Icns, ascii("icns") + be32(body.size + 8L) + body)
        assertEquals(32, img.width)
        assertEquals(0xFF4D4D4D.toInt(), img.argb[0])
    }

    @Test fun outOfMemoryEndsAsAFailure() {
        // Limits far above the heap: the pixel buffer itself cannot be allocated.
        val huge = SimpleLimits(maxPixels = Long.MAX_VALUE, maxSide = Int.MAX_VALUE)
        val qoi = ascii("qoif") + be32(40_000) + be32(40_000) + bytes(4, 0) + ByteArray(16)
        val r = SimpleFormatDecoders.decode(MediaFormat.Qoi, qoi, huge)
        assertIs<SimpleResult.Failure>(r)
    }

    // ---- correctness
    @Test fun hdrOldStyleRunRepeatsThePreviousPixel() {
        // 4 wide (flat scanlines): one red pixel, then (1,1,1,3) repeats it three times.
        val img = ok(MediaFormat.Hdr, ascii("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 4\n") + bytes(128, 0, 0, 129, 1, 1, 1, 3))
        for (x in 0 until 4) assertEquals(0xFFFF0000.toInt(), img.argb[x], "pixel $x")
    }

    private fun rgba(extra: Long?, r: Int, g: Int, b: Int, a: Int): DecodedImage {
        val entries = mutableListOf(
            e(256, 4, 1), e(257, 4, 1), e(258, 3, 8, 8, 8, 8), e(259, 3, 1), e(262, 3, 2), e(277, 3, 4),
            e(273, 4, 1024), e(278, 4, 1), e(279, 4, 4),
        )
        if (extra != null) entries += e(338, 3, extra)
        return ok(MediaFormat.Tiff, tiff(entries, bytes(r, g, b, a)))
    }

    @Test fun tiffExtraSamplesDecideWhetherTheFourthChannelIsAlpha() {
        assertEquals(0xFF102030.toInt(), rgba(null, 0x10, 0x20, 0x30, 0)[0])
        assertEquals(0xFF102030.toInt(), rgba(0, 0x10, 0x20, 0x30, 0)[0])
        assertEquals(0x80102030.toInt(), rgba(2, 0x10, 0x20, 0x30, 0x80)[0])
        // Associated: 0x40 premultiplied by 0x80/255 is 0x80 straight.
        assertEquals(0x80804020.toInt(), rgba(1, 0x40, 0x20, 0x10, 0x80)[0])
        assertEquals(0x00000000, rgba(1, 0, 0, 0, 0)[0])
    }

    private operator fun DecodedImage.get(i: Int) = argb[i]

    @Test fun tiffSixteenBitKeepsTheHighByte() {
        val img = ok(MediaFormat.Tiff, tiff(listOf(
            e(256, 4, 2), e(257, 4, 1), e(258, 3, 16, 16, 16), e(259, 3, 1), e(262, 3, 2), e(277, 3, 3),
            e(273, 4, 1024), e(278, 4, 1), e(279, 4, 12),
        ), bytes(0x11, 0xAA, 0x22, 0xBB, 0x33, 0xCC, 0xFF, 0x01, 0xFF, 0x02, 0xFF, 0x03)))
        assertEquals(0xFFAABBCC.toInt(), img[0])
        assertEquals(0xFF010203.toInt(), img[1])
    }

    @Test fun tiffSixteenBitRawSingleStripOverTheStripCapStillDecodes() {
        // 4 x 2 RGB 16-bit in one strip: 48 raw bytes, 24 kept. The raw strip is read in place, so
        // only the kept rows count against maxInflated; a deflated strip needs its own 48-byte buffer.
        val limits = SimpleLimits(maxInflated = 32)
        val raw = ByteArray(48) { if (it % 2 == 1) (0x10 + it / 2).toByte() else 0x7F }
        fun file(compression: Long, strip: ByteArray) = tiff(listOf(
            e(256, 4, 4), e(257, 4, 2), e(258, 3, 16, 16, 16), e(259, 3, compression), e(262, 3, 2), e(277, 3, 3),
            e(273, 4, 1024), e(279, 4, strip.size.toLong()),
        ), strip)
        val r = SimpleFormatDecoders.decode(MediaFormat.Tiff, file(1, raw), limits)
        assertIs<SimpleResult.Success>(r, (r as? SimpleResult.Failure)?.reason)
        assertEquals(0xFF101112.toInt(), r.image[0])
        assertEquals(0xFF252627.toInt(), r.image[7])
        assertIs<SimpleResult.Failure>(SimpleFormatDecoders.decode(MediaFormat.Tiff, file(8, zlib(raw)), limits))
    }

    @Test fun tiffDeflateLastStripPaddedToRowsPerStripIsAccepted() {
        // 2 x 3 grey, RowsPerStrip 2: the second strip should hold 1 row but carries 2.
        val s0 = zlib(bytes(1, 2, 3, 4))
        val s1 = zlib(bytes(5, 6, 0, 0))
        val img = ok(MediaFormat.Tiff, tiff(listOf(
            e(256, 4, 2), e(257, 4, 3), e(258, 3, 8), e(259, 3, 8), e(262, 3, 1),
            e(273, 4, 1024, 1024L + s0.size), e(278, 4, 2), e(279, 4, s0.size.toLong(), s1.size.toLong()),
        ), s0 + s1))
        assertEquals(0xFF050505.toInt(), img[4])
        assertEquals(0xFF060606.toInt(), img[5])
    }

    @Test fun tgaSixteenBitColourMapWithoutAttributeBitsIsOpaque() {
        // Type 1, 2 entries of 16 bits (top bit clear), descriptor alpha bits 0, top-left origin.
        val head = bytes(0, 1, 1) + le16(0) + le16(2) + bytes(16) + ByteArray(4) + le16(2) + le16(1) + bytes(8, 0x20)
        val img = ok(MediaFormat.Tga, head + le16(0x7C00) + le16(0x001F) + bytes(0, 1))
        assertEquals(0xFFFF0000.toInt(), img[0])
        assertEquals(0xFF0000FF.toInt(), img[1])
    }

    @Test fun pngSplitOverSeveralIdatChunksDecodes() {
        val p = png(40, 30, parts = 5) { x, y -> x + y }
        val img = PngLite.decode(p, 0, p.size, SimpleLimits())
        assertEquals(0xFF000000.toInt() or (68 * 0x010101), img.argb[29 * 40 + 39])
    }

    @Test fun psdSixteenBitRleAndRawWithAlphaDecode() {
        fun head(compression: Int) = ascii("8BPS") + be16(1) + ByteArray(6) + be16(4) + be32(1) + be32(2) +
            be16(16) + be16(3) + be32(0) + be32(0) + be32(0) + be16(compression)
        // Raw: planes R, G, B, A of two 16-bit samples each.
        val raw = ok(MediaFormat.Psd, head(0) + bytes(0x10, 0, 0x11, 0, 0x20, 0, 0x21, 0, 0x30, 0, 0x31, 0, 0xFF, 0, 0x80, 0))
        assertEquals(0xFF102030.toInt(), raw[0])
        assertEquals(0x80112131.toInt(), raw[1])
        // RLE: each 4-byte row as one literal run (5 bytes).
        fun row(hi0: Int, hi1: Int) = bytes(3, hi0, 0, hi1, 0)
        val rle = ok(MediaFormat.Psd, head(1) + be16(5) + be16(5) + be16(5) + be16(5) + row(0x10, 0x11) + row(0x20, 0x21) + row(0x30, 0x31) + row(0xFF, 0x80))
        assertEquals(0xFF102030.toInt(), rle[0])
        assertEquals(0x80112131.toInt(), rle[1])
    }
}
