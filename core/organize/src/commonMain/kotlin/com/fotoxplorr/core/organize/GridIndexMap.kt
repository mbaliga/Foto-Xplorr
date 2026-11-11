package com.fotoxplorr.core.organize

/**
 * Maps between "grid index" -- the LazyGrid's own item position, which can include one header
 * row before each group and one trailing footer spacer -- and "asset index" -- the position in
 * the flat, flattened asset list that the scrubber, the pill caption, arrow-key navigation and
 * opening the viewer all reason about. See docs/TRAPS.md #5 and #9: nothing that changes the
 * grid's item count may skip this map.
 *
 * [groupSizes] lists each group's asset count in the grid's own draw order -- this class does no
 * grouping of its own, it only accounts for the rows grouping adds. With [hasHeaders], each group
 * gets one extra header row immediately before its assets; with headers off the map is the
 * identity (plus [trailingFooter]), matching every headerless grid in this app. [trailingFooter]
 * accounts for the one spacer item every grid here draws after its last row.
 */
class GridIndexMap(
    groupSizes: List<Int>,
    hasHeaders: Boolean,
    trailingFooter: Boolean,
) {
    private val headerRows = if (hasHeaders) 1 else 0

    // Cumulative asset / grid-item count at the START of each group; index `groupSizes.size` is
    // a sentinel holding the running total in each space, so every lookup below can treat "past
    // the last real group" the same as any other group boundary.
    private val assetCumulative = IntArray(groupSizes.size + 1)
    private val gridCumulative = IntArray(groupSizes.size + 1)

    val itemCount: Int

    init {
        for (i in groupSizes.indices) {
            assetCumulative[i + 1] = assetCumulative[i] + groupSizes[i]
            gridCumulative[i + 1] = gridCumulative[i] + headerRows + groupSizes[i]
        }
        itemCount = gridCumulative[groupSizes.size] + if (trailingFooter) 1 else 0
    }

    private val groupCount = groupSizes.size
    private val totalAssets = assetCumulative[groupCount]

    /** The grid item that draws asset [assetIndex]. */
    fun gridIndexOf(assetIndex: Int): Int {
        if (totalAssets == 0) return 0
        val index = assetIndex.coerceIn(0, totalAssets - 1)
        val group = floorGroupByAsset(index)
        return gridCumulative[group] + headerRows + (index - assetCumulative[group])
    }

    /**
     * The asset that grid item [gridIndex] represents. A header row maps to its own group's
     * first asset; a footer row (or any index past the last group) maps to the very last asset.
     */
    fun assetIndexAt(gridIndex: Int): Int {
        if (totalAssets == 0) return 0
        val index = gridIndex.coerceIn(0, itemCount - 1)
        val group = floorGroupByGrid(index)
        val assetGridStart = gridCumulative[group] + headerRows
        return if (index < assetGridStart) {
            // On the header row itself.
            assetCumulative[group]
        } else {
            (assetCumulative[group] + (index - assetGridStart)).coerceAtMost(assetCumulative[group + 1] - 1)
        }
    }

    /** Largest group index `i` with `assetCumulative[i] <= assetIndex`, among real groups. */
    private fun floorGroupByAsset(assetIndex: Int): Int {
        var lo = 0
        var hi = groupCount - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (assetCumulative[mid] <= assetIndex) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /** Largest group index `i` with `gridCumulative[i] <= gridIndex`, among real groups. */
    private fun floorGroupByGrid(gridIndex: Int): Int {
        var lo = 0
        var hi = groupCount - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (gridCumulative[mid] <= gridIndex) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
