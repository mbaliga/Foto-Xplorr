package com.fotoxplorr.core.formats.simple

/**
 * Truevision TGA: types 1/2/3 (colour-mapped, true-colour, grey) and their RLE twins 9/10/11,
 * 8/15/16/24/32 bits per pixel, any of the four origins. TGA has no magic number, so [looksLikeTga]
 * is a header-plausibility check and the caller should also have the `.tga` extension or mime.
 */
internal object TgaDecoder {

    fun looksLikeTga(b: ByteArray): Boolean {
        if (b.size < 18) return false
        val cmType = b[1].toInt()
        val type = b[2].toInt()
        if (cmType !in 0..1 || type !in intArrayOf(1, 2, 3, 9, 10, 11)) return false
        val w = b.le16(12)
        val h = b.le16(14)
        val bpp = b.u8(16)
        if (w == 0 || h == 0 || bpp !in intArrayOf(8, 15, 16, 24, 32)) return false
        if ((type == 1 || type == 9) != (cmType == 1)) return false
        // The TRUEVISION-XFILE footer would be conclusive but is absent from TGA v1 files: not required.
        return true
    }

    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (!looksLikeTga(b)) bad("not a TGA")
        val idLen = b.u8(0)
        val type = b.u8(2)
        val cmFirst = b.le16(3)
        val cmLen = b.le16(5)
        val cmBits = b.u8(7)
        val w = b.le16(12)
        val h = b.le16(14)
        val bpp = b.u8(16)
        val desc = b.u8(17)
        val rle = type >= 9
        val kind = if (type >= 9) type - 8 else type
        val out = allocate(limits, w, h)

        var pos = 18 + idLen
        var palette = IntArray(0)
        if (b.u8(1) == 1) {
            if (cmBits !in intArrayOf(15, 16, 24, 32) || cmLen > 65_535) bad("bad TGA colour map")
            val entryBytes = (cmBits + 7) / 8
            palette = IntArray(cmFirst + cmLen)
            // Map entries carry alpha only when the descriptor declares attribute bits, as for pixels.
            val mapAlpha = desc and 0x0F > 0
            for (i in 0 until cmLen) palette[cmFirst + i] = colour(b, pos + i * entryBytes, cmBits, mapAlpha)
            pos += cmLen * entryBytes
        } else if (kind == 1) bad("colour-mapped TGA without a map")
        if (kind == 1 && bpp != 8) bad("unsupported TGA depth")
        if (kind == 3 && bpp != 8 && bpp != 16) bad("unsupported TGA depth")
        if (kind == 2 && bpp == 8) bad("unsupported TGA depth")
        val bytesPerPixel = (bpp + 7) / 8
        val alphaBits = desc and 0x0F
        val topFirst = desc and 0x20 != 0
        val rightFirst = desc and 0x10 != 0

        fun pixelAt(p: Int): Int = when (kind) {
            1 -> palette.getOrElse(b.u8(p)) { 0xFF000000.toInt() }
            3 -> if (bpp == 8) { val g = b.u8(p); argb(255, g, g, g) } else { val g = b.u8(p); argb(b.u8(p + 1), g, g, g) }
            else -> colour(b, p, bpp, alphaBits > 0)
        }

        var index = 0
        val total = w * h
        fun store(color: Int) {
            val row = index / w
            val col = index % w
            val y = if (topFirst) row else h - 1 - row
            val x = if (rightFirst) w - 1 - col else col
            out[y * w + x] = color
            index++
        }
        if (!rle) {
            if (pos.toLong() + total.toLong() * bytesPerPixel > b.size) bad("TGA data truncated")
            while (index < total) { store(pixelAt(pos)); pos += bytesPerPixel }
        } else {
            while (index < total) {
                val head = b.u8(pos++)
                val n = (head and 0x7F) + 1
                if (head and 0x80 != 0) {
                    val c = pixelAt(pos)
                    pos += bytesPerPixel
                    repeat(minOf(n, total - index)) { store(c) }
                } else {
                    repeat(n) {
                        val c = pixelAt(pos)
                        pos += bytesPerPixel
                        if (index < total) store(c)
                    }
                }
            }
        }
        return DecodedImage(w, h, out)
    }

    /** One true-colour pixel (15/16/24/32 bits, stored B G R [A]); alpha only when [useAlpha]. */
    private fun colour(b: ByteArray, p: Int, bits: Int, useAlpha: Boolean): Int = when (bits) {
        15, 16 -> {
            val v = b.le16(p)
            val r = (v shr 10) and 31
            val g = (v shr 5) and 31
            val bl = v and 31
            argb(if (bits == 16 && useAlpha && v and 0x8000 == 0) 0 else 255, r * 255 / 31, g * 255 / 31, bl * 255 / 31)
        }
        24 -> argb(255, b.u8(p + 2), b.u8(p + 1), b.u8(p))
        else -> argb(if (useAlpha) b.u8(p + 3) else 255, b.u8(p + 2), b.u8(p + 1), b.u8(p))
    }
}
