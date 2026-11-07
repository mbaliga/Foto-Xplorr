package com.fotoxplorr.core.formats.simple

/**
 * RFC 1951 inflate in plain Kotlin (the module has no java.util.zip; it is common code). Used for
 * TIFF Deflate, the PNG inside ORA/KRA/ICNS and ZIP entries. Output is capped at [maxOut] so a
 * decompression bomb ends in an [InvalidImageException], never an OutOfMemoryError. The cap is also
 * held to what the input can possibly expand to (deflate tops out near 1032:1), the buffer starts
 * at the caller's expected size when it knows one, and an exactly full buffer is returned as is, so
 * the peak is the output itself rather than up to three times the cap (growth plus the final copy).
 */
internal object Inflate {

    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** Canonical Huffman table in the "count per length + sorted symbols" form (zlib's puff). */
    private class Huffman(lengths: IntArray, n: Int) {
        val count = IntArray(16)
        val symbol = IntArray(n)

        init {
            for (i in 0 until n) count[lengths[i]]++
            val offs = IntArray(16)
            for (l in 1 until 15) offs[l + 1] = offs[l] + count[l]
            for (i in 0 until n) if (lengths[i] != 0) symbol[offs[lengths[i]]++] = i
        }
    }

    private class Bits(val src: ByteArray, var pos: Int, val end: Int) {
        var buf = 0
        var cnt = 0

        fun need(n: Int): Int {
            while (cnt < n) {
                if (pos >= end) bad("truncated deflate stream")
                buf = buf or ((src[pos++].toInt() and 0xFF) shl cnt)
                cnt += 8
            }
            val v = buf and ((1 shl n) - 1)
            buf = buf ushr n
            cnt -= n
            return v
        }
    }

    /** Most bytes one input byte can become: a 258-byte match costs at least 2 bits (zlib's 1032:1). */
    private const val MAX_RATIO = 1032L

    private class Out(val max: Int, expected: Long) {
        var data = ByteArray(minOf(max.toLong(), if (expected > 0) expected else 1L shl 16).toInt())
        var size = 0

        fun put(b: Int) {
            if (size >= max) bad("deflate output over the limit")
            if (size == data.size) data = data.copyOf(minOf(max.toLong(), data.size * 2L).toInt())
            data[size++] = b.toByte()
        }
    }

    /**
     * Inflates a zlib stream (2-byte header, adler ignored) from [src] starting at [offset]. The
     * result may be shorter than its backing buffer only when [expected] was not met exactly.
     */
    fun zlib(src: ByteArray, offset: Int, length: Int, maxOut: Long, expected: Long = 0): ByteArray {
        if (length < 2) bad("truncated zlib stream")
        val cmf = src.u8(offset)
        val flg = src.u8(offset + 1)
        if (cmf and 0x0F != 8 || ((cmf shl 8) or flg) % 31 != 0 || flg and 0x20 != 0) bad("not a zlib stream")
        return raw(src, offset + 2, length - 2, maxOut, expected)
    }

    /**
     * Inflates a raw deflate stream into at most [maxOut] bytes; [expected] (when positive) is the
     * size the caller knows the output should have, used to size the buffer once.
     */
    fun raw(src: ByteArray, offset: Int, length: Int, maxOut: Long, expected: Long = 0): ByteArray {
        if (offset < 0 || length < 0 || offset.toLong() + length > src.size) bad("truncated")
        val cap = minOf(maxOut, length * MAX_RATIO + 64, Int.MAX_VALUE.toLong() - 8)
        val out = Out(cap.toInt(), expected)
        val bits = Bits(src, offset, offset + length)
        do {
            val last = bits.need(1)
            when (bits.need(2)) {
                0 -> stored(bits, out)
                1 -> codes(bits, out, FIXED_LIT, FIXED_DIST)
                2 -> dynamic(bits, out)
                else -> bad("bad deflate block")
            }
        } while (last == 0)
        return if (out.size == out.data.size) out.data else out.data.copyOf(out.size)
    }

    private val FIXED_LIT: Huffman = Huffman(IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 }, 288)
    private val FIXED_DIST: Huffman = Huffman(IntArray(30) { 5 }, 30)

    private fun stored(bits: Bits, out: Out) {
        bits.buf = 0
        bits.cnt = 0
        if (bits.pos + 4 > bits.end) bad("truncated deflate stream")
        val len = bits.src.le16(bits.pos)
        val nlen = bits.src.le16(bits.pos + 2)
        if (len != nlen.inv() and 0xFFFF) bad("bad stored block")
        bits.pos += 4
        if (bits.pos + len > bits.end) bad("truncated deflate stream")
        for (i in 0 until len) out.put(bits.src[bits.pos + i].toInt())
        bits.pos += len
    }

    private fun decode(bits: Bits, h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..15) {
            code = code or bits.need(1)
            val c = h.count[len]
            if (code - c < first) return h.symbol[index + (code - first)]
            index += c
            first += c
            first = first shl 1
            code = code shl 1
        }
        bad("bad huffman code")
    }

    private fun dynamic(bits: Bits, out: Out) {
        val nlen = bits.need(5) + 257
        val ndist = bits.need(5) + 1
        val ncode = bits.need(4) + 4
        if (nlen > 286 || ndist > 30) bad("bad deflate counts")
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[CL_ORDER[i]] = bits.need(3)
        val cl = Huffman(lengths, 19)
        var idx = 0
        val ll = IntArray(320)
        while (idx < nlen + ndist) {
            val sym = decode(bits, cl)
            if (sym < 16) {
                ll[idx++] = sym
            } else {
                var prev = 0
                val rep: Int
                when (sym) {
                    16 -> { if (idx == 0) bad("bad repeat"); prev = ll[idx - 1]; rep = 3 + bits.need(2) }
                    17 -> rep = 3 + bits.need(3)
                    else -> rep = 11 + bits.need(7)
                }
                if (idx + rep > nlen + ndist) bad("bad repeat")
                repeat(rep) { ll[idx++] = prev }
            }
        }
        if (ll[256] == 0) bad("no end-of-block code")
        codes(bits, out, Huffman(ll, nlen), Huffman(ll.copyOfRange(nlen, nlen + ndist), ndist))
    }

    private fun codes(bits: Bits, out: Out, lit: Huffman, dist: Huffman) {
        while (true) {
            val sym = decode(bits, lit)
            if (sym < 256) {
                out.put(sym)
            } else if (sym == 256) {
                return
            } else {
                val s = sym - 257
                if (s >= 29) bad("bad length symbol")
                val len = LEN_BASE[s] + bits.need(LEN_EXTRA[s])
                val ds = decode(bits, dist)
                if (ds >= 30) bad("bad distance symbol")
                val d = DIST_BASE[ds] + bits.need(DIST_EXTRA[ds])
                if (d > out.size) bad("distance too far back")
                repeat(len) { out.put(out.data[out.size - d].toInt()) }
            }
        }
    }
}
