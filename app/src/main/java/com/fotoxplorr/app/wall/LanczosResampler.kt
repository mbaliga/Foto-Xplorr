package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.editor.applyUnsharpMask
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Enlarging a picture for a print: Lanczos, band by band, then a mild unsharp mask.
//
// This puts MORE PIXELS in a picture; it does not put more DETAIL in. A photo that has too few
// pixels for a print size comes out smooth and clean at the pixel count a lab asks for, instead of
// being stretched later by the lab's own cruder resize. Nothing here guesses at texture, faces or
// anything else the camera did not record, and the UI says so ("Enlarged, no new detail").
// ---------------------------------------------------------------------------

/** The Lanczos filter and the table of weights it gives for one axis. */
internal object Lanczos {
    /** Lobes each side: the usual "Lanczos3". */
    const val LOBES = 3

    /** Taps this close to the edge of the support weigh nothing, and are left out so that two mirrored outputs always get the same taps. */
    private const val EDGE_EPSILON = 1e-7

    /** `sinc(x) * sinc(x / a)` inside `|x| < a`, else 0. */
    fun kernel(x: Double, a: Int = LOBES): Double {
        if (x == 0.0) return 1.0
        val ax = abs(x)
        if (ax >= a) return 0.0
        val px = PI * x
        return a * sin(px) * sin(px / a) / (px * px)
    }

    /**
     * The weights that take [srcLen] samples to [dstLen] along one axis, one row of taps per output
     * index: `weights[i * stride + k]` is the weight of source sample `first[i] + k`, for `k < count[i]`.
     *
     * Output i sits at source position `(i + 0.5) * srcLen / dstLen - 0.5` (pixel centres line up,
     * so a picture is not shifted by half a pixel). The filter is stretched by the reduction when
     * [dstLen] is smaller, so a reduction averages rather than skips; this app only enlarges, but the
     * table is right either way. Taps that fall outside the picture are folded onto its edge pixel,
     * so the weights of every output still add up to one and a flat area stays flat to its border.
     * Every weight row is normalised in double, then stored as floats.
     */
    fun table(srcLen: Int, dstLen: Int, a: Int = LOBES): WeightTable {
        require(srcLen > 0 && dstLen > 0) { "Nothing to resample: $srcLen to $dstLen" }
        if (srcLen == dstLen) {
            // Exact, and the common case for one axis of a fitted crop: one tap of weight one.
            return WeightTable(srcLen, dstLen, IntArray(dstLen) { it }, IntArray(dstLen) { 1 }, FloatArray(dstLen) { 1f }, stride = 1)
        }
        val scale = srcLen.toDouble() / dstLen
        val filterScale = max(1.0, scale)
        val support = a * filterScale
        val stride = floor(2 * support).toInt() + 2
        val first = IntArray(dstLen)
        val count = IntArray(dstLen)
        val weights = FloatArray(Math.multiplyExact(dstLen, stride))
        val folded = DoubleArray(stride)
        for (i in 0 until dstLen) {
            val centre = (i + 0.5) * scale - 0.5
            val lo = ceil(centre - support + EDGE_EPSILON).toInt()
            val hi = floor(centre + support - EDGE_EPSILON).toInt()
            val from = max(lo, 0)
            val to = min(hi, srcLen - 1)
            val n = to - from + 1
            folded.fill(0.0, 0, n)
            for (j in lo..hi) folded[j.coerceIn(0, srcLen - 1) - from] += kernel((j - centre) / filterScale, a)
            var sum = 0.0
            for (k in 0 until n) sum += folded[k]
            first[i] = from
            count[i] = n
            if (sum > 0.0) {
                for (k in 0 until n) weights[i * stride + k] = (folded[k] / sum).toFloat()
            } else {
                // Cannot happen for a Lanczos table; a nearest pixel is the safe answer if it ever did.
                weights[i * stride + (centre.toInt().coerceIn(from, to) - from)] = 1f
            }
        }
        return WeightTable(srcLen, dstLen, first, count, weights, stride)
    }
}

/** One axis's weights, see [Lanczos.table]. Pure data. */
internal class WeightTable(
    val srcLen: Int,
    val dstLen: Int,
    /** The first source sample each output reads. Never decreases from one output to the next. */
    val first: IntArray,
    val count: IntArray,
    val weights: FloatArray,
    val stride: Int,
) {
    fun last(i: Int): Int = first[i] + count[i] - 1
}

/** Source rows, one at a time, as opaque ARGB. A picture that does not fit in memory twice can still be read this way. */
internal interface PixelRows {
    val width: Int
    val height: Int

    /** Fills the first [width] ints of [into] with row [y]. Alpha is ignored by the resampler: rows are taken as opaque. */
    fun readRow(y: Int, into: IntArray)
}

/** Where finished rows go: [rows] rows of [argb], starting at index [offset], are rows [firstRow] and on of the result. */
internal interface RowSink {
    fun write(firstRow: Int, rows: Int, argb: IntArray, offset: Int)
}

/** [PixelRows] over a plain array (tests, and anything already in memory). */
internal class IntArrayRows(val pixels: IntArray, override val width: Int, override val height: Int) : PixelRows {
    override fun readRow(y: Int, into: IntArray) {
        System.arraycopy(pixels, y * width, into, 0, width)
    }
}

internal object LanczosBand {
    /** Rows the mask needs on each side of a band to give a border row the same answer as the whole picture would. */
    const val HALO_ROWS = 2

    const val MAX_ROWS = 256

    /** What a band's three int buffers (the rows, the mask's blurred copy and its scratch) may weigh. A guide, not a promise. */
    const val BUDGET_BYTES = 12L * 1024 * 1024

    /**
     * The most source rows a band (with its [halo]) needs at once: how many horizontally filtered
     * rows the ring holds. Bounded by the band and the filter, not by the picture's height.
     */
    fun sourceRowsHeld(table: WeightTable, bandRows: Int, halo: Int): Int {
        var held = 1
        var y0 = 0
        while (y0 < table.dstLen) {
            val r0 = max(0, y0 - halo)
            val r1 = min(table.dstLen, y0 + bandRows + halo) - 1
            held = max(held, table.last(r1) - table.first[r0] + 1)
            y0 += bandRows
        }
        return held
    }
}

/**
 * Output rows per band for a result [width] pixels wide: as many as keep a band's int buffers near
 * [LanczosBand.BUDGET_BYTES], at least one, at most [LanczosBand.MAX_ROWS].
 */
internal fun bandRowsFor(width: Int): Int {
    if (width <= 0) return 1
    return (LanczosBand.BUDGET_BYTES / (12L * width)).coerceIn(1L, LanczosBand.MAX_ROWS.toLong()).toInt()
}

/** The largest enlargement this app makes: [MAX_EXPORT_EDGE] on an edge and [MAX_EXPORT_PIXELS] in all, the editor's own export limits. Long arithmetic: two fine edges can still overflow an Int. */
internal fun fitsEnlargeCap(width: Int, height: Int): Boolean =
    width in 1..MAX_EXPORT_EDGE && height in 1..MAX_EXPORT_EDGE && width.toLong() * height <= MAX_EXPORT_PIXELS

/**
 * How much unsharp mask follows an enlargement by [factor] (the editor's "sharpen" slider value, 0..1).
 * None for no enlargement; more as the factor grows, held to a gentle 0.25 (the editor's own
 * strength for that is 1.5 x, radius one pixel). Lanczos is already crisp on edges: this only takes
 * a little of the softness that a big factor leaves. Chosen by reasoning, not by looking at prints.
 */
internal fun enlargeSharpen(factor: Double): Float =
    if (factor > 1.0) ((factor - 1.0) * 0.1).coerceAtMost(0.25).toFloat() else 0f

/**
 * Resamples [source] to [dstW] x [dstH] with Lanczos3 and hands the result to [sink] a band of
 * output rows at a time, in order.
 *
 * Separable: the horizontal pass filters each source row once into a small ring of float rows
 * (the "band buffer"), the vertical pass combines those into the band's output rows. At no point
 * is there a float copy of the picture, or of the result: memory is the ring (a band's worth of
 * source rows, each [dstW] x 3 floats), one band of ints, and whatever the source and sink hold.
 * Each source row is read once. The result does not depend on [bandRows]: every pixel is the same
 * float sum in the same order.
 *
 * With a non-zero [sharpen] the editor's unsharp mask ([applyUnsharpMask], sharpen only) runs on
 * each band with [LanczosBand.HALO_ROWS] extra rows each side, and only the middle rows are kept; the
 * mask reaches one pixel, so a band's kept rows come out as they would from the mask over the whole
 * picture (a test pins that against the editor's function itself).
 *
 * [checkCancelled] is called before each band and may throw (a coroutine's `ensureActive`);
 * nothing after that band is read or written. [onProgress] gets the share of output rows written.
 * A single band is not interrupted. The target must be within [fitsEnlargeCap]; checked before
 * anything is allocated.
 */
internal fun lanczosResample(
    source: PixelRows,
    dstW: Int,
    dstH: Int,
    sink: RowSink,
    sharpen: Float = 0f,
    bandRows: Int = bandRowsFor(dstW),
    checkCancelled: () -> Unit = {},
    onProgress: (Float) -> Unit = {},
) {
    require(fitsEnlargeCap(dstW, dstH)) { "A $dstW x $dstH result is over the limits" }
    require(source.width > 0 && source.height > 0) { "Nothing to resample" }
    require(bandRows >= 1) { "A band has at least one row" }
    val halo = if (sharpen != 0f) LanczosBand.HALO_ROWS else 0
    require((bandRows + 2L * halo) * dstW <= Int.MAX_VALUE) { "A band of $bandRows rows is too big" }

    val xt = Lanczos.table(source.width, dstW)
    val yt = Lanczos.table(source.height, dstH)
    val capacity = LanczosBand.sourceRowsHeld(yt, bandRows, halo)
    val ring = Array(capacity) { FloatArray(dstW * 3) }
    val sourceRow = IntArray(source.width)
    val acc = FloatArray(dstW * 3)
    var held = -1 // the last source row in the ring
    var band = IntArray(0)

    var y0 = 0
    while (y0 < dstH) {
        checkCancelled()
        val y1 = min(dstH, y0 + bandRows)
        val r0 = max(0, y0 - halo)
        val r1 = min(dstH, y1 + halo) - 1
        val rows = r1 - r0 + 1

        // Horizontal pass for the source rows this band needs that are not in the ring yet.
        for (sy in max(yt.first[r0], held + 1)..yt.last(r1)) {
            source.readRow(sy, sourceRow)
            horizontal(sourceRow, xt, ring[sy % capacity])
            held = sy
        }

        // Vertical pass, one output row at a time.
        if (band.size != rows * dstW) band = IntArray(rows * dstW)
        for (r in r0..r1) {
            acc.fill(0f)
            val base = r * yt.stride
            for (k in 0 until yt.count[r]) {
                val w = yt.weights[base + k]
                val row = ring[(yt.first[r] + k) % capacity]
                for (i in acc.indices) acc[i] += w * row[i]
            }
            var o = (r - r0) * dstW
            var i = 0
            while (i < acc.size) {
                band[o++] = OPAQUE or (toByte(acc[i]) shl 16) or (toByte(acc[i + 1]) shl 8) or toByte(acc[i + 2])
                i += 3
            }
        }

        if (sharpen != 0f) applyUnsharpMask(band, dstW, rows, sharpen, 0f)
        sink.write(y0, y1 - y0, band, (y0 - r0) * dstW)
        onProgress(y1.toFloat() / dstH)
        y0 = y1
    }
}

private const val OPAQUE = 0xFF000000.toInt()

private fun toByte(v: Float): Int {
    val i = (v + 0.5f).toInt()
    return if (i < 0) 0 else if (i > 255) 255 else i
}

/** One source row filtered along its width into [out], three floats (R, G, B) per output pixel. */
private fun horizontal(row: IntArray, xt: WeightTable, out: FloatArray) {
    var o = 0
    for (x in 0 until xt.dstLen) {
        val f = xt.first[x]
        val base = x * xt.stride
        var r = 0f
        var g = 0f
        var b = 0f
        for (k in 0 until xt.count[x]) {
            val p = row[f + k]
            val w = xt.weights[base + k]
            r += w * (p ushr 16 and 0xFF)
            g += w * (p ushr 8 and 0xFF)
            b += w * (p and 0xFF)
        }
        out[o] = r
        out[o + 1] = g
        out[o + 2] = b
        o += 3
    }
}

// ---------------------------------------------------------------------------
// The Android end: a bitmap in, a bitmap out
// ---------------------------------------------------------------------------

/**
 * The [crop] part of [bitmap], row by row, flattened onto white. `getPixels` hands back sRGB
 * whatever the bitmap's own space (a Display-P3 photo is converted, and clipped to sRGB's range as
 * any 8-bit conversion is), and transparency is composited on white so it never turns black.
 */
internal class BitmapRows(private val bitmap: Bitmap, private val crop: PixelRect) : PixelRows {
    private val hasAlpha = bitmap.hasAlpha()
    override val width get() = crop.width
    override val height get() = crop.height

    override fun readRow(y: Int, into: IntArray) {
        bitmap.getPixels(into, 0, crop.width, crop.left, crop.top + y, crop.width, 1)
        for (i in 0 until crop.width) {
            val p = into[i]
            val a = p ushr 24
            into[i] = if (!hasAlpha || a == 255) {
                p or OPAQUE
            } else {
                OPAQUE or (onWhite(p ushr 16 and 0xFF, a) shl 16) or (onWhite(p ushr 8 and 0xFF, a) shl 8) or onWhite(p and 0xFF, a)
            }
        }
    }

    private fun onWhite(c: Int, a: Int): Int = (c * a + 255 * (255 - a) + 127) / 255
}

/** Writes bands into [bitmap] (sRGB, any size the caller made it for). */
internal class BitmapSink(private val bitmap: Bitmap) : RowSink {
    override fun write(firstRow: Int, rows: Int, argb: IntArray, offset: Int) {
        bitmap.setPixels(argb, offset, bitmap.width, 0, firstRow, bitmap.width, rows)
    }
}

/**
 * The frame's part of [src] ([coverRect] at [aspect]), enlarged to [outW] x [outH] by
 * [lanczosResample] and sharpened a little ([enlargeSharpen]), as a new opaque sRGB bitmap.
 * [src] is not recycled. If anything throws (including a cancellation from [checkCancelled], or
 * running out of memory) the new bitmap is recycled first.
 */
internal fun enlargeBitmap(
    src: Bitmap,
    aspect: Double,
    outW: Int,
    outH: Int,
    checkCancelled: () -> Unit = {},
    onProgress: (Float) -> Unit = {},
): Bitmap {
    val from = coverRect(src.width, src.height, aspect)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
    try {
        lanczosResample(
            BitmapRows(src, from), outW, outH, BitmapSink(out),
            sharpen = enlargeSharpen(outW.toDouble() / from.width),
            checkCancelled = checkCancelled,
            onProgress = onProgress,
        )
        return out
    } catch (t: Throwable) {
        out.recycle()
        throw t
    }
}
