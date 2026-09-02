package com.fotoxplorr.app.adaptive

import kotlin.math.ln

/**
 * Everything a pinch (or Ctrl+scroll) can leave the gallery showing, as ONE ordered ladder.
 *
 * The owner's ask was specific: zoom out past the sparsest grid and you reach Calendar; further
 * out, Map. Modelling that as three unrelated screens the gesture happens to jump between would
 * mean the "further out" rule lives nowhere -- it would have to be re-derived at every call site
 * that touches zoom. Modelling it as one sealed rung on a ladder means "further out" is just
 * "the next rung", checkable once, here.
 */
sealed interface GalleryZoomLevel {
    /** A grid at a given column count -- more columns is denser (more, smaller tiles). */
    data class Grid(val columns: Int) : GalleryZoomLevel

    /** One rung sparser than the widest grid: the month calendar. */
    data object Calendar : GalleryZoomLevel

    /** Sparser still: the map. The far end of the ladder. */
    data object MapView : GalleryZoomLevel
}

/**
 * The ladder's shape for one [minColumns]..[maxColumns] range, and the arithmetic that maps a
 * [GalleryZoomLevel] to its position on it (its "rung") and back.
 *
 * Rung 0 is the densest grid (`Grid(minColumns)` -- fewest columns is the LARGEST tiles, which
 * this file treats as "most zoomed in"; see the class doc above). Rungs climb through
 * `Grid(minColumns + 1) ... Grid(maxColumns)`, then [GalleryZoomLevel.Calendar], then
 * [GalleryZoomLevel.MapView] at the top. Climbing a rung is always "zoom out one notch" and
 * descending is always "zoom in one notch", for every rung on the ladder -- the one invariant
 * [step] relies on to treat a grid-density change and a Calendar<->Map transition as the same
 * kind of move.
 */
class ZoomLadder(val minColumns: Int, val maxColumns: Int) {
    init {
        require(minColumns in 1..maxColumns) {
            "minColumns ($minColumns) must be positive and at most maxColumns ($maxColumns)"
        }
    }

    /** Grid rungs, plus Calendar, plus Map. */
    val rungCount: Int
        get() = (maxColumns - minColumns + 1) + 2

    private val lastGridRung: Int
        get() = maxColumns - minColumns

    /** Where [level] sits on the ladder. A `Grid` outside [minColumns]..[maxColumns] clamps. */
    fun rungOf(level: GalleryZoomLevel): Int = when (level) {
        is GalleryZoomLevel.Grid -> level.columns.coerceIn(minColumns, maxColumns) - minColumns
        GalleryZoomLevel.Calendar -> lastGridRung + 1
        GalleryZoomLevel.MapView -> lastGridRung + 2
    }

    /** The level at [rung], clamped into range -- the ladder has two closed ends, not a wrap. */
    fun levelAt(rung: Int): GalleryZoomLevel {
        val clamped = rung.coerceIn(0, rungCount - 1)
        return when {
            clamped <= lastGridRung -> GalleryZoomLevel.Grid(minColumns + clamped)
            clamped == lastGridRung + 1 -> GalleryZoomLevel.Calendar
            else -> GalleryZoomLevel.MapView
        }
    }
}

/** The ladder's new position after one gesture frame: where it landed, and what is left over. */
data class ZoomStep(val level: GalleryZoomLevel, val residual: Float)

/**
 * How much accumulated pinch it takes to move one rung, in natural-log-of-scale units.
 *
 * `ln(1.25)`: a pinch that has changed the on-screen distance between the two fingers by 25%
 * steps the ladder once. Chosen, not tuned from a device -- there is no device in this loop --
 * but the shape it produces is exactly what the owner asked for ("deliberate, not twitchy"): a
 * small correction mid-gesture (a percent or two of finger-spread jitter) is far below it and
 * changes nothing, while a real "I want the next density" pinch (which is a large, fast motion
 * covering tens of percent) crosses it in one clean step rather than several.
 */
const val PINCH_STEP_THRESHOLD = 0.22f

/**
 * Fold one gesture frame's multiplicative scale change into the ladder, stepping at most as many
 * rungs as the accumulated motion actually crossed.
 *
 * [scaleFactor] is this frame's zoom ratio as gesture APIs report it -- greater than 1 for
 * fingers spreading apart, less than 1 for pinching together, and very close to 1 on almost
 * every frame of a real gesture (frames arrive far faster than fingers move). [ln] turns that
 * multiplicative, noisy-near-1 signal into an additive one that the same [residual] can keep
 * summing across frames without every intermediate frame's rounding compounding into drift --
 * `ln(a) + ln(b) == ln(a*b)` exactly, where multiplying the raw ratios directly would not stay
 * exact under float rounding over hundreds of frames. [AutoFix][com.fotoxplorr.app.editor.AutoFix]
 * leans on the same identity for exposure stops, for the same reason.
 *
 * Spreading (scaleFactor > 1, zooming in) descends the ladder -- fewer columns, and eventually
 * back out of Calendar/Map into the grid. Pinching together (scaleFactor < 1, zooming out)
 * climbs it -- more columns, then Calendar, then Map. That sign flip is the one place "spread to
 * see fewer/bigger, pinch to see more/smaller" (the owner's own phrasing) becomes a number.
 *
 * The residual is clamped to zero whenever a step lands on either closed end of the ladder
 * ([ZoomLadder.levelAt] cannot go further). Without that, a user who keeps pinching after
 * already reaching Map would silently "charge up" a large negative residual that does nothing
 * visible -- and then a single small reversing pinch would spend that whole charge at once and
 * jump back several rungs in one motion, which is the exact thrashing this function exists to
 * prevent, just deferred to the moment the user changes their mind instead of avoided.
 */
fun ZoomLadder.step(
    current: GalleryZoomLevel,
    residual: Float,
    scaleFactor: Float,
    threshold: Float = PINCH_STEP_THRESHOLD,
): ZoomStep {
    require(scaleFactor > 0f) { "scaleFactor must be positive, was $scaleFactor" }
    require(threshold > 0f) { "threshold must be positive, was $threshold" }

    var rung = rungOf(current)
    var acc = residual - ln(scaleFactor)

    while (acc >= threshold && rung < rungCount - 1) {
        rung += 1
        acc -= threshold
    }
    while (acc <= -threshold && rung > 0) {
        rung -= 1
        acc += threshold
    }
    if (rung == 0 && acc < 0f) acc = 0f
    if (rung == rungCount - 1 && acc > 0f) acc = 0f

    return ZoomStep(levelAt(rung), acc)
}
