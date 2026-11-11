package com.fotoxplorr.app.animate

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs

/** PNG container pieces shared by the APNG writer and reader: chunks, filters, the zlib stream. */
internal object PngIo {
    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun be32(out: ByteArray, at: Int, v: Int) {
        out[at] = (v ushr 24).toByte()
        out[at + 1] = (v ushr 16).toByte()
        out[at + 2] = (v ushr 8).toByte()
        out[at + 3] = v.toByte()
    }

    fun readBe32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 255) shl 24) or ((b[at + 1].toInt() and 255) shl 16) or ((b[at + 2].toInt() and 255) shl 8) or (b[at + 3].toInt() and 255)

    fun readBe16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)

    /** One chunk: length, 4-letter type, data, CRC-32 over type and data. */
    fun writeChunk(out: OutputStream, type: String, data: ByteArray, off: Int = 0, len: Int = data.size) {
        val head = ByteArray(8)
        be32(head, 0, len)
        for (i in 0..3) head[4 + i] = type[i].code.toByte()
        out.write(head)
        out.write(data, off, len)
        val crc = CRC32()
        crc.update(head, 4, 4)
        crc.update(data, off, len)
        val tail = ByteArray(4)
        be32(tail, 0, crc.value.toInt())
        out.write(tail)
    }

    /**
     * Filter [raw] (rows of [stride] bytes, [bpp] bytes a pixel) the way PNG wants it: a filter byte
     * in front of each row, chosen per row as the one with the smallest sum of absolute signed
     * bytes (the usual heuristic: small residuals deflate best).
     */
    fun filterRows(raw: ByteArray, stride: Int, rows: Int, bpp: Int): ByteArray {
        val out = ByteArray((stride + 1) * rows)
        val cand = Array(5) { ByteArray(stride) }
        for (y in 0 until rows) {
            val rowAt = y * stride
            val upAt = rowAt - stride
            for (i in 0 until stride) {
                val x = raw[rowAt + i].toInt() and 255
                val a = if (i >= bpp) raw[rowAt + i - bpp].toInt() and 255 else 0
                val b = if (y > 0) raw[upAt + i].toInt() and 255 else 0
                val c = if (y > 0 && i >= bpp) raw[upAt + i - bpp].toInt() and 255 else 0
                cand[0][i] = x.toByte()
                cand[1][i] = (x - a).toByte()
                cand[2][i] = (x - b).toByte()
                cand[3][i] = (x - ((a + b) shr 1)).toByte()
                cand[4][i] = (x - paeth(a, b, c)).toByte()
            }
            var best = 0
            var bestCost = Long.MAX_VALUE
            for (f in 0..4) {
                var cost = 0L
                val row = cand[f]
                for (i in 0 until stride) cost += abs(row[i].toInt())
                if (cost < bestCost) {
                    bestCost = cost
                    best = f
                }
            }
            val o = y * (stride + 1)
            out[o] = best.toByte()
            System.arraycopy(cand[best], 0, out, o + 1, stride)
        }
        return out
    }

    fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Undo the filters of [data] in place; rows are `1 + stride` bytes. Returns false on an unknown filter type. */
    fun unfilterRows(data: ByteArray, offset: Int, stride: Int, rows: Int, bpp: Int): Boolean {
        for (y in 0 until rows) {
            val at = offset + y * (stride + 1)
            val type = data[at].toInt() and 255
            val row = at + 1
            val up = row - (stride + 1)
            for (i in 0 until stride) {
                val a = if (i >= bpp) data[row + i - bpp].toInt() and 255 else 0
                val b = if (y > 0) data[up + i].toInt() and 255 else 0
                val c = if (y > 0 && i >= bpp) data[up + i - bpp].toInt() and 255 else 0
                val x = data[row + i].toInt() and 255
                val v = when (type) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) shr 1)
                    4 -> x + paeth(a, b, c)
                    else -> return false
                }
                data[row + i] = v.toByte()
            }
        }
        return true
    }

    /** Deflate [input] into a zlib stream. */
    fun deflate(input: ByteArray, level: Int): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(input)
            d.finish()
            val buf = ByteArray(maxOf(1024, input.size / 4))
            var out = ByteArray(buf.size)
            var n = 0
            while (!d.finished()) {
                val k = d.deflate(buf)
                if (n + k > out.size) out = out.copyOf(maxOf(out.size * 2, n + k))
                System.arraycopy(buf, 0, out, n, k)
                n += k
            }
            return out.copyOf(n)
        } finally {
            d.end()
        }
    }

    /**
     * Inflate a zlib stream to at most [expected] bytes. Returns the bytes produced and whether the
     * stream ended cleanly having produced exactly that many; a bad or short stream gives what was
     * produced so far and false. Never throws.
     */
    fun inflate(input: ByteArray, len: Int, expected: Int): Pair<ByteArray, Boolean> {
        val inf = Inflater()
        val out = ByteArray(expected)
        var n = 0
        try {
            inf.setInput(input, 0, len)
            while (n < expected && !inf.finished()) {
                val k = inf.inflate(out, n, expected - n)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            return Pair(out, n == expected)
        } catch (e: java.util.zip.DataFormatException) {
            return Pair(out, false)
        } finally {
            inf.end()
        }
    }
}
