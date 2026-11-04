package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.raw.ByteSource
import com.fotoxplorr.core.formats.raw.ExtractionLimitException
import com.fotoxplorr.core.formats.raw.Reader
import com.fotoxplorr.core.formats.raw.u16
import com.fotoxplorr.core.formats.raw.u32

/**
 * The two identifiers an iPhone writes that tie files together: [contentIdentifier] is shared by
 * the still and the MOV of one Live Photo, [burstUuid] by every frame of one burst.
 */
data class AppleIdentifiers(val contentIdentifier: String?, val burstUuid: String?) {
    val isEmpty: Boolean get() = contentIdentifier == null && burstUuid == null
}

/**
 * Reads [AppleIdentifiers] from a still (the Apple maker note inside its Exif, JPEG or HEIC) or a
 * MOV (`moov/meta` `keys` + `ilst`, key `com.apple.quicktime.content.identifier`). Pure and bounded
 * like [MotionPhotoLocator]: the maker note is found in the first [XmpLite.HEAD_BYTES], the MOV walk
 * reads box headers and at most 64 KB of keys and items.
 *
 * Maker note layout (ExifTool's Apple table): `"Apple iOS\0"`, a 2-byte version, a byte-order mark,
 * then an IFD at +14 whose offsets count from the start of the note. Tag 0x0011 is the content
 * identifier, 0x000B the burst UUID; both ASCII. Tested on constructed files only; UNVERIFIED on
 * real iPhone files.
 */
object AppleIdentifierReader {

    fun fromStill(source: ByteSource): AppleIdentifiers? = guarded(source) { r ->
        val n = minOf(r.length, XmpLite.HEAD_BYTES.toLong()).toInt()
        val head = r.bytes(0, n) ?: return@guarded null
        val note = XmpLite.indexOf(head, MAKER_NOTE, 0)
        if (note < 0 || note + 16 > head.size) return@guarded null
        val le = when {
            head.isAscii(note + 12, "II") -> true
            head.isAscii(note + 12, "MM") -> false
            else -> return@guarded null
        }
        val ifd = note + 14
        val count = head.u16(ifd, le)
        if (count == 0 || count > 256 || ifd + 2 + count * 12 > head.size) return@guarded null
        var content: String? = null
        var burst: String? = null
        for (i in 0 until count) {
            val e = ifd + 2 + i * 12
            val tag = head.u16(e, le)
            if (tag != TAG_CONTENT_ID && tag != TAG_BURST_UUID) continue
            if (head.u16(e + 2, le) != 2) continue // ASCII only
            val len = head.u32(e + 4, le)
            if (len < 1 || len > 128) continue
            val at = if (len <= 4) e + 8L else note + head.u32(e + 8, le)
            if (at < 0 || at + len > head.size) continue
            val text = asciiValue(head, at.toInt(), len.toInt()) ?: continue
            if (tag == TAG_CONTENT_ID) content = text else burst = text
        }
        AppleIdentifiers(content, burst).takeUnless { it.isEmpty }
    }

    /** The content identifier of a Live Photo MOV, or null. Walks only `moov` and its `meta`. */
    fun fromMovie(source: ByteSource): AppleIdentifiers? = guarded(source) { r ->
        val moov = Boxes.child(r, 0, r.length, "moov") ?: return@guarded null
        val meta = Boxes.child(r, moov.first, moov.second, "meta") ?: return@guarded null
        // QuickTime's meta has no version/flags; ISO's does. Skip 4 zero bytes before a child box.
        var start = meta.first
        val probe = r.bytes(start, 8)
        if (probe != null && probe.u32(0, false) == 0L) start += 4
        val keys = Boxes.child(r, start, meta.second, "keys") ?: return@guarded null
        val ilst = Boxes.child(r, start, meta.second, "ilst") ?: return@guarded null
        val keyIndex = keyIndex(r, keys) ?: return@guarded null
        val value = itemValue(r, ilst, keyIndex) ?: return@guarded null
        AppleIdentifiers(contentIdentifier = value, burstUuid = null)
    }

    /** 1-based index of the content identifier among the `keys` entries. */
    private fun keyIndex(r: Reader, keys: Pair<Long, Long>): Int? {
        val len = keys.second - keys.first
        if (len < 8 || len > MAX_TABLE) return null
        val b = r.bytes(keys.first, len.toInt()) ?: return null
        val count = b.u32(4, false)
        var pos = 8
        for (i in 1..minOf(count, 512L).toInt()) {
            if (pos + 8 > b.size) return null
            // Compared as Long: a hostile size near 2^31 would overflow `pos + size` as an Int.
            val size = b.u32(pos, false)
            if (size < 8 || size > b.size - pos) return null
            if (b.decodeToString(pos + 8, pos + size.toInt()) == CONTENT_KEY) return i
            pos += size.toInt()
        }
        return null
    }

    /** The UTF-8 text of `ilst` item [index] (its `data` box), or null. */
    private fun itemValue(r: Reader, ilst: Pair<Long, Long>, index: Int): String? {
        val len = ilst.second - ilst.first
        if (len < 8 || len > MAX_TABLE) return null
        val b = r.bytes(ilst.first, len.toInt()) ?: return null
        var pos = 0
        while (pos + 8 <= b.size) {
            val size = b.u32(pos, false)
            if (size < 8 || size > b.size - pos) return null
            val end = pos + size.toInt()
            if (b.u32(pos + 4, false) == index.toLong()) {
                var d = pos + 8
                while (d + 16 <= end) {
                    val dSize = b.u32(d, false)
                    if (dSize < 16 || dSize > end - d) return null
                    if (b.isAscii(d + 4, "data")) {
                        return b.decodeToString(d + 16, d + dSize.toInt()).trim().takeIf { it.isNotEmpty() && it.length <= 128 }
                    }
                    d += dSize.toInt()
                }
            }
            pos = end
        }
        return null
    }

    private fun asciiValue(b: ByteArray, at: Int, len: Int): String? {
        var end = at
        while (end < at + len && b[end].toInt() != 0) end++
        return b.decodeToString(at, end).trim().takeIf { it.isNotEmpty() }
    }

    private inline fun <T> guarded(source: ByteSource, block: (Reader) -> T?): T? = try {
        block(Reader(source))
    } catch (_: ExtractionLimitException) {
        null
    }

    const val CONTENT_KEY = "com.apple.quicktime.content.identifier"
    private const val TAG_CONTENT_ID = 0x0011
    private const val TAG_BURST_UUID = 0x000B
    private const val MAX_TABLE = 64L * 1024
    private val MAKER_NOTE = "Apple iOS\u0000".encodeToByteArray()
}

/** ISO-BMFF / QuickTime box walking over a [Reader]: header reads only. */
internal object Boxes {
    /** The payload range (start, end) of the first [type] box directly inside [start, end), or null. */
    fun child(r: Reader, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var pos = start
        var n = 0
        while (pos + 8 <= end && n++ < MAX_BOXES) {
            val h = r.bytes(pos, 8) ?: return null
            var size = h.u32(0, false)
            var header = 8L
            if (size == 1L) {
                val big = r.bytes(pos + 8, 8) ?: return null
                val hi = big.u32(0, false)
                if (hi != 0L) return null // over 4 GiB: not a photo-sized box
                size = big.u32(4, false)
                header = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header || size > end - pos) return null
            if (h.isAscii(4, type)) return (pos + header) to (pos + size)
            pos += size
        }
        return null
    }

    private const val MAX_BOXES = 4096
}
