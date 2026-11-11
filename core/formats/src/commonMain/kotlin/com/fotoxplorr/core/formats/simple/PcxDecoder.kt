package com.fotoxplorr.core.formats.simple

/**
 * ZSoft PCX, RLE version 5 style: 8-bit palette (256 colours at the end), 24-bit planar RGB, 32-bit
 * planar RGBA, 1-bit mono and 4-plane 16-colour. The 2- and 4-bit-per-plane CGA modes are refused.
 */
internal object PcxDecoder {
    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (b.size < 129 || b.u8(0) != 0x0A || b.u8(2) != 1) bad("not a PCX")
        val bits = b.u8(3)
        val xmin = b.le16(4); val ymin = b.le16(6); val xmax = b.le16(8); val ymax = b.le16(10)
        val planes = b.u8(65)
        val bpl = b.le16(66)
        if (xmax < xmin || ymax < ymin) bad("bad PCX window")
        val w = xmax - xmin + 1
        val h = ymax - ymin + 1
        val out = allocate(limits, w, h)
        val mode = when {
            bits == 8 && planes == 1 -> 0
            bits == 8 && (planes == 3 || planes == 4) -> 1
            bits == 1 && planes == 1 -> 2
            bits == 1 && planes == 4 -> 3
            else -> bad("unsupported PCX depth")
        }
        if (bpl < (w * bits + 7) / 8) bad("bad PCX line size")
        val lineLen = bpl * planes
        val line = ByteArray(lineLen)
        var pos = 128
        var palette: ByteArray? = null
        if (mode == 0) {
            if (b.size < 129 + 768 || b.u8(b.size - 769) != 0x0C) bad("PCX palette missing")
            palette = b.copyOfRange(b.size - 768, b.size)
        }
        val dataEnd = if (mode == 0) b.size - 769 else b.size
        for (y in 0 until h) {
            var n = 0
            while (n < lineLen) {
                if (pos >= dataEnd) bad("PCX data truncated")
                var v = b.u8(pos++)
                var count = 1
                if (v and 0xC0 == 0xC0) {
                    count = v and 0x3F
                    if (pos >= dataEnd) bad("PCX data truncated")
                    v = b.u8(pos++)
                }
                val m = minOf(count, lineLen - n)
                repeat(m) { line[n++] = v.toByte() }
            }
            for (x in 0 until w) {
                out[y * w + x] = when (mode) {
                    0 -> { val i = (line[x].toInt() and 0xFF) * 3; argb(255, palette!!.u8(i), palette.u8(i + 1), palette.u8(i + 2)) }
                    1 -> argb(
                        if (planes == 4) line[3 * bpl + x].toInt() and 0xFF else 255,
                        line[x].toInt() and 0xFF, line[bpl + x].toInt() and 0xFF, line[2 * bpl + x].toInt() and 0xFF,
                    )
                    2 -> if ((line[x / 8].toInt() shr (7 - x % 8)) and 1 == 1) -1 else 0xFF000000.toInt()
                    else -> {
                        var idx = 0
                        for (p in 0 until 4) idx = idx or (((line[p * bpl + x / 8].toInt() shr (7 - x % 8)) and 1) shl p)
                        argb(255, b.u8(16 + idx * 3), b.u8(17 + idx * 3), b.u8(18 + idx * 3))
                    }
                }
            }
        }
        return DecodedImage(w, h, out)
    }
}
