package com.fotoxplorr.core.formats.raw

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every container here is CONSTRUCTED in the test from the public layout descriptions; no real RAW
 * file is used, so these prove the walker follows the layouts as written, not that real cameras
 * write them that way (see the extractor's KDoc). Names avoid commas for Kotlin/Native.
 */
class RawPreviewExtractorTest {

    private fun tiffHeader(b: Buf, ifd0: Long = 8, magic: Int = 42) {
        b.ascii(0, if (b.le) "II" else "MM"); b.u16(2, magic); b.u32(4, ifd0)
    }

    private fun check(p: RawPreview?, src: Buf, w: Int, h: Int, kind: RawPreviewKind, orientation: Int = 1) {
        assertNotNull(p)
        assertEquals(w, p.width); assertEquals(h, p.height)
        assertEquals(kind, p.kind); assertEquals(orientation, p.orientation)
        // The range really is the JPEG.
        assertEquals(0xFF, src.bytes[p.offset.toInt()].toInt() and 0xFF)
        assertEquals(0xD9, src.bytes[(p.offset + p.length - 1).toInt()].toInt() and 0xFF)
    }

    private fun ifd1ThumbTiff(le: Boolean): Buf {
        val b = Buf(le)
        tiffHeader(b)
        val thumb = jpeg(160, 120, 300)
        // IFD0 (1 entry: Orientation=6) -> IFD1 (JPEGInterchangeFormat/Length)
        val end0 = b.ifd(8, 8L + 2 + 12 + 4, intArrayOf(0x0112, 3, 1, 6))
        b.ifd(end0, 0, intArrayOf(0x0201, 4, 1, 200), intArrayOf(0x0202, 4, 1, thumb.size))
        b.put(200, thumb)
        return b
    }

    @Test
    fun `little endian tiff with an IFD1 thumbnail`() {
        val b = ifd1ThumbTiff(true)
        check(RawPreviewExtractor.extract(b.source()), b, 160, 120, RawPreviewKind.IFD_THUMBNAIL, orientation = 6)
    }

    @Test
    fun `big endian tiff with an IFD1 thumbnail`() {
        val b = ifd1ThumbTiff(false)
        check(RawPreviewExtractor.extract(b.source()), b, 160, 120, RawPreviewKind.IFD_THUMBNAIL, orientation = 6)
    }

    @Test
    fun `dng like subifd preview wins over the thumbnail`() {
        val b = Buf(true)
        tiffHeader(b)
        val thumb = jpeg(160, 120, 300)
        val big = jpeg(1616, 1080, 900)
        // IFD0: Orientation, SubIFDs -> 100; IFD1 thumb at 140.
        val end0 = b.ifd(8, 140, intArrayOf(0x0112, 3, 1, 8), intArrayOf(0x014A, 4, 1, 100))
        assertTrue(end0 <= 100)
        b.ifd(100, 0, intArrayOf(0x0103, 3, 1, 7), intArrayOf(0x0111, 4, 1, 400), intArrayOf(0x0117, 4, 1, big.size))
        b.ifd(140, 0, intArrayOf(0x0201, 4, 1, 200), intArrayOf(0x0202, 4, 1, thumb.size))
        b.put(200, thumb).put(400, big)
        check(RawPreviewExtractor.extract(b.source()), b, 1616, 1080, RawPreviewKind.STRIP_JPEG, orientation = 8)
    }

    @Test
    fun `strip with lossless jpeg compression is not a preview`() {
        val b = Buf(true)
        tiffHeader(b)
        val raw = jpeg(4000, 3000, 600, sof = 0xC3) // SOF3: the DNG/CR2 raw payload
        b.ifd(8, 0, intArrayOf(0x0103, 3, 1, 7), intArrayOf(0x0111, 4, 1, 200), intArrayOf(0x0117, 4, 1, raw.size))
        b.put(200, raw)
        assertNull(RawPreviewExtractor.extract(b.source()))
    }

    @Test
    fun `cr2 like ifd0 old jpeg strip`() {
        val b = Buf(true)
        tiffHeader(b)
        val full = jpeg(1920, 1280, 700)
        b.ifd(8, 0, intArrayOf(0x0103, 3, 1, 6), intArrayOf(0x0111, 4, 1, 300), intArrayOf(0x0117, 4, 1, full.size))
        b.put(300, full)
        check(RawPreviewExtractor.extract(b.source()), b, 1920, 1280, RawPreviewKind.STRIP_JPEG)
    }

    @Test
    fun `orf like makernote preview offsets`() {
        val b = Buf(true)
        tiffHeader(b, magic = 0x4F52)
        val thumb = jpeg(160, 120, 300)
        val prev = jpeg(1600, 1200, 800)
        // IFD0: ExifIFD -> 60. ExifIFD: MakerNote (tag 927C, UNDEFINED, 60 bytes) at 120.
        b.ifd(8, 0, intArrayOf(0x8769, 4, 1, 60))
        b.ifd(60, 0, intArrayOf(0x927C, 7, 60, 120))
        // MakerNote: "OLYMPUS\0" "II" 0x0003, IFD at +12; offsets relative to the makernote start.
        b.ascii(120, "OLYMPUS\u0000"); b.ascii(128, "II"); b.u16(130, 3)
        b.ifd(132, 0, intArrayOf(0x2020, 4, 1, 40))
        // CameraSettings IFD at mn+40 = 160: PreviewImageStart/Length relative to mn start.
        b.ifd(160, 0, intArrayOf(0x0101, 4, 1, 500 - 120), intArrayOf(0x0102, 4, 1, prev.size))
        b.put(300, thumb).put(500, prev)
        check(RawPreviewExtractor.extract(b.source()), b, 1600, 1200, RawPreviewKind.MAKER_NOTE_PREVIEW)
    }

    @Test
    fun `pentax like makernote preview`() {
        val b = Buf(false)
        tiffHeader(b)
        val prev = jpeg(1280, 960, 600)
        b.ifd(8, 0, intArrayOf(0x8769, 4, 1, 60))
        b.ifd(60, 0, intArrayOf(0x927C, 7, 60, 120))
        b.ascii(120, "AOC\u0000"); b.ascii(124, "MM")
        b.ifd(126, 0, intArrayOf(0x0003, 4, 1, prev.size), intArrayOf(0x0004, 4, 1, 500))
        b.put(500, prev)
        check(RawPreviewExtractor.extract(b.source()), b, 1280, 960, RawPreviewKind.MAKER_NOTE_PREVIEW)
    }

    @Test
    fun `nikon like preview ifd inside the makernote tiff`() {
        val b = Buf(true)
        tiffHeader(b)
        val prev = jpeg(1620, 1080, 700)
        b.ifd(8, 0, intArrayOf(0x8769, 4, 1, 60))
        b.ifd(60, 0, intArrayOf(0x927C, 7, 80, 120))
        // "Nikon\0" 0x02 0x10 0 0 then an inner TIFF at +10 (= 130).
        b.ascii(120, "Nikon\u0000"); b.u8(126, 2); b.u8(127, 0x10)
        b.ascii(130, "II"); b.u16(132, 42); b.u32(134, 8)
        b.ifd(138, 0, intArrayOf(0x0011, 4, 1, 100)) // PreviewIFD at inner+100 = 230
        b.ifd(230, 0, intArrayOf(0x0201, 4, 1, 300), intArrayOf(0x0202, 4, 1, prev.size)) // inner-relative
        b.put(130 + 300, prev)
        check(RawPreviewExtractor.extract(b.source()), b, 1620, 1080, RawPreviewKind.SUB_IFD_PREVIEW)
    }

    @Test
    fun `sony like inline makernote preview`() {
        val b = Buf(true)
        tiffHeader(b)
        val prev = jpeg(1616, 1080, 700)
        b.ifd(8, 0, intArrayOf(0x8769, 4, 1, 60))
        b.ifd(60, 0, intArrayOf(0x927C, 7, 40, 120))
        b.ascii(120, "SONY DSC \u0000\u0000\u0000")
        b.ifd(132, 0, intArrayOf(0x2001, 7, prev.size, 400))
        b.put(400, prev)
        check(RawPreviewExtractor.extract(b.source()), b, 1616, 1080, RawPreviewKind.MAKER_NOTE_PREVIEW)
    }

    @Test
    fun `rw2 like jpgfromraw tag`() {
        val b = Buf(true)
        tiffHeader(b, magic = 0x55)
        val full = jpeg(1920, 1440, 900)
        b.ifd(8, 0, intArrayOf(0x0112, 3, 1, 3), intArrayOf(0x002E, 7, full.size, 300))
        b.put(300, full)
        check(RawPreviewExtractor.extract(b.source()), b, 1920, 1440, RawPreviewKind.JPG_FROM_RAW, orientation = 3)
    }

    @Test
    fun `raf like header`() {
        val b = Buf(false)
        b.ascii(0, "FUJIFILMCCD-RAW 0201")
        val j = jpeg(1920, 1280, 800, orientation = 6)
        b.u32(84, 160).u32(88, j.size.toLong())
        b.put(160, j).pad(160 + j.size + 100)
        check(RawPreviewExtractor.extract(b.source()), b, 1920, 1280, RawPreviewKind.RAF_EMBEDDED, orientation = 6)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = Buf(false)
        b.u32(0, 8L + payload.size).ascii(4, type).put(8, payload)
        return b.bytes
    }

    @Test
    fun `cr3 like isobmff with a PRVW box and CMT1 orientation`() {
        val prev = jpeg(1620, 1080, 700)
        val thmb = jpeg(160, 120, 300)
        // PRVW payload: u32 0, u16 1, u16 w, u16 h, u16 1, u32 size, jpeg
        val pr = Buf(false)
        pr.u32(0, 0).u16(4, 1).u16(6, 1620).u16(8, 1080).u16(10, 1).u32(12, prev.size.toLong()).put(16, prev)
        val prvwUuid = ByteArray(16) { 0x11 } + Buf(false).u32(0, 0).u32(4, 1).bytes + box("PRVW", pr.bytes)
        val th = Buf(false)
        th.u32(0, 0).u16(4, 160).u16(6, 120).u32(8, thmb.size.toLong()).u32(12, 0).put(16, thmb)
        val tiff = Buf(true); tiffHeader(tiff); tiff.ifd(8, 0, intArrayOf(0x0112, 3, 1, 6))
        val moovUuid = ByteArray(16) { 0x22 } + box("CMT1", tiff.bytes) + box("THMB", th.bytes)
        val ftyp = box("ftyp", "crx ".encodeToByteArray() + ByteArray(4) + "crx isom".encodeToByteArray())
        val file = ftyp + box("moov", box("uuid", moovUuid)) + box("uuid", prvwUuid) + box("mdat", ByteArray(500))
        val src = ByteArraySource(file)
        val p = RawPreviewExtractor.extract(src)
        assertNotNull(p)
        assertEquals(1620, p.width); assertEquals(1080, p.height)
        assertEquals(RawPreviewKind.ISOBMFF_PRVW, p.kind)
        assertEquals(6, p.orientation)
        assertEquals(prev.size.toLong(), p.length)
    }

    @Test
    fun `preview orientation falls back to the jpeg own exif`() {
        val b = Buf(true)
        tiffHeader(b)
        val j = jpeg(1200, 800, 500, orientation = 8)
        b.ifd(8, 0, intArrayOf(0x0103, 3, 1, 6), intArrayOf(0x0111, 4, 1, 300), intArrayOf(0x0117, 4, 1, j.size))
        b.put(300, j)
        check(RawPreviewExtractor.extract(b.source()), b, 1200, 800, RawPreviewKind.STRIP_JPEG, orientation = 8)
    }

    // ---- malformed: must be null and never throw ----

    @Test
    fun `offset beyond the file is rejected`() {
        val b = Buf(true); tiffHeader(b)
        b.ifd(8, 0, intArrayOf(0x0201, 4, 1, 100000), intArrayOf(0x0202, 4, 1, 500))
        assertNull(RawPreviewExtractor.extract(b.source()))
    }

    @Test
    fun `zero length is rejected`() {
        val b = Buf(true); tiffHeader(b)
        b.ifd(8, 0, intArrayOf(0x0201, 4, 1, 200), intArrayOf(0x0202, 4, 1, 0))
        b.put(200, jpeg(800, 600))
        assertNull(RawPreviewExtractor.extract(b.source()))
    }

    @Test
    fun `length running past the file is rejected`() {
        val b = Buf(true); tiffHeader(b)
        val j = jpeg(800, 600, 400)
        b.ifd(8, 0, intArrayOf(0x0201, 4, 1, 200), intArrayOf(0x0202, 4, 1, j.size + 5000))
        b.put(200, j)
        assertNull(RawPreviewExtractor.extract(b.source()))
    }

    @Test
    fun `an ifd loop terminates`() {
        val b = Buf(true); tiffHeader(b)
        b.ifd(8, 8, intArrayOf(0x0112, 3, 1, 1)) // next -> itself
        assertNull(RawPreviewExtractor.extract(b.source()))
        val c = Buf(true); tiffHeader(c)
        c.ifd(8, 0, intArrayOf(0x014A, 4, 1, 8)) // SubIFD -> itself
        assertNull(RawPreviewExtractor.extract(c.source()))
    }

    @Test
    fun `a truncated jpeg is rejected`() {
        val b = Buf(true); tiffHeader(b)
        val j = jpeg(800, 600, 400)
        val cut = j.copyOf(j.size - 10) // loses the EOI
        b.ifd(8, 0, intArrayOf(0x0201, 4, 1, 200), intArrayOf(0x0202, 4, 1, cut.size))
        b.put(200, cut)
        assertNull(RawPreviewExtractor.extract(b.source()))
    }

    @Test
    fun `absurd dimensions are rejected`() {
        val b = Buf(true); tiffHeader(b)
        val j = jpeg(4, 4, 400)
        b.ifd(8, 0, intArrayOf(0x0201, 4, 1, 200), intArrayOf(0x0202, 4, 1, j.size))
        b.put(200, j)
        assertNull(RawPreviewExtractor.extract(b.source()))
    }

    @Test
    fun `a huge entry count and a huge makernote count are survived`() {
        val b = Buf(true); tiffHeader(b)
        b.u16(8, 0xFFFF)
        assertNull(RawPreviewExtractor.extract(b.source()))
        val c = Buf(true); tiffHeader(c)
        c.ifd(8, 0, intArrayOf(0x8769, 4, 1, 60))
        c.ifd(60, 0, intArrayOf(0x927C, 7, Int.MAX_VALUE, 120))
        assertNull(RawPreviewExtractor.extract(c.source()))
    }

    @Test
    fun `empty and non raw sources return null`() {
        assertNull(RawPreviewExtractor.extract(ByteArraySource(ByteArray(0))))
        assertNull(RawPreviewExtractor.extract(ByteArraySource(jpeg(800, 600))))
        assertNull(RawPreviewExtractor.extract(ByteArraySource(ByteArray(64) { 0x55 })))
    }

    @Test
    fun `a box with a bogus size is survived`() {
        val file = Buf(false).u32(0, 20).ascii(4, "ftyp").ascii(8, "crx ").u32(20, 0xFFFFFFF0L).ascii(24, "moov").pad(64)
        assertNull(RawPreviewExtractor.extract(file.source()))
    }

    // ---- property: never reads outside the source, whatever the bytes ----

    @Test
    fun `the extractor never reads beyond the source on mutated and random input`() {
        val seeds = listOf(ifd1ThumbTiff(true).bytes, ifd1ThumbTiff(false).bytes, Buf(false).ascii(0, "FUJIFILMCCD-RAW 0201").u32(84, 160).u32(88, 400).pad(700).bytes)
        val rnd = Random(1234)
        repeat(3000) { n ->
            val data = seeds[n % seeds.size].copyOf(if (n % 7 == 0) rnd.nextInt(0, 900) else 700)
            if (data.isEmpty()) return@repeat
            repeat(rnd.nextInt(1, 12)) { data[rnd.nextInt(data.size)] = rnd.nextInt(256).toByte() }
            val rec = RecordingSource(ByteArraySource(data))
            RawPreviewExtractor.extract(rec)
            assertEquals(0, rec.violations, "read outside the source on iteration $n")
            assertTrue(rec.bytesRead <= Reader.MAX_TOTAL_BYTES)
        }
    }

    @Test
    fun `a large source is not read in full`() {
        val b = ifd1ThumbTiff(true)
        val big = b.bytes.copyOf(40_000_000)
        val rec = RecordingSource(ByteArraySource(big))
        assertNotNull(RawPreviewExtractor.extract(rec))
        assertTrue(rec.bytesRead < 100_000, "read ${rec.bytesRead}")
    }
}
