package com.fotoxplorr.app.textselect

import com.fotoxplorr.app.recognition.TextBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Recognised text as geometry: blocks of lines of words, each with the quad the recogniser put
 * around it. This is the half of text selection that needs no screen. It is built from plain
 * values (never from an ML Kit type, so the one adapter that touches ML Kit stays the only place
 * that does) and answers "which word is here" and "which words does this stroke touch" for the
 * selection model in TextSelection.kt.
 *
 * Reading order is the recogniser's: blocks, lines in blocks, words in lines, exactly as delivered,
 * never re-sorted by position. Re-sorting by y then x is what breaks columns, tilted text and
 * right-to-left lines, and the recogniser has already looked at the whole picture.
 *
 * Direction caveats, so nobody discovers them on a device. The bundled recogniser reads Latin
 * script only, so right-to-left and vertical text are not produced today; the model is still
 * direction-agnostic (corner points, a per-line angle, a per-line right-to-left flag) so a script
 * artifact added later does not need a rewrite. Text assembly trusts the order words arrive in and
 * assumes it is LOGICAL (the order the line is read in). A recogniser that handed a right-to-left
 * line over in visual order must be reversed by its adapter, which has the flag to do it with. A
 * vertical line (angle near a quarter-turn) hit-tests correctly, because hit-testing only uses
 * quads, but whether its columns arrive right-to-left or left-to-right is the recogniser's call.
 */

/** A word as handed over by an adapter: text, corners normalised to 0..1 of the frame, language tag. */
class RawWord(val text: String, val quad: Quad, val language: String? = null)

/** A line as handed over: its words, an optional own quad, and an optional right-to-left override. */
class RawLine(
    val words: List<RawWord>,
    val quad: Quad? = null,
    val language: String? = null,
    val rtl: Boolean? = null,
)

/** A block as handed over: its lines and an optional own quad. */
class RawBlock(val lines: List<RawLine>, val quad: Quad? = null, val language: String? = null)

/** One word. [line] and [block] index [TextLayout.lines] and [TextLayout.blocks]; [quad] is in frame pixels. */
class Word(val text: String, val quad: Quad, val line: Int, val block: Int, val language: String?)

/** A run of words that reads as one line: [wordCount] words from [firstWord]. */
class Line(
    val firstWord: Int,
    val wordCount: Int,
    val quad: Quad,
    val block: Int,
    val rtl: Boolean,
    /** The direction the line runs in, radians (see [Quad.angle]). */
    val angle: Float,
    val language: String?,
) {
    val words: IntRange get() = firstWord until firstWord + wordCount
}

/** A paragraph-ish group of lines: [lineCount] lines from [firstLine], [wordCount] words from [firstWord]. */
class Block(
    val firstLine: Int,
    val lineCount: Int,
    val firstWord: Int,
    val wordCount: Int,
    val quad: Quad,
    val language: String?,
) {
    val lines: IntRange get() = firstLine until firstLine + lineCount
    val words: IntRange get() = firstWord until firstWord + wordCount
}

/**
 * The layout of one photo's text, in FRAME PIXELS of a [frameWidth] x [frameHeight] frame (see
 * [Pt]). Immutable; flat lists so an index is a stable name for a word, line or block for as long
 * as the layout lives. A selection is a set of word indices into ONE layout; when a better layout
 * replaces it (the block-only one from the stored index giving way to a word-level one) the
 * selection has to be carried over with [TextSelection.carriedOver], never reused as is.
 *
 * @property wordLevel false for a layout made from stored whole-block boxes, where each "word" is a
 *   whole block of text. Tap-a-block works on it at once; strokes select whole blocks.
 */
class TextLayout internal constructor(
    val frameWidth: Float,
    val frameHeight: Float,
    val words: List<Word>,
    val lines: List<Line>,
    val blocks: List<Block>,
    val wordLevel: Boolean,
) {
    val isEmpty: Boolean get() = words.isEmpty()

    /**
     * The word under [p], widened by [slop] frame pixels: a point inside a word wins outright, and
     * where two quads overlap the LATER one wins (so a small box over a big one stays reachable);
     * otherwise the nearest word within [slop]; otherwise null.
     */
    fun wordAt(p: Pt, slop: Float = 0f): Int? = pick(words.size, { words[it].quad }, p, slop)

    fun lineAt(p: Pt, slop: Float = 0f): Int? = pick(lines.size, { lines[it].quad }, p, slop)

    fun blockAt(p: Pt, slop: Float = 0f): Int? = pick(blocks.size, { blocks[it].quad }, p, slop)

    /** The word nearest [p] however far away, or null for an empty layout. */
    fun nearestWord(p: Pt): Int? = pick(words.size, { words[it].quad }, p, Float.POSITIVE_INFINITY)

    /**
     * Every word a finger of [radius] frame pixels touches while drawing [path], ascending.
     *
     * One point is a press: the words within [radius] of it. Otherwise the path is thinned (see
     * [TextGeometry.decimate]) and each leg is tested against each word's real quad, so a stroke
     * across the middle of a line gets that line's words, a diagonal across two lines gets words
     * on both and none of the gap, and a stroke along the space between two lines gets nothing as
     * long as [radius] is smaller than half that space.
     */
    fun wordsTouchedBy(path: List<Pt>, radius: Float): List<Int> {
        if (path.isEmpty() || words.isEmpty()) return emptyList()
        val r = max(radius, 0f)
        val result = ArrayList<Int>()
        if (path.size == 1) {
            val p = path[0]
            for (i in words.indices) if (words[i].quad.distanceTo(p) <= r) result.add(i)
            return result
        }
        val thin = TextGeometry.decimate(path, max(r / 2f, MIN_STEP))
        for (i in words.indices) {
            val quad = words[i].quad
            for (k in 0 until thin.size - 1) {
                if (quad.touchedBySegment(thin[k], thin[k + 1], r)) {
                    result.add(i)
                    break
                }
            }
        }
        return result
    }

    /**
     * Every word a closed loop encloses or its outline touches, ascending: words whose centre is
     * inside [polygon] (closed last-to-first) together with those the outline passes within
     * [radius] of, so a loop that clips a word's corner still takes the word.
     */
    fun wordsInLasso(polygon: List<Pt>, radius: Float): List<Int> {
        if (polygon.size < 3 || words.isEmpty()) return wordsTouchedBy(polygon, radius)
        val inside = HashSet<Int>()
        for (i in words.indices) if (TextGeometry.polygonContains(polygon, words[i].quad.centre)) inside.add(i)
        val touched = wordsTouchedBy(polygon + polygon[0], radius)
        return (inside + touched).sorted()
    }

    private inline fun pick(count: Int, quadOf: (Int) -> Quad, p: Pt, slop: Float): Int? {
        var best = -1
        var bestDistance = Float.POSITIVE_INFINITY
        for (i in 0 until count) {
            val quad = quadOf(i)
            if (slop.isFinite() && (p.x < quad.left - slop || p.x > quad.right + slop || p.y < quad.top - slop || p.y > quad.bottom + slop)) continue
            val d = quad.distanceTo(p)
            if (d > slop) continue
            // <= so that among equals (overlapping boxes, all at distance 0) the later one wins.
            if (d <= bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return if (best >= 0) best else null
    }

    companion object {
        private const val MIN_STEP = 0.5f

        fun empty(frameWidth: Int, frameHeight: Int): TextLayout =
            TextLayout(safe(frameWidth), safe(frameHeight), emptyList(), emptyList(), emptyList(), wordLevel = true)

        /**
         * Build a word-level layout from [blocks] whose corners are normalised to 0..1 of the
         * frame the recogniser ran on ([frameWidth] x [frameHeight] pixels of THAT bitmap, the
         * upright decode, never MediaStore's stored size; TRAPS 48).
         *
         * Blank words, lines with no words, blocks with no lines and anything with a non-finite
         * corner are dropped; words keep the order they came in. Coordinates are not clamped: a
         * tilted line at the edge of the picture can honestly have a corner just outside it.
         */
        fun build(blocks: List<RawBlock>, frameWidth: Int, frameHeight: Int): TextLayout =
            assemble(blocks, frameWidth, frameHeight, wordLevel = true)

        /**
         * A block-only layout from the whole-block boxes the recognition index stores: each block
         * becomes one line holding ONE word that is the block's whole text, so a tap selects the
         * block and copies its text exactly. Available before any word-level pass has run.
         */
        fun fromLegacyBlocks(blocks: List<TextBlock>, frameWidth: Int, frameHeight: Int): TextLayout =
            assemble(
                blocks.map { b ->
                    val quad = Quad.fromBounds(min(b.left, b.right), min(b.top, b.bottom), max(b.left, b.right), max(b.top, b.bottom))
                    RawBlock(listOf(RawLine(listOf(RawWord(b.text, quad)))))
                },
                frameWidth, frameHeight, wordLevel = false,
            )

        private fun safe(size: Int): Float = if (size > 0) size.toFloat() else 1f

        private fun assemble(raw: List<RawBlock>, frameWidth: Int, frameHeight: Int, wordLevel: Boolean): TextLayout {
            val fw = safe(frameWidth)
            val fh = safe(frameHeight)
            val words = ArrayList<Word>()
            val lines = ArrayList<Line>()
            val blocks = ArrayList<Block>()
            for (rawBlock in raw) {
                val blockIndex = blocks.size
                val firstLine = lines.size
                val firstWordOfBlock = words.size
                val blockLanguage = tag(rawBlock.language)
                for (rawLine in rawBlock.lines) {
                    val lineIndex = lines.size
                    val firstWord = words.size
                    val lineLanguage = tag(rawLine.language) ?: blockLanguage
                    for (rawWord in rawLine.words) {
                        val text = rawWord.text.trim()
                        if (text.isEmpty() || !rawWord.quad.isFinite) continue
                        words.add(Word(text, rawWord.quad.scaled(fw, fh), lineIndex, blockIndex, tag(rawWord.language) ?: lineLanguage))
                    }
                    val count = words.size - firstWord
                    if (count == 0) continue
                    val own = rawLine.quad?.takeIf { it.isFinite }?.scaled(fw, fh)
                    val quad = own ?: Quad.orientedHull(words.subList(firstWord, words.size).flatMap { it.quad.corners }, words[firstWord].quad.angle)
                    val rtl = rawLine.rtl ?: TextDirection.isRtl(words.subList(firstWord, words.size).joinToString(" ") { it.text })
                    lines.add(Line(firstWord, count, quad, blockIndex, rtl, quad.angle, lineLanguage))
                }
                val lineCount = lines.size - firstLine
                if (lineCount == 0) continue
                val own = rawBlock.quad?.takeIf { it.isFinite }?.scaled(fw, fh)
                val quad = own ?: Quad.orientedHull(
                    words.subList(firstWordOfBlock, words.size).flatMap { it.quad.corners },
                    lines[firstLine].angle,
                )
                blocks.add(Block(firstLine, lineCount, firstWordOfBlock, words.size - firstWordOfBlock, quad, blockLanguage))
            }
            return TextLayout(fw, fh, words, lines, blocks, wordLevel)
        }

        /** A language tag, or null when blank or the recogniser's "undetermined". */
        private fun tag(language: String?): String? =
            language?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
    }
}

/** Which way a run of text reads. */
object TextDirection {
    /**
     * True when the first strongly-directional character of [text] is right-to-left (Hebrew,
     * Arabic and kin), the Unicode default for deciding a paragraph's direction. Digits and
     * punctuation are neutral, so "9 שלום" is right-to-left and "Hello שלום" is not.
     */
    fun isRtl(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                else -> Unit
            }
            i += Character.charCount(cp)
        }
        return false
    }
}
