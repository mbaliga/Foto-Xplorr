package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.isStampRung
import dev.aarso.cellshell.ScrubberEdge

/**
 * Where the timeline strip sits (owner #25: "can be left or right with a vertical scroll, or top
 * and bottom with a horizontal scroll").
 *
 * This is only where the strip is drawn. It does not decide which way the photos scroll and it
 * does not decide their order: the strip maps the START of its track to the first item and the END
 * to the last on every edge, so a strip on any side scrubs any grid. The order is the sort (see
 * [timelineSortLabel]) and the scroll axis is its own setting.
 */
enum class TimelinePlacement(val label: String) {
    LEFT("Left"),
    RIGHT("Right"),
    TOP("Top"),
    BOTTOM("Bottom"),
}

/** The strip's thickness: the library's own 44dp, and so the gutter the grid makes room for. */
internal const val TIMELINE_STRIP_DP = 44

/** The library's edge for a placement; the names are the same four. */
internal fun TimelinePlacement.edge(): ScrubberEdge = when (this) {
    TimelinePlacement.LEFT -> ScrubberEdge.LEFT
    TimelinePlacement.RIGHT -> ScrubberEdge.RIGHT
    TimelinePlacement.TOP -> ScrubberEdge.TOP
    TimelinePlacement.BOTTOM -> ScrubberEdge.BOTTOM
}

/**
 * Whether the strip is part of the screen right now (and so whether the grid makes room for it).
 *
 * It needs a grid it can drive and a date order to describe: not the masonry layout (its scroll
 * state is private, so there is nothing for the strip to track), not the map or a pane drawn in the
 * grid's place, and not Name or Size order, where a month is not a place in the list and the stops
 * would just be noise. Nor an empty view, which has nothing to scrub.
 */
internal fun stripShouldShow(
    timelineOn: Boolean,
    sort: GallerySort,
    masonry: Boolean,
    mapOpen: Boolean,
    paneTakenOver: Boolean,
    hasStops: Boolean,
): Boolean = timelineOn && isDateOrder(sort) && !masonry && !mapOpen && !paneTakenOver && hasStops

/** Newest or Oldest first: the two orders in which a month names a stretch of the list. */
internal fun isDateOrder(sort: GallerySort): Boolean = sort == GallerySort.NEWEST || sort == GallerySort.OLDEST

/**
 * Whether the grid draws a header row above each stretch of photos.
 *
 * The stamp rung always does (flat for Name and Size, see [stampHeadersShown]). On the column
 * rungs the headers are the timeline's: they appear only while it is on, in a date order, and
 * when grouping is not None. A masonry grid has no full-width item to hang one on.
 */
internal fun headersShown(
    rung: Int,
    sort: GallerySort,
    masonry: Boolean,
    timelineOn: Boolean,
    grouping: TimelineGrouping,
): Boolean = stampHeadersShown(rung, sort, masonry) ||
    (!masonry && isDateOrder(sort) && timelineOn && grouping != TimelineGrouping.NONE)

/** The grouping a header row follows: the stamps are always months, the columns follow the setting. */
internal fun headerGrouping(rung: Int, grouping: TimelineGrouping): TimelineGrouping =
    if (isStampRung(rung)) TimelineGrouping.MONTH else grouping

/** The room the grid leaves around itself for the strip, in dp, one side per field. */
internal data class TimelineReserve(val start: Int = 0, val top: Int = 0, val end: Int = 0, val bottom: Int = 0) {
    val isEmpty: Boolean get() = start == 0 && top == 0 && end == 0 && bottom == 0
}

/**
 * The gutter the strip takes: its own thickness on its own side and nothing on the other three, or
 * nothing at all while it is not [shown]. Left and right are literal (not start and end), because
 * the strip is placed by the same words.
 */
internal fun timelineReserve(shown: Boolean, placement: TimelinePlacement): TimelineReserve {
    if (!shown) return TimelineReserve()
    return when (placement) {
        TimelinePlacement.LEFT -> TimelineReserve(start = TIMELINE_STRIP_DP)
        TimelinePlacement.RIGHT -> TimelineReserve(end = TIMELINE_STRIP_DP)
        TimelinePlacement.TOP -> TimelineReserve(top = TIMELINE_STRIP_DP)
        TimelinePlacement.BOTTOM -> TimelineReserve(bottom = TIMELINE_STRIP_DP)
    }
}

/**
 * How far the controls floating at the bottom (the pill, the two discs, the time range) rise so a
 * strip along the bottom edge does not sit under them. Only that placement needs it.
 */
internal fun floatingChromeLift(shown: Boolean, placement: TimelinePlacement): Int =
    if (shown && placement == TimelinePlacement.BOTTOM) TIMELINE_STRIP_DP else 0

/**
 * How far the album or collection bar drops so a strip along the top edge does not sit under it.
 * Zero while the search field is up: the field is above the grid's gutter, and the bar already
 * overlays the field rather than the strip.
 */
internal fun routeBarDrop(shown: Boolean, placement: TimelinePlacement, searchVisible: Boolean): Int =
    if (shown && placement == TimelinePlacement.TOP && !searchVisible) TIMELINE_STRIP_DP else 0

/**
 * Whether the strip stays drawn while photos are being selected. The selection toolbars float over
 * the top corners and the trash over the bottom right, so a strip along the top or bottom would be
 * hidden behind them; a side strip clears all three and stays useful for a long selection. The
 * gutter stays either way, so the grid does not jump when a selection starts.
 */
internal fun stripDrawnWhileSelecting(placement: TimelinePlacement): Boolean =
    placement == TimelinePlacement.LEFT || placement == TimelinePlacement.RIGHT

/** Whether the library's end of the list is the latest photo: the Oldest first order. */
internal fun latestAtEnd(sort: GallerySort): Boolean = sort == GallerySort.OLDEST

/**
 * The item the grid opens at: the first one, or the last (which clamps the scroll so the end of the
 * list sits flush) when the latest photos are at the end. An empty grid opens at 0.
 */
internal fun anchorIndex(itemCount: Int, latestAtEnd: Boolean): Int =
    if (latestAtEnd && itemCount > 0) itemCount - 1 else 0

/**
 * Whether the grid may be moved to its opening position yet. While the library is still being
 * scanned the photos arrive in batches and the latest end keeps moving; anchoring on the first
 * batch would leave the Oldest first order stranded short of the real end.
 */
internal fun anchorReady(hasPhotos: Boolean, scanning: Boolean): Boolean = hasPhotos && !scanning

/**
 * What decides whether the grid is re-anchored: a new destination, route or sort, or a request
 * (choosing a rail word, even the one already chosen, asks for the start again). A plain string so
 * it can be saved with the rest of the browser's state and compared after the viewer round trip
 * or a restore, where the same token must NOT move a scroll position that came back with it.
 */
internal fun anchorToken(destination: String, route: String, sort: GallerySort, request: Int): String =
    "$destination|$route|${sort.name}|$request"

/**
 * The sort rows in the words that say where the latest photo sits. On a vertical grid the date
 * orders read as top and bottom; on a horizontal one, left and right.
 */
internal fun timelineSortLabel(sort: GallerySort, horizontal: Boolean = false): String = when (sort) {
    GallerySort.NEWEST -> if (horizontal) "Latest at left" else "Latest at top"
    GallerySort.OLDEST -> if (horizontal) "Latest at right" else "Latest at bottom"
    GallerySort.NAME -> "By name"
    GallerySort.SIZE -> "Largest first"
}
