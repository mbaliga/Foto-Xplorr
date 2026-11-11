package com.fotoxplorr.app.animate

import java.util.Arrays
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Colour reduction for GIF, in pure Kotlin: a 5-5-5 histogram, weighted median cut, two rounds of
 * Lloyd refinement, an exact nearest-colour search and Floyd-Steinberg dithering.
 *
 * Distances are plain squared RGB distance. That is not how the eye sees colour (Lab or a weighted
 * metric would do better in dark blues and greens) but it keeps the nearest-colour search exact and
 * cheap, and it is the same metric the quality tests measure with. Colours here are 0x00RRGGBB ints.
 */

/** A palette of at most 256 colours, 0x00RRGGBB. */
internal class Palette(val colors: IntArray) {
    init {
        require(colors.size in 1..256) { "A GIF palette holds 1 to 256 colours" }
    }

    val size: Int get() = colors.size
}

/**
 * Counts of the colours in some pixels: one bin per 5-5-5 cell, with the exact sum of the colours
 * that fell in it, so a cell's colour is its true mean and not the cell centre. It also notices
 * whether the pixels hold at most [EXACT_LIMIT] different exact colours, in which case a palette of
 * exactly those colours loses nothing (flat graphics, screenshots, anything already quantised).
 */
internal class ColorHistogram {
    val count = IntArray(BINS)
    val sumR = LongArray(BINS)
    val sumG = LongArray(BINS)
    val sumB = LongArray(BINS)
    var total = 0L
        private set

    private val exactKeys = IntArray(EXACT_TABLE) { -1 }
    private var exactCount = 0
    private var exactOverflow = false

    fun add(rgb: Int) {
        val r = (rgb shr 16) and 255
        val g = (rgb shr 8) and 255
        val b = rgb and 255
        val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        count[bin]++
        sumR[bin] += r.toLong()
        sumG[bin] += g.toLong()
        sumB[bin] += b.toLong()
        total++
        if (!exactOverflow) noteExact(rgb and 0xFFFFFF)
    }

    /** Add the pixels of a rectangle whose [mask] byte is non-zero (every pixel when [mask] is null). */
    fun addRect(argb: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int, mask: ByteArray?) {
        for (y in y0 until y1) {
            var o = y * stride + x0
            for (x in x0 until x1) {
                if (mask == null || mask[o].toInt() != 0) add(argb[o])
                o++
            }
        }
    }

    private fun noteExact(key: Int) {
        var h = (key * -1640531535) ushr (32 - EXACT_TABLE_BITS)
        while (true) {
            val k = exactKeys[h]
            if (k == key) return
            if (k == -1) {
                if (exactCount >= EXACT_LIMIT) {
                    exactOverflow = true
                    return
                }
                exactKeys[h] = key
                exactCount++
                return
            }
            h = (h + 1) and (EXACT_TABLE - 1)
        }
    }

    /** The exact distinct colours when there are at most [limit] of them, sorted, else null. */
    fun exactColors(limit: Int): IntArray? {
        if (exactOverflow || exactCount > limit) return null
        val out = IntArray(exactCount)
        var n = 0
        for (k in exactKeys) if (k != -1) out[n++] = k
        out.sort()
        return out
    }

    companion object {
        const val BINS = 32 * 32 * 32
        const val EXACT_LIMIT = 256
        private const val EXACT_TABLE_BITS = 10
        private const val EXACT_TABLE = 1 shl EXACT_TABLE_BITS
    }
}

internal object MedianCut {

    /**
     * A palette of at most [maxColors] colours for [hist]. Exact when the pixels already fit;
     * otherwise a weighted median cut (always splitting the box with the most squared error, along
     * its widest channel, at the weighted median) refined by [refine] rounds of Lloyd's algorithm
     * over the bins. An empty histogram gives a one-colour black palette.
     */
    fun build(hist: ColorHistogram, maxColors: Int, refine: Int = 2): Palette {
        require(maxColors in 1..256)
        if (hist.total == 0L) return Palette(intArrayOf(0))
        hist.exactColors(maxColors)?.let { return Palette(it) }

        // The non-empty bins, each as its true mean colour and its pixel count.
        var n = 0
        for (i in 0 until ColorHistogram.BINS) if (hist.count[i] > 0) n++
        val ids = IntArray(n) { it }
        val w = DoubleArray(n)
        val c = Array(3) { DoubleArray(n) }
        var k = 0
        for (i in 0 until ColorHistogram.BINS) {
            val cnt = hist.count[i]
            if (cnt > 0) {
                w[k] = cnt.toDouble()
                c[0][k] = hist.sumR[i].toDouble() / cnt
                c[1][k] = hist.sumG[i].toDouble() / cnt
                c[2][k] = hist.sumB[i].toDouble() / cnt
                k++
            }
        }

        val boxLo = IntArray(maxColors)
        val boxHi = IntArray(maxColors)
        val boxSse = DoubleArray(maxColors)
        var boxes = 1
        boxHi[0] = n
        boxSse[0] = sse(ids, 0, n, w, c)

        while (boxes < maxColors) {
            var best = -1
            var bestSse = 0.0
            for (b in 0 until boxes) {
                if (boxHi[b] - boxLo[b] >= 2 && boxSse[b] > bestSse) {
                    bestSse = boxSse[b]
                    best = b
                }
            }
            if (best < 0) break
            val lo = boxLo[best]
            val hi = boxHi[best]
            val axis = widestAxis(ids, lo, hi, c)
            sortByAxis(ids, lo, hi, c[axis])
            // Split where the running weight first reaches half, keeping at least one bin each side.
            var total = 0.0
            for (i in lo until hi) total += w[ids[i]]
            var run = 0.0
            var mid = lo + 1
            for (i in lo until hi - 1) {
                run += w[ids[i]]
                mid = i + 1
                if (run * 2 >= total) break
            }
            boxLo[boxes] = mid
            boxHi[boxes] = hi
            boxHi[best] = mid
            boxSse[best] = sse(ids, lo, mid, w, c)
            boxSse[boxes] = sse(ids, mid, hi, w, c)
            boxes++
        }

        val cr = DoubleArray(boxes)
        val cg = DoubleArray(boxes)
        val cb = DoubleArray(boxes)
        for (b in 0 until boxes) {
            var sw = 0.0
            var r = 0.0
            var g = 0.0
            var bl = 0.0
            for (i in boxLo[b] until boxHi[b]) {
                val id = ids[i]
                sw += w[id]
                r += w[id] * c[0][id]
                g += w[id] * c[1][id]
                bl += w[id] * c[2][id]
            }
            cr[b] = r / sw
            cg[b] = g / sw
            cb[b] = bl / sw
        }
        repeat(refine) { lloyd(w, c, cr, cg, cb) }
        val out = IntArray(boxes) { b -> pack(cr[b], cg[b], cb[b]) }
        return Palette(out)
    }

    private fun pack(r: Double, g: Double, b: Double): Int =
        (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)

    private fun clamp8(v: Double): Int = (v + 0.5).toInt().coerceIn(0, 255)

    private fun sse(ids: IntArray, lo: Int, hi: Int, w: DoubleArray, c: Array<DoubleArray>): Double {
        var sw = 0.0
        val s = DoubleArray(3)
        val s2 = DoubleArray(3)
        for (i in lo until hi) {
            val id = ids[i]
            sw += w[id]
            for (a in 0..2) {
                val v = c[a][id]
                s[a] += w[id] * v
                s2[a] += w[id] * v * v
            }
        }
        if (sw == 0.0) return 0.0
        var e = 0.0
        for (a in 0..2) e += s2[a] - s[a] * s[a] / sw
        return e
    }

    private fun widestAxis(ids: IntArray, lo: Int, hi: Int, c: Array<DoubleArray>): Int {
        var bestAxis = 0
        var bestRange = -1.0
        for (a in 0..2) {
            var mn = Double.MAX_VALUE
            var mx = -Double.MAX_VALUE
            for (i in lo until hi) {
                val v = c[a][ids[i]]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mx - mn > bestRange) {
                bestRange = mx - mn
                bestAxis = a
            }
        }
        return bestAxis
    }

    /** Sort `ids[lo, hi)` by [key] (ties by id, so the result never depends on the sort's mood). */
    private fun sortByAxis(ids: IntArray, lo: Int, hi: Int, key: DoubleArray) {
        val tmp = LongArray(hi - lo)
        for (i in lo until hi) {
            val id = ids[i]
            tmp[i - lo] = ((key[id] * 256.0).toLong() shl 20) or id.toLong()
        }
        Arrays.sort(tmp)
        for (i in lo until hi) ids[i] = (tmp[i - lo] and 0xFFFFF).toInt()
    }

    /** One round of k-means over the bins, each bin going to its nearest centre. Centres nobody wants stay put. */
    private fun lloyd(w: DoubleArray, c: Array<DoubleArray>, cr: DoubleArray, cg: DoubleArray, cb: DoubleArray) {
        val k = cr.size
        val sw = DoubleArray(k)
        val sr = DoubleArray(k)
        val sg = DoubleArray(k)
        val sb = DoubleArray(k)
        for (i in w.indices) {
            var best = 0
            var bd = Double.MAX_VALUE
            for (j in 0 until k) {
                val dr = c[0][i] - cr[j]
                val dg = c[1][i] - cg[j]
                val db = c[2][i] - cb[j]
                val d = dr * dr + dg * dg + db * db
                if (d < bd) {
                    bd = d
                    best = j
                }
            }
            sw[best] += w[i]
            sr[best] += w[i] * c[0][i]
            sg[best] += w[i] * c[1][i]
            sb[best] += w[i] * c[2][i]
        }
        for (j in 0 until k) {
            if (sw[j] > 0.0) {
                cr[j] = sr[j] / sw[j]
                cg[j] = sg[j] / sw[j]
                cb[j] = sb[j] / sw[j]
            }
        }
    }
}

/**
 * Exact nearest-colour search over a palette. The colour cube is cut into 8-level cells; the first
 * time a pixel lands in a cell, the palette entries that could be nearest to ANY point of that cell
 * are listed (an entry is kept when its distance to the cell centre is within the nearest entry's
 * distance plus the cell's diagonal), and later pixels in the cell only compare against that short
 * list. The answer is the same as a scan of the whole palette, with ties going to the lower index.
 */
internal class PaletteLookup(private val palette: IntArray) {
    private val cellStart = IntArray(CELLS) { -1 }
    private val cellLen = IntArray(CELLS)
    private var cand = IntArray(2048)
    private var candSize = 0

    fun nearest(rgb: Int): Int {
        val r = (rgb shr 16) and 255
        val g = (rgb shr 8) and 255
        val b = rgb and 255
        return nearest(r, g, b)
    }

    fun nearest(r: Int, g: Int, b: Int): Int {
        if (palette.size == 1) return 0
        val cell = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        if (cellStart[cell] < 0) buildCell(cell)
        var best = -1
        var bd = Int.MAX_VALUE
        val s = cellStart[cell]
        for (i in s until s + cellLen[cell]) {
            val j = cand[i]
            val p = palette[j]
            val dr = ((p shr 16) and 255) - r
            val dg = ((p shr 8) and 255) - g
            val db = (p and 255) - b
            val d = dr * dr + dg * dg + db * db
            if (d < bd || (d == bd && j < best)) {
                bd = d
                best = j
            }
        }
        return best
    }

    private fun buildCell(cell: Int) {
        val cr = ((cell shr 10) shl 3) + 3.5
        val cg = (((cell shr 5) and 31) shl 3) + 3.5
        val cb = ((cell and 31) shl 3) + 3.5
        val n = palette.size
        val dist = DoubleArray(n)
        var dmin = Double.MAX_VALUE
        for (j in 0 until n) {
            val p = palette[j]
            val dr = ((p shr 16) and 255) - cr
            val dg = ((p shr 8) and 255) - cg
            val db = (p and 255) - cb
            val d = sqrt(dr * dr + dg * dg + db * db)
            dist[j] = d
            if (d < dmin) dmin = d
        }
        // Any point of the cell is within half the diagonal (sqrt(3) * 4) of the centre.
        val limit = dmin + 2 * HALF_DIAGONAL + 1e-6
        if (candSize + n > cand.size) cand = cand.copyOf(max(cand.size * 2, candSize + n))
        cellStart[cell] = candSize
        var len = 0
        for (j in 0 until n) if (dist[j] <= limit) {
            cand[candSize++] = j
            len++
        }
        cellLen[cell] = len
    }

    private companion object {
        const val CELLS = 32 * 32 * 32
        val HALF_DIAGONAL = sqrt(3.0) * 4.0
    }
}

/** The squared RGB distance between two 0x..RRGGBB colours. */
internal fun rgbDistance2(a: Int, b: Int): Int {
    val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
    val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
    val db = (a and 255) - (b and 255)
    return dr * dr + dg * dg + db * db
}

internal object Dither {

    /**
     * Map the rectangle [x0, x1) x [y0, y1) of [argb] (a canvas [stride] pixels wide) to palette
     * indices in [out], laid out rect-row by rect-row. A pixel whose [mask] byte is zero (or, with a
     * null mask, whose alpha is below the cutoff) gets [transparentIndex] and neither takes nor gives
     * error. With [strength] above zero, Floyd-Steinberg error diffusion is run in serpentine order
     * with the error scaled by [strength] (1 is the classic filter, 0 is plain nearest colour).
     * The error is clamped to 128 levels a channel per pixel so one colour the palette cannot reach does not smear a long streak.
     */
    fun map(
        argb: IntArray,
        stride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        mask: ByteArray?,
        lookup: PaletteLookup,
        palette: IntArray,
        transparentIndex: Int,
        strength: Float,
        out: ByteArray,
    ) {
        val rw = x1 - x0
        val rh = y1 - y0
        require(out.size >= rw * rh)
        val dither = strength > 0f
        // Error rows for the current and the next scanline, 3 channels, one pixel of margin each side.
        var cur = FloatArray((rw + 2) * 3)
        var nxt = FloatArray((rw + 2) * 3)
        for (ry in 0 until rh) {
            val y = y0 + ry
            val leftToRight = !dither || ry % 2 == 0
            var rx = if (leftToRight) 0 else rw - 1
            val step = if (leftToRight) 1 else -1
            java.util.Arrays.fill(nxt, 0f)
            for (count in 0 until rw) {
                val o = y * stride + x0 + rx
                val oi = ry * rw + rx
                val opaque = if (mask != null) mask[o].toInt() != 0 else (argb[o] ushr 24) >= AnimSequence.ALPHA_CUTOFF
                if (!opaque) {
                    out[oi] = transparentIndex.toByte()
                } else {
                    val p = argb[o]
                    var r = (p shr 16) and 255
                    var g = (p shr 8) and 255
                    var b = p and 255
                    val e = (rx + 1) * 3
                    if (dither) {
                        r = (r + cur[e]).toInt().coerceIn(0, 255)
                        g = (g + cur[e + 1]).toInt().coerceIn(0, 255)
                        b = (b + cur[e + 2]).toInt().coerceIn(0, 255)
                    }
                    val idx = lookup.nearest(r, g, b)
                    out[oi] = idx.toByte()
                    if (dither) {
                        val q = palette[idx]
                        val er = ((r - ((q shr 16) and 255)) * strength).coerceIn(-MAX_ERROR, MAX_ERROR)
                        val eg = ((g - ((q shr 8) and 255)) * strength).coerceIn(-MAX_ERROR, MAX_ERROR)
                        val eb = ((b - (q and 255)) * strength).coerceIn(-MAX_ERROR, MAX_ERROR)
                        val ahead = e + step * 3
                        val below = e
                        val belowAhead = e + step * 3
                        val belowBehind = e - step * 3
                        spread(cur, ahead, er, eg, eb, 7f / 16f)
                        spread(nxt, belowBehind, er, eg, eb, 3f / 16f)
                        spread(nxt, below, er, eg, eb, 5f / 16f)
                        spread(nxt, belowAhead, er, eg, eb, 1f / 16f)
                    }
                }
                rx += step
            }
            val t = cur
            cur = nxt
            nxt = t
        }
    }

    private fun spread(row: FloatArray, at: Int, er: Float, eg: Float, eb: Float, k: Float) {
        row[at] += er * k
        row[at + 1] += eg * k
        row[at + 2] += eb * k
    }

    private const val MAX_ERROR = 128f
}
