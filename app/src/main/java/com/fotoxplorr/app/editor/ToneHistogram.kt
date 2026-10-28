package com.fotoxplorr.app.editor

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-channel and luma histograms of an image, 256 bins each -- the backdrop a curve graph is
 * drawn over, so the user can see where the photograph's tones actually sit before bending them.
 *
 * Pure over an IntArray of packed ARGB pixels, like the rest of the colour pipeline, so it is
 * testable on the JVM; the caller hands it a small downsampled copy (a few hundred pixels a side
 * is plenty for a 200dp graph and costs well under a frame).
 *
 * Luma is Rec. 709 and rounded, the same weighting [applyColour] and [AutoFix] use, so the
 * histogram's idea of "bright" is the pipeline's.
 */
class ToneHistogram(
    val luma: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
) {
    /** Number of pixels counted -- every bin array sums to this. */
    val total: Int get() = luma.sum()

    companion object {
        const val BINS = 256

        /**
         * Count [pixels]. Fully transparent pixels are skipped: their RGB is whatever the decoder
         * left behind (usually black), and counting them would pile a spike on bin 0.
         */
        fun of(pixels: IntArray): ToneHistogram {
            val luma = IntArray(BINS)
            val red = IntArray(BINS)
            val green = IntArray(BINS)
            val blue = IntArray(BINS)
            for (pixel in pixels) {
                if (pixel ushr 24 == 0) continue
                val r = pixel ushr 16 and 0xFF
                val g = pixel ushr 8 and 0xFF
                val b = pixel and 0xFF
                red[r]++
                green[g]++
                blue[b]++
                luma[(0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)]++
            }
            return ToneHistogram(luma, red, green, blue)
        }

        /**
         * [bins] scaled to 0..1 for drawing.
         *
         * Two things keep a real photo readable. The peak is taken ignoring the two end bins,
         * because clipped shadows or highlights routinely hold a third of all pixels in one bin
         * and would flatten everything else to a line; the end bins are then clamped to 1. And the
         * square root compresses the remaining dynamic range, so a long tail of small counts is
         * still visible. An all-end-bins image (pure black, pure white) falls back to its true peak.
         */
        fun normalised(bins: IntArray): FloatArray {
            if (bins.isEmpty()) return FloatArray(0)
            var peak = 0
            for (i in 1 until bins.size - 1) if (bins[i] > peak) peak = bins[i]
            if (peak == 0) peak = bins.max()
            if (peak <= 0) return FloatArray(bins.size)
            return FloatArray(bins.size) { i -> sqrt(bins[i].toFloat() / peak).coerceIn(0f, 1f) }
        }
    }
}
