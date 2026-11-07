package com.fotoxplorr.app.basemap

/** A label that wants a spot: its rank (0 = most important), its anchor and the size it takes. */
data class LabelCandidate(val rank: Int, val x: Float, val y: Float, val width: Float, val height: Float)

/** A placed label's top-left corner. */
data class PlacedLabel(val index: Int, val left: Float, val top: Float)

/**
 * Greedy label collision: candidates are taken in rank order and kept when their box (grown by
 * [padding]) overlaps nothing already kept and sits inside the viewport. Labels sit centred
 * above-right of the dot, so [anchorGap] is the offset from the dot.
 */
object LabelPlacer {
    fun place(
        candidates: List<LabelCandidate>,
        viewportWidth: Float,
        viewportHeight: Float,
        padding: Float = 4f,
        anchorGap: Float = 6f,
        maxLabels: Int = 40,
    ): List<PlacedLabel> {
        val order = candidates.indices.sortedBy { candidates[it].rank }
        val kept = ArrayList<PlacedLabel>()
        val boxes = ArrayList<FloatArray>()
        for (i in order) {
            if (kept.size >= maxLabels) break
            val c = candidates[i]
            val left = c.x + anchorGap
            val top = c.y - c.height / 2f
            if (left < 0f || top < 0f || left + c.width > viewportWidth || top + c.height > viewportHeight) continue
            val l = left - padding
            val t = top - padding
            val r = left + c.width + padding
            val b = top + c.height + padding
            if (boxes.any { l < it[2] && r > it[0] && t < it[3] && b > it[1] }) continue
            boxes += floatArrayOf(l, t, r, b)
            kept += PlacedLabel(i, left, top)
        }
        return kept
    }

    /** How many of the (rank-sorted) cities may be considered at a zoom: few on the world, all deep in. */
    fun maxRankForLevel(level: Double, total: Int): Int =
        (8.0 * Math.pow(2.0, level * 0.9)).toInt().coerceIn(8, total.coerceAtLeast(8)).coerceAtMost(total)
}
