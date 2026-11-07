package com.fotoxplorr.app.textselect

import kotlin.math.abs

/**
 * Which words of one [TextLayout] are selected, and what that selection says.
 *
 * Immutable, like the lift editor's session: every operation returns the next selection, so it
 * sits in a Compose `mutableStateOf` and "did it change" is a reference or [equals] check. It
 * holds sorted word indices and nothing else; the layout is passed to the operations that need it,
 * so a selection never pins a stale layout alive.
 *
 * The gestures it answers, in the words the person would use:
 *  - tap a block: the block ([tapBlock]); tap the block again, or bare photo, to let go;
 *  - drag one finger: the words the finger touched ([strokeSelect]), and nothing else;
 *  - drag a closed loop: the words inside it and those its outline clips ([lassoSelect]);
 *  - [selectAll] and [clear], which are also the non-gesture way in for assistive technology.
 * Handles and entity detection are deliberately not here.
 */
class TextSelection private constructor(private val sorted: IntArray) {
    val size: Int get() = sorted.size
    val isEmpty: Boolean get() = sorted.isEmpty()

    /** The selected word indices, ascending (which is reading order). */
    val indices: List<Int> get() = sorted.asList()

    operator fun contains(word: Int): Boolean = sorted.binarySearch(word) >= 0

    /** Let go of everything. */
    fun clear(): TextSelection = if (isEmpty) this else NONE

    fun selectWord(layout: TextLayout, word: Int): TextSelection = of(layout, listOf(word))

    fun selectLine(layout: TextLayout, line: Int): TextSelection =
        layout.lines.getOrNull(line)?.let { of(layout, it.words) } ?: this

    fun selectBlock(layout: TextLayout, block: Int): TextSelection =
        layout.blocks.getOrNull(block)?.let { of(layout, it.words) } ?: this

    /** Every word from [a] to [b] inclusive, in either order. Out-of-range ends are clamped. */
    fun selectRange(layout: TextLayout, a: Int, b: Int): TextSelection {
        if (layout.isEmpty) return this
        val last = layout.words.size - 1
        val from = a.coerceIn(0, last)
        val to = b.coerceIn(0, last)
        return of(layout, minOf(from, to)..maxOf(from, to))
    }

    fun selectAll(layout: TextLayout): TextSelection =
        if (layout.isEmpty) this else of(layout, layout.words.indices)

    /** Add [word] if absent, remove it if present. */
    fun toggleWord(layout: TextLayout, word: Int): TextSelection {
        if (word !in layout.words.indices) return this
        return if (word in this) of(layout, indices - word) else of(layout, indices + word)
    }

    fun union(other: TextSelection): TextSelection =
        if (other.isEmpty) this else if (isEmpty) other else TextSelection((indices + other.indices).distinct().sorted().toIntArray())

    /**
     * A tap at [point]: select the block under it ([slop] frame pixels of leeway); tapping the
     * block that already is the whole selection lets go of it; tapping bare photo clears.
     */
    fun tapBlock(layout: TextLayout, point: Pt, slop: Float): TextSelection {
        val block = layout.blockAt(point, slop) ?: return clear()
        val wanted = of(layout, layout.blocks[block].words)
        return if (this == wanted) clear() else wanted
    }

    /**
     * Drag with one finger of [radius] frame pixels along [path]: exactly the words the finger
     * touched, replacing the selection or, with [additive], joining it. Not a reading-order range
     * from the first touched word to the last: skipping a word that was never touched is the
     * point of drawing the selection instead of dragging handles.
     */
    fun strokeSelect(layout: TextLayout, path: List<Pt>, radius: Float, additive: Boolean = false): TextSelection =
        combine(layout, layout.wordsTouchedBy(path, radius), additive)

    /** A closed loop around words: those inside and those the outline touches. */
    fun lassoSelect(layout: TextLayout, polygon: List<Pt>, radius: Float, additive: Boolean = false): TextSelection =
        combine(layout, layout.wordsInLasso(polygon, radius), additive)

    /**
     * One drawn gesture, whichever kind it turned out to be: a closed loop selects as a lasso
     * ([isClosedLasso] with [closeDistance]), anything else as a stroke.
     */
    fun drawSelect(
        layout: TextLayout,
        path: List<Pt>,
        radius: Float,
        closeDistance: Float,
        additive: Boolean = false,
    ): TextSelection =
        if (isClosedLasso(path, closeDistance)) lassoSelect(layout, path, radius, additive)
        else strokeSelect(layout, path, radius, additive)

    /**
     * The selected words as text, in reading order: a space between words on a line (none between
     * two Chinese or Japanese characters, which are not written with spaces), a newline where the
     * line changes, a newline where the block changes. Only selected words appear, so a word
     * skipped between two selected ones leaves a single space, not a hole.
     */
    fun text(layout: TextLayout): String {
        val out = StringBuilder()
        var previous: Word? = null
        for (i in sorted) {
            val word = layout.words.getOrNull(i) ?: continue
            if (previous != null) {
                out.append(
                    when {
                        previous.line != word.line -> "\n"
                        TextAssembly.joinsWithoutSpace(previous.text, word.text) -> ""
                        else -> " "
                    },
                )
            }
            out.append(word.text)
            previous = word
        }
        return out.toString()
    }

    /**
     * The shapes to paint behind the selection: one quad for each unbroken run of selected words
     * on a line (a continuous band, the way a native selection looks) and a new quad after any gap
     * or line change. A lone word keeps its own recogniser quad.
     */
    fun highlightQuads(layout: TextLayout): List<Quad> {
        val result = ArrayList<Quad>()
        var i = 0
        while (i < sorted.size) {
            val first = layout.words.getOrNull(sorted[i])
            if (first == null) { i++; continue }
            var j = i
            while (j + 1 < sorted.size) {
                val next = layout.words.getOrNull(sorted[j + 1]) ?: break
                if (sorted[j + 1] != sorted[j] + 1 || next.line != first.line) break
                j++
            }
            result.add(
                if (i == j) first.quad
                else Quad.orientedHull((i..j).flatMap { layout.words[sorted[it]].quad.corners }, layout.lines[first.line].angle),
            )
            i = j + 1
        }
        return result
    }

    /**
     * The language most of the selected words were recognised as (the earliest wins a tie), or
     * null when none carries one: the source tag a translate or read-aloud hand-off should use.
     */
    fun dominantLanguage(layout: TextLayout): String? {
        val counts = LinkedHashMap<String, Int>()
        for (i in sorted) layout.words.getOrNull(i)?.language?.let { counts.merge(it, 1, Int::plus) }
        return counts.maxByOrNull { it.value }?.key
    }

    /** Whether every word of [layout] is selected (an empty layout is never "all selected"). */
    fun isAll(layout: TextLayout): Boolean = !layout.isEmpty && sorted.size == layout.words.size

    /**
     * This selection expressed in a different layout of the same photo: the words of [newLayout]
     * whose centre lies inside a selected word of [old]. How a tap on a block, made on the
     * block-only layout, survives the word-level layout arriving a moment later.
     *
     * The two layouts need not share a frame size (the block-only one is laid over the photo's
     * full-size upright frame, the word-level one over its 2048 px decode), so the old boxes are
     * first scaled onto the new frame.
     */
    fun carriedOver(old: TextLayout, newLayout: TextLayout): TextSelection {
        if (isEmpty) return this
        if (old === newLayout) return this
        val sx = newLayout.frameWidth / old.frameWidth
        val sy = newLayout.frameHeight / old.frameHeight
        val boxes = sorted.asList().mapNotNull { old.words.getOrNull(it)?.quad?.scaled(sx, sy) }
        val carried = newLayout.words.indices.filter { i ->
            val centre = newLayout.words[i].quad.centre
            boxes.any { it.contains(centre) }
        }
        return of(newLayout, carried)
    }

    private fun combine(layout: TextLayout, touched: List<Int>, additive: Boolean): TextSelection {
        val drawn = of(layout, touched)
        return if (additive) union(drawn) else drawn
    }

    override fun equals(other: Any?): Boolean = other is TextSelection && sorted.contentEquals(other.sorted)

    override fun hashCode(): Int = sorted.contentHashCode()

    override fun toString(): String = "TextSelection$indices"

    companion object {
        val NONE = TextSelection(IntArray(0))

        /** The selection of exactly [words] (out-of-range and repeated indices dropped). */
        fun of(layout: TextLayout, words: Iterable<Int>): TextSelection {
            val valid = words.filter { it in layout.words.indices }.distinct().sorted()
            return if (valid.isEmpty()) NONE else TextSelection(valid.toIntArray())
        }

        /**
         * Whether [path] is a closed loop meant as a lasso: it ends within [closeDistance] of
         * where it began AND encloses an area of at least [closeDistance] squared. The area test
         * is what keeps a stroke that goes out along a line and comes back from reading as a loop
         * (it encloses nothing), and a press with a little wobble from reading as a tiny one.
         */
        fun isClosedLasso(path: List<Pt>, closeDistance: Float): Boolean {
            if (path.size < 4 || closeDistance <= 0f) return false
            if (TextGeometry.distance(path.first(), path.last()) > closeDistance) return false
            return abs(TextGeometry.signedArea(path)) >= closeDistance * closeDistance
        }
    }
}

/** The small text rules selection assembly rests on. */
object TextAssembly {
    /**
     * Whether two neighbouring words are written without a space between them: both sides are
     * Chinese or Japanese characters (or the punctuation and full-width forms that go with them).
     * Korean is written with spaces and is not included.
     */
    fun joinsWithoutSpace(before: String, after: String): Boolean {
        if (before.isEmpty() || after.isEmpty()) return false
        return isCjk(before.codePointBefore(before.length)) && isCjk(after.codePointAt(0))
    }

    private fun isCjk(codePoint: Int): Boolean {
        val script = Character.UnicodeScript.of(codePoint)
        if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA
        ) {
            return true
        }
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
}
