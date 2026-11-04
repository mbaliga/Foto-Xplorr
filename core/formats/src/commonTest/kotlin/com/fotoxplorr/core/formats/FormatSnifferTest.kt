package com.fotoxplorr.core.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [FormatSniffer.sniff] pinned against real header bytes for every format it claims to detect,
 * each verified against a primary source before being written into [FormatSniffer] itself (see
 * that object's own doc). One test per format, plus a handful of "doesn't false-positive on a
 * near-miss" cases for the trickiest signatures (ISOBMFF brand, PNM, PCX).
 */
class FormatSnifferTest {

    @Test
    fun `png`() = assertFormat(MediaFormat.Png, hex("89504E470D0A1A0A") + pad())

    @Test
    fun `jpeg`() = assertFormat(MediaFormat.Jpeg, hex("FFD8FFE0") + pad())

    @Test
    fun `gif87a and gif89a`() {
        assertFormat(MediaFormat.Gif, ascii("GIF87a") + pad())
        assertFormat(MediaFormat.Gif, ascii("GIF89a") + pad())
    }

    @Test
    fun `bmp`() = assertFormat(MediaFormat.Bmp, ascii("BM") + pad())

    @Test
    fun `webp -- a RIFF container with WEBP at offset 8 -- not just any RIFF file`() {
        assertFormat(MediaFormat.WebP, ascii("RIFF") + hex("00000000") + ascii("WEBP") + pad())
        assertNull(FormatSniffer.sniff(ascii("RIFF") + hex("00000000") + ascii("WAVE") + pad()), "a RIFF/WAVE file is not WebP")
    }

    @Test
    fun `ico`() = assertFormat(MediaFormat.Ico, hex("00000100") + pad())

    @Test
    fun `heif brands`() {
        for (brand in listOf("heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "mif1", "msf1")) {
            assertFormat(MediaFormat.Heif, isobmff(brand), "brand=$brand")
        }
    }

    @Test
    fun `avif brands`() {
        assertFormat(MediaFormat.Avif, isobmff("avif"))
        assertFormat(MediaFormat.Avif, isobmff("avis"))
    }

    @Test
    fun `an isobmff box with an unrecognised brand is neither heif nor avif`() {
        assertNull(FormatSniffer.sniff(isobmff("zzzz")))
        assertNull(FormatSniffer.sniff(isobmff("M4A ")), "audio-only brands are not claimed")
    }

    @Test
    fun `isobmff video brands`() {
        for (brand in listOf("isom", "mp41", "mp42", "M4V ", "3gp4", "3g2a", "qt  ")) {
            assertFormat(MediaFormat.Video, isobmff(brand), "brand=$brand")
        }
    }

    @Test
    fun `canon cr3 is an isobmff file with the crx brand`() =
        assertFormat(MediaFormat.Raw(RawVariant.CR3), isobmff("crx "))

    @Test
    fun `matroska webm flv and avi are video`() {
        assertFormat(MediaFormat.Video, hex("1A45DFA3") + pad())
        assertFormat(MediaFormat.Video, ascii("FLV") + hex("01") + pad())
        assertFormat(MediaFormat.Video, ascii("RIFF") + hex("00000000") + ascii("AVI ") + pad())
    }

    @Test
    fun `apng -- an actl chunk before idat -- but a plain png stays png`() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val actl = pngChunk("acTL", ByteArray(8))
        val idat = pngChunk("IDAT", ByteArray(2))
        val sig = hex("89504E470D0A1A0A")
        assertFormat(MediaFormat.Apng, sig + ihdr + actl)
        assertFormat(MediaFormat.Png, sig + ihdr + idat)
        // A small ancillary chunk between IHDR and acTL (gAMA is 4 bytes of data) is walked over.
        assertFormat(MediaFormat.Apng, sig + ihdr + pngChunk("gAMA", ByteArray(4)) + actl)
        // IDAT first means any later acTL is not an animation.
        assertFormat(MediaFormat.Png, sig + ihdr + idat + actl)
    }

    @Test
    fun `a png whose first chunk claims an absurd length does not crash the walk`() {
        val sig = hex("89504E470D0A1A0A")
        assertFormat(MediaFormat.Png, sig + hex("FFFFFFFF") + ascii("iCCP") + pad())
    }

    @Test
    fun `psd is version 1 and psb is version 2`() {
        assertFormat(MediaFormat.Psd, ascii("8BPS") + hex("0001") + pad())
        assertFormat(MediaFormat.Psb, ascii("8BPS") + hex("0002") + pad())
    }

    @Test
    fun `bigtiff in both byte orders is tiff`() {
        assertFormat(MediaFormat.Tiff, hex("49492B00") + pad())
        assertFormat(MediaFormat.Tiff, hex("4D4D002B") + pad())
    }

    @Test
    fun `ora and kra are zips whose first stored entry is mimetype`() {
        assertFormat(MediaFormat.Ora, zipWithMimetype("image/openraster"))
        assertFormat(MediaFormat.Kra, zipWithMimetype("application/x-krita"))
        assertFormat(MediaFormat.Ora, zipWithMimetype("image/openraster", extra = 4), "extra field shifts the data")
        assertNull(FormatSniffer.sniff(zipWithMimetype("application/epub+zip")), "another mimetype zip is not claimed")
        assertNull(FormatSniffer.sniff(hex("504B0304") + pad()), "a bare zip is not claimed")
    }

    @Test
    fun `raw containers with their own markers`() {
        assertFormat(MediaFormat.Raw(RawVariant.CR2), hex("49492A0010000000") + ascii("CR") + hex("0200") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.RAF), ascii("FUJIFILMCCD-RAW 0201") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.X3F), ascii("FOVb") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.MRW), hex("004D524D") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.CRW), hex("49491A0000004845415043434452") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.RW2), hex("49495500") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.ORF), ascii("IIRO") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.ORF), ascii("IIRS") + pad())
        assertFormat(MediaFormat.Raw(RawVariant.ORF), ascii("MMOR") + pad())
    }

    @Test
    fun `a plain tiff header is not mistaken for cr2`() {
        assertFormat(MediaFormat.Tiff, hex("49492A0008000000") + ascii("XX") + hex("0200") + pad())
    }

    @Test
    fun `the header size callers must read covers the deepest check`() {
        assertEquals(64, FormatSniffer.REQUIRED_HEADER_BYTES)
    }

    @Test
    fun `jpeg xl -- both the raw codestream and the container forms`() {
        assertFormat(MediaFormat.JpegXl, hex("FF0A") + pad())
        assertFormat(MediaFormat.JpegXl, hex("0000000C4A584C200D0A870A") + pad())
    }

    @Test
    fun `tiff -- both byte orders`() {
        assertFormat(MediaFormat.Tiff, hex("49492A00") + pad())
        assertFormat(MediaFormat.Tiff, hex("4D4D002A") + pad())
    }

    @Test
    fun `psd`() = assertFormat(MediaFormat.Psd, ascii("8BPS") + pad())

    @Test
    fun `exr`() = assertFormat(MediaFormat.Exr, hex("762F3101") + pad())

    @Test
    fun `radiance hdr`() = assertFormat(MediaFormat.Hdr, ascii("#?RADIANCE\n") + pad())

    @Test
    fun `pnm -- all six netpbm sub-formats -- but not a random P-prefixed file`() {
        for (n in '1'..'6') {
            assertFormat(MediaFormat.Pnm, ascii("P${n}\n") + pad(), "P$n")
        }
        assertNull(FormatSniffer.sniff(ascii("PXY") + pad()), "not every P-prefixed file is PNM")
        assertNull(FormatSniffer.sniff(ascii("P7") + pad()), "P7 (PAM) is outside the P1..P6 range this sniffer claims")
    }

    @Test
    fun `jpeg 2000 -- both the jp2 container and the raw codestream`() {
        assertFormat(MediaFormat.Jp2, hex("0000000C6A5020200D0A870A") + pad())
        assertFormat(MediaFormat.Jp2, hex("FF4F") + pad())
    }

    @Test
    fun `jpeg xr`() = assertFormat(MediaFormat.Jxr, hex("4949BC01") + pad())

    @Test
    fun `qoi`() = assertFormat(MediaFormat.Qoi, ascii("qoif") + pad())

    @Test
    fun `dds`() = assertFormat(MediaFormat.Dds, ascii("DDS ") + pad())

    @Test
    fun `ktx1 and ktx2 exact signatures`() {
        assertFormat(MediaFormat.Ktx, hex("AB4B5458203131BB0D0A1A0A") + pad())
        assertFormat(MediaFormat.Ktx, hex("AB4B5458203230BB0D0A1A0A") + pad())
    }

    @Test
    fun `icns`() = assertFormat(MediaFormat.Icns, ascii("icns") + pad())

    @Test
    fun `pcx -- manufacturer byte alone is not enough -- the encoding byte must also match`() {
        assertFormat(MediaFormat.Pcx, hex("0A0501") + pad())
        assertNull(FormatSniffer.sniff(hex("0A0500") + pad()), "encoding byte 0x00 (uncompressed) doesn't match this sniffer's RLE-only check")
    }

    @Test
    fun `xcf`() = assertFormat(MediaFormat.Xcf, ascii("gimp xcf") + pad())

    @Test
    fun `an empty or short header never throws -- it just returns null`() {
        assertNull(FormatSniffer.sniff(ByteArray(0)))
        assertNull(FormatSniffer.sniff(byteArrayOf(0x00)))
    }

    @Test
    fun `an unrecognised file returns null -- not a guess`() {
        assertNull(FormatSniffer.sniff(ascii("this is not any known format") + pad()))
    }

    private fun assertFormat(expected: MediaFormat, header: ByteArray, message: String? = null) {
        assertEquals(expected, FormatSniffer.sniff(header), message)
    }

    private fun isobmff(brand: String): ByteArray =
        hex("00000018") + ascii("ftyp") + ascii(brand) + hex("00000000") + ascii(brand) + pad()

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val n = data.size
        val length = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        return length + ascii(type) + data + ByteArray(4)
    }

    /** A ZIP local file header for a stored `mimetype` entry, then its content. */
    private fun zipWithMimetype(content: String, extra: Int = 0): ByteArray {
        val header = ByteArray(30)
        hex("504B0304").copyInto(header)
        header[26] = 8 // name length (little endian)
        header[28] = extra.toByte()
        return header + ascii("mimetype") + ByteArray(extra) + ascii(content) + pad()
    }

    private fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun pad(size: Int = FormatSniffer.REQUIRED_HEADER_BYTES): ByteArray = ByteArray(size)
}
