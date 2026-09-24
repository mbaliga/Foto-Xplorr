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
    fun `webp -- a RIFF container with WEBP at offset 8, not just any RIFF file`() {
        assertFormat(MediaFormat.WebP, ascii("RIFF") + hex("00000000") + ascii("WEBP") + pad())
        assertNull(FormatSniffer.sniff(ascii("RIFF") + hex("00000000") + ascii("AVI ") + pad()), "a RIFF/AVI file is not WebP")
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
        assertNull(FormatSniffer.sniff(isobmff("mp41")))
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
    fun `pnm -- all six netpbm sub-formats, but not a random P-prefixed file`() {
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
    fun `pcx -- manufacturer byte alone is not enough, encoding byte must also match`() {
        assertFormat(MediaFormat.Pcx, hex("0A0501") + pad())
        assertNull(FormatSniffer.sniff(hex("0A0500") + pad()), "encoding byte 0x00 (uncompressed) doesn't match this sniffer's RLE-only check")
    }

    @Test
    fun `xcf`() = assertFormat(MediaFormat.Xcf, ascii("gimp xcf") + pad())

    @Test
    fun `an empty or short header never throws, just returns null`() {
        assertNull(FormatSniffer.sniff(ByteArray(0)))
        assertNull(FormatSniffer.sniff(byteArrayOf(0x00)))
    }

    @Test
    fun `an unrecognised file returns null, not a guess`() {
        assertNull(FormatSniffer.sniff(ascii("this is not any known format") + pad()))
    }

    private fun assertFormat(expected: MediaFormat, header: ByteArray, message: String? = null) {
        assertEquals(expected, FormatSniffer.sniff(header), message)
    }

    private fun isobmff(brand: String): ByteArray =
        hex("00000018") + ascii("ftyp") + ascii(brand) + hex("00000000") + ascii(brand) + pad()

    private fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun pad(size: Int = FormatSniffer.REQUIRED_HEADER_BYTES): ByteArray = ByteArray(size)
}
