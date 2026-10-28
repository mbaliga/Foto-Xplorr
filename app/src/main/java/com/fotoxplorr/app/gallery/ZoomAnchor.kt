package com.fotoxplorr.app.gallery

import com.fotoxplorr.core.organize.GridIndexMap

/**
 * Where the grid must jump to when the layout stops drawing month headers, or null to leave it to
 * the lazy grid.
 *
 * A lazy grid keeps its place across a change by the KEY of the first visible item. A photo's key
 * (its id) exists in every layout, but a header's does not exist once the headers are gone, so the
 * grid falls back to the same NUMERIC index -- which is a different photo, one further on for every
 * header above the old position (up to ~180 items for 22k photos over 15 years). When the top line
 * was a header ([oldFirstVisible] is a header row of [old]) the run's first photo is the right
 * anchor: it is what the header stood over.
 *
 * Only a layout that loses its headers needs this; the other way every top item is a photo and
 * its key is found. The same holds when both layouts have headers but not the same ones
 * ([headersDiffer]: months on the stamps, days on the columns), where the old header's key is
 * gone just the same.
 */
internal fun reanchoredGridIndex(
    old: GridIndexMap,
    oldHasHeaders: Boolean,
    new: GridIndexMap,
    newHasHeaders: Boolean,
    oldFirstVisible: Int,
    headersDiffer: Boolean = false,
): Int? {
    if (!oldHasHeaders || (newHasHeaders && !headersDiffer)) return null
    val asset = old.assetIndexAt(oldFirstVisible)
    // A header row maps to its run's first asset, whose own item is the row after it; any other
    // item (a photo, the footer) is found by its key.
    if (old.gridIndexOf(asset) <= oldFirstVisible) return null
    return new.gridIndexOf(asset)
}

/**
 * What the grid keeps of the layout it LAST DREW, so the next layout can be anchored to it.
 *
 * It outlives the grid: Settings, a destination screen and the map replace the grid in the
 * browsing `when`, which disposes [ZoomGrid], while the hoisted `LazyGridState` keeps its old
 * numeric index. A copy remembered inside the grid would be gone by the time the layout changed
 * under that index (Timeline off, Group by None, a rung picked in Settings), and the first
 * composition back would find nothing to compare with. So the browser owns one and every grid it
 * draws is handed it.
 *
 * Plain fields, not state: they are only read by the next [settle], and a snapshot read would
 * recompose the grid on every scroll.
 */
internal class LayoutAnchor {
    private var map: GridIndexMap? = null
    private var hasHeaders = false
    private var grouping = TimelineGrouping.MONTH
    private var sideways = false

    /**
     * Records the layout now drawn and returns the item the grid must be moved to first, if any.
     * [firstVisible] is still the PREVIOUS layout's top line: this runs after composition and
     * before the next measure.
     */
    fun settle(
        map: GridIndexMap,
        hasHeaders: Boolean,
        grouping: TimelineGrouping,
        sideways: Boolean,
        firstVisible: Int,
    ): Int? {
        val previous = this.map
        var target: Int? = null
        if (previous != null && previous !== map) {
            target = reanchoredGridIndex(
                old = previous,
                oldHasHeaders = this.hasHeaders,
                new = map,
                newHasHeaders = hasHeaders,
                headersDiffer = this.grouping != grouping,
                oldFirstVisible = firstVisible,
            )
        }
        // Turning the grid on its side keeps the same first item but not the old pixel offset into
        // it, which was measured along the other axis and could now be longer than the tile.
        if (target == null && previous != null && this.sideways != sideways) target = firstVisible
        this.map = map
        this.hasHeaders = hasHeaders
        this.grouping = grouping
        this.sideways = sideways
        return target
    }

    /**
     * Drops what was drawn, for a moment the grid's scroll position is no longer one of its
     * layouts' (an empty view, or the plain grid that has no headers): comparing the next layout
     * with the last one drawn would anchor on an index that layout never had.
     */
    fun forget() {
        map = null
    }
}
