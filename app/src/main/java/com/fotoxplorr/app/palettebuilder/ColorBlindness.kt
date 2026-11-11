package com.fotoxplorr.app.palettebuilder

/** The three dichromacies the check simulates; each is the full (severity 1.0) loss of one cone type. */
enum class VisionType(val label: String) {
    PROTAN(PaletteBuilderCopy.VISION_PROTAN),
    DEUTAN(PaletteBuilderCopy.VISION_DEUTAN),
    TRITAN(PaletteBuilderCopy.VISION_TRITAN),
}

/** Two swatches that look too alike to [type] eyes: their indices, and their distance seen and unseen. */
data class VisionCollision(
    val first: Int,
    val second: Int,
    val type: VisionType,
    /** Lab distance between the two after the simulation. */
    val simulatedDeltaE: Double,
    /** Lab distance between the two as a typical eye sees them; small here means they were close anyway. */
    val normalDeltaE: Double,
)

/**
 * "Do these swatches stay apart for someone with colour blindness?", on a small SELF-CONTAINED simulation
 * (about 40 lines): the Machado, Oliveira and Fernandes (IEEE TVCG 2009) 3x3 matrices at severity 1.0,
 * applied to linear sRGB.
 *
 * It does not use the `vision` package, which is written in parallel; the nine numbers per type were
 * compared by eye with `vision/MachadoMatrices.kt`'s severity-1.0 rows (they agree) and each row sums to 1,
 * so greys stay grey, which the tests check. Nothing has been compared with the authors' own page.
 * Only the three dichromacies at full severity are covered, so a person with a milder condition sees
 * more than this shows. A pair is flagged when its CIE76 distance after the simulation falls under
 * [DEFAULT_MIN_DELTA_E]: a rule of thumb for "easy to tell apart side by side", not a clinical test, and
 * nothing on a real screen or with a real person has been checked.
 */
object ColorBlindness {

    /** Smaller than this between two swatches, after the simulation, counts as "blurs together". */
    const val DEFAULT_MIN_DELTA_E = 10.0

    // Row-major, linear RGB, severity 1.0.
    private val MATRICES = mapOf(
        VisionType.PROTAN to doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
        VisionType.DEUTAN to doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
        VisionType.TRITAN to doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900),
    )

    /** The opaque colour [argb] looks like to someone of [type]. */
    fun simulate(argb: Int, type: VisionType): Int {
        val m = MATRICES.getValue(type)
        val r = ColorMath.channelToLinear(ColorMath.red(argb))
        val g = ColorMath.channelToLinear(ColorMath.green(argb))
        val b = ColorMath.channelToLinear(ColorMath.blue(argb))
        return ColorMath.rgb(
            ColorMath.linearToChannel(m[0] * r + m[1] * g + m[2] * b),
            ColorMath.linearToChannel(m[3] * r + m[4] * g + m[5] * b),
            ColorMath.linearToChannel(m[6] * r + m[7] * g + m[8] * b),
        )
    }

    /** Every pair of [colors] that falls under [minDeltaE] for some type, in (type, first, second) order. */
    fun collisions(colors: List<Int>, minDeltaE: Double = DEFAULT_MIN_DELTA_E): List<VisionCollision> {
        val normal = colors.map { ColorMath.lab(it) }
        return VisionType.entries.flatMap { type ->
            val seen = colors.map { ColorMath.lab(simulate(it, type)) }
            (0 until colors.size).flatMap { i ->
                (i + 1 until colors.size).mapNotNull { j ->
                    val d = ColorMath.deltaE(seen[i], seen[j])
                    if (d < minDeltaE) VisionCollision(i, j, type, d, ColorMath.deltaE(normal[i], normal[j])) else null
                }
            }
        }
    }

    fun isDistinguishable(colors: List<Int>, minDeltaE: Double = DEFAULT_MIN_DELTA_E): Boolean =
        collisions(colors, minDeltaE).isEmpty()

    fun collisions(palette: Palette, minDeltaE: Double = DEFAULT_MIN_DELTA_E): List<VisionCollision> =
        collisions(palette.argbs, minDeltaE)
}
