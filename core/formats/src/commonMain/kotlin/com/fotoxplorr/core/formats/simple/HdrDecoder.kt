package com.fotoxplorr.core.formats.simple

import kotlin.math.pow

/**
 * Radiance RGBE (`.hdr`): flat, old-style RLE and the modern per-component RLE scanlines. The
 * floating-point radiance is tone-mapped to an 8-bit sRGB picture (clamp, sRGB transfer curve) so a
 * tile can show it; the viewer's real HDR path is a separate item, so the extra range is dropped.
 */
internal object HdrDecoder {
    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (!(b.startsWithAt(0, ascii("#?RADIANCE")) || b.startsWithAt(0, ascii("#?RGBE")))) bad("not a Radiance file")
        var pos = 0
        fun line(): String {
            val s = pos
            while (pos < b.size && b[pos] != '\n'.code.toByte()) pos++
            if (pos >= b.size || pos - s > 512) bad("bad Radiance header")
            return CharArray(pos - s) { (b[s + it].toInt() and 0xFF).toChar() }.concatToString().also { pos++ }
        }
        var rgbe = false
        while (true) {
            val l = line().trim()
            if (l.isEmpty()) break
            if (l.startsWith("FORMAT=")) rgbe = l == "FORMAT=32-bit_rle_rgbe"
            if (pos > 8192) bad("bad Radiance header")
        }
        if (!rgbe) bad("Radiance XYZE is not supported")
        val parts = line().trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size != 4 || parts[0].length != 2 || parts[2].length != 2) bad("bad Radiance resolution")
        // Standard orientation is "-Y h +X w"; flips are honoured, a transposed layout is refused.
        if (parts[0][1] != 'Y' || parts[2][1] != 'X') bad("transposed Radiance layout")
        val h = parts[1].toIntOrNull() ?: bad("bad Radiance size")
        val w = parts[3].toIntOrNull() ?: bad("bad Radiance size")
        val out = allocate(limits, w, h)
        val flipY = parts[0][0] == '+'
        val flipX = parts[2][0] == '-'
        val scan = ByteArray(w * 4)
        for (row in 0 until h) {
            pos = readScanline(b, pos, w, scan)
            val y = if (flipY) h - 1 - row else row
            for (x in 0 until w) {
                val o = x * 4
                val e = scan[o + 3].toInt() and 0xFF
                val tx = if (flipX) w - 1 - x else x
                if (e == 0) {
                    out[y * w + tx] = argb(255, 0, 0, 0)
                } else {
                    val f = 2.0.pow(e - 136)
                    out[y * w + tx] = argb(
                        255, encode((scan[o].toInt() and 0xFF) * f),
                        encode((scan[o + 1].toInt() and 0xFF) * f), encode((scan[o + 2].toInt() and 0xFF) * f),
                    )
                }
            }
        }
        return DecodedImage(w, h, out)
    }

    private fun encode(linear: Double): Int {
        val v = linear.coerceIn(0.0, 1.0)
        val s = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    /** Fills [scan] (w*4 RGBE bytes) from [b] at [start]; returns the position after the scanline. */
    private fun readScanline(b: ByteArray, start: Int, w: Int, scan: ByteArray): Int {
        var p = start
        if (w in 8..32_767 && p + 4 <= b.size && b.u8(p) == 2 && b.u8(p + 1) == 2 && b.u8(p + 2) and 0x80 == 0) {
            if ((b.u8(p + 2) shl 8) or b.u8(p + 3) != w) bad("Radiance scanline width mismatch")
            p += 4
            for (c in 0 until 4) {
                var x = 0
                while (x < w) {
                    val n = b.u8(p++)
                    if (n > 128) {
                        val v = b[p++]
                        val count = n - 128
                        if (x + count > w) bad("Radiance run overflow")
                        repeat(count) { scan[(x++) * 4 + c] = v }
                    } else {
                        if (n == 0 || x + n > w || p + n > b.size) bad("Radiance run overflow")
                        repeat(n) { scan[(x++) * 4 + c] = b[p++] }
                    }
                }
            }
            return p
        }
        // Flat pixels, with the old RLE: (1,1,1,n) repeats the previous pixel n << shift times.
        var x = 0
        var shift = 0
        while (x < w) {
            if (p + 4 > b.size) bad("Radiance data truncated")
            if (b.u8(p) == 1 && b.u8(p + 1) == 1 && b.u8(p + 2) == 1 && x > 0) {
                val count = b.u8(p + 3) shl shift
                if (x + count > w) bad("Radiance run overflow")
                repeat(count) { scan.copyInto(scan, x * 4, (x - 1) * 4, x * 4); x++ }
                shift += 8
            } else {
                b.copyInto(scan, x * 4, p, p + 4)
                x++
                shift = 0
            }
            p += 4
        }
        return p
    }
}
