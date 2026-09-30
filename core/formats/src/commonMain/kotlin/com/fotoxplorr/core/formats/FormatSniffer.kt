package com.fotoxplorr.core.formats

/**
 * WP1.7: identifies a file from its actual leading bytes, independent of whatever mime type or
 * extension MediaStore/the filesystem happens to report -- the "Discover" column of
 * `docs/handoff/MASTER-PLAN.md` §4's format matrix. [MediaFormat.classify] trusts mime/extension
 * (fast, no file I/O); this is the fallback for when that's wrong or missing, and the only way to
 * tell some formats apart at all (HEIC and AVIF share the same ISOBMFF container shape and no
 * extension convention every device agrees on -- only the bytes at offset 8 actually say which).
 *
 * [sniff] needs only a file's first [REQUIRED_HEADER_BYTES] bytes -- callers read that many (or
 * fewer, if the file itself is shorter) and pass them in; this object does no file I/O itself,
 * staying platform-free like the rest of `:core:formats`. Returns null for anything unrecognised
 * -- exactly like [MediaFormat.classify]'s own "unrecognised is not the same claim as known
 * broken" reasoning, a caller falls back to `classify()` or [MediaFormat.Other], never invents a
 * guess.
 *
 * Every signature below is checked against a primary source (the format's own spec, or, absent
 * one, an established reference such as Wikipedia's "List of file signatures") before being
 * written here -- getting a magic number wrong either misclassifies real files or makes this
 * sniffer permanently blind to a format it claims to detect, and neither fails loudly.
 *
 * Deliberately NOT attempted here, all for the same reason -- no signature confident enough to
 * write down:
 *  - **ORA / KRA**: both are ZIP containers; telling them apart (and from a generic ZIP) needs the
 *    archive's internal `mimetype` entry inspected, not a header-byte check.
 *  - **TGA**: no reliable signature at the START of the file -- its one optional signature is an
 *    18-byte footer at the END, which this offset-0-bytes-only design can't reach.
 *  - **Softimage PIC**: no independently-confirmed signature found.
 */
object FormatSniffer {
    /** Comfortably covers every check below (the deepest is KTX2's 12-byte magic at offset 0,
     *  and the ISOBMFF brand check at offset 8..12); callers read this many bytes, or fewer if
     *  the file itself is shorter -- [sniff] handles a short header by simply not matching. */
    const val REQUIRED_HEADER_BYTES = 32

    fun sniff(header: ByteArray): MediaFormat? = when {
        header.startsWith(PNG) -> MediaFormat.Png
        header.startsWith(JPEG) -> MediaFormat.Jpeg
        header.startsWith(GIF87A) || header.startsWith(GIF89A) -> MediaFormat.Gif
        header.startsWith(BMP) -> MediaFormat.Bmp
        isRiffWebp(header) -> MediaFormat.WebP
        header.startsWith(ICO) -> MediaFormat.Ico
        isIsobmff(header) -> sniffIsobmffBrand(header)
        header.size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte() -> MediaFormat.JpegXl
        header.startsWith(JXL_CONTAINER) -> MediaFormat.JpegXl
        header.startsWith(TIFF_LE) || header.startsWith(TIFF_BE) -> MediaFormat.Tiff
        header.startsWith(PSD) -> MediaFormat.Psd
        header.startsWith(EXR) -> MediaFormat.Exr
        header.startsWith(HDR) -> MediaFormat.Hdr
        isPnm(header) -> MediaFormat.Pnm
        header.startsWith(JP2) -> MediaFormat.Jp2
        header.size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0x4F.toByte() -> MediaFormat.Jp2
        header.startsWith(JXR) -> MediaFormat.Jxr
        header.startsWith(QOI) -> MediaFormat.Qoi
        header.startsWith(DDS) -> MediaFormat.Dds
        header.startsWith(KTX1) || header.startsWith(KTX2) -> MediaFormat.Ktx
        header.startsWith(ICNS) -> MediaFormat.Icns
        isPcx(header) -> MediaFormat.Pcx
        header.startsWith(XCF) -> MediaFormat.Xcf
        else -> null
    }

    private fun isIsobmff(header: ByteArray): Boolean = header.size >= 8 && header.regionEquals(4, FTYP)

    /** `mif1` is the one genuinely ambiguous brand: the generic HEIF container brand, but also
     *  sometimes an AVIF file's major brand with `avif` only in its compatible-brands list
     *  (which can start at any offset, not a fixed one) -- treated as HEIF here, the more common
     *  case in practice. A known, accepted limitation, not an oversight. */
    private fun sniffIsobmffBrand(header: ByteArray): MediaFormat? {
        if (header.size < 12) return null
        return when (header.decodeAsciiRange(8, 12)) {
            "heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "mif1", "msf1" -> MediaFormat.Heif
            "avif", "avis" -> MediaFormat.Avif
            else -> null
        }
    }

    private fun isRiffWebp(header: ByteArray): Boolean =
        header.size >= 12 && header.regionEquals(0, RIFF) && header.regionEquals(8, WEBP)

    /** ASCII `P1`..`P6` (Netpbm's six sub-formats), each followed by whitespace per the format's
     *  own grammar -- not just any `P`-prefixed file. */
    private fun isPnm(header: ByteArray): Boolean =
        header.size >= 3 &&
            header[0] == 'P'.code.toByte() &&
            header[1] in '1'.code.toByte()..'6'.code.toByte() &&
            header[2].toInt().toChar().isWhitespace()

    /** Byte 0 is PCX's own "manufacturer" identifier (always 0x0A); byte 2 is the encoding
     *  method, which every PCX writer in practice sets to 1 (RLE) -- a single 0x0A alone is too
     *  common to trust as a signature on its own, so both are checked. */
    private fun isPcx(header: ByteArray): Boolean =
        header.size >= 3 && header[0] == 0x0A.toByte() && header[2] == 0x01.toByte()

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF87A = "GIF87a".toAsciiBytes()
    private val GIF89A = "GIF89a".toAsciiBytes()
    private val BMP = "BM".toAsciiBytes()
    private val RIFF = "RIFF".toAsciiBytes()
    private val WEBP = "WEBP".toAsciiBytes()
    private val ICO = byteArrayOf(0x00, 0x00, 0x01, 0x00)
    private val FTYP = "ftyp".toAsciiBytes()
    private val JXL_CONTAINER = byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)
    private val TIFF_LE = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    private val TIFF_BE = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
    private val PSD = "8BPS".toAsciiBytes()
    private val EXR = byteArrayOf(0x76, 0x2F, 0x31, 0x01)
    private val HDR = "#?RADIANCE\n".toAsciiBytes()
    private val JP2 = byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A)
    private val JXR = byteArrayOf(0x49, 0x49, 0xBC.toByte())
    private val QOI = "qoif".toAsciiBytes()
    private val DDS = "DDS ".toAsciiBytes()
    private val KTX1 = byteArrayOf(0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val KTX2 = byteArrayOf(0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val ICNS = "icns".toAsciiBytes()
    private val XCF = "gimp xcf".toAsciiBytes()
}

private fun String.toAsciiBytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = regionEquals(0, prefix)

private fun ByteArray.regionEquals(offset: Int, other: ByteArray): Boolean {
    if (offset < 0 || size < offset + other.size) return false
    for (i in other.indices) {
        if (this[offset + i] != other[i]) return false
    }
    return true
}

private fun ByteArray.decodeAsciiRange(start: Int, endExclusive: Int): String =
    buildString(endExclusive - start) {
        for (i in start until endExclusive) append(this@decodeAsciiRange[i].toInt().toChar())
    }
