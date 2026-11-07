package com.fotoxplorr.core.formats.simple

/**
 * Baseline TIFF, first image only, stripped (not tiled), chunky or planar:
 *  - photometric: min-is-white/black grey 1/2/4/8/16 bits, RGB 8/16 bits, palette 1..8 bits;
 *    an extra sample (RGBA, grey+alpha) is alpha only when ExtraSamples says so: 2 is straight,
 *    1 is premultiplied (un-premultiplied here), 0 or a missing tag is other data and the pixel is
 *    opaque;
 *  - compression: none, LZW, Deflate (8 and 32946), PackBits; horizontal predictor for 8-bit data;
 *  - either byte order, the orientation tag is applied.
 * JPEG-in-TIFF, tiles, CMYK/Lab, floating-point and 32-bit samples are refused (the tile then shows
 * the placeholder). An IFD0 marked reduced-resolution (NewSubfileType bit 0) or carrying DNGVersion
 * is refused too: that is a RAW's thumbnail, and the RAW preview route handles the file.
 * Only the tags read below are kept, each with a value cap, so a crafted directory cannot make the
 * parser allocate more than a few MB; 16-bit samples keep their high byte only (all a tile shows).
 */
internal object TiffDecoder {

    /** The tags [decode] reads and the most values each may carry; anything else is skipped unread. */
    private val TAG_CAPS: Map<Int, Int> = buildMap {
        for (t in intArrayOf(254, 256, 257, 258, 259, 262, 274, 277, 278, 284, 317, 322, 324, 338, 339, 0xC612)) put(t, 16)
        put(273, 1 shl 18) // StripOffsets: one per row and plane at most (32768 rows x 5 planes fits)
        put(279, 1 shl 18) // StripByteCounts
        put(320, 3 shl 16) // ColorMap
    }

    /** Most values all kept tags together may hold (8 MB of longs). */
    private const val MAX_TOTAL_VALUES = 1L shl 20

    private class Ifd(val b: ByteArray, val le: Boolean, val offset: Int) {
        val tags = HashMap<Int, LongArray>()

        init {
            if (offset < 8 || offset.toLong() + 2 > b.size) bad("bad TIFF directory")
            val n = b.u16(offset)
            if (offset.toLong() + 2 + n * 12L > b.size) bad("bad TIFF directory")
            var total = 0L
            for (i in 0 until n) {
                val e = offset + 2 + i * 12
                val tag = b.u16(e)
                val cap = TAG_CAPS[tag] ?: continue
                val type = b.u16(e + 2)
                val count = b.u32(e + 4)
                val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 0 }
                if (size == 0 || tag in tags) continue
                if (count > cap) bad("TIFF tag too large")
                total += count
                if (total > MAX_TOTAL_VALUES) bad("TIFF directory too large")
                val bytes = size.toLong() * count
                if (bytes > 4 && b.u32(e + 8) + bytes > b.size) continue
                val at = if (bytes <= 4) e + 8 else b.u32(e + 8).toInt()
                if (type == 5 || type == 10 || type == 12) continue // rationals are not needed
                tags[tag] = LongArray(count.toInt()) { k ->
                    when (size) { 1 -> b.u8(at + k).toLong(); 2 -> b.u16(at + k * 2).toLong(); else -> b.u32(at + k * 4) }
                }
            }
        }

        private fun ByteArray.u16(o: Int): Int = if (le) le16(o) else be16(o)
        private fun ByteArray.u32(o: Int): Long = if (le) le32(o) else be32(o)

        fun one(tag: Int, default: Long): Long = tags[tag]?.firstOrNull() ?: default
        fun many(tag: Int): LongArray? = tags[tag]
    }

    fun decode(b: ByteArray, limits: SimpleLimits): DecodedImage {
        if (b.size < 16) bad("not a TIFF")
        val le = when {
            b.startsWithAt(0, ascii("II")) -> true
            b.startsWithAt(0, ascii("MM")) -> false
            else -> bad("not a TIFF")
        }
        if ((if (le) b.le16(2) else b.be16(2)) != 42) bad("not a TIFF")
        val first = if (le) b.le32(4) else b.be32(4)
        if (first >= b.size) bad("bad TIFF directory")
        val ifd = Ifd(b, le, first.toInt())
        val w = ifd.one(256, 0)
        val h = ifd.one(257, 0)
        limits.checkSize(w, h)
        val width = w.toInt()
        val height = h.toInt()
        val bitsArr = ifd.many(258) ?: longArrayOf(1)
        val spp = ifd.one(277, 1).toInt()
        val compression = ifd.one(259, 1).toInt()
        val photo = ifd.one(262, 1).toInt()
        val planar = ifd.one(284, 1).toInt()
        val predictor = ifd.one(317, 1).toInt()
        val sampleFormat = ifd.one(339, 1).toInt()
        val bits = bitsArr[0].toInt()
        if (ifd.many(322) != null || ifd.many(324) != null) bad("tiled TIFF is not supported")
        // A TIFF-based RAW (DNG, NEF...) often has a small uncompressed thumbnail as IFD0; showing it
        // as "the picture" would be wrong, so leave such files to the RAW preview route.
        if (ifd.one(254, 0) and 1L != 0L || ifd.many(0xC612) != null) bad("reduced-resolution or DNG IFD0")
        if (bitsArr.any { it.toInt() != bits } || sampleFormat != 1) bad("TIFF sample layout not supported")
        if (spp !in 1..5 || planar !in 1..2) bad("TIFF sample layout not supported")
        val colour = when (photo) {
            0, 1 -> 1
            2 -> 3
            3 -> 1
            else -> bad("TIFF colour model not supported")
        }
        if (spp < colour) bad("TIFF sample layout not supported")
        // ExtraSamples: 1 = associated (premultiplied) alpha, 2 = straight alpha, 0 or none = other data.
        val extra = if (spp > colour) ifd.one(338, 0).toInt() else 0
        val alpha = extra == 1 || extra == 2
        val okBits = if (photo == 2) bits == 8 || bits == 16 else bits in intArrayOf(1, 2, 4, 8, 16)
        if (!okBits || (photo == 3 && bits > 8) || (bits < 8 && (spp != 1 || planar != 1))) bad("TIFF depth not supported")
        if (predictor != 1 && (predictor != 2 || bits != 8)) bad("TIFF predictor not supported")
        val offsets = ifd.many(273) ?: bad("TIFF has no strips")
        val counts = ifd.many(279) ?: bad("TIFF has no strips")
        if (offsets.size != counts.size || offsets.isEmpty()) bad("bad TIFF strips")
        val rowsPerStrip = minOf(ifd.one(278, height.toLong()), height.toLong()).coerceAtLeast(1).toInt()
        val stripsPerPlane = (height + rowsPerStrip - 1) / rowsPerStrip
        val planes = if (planar == 2) spp else 1
        if (offsets.size < stripsPerPlane * planes) bad("bad TIFF strips")
        val samplesPerPixelInPlane = if (planar == 2) 1 else spp
        val bytesPerRow = ((width.toLong() * samplesPerPixelInPlane * bits + 7) / 8).toInt()
        // 16-bit samples are kept as their high byte, so the rows buffer is 8 bits per sample then.
        val keptBits = if (bits == 16) 8 else bits
        val dataRow = ((width.toLong() * samplesPerPixelInPlane * keptBits + 7) / 8).toInt()
        if (dataRow.toLong() * height * planes > limits.maxInflated) bad("TIFF too large")
        // A raw strip is read in place unless the predictor has to rewrite it; only a strip that gets
        // its own buffer (decompressed or predicted) is held to the per-strip cap.
        val inPlace = compression == 1 && predictor == 1
        if (!inPlace && bytesPerRow.toLong() * rowsPerStrip > limits.maxInflated) bad("TIFF strip too large")
        val palette = ifd.many(320)
        if (photo == 3 && (palette == null || palette.size < 3 * (1 shl bits))) bad("TIFF palette missing")
        if (compression !in intArrayOf(1, 5, 8, 32946, 32773)) bad("TIFF compression not supported")
        // Every strip must lie inside the file (and raw strips must be whole) before anything is allocated.
        for (plane in 0 until planes) for (s in 0 until stripsPerPlane) {
            val idx = plane * stripsPerPlane + s
            val off = offsets[idx]
            val cnt = counts[idx]
            if (off < 0 || cnt < 0 || off + cnt > b.size) bad("TIFF strip out of range")
            if (compression == 1 && cnt < bytesPerRow.toLong() * minOf(rowsPerStrip, height - s * rowsPerStrip)) bad("TIFF strip truncated")
        }

        // Gather the decoded sample bytes, plane by plane, into one buffer of rows.
        val data = ByteArray(dataRow * height * planes)
        for (plane in 0 until planes) for (s in 0 until stripsPerPlane) {
            val idx = plane * stripsPerPlane + s
            val off = offsets[idx].toInt()
            val cnt = counts[idx].toInt()
            val rows = minOf(rowsPerStrip, height - s * rowsPerStrip)
            val want = bytesPerRow * rows
            val strip = when (compression) {
                1 -> if (inPlace) b else b.copyOfRange(off, off + want)
                5 -> lzw(b, off, cnt, want)
                // The last strip may be padded to the full RowsPerStrip; only its first rows are used.
                8, 32946 -> Inflate.zlib(b, off, cnt, bytesPerRow.toLong() * rowsPerStrip, want.toLong())
                else -> ByteArray(want).also { unpackBitsLenient(b, off, cnt, it) }
            }
            val base = if (inPlace) off else 0
            if (strip.size - base < want) bad("TIFF strip truncated")
            if (predictor == 2) for (r in 0 until rows) {
                val rowStart = r * bytesPerRow
                for (i in samplesPerPixelInPlane until bytesPerRow) strip[rowStart + i] = (strip[rowStart + i] + strip[rowStart + i - samplesPerPixelInPlane]).toByte()
            }
            val dst = plane * dataRow * height + s * rowsPerStrip * dataRow
            if (bits == 16) {
                val hi = if (le) 1 else 0
                for (i in 0 until dataRow * rows) data[dst + i] = strip[base + i * 2 + hi]
            } else {
                strip.copyInto(data, dst, base, base + want)
            }
        }

        val out = allocate(limits, width, height)
        val max = (1 shl keptBits) - 1
        fun sample(plane: Int, y: Int, x: Int, ch: Int): Int {
            // The sample's value in [0, max], read from the rows buffer.
            val rowBase = plane * dataRow * height + y * dataRow
            val index = if (planar == 2) x else x * spp + ch
            return when (keptBits) {
                8 -> data[rowBase + index].toInt() and 0xFF
                else -> {
                    val bitPos = index * keptBits
                    ((data[rowBase + bitPos / 8].toInt() and 0xFF) shr (8 - keptBits - bitPos % 8)) and max
                }
            }
        }
        fun to8(v: Int): Int = if (keptBits == 8) v else v * 255 / max
        // Associated alpha: colour was multiplied by alpha, so divide it back out (rounded, clamped).
        fun straight(c: Int, a: Int): Int = if (extra != 1 || a == 255) c else if (a == 0) 0 else minOf(255, (c * 255 + a / 2) / a)
        for (y in 0 until height) for (x in 0 until width) {
            fun s(ch: Int) = sample(if (planar == 2) ch else 0, y, x, ch)
            val a = if (alpha) to8(s(colour)) else 255
            out[y * width + x] = when (photo) {
                2 -> argb(a, straight(to8(s(0)), a), straight(to8(s(1)), a), straight(to8(s(2)), a))
                3 -> {
                    val i = s(0)
                    val n = 1 shl bits
                    argb(255, ((palette!![i] shr 8).toInt()) and 0xFF, ((palette[n + i] shr 8).toInt()) and 0xFF, ((palette[2 * n + i] shr 8).toInt()) and 0xFF)
                }
                else -> {
                    val g = straight(to8(s(0)), a).let { if (photo == 0) 255 - it else it }
                    argb(a, g, g, g)
                }
            }
        }
        return DecodedImage(width, height, out).oriented(ifd.one(274, 1).toInt())
    }

    /** PackBits where a short strip is tolerated (writers differ on the last run); fills what it can. */
    private fun unpackBitsLenient(src: ByteArray, start: Int, length: Int, dst: ByteArray) {
        var p = start
        val end = minOf(start + length, src.size)
        var o = 0
        while (o < dst.size && p < end) {
            val n = src[p++].toInt()
            if (n >= 0) {
                val c = minOf(n + 1, end - p, dst.size - o)
                src.copyInto(dst, o, p, p + c); p += n + 1; o += c
            } else if (n != -128 && p < end) {
                val v = src[p++]
                repeat(minOf(1 - n, dst.size - o)) { dst[o++] = v }
            }
        }
        if (o < dst.size) bad("TIFF strip truncated")
    }

    /** TIFF LZW: MSB-first codes of 9..12 bits, "early change", clear = 256, end = 257. */
    private fun lzw(src: ByteArray, start: Int, length: Int, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var o = 0
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val lens = IntArray(4096)
        for (i in 0 until 256) { suffix[i] = i.toByte(); lens[i] = 1; prefix[i] = -1 }
        var next = 258
        var width = 9
        var prev = -1
        var bitBuf = 0
        var bitCnt = 0
        var p = start
        val end = minOf(start + length, src.size)
        val stack = ByteArray(4097)
        while (o < expected) {
            while (bitCnt < width) {
                if (p >= end) return out.also { if (o < expected) bad("TIFF LZW truncated") }
                bitBuf = (bitBuf shl 8) or (src[p++].toInt() and 0xFF)
                bitCnt += 8
            }
            val code = (bitBuf shr (bitCnt - width)) and ((1 shl width) - 1)
            bitCnt -= width
            bitBuf = bitBuf and ((1 shl bitCnt) - 1)
            if (code == 257) break
            if (code == 256) { next = 258; width = 9; prev = -1; continue }
            val len: Int
            var sp = 0
            if (code < next) {
                var c = code
                while (c >= 0) { stack[sp++] = suffix[c]; c = prefix[c] }
                len = sp
            } else if (code == next && prev >= 0) {
                var c = prev
                while (c >= 0) { stack[sp++] = suffix[c]; c = prefix[c] }
                // KwKwK: the new string is the previous string plus its own first byte.
                val first = stack[sp - 1]
                // Emit prev then first: reverse stack gives prev; append first.
                if (o + sp + 1 > expected) { bad("TIFF LZW overflow") }
                for (i in sp - 1 downTo 0) out[o++] = stack[i]
                out[o++] = first
                if (next < 4096) { prefix[next] = prev; suffix[next] = first; next++ }
                prev = code
                if (next + 1 >= (1 shl width) && width < 12) width++
                continue
            } else bad("bad TIFF LZW code")
            if (o + len > expected) bad("TIFF LZW overflow")
            for (i in len - 1 downTo 0) out[o++] = stack[i]
            if (prev >= 0 && next < 4096) { prefix[next] = prev; suffix[next] = stack[len - 1]; next++ }
            prev = code
            if (next + 1 >= (1 shl width) && width < 12) width++
        }
        return out
    }
}
