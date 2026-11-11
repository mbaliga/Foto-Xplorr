package com.fotoxplorr.core.formats.simple

/**
 * Apple ICNS: the largest PNG element (`ic07`..`ic14`, `icp4`..`icp6`); failing that the classic
 * 24-bit RLE colour elements (`is32`/`il32`/`ih32`/`it32`) with their 8-bit mask. JPEG 2000 elements
 * (older icons) are skipped, so a file holding only those is refused.
 */
internal object IcnsDecoder {
    private class Element(val type: String, val start: Int, val length: Int)

    /** PNG elements tried before falling back to the legacy ones; a real icon needs one. */
    private const val MAX_PNG_TRIES = 3

    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (b.size < 16 || !b.startsWithAt(0, ascii("icns"))) bad("not an ICNS")
        val total = minOf(b.be32(4), b.size.toLong()).toInt()
        val elements = ArrayList<Element>()
        var p = 8
        while (p + 8 <= total && elements.size < 256) {
            val len = b.be32(p + 4)
            if (len < 8 || p + len > total) break
            val type = CharArray(4) { (b[p + it].toInt() and 0xFF).toChar() }.concatToString()
            elements.add(Element(type, p + 8, len.toInt() - 8))
            p += len.toInt()
        }
        // Largest PNG first, ranked by the IHDR sizes so only one is decoded (the next on failure).
        val pngs = elements.filter { it.length >= 33 && PngLite.isPng(b, it.start) && b.startsWithAt(it.start + 12, ascii("IHDR")) }
            .sortedByDescending { minOf(b.be32(it.start + 16), 1L shl 20) * minOf(b.be32(it.start + 20), 1L shl 20) }
        for (e in pngs.take(MAX_PNG_TRIES)) {
            runCatching { PngLite.decode(b, e.start, e.length, limits) }.getOrNull()?.let { return it }
        }
        for ((colour, mask, size) in listOf(Triple("it32", "t8mk", 128), Triple("ih32", "h8mk", 48), Triple("il32", "l8mk", 32), Triple("is32", "s8mk", 16))) {
            val c = elements.firstOrNull { it.type == colour } ?: continue
            val m = elements.firstOrNull { it.type == mask }
            return legacy(b, c, m, size, limits)
        }
        bad("no usable ICNS element")
    }

    private fun legacy(b: ByteArray, c: Element, m: Element?, size: Int, limits: SimpleLimits): DecodedImage {
        val n = size * size
        val out = allocate(limits, size, size)
        var p = c.start
        val end = c.start + c.length
        if (size == 128 && b.be32(p) == 0L) p += 4 // it32 carries four zero bytes first
        val planes = Array(3) { ByteArray(n) }
        for (plane in planes) {
            var o = 0
            while (o < n) {
                if (p >= end) bad("ICNS data truncated")
                val k = b.u8(p++)
                if (k < 128) {
                    val count = k + 1
                    if (p + count > end || o + count > n) bad("bad ICNS run")
                    b.copyInto(plane, o, p, p + count); p += count; o += count
                } else {
                    val count = k - 128 + 3
                    if (p >= end || o + count > n) bad("bad ICNS run")
                    val v = b[p++]
                    repeat(count) { plane[o++] = v }
                }
            }
        }
        for (i in 0 until n) {
            val a = if (m != null && m.length >= n) b.u8(m.start + i) else 255
            out[i] = argb(a, planes[0][i].toInt() and 0xFF, planes[1][i].toInt() and 0xFF, planes[2][i].toInt() and 0xFF)
        }
        return DecodedImage(size, size, out)
    }
}
