package com.fotoxplorr.core.formats.simple

/** QOI ("Quite OK Image"), RGB and RGBA; the spec's six ops, the 64-entry index and the end marker. */
internal object QoiDecoder {
    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (b.size < 14 + 8 || !b.startsWithAt(0, ascii("qoif"))) bad("not a QOI")
        val w = b.be32(4)
        val h = b.be32(8)
        val channels = b.u8(12)
        if (channels != 3 && channels != 4) bad("bad QOI channels")
        limits.checkSize(w, h)
        val out = allocate(limits, w.toInt(), h.toInt())
        val index = IntArray(64)
        var r = 0; var g = 0; var bl = 0; var a = 255
        var pos = 14
        val dataEnd = b.size - 8
        var i = 0
        var run = 0
        while (i < out.size) {
            if (run > 0) {
                run--
            } else {
                if (pos >= dataEnd) bad("QOI data truncated")
                val op = b.u8(pos++)
                when {
                    op == 0xFE -> { r = b.u8(pos); g = b.u8(pos + 1); bl = b.u8(pos + 2); pos += 3 }
                    op == 0xFF -> { r = b.u8(pos); g = b.u8(pos + 1); bl = b.u8(pos + 2); a = b.u8(pos + 3); pos += 4 }
                    op shr 6 == 0 -> {
                        val c = index[op]
                        a = c ushr 24; r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; bl = c and 0xFF
                    }
                    op shr 6 == 1 -> {
                        r = (r + ((op shr 4) and 3) - 2) and 0xFF
                        g = (g + ((op shr 2) and 3) - 2) and 0xFF
                        bl = (bl + (op and 3) - 2) and 0xFF
                    }
                    op shr 6 == 2 -> {
                        val dg = (op and 0x3F) - 32
                        val n = b.u8(pos++)
                        r = (r + dg - 8 + ((n shr 4) and 0xF)) and 0xFF
                        g = (g + dg) and 0xFF
                        bl = (bl + dg - 8 + (n and 0xF)) and 0xFF
                    }
                    else -> run = op and 0x3F // RUN: this pixel plus `run` more
                }
                index[(r * 3 + g * 5 + bl * 7 + a * 11) % 64] = argb(a, r, g, bl)
            }
            out[i++] = argb(a, r, g, bl)
        }
        return DecodedImage(w.toInt(), h.toInt(), out)
    }
}
