package com.fotoxplorr.app.animate

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * PNG and APNG assembled in the tests with no help from the code under test: the filters are written
 * again here (a different row of the five for every scanline, so the reader meets all of them), the
 * bit packing and Adam7 passes are done here, only the JDK's `Deflater` and `CRC32` are shared.
 */
object HandPng {

    fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(data.size))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        val crc = CRC32()
        crc.update(type.toByteArray(Charsets.ISO_8859_1))
        crc.update(data)
        out.write(be32(crc.value.toInt()))
        return out.toByteArray()
    }

    fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun ihdr(w: Int, h: Int, depth: Int, type: Int, interlace: Boolean = false): ByteArray =
        chunk("IHDR", be32(w) + be32(h) + byteArrayOf(depth.toByte(), type.toByte(), 0, 0, (if (interlace) 1 else 0).toByte()))

    fun channels(type: Int) = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; else -> 4 }

    /** Pack one row of pixels, each an array of channel samples, at [depth] bits per sample. */
    fun packRow(pixels: List<IntArray>, depth: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (depth >= 8) {
            for (p in pixels) for (s in p) if (depth == 16) out.write(be16(s)) else out.write(s)
        } else {
            var acc = 0
            var bits = 0
            for (p in pixels) for (s in p) {
                acc = (acc shl depth) or s
                bits += depth
                if (bits == 8) {
                    out.write(acc)
                    acc = 0
                    bits = 0
                }
            }
            if (bits > 0) out.write(acc shl (8 - bits))
        }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** Filter rows of one (sub)image, filter type `row % 5` (0 none, 1 sub, 2 up, 3 average, 4 paeth). */
    private fun filter(rows: List<ByteArray>, bpp: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for ((y, row) in rows.withIndex()) {
            val t = y % 5
            out.write(t)
            val up = if (y > 0) rows[y - 1] else ByteArray(row.size)
            for (i in row.indices) {
                val x = row[i].toInt() and 255
                val a = if (i >= bpp) row[i - bpp].toInt() and 255 else 0
                val b = up[i].toInt() and 255
                val c = if (i >= bpp) up[i - bpp].toInt() and 255 else 0
                val f = when (t) {
                    0 -> x
                    1 -> x - a
                    2 -> x - b
                    3 -> x - ((a + b) / 2)
                    else -> x - paeth(a, b, c)
                }
                out.write(f and 255)
            }
        }
        return out.toByteArray()
    }

    fun zlib(raw: ByteArray, level: Int = 6): ByteArray {
        val d = Deflater(level)
        d.setInput(raw)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private val PASSES = listOf(intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4), intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))

    /** The deflated image data for a [w] x [h] image whose pixel (x, y) has the channel samples [sample] gives. */
    fun imageData(w: Int, h: Int, depth: Int, type: Int, interlace: Boolean, sample: (Int, Int) -> IntArray): ByteArray {
        val bpp = maxOf(1, channels(type) * depth / 8)
        val raw = ByteArrayOutputStream()
        val passes = if (interlace) PASSES else listOf(intArrayOf(0, 0, 1, 1))
        for (p in passes) {
            val xs = (p[0] until w step p[2]).toList()
            val ys = (p[1] until h step p[3]).toList()
            if (xs.isEmpty() || ys.isEmpty()) continue
            val rows = ys.map { y -> packRow(xs.map { x -> sample(x, y) }, depth) }
            raw.write(filter(rows, bpp))
        }
        return zlib(raw.toByteArray())
    }

    /** A complete still PNG; [before] chunks (PLTE, tRNS) go between IHDR and IDAT. */
    fun png(w: Int, h: Int, depth: Int, type: Int, interlace: Boolean, before: List<ByteArray> = emptyList(), sample: (Int, Int) -> IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(ihdr(w, h, depth, type, interlace))
        for (b in before) out.write(b)
        out.write(chunk("IDAT", imageData(w, h, depth, type, interlace, sample)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    /** One frame of a hand-built APNG: RGBA 8-bit pixels of [w] x [h] placed at ([x], [y]). */
    class Frame(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val delayNum: Int, val delayDen: Int,
        val dispose: Int, val blend: Int,
        val argb: IntArray,
    )

    /**
     * An RGBA 8-bit APNG. With [hiddenDefault] the first `IDAT` is a default image that comes BEFORE
     * the first `fcTL`, so it is not part of the animation. [seqOverride] lets a test break the
     * numbering: it maps (chunk ordinal) to the number written.
     */
    fun apng(
        w: Int, h: Int, plays: Int, frames: List<Frame>,
        hiddenDefault: IntArray? = null,
        seqOverride: ((Int) -> Int)? = null,
        splitData: Int = 60,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(ihdr(w, h, 8, 6))
        out.write(chunk("acTL", be32(frames.size) + be32(plays)))
        var ordinal = 0
        fun seq(): ByteArray {
            val n = ordinal++
            return be32(seqOverride?.invoke(n) ?: n)
        }
        fun rgbaSample(px: IntArray, fw: Int): (Int, Int) -> IntArray = { x, y ->
            val p = px[y * fw + x]
            intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255, p ushr 24)
        }
        if (hiddenDefault != null) out.write(chunk("IDAT", imageData(w, h, 8, 6, false, rgbaSample(hiddenDefault, w))))
        for ((i, f) in frames.withIndex()) {
            out.write(chunk("fcTL", seq() + be32(f.w) + be32(f.h) + be32(f.x) + be32(f.y) + be16(f.delayNum) + be16(f.delayDen) + byteArrayOf(f.dispose.toByte(), f.blend.toByte())))
            val z = imageData(f.w, f.h, 8, 6, false, rgbaSample(f.argb, f.w))
            var at = 0
            while (at < z.size) {
                val n = minOf(splitData, z.size - at)
                val part = z.copyOfRange(at, at + n)
                if (i == 0 && hiddenDefault == null) out.write(chunk("IDAT", part)) else out.write(chunk("fdAT", seq() + part))
                at += n
            }
        }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }
}
