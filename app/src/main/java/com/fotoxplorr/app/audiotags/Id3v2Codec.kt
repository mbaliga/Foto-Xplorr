package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Reads ID3v2.2/2.3/2.4 tags and writes ID3v2.3 — MP3's tag format.
 *
 * ## What a write keeps and what it drops
 * [AudioTagCodec.write] resolves edits against the file's EXISTING tags before this codec ever
 * runs (see [mergeTags]), so a field left `null` in an edit keeps its old value. What this codec
 * itself does not do is preserve frames [AudioTags] has no field for at all — a comment, lyrics, a
 * custom `TXXX`, replay-gain data. Every write REBUILDS the tag from scratch containing only the
 * seven frames [AudioTags] models, because a v2.2 file's other frames (3-character IDs) are not
 * valid inside the v2.3 tag this codec always writes, and merging three ID3 sub-versions' frame
 * sets correctly is a meaningfully larger job than this app's tag editor needs to take on. See
 * `docs/audio-playback.md` for this stated as a user-facing limit, not a hidden one.
 */
internal object Id3v2Codec {

    fun read(bytes: ByteArray): AudioTags? {
        val header = parseHeader(bytes) ?: return null
        val frames = parseFrames(bytes, header)
        return framesToTags(frames)
    }

    fun write(input: ByteArray, output: OutputStream, tags: AudioTags) {
        val header = parseHeader(input)
        val audioStart = header?.let { HEADER_SIZE + it.tagSize } ?: 0
        val tagBytes = buildTagV23(tags)
        output.write(tagBytes)
        output.write(input, audioStart, input.size - audioStart)
    }

    // ── Header ──────────────────────────────────────────────────────────────────────────

    internal data class Header(
        val majorVersion: Int,
        val flags: Int,
        /** Body size from the header's own synchsafe field — excludes the 10-byte header and any
         *  v2.4 footer, exactly as the frames below start counting from. */
        val tagSize: Int,
    ) {
        val unsynchronised: Boolean get() = flags and 0x80 != 0
        val hasExtendedHeader: Boolean get() = flags and 0x40 != 0
    }

    internal fun parseHeader(bytes: ByteArray): Header? {
        if (bytes.size < HEADER_SIZE) return null
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return null
        val major = bytes[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = bytes[5].toInt() and 0xFF
        val size = synchsafeDecode(bytes, 6)
        if (HEADER_SIZE + size > bytes.size) return null
        return Header(major, flags, size)
    }

    private fun synchsafeDecode(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun synchsafeEncode(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 21) and 0x7F)
        out.write((value ushr 14) and 0x7F)
        out.write((value ushr 7) and 0x7F)
        out.write(value and 0x7F)
    }

    /** Removes ID3's unsynchronisation scheme: every `0xFF 0x00` pair in the stored bytes is a
     *  literal `0xFF` with a zero byte inserted after it purely to break a false MPEG frame sync.
     *  Reversing it is a single linear pass — the zero can never legitimately follow a `0xFF` any
     *  other way once this scheme has been applied. */
    internal fun removeUnsynchronization(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            out.write(b.toInt())
            if (b == 0xFF.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0.toByte()) {
                i += 2 // the inserted 0x00 is consumed, never written
            } else {
                i += 1
            }
        }
        return out.toByteArray()
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────

    internal data class Frame(val id: String, val data: ByteArray)

    internal fun parseFrames(bytes: ByteArray, header: Header): List<Frame> {
        var body = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + header.tagSize)
        if (header.unsynchronised) body = removeUnsynchronization(body)

        var pos = 0
        if (header.hasExtendedHeader) {
            pos += if (header.majorVersion >= 4) {
                synchsafeDecode(body, 0) // v2.4: size includes itself
            } else {
                body.beInt32(0) + 4 // v2.3: size EXCLUDES its own 4-byte field
            }
        }

        val frames = mutableListOf<Frame>()
        val idLength = if (header.majorVersion == 2) 3 else 4
        val sizeLength = if (header.majorVersion == 2) 3 else 4

        while (pos + idLength + sizeLength <= body.size) {
            if (body[pos] == 0.toByte()) break // padding: nothing but zero bytes from here on

            val id = String(body, pos, idLength, Charsets.US_ASCII)
            pos += idLength
            val size = if (header.majorVersion == 2) {
                body.beUInt24(pos)
            } else if (header.majorVersion == 4) {
                synchsafeDecode(body, pos)
            } else {
                body.beInt32(pos)
            }
            pos += sizeLength

            var frameFlags = 0
            if (header.majorVersion >= 3) {
                frameFlags = body.beUShort(pos)
                pos += 2
            }

            if (size < 0 || pos + size > body.size) break
            var data = body.copyOfRange(pos, pos + size)
            pos += size

            if (header.majorVersion == 4) {
                data = destageV24FrameData(data, frameFlags)
            }

            frames += Frame(id, data)
        }
        return frames
    }

    /** Undoes the two v2.4-only, per-frame transforms this codec supports: an optional grouping
     *  byte to skip, and a data-length-indicator (a synchsafe original-size field, present when
     *  the frame is also individually unsynchronised) ahead of the real payload. Compression and
     *  encryption flags are intentionally not handled — vanishingly rare in the wild and, if this
     *  ever meets one, this returns the frame's compressed/encrypted bytes unchanged, so a caller
     *  reads a value that doesn't decode as text rather than crashing on it. */
    private fun destageV24FrameData(data: ByteArray, flags: Int): ByteArray {
        var pos = 0
        var payload = data
        if (flags and 0x40 != 0 && pos < payload.size) pos += 1 // grouping identity byte
        val hasDataLengthIndicator = flags and 0x01 != 0
        if (hasDataLengthIndicator) pos += 4 // synchsafe original size, not needed to decode
        if (pos > 0) payload = payload.copyOfRange(pos.coerceAtMost(payload.size), payload.size)
        if (flags and 0x02 != 0) payload = removeUnsynchronization(payload) // per-frame unsync
        return payload
    }

    // ── Frame <-> AudioTags ─────────────────────────────────────────────────────────────

    private val TITLE_IDS = setOf("TIT2", "TT2")
    private val ARTIST_IDS = setOf("TPE1", "TP1")
    private val ALBUM_IDS = setOf("TALB", "TAL")
    private val YEAR_IDS = setOf("TYER", "TYE", "TDRC")
    private val TRACK_IDS = setOf("TRCK", "TRK")
    private val GENRE_IDS = setOf("TCON", "TCO")
    private val COVER_IDS = setOf("APIC", "PIC")

    private fun framesToTags(frames: List<Frame>): AudioTags {
        fun textOf(ids: Set<String>) = frames.firstOrNull { it.id in ids }?.let { decodeId3Text(it.data) }
        val cover = frames.firstOrNull { it.id in COVER_IDS }?.let {
            if (it.id == "PIC") decodePic(it.data) else decodeApic(it.data)
        }
        return AudioTags(
            title = textOf(TITLE_IDS)?.takeIf(String::isNotBlank),
            artist = textOf(ARTIST_IDS)?.takeIf(String::isNotBlank),
            album = textOf(ALBUM_IDS)?.takeIf(String::isNotBlank),
            year = textOf(YEAR_IDS)?.let { parseLeadingYear(it) },
            trackNumber = textOf(TRACK_IDS)?.let { parseLeadingInt(it) },
            genre = textOf(GENRE_IDS)?.let(::resolveGenre)?.takeIf(String::isNotBlank),
            coverArt = cover?.first,
        )
    }

    private fun buildTagV23(tags: AudioTags): ByteArray {
        val frames = ByteArrayOutputStream()
        tags.title?.let { writeTextFrame(frames, "TIT2", it) }
        tags.artist?.let { writeTextFrame(frames, "TPE1", it) }
        tags.album?.let { writeTextFrame(frames, "TALB", it) }
        tags.year?.let { writeTextFrame(frames, "TYER", "%04d".format(it)) }
        tags.trackNumber?.let { writeTextFrame(frames, "TRCK", it.toString()) }
        tags.genre?.let { writeTextFrame(frames, "TCON", it) }
        tags.coverArt?.let { writeApicFrame(frames, it) }

        val frameBytes = frames.toByteArray()
        val padded = frameBytes.size + PADDING_BYTES

        val header = ByteArrayOutputStream()
        header.write('I'.code); header.write('D'.code); header.write('3'.code)
        header.write(3); header.write(0) // version 2.3.0
        header.write(0) // flags: no unsynchronisation, no extended header
        synchsafeEncode(header, padded)

        val out = ByteArrayOutputStream(HEADER_SIZE + padded)
        out.write(header.toByteArray())
        out.write(frameBytes)
        out.write(ByteArray(PADDING_BYTES))
        return out.toByteArray()
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, id: String, text: String) {
        val body = encodeId3Text(text)
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.writeBeInt32(body.size)
        out.writeBeInt16(0) // frame flags: none
        out.write(body)
    }

    private fun writeApicFrame(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val body = encodeApic(imageBytes)
        out.write("APIC".toByteArray(Charsets.US_ASCII))
        out.writeBeInt32(body.size)
        out.writeBeInt16(0)
        out.write(body)
    }

    private const val HEADER_SIZE = 10
    private const val PADDING_BYTES = 512
}

// ── Text encoding ──────────────────────────────────────────────────────────────────────

/** Decodes an ID3v2 text frame's body: a 1-byte encoding indicator followed by the string,
 *  optionally null-terminated. Trailing terminators are stripped; a frame with no terminator at
 *  all (common when a writer sizes the frame exactly to its content) decodes identically. */
internal fun decodeId3Text(data: ByteArray): String {
    if (data.isEmpty()) return ""
    val encoding = data[0].toInt() and 0xFF
    val payload = data.copyOfRange(1, data.size)
    return when (encoding) {
        1 -> decodeUtf16(payload, hasBom = true)
        2 -> decodeUtf16(payload, hasBom = false)
        3 -> String(payload, Charsets.UTF_8).trimEnd(' ')
        else -> String(payload, Charsets.ISO_8859_1).trimEnd(' ')
    }
}

private fun decodeUtf16(payload: ByteArray, hasBom: Boolean): String {
    if (payload.isEmpty()) return ""
    val charset = if (hasBom) Charsets.UTF_16 else Charsets.UTF_16BE
    val terminator = payload.indexOfUtf16Terminator(0)
    val trimmed = if (terminator >= 0) payload.copyOfRange(0, terminator) else payload
    return runCatching { String(trimmed, charset) }.getOrDefault("")
}

/** Encodes for a v2.3 write: ISO-8859-1 (encoding 0) whenever every character fits in it — the
 *  overwhelmingly common case, and the most widely-compatible ID3v2.3 encoding — otherwise UTF-16
 *  with a byte-order mark (encoding 1; v2.3 has no UTF-8 text frame encoding at all, unlike v2.4).
 *  Neither branch writes a null terminator: this codec always sets the frame's size field to
 *  exactly the encoded bytes, so a terminator would only be redundant, never load-bearing. */
internal fun encodeId3Text(text: String): ByteArray {
    val isLatin1 = text.all { it.code <= 0xFF }
    return if (isLatin1) {
        byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
    } else {
        byteArrayOf(1) + text.toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }
    }
}

private fun parseLeadingYear(text: String): Int? =
    Regex("\\d{4}").find(text)?.value?.toIntOrNull()

private fun parseLeadingInt(text: String): Int? =
    Regex("\\d+").find(text)?.value?.toIntOrNull()

// ── Genre (ID3v1 numeric references embedded in TCON/TCO) ─────────────────────────────

private fun resolveGenre(raw: String): String {
    val trimmed = raw.trim()
    val match = Regex("^\\((\\d+)\\)(.*)$|^(\\d+)$").find(trimmed) ?: return trimmed
    val number = (match.groupValues[1].ifEmpty { null } ?: match.groupValues[3].ifEmpty { null })?.toIntOrNull()
    val remainder = match.groupValues[2].trim()
    val looked = number?.let { ID3V1_GENRES.getOrNull(it) }
    return remainder.ifEmpty { looked ?: trimmed }
}

// ── Cover art (APIC / PIC) ──────────────────────────────────────────────────────────────

/** @return image bytes to MIME type, or null if the frame is too short to contain one. */
internal fun decodeApic(data: ByteArray): Pair<ByteArray, String>? {
    if (data.isEmpty()) return null
    val encoding = data[0].toInt() and 0xFF
    val mimeEnd = data.indexOf(0, 1)
    if (mimeEnd < 0) return null
    val mime = String(data, 1, mimeEnd - 1, Charsets.ISO_8859_1).ifBlank { "image/jpeg" }
    var pos = mimeEnd + 1 + 1 // skip terminator, then the 1-byte picture type
    pos = skipEncodedString(data, pos, encoding)
    if (pos > data.size) return null
    return data.copyOfRange(pos, data.size) to mime
}

/** @return image bytes to MIME type derived from the v2.2 3-character image-format code. */
internal fun decodePic(data: ByteArray): Pair<ByteArray, String>? {
    if (data.size < 5) return null
    val encoding = data[0].toInt() and 0xFF
    val format = String(data, 1, 3, Charsets.ISO_8859_1).trim().uppercase()
    var pos = 4 + 1 // format code, then the 1-byte picture type
    pos = skipEncodedString(data, pos, encoding)
    if (pos > data.size) return null
    val mime = if (format == "PNG") "image/png" else "image/jpeg"
    return data.copyOfRange(pos, data.size) to mime
}

private fun skipEncodedString(data: ByteArray, start: Int, encoding: Int): Int {
    return if (encoding == 1 || encoding == 2) {
        val terminator = data.indexOfUtf16Terminator(start)
        if (terminator < 0) data.size else terminator + 2
    } else {
        val terminator = data.indexOf(0, start)
        if (terminator < 0) data.size else terminator + 1
    }
}

internal fun encodeApic(imageBytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0) // description encoding: ISO-8859-1
    out.write(sniffImageMime(imageBytes).toByteArray(Charsets.ISO_8859_1))
    out.write(0) // MIME terminator
    out.write(3) // picture type: "Cover (front)"
    out.write(0) // empty description + its terminator
    out.write(imageBytes)
    return out.toByteArray()
}
