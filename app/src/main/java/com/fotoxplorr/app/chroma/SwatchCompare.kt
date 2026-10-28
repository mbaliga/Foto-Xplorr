package com.fotoxplorr.app.chroma

import kotlin.math.abs

/** How far apart two swatches look, in five plain steps plus one for a reading too rough to say. */
internal enum class SwatchLevel(val message: String) {
    SAME(ChromaCopy.SAME),
    CLOSE(ChromaCopy.CLOSE),
    NOTICEABLE(ChromaCopy.NOTICEABLE),
    DIFFERENT(ChromaCopy.DIFFERENT),
    VERY_DIFFERENT(ChromaCopy.VERY_DIFFERENT),

    /** The difference is inside what the readings can tell (see [SwatchCompare.CALIBRATED_FLOOR] and [SwatchCompare.APPROXIMATE_FLOOR]). */
    TOO_CLOSE_TO_CALL(ChromaCopy.TOO_CLOSE_TO_CALL),
}

/**
 * @property deltaE CIEDE2000 between the two.
 * @property level the plain verdict.
 * @property detail the main way the second differs from the first, or null when they look the same.
 */
internal class SwatchVerdict(val deltaE: Double, val level: SwatchLevel, val detail: String?)

/**
 * "Compare two swatches": CIEDE2000 and a plain verdict. The cut-offs follow the usual reading of
 * CIEDE2000 (under 1 not seen, 1 to 2 seen by a careful eye side by side, 2 to 10 seen at a glance,
 * 10 to 25 clearly different, beyond that a different colour); the 25 is ours. Those readings of
 * CIEDE2000 are for two swatches seen, not for two surfaces measured by a phone: a card-corrected
 * reading is good to about 3 to 6 at best ([CALIBRATED_FLOOR]) and one made without a card to maybe 5 to
 * 10 ([APPROXIMATE_FLOOR]) and without any lightness, so a difference below the floor is "too close to
 * call" rather than a verdict that implies precision. That leaves [SwatchLevel.SAME] and
 * [SwatchLevel.CLOSE] out of reach of the app (they are still the table below a floor of 0).
 */
internal object SwatchCompare {
    const val SAME_BELOW = 1.0
    const val CLOSE_BELOW = 2.0
    const val NOTICEABLE_BELOW = 10.0
    const val DIFFERENT_BELOW = 25.0

    /** The difference below which two card-corrected readings cannot be told apart (the stated error at best is 3 to 6). */
    const val CALIBRATED_FLOOR = 3.0

    /** The difference below which a pair with an approximate reading in it cannot be told apart. */
    const val APPROXIMATE_FLOOR = 8.0

    /**
     * @param eitherApproximate a reading made without a card (or without a flash) is in the pair.
     * @param ignoreLightness take [b] at the lightness of [a], so the difference is in shade and strength only
     *   (for readings whose lightness was not measured); the verdict then never says lighter or darker.
     * @param floor the difference below which the verdict is [SwatchLevel.TOO_CLOSE_TO_CALL]; by default
     *   [APPROXIMATE_FLOOR] when [eitherApproximate], else [CALIBRATED_FLOOR]. 0 gives the bare table.
     */
    fun compare(
        a: Lab,
        b: Lab,
        eitherApproximate: Boolean = false,
        ignoreLightness: Boolean = false,
        floor: Double = if (eitherApproximate) APPROXIMATE_FLOOR else CALIBRATED_FLOOR,
    ): SwatchVerdict {
        val parts = DeltaE2000.parts(a, if (ignoreLightness) b.copy(l = a.l) else b)
        val de = parts.total
        val level = when {
            de < floor -> SwatchLevel.TOO_CLOSE_TO_CALL
            de < SAME_BELOW -> SwatchLevel.SAME
            de < CLOSE_BELOW -> SwatchLevel.CLOSE
            de < NOTICEABLE_BELOW -> SwatchLevel.NOTICEABLE
            de < DIFFERENT_BELOW -> SwatchLevel.DIFFERENT
            else -> SwatchLevel.VERY_DIFFERENT
        }
        val detail = if (level == SwatchLevel.SAME || level == SwatchLevel.TOO_CLOSE_TO_CALL) null else largestTerm(parts)
        return SwatchVerdict(de, level, detail)
    }

    private fun largestTerm(p: DeltaE2000.Parts): String {
        val l = abs(p.lightness)
        val c = abs(p.chroma)
        val h = abs(p.hue)
        return when {
            l >= c && l >= h -> if (p.lightness > 0) ChromaCopy.B_LIGHTER else ChromaCopy.B_DARKER
            c >= h -> if (p.chroma > 0) ChromaCopy.B_MORE_VIVID else ChromaCopy.B_DULLER
            else -> ChromaCopy.HUE_DIFFERS
        }
    }
}

/**
 * The focus distance a camera reports is in DIOPTERS (1 / metres; 0 means infinity). This turns it
 * into metres for the solver's distance correction, or null when it is not a usable close distance
 * (zero, negative, not a number, or outside 5 cm to 2 m, where the flash falloff correction would be
 * extrapolating). How accurate a phone's reported focus distance is varies by model and is not
 * checked here.
 */
internal object FocusDistance {
    const val NEAREST_METRES = 0.05
    const val FARTHEST_METRES = 2.0

    fun metresFromDiopters(diopters: Float?): Double? {
        if (diopters == null || diopters.isNaN() || diopters <= 0f) return null
        val m = 1.0 / diopters
        return if (m in NEAREST_METRES..FARTHEST_METRES) m else null
    }
}
