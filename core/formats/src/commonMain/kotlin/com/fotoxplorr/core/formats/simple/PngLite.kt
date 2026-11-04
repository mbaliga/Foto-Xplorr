package com.fotoxplorr.core.formats.simple

/**
 * A small PNG reader for the PNGs INSIDE other containers (ICNS elements, ORA/KRA merged images).
 * Every colour type and depth, Adam7 interlace, tRNS; CRCs are not checked and ancillary chunks are
 * skipped. It is not the app's PNG path: stand-alone PNGs stay with the platform codec.
 */
internal object PngLite {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(b: ByteArray, offset: Int = 0): Boolean = b.startsWithAt(offset, SIGNATURE)

    fun decode(b: ByteArray, offset: Int, length: Int, limits: SimpleLimits): DecodedImage {
        if (length < 33 || !isPng(b, offset)) bad("not a PNG")
        val end = minOf(b.size.toLong(), offset.toLong() + length).toInt()
        var pos = offset + 8
        var width = 0
        var height = 0
        var depth = 0
        var colorType = 0
        var interlace = 0
        var palette = ByteArray(0)
        var trns = ByteArray(0)
        val idat = ArrayList<ByteArray>()
        var idatSize = 0L
        var sawHeader = false
        while (pos + 8 <= end) {
            val len = b.be32(pos)
            val type = b.be32(pos + 4).toInt()
            val data = pos + 8
            if (len < 0 || data + len > end) break
            val n = len.toInt()
            when (type) {
                0x49484452 -> { // IHDR
                    if (n < 13) bad("bad IHDR")
                    width = b.be32(data).also { if (it > limits.maxSide) bad("image too large") }.toInt()
                    height = b.be32(data + 4).also { if (it > limits.maxSide) bad("image too large") }.toInt()
                    depth = b.u8(data + 8)
                    colorType = b.u8(data + 9)
                    interlace = b.u8(data + 12)
                    sawHeader = true
                }
                0x504C5445 -> palette = b.copyOfRange(data, data + n) // PLTE
                0x74524E53 -> trns = b.copyOfRange(data, data + n) // tRNS
                0x49444154 -> { // IDAT
                    idatSize += n
                    if (idatSize > limits.maxInflated) bad("PNG too large")
                    idat.add(b.copyOfRange(data, data + n))
                }
                0x49454E44 -> break // IEND
            }
            pos = data + n + 4
        }
        if (!sawHeader || idat.isEmpty()) bad("PNG has no image data")
        limits.checkSize(width.toLong(), height.toLong())
        val channels = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> bad("bad PNG colour type") }
        val okDepth = when (colorType) {
            0 -> depth in intArrayOf(1, 2, 4, 8, 16)
            3 -> depth in intArrayOf(1, 2, 4, 8)
            else -> depth == 8 || depth == 16
        }
        if (!okDepth || interlace !in 0..1) bad("bad PNG header")
        if (colorType == 3 && palette.isEmpty()) bad("PNG palette missing")

        val joined = ByteArray(idatSize.toInt())
        var at = 0
        for (c in idat) { c.copyInto(joined, at); at += c.size }
        val bpp = maxOf(1, channels * depth / 8)
        val bitsPerPixel = channels * depth
        // Pass layout: Adam7 or a single full pass.
        val passes = if (interlace == 0) listOf(intArrayOf(0, 0, 1, 1)) else listOf(
            intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
            intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2),
        )
        var expected = 0L
        for (p in passes) {
            val pw = (width - p[0] + p[2] - 1) / p[2]
            val ph = (height - p[1] + p[3] - 1) / p[3]
            if (pw > 0 && ph > 0) expected += ph.toLong() * (1 + (pw.toLong() * bitsPerPixel + 7) / 8)
        }
        val raw = Inflate.zlib(joined, 0, joined.size, minOf(limits.maxInflated, expected + 64))
        if (raw.size < expected) bad("PNG data truncated")

        val out = allocate(limits, width, height)
        var rp = 0
        for (p in passes) {
            val pw = (width - p[0] + p[2] - 1) / p[2]
            val ph = (height - p[1] + p[3] - 1) / p[3]
            if (pw <= 0 || ph <= 0) continue
            val stride = ((pw.toLong() * bitsPerPixel + 7) / 8).toInt()
            var prev = ByteArray(stride)
            for (row in 0 until ph) {
                val filter = raw[rp++].toInt() and 0xFF
                val cur = raw.copyOfRange(rp, rp + stride)
                rp += stride
                unfilter(filter, cur, prev, bpp)
                for (col in 0 until pw) {
                    val x = p[0] + col * p[2]
                    val y = p[1] + row * p[3]
                    out[y * width + x] = pixel(cur, col, colorType, depth, palette, trns)
                }
                prev = cur
            }
        }
        return DecodedImage(width, height, out)
    }

    private fun unfilter(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        for (i in cur.indices) {
            val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
            val up = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            val add = when (filter) {
                0 -> 0
                1 -> a
                2 -> up
                3 -> (a + up) / 2
                4 -> {
                    val pa = kotlin.math.abs(up - c)
                    val pb = kotlin.math.abs(a - c)
                    val pc = kotlin.math.abs(a + up - 2 * c)
                    if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c
                }
                else -> bad("bad PNG filter")
            }
            cur[i] = (cur[i] + add).toByte()
        }
    }

    private fun sample(row: ByteArray, index: Int, depth: Int): Int = when (depth) {
        8 -> row[index].toInt() and 0xFF
        16 -> ((row[index * 2].toInt() and 0xFF) shl 8) or (row[index * 2 + 1].toInt() and 0xFF)
        else -> {
            val perByte = 8 / depth
            val byte = row[index / perByte].toInt() and 0xFF
            (byte shr (8 - depth * (index % perByte + 1))) and ((1 shl depth) - 1)
        }
    }

    private fun pixel(row: ByteArray, col: Int, colorType: Int, depth: Int, palette: ByteArray, trns: ByteArray): Int {
        val maxv = (1 shl depth) - 1
        fun to8(v: Int) = if (depth == 16) v shr 8 else if (depth == 8) v else v * 255 / maxv
        when (colorType) {
            0 -> {
                val v = sample(row, col, depth)
                val key = if (trns.size >= 2) trns.be16(0) else -1
                val g = to8(v)
                return argb(if (v == key) 0 else 255, g, g, g)
            }
            2 -> {
                val r = sample(row, col * 3, depth)
                val g = sample(row, col * 3 + 1, depth)
                val bl = sample(row, col * 3 + 2, depth)
                val keyed = trns.size >= 6 && r == trns.be16(0) && g == trns.be16(2) && bl == trns.be16(4)
                return argb(if (keyed) 0 else 255, to8(r), to8(g), to8(bl))
            }
            3 -> {
                val i = sample(row, col, depth)
                if (i * 3 + 2 >= palette.size) return argb(255, 0, 0, 0)
                val a = if (i < trns.size) trns[i].toInt() and 0xFF else 255
                return argb(a, palette[i * 3].toInt() and 0xFF, palette[i * 3 + 1].toInt() and 0xFF, palette[i * 3 + 2].toInt() and 0xFF)
            }
            4 -> {
                val g = to8(sample(row, col * 2, depth))
                return argb(to8(sample(row, col * 2 + 1, depth)), g, g, g)
            }
            else -> return argb(
                to8(sample(row, col * 4 + 3, depth)), to8(sample(row, col * 4, depth)),
                to8(sample(row, col * 4 + 1, depth)), to8(sample(row, col * 4 + 2, depth)),
            )
        }
    }
}
