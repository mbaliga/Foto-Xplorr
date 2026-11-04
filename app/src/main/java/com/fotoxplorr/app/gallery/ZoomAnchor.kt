package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
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

    /** The justified rows last drawn, or null when the last layout was the square grid (or none). */
    private var justified: JustifiedLayout? = null

    /** The photos the last layout drew, so the photo at the top is found again by its id. */
    private var assets: List<MediaAsset>? = null

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
        assets: List<MediaAsset>? = null,
    ): Int? {
        val previous = this.map
        val previousRows = justified
        var target: Int? = null
        if (previousRows != null) {
            // From the justified rows: the old index is a ROW, which means nothing as a square
            // grid item. Carry the photo heading that row instead.
            target = carried(JustifiedIndexSpace(previousRows), MapIndexSpace(map), firstVisible, assets)
        } else if (previous != null && previous !== map) {
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
        this.justified = null
        this.assets = assets
        return target
    }

    /**
     * The justified rows' counterpart of [settle]. Rows are rebuilt on every rung change (the row
     * height follows the rung) and their keys (`jrow:<first id>`) mostly do not survive that, so
     * the lazy grid would keep a bare row number that now holds quite another photo; coming from
     * the square grid the old index is an ITEM, read here as a row. Both ways the photo at the top
     * is carried by id into the row that now holds it.
     */
    fun settleJustified(layout: JustifiedLayout, assets: List<MediaAsset>, firstVisible: Int): Int? {
        val previousRows = justified
        val previousMap = map
        val old: GridIndexSpace? = when {
            previousRows === layout && this.assets === assets -> null
            previousRows != null -> JustifiedIndexSpace(previousRows)
            previousMap != null -> MapIndexSpace(previousMap)
            else -> null
        }
        var target = old?.let { carried(it, JustifiedIndexSpace(layout), firstVisible, assets) }
        if (target != null && previousRows != null && keptByKey(previousRows, layout, firstVisible, target, assets)) {
            target = null
        }
        justified = layout
        map = null
        sideways = false
        this.assets = assets
        return target
    }

    /**
     * Whether the lazy grid already keeps the top row by itself: the row [target] of [new] is headed
     * by the same photo as row [firstVisible] of [old] (so its `jrow:<id>` key is found) and is as
     * tall, so the pixel offset into it still means the same place. A list refresh (MediaStore, a
     * favourite, a photo arriving further down) rebuilds the rows but leaves the top one as it was;
     * jumping there with requestScrollToItem would drop that offset and snap the row's top edge to
     * the viewport top, mid-fling. A rung change alters the height and still jumps.
     */
    private fun keptByKey(old: JustifiedLayout, new: JustifiedLayout, firstVisible: Int, target: Int, newAssets: List<MediaAsset>): Boolean {
        val oldAssets = assets ?: return false
        if (old.rows == 0 || new.rows == 0 || firstVisible !in 0 until old.rows || target !in 0 until new.rows) return false
        val oldHead = oldAssets.getOrNull(old.rowItems(firstVisible).first)?.id ?: return false
        val newHead = newAssets.getOrNull(new.rowItems(target).first)?.id ?: return false
        return oldHead == newHead && kotlin.math.abs(old.rowHeight(firstVisible) - new.rowHeight(target)) < 0.5f
    }

    /** The item of [new] showing the photo that item [firstVisible] of [old] showed. */
    private fun carried(old: GridIndexSpace, new: GridIndexSpace, firstVisible: Int, newAssets: List<MediaAsset>?): Int? {
        if (old.itemCount <= 1 || new.itemCount <= 1) return null
        val oldIndex = old.assetIndexAt(firstVisible)
        val oldAssets = assets
        val newIndex = if (oldAssets == null || newAssets == null || oldAssets === newAssets) {
            oldIndex
        } else {
            val id = oldAssets.getOrNull(oldIndex)?.id
            newAssets.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: oldIndex
        }
        return new.gridIndexOf(newIndex)
    }

    /**
     * Drops what was drawn, for a moment the grid's scroll position is no longer one of its
     * layouts' (an empty view, or the plain grid that has no headers): comparing the next layout
     * with the last one drawn would anchor on an index that layout never had.
     */
    fun forget() {
        map = null
        justified = null
        assets = null
    }
}
