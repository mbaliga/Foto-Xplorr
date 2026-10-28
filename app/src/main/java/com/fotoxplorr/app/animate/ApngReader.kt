package com.fotoxplorr.app.animate

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Reads an animated PNG, or a plain PNG as a one-frame animation, into fully composited canvases,
 * in pure Kotlin. Covers every PNG pixel layout (grey, RGB, palette, grey+alpha, RGBA; 1, 2, 4, 8
 * and 16 bits; `tRNS` transparency; Adam7 interlace), the APNG chunks `acTL`, `fcTL` and `fdAT`,
 * both dispose ops that matter (background, previous) and both blends (source, over). Colour
 * management chunks (gAMA, iCCP, sRGB...) are ignored: pixels come out as stored. 16-bit samples
 * are cut to their high byte.
 *
 * If the first `fcTL` comes after the `IDAT` data, the default image is not part of the animation
 * and is skipped, as the APNG rules say.
 *
 * ## Damaged files
 * Like [GifReader], nothing throws. Every chunk's CRC is checked while the file is scanned; the scan
 * stops at the first chunk that is cut off or fails its CRC, and the frames that were wholly before
 * it are returned with [problem] set. A frame sequence number out of order is treated the same way
 * (the scan stops there, the frames before it are kept). A frame whose pixel data does not inflate
 * to the size its header says is not returned.
 *
 * The scan is bounded: it lists at most [DecodeLimits.maxFrames] + 1 frames and [MAX_CHUNKS] chunks and
 * then stops with [DecodeProblem.TOO_LARGE], so a 32 MB file of empty chunks cannot make millions of
 * objects before the first frame is read.
 *
 * Delays are returned as written (a delay of 0 stays 0); there is no browser-style rounding up.
 */
class ApngReader(private val data: ByteArray, private val limits: DecodeLimits = DecodeLimits()) {
    var width = 0
        private set
    var height = 0
        private set

    /** Total plays, 0 for ever. A plain PNG plays once. */
    var plays = 1
        private set

    var problem = DecodeProblem.NONE
        private set
    var detail = ""
        private set

    private class Chunk(val type: String, val off: Int, val len: Int)

    private class Rec(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val delayMs: Int, val dispose: Int, val blend: Int,
    ) {
        val parts = ArrayList<Chunk>()
        val isFdat get() = parts.firstOrNull()?.type == "fdAT"
    }

    private var bitDepth = 0
    private var colorType = 0
    private var interlaced = false
    private var palette: IntArray? = null
    private var trnsAlpha: ByteArray? = null
    private var trnsKey: IntArray? = null

    private val recs = ArrayList<Rec>()
    private val defaultParts = ArrayList<Chunk>()
    private var declaredFrames = 0
    private var scanEndedClean = false
    private var scanProblem = DecodeProblem.NONE
    private var scanDetail = ""

    private var canvas = IntArray(0)
    private var cursor = 0
    private var done = false
    private var prevRect: PixelRect? = null
    private var prevDispose = 0
    private var prevSaved: IntArray? = null
    private var staticServed = false
    private var animated = false

    init {
        parse()
    }

    private fun fail(p: DecodeProblem, why: String) {
        if (problem == DecodeProblem.NONE) {
            problem = p
            detail = why
        }
        done = true
    }

    private fun parse() {
        if (data.size < 8 || !data.copyOfRange(0, 8).contentEquals(PngIo.SIGNATURE)) {
            fail(DecodeProblem.NOT_THIS_FORMAT, "no PNG signature")
            return
        }
        // 1. Scan the chunk list, checking each CRC.
        val chunks = ArrayList<Chunk>()
        var pos = 8
        while (true) {
            if (pos + 12 > data.size) {
                if (pos != data.size || chunks.lastOrNull()?.type != "IEND") {
                    scanProblem = DecodeProblem.TRUNCATED
                    scanDetail = "chunk list cut short"
                }
                break
            }
            val len = PngIo.readBe32(data, pos)
            if (len < 0 || pos.toLong() + 12 + len > data.size) {
                scanProblem = DecodeProblem.TRUNCATED
                scanDetail = "chunk runs past the end"
                break
            }
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            val crc = CRC32()
            crc.update(data, pos + 4, 4 + len)
            if (crc.value.toInt() != PngIo.readBe32(data, pos + 8 + len)) {
                scanProblem = DecodeProblem.CORRUPT
                scanDetail = "bad CRC in $type"
                break
            }
            if (chunks.size >= MAX_CHUNKS) {
                scanProblem = DecodeProblem.TOO_LARGE
                scanDetail = "more than $MAX_CHUNKS chunks"
                break
            }
            chunks.add(Chunk(type, pos + 8, len))
            pos += 12 + len
            if (type == "IEND") {
                scanEndedClean = true
                break
            }
        }
        val ihdr = chunks.firstOrNull()
        if (ihdr == null || ihdr.type != "IHDR" || ihdr.len != 13) {
            fail(if (scanProblem != DecodeProblem.NONE) scanProblem else DecodeProblem.CORRUPT, "no usable IHDR")
            return
        }
        val w = PngIo.readBe32(data, ihdr.off)
        val h = PngIo.readBe32(data, ihdr.off + 4)
        bitDepth = data[ihdr.off + 8].toInt() and 255
        colorType = data[ihdr.off + 9].toInt() and 255
        interlaced = data[ihdr.off + 12].toInt() == 1
        if (w <= 0 || h <= 0 || !validLayout(bitDepth, colorType) || data[ihdr.off + 10].toInt() != 0 || data[ihdr.off + 11].toInt() != 0) {
            fail(DecodeProblem.CORRUPT, "unsupported or invalid IHDR")
            return
        }
        if (w.toLong() * h > limits.maxCanvasPixels || w > AnimSequence.MAX_SIDE || h > AnimSequence.MAX_SIDE) {
            fail(DecodeProblem.TOO_LARGE, "canvas $w x $h is over the limit")
            return
        }
        width = w
        height = h

        // 2. Sort the rest into the palette, the transparency, and the frames.
        var expectSeq = 0
        var current: Rec? = null
        var seenIdat = false
        var idatTarget: MutableList<Chunk>? = null
        for (ci in 1 until chunks.size) {
            val c = chunks[ci]
            when (c.type) {
                "PLTE" -> palette = IntArray(c.len / 3) { i ->
                    (0xFF shl 24) or ((data[c.off + i * 3].toInt() and 255) shl 16) or ((data[c.off + i * 3 + 1].toInt() and 255) shl 8) or (data[c.off + i * 3 + 2].toInt() and 255)
                }
                "tRNS" -> when (colorType) {
                    3 -> trnsAlpha = data.copyOfRange(c.off, c.off + c.len)
                    0 -> if (c.len >= 2) trnsKey = intArrayOf(PngIo.readBe16(data, c.off))
                    2 -> if (c.len >= 6) trnsKey = intArrayOf(PngIo.readBe16(data, c.off), PngIo.readBe16(data, c.off + 2), PngIo.readBe16(data, c.off + 4))
                }
                "acTL" -> if (c.len >= 8 && !seenIdat) {
                    animated = true
                    declaredFrames = PngIo.readBe32(data, c.off)
                    // A count with the top bit set is read as a very large number of plays, not a negative one.
                    plays = PngIo.readBe32(data, c.off + 4).let { if (it < 0) Int.MAX_VALUE else it }
                }
                "fcTL" -> if (animated && c.len >= 26) {
                    if (PngIo.readBe32(data, c.off) != expectSeq) {
                        // Like a bad CRC: the frames wholly before this point are still good.
                        scanProblem = DecodeProblem.CORRUPT
                        scanDetail = "frame sequence numbers out of order"
                        break
                    }
                    if (recs.size > limits.maxFrames) {
                        scanProblem = DecodeProblem.TOO_LARGE
                        scanDetail = "more frames than the limit"
                        break
                    }
                    expectSeq++
                    val num = PngIo.readBe16(data, c.off + 20)
                    var den = PngIo.readBe16(data, c.off + 22)
                    if (den == 0) den = 100
                    val rec = Rec(
                        x = PngIo.readBe32(data, c.off + 12), y = PngIo.readBe32(data, c.off + 16),
                        w = PngIo.readBe32(data, c.off + 4), h = PngIo.readBe32(data, c.off + 8),
                        delayMs = (num * 1000L / den).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        dispose = data[c.off + 24].toInt() and 255, blend = data[c.off + 25].toInt() and 255,
                    )
                    recs.add(rec)
                    current = rec
                }
                "IDAT" -> {
                    // After an fcTL that came before any IDAT, the image data is that frame's; else it is the default image.
                    if (idatTarget == null) idatTarget = if (animated && current != null) current.parts else defaultParts
                    idatTarget.add(c)
                    seenIdat = true
                }
                "fdAT" -> if (animated && c.len >= 4) {
                    if (PngIo.readBe32(data, c.off) != expectSeq) {
                        scanProblem = DecodeProblem.CORRUPT
                        scanDetail = "frame sequence numbers out of order"
                        break
                    }
                    expectSeq++
                    current?.parts?.add(c)
                }
            }
        }
        if (problem != DecodeProblem.NONE) return
        if (colorType == 3 && palette == null) {
            fail(DecodeProblem.CORRUPT, "palette image with no palette")
            return
        }
        canvas = IntArray(width * height)
    }

    private fun validLayout(depth: Int, type: Int): Boolean = when (type) {
        0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16
        2, 4, 6 -> depth == 8 || depth == 16
        3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8
        else -> false
    }

    private fun channels(): Int = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; else -> 4 }
    private fun bitsPerPixel(): Int = channels() * bitDepth

    /** The next composited frame, or null at the end or at the first unreadable frame. */
    fun next(): AnimFrame? {
        if (done) return null
        if (!animated) return nextStatic()
        if (cursor >= recs.size) {
            done = true
            if (scanProblem != DecodeProblem.NONE) fail(scanProblem, scanDetail)
            else if (!scanEndedClean) fail(DecodeProblem.TRUNCATED, "no IEND")
            else if (cursor < declaredFrames) fail(DecodeProblem.CORRUPT, "fewer frames than acTL says")
            return null
        }
        val rec = recs[cursor++]
        if (rec.w <= 0 || rec.h <= 0 || rec.x < 0 || rec.y < 0 || rec.x.toLong() + rec.w > width || rec.y.toLong() + rec.h > height) {
            fail(DecodeProblem.CORRUPT, "a frame lies outside the canvas")
            return null
        }
        val pixels = decodeImage(rec.parts, rec.w, rec.h, rec.isFdat)
        if (pixels == null) {
            // The data ended or broke: if the scan also stopped early the file was cut, else it is damaged.
            fail(if (scanProblem != DecodeProblem.NONE && cursor == recs.size) scanProblem else DecodeProblem.CORRUPT, "frame pixels do not decode")
            return null
        }
        return composite(rec, pixels)
    }

    private fun nextStatic(): AnimFrame? {
        if (staticServed) {
            done = true
            if (scanProblem != DecodeProblem.NONE) fail(scanProblem, scanDetail)
            return null
        }
        staticServed = true
        val pixels = decodeImage(defaultParts, width, height, false)
        if (pixels == null) {
            fail(if (scanProblem != DecodeProblem.NONE) scanProblem else DecodeProblem.CORRUPT, "image pixels do not decode")
            return null
        }
        return AnimFrame(pixels, 100)
    }

    private fun composite(rec: Rec, px: IntArray): AnimFrame {
        // Dispose of the previous frame first.
        prevRect?.let { r ->
            when (prevDispose) {
                1 -> for (y in r.y0 until r.y1) java.util.Arrays.fill(canvas, y * width + r.x0, y * width + r.x1, 0)
                2 -> prevSaved?.let { saved ->
                    var k = 0
                    for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) canvas[y * width + x] = saved[k++]
                }
            }
        }
        val rect = PixelRect(rec.x, rec.y, rec.x + rec.w, rec.y + rec.h)
        // "Previous" on the very first frame means "background".
        val dispose = if (rec.dispose == 2 && prevRect == null && cursor == 1) 1 else rec.dispose
        prevSaved = if (dispose == 2) {
            val s = IntArray(rect.area)
            var k = 0
            for (y in rect.y0 until rect.y1) for (x in rect.x0 until rect.x1) s[k++] = canvas[y * width + x]
            s
        } else null
        prevRect = rect
        prevDispose = dispose

        var k = 0
        for (y in rect.y0 until rect.y1) {
            var o = y * width + rect.x0
            for (x in rect.x0 until rect.x1) {
                val src = px[k++]
                canvas[o] = if (rec.blend == 0) src else over(src, canvas[o])
                o++
            }
        }
        return AnimFrame(canvas.copyOf(), rec.delayMs)
    }

    /** Straight-alpha "source over destination". */
    private fun over(src: Int, dst: Int): Int {
        val sa = src ushr 24
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = dst ushr 24
        if (da == 0) return src
        // outA = sa + da (1 - sa); colour = (sc sa + dc da (1 - sa)) / outA, in 0..255 integers.
        val dw = da * (255 - sa) // weight of the destination, times 255
        val sw = sa * 255
        val total = sw + dw
        val outA = (total + 127) / 255
        fun ch(shift: Int): Int {
            val sc = (src shr shift) and 255
            val dc = (dst shr shift) and 255
            return ((sc * sw + dc * dw + total / 2) / total).coerceIn(0, 255)
        }
        return (outA.coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- pixels ----

    /** Inflate and unfilter one image of [w] x [h] from [parts], to straight ARGB. Null when the data is short or broken. */
    private fun decodeImage(parts: List<Chunk>, w: Int, h: Int, fdat: Boolean): IntArray? {
        val bits = bitsPerPixel()
        val passes = if (interlaced) ADAM7 else arrayOf(intArrayOf(0, 0, 1, 1))
        var expected = 0L
        for (p in passes) {
            val pw = passSize(w, p[0], p[2])
            val ph = passSize(h, p[1], p[3])
            if (pw > 0 && ph > 0) expected += (1L + (pw.toLong() * bits + 7) / 8) * ph
        }
        if (expected > Int.MAX_VALUE / 2 || expected > limits.maxCanvasPixels * 8 + 4096) return null

        val joined = ByteArrayOutputStream()
        for (c in parts) {
            if (fdat) joined.write(data, c.off + 4, c.len - 4) else joined.write(data, c.off, c.len)
        }
        val z = joined.toByteArray()
        val (raw, ok) = PngIo.inflate(z, z.size, expected.toInt())
        if (!ok) return null

        val out = IntArray(w * h)
        val bpp = maxOf(1, bits / 8)
        var at = 0
        for (p in passes) {
            val pw = passSize(w, p[0], p[2])
            val ph = passSize(h, p[1], p[3])
            if (pw <= 0 || ph <= 0) continue
            val stride = ((pw.toLong() * bits + 7) / 8).toInt()
            if (!PngIo.unfilterRows(raw, at, stride, ph, bpp)) return null
            for (ry in 0 until ph) {
                val dstY = p[1] + ry * p[3]
                rowToArgb(raw, at + ry * (stride + 1) + 1, pw, out, dstY * w + p[0], p[2])
            }
            at += (stride + 1) * ph
        }
        return out
    }

    private fun passSize(total: Int, start: Int, step: Int): Int = if (total > start) (total - start + step - 1) / step else 0

    /** Convert [n] pixels of the row at [rs] into [out], starting at [os] and advancing [ostep] a pixel. */
    private fun rowToArgb(src: ByteArray, rs: Int, n: Int, out: IntArray, os: Int, ostep: Int) {
        var o = os
        when (colorType) {
            6 -> if (bitDepth == 8) {
                var s = rs
                for (i in 0 until n) {
                    out[o] = ((src[s + 3].toInt() and 255) shl 24) or ((src[s].toInt() and 255) shl 16) or ((src[s + 1].toInt() and 255) shl 8) or (src[s + 2].toInt() and 255)
                    s += 4
                    o += ostep
                }
            } else {
                var s = rs
                for (i in 0 until n) {
                    out[o] = ((src[s + 6].toInt() and 255) shl 24) or ((src[s].toInt() and 255) shl 16) or ((src[s + 2].toInt() and 255) shl 8) or (src[s + 4].toInt() and 255)
                    s += 8
                    o += ostep
                }
            }
            2 -> {
                val key = trnsKey
                val step = if (bitDepth == 8) 3 else 6
                val half = if (bitDepth == 8) 1 else 2
                var s = rs
                for (i in 0 until n) {
                    val r = src[s].toInt() and 255
                    val g = src[s + half].toInt() and 255
                    val b = src[s + 2 * half].toInt() and 255
                    var a = 255
                    if (key != null) {
                        val kr = if (bitDepth == 8) r else PngIo.readBe16(src, s)
                        val kg = if (bitDepth == 8) g else PngIo.readBe16(src, s + 2)
                        val kb = if (bitDepth == 8) b else PngIo.readBe16(src, s + 4)
                        if (kr == key[0] && kg == key[1] && kb == key[2]) a = 0
                    }
                    out[o] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    s += step
                    o += ostep
                }
            }
            0 -> {
                val key = trnsKey
                for (i in 0 until n) {
                    val v = sample(src, rs, i)
                    val g8 = when (bitDepth) {
                        1 -> v * 255
                        2 -> v * 85
                        4 -> v * 17
                        8 -> v
                        else -> v shr 8
                    }
                    val a = if (key != null && v == key[0]) 0 else 255
                    out[o] = (a shl 24) or (g8 shl 16) or (g8 shl 8) or g8
                    o += ostep
                }
            }
            3 -> {
                val pal = palette!!
                val alpha = trnsAlpha
                for (i in 0 until n) {
                    val idx = sample(src, rs, i)
                    var c = if (idx < pal.size) pal[idx] else 0xFF000000.toInt()
                    if (alpha != null && idx < alpha.size) c = (c and 0xFFFFFF) or ((alpha[idx].toInt() and 255) shl 24)
                    out[o] = c
                    o += ostep
                }
            }
            4 -> {
                var s = rs
                val step = if (bitDepth == 8) 2 else 4
                val aAt = if (bitDepth == 8) 1 else 2
                for (i in 0 until n) {
                    val g = src[s].toInt() and 255
                    val a = src[s + aAt].toInt() and 255
                    out[o] = (a shl 24) or (g shl 16) or (g shl 8) or g
                    s += step
                    o += ostep
                }
            }
        }
    }

    /** Sample [i] of a one-channel row of 1, 2, 4, 8 or 16 bits. */
    private fun sample(src: ByteArray, rs: Int, i: Int): Int = when (bitDepth) {
        8 -> src[rs + i].toInt() and 255
        16 -> PngIo.readBe16(src, rs + i * 2)
        else -> {
            val perByte = 8 / bitDepth
            val b = src[rs + i / perByte].toInt() and 255
            val shift = 8 - bitDepth * (i % perByte + 1)
            (b shr shift) and ((1 shl bitDepth) - 1)
        }
    }

    companion object {
        /** Chunks listed from one file before the scan gives up; a real file of 32 MB has far fewer (libpng cuts its data chunks at 8 KB). */
        const val MAX_CHUNKS = 200_000

        private val ADAM7 = arrayOf(
            intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
            intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2),
        )

        /** Read the whole file, holding every frame, within [limits]. */
        fun decodeAll(data: ByteArray, limits: DecodeLimits = DecodeLimits()): AnimDecodeResult {
            val reader = ApngReader(data, limits)
            val frames = ArrayList<AnimFrame>()
            var pixels = 0L
            var limitHit = false
            while (true) {
                val f = reader.next() ?: break
                if (frames.size >= limits.maxFrames || pixels + f.pixels.size > limits.maxTotalPixels) {
                    limitHit = true
                    break
                }
                frames.add(f)
                pixels += f.pixels.size
            }
            if (reader.width == 0) return AnimDecodeResult(null, reader.problem, reader.detail)
            val seq = AnimSequence(reader.width, reader.height, frames, reader.plays)
            return if (limitHit) AnimDecodeResult(seq, DecodeProblem.TOO_LARGE, "frame or size limit reached")
            else AnimDecodeResult(seq, reader.problem, reader.detail)
        }
    }
}
