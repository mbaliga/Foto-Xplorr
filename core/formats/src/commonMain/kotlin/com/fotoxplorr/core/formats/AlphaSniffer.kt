package com.fotoxplorr.core.formats

/**
 * Whether an image file's own BYTES declare transparency -- not its MIME type, which cannot tell
 * an opaque PNG from one with an alpha channel. The viewer uses the answer to decide whether a
 * backdrop (a checkerboard behind the photo) is worth drawing at all.
 *
 * Like [AnimationSniffer] this is pure and Android-free: no pixel is ever decoded, only the header
 * or container structure each format publishes near the start of the file is read. The caller
 * hands over a bounded prefix; at most [MAX_READ_BYTES] of it is ever looked at.
 *
 * This reports what the file DECLARES. A PNG with an alpha channel whose pixels are all opaque
 * still answers `true`, and a GIF whose transparent palette index is never drawn still does too.
 */
object AlphaSniffer {

    /** The most of `head` this object reads; longer input is treated as if cut off here. */
    const val MAX_READ_BYTES: Int = 64 * 1024

    /**
     * @param head a prefix of the file's own bytes; not required to be the whole file.
     * @return `true`/`false` when [mime] is a format this function understands and the bytes
     *   settle the question, `null` when it is not (an unrecognised MIME type, bytes that do not
     *   match the format, or a prefix that ends before the answer is known). The caller should
     *   treat `null` as "do not assume either way", never as `false`.
     */
    fun sniff(head: ByteArray, mime: String): Boolean? {
        val bytes = if (head.size > MAX_READ_BYTES) head.copyOfRange(0, MAX_READ_BYTES) else head
        return when (mime.substringBefore(';').trim().lowercase()) {
            "image/png" -> sniffPng(bytes)
            "image/webp" -> sniffWebp(bytes)
            "image/gif" -> sniffGif(bytes)
            "image/avif", "image/heif", "image/heic" -> sniffIsoBmff(bytes)
            // Both formats can carry transparency per image and are not worth walking: ICO frames
            // are PNG or masked DIBs, SVG is a drawing on an unpainted canvas.
            "image/x-icon", "image/vnd.microsoft.icon", "image/svg+xml" -> true
            // Neither has an alpha channel as far as the viewer's decoders are concerned (a
            // 32-bit BMP's fourth byte is unused padding in practice).
            "image/jpeg", "image/jpg", "image/pjpeg", "image/bmp", "image/x-ms-bmp", "image/x-bmp" -> false
            else -> null
        }
    }

    // ---- PNG ----

    /**
     * IHDR's colour type says it outright for greyscale+alpha (4) and RGBA (6). Greyscale (0),
     * truecolour (2) and palette (3) are transparent only when a `tRNS` chunk precedes the first
     * `IDAT`, so chunks are walked in order until one of those two settles it.
     */
    private fun sniffPng(b: ByteArray): Boolean? {
        if (!matchesBytes(b, 0, PNG_SIGNATURE)) return null
        var pos = 8
        var colourType = -1
        while (pos + 8 <= b.size) {
            val length = readInt32BE(b, pos)
            if (length < 0) return null // malformed length field; nothing more to trust
            val type = asciiAt(b, pos + 4, 4)
            when (type) {
                "IHDR" -> {
                    // width(4) height(4) depth(1) colour type(1) ...
                    if (length < 13 || pos + 8 + 10 > b.size) return null
                    colourType = b[pos + 8 + 9].toInt() and 0xFF
                    if (colourType == 4 || colourType == 6) return true
                    if (colourType != 0 && colourType != 2 && colourType != 3) return null
                }
                "tRNS" -> return if (colourType == -1) null else true
                "IDAT" -> return if (colourType == -1) null else false
                else -> if (colourType == -1) return null // IHDR must come first
            }
            val next = pos + 8L + length + 4L // length field + type + data + CRC
            if (next > b.size) return null
            pos = next.toInt()
        }
        return null
    }

    // ---- WebP ----

    /**
     * Extended WebP (`VP8X`) has an alpha flag (`0x10`) in its flags byte; lossless (`VP8L`) has
     * an `alpha_is_used` bit in its own header; lossy (`VP8 `) alone never has alpha.
     */
    private fun sniffWebp(b: ByteArray): Boolean? {
        if (b.size < 16 || !matchesAscii(b, 0, "RIFF") || !matchesAscii(b, 8, "WEBP")) return null
        return when (asciiAt(b, 12, 4)) {
            "VP8X" -> if (b.size < 21) null else (b[20].toInt() and 0x10) != 0
            "VP8L" -> {
                // chunk data at 20: signature 0x2F, then 14 bits width-1, 14 bits height-1,
                // 1 bit alpha_is_used, 3 bits version -- bit 28 of the little-endian word, which
                // is bit 4 of its fourth byte (offset 24).
                if (b.size < 25 || (b[20].toInt() and 0xFF) != 0x2F) null
                else (b[24].toInt() and 0x10) != 0
            }
            "VP8 " -> false
            else -> null
        }
    }

    // ---- GIF ----

    /**
     * Transparency in a GIF lives in a Graphic Control Extension (`0x21 0xF9`): bit 0 of its packed
     * byte says the frame has a transparent colour index. Blocks are walked, without decompressing
     * any pixel data, until a GCE sets that flag (`true`) or the trailer is reached without one
     * (`false`). A prefix that ends before either is `null`.
     */
    private fun sniffGif(b: ByteArray): Boolean? {
        if (b.size < GIF_HEADER_SIZE) return null
        val magic = asciiAt(b, 0, 6)
        if (magic != "GIF87a" && magic != "GIF89a") return null
        val screenPacked = b[10].toInt() and 0xFF
        var pos = GIF_HEADER_SIZE
        if (screenPacked and 0x80 != 0) pos += colorTableBytes(screenPacked)
        while (true) {
            if (pos >= b.size) return null
            when (b[pos].toInt() and 0xFF) {
                EXTENSION_INTRODUCER -> {
                    if (pos + 2 > b.size) return null
                    val label = b[pos + 1].toInt() and 0xFF
                    if (label == GRAPHIC_CONTROL_LABEL) {
                        // introducer(1) label(1) block size(1) packed(1) ...
                        if (pos + 4 > b.size) return null
                        if (b[pos + 3].toInt() and 0x01 != 0) return true
                    }
                    pos = skipSubBlocks(b, pos + 2) ?: return null
                }
                IMAGE_SEPARATOR -> {
                    // Separator(1) + left(2) + top(2) + width(2) + height(2) + packed(1) = 10.
                    if (pos + 10 > b.size) return null
                    val imagePacked = b[pos + 9].toInt() and 0xFF
                    pos += 10
                    if (imagePacked and 0x80 != 0) pos += colorTableBytes(imagePacked)
                    pos += 1 // LZW minimum code size
                    pos = skipSubBlocks(b, pos) ?: return null
                }
                TRAILER -> return false
                else -> return null // malformed/unrecognised
            }
        }
    }

    private fun colorTableBytes(packedByte: Int): Int = 3 * (1 shl ((packedByte and 0x07) + 1))

    /** Consumes GIF sub-blocks starting at [pos]; the position past the terminator, or null if
     *  [b] ran out first. */
    private fun skipSubBlocks(b: ByteArray, pos: Int): Int? {
        var cursor = pos
        while (true) {
            if (cursor >= b.size) return null
            val size = b[cursor].toInt() and 0xFF
            cursor += 1
            if (size == 0) return cursor
            if (cursor + size > b.size) return null
            cursor += size
        }
    }

    // ---- AVIF / HEIF ----

    /**
     * An ISO base media image has alpha when an auxiliary image property (`auxC`, inside
     * `meta` > `iprp` > `ipco`) names the alpha plane's URN. Boxes are walked by their declared
     * sizes. Every miss is `false` only when the containers involved fit entirely inside the
     * prefix, so a `meta` box that is cut off before its answer is `null`.
     */
    private fun sniffIsoBmff(b: ByteArray): Boolean? {
        if (b.size < 16 || !matchesAscii(b, 4, "ftyp")) return null
        // The file as a whole is never known to be complete, so a missing meta is unknown.
        val meta = walkBoxes(b, 0, b.size).boxes.firstOrNull { it.type == "meta" } ?: return null
        // meta is a full box: version(1) + flags(3) precede its children.
        val iprpWalk = walkBoxes(b, meta.payload + 4, meta.end)
        val iprp = iprpWalk.boxes.firstOrNull { it.type == "iprp" }
            ?: return if (meta.truncated || iprpWalk.ranOut) null else false
        val ipcoWalk = walkBoxes(b, iprp.payload, iprp.end)
        val ipco = ipcoWalk.boxes.firstOrNull { it.type == "ipco" }
            ?: return if (meta.truncated || iprp.truncated || ipcoWalk.ranOut) null else false
        val properties = walkBoxes(b, ipco.payload, ipco.end)
        for (property in properties.boxes) {
            if (property.type == "auxC" && isAlphaAuxType(b, property)) return true
        }
        return if (meta.truncated || iprp.truncated || ipco.truncated || properties.ranOut) null else false
    }

    /** `auxC` is a full box: version/flags(4), then a NUL-terminated aux_type URN. */
    private fun isAlphaAuxType(b: ByteArray, box: Box): Boolean {
        val start = box.payload + 4
        var end = start
        while (end < box.end && b[end].toInt() != 0) end++
        if (end <= start) return false
        val urn = b.decodeToString(start, end).lowercase()
        // "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha" (AVIF/MIAF) or the older HEVC form
        // "urn:mpeg:hevc:2015:auxid:1".
        return urn.endsWith(":auxiliary:alpha") || urn.endsWith(":auxid:1")
    }

    /** [payload] and [end] are absolute offsets, [end] already clamped to the bytes held. */
    private class Box(val type: String, val payload: Int, val end: Int, val truncated: Boolean)

    /** [ranOut]: the walk stopped because a header or a box went past [to], not because [to] was
     *  reached on a box boundary. */
    private class BoxWalk(val boxes: List<Box>, val ranOut: Boolean)

    private fun walkBoxes(b: ByteArray, from: Int, to: Int): BoxWalk {
        val limit = minOf(to, b.size)
        val boxes = ArrayList<Box>()
        var pos = from
        while (pos < limit) {
            if (pos + 8 > limit) return BoxWalk(boxes, ranOut = true)
            var headerLength = 8
            var size = readInt32BE(b, pos).toLong() and 0xFFFFFFFFL
            val type = asciiAt(b, pos + 4, 4)
            if (size == 1L) {
                // 64-bit largesize follows the type; a high word above zero cannot fit in 64 KiB.
                if (pos + 16 > limit) return BoxWalk(boxes, ranOut = true)
                if (readInt32BE(b, pos + 8) != 0) {
                    boxes.add(Box(type, pos + 16, limit, truncated = true))
                    return BoxWalk(boxes, ranOut = true)
                }
                size = readInt32BE(b, pos + 12).toLong() and 0xFFFFFFFFL
                headerLength = 16
            } else if (size == 0L) {
                size = (limit - pos).toLong() // runs to the end of the enclosing box or file
            }
            if (size < headerLength) return BoxWalk(boxes, ranOut = true) // malformed
            val declaredEnd = pos + size
            if (declaredEnd > limit) {
                boxes.add(Box(type, pos + headerLength, limit, truncated = true))
                return BoxWalk(boxes, ranOut = true)
            }
            boxes.add(Box(type, pos + headerLength, declaredEnd.toInt(), truncated = false))
            pos = declaredEnd.toInt()
        }
        return BoxWalk(boxes, ranOut = false)
    }

    // ---- shared byte helpers ----

    private fun matchesAscii(bytes: ByteArray, offset: Int, expected: String): Boolean {
        if (offset + expected.length > bytes.size) return false
        return asciiAt(bytes, offset, expected.length) == expected
    }

    // decodeToString(), not a JVM-only String constructor (this module also builds for
    // linuxX64/linuxArm64). Exact for every caller here: only 7-bit ASCII tags are compared.
    private fun asciiAt(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.decodeToString(offset, offset + length)

    /** Byte-for-byte, because PNG's signature opens with 0x89, which an ASCII decode would mangle. */
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
    private const val GRAPHIC_CONTROL_LABEL = 0xF9
    private const val IMAGE_SEPARATOR = 0x2C
    private const val TRAILER = 0x3B
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
