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
        /**
         * The correction, in degrees, for [Id.STRAIGHTEN] only -- otherwise null.
         *
         * Straighten lives on [EditRecipe][com.fotoxplorr.app.editor.EditRecipe], not inside
         * [Adjustments], so it cannot ride in [adjustments] the way the other three suggestions'
         * whole state does. A second, optional field is less disruptive than widening every
         * existing [Suggestion] into a general "recipe patch" type would have been: the three
         * suggestions that already shipped, and their tests, keep constructing and reading this
         * type exactly as before.
         */
        val straightenDegrees: Float? = null,
    ) {
        enum class Id { TONE, WHITE_BALANCE, PUNCH, STRAIGHTEN }
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
     *
     * @param horizonDegrees the output of [detectHorizon] on the same sampled pixels, or null to
     *   skip the straighten offer entirely. A separate parameter rather than folded into
     *   [Analysis] because it needs the pixels' 2D layout (width and height) to compute gradients,
     *   while everything [Analysis] holds is a histogram that does not care what order the pixels
     *   came in -- merging the two would make [analyse] take parameters only straighten needs.
     *   Defaulted to null so the existing three suggestions, and every test written against the
     *   two-argument overload, are unaffected by this feature's addition.
     */
    fun suggestionsFor(
        analysis: Analysis,
        current: Adjustments = Adjustments.NONE,
        horizonDegrees: Float? = null,
    ): List<Suggestion> {
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

        // ---- straighten: is there a dominant tilted edge worth levelling? ----
        // horizonDegrees is already the CORRECTION (see detectHorizon), so it is used as-is, not
        // negated again here -- negating it twice is the exact sign bug this feature is warned
        // about, and it would be invisible in this function since nothing here renders a preview.
        if (horizonDegrees != null && abs(horizonDegrees) >= MIN_OFFERED_STRAIGHTEN) {
            out += Suggestion(
                id = Suggestion.Id.STRAIGHTEN,
                label = "Straighten",
                reason = "The horizon looks tilted",
                adjustments = current,
                straightenDegrees = horizonDegrees,
            )
        }

        return out
    }

    /**
     * The dominant near-horizontal or near-vertical edge in [pixels], as a straighten correction
     * in degrees -- or null when the photograph does not have one strong enough to act on.
     *
     * The method: a cheap central-difference gradient at every interior pixel, discarding weak
     * ones (texture and sensor noise, not a real line); for what remains, the direction of
     * steepest brightness change is perpendicular to whatever edge produced it, and *how far that
     * direction sits from the nearest multiple of 90 degrees* is a quantity worth histogramming,
     * because folding by 90 degrees is invariant to the 90-degree edge/gradient perpendicularity
     * -- a near-horizontal horizon (edge angle near 0) and a near-vertical door frame (edge angle
     * near 90) both fold to a small number near zero, so one histogram answers "is anything in
     * this photo close to level" for both orientations at once, with no separate horizontal and
     * vertical passes to keep in sync.
     *
     * A single dominant bin is required, weighted by gradient strength, before this offers
     * anything: a photograph with edges scattered across every angle -- gravel, foliage, a crowd
     * -- must not be told it has a horizon, which is the negative case that matters as much as the
     * positive one (see [AutoFixTest] for both).
     *
     * @return degrees to feed [EditRecipe.straightenDegrees][com.fotoxplorr.app.editor.EditRecipe]
     *   directly: the SIGN is already the correction, not the tilt. If the dominant edge is tilted
     *   by +5 degrees, the photo needs to be rotated by -5 to level it, and that negation happens
     *   once, here -- not at every call site, where it would be one call site away from being
     *   forgotten or applied twice.
     */
    fun detectHorizon(pixels: IntArray, width: Int, height: Int): Float? {
        if (width < 3 || height < 3 || pixels.size != width * height) return null

        // Luma once, up front: the gradient at each interior pixel reads four neighbours, and
        // recomputing luma from packed ARGB on every read would be four times the work for a
        // value that never changes. Rec.709 weights again, to agree with analyse() about what
        // "brightness" means -- two different definitions of luma in the same file would mean the
        // tone fix and the straighten offer occasionally disagreeing about the same pixel.
        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        // Blur before measuring, at a radius that scales with the frame. This is not a quality
        // nicety, it is what makes the measurement possible at all.
        //
        // A crisp edge tilted a few degrees is a STAIRCASE of pixels: long horizontal runs with an
        // occasional one-pixel step. Sample the gradient at a single pixel and almost every reading
        // sits on a run and reports "perfectly horizontal"; the tilt only exists at the steps. The
        // histogram then peaks hard at 0 degrees no matter how tilted the picture is, the dominance
        // check sees one narrow spike, and the function returns null for exactly the clean,
        // strongly-edged photographs it was written to handle. Measured, not theorised: before this
        // blur, a synthetic 6-degree horizon returned null at every size tried.
        //
        // The radius has to be comparable to the spacing between steps, which is set by the tilt
        // and the frame width, so it scales with the frame rather than being a fixed 1 or 2.
        val blurRadius = (minOf(width, height) / HORIZON_BLUR_DIVISOR).coerceIn(2, 12)
        val blurred = boxBlur(luma, width, height, blurRadius)

        // A histogram of "degrees from the nearest axis", weighted by edge strength, covering
        // (-45, 45] at HORIZON_BINS_PER_DEGREE resolution.
        val histogram = FloatArray(HORIZON_HISTOGRAM_BINS)
        var totalWeight = 0.0
        var strongPixels = 0

        // The bar for "this pixel is on an edge" is a FRACTION of the strongest gradient in this
        // frame, not an absolute number. Two reasons, and the first is a bug this replaced: the
        // blur above divides gradient magnitude by roughly its own diameter, so a fixed threshold
        // that passed at one blur radius silently rejected every pixel at a larger one -- a
        // 6-degree horizon was detected in a 64px frame and missed in a 96px frame, purely because
        // the radius scales with the frame. The second is that an absolute bar also fails honest
        // photographs: a misty landscape has real edges at a fraction of a sunlit scene's contrast.
        var maxMagnitude = 0f
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val gx = blurred[row + x + 1] - blurred[row + x - 1]
                val gy = blurred[row + width + x] - blurred[row - width + x]
                val magnitude = kotlin.math.sqrt(gx * gx + gy * gy)
                if (magnitude > maxMagnitude) maxMagnitude = magnitude
            }
        }
        // A frame with no gradient at all -- a solid colour. Nothing to level.
        if (maxMagnitude < HORIZON_MIN_PEAK_GRADIENT) return null
        val threshold = maxMagnitude * HORIZON_GRADIENT_FRACTION

        for (y in 1 until height - 1) {
            val row = y * width
            val rowAbove = row - width
            val rowBelow = row + width
            for (x in 1 until width - 1) {
                val gx = blurred[row + x + 1] - blurred[row + x - 1]
                val gy = blurred[rowBelow + x] - blurred[rowAbove + x]
                // sqrt(gx^2 + gy^2) rather than kotlin.math.hypot: hypot's extra work is to avoid
                // overflow on values far outside luma's 0..255 range, which cannot happen here.
                val magnitude = kotlin.math.sqrt(gx * gx + gy * gy)
                if (magnitude < threshold) continue

                var degrees = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gx.toDouble())).toFloat()
                // Fold into (-45, 45]. The `+90` before each `%90` is what makes this correct for
                // NEGATIVE angles too: Kotlin's `%` keeps the sign of its left operand, so a
                // negative `degrees % 90` alone would land in (-90, 0] instead of the intended
                // [0, 90) and every leaning-left edge would be histogrammed at the wrong offset.
                degrees = ((degrees % 90f) + 90f) % 90f
                if (degrees > 45f) degrees -= 90f

                val bin = ((degrees + 45f) * HORIZON_BINS_PER_DEGREE).toInt()
                    .coerceIn(0, HORIZON_HISTOGRAM_BINS - 1)
                histogram[bin] += magnitude
                totalWeight += magnitude
                strongPixels++
            }
        }

        // Too little edge content to have an opinion -- a flat wall, an overcast sky, a macro
        // shot. Offering a correction here would be a coin flip wearing a confident label.
        if (strongPixels < MIN_HORIZON_STRONG_PIXELS || totalWeight <= 0.0) return null

        val peakBin = histogram.indices.maxByOrNull { histogram[it] } ?: return null

        // The dominant angle as a weighted mean over a small window around the peak bin, not just
        // the single tallest bin: a real edge spreads over a couple of degrees once anything is
        // downsampled or anti-aliased, and reading only the sharpest bin answers with quantisation
        // noise instead of the edge's actual angle.
        var windowWeight = 0.0
        var windowSum = 0.0
        for (bin in (peakBin - HORIZON_PEAK_WINDOW_BINS)..(peakBin + HORIZON_PEAK_WINDOW_BINS)) {
            if (bin !in histogram.indices) continue
            val angle = bin / HORIZON_BINS_PER_DEGREE - 45f
            windowWeight += histogram[bin]
            windowSum += histogram[bin] * angle
        }
        if (windowWeight <= 0.0) return null

        // Dominance: what fraction of the picture's total edge energy agrees with this one
        // direction. This is the check that separates "one strong tilted line" from "edges
        // everywhere, none of them in charge" -- the two images can have an identical peak bin
        // height and still deserve opposite answers.
        val dominance = windowWeight / totalWeight
        if (dominance < MIN_HORIZON_DOMINANCE) return null

        val tiltDegrees = (windowSum / windowWeight).toFloat()
        if (abs(tiltDegrees) < MIN_DETECTABLE_TILT) return null

        // The dominant edge IS tilted by tiltDegrees; levelling it means rotating the photo by
        // the opposite amount. This is the one negation in the whole function, and it happens
        // once, here -- see the KDoc's @return note for why that placement matters.
        return (-tiltDegrees).coerceIn(-MAX_AUTO_STRAIGHTEN, MAX_AUTO_STRAIGHTEN)
    }

    /**
     * Separable box blur over a luma plane. Two one-dimensional passes rather than one square
     * window: a radius-8 square is 289 reads per pixel where two passes are 34, and on a
     * 128x128 analysis frame that difference is the whole cost of the feature.
     */
    private fun boxBlur(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return source
        val horizontal = FloatArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (dx in -radius..radius) {
                    val sx = x + dx
                    if (sx < 0 || sx >= width) continue
                    sum += source[row + sx]
                    count++
                }
                horizontal[row + x] = sum / count
            }
        }
        val out = FloatArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    val sy = y + dy
                    if (sy < 0 || sy >= height) continue
                    sum += horizontal[sy * width + x]
                    count++
                }
                out[y * width + x] = sum / count
            }
        }
        return out
    }

    /** Frame fraction used as the pre-measurement blur radius. See the note in [detectHorizon]. */
    private const val HORIZON_BLUR_DIVISOR = 12

    /** Share of the frame's strongest gradient a pixel must reach to count as "on an edge". */
    private const val HORIZON_GRADIENT_FRACTION = 0.35f

    /** Below this peak gradient the frame has no edges worth measuring at all. */
    private const val HORIZON_MIN_PEAK_GRADIENT = 1.5f

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

    /** Below this many degrees, a straighten offer is not worth surfacing to the user at all. */
    private const val MIN_OFFERED_STRAIGHTEN = 0.75f

    // ---- detectHorizon tuning ----

    /** Histogram resolution: half-degree bins across the (-45, 45] fold range. */
    private const val HORIZON_BINS_PER_DEGREE = 2f
    private const val HORIZON_HISTOGRAM_BINS = 180

    /**
     * Minimum central-difference gradient magnitude to count as "an edge" rather than sensor
     * noise or JPEG texture. Luma is 0..255, so this is a small fraction of the full range.
     */
    private const val HORIZON_GRADIENT_THRESHOLD = 24f

    /** Below this many qualifying pixels, the image is treated as having no opinion to offer. */
    private const val MIN_HORIZON_STRONG_PIXELS = 40

    /** Half-width, in bins, of the window averaged around the histogram's peak. */
    private const val HORIZON_PEAK_WINDOW_BINS = 6

    /** Fraction of total edge energy the peak window must hold before a correction is offered. */
    private const val MIN_HORIZON_DOMINANCE = 0.25

    /** Below this many degrees of measured tilt, levelling it would not be visible anyway. */
    private const val MIN_DETECTABLE_TILT = 0.5f

    /** Deliberately inside the straighten slider's -15..15 range: auto-fix should improve a
     *  photo, not gamble on a large automatic rotation it cannot ask the user to confirm first. */
    private const val MAX_AUTO_STRAIGHTEN = 10f
}
