package com.fotoxplorr.core.formats.raw

/** One 12-byte IFD entry. [abs] is its absolute position in the source. */
internal class TiffEntry(val tag: Int, val type: Int, val count: Long, val raw: ByteArray, val abs: Long)

internal class TiffIfd(val entries: List<TiffEntry>, val next: Long)

/**
 * A TIFF structure inside a window of the source: [base] is the position of its `II`/`MM` header
 * (all offsets inside are relative to it), [limit] the end of the window. Reads go through the
 * bounds-guarded [Reader]; every count and offset read from the file is range-checked before use.
 */
internal class Tiff(
    private val r: Reader,
    val base: Long,
    val limit: Long,
    val le: Boolean,
    val ifd0: Long,
) {
    /** The IFD at [rel] (relative to [base]), or null if it does not fit the window or looks absurd. */
    fun readIfd(rel: Long): TiffIfd? {
        val pos = base + rel
        if (rel < 0 || pos + 2 > limit) return null
        val n = (r.bytes(pos, 2) ?: return null).u16(0, le)
        if (n == 0 || n > MAX_ENTRIES || pos + 2 + n * 12L > limit) return null
        val body = r.bytes(pos + 2, n * 12) ?: return null
        val entries = ArrayList<TiffEntry>(n)
        for (i in 0 until n) {
            val o = i * 12
            val raw = body.copyOfRange(o, o + 12)
            entries += TiffEntry(raw.u16(0, le), raw.u16(2, le), raw.u32(4, le), raw, pos + 2 + o)
        }
        val nextPos = pos + 2 + n * 12L
        val next = if (nextPos + 4 <= limit) r.bytes(nextPos, 4)?.u32(0, le) ?: 0L else 0L
        return TiffIfd(entries, next)
    }

    /** Absolute position of [e]'s value bytes (inline in the entry when it fits 4 bytes), or null. */
    fun dataPos(e: TiffEntry): Long? {
        val size = typeSize(e.type)
        if (size == 0) return null
        val total = e.count * size
        if (total <= 4) return e.abs + 8
        val pos = base + e.raw.u32(8, le)
        return if (pos < limit && total <= limit - pos) pos else null
    }

    /** The [index]th integer of a BYTE/SHORT/LONG/UNDEFINED entry, or null. */
    fun uint(e: TiffEntry, index: Int = 0): Long? {
        val size = typeSize(e.type)
        if (size == 0 || index < 0 || index >= e.count) return null
        if (e.type != 1 && e.type != 3 && e.type != 4 && e.type != 7 && e.type != 13) return null
        val pos = dataPos(e) ?: return null
        val b = r.bytes(pos + index.toLong() * size, size) ?: return null
        return when (size) {
            1 -> b.u8(0).toLong()
            2 -> b.u16(0, le).toLong()
            else -> b.u32(0, le)
        }
    }

    fun orientationOfIfd0(): Int? {
        val ifd = readIfd(ifd0) ?: return null
        val e = ifd.entries.firstOrNull { it.tag == TAG_ORIENTATION } ?: return null
        return uint(e)?.toInt()?.takeIf { it in 1..8 }
    }

    companion object {
        const val MAX_ENTRIES = 4096
        const val TAG_ORIENTATION = 0x0112

        /** Header magics accepted: TIFF 42, Olympus ORF (RO/RS), Panasonic RW2 (0x55). */
        private val MAGICS = intArrayOf(42, 0x4F52, 0x5352, 0x55)

        private val SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

        fun typeSize(type: Int) = if (type in SIZES.indices) SIZES[type] else 0

        fun open(r: Reader, base: Long, limit: Long): Tiff? {
            if (base < 0 || base + 8 > limit) return null
            val h = r.bytes(base, 8) ?: return null
            val le = when {
                h.u8(0) == 'I'.code && h.u8(1) == 'I'.code -> true
                h.u8(0) == 'M'.code && h.u8(1) == 'M'.code -> false
                else -> return null
            }
            if (h.u16(2, le) !in MAGICS) return null
            val ifd0 = h.u32(4, le)
            if (ifd0 < 8 || base + ifd0 + 2 > limit) return null
            return Tiff(r, base, limit, le, ifd0)
        }

        /** A TIFF window with a known byte order and no header magic (vendor MakerNote IFDs). */
        fun window(r: Reader, base: Long, limit: Long, le: Boolean) = Tiff(r, base, limit, le, 0)

        /** Orientation from an in-memory TIFF block starting at [base] in [b] (a JPEG's APP1 payload). */
        fun orientationIn(b: ByteArray, base: Int): Int? {
            if (b.size < base + 8) return null
            val le = when {
                b[base].toInt() == 'I'.code && b[base + 1].toInt() == 'I'.code -> true
                b[base].toInt() == 'M'.code && b[base + 1].toInt() == 'M'.code -> false
                else -> return null
            }
            if (b.u16(base + 2, le) != 42) return null
            val ifd = b.u32(base + 4, le)
            if (ifd < 8 || base + ifd + 2 > b.size) return null
            val o = (base + ifd).toInt()
            val n = b.u16(o, le)
            if (n > MAX_ENTRIES || o + 2 + n * 12 > b.size) return null
            for (i in 0 until n) {
                val e = o + 2 + i * 12
                if (b.u16(e, le) == TAG_ORIENTATION && b.u16(e + 2, le) == 3) {
                    return b.u16(e + 8, le).takeIf { it in 1..8 }
                }
            }
            return null
        }
    }
}
