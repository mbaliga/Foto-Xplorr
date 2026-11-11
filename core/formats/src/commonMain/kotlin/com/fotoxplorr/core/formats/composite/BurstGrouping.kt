package com.fotoxplorr.core.formats.composite

/**
 * One photo as burst detection sees it. [folder] is equal exactly for files in the same folder;
 * [burstUuid] is Apple's BurstUUID when it has been read. [takenIsCaptureTime] is false when
 * [takenMs] is only a stand-in (the file's modification second when the photo has no taken date):
 * such a time says when the file was written, not when the photo was taken, so the time rule
 * ignores it.
 */
data class BurstCandidate<K>(
    val key: K,
    val folder: String,
    val displayName: String,
    val takenMs: Long,
    val burstUuid: String? = null,
    val takenIsCaptureTime: Boolean = true,
)

/** One burst: [members] in time order (ties by name), [cover] one of them. */
data class BurstStack<K>(val cover: K, val members: List<K>) {
    val size: Int get() = members.size
}

/** Why frames were put together, strongest first. */
enum class BurstReason { APPLE_UUID, NAME, TIME }

/**
 * Finds bursts in a list of photos. Pure; nothing is stored (the grid recomputes this as a
 * presentation and Settings > Media > Group bursts turns it off).
 *
 * 1. **Apple BurstUUID**: every frame of an iPhone burst carries the same one.
 * 2. **Names**: Google Camera's `..._BURST<digits>[_COVER]` (the digits are the burst id; `COVER`
 *    marks the frame the camera chose), the numbered `<prefix>_BURST<n>` form, and Samsung's
 *    `yyyyMMdd_HHmmss_NNN` (three-digit frame numbers after a shared second), all within one folder.
 * 3. **Time**: what is left, per folder, in time order: a run of at least [MIN_TIME_RUN] photos
 *    whose names share a prefix and end in numbers at most [MAX_NUMBER_STEP] apart, each within
 *    [MAX_GAP_MS] of the previous. Only real capture times count ([BurstCandidate.takenIsCaptureTime]):
 *    a batch of received or unzipped files with no taken date all share the second they were
 *    written. Names are required because a copied folder often has every file stamped with the same
 *    second; a run longer than [MAX_TIME_RUN] is taken to be such a copy and left alone.
 *
 * A group needs at least two frames. The cover is the `COVER`-named frame, else the first in time.
 */
object BurstGrouping {
    const val MAX_GAP_MS = 500L
    const val MIN_TIME_RUN = 3
    const val MAX_TIME_RUN = 100
    const val MAX_NUMBER_STEP = 2L

    fun <K> group(candidates: List<BurstCandidate<K>>): List<BurstStack<K>> {
        val out = ArrayList<BurstStack<K>>()
        val used = HashSet<K>()

        fun emit(members: List<BurstCandidate<K>>) {
            if (members.size < 2) return
            val ordered = members.sortedWith(compareBy<BurstCandidate<K>>({ it.takenMs }, { it.displayName }))
            val cover = ordered.firstOrNull { nameKey(it.displayName)?.isCover == true } ?: ordered.first()
            out += BurstStack(cover.key, ordered.map { it.key })
            ordered.forEach { used += it.key }
        }

        candidates.filter { it.burstUuid != null }
            .groupBy { it.burstUuid!!.lowercase() }
            .values.forEach(::emit)

        candidates.filter { it.key !in used }
            .mapNotNull { c -> nameKey(c.displayName)?.let { (c.folder to it.id) to c } }
            .groupBy({ it.first }, { it.second })
            .values.forEach(::emit)

        candidates.filter { it.key !in used && it.takenMs > 0 && it.takenIsCaptureTime }
            .groupBy { it.folder }
            .values.forEach { folder -> timeRuns(folder).forEach(::emit) }

        return out
    }

    /** Which rule would group a file by its name alone, and whether the name marks it as the cover. */
    data class NameKey(val id: String, val isCover: Boolean)

    fun nameKey(displayName: String): NameKey? {
        val base = LivePhotoPairing.baseName(displayName)
        val cover = base.contains("COVER", ignoreCase = true)
        GOOGLE.find(base)?.let { return NameKey("google:" + it.groupValues[1], cover) }
        NUMBERED.matchEntire(base)?.let { return NameKey("burst:" + it.groupValues[1].lowercase(), cover) }
        SAMSUNG.matchEntire(base)?.let { return NameKey("samsung:" + it.groupValues[1], cover) }
        return null
    }

    private fun <K> timeRuns(folder: List<BurstCandidate<K>>): List<List<BurstCandidate<K>>> {
        // Ties in time go by frame number, not text: `IMG_9` before `IMG_10`.
        val sorted = folder.sortedWith(compareBy<BurstCandidate<K>>({ it.takenMs }, { frameNumber(it.displayName) }, { it.displayName }))
        val runs = ArrayList<List<BurstCandidate<K>>>()
        var run = ArrayList<BurstCandidate<K>>()
        fun close() {
            if (run.size in MIN_TIME_RUN..MAX_TIME_RUN) runs += run
            run = ArrayList()
        }
        for (c in sorted) {
            val prev = run.lastOrNull()
            if (prev != null && (c.takenMs - prev.takenMs > MAX_GAP_MS || !sequential(prev.displayName, c.displayName))) close()
            run += c
        }
        close()
        return runs
    }

    /**
     * Same prefix, both ending in a number, the second at most [MAX_NUMBER_STEP] after the first:
     * `IMG_0041` then `IMG_0042` (or `IMG_0043`, a frame deleted in between), not `IMG_0041` then `IMG_0090`.
     */
    internal fun sequential(a: String, b: String): Boolean {
        val ma = TRAILING_NUMBER.matchEntire(LivePhotoPairing.baseName(a)) ?: return false
        val mb = TRAILING_NUMBER.matchEntire(LivePhotoPairing.baseName(b)) ?: return false
        if (!ma.groupValues[1].equals(mb.groupValues[1], ignoreCase = true)) return false
        val na = ma.groupValues[2].takeLast(MAX_DIGITS).toLongOrNull() ?: return false
        val nb = mb.groupValues[2].takeLast(MAX_DIGITS).toLongOrNull() ?: return false
        return nb - na in 1..MAX_NUMBER_STEP
    }

    private fun frameNumber(name: String): Long =
        TRAILING_NUMBER.matchEntire(LivePhotoPairing.baseName(name))?.groupValues?.get(2)?.takeLast(MAX_DIGITS)?.toLongOrNull() ?: -1L

    private const val MAX_DIGITS = 15

    private val GOOGLE = Regex("""BURST(\d{8,})""", RegexOption.IGNORE_CASE)
    private val NUMBERED = Regex("""(.+?)_BURST\d{1,4}(?:_COVER)?""", RegexOption.IGNORE_CASE)
    private val SAMSUNG = Regex("""(\d{8}_\d{6})_\d{3}""")
    private val TRAILING_NUMBER = Regex("""(.*?)(\d+)""")
}

/**
 * What the grid shows once bursts are stacked: [visible] (a collapsed stack reduced to its cover,
 * in the cover's place), and for each stack's representative its total count and whether it is
 * open. Only members present in the input count, so a filter that hides frames shrinks the badge,
 * and a stack whose cover is filtered away is represented by its first visible frame.
 */
data class StackedView<T, K>(
    val visible: List<T>,
    val badges: Map<K, StackBadge<K>>,
)

/** The badge on a stack's representative tile. */
data class StackBadge<K>(val representative: K, val count: Int, val expanded: Boolean, val members: List<K>)

object BurstStacks {
    fun <T, K> present(
        items: List<T>,
        keyOf: (T) -> K,
        stacks: List<BurstStack<K>>,
        expanded: Set<K>,
    ): StackedView<T, K> {
        if (stacks.isEmpty() || items.isEmpty()) return StackedView(items, emptyMap())
        val present = HashSet<K>(items.size * 2)
        for (item in items) present += keyOf(item)
        val badges = HashMap<K, StackBadge<K>>()
        val hidden = HashSet<K>()
        for (stack in stacks) {
            val shown = stack.members.filter { it in present }
            if (shown.size < 2) continue
            val rep = if (stack.cover in present) stack.cover else shown.first()
            val open = rep in expanded
            badges[rep] = StackBadge(rep, shown.size, open, shown)
            if (!open) for (m in shown) if (m != rep) hidden += m
        }
        if (badges.isEmpty()) return StackedView(items, emptyMap())
        val visible = if (hidden.isEmpty()) items else items.filter { keyOf(it) !in hidden }
        return StackedView(visible, badges)
    }
}
