package com.fotoxplorr.app.reel

/**
 * The shape of the picture a reel is made in. [ratio] is width / height, or `null` for [ORIGINAL],
 * which means "each picture keeps its own shape" (no crop, no motion that needs a crop).
 *
 * [code] is what is saved; never rename one.
 */
enum class ReelAspect(val code: String, val ratio: Float?) {
    WIDE("16:9", 16f / 9f),
    TALL("9:16", 9f / 16f),
    SQUARE("1:1", 1f),
    ORIGINAL("original", null),
    ;

    companion object {
        fun fromCode(code: String?): ReelAspect = entries.firstOrNull { it.code == code } ?: WIDE
    }
}

/** How one item hands over to the next. [code] is what is saved; never rename one. */
enum class ReelTransition(val code: String) {
    CUT("cut"),
    CROSS_FADE("fade"),
    SLIDE("slide"),
    ;

    /** Whether this hand-over lays the two items over each other for a moment (so it shortens the reel). */
    val overlaps: Boolean get() = this != CUT

    companion object {
        fun fromCode(code: String?): ReelTransition = entries.firstOrNull { it.code == code } ?: CUT
    }
}

/**
 * One item of a reel, in the order it plays. Every item has a project-unique [id] (so an editor can
 * keep a selection while items move), a [transition] INTO THE NEXT item (the last item's is ignored),
 * and a [durationMs] on screen.
 *
 * A picture or video is named by its library [mediaId] (the same id the panorama draft keeps), not
 * by a copy of the file: a reel is a recipe, and rendering one re-checks that the photo may still be
 * shown and exported (a protected, locked or deleted one is not drawn, see TRAPS #33).
 */
sealed interface ReelItem {
    val id: String
    val durationMs: Long
    val transition: ReelTransition

    /** The same item with another [transition] into the next. */
    fun withTransition(transition: ReelTransition): ReelItem

    /** The same item with a new id; used when an editor duplicates one. */
    fun withId(id: String): ReelItem
}

/** A still picture shown for [durationMs], with [motion] across it. */
data class StillItem(
    override val id: String,
    val mediaId: Long,
    override val durationMs: Long = ReelLimits.STILL_DEFAULT_MS,
    val motion: ReelMotion = ReelMotion.None,
    override val transition: ReelTransition = ReelTransition.CUT,
) : ReelItem {
    override fun withTransition(transition: ReelTransition): StillItem = copy(transition = transition)
    override fun withId(id: String): StillItem = copy(id = id)
}

/**
 * A part of a video, from [inMs] to [outMs] of the video. Its time on screen is that part, so it is
 * worked out and not stored twice. [sourceDurationMs] is the whole video's length as it was when the
 * clip was added, kept so the plan can be checked without the library (0 = not known).
 */
data class ClipItem(
    override val id: String,
    val mediaId: Long,
    val inMs: Long,
    val outMs: Long,
    val sourceDurationMs: Long = 0L,
    override val transition: ReelTransition = ReelTransition.CUT,
) : ReelItem {
    override val durationMs: Long get() = (outMs - inMs).coerceAtLeast(0L)
    override fun withTransition(transition: ReelTransition): ClipItem = copy(transition = transition)
    override fun withId(id: String): ClipItem = copy(id = id)
}

/**
 * A plain card with [text] (lines split by `\n`) on a [backgroundArgb] colour (packed ARGB, as
 * `Color.toArgb()` gives).
 */
data class TitleItem(
    override val id: String,
    val text: String,
    val backgroundArgb: Int = ReelLimits.TITLE_DEFAULT_ARGB,
    override val durationMs: Long = ReelLimits.TITLE_DEFAULT_MS,
    override val transition: ReelTransition = ReelTransition.CUT,
) : ReelItem {
    override fun withTransition(transition: ReelTransition): TitleItem = copy(transition = transition)
    override fun withId(id: String): TitleItem = copy(id = id)
}

/**
 * A sound file the person picked, laid under the whole reel. Only the reference is kept: [uri] is a
 * document-picker URI string, [trimStartMs] is where in the file the music starts, and [fadeOutMs]
 * is how long the volume falls at the end of the reel. Nothing is played back in the app; the
 * renderer lays the file into the saved video.
 */
data class ReelSoundtrack(
    val uri: String,
    val trimStartMs: Long = 0L,
    val fadeOutMs: Long = ReelLimits.FADE_OUT_DEFAULT_MS,
)

/**
 * A reel as a recipe: the ordered [items], the picture [aspect], and an optional [soundtrack].
 * Immutable; every edit returns a new project, so undo is keeping the old one.
 *
 * ## What is saved
 * All of it, as one JSON string ([ReelCodec]); the pure part holds no pictures. Nothing here has
 * been rendered on a device.
 *
 * ## Time
 * An item that hands over with a cross-fade or a slide overlaps the next for [overlapAfterMs]
 * (never more than half of either item), so the reel is SHORTER than the sum of its items:
 * [totalDurationMs] is the sum minus those overlaps, and [timeline] gives each item's start. A cut
 * overlaps nothing. The last item's transition is ignored.
 */
data class ReelProject(
    val items: List<ReelItem> = emptyList(),
    val aspect: ReelAspect = ReelAspect.WIDE,
    val soundtrack: ReelSoundtrack? = null,
    /** What the person called it; may be empty. */
    val name: String = "",
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * How long items [index] and [index] + 1 lie over each other. 0 for a cut, for the last item, and
     * for an index out of range.
     */
    fun overlapAfterMs(index: Int): Long {
        if (index < 0 || index >= items.lastIndex) return 0L
        val a = items[index]
        val b = items[index + 1]
        if (!a.transition.overlaps) return 0L
        val half = minOf(a.durationMs, b.durationMs) / 2
        return minOf(ReelLimits.TRANSITION_MS, half).coerceAtLeast(0L)
    }

    /** The length of the whole reel in milliseconds (0 for no items). */
    val totalDurationMs: Long
        get() {
            var total = 0L
            for (i in items.indices) total += items[i].durationMs - overlapAfterMs(i)
            return total.coerceAtLeast(0L)
        }

    /** Where each item starts and ends on the reel's clock. */
    fun timeline(): List<ReelSlot> {
        val out = ArrayList<ReelSlot>(items.size)
        var start = 0L
        for (i in items.indices) {
            val end = start + items[i].durationMs
            out += ReelSlot(index = i, startMs = start, endMs = end, overlapIntoNextMs = overlapAfterMs(i))
            start = end - overlapAfterMs(i)
        }
        return out
    }

    /** The item at the reel time [timeMs], or -1 for none; during an overlap the LATER item. */
    fun indexAt(timeMs: Long): Int {
        if (timeMs < 0L) return -1
        var found = -1
        for (slot in timeline()) {
            if (timeMs >= slot.startMs && timeMs < slot.endMs) found = slot.index
        }
        return found
    }

    // ---- editing; every function returns a new project and leaves an out-of-range request alone ----

    /** An id no item has, for a new item. */
    fun freshId(): String {
        var highest = 0
        for (item in items) {
            if (!item.id.startsWith("r")) continue
            val n = item.id.substring(1).toIntOrNull() ?: continue
            if (n > highest) highest = n
        }
        return "r${highest + 1}"
    }

    fun inserted(index: Int, item: ReelItem): ReelProject {
        val at = index.coerceIn(0, items.size)
        return copy(items = items.toMutableList().apply { add(at, item) })
    }

    fun appended(item: ReelItem): ReelProject = inserted(items.size, item)

    fun removed(index: Int): ReelProject =
        if (index in items.indices) copy(items = items.toMutableList().apply { removeAt(index) }) else this

    fun replaced(index: Int, item: ReelItem): ReelProject =
        if (index in items.indices) copy(items = items.toMutableList().apply { set(index, item) }) else this

    /** The item at [from] moved so it sits at [to]. */
    fun moved(from: Int, to: Int): ReelProject {
        if (from !in items.indices || to !in items.indices || from == to) return this
        val next = items.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(items = next)
    }

    /** The item at [index] copied right after itself under a fresh id. */
    fun duplicated(index: Int): ReelProject {
        val item = items.getOrNull(index) ?: return this
        return inserted(index + 1, item.withId(freshId()))
    }

    /** Every hand-over set to [transition]. */
    fun withTransitions(transition: ReelTransition): ReelProject =
        copy(items = items.map { it.withTransition(transition) })
}

/** One item's place on the reel's clock, see [ReelProject.timeline]. */
data class ReelSlot(val index: Int, val startMs: Long, val endMs: Long, val overlapIntoNextMs: Long)

/** The numbers every part of the reel maker agrees on. */
object ReelLimits {
    const val MIN_ITEM_MS = 500L
    const val STILL_MIN_MS = 2_500L
    const val STILL_DEFAULT_MS = 3_000L
    const val STILL_MAX_MS = 3_500L

    /** The longest the auto-draft lets a clip run; the person can trim a clip longer by hand. */
    const val CLIP_MAX_MS = 4_000L
    const val CLIP_MIN_MS = 1_500L

    const val TITLE_DEFAULT_MS = 2_500L
    const val TITLE_DEFAULT_ARGB: Int = 0xFF1B2330.toInt()

    /** How long a cross-fade or slide lasts, before it is limited to half of either neighbour. */
    const val TRANSITION_MS = 500L

    const val FADE_OUT_DEFAULT_MS = 2_000L
    const val FADE_OUT_MAX_MS = 30_000L

    /** An item the editor may be given on purpose is still sane: no still, card or clip beyond this. */
    const val ITEM_MAX_MS = 10L * 60_000L

    /** The most items a project holds; a reel of more is a film. */
    const val MAX_ITEMS = 300

    /** The longest a whole reel may be. */
    const val MAX_TOTAL_MS = 15L * 60_000L

    const val TEXT_MAX_CHARS = 200
}
