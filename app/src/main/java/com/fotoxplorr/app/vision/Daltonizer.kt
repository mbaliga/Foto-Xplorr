package com.fotoxplorr.app.vision

/**
 * Daltonization: changes a picture so that colours a person with a colour-vision deficiency would
 * confuse become distinguishable to them.
 *
 * Method (the standard "daltonize" idea of Fidaner, Lin and Ozguven, 2005, redone with an explicit
 * opponent basis): simulate the deficiency ([Simulation]), take the error `e = original - simulated`
 * in linear RGB, which is the information the eye loses, and move the part of it that lies on the lost
 * axis into the axes the person still has, then add that to the original.
 *
 * - Protan and deutan lose the red-green axis. Its error is moved into the blue-yellow axis
 *   (a reddish colour is pushed toward yellow, a greenish one toward blue) and into lightness
 *   (reddish lighter, greenish darker).
 * - Tritan loses the blue-yellow axis. Its error is moved into the red-green axis and lightness.
 *
 * The three axes are lightness (the luminance row of sRGB), red-green `(R - G)` and blue-yellow
 * `((R + G) / 2 - B)`, all in linear RGB, so the whole operation is ONE 3x3 matrix
 * `M = I + A (I - S)`. A grey has zero error, so it is unchanged. The gains [LIGHT_GAIN] and
 * [AXIS_GAIN] are 0.7, the figure the usual daltonize recipes use; they were checked against random
 * pairs of colours a simulated eye confuses (most pairs end up much further apart) and not tuned
 * beyond that.
 *
 * ## Staying in the gamut
 * At full strength `M c` leaves 0..1 for a great many ordinary colours (about a quarter of muted ones
 * and half of leaf-like greens in a seeded trial), and clamping those flattens a whole channel: a
 * green field ends with no red in it at all, and the texture in that channel is gone from the saved
 * picture. So the correction `M c - c` is scaled, for each colour, by one factor `t` (0..1) chosen so
 * that no channel moves more than [HEADROOM] of the way to the nearest edge of the range: the result
 * keeps the direction of the correction, never reaches 0 or 1 and is never clamped. The factor is the
 * same for all three channels, and it is unchanged when the colour is scaled in brightness, so shading
 * and texture survive. The price is that a colour near the edge of the gamut is corrected less, and
 * a saturated one may get little help; that is deliberate.
 *
 * Be clear about what this does. It makes confusable colours distinguishable to the person; it does
 * NOT show a colourblind person what a normal-sighted person sees, which is impossible, and it
 * changes the picture's colours for everyone else, so it is a viewing aid, never a replacement for
 * the original. In DaltonizerTest's seeded trial of random colour pairs a simulated eye confuses
 * (simulated distance under 12, true distance over 25), 339 of 395 protan pairs, 477 of 546 deutan
 * pairs and 234 of 317 tritan pairs ended up further apart as the simulated eye sees them (the
 * test holds floors of 80%, 80% and 60%). Keeping the result in the gamut costs some of that: a
 * plain clamped matrix scored 394, 539 and 255 of the same pairs, by flattening whatever it clipped.
 * The measure uses the same Machado model that built the correction, so it says the maths does what
 * it was built for and NOT that a person finds the picture easier; no person has looked at these
 * pictures.
 */
internal object Daltonizer {
    const val LIGHT_GAIN = 0.7
    const val AXIS_GAIN = 0.7

    /** How much of the distance to the nearest edge of 0..1 a channel may move in one correction. */
    const val HEADROOM = 0.9

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

    /** The row-major 3x3 linear-RGB matrix `I + A (I - S)`; apply it with [fitInto], not as a plain product, to stay in the gamut. */
    fun matrix(deficiency: Deficiency, severity: Double): DoubleArray {
        val lost = Mat3.sub(Mat3.IDENTITY, MachadoMatrices.matrix(deficiency, severity))
        val a = Mat3.mul(FROM_OPPONENT, Mat3.mul(redistribution(deficiency), TO_OPPONENT))
        return Mat3.add(Mat3.IDENTITY, Mat3.mul(a, lost))
    }

    /**
     * The correction `m * rgb - rgb` of linear colour [rgb] (each 0..1), scaled by the one factor
     * that keeps every channel within [HEADROOM] of its way to the edge, added to [rgb]. Writes into
     * [out] (three values, linear, always inside 0..1 for an input inside 0..1) and returns it.
     */
    internal fun fitInto(m: DoubleArray, r: Double, g: Double, b: Double, out: DoubleArray): DoubleArray {
        val dr = m[0] * r + m[1] * g + m[2] * b - r
        val dg = m[3] * r + m[4] * g + m[5] * b - g
        val db = m[6] * r + m[7] * g + m[8] * b - b
        var t = 1.0
        t = limit(t, r, dr)
        t = limit(t, g, dg)
        t = limit(t, b, db)
        out[0] = r + t * dr
        out[1] = g + t * dg
        out[2] = b + t * db
        return out
    }

    /** [t] lowered, if need be, so that a channel at [c] moving by [d] stays within [HEADROOM] of the edge it moves toward. */
    private fun limit(t: Double, c: Double, d: Double): Double = when {
        d < 0.0 -> minOf(t, HEADROOM * c / -d)
        d > 0.0 -> minOf(t, HEADROOM * (1.0 - c) / d)
        else -> t
    }

    /** [fitInto] as a new array. */
    internal fun fitted(m: DoubleArray, rgb: DoubleArray): DoubleArray = fitInto(m, rgb[0], rgb[1], rgb[2], DoubleArray(3))

    private fun applyFitted(m: DoubleArray, argb: Int, scratch: DoubleArray): Int {
        fitInto(m, ColourSpace.decode8(ColourSpace.red(argb)), ColourSpace.decode8(ColourSpace.green(argb)), ColourSpace.decode8(ColourSpace.blue(argb)), scratch)
        return ColourSpace.pack(
            ColourSpace.alpha(argb),
            ColourSpace.encode8(scratch[0]),
            ColourSpace.encode8(scratch[1]),
            ColourSpace.encode8(scratch[2]),
        )
    }

    /** One colour, corrected within the gamut (see the class note). Alpha is kept. */
    fun daltonize(argb: Int, deficiency: Deficiency, severity: Double = 1.0): Int =
        applyFitted(matrix(deficiency, severity), argb, DoubleArray(3))

    /** The first [count] pixels of [pixels], corrected in place by the matrix [m] from [matrix]. */
    fun daltonizeInPlace(pixels: IntArray, count: Int, m: DoubleArray) {
        val scratch = DoubleArray(3)
        for (i in 0 until count) pixels[i] = applyFitted(m, pixels[i], scratch)
    }

    /** Every pixel of [src] into [dst] (the same array is allowed); the matrix is built once. */
    fun daltonizeAll(
        src: IntArray,
        deficiency: Deficiency,
        severity: Double = 1.0,
        dst: IntArray = IntArray(src.size),
    ): IntArray {
        require(dst.size >= src.size) { "dst is shorter than src" }
        val m = matrix(deficiency, severity)
        val scratch = DoubleArray(3)
        for (i in src.indices) dst[i] = applyFitted(m, src[i], scratch)
        return dst
    }
}
