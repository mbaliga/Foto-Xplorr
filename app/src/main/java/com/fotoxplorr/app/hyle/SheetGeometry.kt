package com.fotoxplorr.app.hyle

import kotlin.math.sqrt

/**
 * The geometry of the parked card when a top room is pulled open: the grid becomes a sheet that has
 * slipped down, with rounded top corners, and whatever the room (or the shade inside the card)
 * puts near those corners has to stay clear of them.
 *
 * The shell draws the corners itself (`SpatialMotion.PARK_CORNER_DP * lift`), so [sheetCornerDp]
 * only restates that rule for the app's own content: if the two ever differ the insets below are
 * computed for a corner that is not there. Pure, so both can be pinned in a JVM test.
 */
object SheetGeometry {
    /** The shell's park radius at full lift (`SpatialMotion.PARK_CORNER_DP`). */
    const val FULL_CORNER_DP = 20f

    /** Never closer to a rounded corner than this, in dp. */
    const val CORNER_MARGIN_DP = 6f

    /** The corner radius at a pull of [lift] (0 closed, 1 fully open): eased in, 0 at rest. */
    fun sheetCornerDp(lift: Float): Float = FULL_CORNER_DP * lift.coerceIn(0f, 1f)

    /**
     * The corner of the grid sheet under the activity shade while the shade is [heightDp] tall:
     * square at the collapsed sliver (nothing changes at rest) and the full radius at one
     * notification row's height (the 22 dp from the 28 dp sliver to the 50 dp row), easing in
     * with the shade's own animation.
     */
    fun shadeSheetCornerDp(heightDp: Float, collapsedDp: Float = 28f, rampDp: Float = 22f): Float =
        sheetCornerDp((heightDp - collapsedDp) / rampDp)

    /**
     * How far a row whose text starts [depthDp] below the sheet's top edge must be pushed in from
     * the left or right edge to clear a corner of [cornerDp]: the circle's own horizontal bite at
     * that depth, plus [CORNER_MARGIN_DP]. Zero once the row is below the corner, and for no corner.
     */
    fun cornerClearanceDp(cornerDp: Float, depthDp: Float): Float {
        if (cornerDp <= 0f || depthDp >= cornerDp) return 0f
        val dy = cornerDp - depthDp.coerceAtLeast(0f)
        val bite = cornerDp - sqrt((cornerDp * cornerDp - dy * dy).coerceAtLeast(0f))
        return bite + CORNER_MARGIN_DP
    }

    /**
     * The side insets for content that sits [depthDp] below the top of the sheet: at least the
     * content's own [baseDp] gutter, at least the corner's clearance, and the timeline's own
     * gutter on whichever side it stands ([gutterStartDp] for a left strip, [gutterEndDp] for a
     * right one) added on top, because the strip is drawn over that edge.
     */
    fun contentInsets(
        cornerDp: Float,
        depthDp: Float,
        baseDp: Float,
        gutterStartDp: Float = 0f,
        gutterEndDp: Float = 0f,
    ): Insets {
        val side = maxOf(baseDp, cornerClearanceDp(cornerDp, depthDp))
        return Insets(startDp = side + gutterStartDp, endDp = side + gutterEndDp)
    }

    data class Insets(val startDp: Float, val endDp: Float)
}
