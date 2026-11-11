package com.fotoxplorr.app.viewer.colour

import kotlin.math.abs
import kotlin.math.max

/**
 * Pixel measurements behind the viewer's pro overlay, pure over packed ARGB so they run on the JVM.
 * The caller hands a SMALL upright copy of the photo (a few hundred pixels a side): these are
 * overlays to judge exposure and focus by eye, not measurements of the file's full detail.
 */
object PixelAnalysis {

    const val SHADOW_MAX = 3
    const val HIGHLIGHT_MIN = 252

    /** Overlay colours (ARGB): highlights red, shadows blue, focus peaks yellow. */
    const val HIGHLIGHT_COLOUR = 0xCCFF3B30.toInt()
    const val SHADOW_COLOUR = 0xCC2F6BFF.toInt()
    const val PEAK_COLOUR = 0xE6FFD60A.toInt()

    /** Rec. 709 luma, 0..255, the weighting the editor's histogram uses. */
    fun luma(argb: Int): Int {
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        return ((2126 * r + 7152 * g + 722 * b) / 10_000)
    }

    /** Fractions of the (opaque) pixels that are crushed to black or blown to white. */
    data class Clipping(val shadows: Float, val highlights: Float) {
        val any: Boolean get() = shadows > 0f || highlights > 0f
    }

    /** A pixel is a blown highlight when ANY channel is at or above [HIGHLIGHT_MIN] (a clipped channel loses colour), a crushed shadow when its luma is at or below [SHADOW_MAX]. */
    fun isHighlight(argb: Int): Boolean {
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        return max(r, max(g, b)) >= HIGHLIGHT_MIN
    }

    fun isShadow(argb: Int): Boolean = luma(argb) <= SHADOW_MAX

    fun clipping(pixels: IntArray): Clipping {
        var shadows = 0
        var highlights = 0
        var n = 0
        for (p in pixels) {
            if (p ushr 24 == 0) continue
            n++
            if (isHighlight(p)) highlights++ else if (isShadow(p)) shadows++
        }
        if (n == 0) return Clipping(0f, 0f)
        return Clipping(shadows.toFloat() / n, highlights.toFloat() / n)
    }

    /** The clipping overlay: transparent except blown highlights (red) and crushed shadows (blue). */
    fun clippingMask(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val p = pixels[i]
        when {
            p ushr 24 == 0 -> 0
            isHighlight(p) -> HIGHLIGHT_COLOUR
            isShadow(p) -> SHADOW_COLOUR
            else -> 0
        }
    }

    /**
     * Sobel gradient magnitude of the luma, per pixel (borders 0), scaled to roughly 0..1020 (the
     * largest a 0..255 step can give). The input of focus peaking.
     */
    fun gradient(pixels: IntArray, width: Int, height: Int): IntArray {
        require(pixels.size >= width * height) { "pixels smaller than ${width}x$height" }
        val out = IntArray(width * height)
        if (width < 3 || height < 3) return out
        val l = IntArray(width * height) { luma(pixels[it]) }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val gx = -l[i - width - 1] + l[i - width + 1] - 2 * l[i - 1] + 2 * l[i + 1] - l[i + width - 1] + l[i + width + 1]
                val gy = -l[i - width - 1] - 2 * l[i - width] - l[i - width + 1] + l[i + width - 1] + 2 * l[i + width] + l[i + width + 1]
                out[i] = (abs(gx) + abs(gy)) / 4
            }
        }
        return out
    }

    /**
     * Focus peaking: the pixels whose edge strength is in the top [topFraction] of the photo AND at
     * least [MIN_EDGE] in absolute terms, so a flat or smoothly graded photo shows no peaks rather
     * than the least-flat 8% of noise. Returned as an overlay (transparent / [PEAK_COLOUR]).
     */
    fun peakingMask(pixels: IntArray, width: Int, height: Int, topFraction: Float = 0.08f): IntArray {
        val g = gradient(pixels, width, height)
        val out = IntArray(g.size)
        if (g.isEmpty()) return out
        val sorted = g.copyOf().also { it.sort() }
        val cut = sorted[((sorted.size - 1) * (1f - topFraction.coerceIn(0.001f, 0.5f))).toInt()]
        val threshold = max(cut, MIN_EDGE)
        for (i in g.indices) if (g[i] >= threshold) out[i] = PEAK_COLOUR
        return out
    }

    /** An edge weaker than this (out of ~255) is texture, not a focus edge. */
    const val MIN_EDGE = 24
}
