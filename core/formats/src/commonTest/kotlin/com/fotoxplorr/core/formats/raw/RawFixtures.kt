package com.fotoxplorr.core.formats.raw

/** Growable byte buffer with positional writes, for constructing synthetic RAW containers. */
class Buf(val le: Boolean = true) {
    var bytes = ByteArray(0)
    private fun ensure(n: Int) { if (bytes.size < n) bytes = bytes.copyOf(n) }
    fun u8(at: Int, v: Int): Buf { ensure(at + 1); bytes[at] = v.toByte(); return this }
    fun u16(at: Int, v: Int): Buf {
        if (le) { u8(at, v); u8(at + 1, v shr 8) } else { u8(at, v shr 8); u8(at + 1, v) }
        return this
    }
    fun u32(at: Int, v: Long): Buf {
        if (le) { for (i in 0..3) u8(at + i, (v shr (8 * i)).toInt()) } else { for (i in 0..3) u8(at + i, (v shr (8 * (3 - i))).toInt()) }
        return this
    }
    fun put(at: Int, data: ByteArray): Buf { ensure(at + data.size); data.copyInto(bytes, at); return this }
    fun ascii(at: Int, s: String): Buf = put(at, s.encodeToByteArray())
    fun pad(size: Int): Buf { ensure(size); return this }
    fun source() = ByteArraySource(bytes.copyOf())

    /** Writes an IFD at [at]; entries are (tag, type, count, value-or-offset); returns the end. */
    fun ifd(at: Int, next: Long, vararg e: IntArray): Int {
        u16(at, e.size)
        e.forEachIndexed { i, x ->
            val o = at + 2 + i * 12
            u16(o, x[0]); u16(o + 2, x[1]); u32(o + 4, x[2].toLong())
            if (x[1] == 3 && x[2] == 1) { u16(o + 8, x[3]) } else u32(o + 8, x[3].toLong())
        }
        u32(at + 2 + e.size * 12, next)
        return at + 2 + e.size * 12 + 4
    }
}

/** A minimal but structurally valid baseline JPEG: SOI, optional Exif APP1, SOF0, SOS, filler, EOI. */
fun jpeg(w: Int, h: Int, size: Int = 400, orientation: Int? = null, sof: Int = 0xC0): ByteArray {
    val b = Buf(false)
    var p = 0
    b.u8(0, 0xFF).u8(1, 0xD8); p = 2
    if (orientation != null) {
        // APP1: "Exif\0\0" + big-endian TIFF with one Orientation entry.
        val t = Buf(false)
        t.ascii(0, "MM"); t.u16(2, 42); t.u32(4, 8)
        t.ifd(8, 0, intArrayOf(0x0112, 3, 1, orientation))
        val payload = "Exif\u0000\u0000".encodeToByteArray() + t.bytes
        b.u8(p, 0xFF).u8(p + 1, 0xE1).u16(p + 2, payload.size + 2).put(p + 4, payload)
        p += 4 + payload.size
    }
    b.u8(p, 0xFF).u8(p + 1, sof).u16(p + 2, 11).u8(p + 4, 8).u16(p + 5, h).u16(p + 7, w).u8(p + 9, 1).u8(p + 10, 1).u8(p + 11, 0x11).u8(p + 12, 0)
    p += 13
    b.u8(p, 0xFF).u8(p + 1, 0xDA).u16(p + 2, 8).pad(p + 10)
    p += 10
    val total = maxOf(size, p + 2)
    b.pad(total - 2)
    // Entropy-coded filler without any FF byte.
    for (i in p until total - 2) b.u8(i, 0x41)
    b.u8(total - 2, 0xFF).u8(total - 1, 0xD9)
    return b.bytes
}

/** Records every read so a test can prove nothing was requested outside the source. */
class RecordingSource(private val inner: ByteSource) : ByteSource {
    var violations = 0
    var bytesRead = 0L
    override val length get() = inner.length
    override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        if (position < 0 || count <= 0 || position + count > inner.length) violations++
        val n = inner.read(position, buffer, offset, count)
        if (n > 0) bytesRead += n
        return n
    }
}
