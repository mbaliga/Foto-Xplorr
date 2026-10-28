package com.fotoxplorr.app.vision

/**
 * Daltonization: changes a picture so that colours a person with a colour-vision deficiency would
 * confuse become distinguishable to them.
 *
 * Method (the standard "daltonize" idea of Fidaner, Lin and Ozguven, 2005, redone with an explicit
 * opponent basis): simulate the deficiency ([Simulation]), take the error `e = original - simulated`
 * in linear RGB, which is the information the eye loses, and move the part of it that lies on the lost
 * axis into the axes the person still has, then add that to the original and clamp.
 *
 * - Protan and deutan lose the red-green axis. Its error is moved into the blue-yellow axis
 *   (a reddish colour is pushed toward yellow, a greenish one toward blue) and into lightness
 *   (reddish lighter, greenish darker).
 * - Tritan loses the blue-yellow axis. Its error is moved into the red-green axis and lightness.
 *
 * The three axes are lightness (the luminance row of sRGB), red-green `(R - G)` and blue-yellow
 * `((R + G) / 2 - B)`, all in linear RGB, so the whole operation is ONE 3x3 matrix
 * `M = I + A (I - S)` that is applied before the clamp. A grey has zero error, so it is unchanged.
 * The gains [LIGHT_GAIN] and [AXIS_GAIN] are 0.7, the figure the usual daltonize recipes use; they
 * were checked against random pairs of colours a simulated eye confuses (most pairs end up much
 * further apart) and not tuned beyond that.
 *
 * Be clear about what this does. It makes confusable colours distinguishable to the person; it does
 * NOT show a colourblind person what a normal-sighted person sees, which is impossible, and it
 * changes the picture's colours for everyone else, so it is a viewing aid, never a replacement for
 * the original. Colours near the edge of the gamut have nowhere to go and are clamped, so a few
 * pairs get no better. In DaltonizerTest's seeded trial of random colour pairs a simulated eye
 * confuses (simulated distance under 12, true distance over 25), 394 of 395 protan pairs, 539 of
 * 546 deutan pairs and 255 of 317 tritan pairs ended up further apart as the simulated eye sees them.
 */
internal object Daltonizer {
    const val LIGHT_GAIN = 0.7
    const val AXIS_GAIN = 0.7

    // Rows: lightness, red-green, blue-yellow. Linear RGB in, opponent value out.
    private val TO_OPPONENT = doubleArrayOf(
        0.2126729, 0.7151522, 0.0721750,
        1.0, -1.0, 0.0,
        0.5, 0.5, -1.0,
    )
    private val FROM_OPPONENT = Mat3.invert(TO_OPPONENT)

    /** Which opponent axis the deficiency loses, and where the error goes: rows = lightness, red-green, blue-yellow. */
    private fun redistribution(deficiency: Deficiency): DoubleArray = when (deficiency) {
        Deficiency.PROTAN, Deficiency.DEUTAN -> doubleArrayOf(
            0.0, LIGHT_GAIN, 0.0,
            0.0, 0.0, 0.0,
            0.0, AXIS_GAIN, 0.0,
        )
        Deficiency.TRITAN -> doubleArrayOf(
            0.0, 0.0, LIGHT_GAIN,
            0.0, 0.0, AXIS_GAIN,
            0.0, 0.0, 0.0,
        )
    }

    /** The row-major 3x3 linear-RGB matrix `I + A (I - S)`; applied to a colour, then clamped. */
    fun matrix(deficiency: Deficiency, severity: Double): DoubleArray {
        val lost = Mat3.sub(Mat3.IDENTITY, MachadoMatrices.matrix(deficiency, severity))
        val a = Mat3.mul(FROM_OPPONENT, Mat3.mul(redistribution(deficiency), TO_OPPONENT))
        return Mat3.add(Mat3.IDENTITY, Mat3.mul(a, lost))
    }

    /** One colour; every channel of the result is clamped to 0..1 by the sRGB encode. Alpha is kept. */
    fun daltonize(argb: Int, deficiency: Deficiency, severity: Double = 1.0): Int =
        ColourSpace.applyLinear(matrix(deficiency, severity), argb)

    /** Every pixel of [src] into [dst] (the same array is allowed); the matrix is built once. */
    fun daltonizeAll(
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
