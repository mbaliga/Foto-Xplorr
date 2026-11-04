package com.fotoxplorr.app.gallery

/**
 * Justified layout (plan WP2.11): rows of photos at their own aspect ratios, every row stretched
 * to the full width, row heights near a target. The pure engine only; it measures nothing and draws
 * nothing, so the year / month / day / column levels of the zoom ladder are untouched. A Compose
 * grid reads [JustifiedLayout.rows] and offsets.
 *
 * Linear in the item count and allocation-light (two int arrays and one float array), so 500k
 * items are a single pass; [JustifiedLayout.rowOf] and [JustifiedLayout.rowAtOffset] are binary
 * searches and so are what the scrubber and a fling's visible-range lookup call.
 */
internal class JustifiedLayout private constructor(
    /** Index of the first item of row r; has rows+1 entries, the last being the item count. */
    private val rowStart: IntArray,
    /** Height in px of row r (without the gap). */
    private val rowHeight: FloatArray,
    /** Top offset of row r in px (cumulative, gaps included); rows+1 entries, the last = total height. */
    private val rowTop: FloatArray,
    private val aspects: FloatArray,
    private val width: Float,
    private val gap: Float,
    private val fullWidthRow: BooleanArray,
) {
    val rows: Int get() = rowHeight.size
    val itemCount: Int get() = aspects.size
    val totalHeight: Float get() = rowTop[rows]

    fun rowItems(row: Int): IntRange = rowStart[row] until rowStart[row + 1]
    fun rowHeight(row: Int): Float = rowHeight[row]
    fun rowTop(row: Int): Float = rowTop[row]

    /** The row that holds [item], or -1 when out of range. */
    fun rowOf(item: Int): Int {
        if (item !in 0 until itemCount) return -1
        var lo = 0
        var hi = rows - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rowStart[mid] <= item) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The row covering vertical [offset] (clamped), for "what is on screen" and for the scrubber. */
    fun rowAtOffset(offset: Float): Int {
        if (rows == 0) return -1
        var lo = 0
        var hi = rows - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rowTop[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Item rectangle: x and width within the row; y is [rowTop] of its row. */
    fun itemX(item: Int): Float {
        val row = rowOf(item)
        if (row < 0) return 0f
        var x = 0f
        for (i in rowStart[row] until item) x += aspects[i] * rowHeight[row] + gap
        return x
    }

    fun itemWidth(item: Int): Float {
        val row = rowOf(item)
        return if (row < 0) 0f else aspects[item] * rowHeight[row]
    }

    /** True when row [row] was stretched to the full width (a short last row is not). */
    fun isJustified(row: Int): Boolean = fullWidthRow[row]

    companion object {
        /** A photo with an unknown or absurd shape is laid out as 3:2 rather than breaking a row. */
        const val FALLBACK_ASPECT = 1.5f
        const val MIN_ASPECT = 0.2f
        const val MAX_ASPECT = 5f

        /** Past this fraction of the target, a last row is stretched to full width instead of left short. */
        const val LAST_ROW_STRETCH_FROM = 0.6f

        fun normalizedAspect(a: Float): Float =
            if (a.isNaN() || a <= 0f) FALLBACK_ASPECT else a.coerceIn(MIN_ASPECT, MAX_ASPECT)

        /**
         * @param aspectOf width / height of item i (the stored dimensions, orientation applied).
         * @param width available width in px.
         * @param targetRowHeight wanted row height in px; rows end up between about 0.7x and 1.4x of it.
         * @param gap space between items and rows in px.
         */
        fun build(
            count: Int,
            aspectOf: (Int) -> Float,
            width: Float,
            targetRowHeight: Float,
            gap: Float,
        ): JustifiedLayout {
            val aspects = FloatArray(count.coerceAtLeast(0)) { normalizedAspect(aspectOf(it)) }
            if (aspects.isEmpty() || width <= 0f || targetRowHeight <= 0f) {
                return JustifiedLayout(intArrayOf(0), FloatArray(0), floatArrayOf(0f), aspects, width, gap, BooleanArray(0))
            }
            val starts = ArrayList<Int>()
            val heights = ArrayList<Float>()
            val full = ArrayList<Boolean>()
            var start = 0
            var sum = 0f
            for (i in aspects.indices) {
                sum += aspects[i]
                val n = i - start + 1
                val usable = width - gap * (n - 1)
                val h = usable / sum
                // close the row when it would be at or below the target height with this item
                if (h <= targetRowHeight) {
                    // choose between ending here (h <= target) and before this item (taller than target)
                    val hBefore = if (n > 1) (width - gap * (n - 2)) / (sum - aspects[i]) else Float.MAX_VALUE
                    if (n > 1 && kotlin.math.abs(hBefore - targetRowHeight) < kotlin.math.abs(h - targetRowHeight)) {
                        starts += start; heights += hBefore; full += true
                        start = i
                        sum = aspects[i]
                        // the carried item may itself fill a row alone
                        val hAlone = width / sum
                        if (hAlone <= targetRowHeight) {
                            starts += start; heights += hAlone; full += true
                            start = i + 1
                            sum = 0f
                        }
                    } else {
                        starts += start; heights += h; full += true
                        start = i + 1
                        sum = 0f
                    }
                }
            }
            if (start < aspects.size) {
                val n = aspects.size - start
                val usable = width - gap * (n - 1)
                val h = usable / sum
                val stretch = h <= targetRowHeight / LAST_ROW_STRETCH_FROM
                starts += start
                heights += if (stretch) h else targetRowHeight
                full += stretch
            }
            val rowStart = IntArray(starts.size + 1)
            for (r in starts.indices) rowStart[r] = starts[r]
            rowStart[starts.size] = aspects.size
            val rowHeight = FloatArray(heights.size) { heights[it] }
            val rowTop = FloatArray(heights.size + 1)
            for (r in heights.indices) rowTop[r + 1] = rowTop[r] + heights[r] + (if (r < heights.size - 1) gap else 0f)
            return JustifiedLayout(rowStart, rowHeight, rowTop, aspects, width, gap, BooleanArray(full.size) { full[it] })
        }
    }
}
