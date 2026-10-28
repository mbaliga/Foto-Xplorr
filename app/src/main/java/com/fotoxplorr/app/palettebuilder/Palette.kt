package com.fotoxplorr.app.palettebuilder

/** One swatch of a [Palette]: an opaque colour and whether regenerating leaves it alone. */
data class PaletteColor(val argb: Int, val locked: Boolean = false) {
    init {
        require((argb ushr 24) == 0xFF) { "palette colours are opaque" }
    }

    val hex: String get() = ColorMath.hex(argb)
}

/**
 * A named palette of [MIN_COLORS]..[MAX_COLORS] swatches.
 *
 * Immutable: every edit returns a new value, or null when the edit would break the 2..12 rule, so the
 * screen on top can keep the last value in a `rememberSaveable` through the text codec and never holds
 * a half-edited palette. The colours are always opaque (a swatch that is "half there" has no hex).
 *
 * A lock protects a swatch from [Harmony.regenerate] only. The person can still change a locked colour
 * by hand ([withColor]) or delete it ([removed]), because a lock that blocked direct edits would be a
 * trap, not a protection.
 */
data class Palette(val name: String, val colors: List<PaletteColor>) {
    init {
        require(colors.size in MIN_COLORS..MAX_COLORS) { "a palette holds $MIN_COLORS..$MAX_COLORS colours, got ${colors.size}" }
        require(name == cleanName(name)) { "use Palette.of for a name that is not already clean" }
    }

    val size: Int get() = colors.size
    val argbs: List<Int> get() = colors.map { it.argb }
    val canAdd: Boolean get() = colors.size < MAX_COLORS
    val canRemove: Boolean get() = colors.size > MIN_COLORS

    fun renamed(newName: String): Palette = copy(name = cleanName(newName))

    /** Replaces colour [index] (alpha is dropped), keeping its lock; this palette when [index] is out of range. */
    fun withColor(index: Int, argb: Int): Palette =
        if (index !in colors.indices) this else copy(colors = colors.toMutableList().also { it[index] = it[index].copy(argb = ColorMath.opaque(argb)) })

    fun withLock(index: Int, locked: Boolean): Palette =
        if (index !in colors.indices) this else copy(colors = colors.toMutableList().also { it[index] = it[index].copy(locked = locked) })

    fun toggledLock(index: Int): Palette = if (index !in colors.indices) this else withLock(index, !colors[index].locked)

    /** One more swatch at the end, or null when the palette already holds [MAX_COLORS]. */
    fun added(argb: Int): Palette? =
        if (!canAdd) null else copy(colors = colors + PaletteColor(ColorMath.opaque(argb)))

    /** Without swatch [index], or null when that would leave fewer than [MIN_COLORS] or [index] is out of range. */
    fun removed(index: Int): Palette? =
        if (!canRemove || index !in colors.indices) null else copy(colors = colors.filterIndexed { i, _ -> i != index })

    /** Swatch [from] moved to position [to] (the non-drag alternative to a drag reorder); this when either is out of range. */
    fun moved(from: Int, to: Int): Palette {
        if (from !in colors.indices || to !in colors.indices || from == to) return this
        val list = colors.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(colors = list)
    }

    companion object {
        const val MIN_COLORS = 2
        const val MAX_COLORS = 12
        const val MAX_NAME_LENGTH = 40

        /**
         * A name with control characters and runs of white space folded to single spaces, trimmed, cut to
         * [MAX_NAME_LENGTH] (never between the halves of a surrogate pair) and, when nothing is left,
         * the default name. The result is always one line, so it is safe in every export and in the codec.
         */
        fun cleanName(raw: String): String {
            val folded = buildString {
                var pendingSpace = false
                for (ch in raw) {
                    if (ch.isWhitespace() || ch.isISOControl()) {
                        pendingSpace = true
                    } else {
                        if (pendingSpace && isNotEmpty()) append(' ')
                        pendingSpace = false
                        append(ch)
                    }
                }
            }
            var cut = folded.take(MAX_NAME_LENGTH)
            if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
            return cut.trimEnd().ifEmpty { PaletteBuilderCopy.DEFAULT_NAME }
        }

        /** A palette from plain colours (alpha dropped, none locked); null when the count is outside 2..12. */
        fun of(name: String, argbs: List<Int>): Palette? =
            if (argbs.size !in MIN_COLORS..MAX_COLORS) null
            else Palette(cleanName(name), argbs.map { PaletteColor(ColorMath.opaque(it)) })
    }
}
