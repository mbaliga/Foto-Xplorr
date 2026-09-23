package com.fotoxplorr.app.share

import java.io.InputStream
import java.io.OutputStream

/**
 * P0-04: strips location- and identity-carrying metadata from an image, format by format, by
 * parsing its actual container structure rather than trusting a library that can silently no-op.
 *
 * `ExifInterface.saveAttributes()` (what the old `SharePreparer.stripCommonExif` relied on) can
 * only write JPEG, PNG and WebP -- calling it on a HEIC, AVIF, DNG or TIFF file is a silent no-op,
 * and the file goes out with its GPS tag exactly as it arrived. This class instead parses each
 * format's own byte structure and copies through only the segments/chunks that never carry
 * per-photo metadata, dropping the rest -- and reports [StripResult.Unsupported] for anything it
 * does not have a verified-safe parser for, rather than guessing.
 *
 * Deliberately **pure Kotlin** -- [InputStream]/[OutputStream]/[ByteArray] only, no Android import
 * anywhere in this file. Nothing about "which bytes of a JPEG are safe to keep" needs a `Context`,
 * and keeping it Android-free is what makes the round-trip tests plain, fast JUnit rather than
 * Robolectric.
 *
 * Format is detected by magic bytes, never by the caller-supplied MIME type or file extension --
 * both are attacker- and typo-controlled, and this class's entire job is to fail closed rather
 * than trust a label.
 */
object MetadataStripper {

    enum class Format(val mimeType: String, val extension: String) {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        GIF("image/gif", "gif"),
        BMP("image/bmp", "bmp"),
    }

    sealed interface StripResult {
        data class Stripped(val format: Format) : StripResult
        data class Unsupported(val reason: String) : StripResult
    }

    /**
     * Reads all of [input], strips it, and writes the result to [output]. [input] is read fully
     * into memory -- every format below needs to look back (a WebP RIFF size, a GIF trailer) or
     * forward (JPEG's next-marker scan) past what a single streaming pass could hold, and the
     * images this pipeline handles (camera photos, not multi-gigabyte RAW masters) are small
     * enough that this is not a meaningful memory concern.
     */
    fun strip(input: InputStream, output: OutputStream): StripResult {
        val bytes = input.readBytes()
        return stripBytes(bytes, output)
    }

    internal fun stripBytes(bytes: ByteArray, output: OutputStream): StripResult = when {
        isJpeg(bytes) -> stripJpeg(bytes, output)
        isPng(bytes) -> stripPng(bytes, output)
        isWebp(bytes) -> stripWebp(bytes, output)
        isGif(bytes) -> stripGif(bytes, output)
        isBmp(bytes) -> stripBmp(bytes, output)
        else -> StripResult.Unsupported("Unrecognized image format")
    }

    // internal, not private: com.fotoxplorr.app.metadata.isMetadataWritable (P0-08) sniffs the
    // exact same three writable-by-ExifInterface formats by the exact same magic bytes, and
    // reusing these rather than a second hand-typed copy is what keeps the two decisions ("can
    // this app strip metadata from it" and "can this app write metadata into it") from silently
    // drifting apart on a future format addition to only one of them.
    internal fun isJpeg(bytes: ByteArray) =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    internal fun isPng(bytes: ByteArray) =
        bytes.size >= 8 && (0 until 8).all { bytes[it] == PNG_MAGIC[it] }

    internal fun isWebp(bytes: ByteArray) =
        bytes.size >= 12 && isAscii(bytes, 0, "RIFF") && isAscii(bytes, 8, "WEBP")

    private fun isGif(bytes: ByteArray) =
        bytes.size >= 13 && (isAscii(bytes, 0, "GIF87a") || isAscii(bytes, 0, "GIF89a"))

    private fun isBmp(bytes: ByteArray) =
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()

    // ---------------------------------------------------------------------------------------
    // JPEG
    //
    // Keep: SOI, APP0, APP2 whose payload identifies itself as ICC_PROFILE, APP14 whose payload
    // identifies itself as Adobe, DQT, DHT, every SOF marker, DRI, SOS (header + entropy data),
    // EOI. Drop: every other APPn (APP1 -- Exif AND XMP both live there -- APP3-13), COM. Stop at
    // the first EOI and drop everything after it -- a motion-photo MP4 trailer, any other
    // appended data -- rather than copying it through unexamined.
    // ---------------------------------------------------------------------------------------

    private val SOF_MARKERS = (0xC0..0xCF).filterNot { it == 0xC4 || it == 0xC8 || it == 0xCC }.toSet()
    private val ICC_PROFILE_ID = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
    private val ADOBE_ID = "Adobe".toByteArray(Charsets.US_ASCII)

    private fun stripJpeg(bytes: ByteArray, output: OutputStream): StripResult {
        output.write(bytes, 0, 2) // SOI, already matched by isJpeg
        var pos = 2
        while (pos < bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) {
                return StripResult.Unsupported("Malformed JPEG: expected a marker at offset $pos")
            }
            var markerPos = pos
            while (markerPos < bytes.size && bytes[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= bytes.size) return StripResult.Unsupported("Truncated JPEG marker")
            val marker = bytes[markerPos].toInt() and 0xFF
            val markerStart = pos
            val afterMarker = markerPos + 1

            when {
                marker == 0xD9 -> { // EOI: keep it, drop everything after
                    output.write(bytes, markerStart, afterMarker - markerStart)
                    return StripResult.Stripped(Format.JPEG)
                }
                marker == 0x01 || marker in 0xD0..0xD7 -> { // TEM / restart markers: no payload
                    output.write(bytes, markerStart, afterMarker - markerStart)
                    pos = afterMarker
                }
                marker == 0xDA -> { // SOS: header, then raw entropy-coded data
                    val header = readSegmentBounds(bytes, afterMarker)
                        ?: return StripResult.Unsupported("Truncated SOS header")
                    output.write(bytes, markerStart, header.end - markerStart)
                    val scanEnd = scanEntropyData(bytes, header.end, output)
                        ?: return StripResult.Unsupported("Truncated entropy-coded scan data")
                    pos = scanEnd
                }
                else -> { // every other marker carries a 2-byte length
                    val segment = readSegmentBounds(bytes, afterMarker)
                        ?: return StripResult.Unsupported("Truncated JPEG segment at offset $pos")
                    val payloadStart = afterMarker + 2
                    val keep = when (marker) {
                        0xE0 -> true // APP0
                        0xE2 -> hasIdentifier(bytes, payloadStart, segment.end, ICC_PROFILE_ID)
                        0xEE -> hasIdentifier(bytes, payloadStart, segment.end, ADOBE_ID)
                        0xDB, 0xC4, 0xDD -> true // DQT, DHT, DRI
                        in SOF_MARKERS -> true
                        else -> false // every other APPn (Exif/XMP live in APP1) and COM
                    }
                    if (keep) output.write(bytes, markerStart, segment.end - markerStart)
                    pos = segment.end
                }
            }
        }
        return StripResult.Unsupported("JPEG ended without an EOI marker")
    }

    private class SegmentBounds(val end: Int)

    /** Reads a marker segment's 2-byte big-endian length (which counts itself) starting at
     * [lengthOffset], and returns the offset just past the segment -- or null if truncated. */
    private fun readSegmentBounds(bytes: ByteArray, lengthOffset: Int): SegmentBounds? {
        if (lengthOffset + 2 > bytes.size) return null
        val length = ((bytes[lengthOffset].toInt() and 0xFF) shl 8) or (bytes[lengthOffset + 1].toInt() and 0xFF)
        if (length < 2) return null
        val end = lengthOffset + length
        if (end > bytes.size) return null
        return SegmentBounds(end)
    }

    /**
     * Copies entropy-coded scan data verbatim -- including `FF00` stuffed bytes and `FFD0`-`FFD7`
     * restart markers, both of which are part of the scan, not the next marker -- until it finds
     * a real marker (the next scan of a progressive JPEG, or the trailing EOI). Returns the offset
     * of that marker's leading `FF`, or null if the data runs off the end of the file first.
     */
    private fun scanEntropyData(bytes: ByteArray, start: Int, output: OutputStream): Int? {
        var i = start
        while (true) {
            if (i >= bytes.size) return null
            if (bytes[i] != 0xFF.toByte()) {
                output.write(bytes, i, 1)
                i++
                continue
            }
            if (i + 1 >= bytes.size) return null
            val next = bytes[i + 1].toInt() and 0xFF
            when {
                next == 0x00 || next in 0xD0..0xD7 -> { // stuffed byte or restart marker: scan data
                    output.write(bytes, i, 2)
                    i += 2
                }
                next == 0xFF -> { // fill byte before a real marker; copy and keep scanning
                    output.write(bytes, i, 1)
                    i++
                }
                else -> return i // a genuine marker starts here
            }
        }
    }

    private fun hasIdentifier(bytes: ByteArray, start: Int, end: Int, id: ByteArray): Boolean {
        if (start + id.size > end || start + id.size > bytes.size) return false
        for (i in id.indices) if (bytes[start + i] != id[i]) return false
        return true
    }

    // ---------------------------------------------------------------------------------------
    // PNG
    //
    // Chunks are copied through verbatim (so their CRC, which covers only type+data, never needs
    // recomputing) or dropped whole. An unrecognized CRITICAL chunk (uppercase first letter) fails
    // closed as Unsupported -- this class does not know what it contains, so it cannot promise it
    // carries no metadata; an unrecognized ancillary chunk (lowercase first letter) is dropped,
    // the safer default for a chunk this class does not otherwise know about.
    // ---------------------------------------------------------------------------------------

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val PNG_KEEP_CHUNKS = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "pHYs",
        "bKGD", "hIST", "sPLT", "acTL", "fcTL", "fdAT",
    )
    private val PNG_DROP_CHUNKS = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")

    private fun stripPng(bytes: ByteArray, output: OutputStream): StripResult {
        output.write(bytes, 0, 8) // signature, already matched by isPng
        var pos = 8
        while (pos < bytes.size) {
            if (pos + 8 > bytes.size) return StripResult.Unsupported("Truncated PNG chunk header")
            val length = readInt32BE(bytes, pos)
            if (length < 0) return StripResult.Unsupported("Invalid PNG chunk length")
            val typeStart = pos + 4
            val dataStart = typeStart + 4
            val chunkEnd = dataStart + length + 4 // + CRC
            if (chunkEnd > bytes.size) return StripResult.Unsupported("Truncated PNG chunk")
            val chunkType = String(bytes, typeStart, 4, Charsets.US_ASCII)
            val keep = when {
                chunkType in PNG_KEEP_CHUNKS -> true
                chunkType in PNG_DROP_CHUNKS -> false
                chunkType.isNotEmpty() && chunkType[0].isUpperCase() ->
                    return StripResult.Unsupported("Unknown critical PNG chunk: $chunkType")
                else -> false
            }
            if (keep) output.write(bytes, pos, chunkEnd - pos)
            if (chunkType == "IEND") return StripResult.Stripped(Format.PNG)
            pos = chunkEnd
        }
        return StripResult.Unsupported("PNG ended without an IEND chunk")
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    // ---------------------------------------------------------------------------------------
    // WebP
    //
    // A RIFF container of FourCC-tagged chunks. EXIF and XMP ("XMP ") chunks are dropped whole; a
    // VP8X chunk's flag byte has its EXIF (0x08) and XMP (0x04) bits cleared rather than being
    // dropped itself, since it also carries the canvas size other chunks depend on. The RIFF size
    // field is recomputed from what actually ends up in the output.
    // ---------------------------------------------------------------------------------------

    private const val MAX_WEBP_SIZE = 64L * 1024 * 1024

    private fun stripWebp(bytes: ByteArray, output: OutputStream): StripResult {
        if (bytes.size > MAX_WEBP_SIZE) return StripResult.Unsupported("WebP larger than 64MB")
        val chunks = mutableListOf<ByteArray>()
        var pos = 12
        while (pos < bytes.size) {
            if (pos + 8 > bytes.size) return StripResult.Unsupported("Truncated WebP chunk header")
            val fourCc = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readInt32LE(bytes, pos + 4)
            if (size < 0) return StripResult.Unsupported("Invalid WebP chunk size")
            val dataStart = pos + 8
            val paddedEnd = dataStart + size + (size and 1)
            if (paddedEnd > bytes.size) return StripResult.Unsupported("Truncated WebP chunk")
            when (fourCc) {
                "EXIF", "XMP " -> Unit // dropped
                "VP8X" -> {
                    val chunk = bytes.copyOfRange(pos, paddedEnd)
                    if (size >= 1) {
                        val flagsIndex = 8 // fourcc(4) + size(4)
                        chunk[flagsIndex] = (chunk[flagsIndex].toInt() and 0xFF and 0x08.inv() and 0x04.inv()).toByte()
                    }
                    chunks += chunk
                }
                else -> chunks += bytes.copyOfRange(pos, paddedEnd)
            }
            pos = paddedEnd
        }
        val bodySize = 4 + chunks.sumOf { it.size } // "WEBP" + chunks
        output.write(ASCII_RIFF)
        output.write(intToLe32(bodySize))
        output.write(ASCII_WEBP)
        chunks.forEach { output.write(it) }
        return StripResult.Stripped(Format.WEBP)
    }

    private val ASCII_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val ASCII_WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun intToLe32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    // ---------------------------------------------------------------------------------------
    // GIF
    //
    // Image data (descriptor + local color table + LZW sub-blocks) and the Graphic Control
    // Extension (needed for correct disposal/delay/transparency) are always kept. A Comment
    // Extension is always dropped. An Application Extension is kept only when its 11-byte
    // identifier+auth-code names the NETSCAPE2.0 or ANIMEXTS1.0 looping convention -- any other
    // application extension is dropped.
    // ---------------------------------------------------------------------------------------

    private fun stripGif(bytes: ByteArray, output: OutputStream): StripResult {
        output.write(bytes, 0, 6) // GIF87a/GIF89a signature, already matched by isGif
        if (13 > bytes.size) return StripResult.Unsupported("Truncated GIF logical screen descriptor")
        val packed = bytes[10].toInt() and 0xFF
        output.write(bytes, 6, 7) // logical screen descriptor
        var pos = 13
        if ((packed shr 7) and 1 == 1) {
            val gctSize = 3 * (1 shl ((packed and 0x07) + 1))
            if (pos + gctSize > bytes.size) return StripResult.Unsupported("Truncated GIF global color table")
            output.write(bytes, pos, gctSize)
            pos += gctSize
        }
        while (pos < bytes.size) {
            when (val introducer = bytes[pos].toInt() and 0xFF) {
                0x3B -> { // trailer
                    output.write(bytes, pos, 1)
                    return StripResult.Stripped(Format.GIF)
                }
                0x2C -> { // image descriptor
                    val descStart = pos
                    if (pos + 10 > bytes.size) return StripResult.Unsupported("Truncated GIF image descriptor")
                    val imgPacked = bytes[pos + 9].toInt() and 0xFF
                    var next = pos + 10
                    if ((imgPacked shr 7) and 1 == 1) {
                        val lctSize = 3 * (1 shl ((imgPacked and 0x07) + 1))
                        if (next + lctSize > bytes.size) return StripResult.Unsupported("Truncated GIF local color table")
                        next += lctSize
                    }
                    if (next >= bytes.size) return StripResult.Unsupported("Truncated GIF image data")
                    next += 1 // LZW minimum code size
                    next = skipGifSubBlocks(bytes, next) ?: return StripResult.Unsupported("Truncated GIF image data")
                    output.write(bytes, descStart, next - descStart)
                    pos = next
                }
                0x21 -> { // extension
                    if (pos + 2 > bytes.size) return StripResult.Unsupported("Truncated GIF extension")
                    val label = bytes[pos + 1].toInt() and 0xFF
                    val subBlocksStart = pos + 2
                    val blockEnd = skipGifSubBlocks(bytes, subBlocksStart)
                        ?: return StripResult.Unsupported("Truncated GIF extension")
                    val keep = when (label) {
                        0xFE -> false // comment extension
                        0xFF -> isGifLoopExtension(bytes, subBlocksStart)
                        else -> true // graphic control, plain text, anything else
                    }
                    if (keep) output.write(bytes, pos, blockEnd - pos)
                    pos = blockEnd
                }
                else -> return StripResult.Unsupported(
                    "Unrecognized GIF block introducer 0x${introducer.toString(16)}",
                )
            }
        }
        return StripResult.Unsupported("GIF ended without a trailer")
    }

    private fun skipGifSubBlocks(bytes: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= bytes.size) return null
            val length = bytes[pos].toInt() and 0xFF
            pos += 1
            if (length == 0) return pos
            if (pos + length > bytes.size) return null
            pos += length
        }
    }

    private fun isGifLoopExtension(bytes: ByteArray, subBlocksStart: Int): Boolean {
        if (subBlocksStart >= bytes.size) return false
        val firstLength = bytes[subBlocksStart].toInt() and 0xFF
        val idStart = subBlocksStart + 1
        if (firstLength != 11 || idStart + 11 > bytes.size) return false
        val identifier = String(bytes, idStart, 11, Charsets.US_ASCII)
        return identifier == "NETSCAPE2.0" || identifier == "ANIMEXTS1.0"
    }

    // ---------------------------------------------------------------------------------------
    // BMP -- no per-photo location metadata format exists for it; copy the whole file through.
    // ---------------------------------------------------------------------------------------

    private fun stripBmp(bytes: ByteArray, output: OutputStream): StripResult {
        output.write(bytes)
        return StripResult.Stripped(Format.BMP)
    }

    private fun isAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset + text.length > bytes.size) return false
        for (i in text.indices) if (bytes[offset + i] != text[i].code.toByte()) return false
        return true
    }
}
