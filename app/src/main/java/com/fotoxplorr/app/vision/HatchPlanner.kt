package com.fotoxplorr.app.vision

/**
 * The line and dot patterns that can mark a colour region. Six, so a picture of at most
 * [ColourIndex.MAX_K] colours never needs to reuse one. Listed in the order the planner hands them out.
 */
internal enum class HatchPattern {
    DIAGONAL,
    DOTS,
    CROSS,
    HORIZONTAL,
    ANTI_DIAGONAL,
    VERTICAL,
    ;

    /**
     * Whether the pixel at ([x], [y]) is ink for this pattern, with a repeat of [period] pixels and
     * a stroke a quarter as thick (at least one pixel). Pure, so the same pattern lines up across tiles.
     */
    fun isInk(x: Int, y: Int, period: Int = DEFAULT_PERIOD): Boolean {
        val p = period.coerceAtLeast(2)
        val w = maxOf(1, p / 4)
        val px = Math.floorMod(x, p)
        val py = Math.floorMod(y, p)
        return when (this) {
            DIAGONAL -> Math.floorMod(x + y, p) < w
            ANTI_DIAGONAL -> Math.floorMod(x - y, p) < w
            HORIZONTAL -> py < w
            VERTICAL -> px < w
            CROSS -> px < w || py < w
            DOTS -> px < w + 1 && py < w + 1
        }
    }

    companion object {
        const val DEFAULT_PERIOD = 8
    }
}

/**
 * Which clusters need a pattern. [patterns] is per cluster id: null for a cluster that is not
 * confusable with any other, otherwise its pattern. [pairs] lists the confusable pairs (lower id first).
 */
internal class HatchPlan(val patterns: List<HatchPattern?>, val pairs: List<Pair<Int, Int>>) {
    val isEmpty: Boolean get() = pairs.isEmpty()
}

/**
 * Plans pattern overlays for a colour-index picture ([ColourIndex]): a second cue for regions a
 * person with a colour-vision deficiency would take for the same colour.
 *
 * Two clusters are CONFUSABLE when, after [Simulation] at the chosen deficiency and severity, their
 * CIE76 Lab distance is below [DEFAULT_THRESHOLD], while in the original they differ by at least that
 * much (a pair that is already alike to normal vision gets no pattern). The threshold is well above a
 * just-noticeable difference (about 2.3) because photo regions are seen apart and not side by side.
 * Only clusters in at least one confusable pair get a pattern; every such cluster gets its own while
 * patterns last (six), and if more are needed a cluster takes the pattern least used among those not
 * worn by one of its confusable partners.
 *
 * It decides on cluster colours only; it cannot know whether two regions touch, so a far-apart pair
 * still gets patterns, which costs clutter and nothing else.
 */
internal object HatchPlanner {
    const val DEFAULT_THRESHOLD = 12.0

    fun plan(
        centres: IntArray,
        deficiency: Deficiency,
        severity: Double = 1.0,
        threshold: Double = DEFAULT_THRESHOLD,
    ): HatchPlan {
        val n = centres.size
        val seen = IntArray(n) { Simulation.simulate(centres[it], deficiency, severity) }
        val pairs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) for (j in i + 1 until n) {
            if (ColourSpace.deltaE(seen[i], seen[j]) < threshold && ColourSpace.deltaE(centres[i], centres[j]) >= threshold) {
                pairs += i to j
            }
        }
        val partners = Array(n) { ArrayList<Int>() }
        for ((a, b) in pairs) {
            partners[a] += b
            partners[b] += a
        }
        val all = HatchPattern.entries
        val used = IntArray(all.size)
        val assigned = arrayOfNulls<HatchPattern>(n)
        for (c in 0 until n) {
            if (partners[c].isEmpty()) continue
            val blocked = partners[c].mapNotNull { assigned[it] }.toSet()
            val pick = all.filter { it !in blocked }.ifEmpty { all }.minByOrNull { used[it.ordinal] }!!
            assigned[c] = pick
            used[pick.ordinal]++
        }
        return HatchPlan(assigned.toList(), pairs)
    }

    /** Per pixel, whether a pattern ink goes there: true only on pixels of a hatched cluster that fall on its pattern. */
    fun inkMask(image: ColourIndexImage, plan: HatchPlan, period: Int = HatchPattern.DEFAULT_PERIOD): BooleanArray {
        val mask = BooleanArray(image.width * image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val i = y * image.width + x
            val pattern = plan.patterns.getOrNull(image.index[i].toInt()) ?: continue
            mask[i] = pattern.isInk(x, y, period)
        }
        return mask
    }
}
