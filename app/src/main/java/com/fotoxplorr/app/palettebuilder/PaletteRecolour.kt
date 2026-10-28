package com.fotoxplorr.app.palettebuilder

import kotlin.math.sqrt

/**
 * Pulls the colours of a picture toward a palette: each pixel goes to the palette colour nearest it in
 * Lab, with a smooth hand-over where two palette colours are almost equally near.
 *
 * Pure arithmetic over packed ARGB arrays, deterministic (no random choice, no state kept between pixels
 * beyond a same-as-the-last-pixel shortcut that cannot change an answer), so a JVM test pins it and the
 * seam on top only decides how pixels get in and out.
 *
 * The rule, per pixel: let d_i be the Lab distance to palette colour i and d_min the smallest. Colour i
 * counts with weight `(1 - (d_i - d_min) / softness)^2` when that difference is under [softness], else 0.
 * The nearest always has weight 1, so away from the borders between palette colours the pixel simply takes
 * the nearest colour, and across a border the two blend over a band about [softness] wide. A pixel that
 * equals a palette colour exactly maps to it exactly, provided no other palette colour lies within
 * [softness] of it (two swatches closer than that are one colour to this mapping, and [PaletteMerge]
 * already folds those). The blend is done in Lab and gamut-mapped back to sRGB.
 *
 * [amount] (0..1) mixes the result back toward the original pixel, and [keepLightness] (0..1) keeps that
 * share of the ORIGINAL lightness so shading survives (at 0 the result is flat palette colours; at 1 only
 * hue and chroma come from the palette). Alpha is carried through untouched.
 *
 * Memory: nothing here allocates per pixel. A picture is processed in bands of whole rows ([bands]) so a
 * caller holds one band of pixels rather than the whole bitmap; every pixel is independent, so the result is
 * identical to processing the picture in one piece (a test pins it). An instance is safe to share between
 * threads: it keeps no per-call state.
 */
class PaletteRecolour(
    colors: List<Int>,
    val softness: Double = DEFAULT_SOFTNESS,
    val amount: Float = 1f,
    val keepLightness: Float = 0f,
) {
    private val palette: IntArray = colors.map { ColorMath.opaque(it) }.toIntArray()
    private val paletteLab: Array<DoubleArray> = Array(palette.size) { DoubleArray(3).also { out -> ColorMath.labInto(palette[it], out) } }
    private val soft: Double = softness.coerceAtLeast(MIN_SOFTNESS)
    private val mix: Float = amount.coerceIn(0f, 1f)
    private val keep: Double = keepLightness.coerceIn(0f, 1f).toDouble()

    init {
        require(palette.isNotEmpty()) { "recolouring needs at least one palette colour" }
    }

    constructor(palette: Palette, softness: Double = DEFAULT_SOFTNESS, amount: Float = 1f, keepLightness: Float = 0f) :
        this(palette.argbs, softness, amount, keepLightness)

    /** The recoloured pixel for [argb]; its alpha is kept. */
    fun mapPixel(argb: Int): Int = mapWith(argb, DoubleArray(3), DoubleArray(palette.size), DoubleArray(3))

    /** Recolours `count` pixels from `src[0..count)` into `dst[0..count)`; `dst` may be `src` (in place). */
    fun mapInto(src: IntArray, dst: IntArray, count: Int = src.size) {
        require(count in 0..src.size && count <= dst.size) { "count $count does not fit the arrays" }
        val lab = DoubleArray(3)
        val weights = DoubleArray(palette.size)
        val blended = DoubleArray(3)
        var lastIn = 0
        var lastOut = 0
        var haveLast = false
        for (i in 0 until count) {
            val p = src[i]
            if (haveLast && p == lastIn) {
                dst[i] = lastOut
                continue
            }
            val out = mapWith(p, lab, weights, blended)
            lastIn = p
            lastOut = out
            haveLast = true
            dst[i] = out
        }
    }

    /** A new array of the recoloured [src]. */
    fun mapAll(src: IntArray): IntArray = IntArray(src.size).also { mapInto(src, it) }

    private fun mapWith(argb: Int, lab: DoubleArray, weights: DoubleArray, blended: DoubleArray): Int {
        val alpha = argb and (0xFF shl 24)
        ColorMath.labInto(argb, lab)

        var nearest = 0
        var nearestD = Double.MAX_VALUE
        for (i in palette.indices) {
            val pl = paletteLab[i]
            val dl = lab[0] - pl[0]
            val da = lab[1] - pl[1]
            val db = lab[2] - pl[2]
            val d = sqrt(dl * dl + da * da + db * db)
            weights[i] = d
            if (d < nearestD) {
                nearestD = d
                nearest = i
            }
        }
        var total = 0.0
        var contributors = 0
        for (i in palette.indices) {
            val t = (weights[i] - nearestD) / soft
            val w = if (t >= 1.0) 0.0 else (1.0 - t) * (1.0 - t)
            weights[i] = w
            total += w
            if (w > 0.0) contributors++
        }

        var result: Int
        if (contributors == 1 && keep == 0.0) {
            result = palette[nearest]
        } else {
            blended[0] = 0.0
            blended[1] = 0.0
            blended[2] = 0.0
            for (i in palette.indices) {
                val w = weights[i] / total
                if (w == 0.0) continue
                blended[0] += w * paletteLab[i][0]
                blended[1] += w * paletteLab[i][1]
                blended[2] += w * paletteLab[i][2]
            }
            val l = blended[0] * (1 - keep) + lab[0] * keep
            result = ColorMath.fromLab(l, blended[1], blended[2])
        }

        if (mix < 1f) {
            val m = mix.toDouble()
            result = ColorMath.rgb(
                mixChannel(ColorMath.red(argb), ColorMath.red(result), m),
                mixChannel(ColorMath.green(argb), ColorMath.green(result), m),
                mixChannel(ColorMath.blue(argb), ColorMath.blue(result), m),
            )
        }
        return alpha or (result and 0x00FFFFFF)
    }

    private fun mixChannel(from: Int, to: Int, m: Double): Int = Math.round(from + (to - from) * m).toInt()

    companion object {
        /** Lab distance over which two palette colours hand over to each other. */
        const val DEFAULT_SOFTNESS = 10.0

        /** The floor for [softness]: at 0 the hand-over would divide by zero, so it becomes a hard nearest-colour pick. */
        const val MIN_SOFTNESS = 1e-6

        /** Pixels per band when the caller has no better figure: about 1M pixels, 4 MB of ARGB. */
        const val DEFAULT_BAND_PIXELS = 1 shl 20

        /**
         * The row ranges that cover `height` rows in bands of at most [maxPixelsPerBand] pixels (at least one
         * row each, even if a single row is wider). Each is `first..last` inclusive; together they tile the
         * picture with no gap or overlap. `width` or `height` of 0 or less gives no bands.
         */
        fun bands(width: Int, height: Int, maxPixelsPerBand: Int = DEFAULT_BAND_PIXELS): List<IntRange> {
            if (width <= 0 || height <= 0) return emptyList()
            val rows = (maxPixelsPerBand.coerceAtLeast(1) / width).coerceAtLeast(1)
            val out = ArrayList<IntRange>((height + rows - 1) / rows)
            var y = 0
            while (y < height) {
                val last = minOf(y + rows, height) - 1
                out += y..last
                y = last + 1
            }
            return out
        }
    }
}
