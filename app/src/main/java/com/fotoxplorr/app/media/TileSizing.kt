package com.fotoxplorr.app.media

import com.fotoxplorr.core.formats.simple.SimpleLimits
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How big [RouteCoilDecoder] makes a bitmap for a Coil request, and what a tile may cost. Pure so it is a JVM test.
 *
 * A request is the box Coil asks for ([reqW] x [reqH], null for an undefined side) and how the picture goes in it:
 * `fill` (Coil's Scale.FILL, which ContentScale.Crop asks for) covers the box, so the SHORT edge reaches it;
 * otherwise the picture fits inside. Never larger than the source, never past [MAX_EDGE]; with no size at all the
 * long edge is [DEFAULT_EDGE].
 */
internal object TileSizing {
    const val DEFAULT_EDGE = 2048
    const val MAX_EDGE = 8192

    /** A request whose defined sides are all this or less is a tile (a grid cell, a strip, a stack). */
    const val TILE_EDGE = 1024

    /**
     * What one tile of a simple format may read and decode: 32 MB of file and 12 MP of pixels. The simple
     * decoders have no sampled entry point, so a tile is decoded at full size and then averaged down; at the
     * defaults (192 MB, 24 MP) one tile could hold the file plus 96 MB of pixels. Here the worst tile holds
     * 32 MB plus 48 MB, and only [SIMPLE_DECODES_AT_ONCE] of them run together. A larger file shows the
     * placeholder in the grid and still opens in the viewer, whose request is not a tile.
     */
    val TILE_INPUT: SimpleLimits = SimpleLimits(maxPixels = 12_000_000L, maxInputBytes = 32L shl 20)

    /** Simple-format decodes (tile or viewer) allowed at the same time across all requests. */
    const val SIMPLE_DECODES_AT_ONCE = 2

    fun isTile(reqW: Int?, reqH: Int?): Boolean {
        val sides = listOfNotNull(reqW, reqH)
        return sides.isNotEmpty() && sides.all { it <= TILE_EDGE }
    }

    /** The caps a simple-format decode of this request reads under. */
    fun simpleLimits(reqW: Int?, reqH: Int?): SimpleLimits = if (isTile(reqW, reqH)) TILE_INPUT else SimpleLimits()

    /** The size to produce for a [srcW] x [srcH] picture. */
    fun target(srcW: Int, srcH: Int, reqW: Int?, reqH: Int?, fill: Boolean): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return max(0, srcW) to max(0, srcH)
        val longSrc = max(srcW, srcH).toDouble()
        val sx = reqW?.takeIf { it > 0 }?.let { it.toDouble() / srcW }
        val sy = reqH?.takeIf { it > 0 }?.let { it.toDouble() / srcH }
        val wanted = when {
            sx == null && sy == null -> DEFAULT_EDGE / longSrc
            sx == null -> sy!!
            sy == null -> sx
            fill -> max(sx, sy)
            else -> min(sx, sy)
        }
        val scale = min(1.0, min(wanted, MAX_EDGE / longSrc))
        return max(1, (srcW * scale).roundToInt()) to max(1, (srcH * scale).roundToInt())
    }

    /**
     * The [DecodeLimits] under which [targetSize] gives [target] for a [srcW] x [srcH] source, for the readers that
     * take limits (the RAW preview): the long edge is exact, the pixel cap leaves room for rounding.
     */
    fun limitsFor(srcW: Int, srcH: Int, reqW: Int?, reqH: Int?, fill: Boolean): DecodeLimits {
        val (w, h) = target(srcW, srcH, reqW, reqH, fill)
        return DecodeLimits(maxLongEdge = max(1, max(w, h)), maxPixels = (w + 1).toLong() * (h + 1))
    }

    /**
     * [argb] ([srcW] x [srcH], non-premultiplied ARGB) averaged down to [dstW] x [dstH]: each output pixel is the
     * mean of the source box it covers, weighted by alpha so a transparent pixel does not darken its neighbours.
     * Only the small array is allocated, so no full-size Bitmap is ever made for a tile.
     */
    fun downscale(argb: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        require(argb.size.toLong() == srcW.toLong() * srcH && dstW in 1..srcW && dstH in 1..srcH)
        val out = IntArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val y0 = (dy.toLong() * srcH / dstH).toInt()
            val y1 = max(y0 + 1, ((dy + 1).toLong() * srcH / dstH).toInt())
            for (dx in 0 until dstW) {
                val x0 = (dx.toLong() * srcW / dstW).toInt()
                val x1 = max(x0 + 1, ((dx + 1).toLong() * srcW / dstW).toInt())
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in y0 until y1) {
                    var i = y * srcW + x0
                    for (x in x0 until x1) {
                        val c = argb[i++]
                        val ca = (c ushr 24).toLong()
                        a += ca
                        r += ((c shr 16) and 0xFF) * ca
                        g += ((c shr 8) and 0xFF) * ca
                        b += (c and 0xFF) * ca
                    }
                }
                val n = (y1 - y0).toLong() * (x1 - x0)
                out[dy * dstW + dx] = if (a == 0L) 0 else {
                    val oa = ((a + n / 2) / n).toInt()
                    (oa shl 24) or (((r + a / 2) / a).toInt() shl 16) or (((g + a / 2) / a).toInt() shl 8) or ((b + a / 2) / a).toInt()
                }
            }
        }
        return out
    }
}
