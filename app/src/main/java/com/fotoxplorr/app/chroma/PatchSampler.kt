package com.fotoxplorr.app.chroma

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a patch of a frame says, in linear light.
 *
 * @property linear the per-channel MEDIAN of the pixels that were not clipped or crushed.
 * @property standardError how far [linear] could be from the patch's true level because of noise,
 *   per channel: the standard error of a median (1.2533 sigma / sqrt n, sigma from the median
 *   absolute deviation) but never less than a third of one 8-bit step, because the pixels are
 *   rounded and a perfectly flat noise-free patch is still only known to the nearest code.
 * @property used how many of the sampled pixels went into the median.
 * @property evenness the interquartile range of the patch's luminance divided by its median: about
 *   0.03 for a flat patch with 2% noise, large when the patch straddles an edge.
 * @property clippedShare the share of the sampled pixels at or above [PatchSampler.CLIP_CODE] in any channel.
 */
internal class Patch(
    val linear: LinearRgb,
    val standardError: LinearRgb,
    val used: Int,
    val evenness: Double,
    val clippedShare: Double,
)

/**
 * Robust sampling of a plain area of a frame.
 *
 * A pixel is left out when any channel is at or above [CLIP_CODE] (the sensor or the JPEG clipped
 * it, so its true level is higher and unknowable). Dark pixels are KEPT: on a dark surface the codes
 * scatter about the floor, and dropping the low ones would leave a median taken over the noisy upper half,
 * too light. The remaining pixels give a per-channel median, which ignores a speck of dust, a hair, a
 * highlight, or a stray edge pixel that a mean would let through.
 *
 * It REFUSES rather than guess (a patch's evenness is judged only above [EVENNESS_MIN_LUMINANCE]): more than [MAX_CLIPPED_SHARE] of the patch clipped is [Refusal.CLIPPED];
 * a median brightest-channel code below [DARK_CODE] (where the black level and the noise floor dominate)
 * is [Refusal.TOO_DARK]; a patch whose luminance spread is wider
 * than [MAX_UNEVENNESS] times its median (two surfaces, a shadow edge) is [Refusal.UNEVEN].
 *
 * Very large regions are subsampled on a regular grid to about [MAX_SAMPLES] pixels; medians of a
 * regular subsample are as good as of the whole for a patch this size. Pure and deterministic.
 */
internal object PatchSampler {
    /** A pixel with a channel at or above this code is treated as clipped (sRGB 250 is linear 0.96). */
    const val CLIP_CODE = 250

    /** A patch whose median brightest-channel code is below this is crushed to black: too dark to read. */
    const val DARK_CODE = 8

    const val MAX_CLIPPED_SHARE = 0.10
    const val MAX_UNEVENNESS = 0.6
    const val MAX_SAMPLES = 90_000
    const val MIN_SAMPLES = 64

    /** A patch this dark (linear luminance, about sRGB code 12) is too near the noise floor to judge its evenness. */
    private const val EVENNESS_MIN_LUMINANCE = 0.002

    /**
     * @param keepDark true for an AMBIENT frame: a room with no light is a true answer there (the
     *   frame is black and the whole flash frame is the flash's), so the frame is never refused as too
     *   dark. Clipping is still refused.
     */
    fun sample(frame: ChromaFrame, region: PatchRegion = PatchRegion.CENTRE, keepDark: Boolean = false): Answer<Patch> {
        val x0 = (region.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = ceil(region.right * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = (region.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = ceil(region.bottom * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w.toLong() * h < MIN_SAMPLES) return Answer.Refused(Refusal.TOO_SMALL)

        val step = max(1, ceil(sqrt(w.toDouble() * h / MAX_SAMPLES)).toInt())
        val r = DoubleArray(((w + step - 1) / step) * ((h + step - 1) / step))
        val g = DoubleArray(r.size)
        val b = DoubleArray(r.size)
        val codes = IntArray(r.size) // the brightest channel code of each kept pixel, for the quantisation floor
        var seen = 0
        var clipped = 0
        var used = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = frame.argb[y * frame.width + x]
                val pr = (p shr 16) and 0xFF
                val pg = (p shr 8) and 0xFF
                val pb = p and 0xFF
                val top = max(pr, max(pg, pb))
                seen++
                if (top >= CLIP_CODE) {
                    clipped++
                } else {
                    r[used] = ColourSpace.decode(pr)
                    g[used] = ColourSpace.decode(pg)
                    b[used] = ColourSpace.decode(pb)
                    codes[used] = top
                    used++
                }
                x += step
            }
            y += step
        }
        if (seen < MIN_SAMPLES) return Answer.Refused(Refusal.TOO_SMALL)
        val clippedShare = clipped.toDouble() / seen
        if (clippedShare > MAX_CLIPPED_SHARE) return Answer.Refused(Refusal.CLIPPED)
        val medCode = codes.copyOf(used).also { it.sort() }[used / 2]
        if (!keepDark && medCode < DARK_CODE) return Answer.Refused(Refusal.TOO_DARK)

        val rs = r.copyOf(used).also { it.sort() }
        val gs = g.copyOf(used).also { it.sort() }
        val bs = b.copyOf(used).also { it.sort() }
        val lum = DoubleArray(used) { 0.2126729 * r[it] + 0.7151522 * g[it] + 0.0721750 * b[it] }.also { it.sort() }
        val medLum = median(lum)
        val evenness = if (medLum >= EVENNESS_MIN_LUMINANCE) (quantile(lum, 0.75) - quantile(lum, 0.25)) / medLum else 0.0
        if (evenness > MAX_UNEVENNESS) return Answer.Refused(Refusal.UNEVEN)

        val floor = ColourSpace.stepAt(medCode) / 3.0
        val medians = doubleArrayOf(median(rs), median(gs), median(bs))
        val sorted = arrayOf(rs, gs, bs)
        val se = DoubleArray(3) { c ->
            val sigma = 1.4826 * medianAbsDeviation(sorted[c], medians[c])
            max(1.2533 * sigma / sqrt(used.toDouble()), floor)
        }
        return Answer.Value(
            Patch(
                linear = LinearRgb(medians[0], medians[1], medians[2]),
                standardError = LinearRgb(se[0], se[1], se[2]),
                used = used,
                evenness = evenness,
                clippedShare = clippedShare,
            ),
        )
    }

    private fun median(sorted: DoubleArray): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun quantile(sorted: DoubleArray, q: Double): Double = sorted[((sorted.size - 1) * q).toInt()]

    private fun medianAbsDeviation(sorted: DoubleArray, med: Double): Double {
        val dev = DoubleArray(sorted.size) { kotlin.math.abs(sorted[it] - med) }
        dev.sort()
        return median(dev)
    }
}
