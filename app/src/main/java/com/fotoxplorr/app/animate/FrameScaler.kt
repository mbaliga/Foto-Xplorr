package com.fotoxplorr.app.animate

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How a picture of one shape is placed on a canvas of another. */
enum class FitMode {
    /** The whole picture, shrunk or grown to fit inside; the rest of the canvas is [FrameScaler.fit]'s background. */
    CONTAIN,

    /** The canvas filled, the picture shrunk or grown to cover it, the overflow cropped evenly from both sides. */
    COVER,

    /** The picture stretched to the canvas, whatever that does to its shape. */
    STRETCH,
}

/**
 * Resizing and placing frames, pure. Colour is averaged with alpha as the weight, so a clear pixel
 * (whatever colour it carries) never tints its neighbours.
 */
object FrameScaler {

    /**
     * Resize [src] (`w x h`) to `nw x nh`. Shrinking an axis averages the source pixels each output
     * pixel covers (exact area weights, so no aliasing); growing one interpolates linearly between
     * pixel centres. The two axes are done separately, so a picture can shrink on one and grow on
     * the other.
     */
    fun scale(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        require(w > 0 && h > 0 && nw > 0 && nh > 0) { "Nothing to scale" }
        require(src.size == w * h)
        if (w == nw && h == nh) return src.copyOf()
        // Horizontal pass into premultiplied floats, 4 channels, then the vertical pass out of them.
        val tx = Taps.make(w, nw)
        val ty = Taps.make(h, nh)
        val mid = FloatArray(nw * h * 4)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until nw) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in 0 until tx.count[x]) {
                    val p = src[row + tx.first[x] + t]
                    val wt = tx.weight[x * tx.stride + t]
                    val pa = (p ushr 24) * wt
                    a += pa
                    r += ((p shr 16) and 255) * pa
                    g += ((p shr 8) and 255) * pa
                    b += (p and 255) * pa
                }
                val o = (y * nw + x) * 4
                mid[o] = a
                mid[o + 1] = r
                mid[o + 2] = g
                mid[o + 3] = b
            }
        }
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in 0 until ty.count[y]) {
                    val wt = ty.weight[y * ty.stride + t]
                    val o = ((ty.first[y] + t) * nw + x) * 4
                    a += mid[o] * wt
                    r += mid[o + 1] * wt
                    g += mid[o + 2] * wt
                    b += mid[o + 3] * wt
                }
                out[y * nw + x] = if (a <= 0f) 0 else {
                    val ai = (a + 0.5f).toInt().coerceIn(0, 255)
                    (ai shl 24) or (round8(r / a) shl 16) or (round8(g / a) shl 8) or round8(b / a)
                }
            }
        }
        return out
    }

    private fun round8(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    /**
     * Place [src] on a `cw x ch` canvas by [mode], filling any uncovered canvas with [background]
     * (straight ARGB; 0 is clear). The picture is centred.
     */
    fun fit(src: IntArray, w: Int, h: Int, cw: Int, ch: Int, mode: FitMode, background: Int = 0): IntArray {
        if (mode == FitMode.STRETCH) return scale(src, w, h, cw, ch)
        val sx = cw.toDouble() / w
        val sy = ch.toDouble() / h
        val s = if (mode == FitMode.CONTAIN) min(sx, sy) else max(sx, sy)
        val nw = max(1, Math.round(w * s).toInt())
        val nh = max(1, Math.round(h * s).toInt())
        val scaled = scale(src, w, h, nw, nh)
        val out = IntArray(cw * ch)
        if (background != 0) java.util.Arrays.fill(out, background)
        // Where the scaled picture's top-left lands on the canvas (negative when it overflows).
        val ox = (cw - nw) / 2
        val oy = (ch - nh) / 2
        for (y in 0 until nh) {
            val cy = y + oy
            if (cy < 0 || cy >= ch) continue
            for (x in 0 until nw) {
                val cx = x + ox
                if (cx < 0 || cx >= cw) continue
                out[cy * cw + cx] = over(scaled[y * nw + x], out[cy * cw + cx])
            }
        }
        return out
    }

    /** Fit [longEdge]: the size a `w x h` picture is shrunk to so its long side is at most [longEdge] (never enlarged). */
    fun capLongEdge(w: Int, h: Int, longEdge: Int): Pair<Int, Int> {
        require(w > 0 && h > 0 && longEdge > 0)
        val long = max(w, h)
        if (long <= longEdge) return Pair(w, h)
        val s = longEdge.toDouble() / long
        return Pair(max(1, Math.round(w * s).toInt()), max(1, Math.round(h * s).toInt()))
    }

    /** Straight-alpha source over destination, rounded. */
    internal fun over(src: Int, dst: Int): Int {
        val sa = src ushr 24
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = dst ushr 24
        if (da == 0) return src
        val dw = da * (255 - sa)
        val sw = sa * 255
        val total = sw + dw
        val outA = (total + 127) / 255
        fun ch(shift: Int): Int = ((((src shr shift) and 255) * sw + ((dst shr shift) and 255) * dw + total / 2) / total).coerceIn(0, 255)
        return (outA.coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The source taps of each output index along one axis: `weight[i * stride + t]` for source `first[i] + t`. Rows sum to 1. */
    private class Taps(val first: IntArray, val count: IntArray, val weight: FloatArray, val stride: Int) {
        companion object {
            fun make(src: Int, dst: Int): Taps {
                if (dst < src) {
                    // Area average: output i covers source [i * k, (i + 1) * k), k = src / dst.
                    val k = src.toDouble() / dst
                    val stride = ceil(k).toInt() + 1
                    val first = IntArray(dst)
                    val count = IntArray(dst)
                    val weight = FloatArray(dst * stride)
                    for (i in 0 until dst) {
                        val lo = i * k
                        val hi = min(src.toDouble(), (i + 1) * k)
                        val f = floor(lo).toInt()
                        val l = min(src - 1, ceil(hi).toInt() - 1)
                        first[i] = f
                        count[i] = l - f + 1
                        var sum = 0.0
                        for (t in 0 until count[i]) {
                            val cover = min(hi, (f + t + 1).toDouble()) - max(lo, (f + t).toDouble())
                            weight[i * stride + t] = cover.toFloat()
                            sum += cover
                        }
                        for (t in 0 until count[i]) weight[i * stride + t] = (weight[i * stride + t] / sum).toFloat()
                    }
                    return Taps(first, count, weight, stride)
                }
                // Linear interpolation between pixel centres; edges clamp.
                val first = IntArray(dst)
                val count = IntArray(dst)
                val weight = FloatArray(dst * 2)
                val k = src.toDouble() / dst
                for (i in 0 until dst) {
                    val pos = (i + 0.5) * k - 0.5
                    val f = floor(pos).toInt()
                    val frac = (pos - f).toFloat()
                    if (f < 0) {
                        first[i] = 0
                        count[i] = 1
                        weight[i * 2] = 1f
                    } else if (f >= src - 1) {
                        first[i] = src - 1
                        count[i] = 1
                        weight[i * 2] = 1f
                    } else {
                        first[i] = f
                        count[i] = 2
                        weight[i * 2] = 1f - frac
                        weight[i * 2 + 1] = frac
                    }
                }
                return Taps(first, count, weight, 2)
            }
        }
    }
}
