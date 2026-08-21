package com.fotoxplorr.app.editor

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * "Fix this for me" — suggested adjustments, derived from the photograph rather than guessed.
 *
 * Each suggestion is a named, separately-applicable [Adjustments] delta with a reason attached, so
 * the editor can offer them as a row of chips and the user can take one without taking the others.
 * That is the difference between an auto-fix people trust and one they turn off: a single "Auto"
 * button that silently does four things is impossible to disagree with in part.
 *
 * Pure Kotlin over a pixel array — no Bitmap, no Android — so the arithmetic is unit-tested rather
 * than eyeballed on a device. The caller samples the bitmap down before handing it over; analysis
 * at full resolution would cost seconds and tell you nothing a thumbnail does not.
 */
object AutoFix {

    /** One offer. [adjustments] is the WHOLE adjustment state to apply, not a diff. */
    data class Suggestion(
        val id: Id,
        val label: String,
        /** Why this is being offered, in the user's terms. Shown under the chip. */
        val reason: String,
        val adjustments: Adjustments,
    ) {
        enum class Id { TONE, WHITE_BALANCE, PUNCH }
    }

    /** What the image looks like, statistically. Exposed because the tests assert on it. */
    data class Analysis(
        /** 0..1 luminance below which 0.5% of pixels fall — the effective black point. */
        val blackPoint: Float,
        /** 0..1 luminance above which 0.5% of pixels fall — the effective white point. */
        val whitePoint: Float,
        val medianLuminance: Float,
        val meanRed: Float,
        val meanGreen: Float,
        val meanBlue: Float,
    ) {
        /** How much of the available range the image actually uses. 1.0 is the full range. */
        val range: Float get() = whitePoint - blackPoint
    }

    /**
     * Measure [pixels] (packed ARGB, as `Bitmap.getPixels` returns).
     *
     * Percentiles rather than min/max: a single blown speck or one dead pixel would otherwise
     * declare the range already full and suppress every suggestion. 0.5% at each end is the
     * standard tolerance and survives dust, sensor noise and a bright spot of sky.
     */
    fun analyse(pixels: IntArray): Analysis {
        if (pixels.isEmpty()) return Analysis(0f, 1f, 0.5f, 0.5f, 0.5f, 0.5f)

        val histogram = IntArray(256)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sumR += r
            sumG += g
            sumB += b
            // Rec.709 luma: the eye is not equally sensitive to the three, and a flat average
            // makes blue skies read as darker than they look.
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            histogram[luma]++
        }

        val total = pixels.size
        val lowCut = (total * CLIP_FRACTION).toInt().coerceAtLeast(1)
        val highCut = (total * (1f - CLIP_FRACTION)).toInt().coerceAtLeast(1)
        val midCut = total / 2

        var seen = 0
        var black = 0
        var white = 255
        var median = 128
        var haveBlack = false
        var haveMedian = false
        for (level in 0..255) {
            val before = seen
            seen += histogram[level]
            if (!haveBlack && seen >= lowCut) {
                black = level
                haveBlack = true
            }
            if (!haveMedian && seen >= midCut) {
                median = level
                haveMedian = true
            }
            if (before < highCut && seen >= highCut) {
                white = level
            }
        }

        return Analysis(
            blackPoint = black / 255f,
            whitePoint = white / 255f,
            medianLuminance = median / 255f,
            meanRed = (sumR.toFloat() / total) / 255f,
            meanGreen = (sumG.toFloat() / total) / 255f,
            meanBlue = (sumB.toFloat() / total) / 255f,
        )
    }

    /**
     * Offers for this image, on top of whatever the user has already done ([current]).
     *
     * Returns an empty list when the photograph does not need anything. That matters: an auto-fix
     * that always finds something to change trains people to ignore it, and a well-exposed photo
     * genuinely needs nothing.
     */
    fun suggestionsFor(analysis: Analysis, current: Adjustments = Adjustments.NONE): List<Suggestion> {
        val out = mutableListOf<Suggestion>()

        // ---- tone: is the histogram using the range it has? ----
        val headroom = 1f - analysis.whitePoint
        val footroom = analysis.blackPoint
        if (headroom > FLAT_MARGIN || footroom > FLAT_MARGIN) {
            // Push the ends outward in proportion to the slack, so a slightly flat image gets a
            // slight correction rather than everything being slammed to full range.
            val whites = (headroom / MAX_STRETCH).coerceIn(0f, 1f) * TONE_STRENGTH
            val blacks = -(footroom / MAX_STRETCH).coerceIn(0f, 1f) * TONE_STRENGTH
            // Exposure only when the midpoint is genuinely off, and in stops: doubling the light
            // is one stop, which is what `log2` of the ratio gives.
            val exposure = if (analysis.medianLuminance > 0.02f) {
                val stops = ln(TARGET_MIDTONE / analysis.medianLuminance) / LN_2
                stops.coerceIn(-MAX_AUTO_STOPS, MAX_AUTO_STOPS)
            } else {
                0f
            }
            out += Suggestion(
                id = Suggestion.Id.TONE,
                label = "Fix exposure",
                reason = buildString {
                    if (headroom > FLAT_MARGIN && footroom > FLAT_MARGIN) {
                        append("Flat — the darks and the brights are both unused")
                    } else if (headroom > FLAT_MARGIN) {
                        append("Nothing reaches white")
                    } else {
                        append("Nothing reaches black")
                    }
                },
                adjustments = current.copy(
                    exposure = exposure,
                    whites = whites,
                    blacks = blacks,
                ),
            )
        }

        // ---- white balance: grey-world ----
        // The assumption is that an average scene averages to grey. It is wrong for a photograph
        // that is genuinely mostly one colour (a red wall, a forest), which is why this is an
        // OFFER with its reason stated, not something applied silently on import.
        val grey = (analysis.meanRed + analysis.meanGreen + analysis.meanBlue) / 3f
        if (grey > 0.02f) {
            val redRatio = analysis.meanRed / grey
            val blueRatio = analysis.meanBlue / grey
            val greenRatio = analysis.meanGreen / grey
            val warmth = redRatio - blueRatio
            val greenMagenta = greenRatio - (redRatio + blueRatio) / 2f

            if (abs(warmth) > CAST_MARGIN || abs(greenMagenta) > CAST_MARGIN) {
                out += Suggestion(
                    id = Suggestion.Id.WHITE_BALANCE,
                    label = "Fix colour",
                    reason = when {
                        warmth > CAST_MARGIN -> "Warm cast — looks orange"
                        warmth < -CAST_MARGIN -> "Cool cast — looks blue"
                        greenMagenta > CAST_MARGIN -> "Green cast"
                        else -> "Magenta cast"
                    },
                    adjustments = current.copy(
                        // Correcting means moving AGAINST the cast, hence the negation.
                        temperature = (-warmth * CAST_STRENGTH).coerceIn(-1f, 1f),
                        tint = (greenMagenta * CAST_STRENGTH).coerceIn(-1f, 1f),
                    ),
                )
            }
        }

        // ---- punch: a gentle, opinionated finish ----
        // Offered only when the image is not already contrasty, so it never doubles up on a photo
        // that has plenty.
        if (analysis.range > 0.5f && analysis.range < 0.92f) {
            out += Suggestion(
                id = Suggestion.Id.PUNCH,
                label = "Add punch",
                reason = "A little more contrast and colour",
                adjustments = current.copy(
                    contrast = PUNCH_CONTRAST,
                    vibrance = PUNCH_VIBRANCE,
                ),
            )
        }

        return out
    }

    /** Percentile trimmed from each end before calling something the black or white point. */
    private const val CLIP_FRACTION = 0.005f

    /** Below this much unused range at an end, the image is considered to reach that end. */
    private const val FLAT_MARGIN = 0.06f

    /** Slack at which the correction is at full strength; more than this is still full. */
    private const val MAX_STRETCH = 0.35f

    /** Deliberately below 1.0: auto-fix should improve a photo, not redevelop it. */
    private const val TONE_STRENGTH = 0.8f

    /** Mid-grey as a display value. Not 0.5 — perceptual middle sits a little below it. */
    private const val TARGET_MIDTONE = 0.46f
    private const val MAX_AUTO_STOPS = 1.2f
    private val LN_2 = ln(2f)

    /** Channel imbalance under which a cast is not worth mentioning. */
    private const val CAST_MARGIN = 0.06f
    private const val CAST_STRENGTH = 1.4f

    private const val PUNCH_CONTRAST = 0.18f
    private const val PUNCH_VIBRANCE = 0.22f
}
