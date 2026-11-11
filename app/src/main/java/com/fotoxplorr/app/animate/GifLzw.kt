package com.fotoxplorr.app.animate

/**
 * GIF's variable-width LZW, both ways. The stream is a run of codes packed least-significant bit
 * first, 3 to 12 bits wide, starting at `minCodeSize + 1` bits. Code `1 shl minCodeSize` clears the
 * table, the next one ends the data, and the dictionary grows from the one after that. The packed
 * bytes returned and taken here are the raw stream; cutting it into 255-byte sub-blocks is the
 * container's job ([GifBytes]).
 */
internal object GifLzw {
    private const val MAX_BITS = 12
    private const val TABLE_SIZE = 1 shl MAX_BITS
    private const val HASH_SIZE = 8209 // a prime a little over twice the table, so probing stays short

    /** How a decode ended. */
    enum class End {
        /** The expected number of pixels was produced. */
        FULL,

        /** The end-of-information code came first. */
        EOI,

        /** The bytes ran out. */
        DATA_END,

        /** A code that no table could hold. */
        BAD_CODE,
    }

    /** [count] indices written to the output, and why it stopped. */
    class Decoded(val count: Int, val end: End)

    /**
     * Compress [count] palette indices from [indices]. Each must be below `1 shl minCodeSize`.
     * Emits a clear code first and whenever the 4096-entry table fills, as every decoder expects.
     */
    fun encode(indices: ByteArray, count: Int, minCodeSize: Int): ByteArray {
        require(minCodeSize in 2..8) { "LZW minimum code size is 2 to 8" }
        require(count in 1..indices.size)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val out = BitSink(count / 3 + 64)
        val hashKey = IntArray(HASH_SIZE) { -1 }
        val hashCode = IntArray(HASH_SIZE)
        var next = clear + 2
        var bits = minCodeSize + 1
        out.put(clear, bits)
        var prefix = indices[0].toInt() and 255
        for (i in 1 until count) {
            val c = indices[i].toInt() and 255
            val key = (prefix shl 8) or c
            var h = ((key * -1640531535) ushr 19) % HASH_SIZE
            var found = -1
            while (true) {
                val k = hashKey[h]
                if (k == -1) break
                if (k == key) {
                    found = hashCode[h]
                    break
                }
                h++
                if (h == HASH_SIZE) h = 0
            }
            if (found >= 0) {
                prefix = found
                continue
            }
            out.put(prefix, bits)
            if (next < TABLE_SIZE) {
                hashKey[h] = key
                hashCode[h] = next
                // The code just made may be the next one sent, so the width must already cover it.
                if (next == (1 shl bits) && bits < MAX_BITS) bits++
                next++
            } else {
                out.put(clear, bits)
                java.util.Arrays.fill(hashKey, -1)
                next = clear + 2
                bits = minCodeSize + 1
            }
            prefix = c
        }
        out.put(prefix, bits)
        // The decoder adds one entry for the code just sent, which can widen it before the end code.
        if (next < TABLE_SIZE && next == (1 shl bits) && bits < MAX_BITS) bits++
        out.put(eoi, bits)
        return out.finish()
    }

    /**
     * Expand [data] (the raw LZW stream, [dataLen] bytes) into [out], at most [expected] indices.
     * Never throws on bad data: it returns what it produced and why it stopped. A deferred clear
     * (a full table that just stops growing, as some writers do) is handled.
     */
    fun decode(data: ByteArray, dataLen: Int, minCodeSize: Int, out: ByteArray, expected: Int): Decoded {
        if (minCodeSize !in 1..8) return Decoded(0, End.BAD_CODE)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val prefixOf = IntArray(TABLE_SIZE)
        val suffixOf = ByteArray(TABLE_SIZE)
        val stack = ByteArray(TABLE_SIZE + 1)
        var next = clear + 2
        var bits = minCodeSize + 1
        var mask = (1 shl bits) - 1
        var bitBuf = 0
        var bitCnt = 0
        var pos = 0
        var written = 0
        var prev = -1
        var first = 0
        while (written < expected) {
            while (bitCnt < bits) {
                if (pos >= dataLen) return Decoded(written, End.DATA_END)
                bitBuf = bitBuf or ((data[pos++].toInt() and 255) shl bitCnt)
                bitCnt += 8
            }
            val code = bitBuf and mask
            bitBuf = bitBuf ushr bits
            bitCnt -= bits
            if (code == clear) {
                next = clear + 2
                bits = minCodeSize + 1
                mask = (1 shl bits) - 1
                prev = -1
                continue
            }
            if (code == eoi) return Decoded(written, End.EOI)
            if (prev == -1) {
                if (code >= clear) return Decoded(written, End.BAD_CODE)
                out[written++] = code.toByte()
                prev = code
                first = code
                continue
            }
            var sp = 0
            var c = code
            if (code < next) {
                // fall through to expand
            } else if (code == next && next < TABLE_SIZE) {
                stack[sp++] = first.toByte()
                c = prev
            } else {
                return Decoded(written, End.BAD_CODE)
            }
            while (c >= clear) {
                if (c < clear + 2) return Decoded(written, End.BAD_CODE)
                stack[sp++] = suffixOf[c]
                c = prefixOf[c]
            }
            stack[sp++] = c.toByte()
            first = c
            while (sp > 0 && written < expected) out[written++] = stack[--sp]
            if (next < TABLE_SIZE) {
                prefixOf[next] = prev
                suffixOf[next] = first.toByte()
                next++
                if (next == (1 shl bits) && bits < MAX_BITS) {
                    bits++
                    mask = (1 shl bits) - 1
                }
            }
            prev = code
        }
        return Decoded(written, End.FULL)
    }

    /** Least-significant-bit-first bit packing into a growing byte array. */
    private class BitSink(initial: Int) {
        private var buf = ByteArray(initial.coerceAtLeast(64))
        private var n = 0
        private var acc = 0
        private var accBits = 0

        fun put(code: Int, bits: Int) {
            acc = acc or (code shl accBits)
            accBits += bits
            while (accBits >= 8) {
                if (n == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[n++] = acc.toByte()
                acc = acc ushr 8
                accBits -= 8
            }
        }

        fun finish(): ByteArray {
            if (accBits > 0) {
                if (n == buf.size) buf = buf.copyOf(buf.size + 1)
                buf[n++] = acc.toByte()
                acc = 0
                accBits = 0
            }
            return buf.copyOf(n)
        }
    }
}
