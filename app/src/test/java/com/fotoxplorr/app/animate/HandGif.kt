package com.fotoxplorr.app.animate

import java.io.ByteArrayOutputStream

/**
 * A GIF writer for tests that shares no code with the real one: palette indices go out as plain
 * literal LZW codes (a clear code every so often so the code width never grows), the container is
 * assembled byte by byte. That lets a decoder test build exactly the odd file it wants (interlaced,
 * disposal 3, a frame off the canvas, a missing trailer) without trusting [GifLzw] or [GifEncoder].
 */
class HandGif(val width: Int, val height: Int, private val globalTable: IntArray? = null, private val loop: Int? = null) {
    private val out = ByteArrayOutputStream()

    init {
        out.write("GIF89a".toByteArray())
        le16(width)
        le16(height)
        val bits = globalTable?.let { bitsFor(it.size) }
        out.write(if (bits != null) 0x80 or 0x70 or (bits - 1) else 0x70)
        out.write(0)
        out.write(0)
        if (globalTable != null) table(globalTable, bitsFor(globalTable.size))
        if (loop != null) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
            out.write("NETSCAPE2.0".toByteArray())
            out.write(byteArrayOf(3, 1, (loop and 255).toByte(), (loop shr 8).toByte(), 0))
        }
    }

    private fun le16(v: Int) {
        out.write(v and 255)
        out.write((v shr 8) and 255)
    }

    private fun bitsFor(n: Int): Int {
        var b = 1
        while ((1 shl b) < n) b++
        return b
    }

    private fun table(colors: IntArray, bits: Int) {
        for (i in 0 until (1 shl bits)) {
            val c = if (i < colors.size) colors[i] else 0
            out.write((c shr 16) and 255)
            out.write((c shr 8) and 255)
            out.write(c and 255)
        }
    }

    fun gce(disposal: Int, delayCs: Int, transparentIndex: Int = -1): HandGif {
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
        out.write((disposal shl 2) or (if (transparentIndex >= 0) 1 else 0))
        le16(delayCs)
        out.write(if (transparentIndex >= 0) transparentIndex else 0)
        out.write(0)
        return this
    }

    /**
     * An image of [w] x [h] at ([left], [top]) whose rows are given in STORED order (so for an
     * interlaced image the caller passes them interlaced). [localTable] adds a local colour table.
     */
    fun image(left: Int, top: Int, w: Int, h: Int, indices: IntArray, interlaced: Boolean = false, localTable: IntArray? = null, minCode: Int? = null, omitTerminator: Boolean = false): HandGif {
        require(indices.size == w * h)
        out.write(0x2C)
        le16(left)
        le16(top)
        le16(w)
        le16(h)
        val lbits = localTable?.let { bitsFor(it.size) }
        out.write((if (lbits != null) 0x80 or (lbits - 1) else 0) or (if (interlaced) 0x40 else 0))
        if (localTable != null) table(localTable, lbits!!)
        val colors = (localTable ?: globalTable)?.size ?: 4
        val mc = minCode ?: maxOf(2, bitsFor(colors))
        out.write(mc)
        val data = literalLzw(indices, mc)
        var pos = 0
        while (pos < data.size) {
            val n = minOf(255, data.size - pos)
            out.write(n)
            out.write(data, pos, n)
            pos += n
        }
        if (!omitTerminator) out.write(0)
        return this
    }

    fun trailer(): HandGif {
        out.write(0x3B)
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        /** Every pixel a literal code; a clear code every `2^mc - 2` codes so the table never reaches the width bump. */
        fun literalLzw(indices: IntArray, mc: Int): ByteArray {
            val clear = 1 shl mc
            val eoi = clear + 1
            val width = mc + 1
            var acc = 0L
            var n = 0
            val bytes = ByteArrayOutputStream()
            fun put(code: Int) {
                acc = acc or (code.toLong() shl n)
                n += width
                while (n >= 8) {
                    bytes.write((acc and 255).toInt())
                    acc = acc ushr 8
                    n -= 8
                }
            }
            put(clear)
            var sinceClear = 0
            for (v in indices) {
                put(v)
                // The decoder adds an entry per code after the first; a clear before the table fills its width keeps the width fixed.
                if (++sinceClear >= (1 shl mc) - 3) {
                    put(clear)
                    sinceClear = 0
                }
            }
            put(eoi)
            if (n > 0) bytes.write((acc and 255).toInt())
            return bytes.toByteArray()
        }
    }
}
