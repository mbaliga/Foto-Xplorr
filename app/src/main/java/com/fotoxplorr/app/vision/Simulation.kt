package com.fotoxplorr.app.vision

/**
 * Simulates how a colour looks with a colour-vision deficiency, by the Machado, Oliveira and
 * Fernandes (2009) model (see [MachadoMatrices] for the citation and where the numbers come from).
 *
 * The matrix is applied in LINEAR RGB: each channel is sRGB-decoded, multiplied, clamped to 0..1
 * and sRGB-encoded again. Alpha passes through. Severity 0 is exactly the identity, 1 is full
 * dichromacy, and between is anomalous trichromacy ("weak" rather than "missing").
 *
 * What it is and is not. It is a model of the typical affected eye, fitted to published
 * experiments; it is not what any one person sees, and it assumes a standard sRGB screen at a
 * normal brightness. It is also not idempotent: a protan or deutan output run through the model
 * again moves a little (mean 3.8 and 1.8 Lab units over a 9x9x9 grid of sRGB, worst case 22 and
 * 13, pinned in SimulationTest), and a tritan one moves a lot (mean 10, worst 28), because the
 * paper's matrices are fitted, not exact projections.
 */
internal object Simulation {
    /** The row-major 3x3 linear-RGB matrix for this deficiency and severity (a fresh copy). */
    fun matrix(deficiency: Deficiency, severity: Double): DoubleArray = MachadoMatrices.matrix(deficiency, severity)

    fun simulate(argb: Int, deficiency: Deficiency, severity: Double = 1.0): Int =
        ColourSpace.applyLinear(matrix(deficiency, severity), argb)

    /** Simulates every pixel of [src] into [dst] (the same array is allowed); the matrix is built once. */
    fun simulateAll(
        src: IntArray,
        deficiency: Deficiency,
        severity: Double = 1.0,
        dst: IntArray = IntArray(src.size),
    ): IntArray {
        require(dst.size >= src.size) { "dst is shorter than src" }
        val m = matrix(deficiency, severity)
        for (i in src.indices) dst[i] = ColourSpace.applyLinear(m, src[i])
        return dst
    }
}
