package com.fotoxplorr.app.editor

/**
 * How much each tool has been used lately: a small, bounded, decaying counter per tool id, which is
 * what lets the hub's centre adapt to the person.
 *
 * Immutable; [record] returns a new value. Pure Kotlin and all-integer, so the same sequence of
 * uses gives the same scores on every device and a score survives [encode] and [decode] exactly.
 *
 * How it counts:
 *  - A score is in thousandths of a use (1_000 = one recent use).
 *  - Recording a use first decays EVERY score by [DECAY_NUM]/[DECAY_DEN] (3 percent), then adds one
 *    use to that tool. So the decay is per use, not per day: a person who stops editing for a
 *    month comes back to the same centre. Roughly 23 uses of other tools halve a score.
 *  - A score under [FLOOR_MILLI] is forgotten, and an id that is not a catalogue tool is never
 *    kept, so the table can never outgrow the catalogue. One score never passes [MAX_MILLI] (the
 *    limit of the 3 percent decay is about 33 uses).
 *
 * What counts as "a use" is the caller's call. The recommendation: once per opening of a tool, at
 * its first actual change (a slider that moved, a crop that was dragged, a button that was
 * tapped), not on open alone, so browsing the hub teaches it nothing.
 */
class ToolUsage private constructor(private val milli: Map<String, Int>) {

    val isEmpty: Boolean get() = milli.isEmpty()

    /** Ids with a remembered score, sorted, for tests and the codec. */
    val ids: List<String> get() = milli.keys.sorted()

    /** The learned score of [id] in thousandths of a use; 0 for a tool never used or long forgotten. */
    fun score(id: String): Int = milli[id] ?: 0

    /** A copy with one more use of [id]. An id that is not a catalogue tool is ignored. */
    fun record(id: String): ToolUsage {
        if (!ToolCatalog.has(id)) return this
        val next = HashMap<String, Int>(milli.size + 1)
        for ((key, value) in milli) {
            val decayed = (value.toLong() * DECAY_NUM / DECAY_DEN).toInt()
            if (decayed >= FLOOR_MILLI) next[key] = decayed
        }
        next[id] = ((next[id] ?: 0) + ONE_USE).coerceAtMost(MAX_MILLI)
        return ToolUsage(next)
    }

    /**
     * Plain text for a SharedPreferences string: `tu1;exposure=1450;crop=970`, ids sorted so the
     * same value always writes the same text. Never contains a newline.
     */
    fun encode(): String = buildString {
        append(HEADER)
        for (id in ids) append(';').append(id).append('=').append(milli.getValue(id))
    }

    override fun equals(other: Any?): Boolean = other is ToolUsage && other.milli == milli

    override fun hashCode(): Int = milli.hashCode()

    override fun toString(): String = encode()

    companion object {
        val EMPTY = ToolUsage(emptyMap())

        const val ONE_USE = 1_000
        const val DECAY_NUM = 97
        const val DECAY_DEN = 100
        const val FLOOR_MILLI = 50
        const val MAX_MILLI = 40_000

        /** Longest text [decode] will look at; anything longer is not something [encode] wrote. */
        const val MAX_ENCODED_CHARS = 2_048

        private const val HEADER = "tu1"
        private val ID_PATTERN = Regex("[a-z][a-z0-9_]{0,31}")

        /**
         * The inverse of [encode], for text that may be missing, truncated, hand-edited or from some
         * other version. It never throws: text it cannot read as a whole (no header, too long)
         * gives [EMPTY], and a single bad entry (no `=`, a bad id, a non-number, a score under the
         * floor, an id that is not a catalogue tool) is skipped while its neighbours are kept. A
         * repeated id keeps its first score; a score over [MAX_MILLI] is clamped.
         */
        fun decode(text: String?): ToolUsage {
            if (text.isNullOrEmpty() || text.length > MAX_ENCODED_CHARS) return EMPTY
            val parts = text.split(';')
            if (parts.first() != HEADER) return EMPTY
            val found = HashMap<String, Int>()
            for (part in parts.drop(1)) {
                val pair = part.split('=')
                if (pair.size != 2) continue
                val id = pair[0]
                if (!ID_PATTERN.matches(id) || !ToolCatalog.has(id) || id in found) continue
                val value = pair[1].toIntOrNull() ?: continue
                if (value < FLOOR_MILLI) continue
                found[id] = value.coerceAtMost(MAX_MILLI)
            }
            return ToolUsage(found)
        }
    }
}
