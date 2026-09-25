package com.fotoxplorr.core.formats

/**
 * Whether a file's own BYTES actually animate -- not its MIME type, which cannot tell a static
 * WebP/AVIF from an animated one, and which [com.fotoxplorr.app.media.MediaAsset.isAnimated]
 * (P0-14's own named defect) used to treat as a synonym for "is a GIF, WebP or AVIF at all."
 *
 * [sniff] is pure and Android-free by design, exactly like [GifDecoder] one file over: no image
 * is ever decoded here, only the CONTAINER structure each format already publishes near the start
 * of the file is walked. [com.fotoxplorr.app.formats.AnimationIndex] is the caller that actually
 * reads bytes off disk and persists the result; this object only ever sees what it is handed.
 */
object AnimationSniffer {

    /**
     * @param head a bounded prefix of the file's own bytes -- see [AnimationIndex] for exactly how
     *   much of each format it reads. Not required to be the whole file; GIF specifically is
     *   walked until either a second frame is found, the real end of the file (the trailer byte)
     *   is reached, or [head] itself runs out, whichever comes first.
     * @return `true`/`false` when [mime] is a format this function understands, `null` when it is
     *   not (an unsupported/unrecognised MIME type -- the caller should leave that asset alone
     *   rather than treat "not GIF/WebP/PNG/HEIF-family" as a confident "not animated").
     */
    fun sniff(head: ByteArray, mime: String): Boolean? = when (mime.lowercase()) {
        "image/gif" -> sniffGif(head)
        "image/webp" -> sniffWebp(head)
        "image/png" -> sniffPng(head)
        "image/heif", "image/heic", "image/avif" -> sniffIsoBmffSequence(head)
        else -> null
    }

    /**
     * Walks GIF blocks (extension / image descriptor / trailer -- the same three markers
     * [GifDecoder] recognises, by the same byte values) counting Image Descriptors, WITHOUT
     * decompressing any pixel data -- an animation check never needs to know what a frame looks
     * like, only that a second one exists. Stops as soon as that is known either way:
     *
     * - a second Image Descriptor is seen -> `true`, immediately, without reading the rest;
     * - the Trailer (`0x3B`) is reached having seen at most one -> `false`, a real single-frame GIF;
     * - [head] runs out before either of those -- because the caller only ever hands over a
     *   bounded prefix (1 MiB, see [AnimationIndex]), not necessarily the whole file -- `true`,
     *   conservatively: a partially-read file that has not yet proven itself single-frame is
     *   treated as animated rather than risk showing a real animation as a frozen first frame.
     */
    private fun sniffGif(head: ByteArray): Boolean {
        if (head.size < GIF_HEADER_SIZE || !isGifMagic(head)) return true
        var pos = 6
        // Logical Screen Descriptor: width(2) height(2) packed(1) background(1) aspect(1).
        if (pos + 7 > head.size) return true
        val screenPacked = head[pos + 4].toInt() and 0xFF
        pos += 7
        if (screenPacked and 0x80 != 0) {
            pos += colorTableBytes(screenPacked)
        }

        var imageDescriptors = 0
        while (true) {
            if (pos >= head.size) return true
            when (head[pos].toInt() and 0xFF) {
                EXTENSION_INTRODUCER -> {
                    if (pos + 2 > head.size) return true
                    pos += 2 // introducer + label
                    pos = skipSubBlocks(head, pos) ?: return true
                }
                IMAGE_SEPARATOR -> {
                    imageDescriptors++
                    if (imageDescriptors >= 2) return true
                    // Separator(1) + left(2) + top(2) + width(2) + height(2) + packed(1) = 10.
                    if (pos + 10 > head.size) return true
                    val imagePacked = head[pos + 9].toInt() and 0xFF
                    pos += 10
                    if (imagePacked and 0x80 != 0) {
                        pos += colorTableBytes(imagePacked)
                    }
                    if (pos >= head.size) return true
                    pos += 1 // LZW minimum code size
                    pos = skipSubBlocks(head, pos) ?: return true
                }
                TRAILER -> return false
                else -> return true // malformed/unrecognised -- conservative, same as a truncated read
            }
        }
    }

    private fun colorTableBytes(packedByte: Int): Int = 3 * (1 shl ((packedByte and 0x07) + 1))

    /** Consumes GIF sub-blocks (length byte + that many bytes, terminated by a zero-length one)
     *  starting at [pos]. Returns the position just past the terminator, or null if [head] ran
     *  out before one was found. */
    private fun skipSubBlocks(head: ByteArray, pos: Int): Int? {
        var cursor = pos
        while (true) {
            if (cursor >= head.size) return null
            val size = head[cursor].toInt() and 0xFF
            cursor += 1
            if (size == 0) return cursor
            if (cursor + size > head.size) return null
            cursor += size
        }
    }

    private fun isGifMagic(head: ByteArray): Boolean {
        val magic = asciiAt(head, 0, 6)
        return magic == "GIF87a" || magic == "GIF89a"
    }

    /**
     * WebP's extended format (`VP8X`, always the very first chunk when present) carries a flags
     * byte whose `0x02` bit is the container's own "this file has an ANIM chunk" declaration.
     * Simple WebP (`VP8 `/`VP8L` as the first chunk, no `VP8X` at all) has no animation support at
     * all -- it is a single frame by construction, so absence of `VP8X` is itself a `false`, not
     * an unknown.
     */
    private fun sniffWebp(head: ByteArray): Boolean {
        if (head.size < 21) return false
        if (!matchesAscii(head, 0, "RIFF") || !matchesAscii(head, 8, "WEBP") || !matchesAscii(head, 12, "VP8X")) {
            return false
        }
        val flags = head[20].toInt() and 0xFF
        return flags and 0x02 != 0
    }

    /**
     * APNG's `acTL` (Animation Control) chunk must appear before the first `IDAT` for a decoder
     * to honour it (the spec's own rule -- an `acTL` after `IDAT` is not a valid APNG). Walking
     * chunks in order and asking "which do we see first" is exactly that rule, not a heuristic.
     */
    private fun sniffPng(head: ByteArray): Boolean {
        if (head.size < 8 || !matchesBytes(head, 0, PNG_SIGNATURE)) return false
        var pos = 8
        while (pos + 8 <= head.size) {
            val length = readInt32BE(head, pos)
            if (length < 0) return false // malformed length field; nothing more to trust
            val type = asciiAt(head, pos + 4, 4)
            when (type) {
                "acTL" -> return true
                "IDAT" -> return false
            }
            val next = pos + 8L + length + 4L // length field + type + data + CRC
            if (next > head.size || next < 0) return false
            pos = next.toInt()
        }
        return false
    }

    /**
     * HEIF/AVIF are both ISO base media file format containers. The `ftyp` box's major brand, or
     * any of its listed compatible brands, names an image-SEQUENCE brand (`msf1`, `hevs`, `avis`)
     * when the file holds more than one image -- the same three codes the brief names, checked
     * literally rather than re-derived from the wider ISOBMFF/MIAF brand registry.
     */
    private fun sniffIsoBmffSequence(head: ByteArray): Boolean {
        if (head.size < 16 || !matchesAscii(head, 4, "ftyp")) return false
        val boxSize = readInt32BE(head, 0)
        val majorBrand = asciiAt(head, 8, 4)
        if (majorBrand in ANIMATED_ISO_BRANDS) return true
        val end = if (boxSize in 1..head.size) boxSize else head.size
        var pos = 16 // size(4) + "ftyp"(4) + major_brand(4) + minor_version(4)
        while (pos + 4 <= end) {
            if (asciiAt(head, pos, 4) in ANIMATED_ISO_BRANDS) return true
            pos += 4
        }
        return false
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, expected: String): Boolean {
        if (offset + expected.length > bytes.size) return false
        return asciiAt(bytes, offset, expected.length) == expected
    }

    // decodeToString(), not the JVM-only String(bytes, offset, length, Charsets.US_ASCII)
    // constructor overload (ADR-010 WP1.2: this module also builds for linuxX64/linuxArm64).
    // UTF-8 decoding of bytes already known to be 7-bit ASCII is byte-for-byte identical to an
    // ASCII decode, so this is exact for every caller here (a GIF/PNG/ISOBMFF magic tag).
    private fun asciiAt(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.decodeToString(offset, offset + length)

    /**
     * Raw byte-for-byte comparison, unlike [matchesAscii]: PNG's own file signature deliberately
     * opens with `0x89`, a byte outside the 7-bit ASCII range specifically so a transmission that
     * strips the high bit corrupts it detectably -- decoding it through [Charsets.US_ASCII] would
     * replace that byte with U+FFFD on both a real PNG and on garbage alike, so this checks bytes
     * against [expected] directly instead.
     */
    private fun matchesBytes(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset + expected.size > bytes.size) return false
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i]) return false
        }
        return true
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private const val GIF_HEADER_SIZE = 13 // 6-byte magic + 7-byte logical screen descriptor
    private const val EXTENSION_INTRODUCER = 0x21
    private const val IMAGE_SEPARATOR = 0x2C
    private const val TRAILER = 0x3B
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val ANIMATED_ISO_BRANDS = setOf("msf1", "hevs", "avis")
}
