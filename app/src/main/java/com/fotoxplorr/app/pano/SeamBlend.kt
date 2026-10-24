package com.fotoxplorr.app.pano

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Joining two photos that already sit on the same canvas: where does one stop and the other start,
 * and how softly? Pure Kotlin over ARGB bands, so the compositor can call it once per band and a
 * test can call it on a handful of pixels.
 *
 * ## The idea (slice 1)
 * Inside the columns the two photos share, the seam is a straight vertical line at the MIDDLE of
 * the overlap, and the join is feathered: across about [DEFAULT_FEATHER] columns centred on that
 * line the second photo fades in with a cosine ramp (0 -> 1), so a small difference in exposure or
 * a pixel or two of misalignment becomes a soft gradient instead of a hard step. Left of the ramp
 * the first photo is used untouched, right of it the second. The seam sits in the middle so that
 * each photo contributes the part of the overlap that is nearer ITS own centre, where it is
 * sharpest and least vignetted.
 *
 * ## What it does not do
 * A straight seam cannot steer around a moving person or a near pole, and a feather averages
 * misaligned detail into a ghost instead of hiding it. A dynamic-programming seam that follows the
 * least-different path is the planned replacement; everything goes through [blendBand], so swapping
 * the seam in changes nothing above it. Exposure is NOT matched here -- apply a per-photo gain first.
 *
 * ## Validity
 * A pixel's alpha says whether the photo covers it: 0 means no data (outside the photo, or off the
 * edge of the cylinder warp), anything else means it does. Where only one photo has data it wins
 * whatever the seam says, so a vertical jitter between frames never fades a photo towards black.
 * Output pixels are fully opaque where either photo had data and 0 where neither did.
 */
object SeamBlend {

    /** The feather width the compositor uses, in output columns. */
    const val DEFAULT_FEATHER = 32

    /** Fixed-point 1.0: weights are integers in 0..[WEIGHT_ONE], so they sum to exactly one and equal photos blend to themselves. */
    const val WEIGHT_ONE = 4096

    private const val WEIGHT_HALF = WEIGHT_ONE / 2

    /**
     * The share of the SECOND photo in each of [width] columns, in 0..[WEIGHT_ONE] (the first photo
     * gets the rest). The overlap is columns [overlapStart] until [overlapEnd]; both are clamped into
     * the band and the end is not allowed before the start, so a gap between the photos (end <= start)
     * behaves as a zero-width overlap: a hard cut at [overlapStart]. The ramp is [feather] columns
     * wide, but never wider than the overlap, centred on its middle, and non-decreasing; the weights
     * mirror around the middle, so each pair adds to exactly [WEIGHT_ONE].
     */
    fun secondWeights(width: Int, overlapStart: Int, overlapEnd: Int, feather: Int = DEFAULT_FEATHER): IntArray {
        require(width >= 0) { "width must not be negative" }
        require(feather >= 0) { "feather must not be negative" }
        val start = overlapStart.coerceIn(0, width)
        val end = overlapEnd.coerceIn(start, width)
        val ramp = minOf(feather, end - start)
        val rampStart = start + (end - start - ramp) / 2
        val weights = IntArray(width)
        for (x in 0 until width) {
            weights[x] = when {
                x < rampStart -> 0
                x >= rampStart + ramp -> WEIGHT_ONE
                else -> rampWeight(x - rampStart, ramp)
            }
        }
        return weights
    }

    /** Cosine ramp, the k-th of [n] columns; the upper half is the mirror of the lower so the ramp is exactly symmetric. */
    private fun rampWeight(k: Int, n: Int): Int = when {
        2 * k + 1 < n -> (WEIGHT_ONE * (0.5 - 0.5 * cos(PI * (k + 0.5) / n))).roundToInt()
        2 * k + 1 == n -> WEIGHT_HALF
        else -> WEIGHT_ONE - rampWeight(n - 1 - k, n)
    }

    /**
     * Blend [first] and [second], two [width] x [rows] ARGB bands on the same canvas, into [out]
     * (which may be either input: every pixel is read before it is written). The photos share the
     * columns [overlapStart] until [overlapEnd] (see [secondWeights]); the first is the one on the
     * left. Returns [out], allocating it when not given.
     *
     * Today this is [secondWeights] applied per column. A seam that wanders from row to row would
     * take its per-row position here and keep the same contract.
     */
    fun blendBand(
        first: IntArray,
        second: IntArray,
        width: Int,
        rows: Int,
        overlapStart: Int,
        overlapEnd: Int,
        feather: Int = DEFAULT_FEATHER,
        out: IntArray = IntArray(first.size),
    ): IntArray {
        require(width >= 0 && rows >= 0) { "a band must not have negative size" }
        val size = width.toLong() * rows
        require(first.size.toLong() == size && second.size.toLong() == size && out.size.toLong() == size) {
            "bands must hold ${width}x$rows pixels"
        }
        val weights = secondWeights(width, overlapStart, overlapEnd, feather)
        var i = 0
        for (row in 0 until rows) {
            for (x in 0 until width) {
                out[i] = mix(first[i], second[i], weights[x])
                i++
            }
        }
        return out
    }

    /** One pixel: [w] is the second photo's share in 0..[WEIGHT_ONE]; a pixel with no data (alpha 0) gives way to the other. */
    private fun mix(a: Int, b: Int, w: Int): Int {
        val aValid = a ushr 24 != 0
        val bValid = b ushr 24 != 0
        return when {
            !aValid && !bValid -> 0
            !bValid -> a or OPAQUE
            !aValid -> b or OPAQUE
            w == 0 -> a or OPAQUE
            w == WEIGHT_ONE -> b or OPAQUE
            else -> {
                val v = WEIGHT_ONE - w
                OPAQUE or
                    (channel(a, b, v, w, 16) shl 16) or
                    (channel(a, b, v, w, 8) shl 8) or
                    channel(a, b, v, w, 0)
            }
        }
    }

    private fun channel(a: Int, b: Int, v: Int, w: Int, shift: Int): Int =
        ((((a ushr shift) and 0xFF) * v + ((b ushr shift) and 0xFF) * w + WEIGHT_HALF) ushr 12)

    private const val OPAQUE = 0xFF shl 24
}
