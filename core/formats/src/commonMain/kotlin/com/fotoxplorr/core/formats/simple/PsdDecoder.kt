package com.fotoxplorr.core.formats.simple

/**
 * Adobe Photoshop (PSD, version 1) read as the FLATTENED COMPOSITE at the end of the file, never the
 * layers: RGB, greyscale, indexed and CMYK, 8 or 16 bits, raw or PackBits-RLE. A file saved without
 * "Maximize Compatibility" carries a blank composite and shows as such; that cannot be detected
 * here. PSB (version 2) is a separate format and a placeholder, ZIP-compressed composites are refused.
 */
internal object PsdDecoder {
    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (b.size < 26 || !b.startsWithAt(0, ascii("8BPS"))) bad("not a PSD")
        if (b.be16(4) != 1) bad("only PSD version 1")
        val channels = b.be16(12)
        val h = b.be32(14)
        val w = b.be32(18)
        val depth = b.be16(22)
        val mode = b.be16(24)
        limits.checkSize(w, h)
        if (channels !in 1..56 || depth != 8 && depth != 16) bad("unsupported PSD depth")
        var pos = 26L
        fun section(): Long {
            if (pos + 4 > b.size) bad("PSD truncated")
            val len = b.be32(pos.toInt())
            pos += 4
            if (len < 0 || pos + len > b.size) bad("PSD truncated")
            return len
        }
        val modeLen = section()
        val modeStart = pos.toInt()
        pos += modeLen
        val resources = section()
        pos += resources
        val layers = section()
        pos += layers
        if (pos + 2 > b.size) bad("PSD has no composite")
        val compression = b.be16(pos.toInt())
        pos += 2
        val width = w.toInt()
        val height = h.toInt()
        val needed = when (mode) { 1, 2 -> 1; 3 -> 3; 4 -> 4; else -> bad("unsupported PSD colour mode") }
        if (channels < needed) bad("PSD channel count")
        val used = if ((mode == 3 && channels >= 4) || (mode == 1 && channels >= 2)) needed + 1 else needed
        val out = allocate(limits, width, height)
        val bytesPerSample = depth / 8
        val rowBytes = width * bytesPerSample
        val planes = Array(used) { ByteArray(rowBytes * height) }
        var p = pos.toInt()
        when (compression) {
            0 -> {
                // Raw planar data runs through every channel, used or not; only the first `used` are read.
                if (p.toLong() + rowBytes.toLong() * height * used > b.size) bad("PSD data truncated")
                for (c in 0 until used) { b.copyInto(planes[c], 0, p, p + rowBytes * height); p += rowBytes * height }
            }
            1 -> {
                val counts = IntArray(channels * height)
                if (p.toLong() + counts.size * 2L > b.size) bad("PSD data truncated")
                for (i in counts.indices) counts[i] = b.be16(p + i * 2)
                var dp = p + counts.size * 2
                for (c in 0 until channels) for (y in 0 until height) {
                    val len = counts[c * height + y]
                    if (c < used) unpackBits(b, dp, len, planes[c], y * rowBytes, rowBytes)
                    dp += len
                }
            }
            else -> bad("PSD composite compression not supported")
        }
        fun at(c: Int, i: Int): Int = planes[c][i * bytesPerSample].toInt() and 0xFF // high byte of a 16-bit sample
        val palette = if (mode == 2 && modeLen >= 768) ByteArray(768) { b[modeStart + it] } else null
        if (mode == 2 && palette == null) bad("PSD palette missing")
        for (i in 0 until width * height) {
            out[i] = when (mode) {
                3 -> argb(if (used == 4) at(3, i) else 255, at(0, i), at(1, i), at(2, i))
                1 -> { val g = at(0, i); argb(if (used == 2) at(1, i) else 255, g, g, g) }
                2 -> { val k = at(0, i); argb(255, palette!!.u8(k), palette.u8(256 + k), palette.u8(512 + k)) }
                else -> { // CMYK, stored inverted (255 = no ink)
                    val k = at(3, i)
                    argb(255, at(0, i) * k / 255, at(1, i) * k / 255, at(2, i) * k / 255)
                }
            }
        }
        return DecodedImage(width, height, out)
    }
}

/** Apple PackBits: decodes exactly [expected] bytes into [dst] at [dstOffset] or throws. */
internal fun unpackBits(src: ByteArray, start: Int, length: Int, dst: ByteArray, dstOffset: Int, expected: Int) {
    var p = start
    val end = start + length
    var o = 0
    if (end > src.size) bad("truncated PackBits")
    while (o < expected) {
        if (p >= end) bad("truncated PackBits")
        val n = src[p++].toInt()
        if (n >= 0) {
            val c = n + 1
            if (p + c > end || o + c > expected) bad("bad PackBits run")
            src.copyInto(dst, dstOffset + o, p, p + c)
            p += c; o += c
        } else if (n != -128) {
            val c = 1 - n
            if (p >= end || o + c > expected) bad("bad PackBits run")
            val v = src[p++]
            repeat(c) { dst[dstOffset + o++] = v }
        }
    }
}
