package com.fotoxplorr.app.adaptive

import kotlin.math.ln

/**
 * The gallery's zoom as ONE ordered list of rungs, and the arithmetic a pinch needs to move along it.
 *
 * The owner's ask (#21) was one continuous grid, not a set of views: a grid of 1, 2, 3, 4, 5 or 6
 * columns, and past the sparsest of those the same grid turns into postage stamps grouped by month,
 * still one long scroll. So the rungs are just numbers. Rungs `1..6` are the column count and rung
 * [ZOOM_RUNG_STAMPS] is the stamp view, whose own column count depends on the window's width (see
 * [stampAcross]). Zooming out is always "the next rung up", zooming in "the next rung down", for
 * every rung alike -- the one invariant [pinchStep] and [stepRung] rely on.
 *
 * Pinching beyond Stamps does nothing further (there is no Year or Decade view): the list has two
 * closed ends, and the charge a pinch builds up against an end is thrown away rather than kept, so
 * reversing the pinch moves at once instead of first paying back a debt nobody could see.
 *
 * Pure Kotlin on purpose (like [galleryShortcutFor]): the mapping into Compose types -- the pointer
 * stream, the window width in dp -- happens at the call sites, so everything here is checked by a
 * plain JVM test.
 */
const val ZOOM_RUNG_MIN = 1

/** The widest of the plain column grids. Also the widest the photo picker and a masonry grid go. */
const val ZOOM_RUNG_LAST_GRID = 6

/** The stamp view: one tiny stamp per photo, grouped under a header per month. The last rung. */
const val ZOOM_RUNG_STAMPS = 7

/** Three columns, the same density the grid has always opened at. */
const val DEFAULT_ZOOM_RUNG = 3

/**
 * How much accumulated pinch it takes to move one rung, in natural-log-of-scale units.
 *
 * `ln(1.25)`: a pinch that has changed the on-screen distance between the two fingers by 25%
 * steps the list once. Chosen, not tuned from a device -- there is no device in this loop -- but
 * the shape it produces is what the owner asked for ("deliberate, not twitchy"): a small
 * correction mid-gesture (a percent or two of finger-spread jitter) is far below it and changes
 * nothing, while a real "I want the next density" pinch crosses it in one clean step. The viewer's
 * panel-shortcut pinch reuses the number for the same judgement.
 *
 * The leftover after a step stays in the accumulator (see [ZoomStep.residual]), and that is also
 * what keeps a finger resting near a boundary from flickering between two rungs: stepping back
 * takes a further full threshold of motion the other way, not a hair of it.
 */
const val PINCH_STEP_THRESHOLD = 0.22f

/** The most tiles ever drawn across one row of stamps, however wide the window. */
private const val STAMPS_ACROSS_MIN = 8
private const val STAMPS_ACROSS_MAX = 24

/** The rung after one gesture frame: where it landed, and the pinch motion not yet spent. */
data class ZoomStep(val rung: Int, val residual: Float)

/**
 * Folds one gesture frame's multiplicative scale change into the rung, stepping at most as many
 * rungs as the accumulated motion actually crossed.
 *
 * [scaleFactor] is this frame's zoom ratio as gesture APIs report it -- greater than 1 for fingers
 * spreading apart, less than 1 for pinching together, and very close to 1 on almost every frame of
 * a real gesture. [ln] turns that multiplicative signal into an additive one the [residual] can
 * keep summing across frames: `ln(a) + ln(b) == ln(a*b)` exactly, where multiplying raw ratios
 * would drift under float rounding over hundreds of frames.
 *
 * Spreading (greater than 1, zooming in) descends -- fewer, bigger tiles. Pinching together
 * (less than 1, zooming out) climbs: more columns, then Stamps. That sign flip is the one place
 * "spread to see bigger, pinch to see more" becomes a number.
 *
 * [maxRung] is the last rung this grid can show: [ZOOM_RUNG_STAMPS] for the main grid, and
 * [ZOOM_RUNG_LAST_GRID] where there are no stamps (a masonry grid, the photo picker).
 */
fun pinchStep(
    rung: Int,
    residual: Float,
    scaleFactor: Float,
    maxRung: Int = ZOOM_RUNG_STAMPS,
    threshold: Float = PINCH_STEP_THRESHOLD,
): ZoomStep {
    require(scaleFactor > 0f) { "scaleFactor must be positive, was $scaleFactor" }
    require(threshold > 0f) { "threshold must be positive, was $threshold" }

    val top = maxRung.coerceIn(ZOOM_RUNG_MIN, ZOOM_RUNG_STAMPS)
    var current = rung.coerceIn(ZOOM_RUNG_MIN, top)
    var acc = residual - ln(scaleFactor)

    while (acc >= threshold && current < top) {
        current += 1
        acc -= threshold
    }
    while (acc <= -threshold && current > ZOOM_RUNG_MIN) {
        current -= 1
        acc += threshold
    }
    // Discard whatever was pushed against a closed end, or the next, opposite pinch would first
    // have to spend it.
    if (current == ZOOM_RUNG_MIN && acc < 0f) acc = 0f
    if (current == top && acc > 0f) acc = 0f

    return ZoomStep(current, acc)
}

/** [rung] moved by [delta] rungs (positive zooms out), held inside `1..maxRung`. */
fun stepRung(rung: Int, delta: Int, maxRung: Int = ZOOM_RUNG_STAMPS): Int {
    val top = maxRung.coerceIn(ZOOM_RUNG_MIN, ZOOM_RUNG_STAMPS)
    return (rung + delta).coerceIn(ZOOM_RUNG_MIN, top)
}

/**
 * The next rung out, wrapping from the last one back to the first -- what the density button does,
 * since a button has no gesture to go back with.
 */
fun wrapRung(rung: Int, maxRung: Int = ZOOM_RUNG_STAMPS): Int {
    val top = maxRung.coerceIn(ZOOM_RUNG_MIN, ZOOM_RUNG_STAMPS)
    return if (rung >= top) ZOOM_RUNG_MIN else rung.coerceAtLeast(ZOOM_RUNG_MIN) + 1
}

/** Whether [rung] is the stamp view. */
fun isStampRung(rung: Int): Boolean = rung >= ZOOM_RUNG_STAMPS

/** How many tiles sit across one row at [rung]; [stampAcross] is the stamp view's own count. */
fun columnsAcross(rung: Int, stampAcross: Int): Int =
    if (isStampRung(rung)) stampAcross.coerceAtLeast(1) else rung.coerceIn(ZOOM_RUNG_MIN, ZOOM_RUNG_LAST_GRID)

/**
 * How many stamps fit across [widthDp], each about [targetTileDp] wide with [gutterDp] between,
 * held to `8..24` so a narrow window still reads as a sheet of stamps and a very wide one does not
 * turn them to dust. Width in dp, not px, so the answer is the same on every screen density.
 */
fun stampAcross(widthDp: Float, targetTileDp: Float = 36f, gutterDp: Float = 4f): Int {
    if (!widthDp.isFinite() || widthDp <= 0f) return STAMPS_ACROSS_MIN
    val fit = ((widthDp + gutterDp) / (targetTileDp + gutterDp)).toInt()
    return fit.coerceIn(STAMPS_ACROSS_MIN, STAMPS_ACROSS_MAX)
}

/** The columns the photo picker draws for a stored rung: the stamp view is not offered there. */
fun pickerColumnsFor(rung: Int): Int = rung.coerceIn(ZOOM_RUNG_MIN, ZOOM_RUNG_LAST_GRID)

/**
 * A stored column count from before the rungs, as a rung. The old range was 2..7: 2 to 6 are the
 * same rung, and a 7 becomes 6 rather than Stamps, so nobody's grid changes structure under them
 * on the update.
 */
fun zoomRungFromLegacyColumns(columns: Int): Int = columns.coerceIn(ZOOM_RUNG_MIN, ZOOM_RUNG_LAST_GRID)

/** What a screen reader and a stepper say for [rung]: "1 column", "3 columns", "Stamps". */
fun zoomRungLabel(rung: Int): String = when {
    isStampRung(rung) -> "Stamps"
    rung <= ZOOM_RUNG_MIN -> "1 column"
    else -> "${rung.coerceAtMost(ZOOM_RUNG_LAST_GRID)} columns"
}

/** The short form for a stepper's value: "3" or "Stamps". */
fun zoomRungValue(rung: Int): String =
    if (isStampRung(rung)) "Stamps" else rung.coerceIn(ZOOM_RUNG_MIN, ZOOM_RUNG_LAST_GRID).toString()
