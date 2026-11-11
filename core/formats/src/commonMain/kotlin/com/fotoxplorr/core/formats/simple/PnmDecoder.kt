package com.fotoxplorr.core.formats.simple

/** Netpbm P1..P6 (bitmap, greymap, pixmap, ASCII and binary), maxval up to 65535 scaled to 8 bits. */
internal object PnmDecoder {

    private class Tokens(val b: ByteArray, var pos: Int) {
        /** Next whitespace-delimited token; `#` comments run to the end of the line. */
        fun next(): String {
            while (true) {
                if (pos >= b.size) bad("truncated PNM header")
                val c = b[pos].toInt() and 0xFF
                if (c == '#'.code) { while (pos < b.size && b[pos] != '\n'.code.toByte()) pos++ }
                else if (c <= 32) pos++
                else break
            }
            val s = pos
            while (pos < b.size && (b[pos].toInt() and 0xFF) > 32 && b[pos] != '#'.code.toByte()) pos++
            if (pos - s > 12) bad("bad PNM token")
            return CharArray(pos - s) { (b[s + it].toInt() and 0xFF).toChar() }.concatToString()
        }

        fun int(): Int = next().toIntOrNull() ?: bad("bad PNM number")
    }

    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (b.size < 3 || b[0] != 'P'.code.toByte() || b[1] !in '1'.code.toByte()..'6'.code.toByte()) bad("not a PNM")
        val kind = b[1] - '0'.code.toByte()
        val t = Tokens(b, 2)
        val w = t.int()
        val h = t.int()
        val bitmap = kind == 1 || kind == 4
        val maxv = if (bitmap) 1 else t.int()
        if (maxv !in 1..65_535) bad("bad PNM maxval")
        val out = allocate(limits, w, h)
        val colour = kind == 3 || kind == 6
        val channels = if (colour) 3 else 1
        val binary = kind >= 4
        if (binary) t.pos++ // exactly one whitespace byte separates the header from the raster
        fun scale(v: Int): Int = if (maxv == 255) v else (v.coerceIn(0, maxv) * 255 + maxv / 2) / maxv

        if (kind == 4) {
            val rowBytes = (w + 7) / 8
            if (t.pos.toLong() + rowBytes.toLong() * h > b.size) bad("PNM data truncated")
            for (y in 0 until h) for (x in 0 until w) {
                val bit = (b[t.pos + y * rowBytes + x / 8].toInt() shr (7 - x % 8)) and 1
                val g = if (bit == 1) 0 else 255
                out[y * w + x] = argb(255, g, g, g)
            }
            return DecodedImage(w, h, out)
        }
        val wide = binary && maxv > 255
        val per = if (wide) 2 else 1
        if (binary && t.pos.toLong() + w.toLong() * h * channels * per > b.size) bad("PNM data truncated")
        fun sample(): Int = when {
            kind == 1 -> {
                // P1 samples may be packed without spaces ("0110"): take one digit at a time.
                while (t.pos < b.size && b[t.pos] != '0'.code.toByte() && b[t.pos] != '1'.code.toByte()) t.pos++
                if (t.pos >= b.size) bad("PNM data truncated")
                b[t.pos++] - '0'.code.toByte()
            }
            !binary -> t.int()
            wide -> { val v = b.be16(t.pos); t.pos += 2; v }
            else -> b.u8(t.pos++)
        }
        for (i in 0 until w * h) {
            if (colour) {
                val r = scale(sample()); val g = scale(sample()); val bl = scale(sample())
                out[i] = argb(255, r, g, bl)
            } else if (bitmap) {
                val g = if (sample() == 1) 0 else 255
                out[i] = argb(255, g, g, g)
            } else {
                val g = scale(sample())
                out[i] = argb(255, g, g, g)
            }
        }
        return DecodedImage(w, h, out)
    }
}
