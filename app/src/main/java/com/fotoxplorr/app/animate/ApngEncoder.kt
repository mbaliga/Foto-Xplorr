package com.fotoxplorr.app.animate

import java.io.ByteArrayOutputStream

/** [compressionLevel] is zlib's, 1 (fast) to 9 (small). */
class ApngOptions(val compressionLevel: Int = 6) {
    init {
        require(compressionLevel in 1..9) { "compressionLevel is 1 to 9" }
    }
}

/**
 * Writes an animated PNG (APNG) with `java.util.zip.Deflater`, in pure Kotlin: `acTL`, then for each
 * frame an `fcTL` and its pixels (`IDAT` for the first, `fdAT` after), every chunk with its CRC-32.
 * A plain PNG viewer shows the first frame, which is the file's default image, so nothing is lost
 * where APNG is not understood.
 *
 * Unlike GIF this is lossless and has a real alpha channel, and it is much larger for photographs.
 *
 * ## What it does to keep files small
 * - Each frame after the first is cut to the box of pixels that differ from the frame before; a
 *   frame identical to the one before is dropped and its time added to it.
 * - When the picture is not wholly opaque, or a third or more of the pixels inside the boxes
 *   do not change, frames are written RGBA and the unchanged pixels inside a box become fully
 *   transparent drawn "over" the canvas, which is exact and compresses to almost nothing.
 *   A frame whose changed pixels are themselves see-through cannot do that and is written as a plain
 *   replacement. Otherwise frames are written RGB (a quarter smaller before compression).
 * - Each row picks its own PNG filter.
 *
 * Delays are in milliseconds and never below 20, which is what a viewer will honour.
 *
 * The JDK cannot read APNG, so the chunk structure and CRCs are checked by a hand parser in the
 * tests, and the first frame by `ImageIO`; no real APNG viewer has seen this output.
 */
object ApngEncoder {
    const val MIN_DELAY_MS = 20

    private class Planned(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val index: Int,
        val over: Boolean,
        var delayMs: Long,
    )

    fun encode(
        sequence: AnimSequence,
        options: ApngOptions = ApngOptions(),
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ): ByteArray {
        require(sequence.frames.isNotEmpty()) { "An APNG needs at least one frame" }
        val w = sequence.width
        val h = sequence.height
        val frames = sequence.frames

        val hasAlpha = frames.any { f -> f.pixels.any { (it ushr 24) != 255 } }

        // Pass 1: which frames are kept, and the box and blend of each.
        val plan = ArrayList<Planned>()
        var unchangedInBoxes = 0L
        var boxPixels = 0L
        plan.add(Planned(0, 0, w, h, 0, false, frames[0].delayMs.toLong()))
        for (i in 1 until frames.size) {
            val prev = frames[i - 1].pixels
            val cur = frames[i].pixels
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var o = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (cur[o] != prev[o]) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                    o++
                }
            }
            if (maxX < 0) {
                plan.last().delayMs += frames[i].delayMs
                continue
            }
            var changed = 0L
            var allOpaque = true
            for (y in minY..maxY) {
                var p = y * w + minX
                for (x in minX..maxX) {
                    if (cur[p] != prev[p]) {
                        changed++
                        if ((cur[p] ushr 24) != 255) allOpaque = false
                    }
                    p++
                }
            }
            val area = (maxX - minX + 1).toLong() * (maxY - minY + 1)
            unchangedInBoxes += area - changed
            boxPixels += area
            plan.add(Planned(minX, minY, maxX - minX + 1, maxY - minY + 1, i, allOpaque, frames[i].delayMs.toLong()))
        }
        val rgba = hasAlpha || (boxPixels > 0 && unchangedInBoxes * 3 >= boxPixels)
        val colorType = if (rgba) 6 else 2
        val bpp = if (rgba) 4 else 3

        val out = ByteArrayOutputStream(w * h / 2 + 1024)
        out.write(PngIo.SIGNATURE)
        val ihdr = ByteArray(13)
        PngIo.be32(ihdr, 0, w)
        PngIo.be32(ihdr, 4, h)
        ihdr[8] = 8
        ihdr[9] = colorType.toByte()
        PngIo.writeChunk(out, "IHDR", ihdr)
        val actl = ByteArray(8)
        PngIo.be32(actl, 0, plan.size)
        PngIo.be32(actl, 4, sequence.plays)
        PngIo.writeChunk(out, "acTL", actl)

        var seq = 0
        for ((k, p) in plan.withIndex()) {
            val fctl = ByteArray(26)
            PngIo.be32(fctl, 0, seq++)
            PngIo.be32(fctl, 4, p.w)
            PngIo.be32(fctl, 8, p.h)
            PngIo.be32(fctl, 12, p.x)
            PngIo.be32(fctl, 16, p.y)
            val ms = maxOf(p.delayMs, MIN_DELAY_MS.toLong())
            val num: Int
            val den: Int
            if (ms <= 65_535) {
                num = ms.toInt()
                den = 1000
            } else {
                num = minOf(ms / 10, 65_535L).toInt()
                den = 100
            }
            fctl[20] = (num ushr 8).toByte()
            fctl[21] = num.toByte()
            fctl[22] = (den ushr 8).toByte()
            fctl[23] = den.toByte()
            fctl[24] = 0 // dispose_op NONE: the canvas is left as it is
            fctl[25] = (if (p.over && rgba) 1 else 0).toByte() // blend_op OVER or SOURCE
            PngIo.writeChunk(out, "fcTL", fctl)

            val raw = ByteArray(p.w * p.h * bpp)
            val cur = frames[p.index].pixels
            val prev = if (p.index > 0) frames[p.index - 1].pixels else null
            var r = 0
            val over = p.over && rgba && prev != null
            for (y in p.y until p.y + p.h) {
                var o = y * w + p.x
                for (x in 0 until p.w) {
                    val px = cur[o]
                    if (over && px == prev!![o]) {
                        r += 4 // already zero: fully transparent, so the canvas shows through
                    } else {
                        raw[r++] = (px shr 16).toByte()
                        raw[r++] = (px shr 8).toByte()
                        raw[r++] = px.toByte()
                        if (rgba) raw[r++] = (px ushr 24).toByte()
                    }
                    o++
                }
            }
            val z = PngIo.deflate(PngIo.filterRows(raw, p.w * bpp, p.h, bpp), options.compressionLevel)
            var at = 0
            while (at < z.size) {
                val n = minOf(CHUNK_BYTES, z.size - at)
                if (k == 0) {
                    PngIo.writeChunk(out, "IDAT", z, at, n)
                } else {
                    val body = ByteArray(4 + n)
                    PngIo.be32(body, 0, seq++)
                    System.arraycopy(z, at, body, 4, n)
                    PngIo.writeChunk(out, "fdAT", body)
                }
                at += n
            }
            progress?.invoke(k + 1, plan.size)
        }
        PngIo.writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Pixel data goes out in chunks of at most this many bytes. */
    private const val CHUNK_BYTES = 65_536
}
