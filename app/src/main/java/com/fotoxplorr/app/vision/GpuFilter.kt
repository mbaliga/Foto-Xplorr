package com.fotoxplorr.app.vision

import kotlin.math.abs

/** How far a [GpuFilter] matrix is from the exact path, in 8-bit sRGB steps (the largest channel difference per colour). */
internal class FilterError(val maxSteps: Int, val meanSteps: Double)

/**
 * The simulate and daltonize matrices as a 4x5 colour matrix for a GPU colour filter (the layout of
 * `android.graphics.ColorMatrix`: four rows R, G, B, A of `[r g b a offset]`, row-major, 20 floats,
 * offsets in 0..255 units and here always 0). Pure: nothing here touches the platform.
 *
 * The approximation, stated plainly. The exact result is `encode(clamp(M * decode(rgb)))` in linear
 * light. A colour matrix filter has no transfer curve, so [simulate] and [daltonize] put the SAME
 * 3x3 `M` on the sRGB-encoded values directly. That is exact for a grey and for any colour whose
 * result stays inside the gamut and close to the grey axis, and wrong elsewhere: the encode curve is
 * steep near black and the matrices push saturated colours out of the gamut, where a linear-light
 * clamp and an encoded clamp disagree. A least-squares matrix refitted in the encoded domain was
 * tried (script, not shipped) and was no better in the worst case, so none is offered.
 *
 * Measured by [error] over a 17x17x17 grid of 8-bit sRGB (GpuFilterTest prints and pins these within
 * a margin), in 8-bit steps of the worst channel. Over every grid colour the full-severity simulate
 * matrices are off by up to 83 to 89 steps (mean 17 to 19); over muted colours (channels within 30
 * percent of each other, most of a photograph) by up to 27 to 33 steps (mean 2.5 to 3.5). The
 * daltonize matrix is far worse (up to 111 to 117 steps overall, 91 to 103 over muted colours, mean
 * about 10 over muted) because its gain pushes more colours past the gamut edge.
 *
 * So: [simulate] is acceptable as a LIVE PREVIEW of a photo; [daltonize] as a filter is only a rough
 * hint; anything the person saves or shares must use the exact per-pixel path ([Simulation.simulateAll],
 * [Daltonizer.daltonizeAll]).
 */
internal object GpuFilter {
    /** A 3x3 row-major matrix as the 20-float colour matrix: identity alpha, zero offsets. */
    fun from3x3(m: DoubleArray): FloatArray {
        require(m.size == 9) { "need a 3x3 matrix" }
        val f = FloatArray(20)
        for (r in 0 until 3) {
            for (c in 0 until 3) f[r * 5 + c] = m[r * 3 + c].toFloat()
        }
        f[18] = 1f
        return f
    }

    fun simulate(deficiency: Deficiency, severity: Double = 1.0): FloatArray =
        from3x3(Simulation.matrix(deficiency, severity))

    fun daltonize(deficiency: Deficiency, severity: Double = 1.0): FloatArray =
        from3x3(Daltonizer.matrix(deficiency, severity))

    /** Applies a 20-float colour matrix to one packed colour the way the platform does (0..255 channels, clamped). */
    fun apply(matrix: FloatArray, argb: Int): Int {
        require(matrix.size == 20) { "need a 4x5 matrix" }
        val r = ColourSpace.red(argb).toDouble()
        val g = ColourSpace.green(argb).toDouble()
        val b = ColourSpace.blue(argb).toDouble()
        fun row(i: Int): Int {
            val v = matrix[i * 5] * r + matrix[i * 5 + 1] * g + matrix[i * 5 + 2] * b + matrix[i * 5 + 4]
            return (v + 0.5).toInt().coerceIn(0, 255)
        }
        return ColourSpace.pack(ColourSpace.alpha(argb), row(0), row(1), row(2))
    }

    /**
     * The error of [matrix] against [exact] over a 17x17x17 grid of 8-bit sRGB colours (steps of 16, the last being 255).
     * With [mutedOnly] the grid is cut to colours whose channels are within 76 (30 percent) of each other.
     */
    fun error(matrix: FloatArray, exact: (Int) -> Int, mutedOnly: Boolean = false): FilterError {
        var worst = 0
        var sum = 0.0
        var count = 0
        for (r in GRID) for (g in GRID) for (b in GRID) {
            if (mutedOnly && maxOf(r, g, b) - minOf(r, g, b) > MUTED_SPREAD) continue
            val c = ColourSpace.pack(255, r, g, b)
            val want = exact(c)
            val got = apply(matrix, c)
            val d = maxOf(
                abs(ColourSpace.red(want) - ColourSpace.red(got)),
                abs(ColourSpace.green(want) - ColourSpace.green(got)),
                abs(ColourSpace.blue(want) - ColourSpace.blue(got)),
            )
            if (d > worst) worst = d
            sum += d
            count++
        }
        return FilterError(worst, if (count == 0) 0.0 else sum / count)
    }

    private const val MUTED_SPREAD = 76
    private val GRID = IntArray(17) { minOf(it * 16, 255) }
}
