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
 * ORA and KRA are recognised from the ZIP's first (stored) `mimetype` entry, which both specs
 * pin to the start of the archive. APNG needs a short PNG chunk walk. Proprietary RAW containers
 * that are not TIFF-shaped (CR3, CR2, RAF, ORF, RW2, X3F, MRW, CRW) are identified by their own
 * markers; NEF, ARW, DNG and the other TIFF-based RAWs cannot be told from a TIFF in the first
 * bytes, so they stay with the filename rule in [MediaFormat.classify].
 *
 * Deliberately NOT attempted here, all for the same reason -- no signature confident enough to
 * write down:
 *  - **WBMP, SVGZ**: no magic of their own (SVGZ is a bare gzip stream).
 *  - **MPEG-TS, Ogg**: TS needs a sync byte checked at 188-byte strides, and `OggS` carries audio
 *    just as often as video.
 *  - **TGA**: no reliable signature at the START of the file -- its one optional signature is an
 *    18-byte footer at the END, which this offset-0-bytes-only design can't reach.
 *  - **Softimage PIC**: no independently-confirmed signature found.
 */
object FormatSniffer {
    /** Comfortably covers every check below (the deepest is the ZIP `mimetype` entry of an ORA or
     *  KRA file, which ends near byte 60, and the PNG chunk walk that spots APNG); callers read
     *  this many bytes, or fewer if the file itself is shorter -- [sniff] handles a short header
     *  by simply not matching. */
    const val REQUIRED_HEADER_BYTES = 64

    fun sniff(header: ByteArray): MediaFormat? = when {
        header.startsWith(PNG) -> sniffPng(header)
        header.startsWith(JPEG) -> MediaFormat.Jpeg
        header.startsWith(GIF87A) || header.startsWith(GIF89A) -> MediaFormat.Gif
        header.startsWith(BMP) -> MediaFormat.Bmp
        isRiffWebp(header) -> MediaFormat.WebP
        isRiffAvi(header) -> MediaFormat.Video
        header.startsWith(ICO) -> MediaFormat.Ico
        isIsobmff(header) -> sniffIsobmffBrand(header)
        header.startsWith(MATROSKA) -> MediaFormat.Video
        header.startsWith(FLV) -> MediaFormat.Video
        header.startsWith(ZIP_LOCAL) -> sniffZipDocument(header)
        header.startsWith(RAF) -> MediaFormat.Raw(RawVariant.RAF)
        header.startsWith(X3F) -> MediaFormat.Raw(RawVariant.X3F)
        header.startsWith(MRW) -> MediaFormat.Raw(RawVariant.MRW)
        header.startsWith(CRW) -> MediaFormat.Raw(RawVariant.CRW)
        header.startsWith(RW2) -> MediaFormat.Raw(RawVariant.RW2)
        header.startsWith(ORF_LE_O) || header.startsWith(ORF_LE_S) || header.startsWith(ORF_BE) ->
            MediaFormat.Raw(RawVariant.ORF)
        header.startsWith(TIFF_LE) && header.regionEquals(8, CR2_TAG) -> MediaFormat.Raw(RawVariant.CR2)
        header.size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte() -> MediaFormat.JpegXl
        header.startsWith(JXL_CONTAINER) -> MediaFormat.JpegXl
        header.startsWith(TIFF_LE) || header.startsWith(TIFF_BE) || header.startsWith(BIGTIFF_LE) ||
            header.startsWith(BIGTIFF_BE) -> MediaFormat.Tiff
        header.startsWith(PSD) -> sniffPhotoshop(header)
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

    /** PNG, or APNG when an `acTL` (animation control) chunk shows up before the first `IDAT`.
     *  Walks the chunks that fit in the header; a PNG whose first chunks are too big to reach
     *  `acTL` (a large ICC profile ahead of it) reads as a plain PNG, which is still true of its
     *  first frame -- the extension and mime rules in [MediaFormat.classify] cover that case. */
    private fun sniffPng(header: ByteArray): MediaFormat {
        var offset = PNG.size
        while (offset + 8 <= header.size) {
            val length = header.readUInt32Be(offset)
            when {
                header.regionEquals(offset + 4, ACTL) -> return MediaFormat.Apng
                header.regionEquals(offset + 4, IDAT) -> return MediaFormat.Png
            }
            // length + type(4) + crc(4); a chunk too long for the header ends the walk.
            offset += (length + 12L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (offset < 0) break
        }
        return MediaFormat.Png
    }

    /** `8BPS` followed by a 16-bit version: 1 is PSD, 2 is the large-document PSB. */
    private fun sniffPhotoshop(header: ByteArray): MediaFormat =
        if (header.size >= 6 && header[4] == 0.toByte() && header[5] == 2.toByte()) MediaFormat.Psb else MediaFormat.Psd

    /**
     * ORA and KRA are ZIP files whose FIRST entry is a stored (uncompressed) `mimetype` file --
     * both specs require that precisely so the type can be read from the first bytes. Reads the
     * local file header (name length at 26, extra length at 28), checks the entry is called
     * `mimetype`, and compares its content. Any other ZIP is not claimed.
     */
    private fun sniffZipDocument(header: ByteArray): MediaFormat? {
        if (header.size < 30) return null
        val nameLength = header.readUInt16Le(26)
        val extraLength = header.readUInt16Le(28)
        if (nameLength != MIMETYPE_ENTRY.size || !header.regionEquals(30, MIMETYPE_ENTRY)) return null
        val dataStart = 30 + nameLength + extraLength
        return when {
            header.regionEquals(dataStart, ORA_MIME) -> MediaFormat.Ora
            header.regionEquals(dataStart, KRA_MIME) -> MediaFormat.Kra
            else -> null
        }
    }

    private fun isRiffAvi(header: ByteArray): Boolean =
        header.size >= 12 && header.regionEquals(0, RIFF) && header.regionEquals(8, AVI)

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
            "crx " -> MediaFormat.Raw(RawVariant.CR3)
            // The common MP4 / QuickTime / 3GPP family brands. Deliberately a short list of brands
            // that are video in practice; an audio-only brand (`M4A `) is left unclaimed.
            "isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "M4VH", "mmp4",
            "3gp4", "3gp5", "3gp6", "3g2a", "qt  " -> MediaFormat.Video
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
    private val BIGTIFF_LE = byteArrayOf(0x49, 0x49, 0x2B, 0x00)
    private val BIGTIFF_BE = byteArrayOf(0x4D, 0x4D, 0x00, 0x2B)
    private val ACTL = "acTL".toAsciiBytes()
    private val IDAT = "IDAT".toAsciiBytes()
    private val AVI = "AVI ".toAsciiBytes()
    private val MATROSKA = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val FLV = byteArrayOf(0x46, 0x4C, 0x56, 0x01)
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val MIMETYPE_ENTRY = "mimetype".toAsciiBytes()
    private val ORA_MIME = "image/openraster".toAsciiBytes()
    private val KRA_MIME = "application/x-krita".toAsciiBytes()
    private val RAF = "FUJIFILMCCD-RAW".toAsciiBytes()
    private val X3F = "FOVb".toAsciiBytes()
    private val MRW = byteArrayOf(0x00, 0x4D, 0x52, 0x4D)
    private val CRW = byteArrayOf(0x49, 0x49, 0x1A, 0x00, 0x00, 0x00, 0x48, 0x45, 0x41, 0x50, 0x43, 0x43, 0x44, 0x52)
    private val RW2 = byteArrayOf(0x49, 0x49, 0x55, 0x00)
    private val ORF_LE_O = "IIRO".toAsciiBytes()
    private val ORF_LE_S = "IIRS".toAsciiBytes()
    private val ORF_BE = "MMOR".toAsciiBytes()
    private val CR2_TAG = byteArrayOf(0x43, 0x52, 0x02, 0x00)
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

private fun ByteArray.readUInt32Be(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or (this[offset + 3].toLong() and 0xFF)

private fun ByteArray.readUInt16Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.decodeAsciiRange(start: Int, endExclusive: Int): String =
    buildString(endExclusive - start) {
        for (i in start until endExclusive) append(this@decodeAsciiRange[i].toInt().toChar())
    }
