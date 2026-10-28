package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.MoveDirection
import com.fotoxplorr.app.adaptive.isStampRung

/**
 * Which way the photos scroll (owner #25: "left or right with a vertical scroll, or top and bottom
 * with a horizontal scroll"). A setting of its own: it does not move the timeline strip (see
 * [TimelinePlacement]) and does not reorder the photos (that is the sort, worded by
 * [timelineSortLabel]). Vertical is the grid everyone already has.
 */
enum class ScrollAxis(val label: String) {
    VERTICAL("Vertical"),
    HORIZONTAL("Horizontal"),
}

/**
 * Whether the grid on screen scrolls sideways, which is not always what the setting says.
 *
 * Two grids stay vertical whatever is chosen: the stamp view, whose month headers and 8..24 across
 * are laid out for a screen's width, and the masonry layout, which is a staggered vertical grid.
 * Falling back to vertical at the stamps (rather than skipping that rung on the density pill) keeps
 * the pill and the pinch stepping through the same seven rungs on either axis; the setting is not
 * lost, the next column rung is sideways again.
 */
internal fun gridScrollsHorizontally(axis: ScrollAxis, rung: Int, masonry: Boolean): Boolean =
    axis == ScrollAxis.HORIZONTAL && !masonry && !isStampRung(rung)

/**
 * How far an arrow key moves the keyboard's cursor in the list of rendered photos. [across] is how
 * many tiles sit across the grid's short side (columns when it scrolls down, rows when it scrolls
 * sideways).
 *
 * The photos fill a line at a time, so the key that runs ALONG a line steps one photo and the key
 * that runs ACROSS lines steps a whole line: Up and Down are +-1 in a sideways grid, where Left and
 * Right take the long stride.
 */
internal fun arrowKeyStep(direction: MoveDirection, across: Int, horizontal: Boolean): Int {
    val stride = across.coerceAtLeast(1)
    return when (direction) {
        MoveDirection.UP -> if (horizontal) -1 else -stride
        MoveDirection.DOWN -> if (horizontal) 1 else stride
        MoveDirection.LEFT -> if (horizontal) -stride else -1
        MoveDirection.RIGHT -> if (horizontal) stride else 1
    }
}

/**
 * Whether the strip's edge runs the same way as the photos: a strip down a side suits a grid that
 * scrolls down, a strip along the top or bottom one that scrolls across. Only a hint for the
 * Settings text; nothing is forced, and any strip scrubs any grid.
 */
internal fun pairsNaturally(axis: ScrollAxis, placement: TimelinePlacement): Boolean {
    val alongTop = placement == TimelinePlacement.TOP || placement == TimelinePlacement.BOTTOM
    return alongTop == (axis == ScrollAxis.HORIZONTAL)
}

/** The width of a month's header column in the sideways grid, a full row-height tall. */
internal const val SIDE_HEADER_DP = 36

/** The room the sideways grid keeps along its bottom for the pill and the two discs. */
internal const val SIDE_GRID_BOTTOM_BAND_DP = 88

/** The empty column the sideways grid ends with (the footer item the index map counts). */
internal const val SIDE_FOOTER_DP = 24
