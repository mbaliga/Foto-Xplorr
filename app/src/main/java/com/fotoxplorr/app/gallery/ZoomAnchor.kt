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
