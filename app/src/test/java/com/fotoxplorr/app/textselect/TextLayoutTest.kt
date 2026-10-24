package com.fotoxplorr.app.textselect

import com.fotoxplorr.app.recognition.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutTest {

    // ---- building -------------------------------------------------------------------------

    @Test
    fun `the page builds into flat arrays with consistent indices`() {
        val layout = page()

        assertEquals(listOf("PLATFORM", "9", "Northern", "line", "Exit", "only", "left"), layout.words.map { it.text })
        assertEquals(3, layout.lines.size)
        assertEquals(2, layout.blocks.size)

        assertEquals(listOf(0, 0, 1, 1, 2, 2, 2), layout.words.map { it.line })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), layout.words.map { it.block })

        assertEquals(0, layout.lines[0].firstWord); assertEquals(2, layout.lines[0].wordCount)
        assertEquals(2, layout.lines[1].firstWord); assertEquals(2, layout.lines[1].wordCount)
        assertEquals(4, layout.lines[2].firstWord); assertEquals(3, layout.lines[2].wordCount)
        assertEquals(0..1, layout.lines[0].words)
        assertEquals(listOf(0, 0, 1), layout.lines.map { it.block })

        assertEquals(0, layout.blocks[0].firstLine); assertEquals(2, layout.blocks[0].lineCount)
        assertEquals(0, layout.blocks[0].firstWord); assertEquals(4, layout.blocks[0].wordCount)
        assertEquals(2, layout.blocks[1].firstLine); assertEquals(1, layout.blocks[1].lineCount)
        assertEquals(4..6, layout.blocks[1].words)
        assertEquals(2..2, layout.blocks[1].lines)
        assertTrue(layout.wordLevel)
    }

    @Test
    fun `corners are scaled by the frame the recogniser ran on, not by the asset`() {
        val layout = TextLayout.build(
            listOf(RawBlock(listOf(RawLine(listOf(RawWord("hi", Quad.fromBounds(0.1f, 0.2f, 0.3f, 0.4f))))))),
            frameWidth = 1000, frameHeight = 500,
        )
        val q = layout.words[0].quad
        assertEquals(100f, q.left, 1e-3f); assertEquals(100f, q.top, 1e-3f)
        assertEquals(300f, q.right, 1e-3f); assertEquals(200f, q.bottom, 1e-3f)
        assertEquals(1000f, layout.frameWidth, 0f)
        assertEquals(500f, layout.frameHeight, 0f)
    }

    @Test
    fun `an unknown frame size falls back to one so nothing divides by zero`() {
        val layout = TextLayout.build(
            listOf(RawBlock(listOf(RawLine(listOf(RawWord("hi", Quad.fromBounds(0.1f, 0.2f, 0.3f, 0.4f))))))),
            frameWidth = 0, frameHeight = -5,
        )
        assertEquals(1f, layout.frameWidth, 0f)
        assertEquals(0.3f, layout.words[0].quad.right, 1e-6f)
        assertTrue(TextLayout.empty(0, 0).isEmpty)
    }

    @Test
    fun `reading order is the recogniser's, never re-sorted by position`() {
        // The lower block arrives first; it must stay first.
        val layout = layoutOf(
            RawBlock(listOf(RawLine(listOf(rawWord("lower", 100f, 400f, 200f, 430f))))),
            RawBlock(listOf(RawLine(listOf(rawWord("upper", 100f, 50f, 200f, 80f))))),
        )
        assertEquals(listOf("lower", "upper"), layout.words.map { it.text })
    }

    @Test
    fun `blank words, empty lines, empty blocks and non finite corners are dropped`() {
        val nan = RawWord("bad", Quad(Pt(Float.NaN, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f)))
        val layout = layoutOf(
            RawBlock(
                listOf(
                    RawLine(listOf(rawWord("  ", 10f, 10f, 20f, 20f), rawWord("", 30f, 10f, 40f, 20f), nan)),
                    RawLine(listOf(rawWord(" kept ", 50f, 10f, 90f, 20f))),
                ),
            ),
            RawBlock(emptyList()),
            RawBlock(listOf(RawLine(emptyList()))),
        )
        assertEquals(listOf("kept"), layout.words.map { it.text })
        assertEquals(1, layout.lines.size)
        assertEquals(1, layout.blocks.size)
        assertEquals(0, layout.words[0].line)
    }

    @Test
    fun `a line quad and a block quad come from their own corners when given, else from their words`() {
        val given = norm(box(90f, 90f, 360f, 140f))
        val withOwn = layoutOf(
            RawBlock(
                listOf(RawLine(listOf(rawWord("a", 100f, 100f, 140f, 130f), rawWord("b", 200f, 100f, 240f, 130f)), quad = given)),
                quad = norm(box(80f, 80f, 400f, 200f)),
            ),
        )
        assertEquals(90f, withOwn.lines[0].quad.left, 1e-3f)
        assertEquals(360f, withOwn.lines[0].quad.right, 1e-3f)
        assertEquals(80f, withOwn.blocks[0].quad.left, 1e-3f)
        assertEquals(200f, withOwn.blocks[0].quad.bottom, 1e-3f)

        val derived = page()
        assertEquals(100f, derived.lines[0].quad.left, 1e-3f)
        assertEquals(350f, derived.lines[0].quad.right, 1e-3f)
        assertEquals(100f, derived.blocks[0].quad.top, 1e-3f)
        assertEquals(180f, derived.blocks[0].quad.bottom, 1e-3f)
    }

    @Test
    fun `a derived line quad follows a tilted line`() {
        val first = turned(300f, 300f, 100f, 30f, 25f)
        val second = turned(420f, 355f, 100f, 30f, 25f)
        val layout = layoutOf(RawBlock(listOf(RawLine(listOf(RawWord("one", norm(first)), RawWord("two", norm(second)))))))
        val line = layout.lines[0]
        assertEquals(Math.toRadians(25.0).toFloat(), line.angle, 1e-2f)
        assertTrue(line.quad.contains(layout.words[0].quad.centre))
        assertTrue(line.quad.contains(layout.words[1].quad.centre))
        // A point in the empty corner of the line's bounding box is not on the line.
        assertFalse(line.quad.contains(Pt(line.quad.right, line.quad.top)))
    }

    @Test
    fun `languages fall back word to line to block and undetermined means unknown`() {
        val layout = layoutOf(
            RawBlock(
                listOf(
                    RawLine(
                        listOf(rawWord("a", 10f, 10f, 20f, 20f, "fr"), rawWord("b", 30f, 10f, 40f, 20f), rawWord("c", 50f, 10f, 60f, 20f, "und")),
                        language = "en",
                    ),
                    RawLine(listOf(rawWord("d", 10f, 30f, 20f, 40f))),
                ),
                language = "de",
            ),
        )
        assertEquals(listOf("fr", "en", "en", "de"), layout.words.map { it.language })
        assertEquals("en", layout.lines[0].language)
        assertEquals("de", layout.blocks[0].language)
        assertNull(layoutOf(RawBlock(listOf(RawLine(listOf(rawWord("x", 1f, 1f, 5f, 5f, "und")))))).words[0].language)
    }

    @Test
    fun `line direction is detected from the first strong character and can be overridden`() {
        assertTrue(TextDirection.isRtl("שלום"))
        assertTrue(TextDirection.isRtl("مرحبا"))
        assertTrue(TextDirection.isRtl("9 שלום"))
        assertFalse(TextDirection.isRtl("Hello שלום"))
        assertFalse(TextDirection.isRtl("12:30 - 45"))
        assertFalse(TextDirection.isRtl(""))

        val detected = layoutOf(RawBlock(listOf(RawLine(listOf(rawWord("שלום", 10f, 10f, 80f, 30f))))))
        assertTrue(detected.lines[0].rtl)
        val forcedOff = layoutOf(RawBlock(listOf(RawLine(listOf(rawWord("שלום", 10f, 10f, 80f, 30f)), rtl = false))))
        assertFalse(forcedOff.lines[0].rtl)
        assertFalse(page().lines[0].rtl)
    }

    @Test
    fun `a legacy layout is one word per stored block and says it is not word level`() {
        val stored = listOf(
            TextBlock("PLATFORM 9\nNorthern line", left = fx(100f), top = fy(100f), right = fx(350f), bottom = fy(180f)),
            TextBlock("   ", left = 0f, top = 0f, right = 0.1f, bottom = 0.1f),
            // A box handed over upside down is put the right way up rather than dropped.
            TextBlock("Exit only", left = fx(780f), top = fy(330f), right = fx(600f), bottom = fy(300f)),
        )
        val layout = TextLayout.fromLegacyBlocks(stored, FRAME_W, FRAME_H)

        assertFalse(layout.wordLevel)
        assertEquals(listOf("PLATFORM 9\nNorthern line", "Exit only"), layout.words.map { it.text })
        assertEquals(2, layout.blocks.size)
        assertEquals(2, layout.lines.size)
        assertEquals(100f, layout.words[0].quad.left, 1e-3f)
        assertEquals(100f, layout.words[0].quad.top, 1e-3f)
        assertEquals(600f, layout.words[1].quad.left, 1e-3f)
        assertEquals(330f, layout.words[1].quad.bottom, 1e-3f)
        assertEquals(780f, layout.words[1].quad.right, 1e-3f)
        // Tap-a-block works on it at once and copies the block's text exactly.
        val tapped = TextSelection.NONE.tapBlock(layout, Pt(200f, 150f), 0f)
        assertEquals("PLATFORM 9\nNorthern line", tapped.text(layout))
    }

    // ---- hit testing ----------------------------------------------------------------------

    @Test
    fun `wordAt finds the word under a point and nothing on bare photo`() {
        val layout = page()
        assertEquals(0, layout.wordAt(Pt(200f, 115f)))
        assertEquals(1, layout.wordAt(Pt(335f, 115f)))
        assertEquals(6, layout.wordAt(Pt(840f, 315f)))
        assertNull(layout.wordAt(Pt(900f, 450f)))
        assertNull(layout.wordAt(Pt(310f, 115f)))
    }

    @Test
    fun `slop reaches into the gap between words and picks the nearer one`() {
        val layout = page()
        // 5 px right of PLATFORM, 15 px left of the 9.
        assertEquals(0, layout.wordAt(Pt(305f, 115f), slop = 10f))
        // Equidistant: the later word wins.
        assertEquals(1, layout.wordAt(Pt(310f, 115f), slop = 10f))
        assertNull(layout.wordAt(Pt(310f, 115f), slop = 9f))
    }

    @Test
    fun `nearestWord answers beyond any slop and null for an empty layout`() {
        val layout = page()
        assertNull(layout.wordAt(Pt(900f, 450f), slop = 10f))
        assertEquals(6, layout.nearestWord(Pt(900f, 450f)))
        assertEquals(0, layout.nearestWord(Pt(0f, 0f)))
        assertNull(TextLayout.empty(FRAME_W, FRAME_H).nearestWord(Pt(1f, 1f)))
        assertNull(TextLayout.empty(FRAME_W, FRAME_H).wordAt(Pt(1f, 1f), 5f))
    }

    @Test
    fun `where boxes overlap the later one wins so a small box stays reachable`() {
        val layout = layoutOf(
            RawBlock(
                listOf(
                    RawLine(listOf(rawWord("whole", 0f, 0f, 400f, 400f))),
                    RawLine(listOf(rawWord("small", 150f, 150f, 250f, 250f))),
                ),
            ),
        )
        assertEquals(1, layout.wordAt(Pt(200f, 200f)))
        assertEquals(0, layout.wordAt(Pt(50f, 50f)))
    }

    @Test
    fun `a point in a tilted line's bounding box corner is not a hit`() {
        val quad = turned(500f, 250f, 240f, 40f, 30f)
        val layout = layoutOf(RawBlock(listOf(RawLine(listOf(RawWord("tilted", norm(quad)))))))
        assertEquals(0, layout.wordAt(quad.centre))
        // Inside the stored rectangle's corner, off the text itself.
        assertNull(layout.wordAt(Pt(quad.right - 1f, quad.top + 1f)))
        assertNull(layout.lineAt(Pt(quad.right - 1f, quad.top + 1f)))
        assertNull(layout.blockAt(Pt(quad.right - 1f, quad.top + 1f)))
        // And it IS found along the text's own slope.
        val along = Pt(quad.centre.x + 80f * 0.866f, quad.centre.y + 80f * 0.5f)
        assertEquals(0, layout.wordAt(along))
    }

    @Test
    fun `block, line and word resolution differ in the gaps`() {
        val layout = page()
        val inGap = Pt(200f, 140f)
        assertNull(layout.wordAt(inGap))
        assertNull(layout.lineAt(inGap))
        assertEquals(0, layout.blockAt(inGap))

        assertEquals(1, layout.lineAt(Pt(150f, 165f)))
        assertEquals(2, layout.lineAt(Pt(850f, 315f)))
        assertEquals(1, layout.blockAt(Pt(850f, 315f)))
        assertNull(layout.blockAt(Pt(500f, 450f)))
        assertEquals(1, layout.blockAt(Pt(500f, 315f), slop = 101f))
    }

    // ---- strokes ----------------------------------------------------------------------------

    @Test
    fun `a stroke across the middle of one line gets exactly that line's words`() {
        assertEquals(listOf(0, 1), page().wordsTouchedBy(listOf(Pt(90f, 115f), Pt(360f, 115f)), radius = 2f))
        assertEquals(listOf(4, 5, 6), page().wordsTouchedBy(listOf(Pt(500f, 315f), Pt(900f, 315f)), radius = 2f))
    }

    @Test
    fun `a stroke selects only the words it touched, not a range`() {
        // A stroke that dips below the line between Exit and left never touches 'only'.
        val path = listOf(Pt(620f, 315f), Pt(640f, 315f), Pt(660f, 360f), Pt(820f, 360f), Pt(840f, 315f), Pt(860f, 315f))
        assertEquals(listOf(4, 6), page().wordsTouchedBy(path, radius = 5f))
        // The straight stroke from Exit to left crosses 'only' on the way, so it gets it.
        assertEquals(listOf(4, 5, 6), page().wordsTouchedBy(listOf(Pt(620f, 315f), Pt(860f, 315f)), radius = 5f))
    }

    @Test
    fun `a diagonal across two lines gets words on both and none in the gap`() {
        val layout = page()
        val touched = layout.wordsTouchedBy(listOf(Pt(150f, 105f), Pt(150f, 175f)), radius = 2f)
        assertEquals(listOf(0, 2), touched)
        assertFalse(1 in touched)
        assertFalse(3 in touched)
    }

    @Test
    fun `a stroke along the gap between two lines gets nothing until the radius reaches them`() {
        val gap = listOf(Pt(90f, 140f), Pt(360f, 140f))
        assertEquals(emptyList<Int>(), page().wordsTouchedBy(gap, radius = 5f))
        assertEquals(emptyList<Int>(), page().wordsTouchedBy(gap, radius = 9.9f))
        assertEquals(listOf(0, 1, 2, 3), page().wordsTouchedBy(gap, radius = 10.1f))
    }

    @Test
    fun `radius widens the result`() {
        val path = listOf(Pt(200f, 115f), Pt(200f, 116f))
        assertEquals(listOf(0), page().wordsTouchedBy(path, radius = 1f))
        // Just below the gap, beside '9' and 'line': a thin finger finds nothing, a fatter one
        // the two beneath and above it, a fatter still PLATFORM's corner too.
        val beside = listOf(Pt(318f, 140f), Pt(318f, 141f))
        assertEquals(emptyList<Int>(), page().wordsTouchedBy(beside, radius = 5f))
        assertEquals(listOf(1, 3), page().wordsTouchedBy(beside, radius = 12f))
        assertEquals(listOf(0, 1, 3), page().wordsTouchedBy(beside, radius = 25f))
        assertTrue(page().wordsTouchedBy(path, radius = 40f).containsAll(listOf(0, 2)))
    }

    @Test
    fun `a single point stroke behaves like a press`() {
        val layout = page()
        assertEquals(listOf(0), layout.wordsTouchedBy(listOf(Pt(150f, 115f)), radius = 0f))
        assertEquals(emptyList<Int>(), layout.wordsTouchedBy(listOf(Pt(500f, 450f)), radius = 5f))
        assertEquals(listOf(0, 1), layout.wordsTouchedBy(listOf(Pt(310f, 115f)), radius = 10f))
        assertEquals(emptyList<Int>(), layout.wordsTouchedBy(emptyList(), radius = 10f))
    }

    @Test
    fun `thinning the path does not change what a stroke selects`() {
        val layout = page()
        val sparse = listOf(Pt(90f, 115f), Pt(360f, 115f), Pt(360f, 165f), Pt(90f, 165f))
        val dense = ArrayList<Pt>()
        for (i in 0..540) dense.add(Pt(90f + i * 0.5f, 115f))
        for (i in 0..100) dense.add(Pt(360f, 115f + i * 0.5f))
        for (i in 0..540) dense.add(Pt(360f - i * 0.5f, 165f))
        assertEquals(layout.wordsTouchedBy(sparse, radius = 6f), layout.wordsTouchedBy(dense, radius = 6f))
        assertEquals(listOf(0, 1, 2, 3), layout.wordsTouchedBy(dense, radius = 6f))
    }

    @Test
    fun `a stroke that misses the page touches nothing`() {
        assertEquals(emptyList<Int>(), page().wordsTouchedBy(listOf(Pt(0f, 0f), Pt(900f, 0f), Pt(900f, 60f)), radius = 5f))
    }

    // ---- lasso ------------------------------------------------------------------------------

    @Test
    fun `a lasso takes the words whose centre is inside`() {
        val loop = listOf(Pt(80f, 90f), Pt(380f, 90f), Pt(380f, 140f), Pt(80f, 140f))
        assertEquals(listOf(0, 1), page().wordsInLasso(loop, radius = 0f))
    }

    @Test
    fun `a lasso also takes words its outline clips`() {
        // Around the 'only' word's left end: its centre is outside, the outline cuts through it.
        val clip = listOf(Pt(690f, 290f), Pt(710f, 290f), Pt(710f, 340f), Pt(690f, 340f))
        assertEquals(listOf(5), page().wordsInLasso(clip, radius = 0f))
    }

    @Test
    fun `lasso reach grows with the finger radius`() {
        val loop = listOf(Pt(80f, 90f), Pt(380f, 90f), Pt(380f, 140f), Pt(80f, 140f))
        assertEquals(listOf(0, 1, 2, 3), page().wordsInLasso(loop, radius = 12f))
    }

    @Test
    fun `a lasso around nothing selects nothing and a degenerate one falls back to a stroke`() {
        val empty = listOf(Pt(450f, 400f), Pt(550f, 400f), Pt(550f, 450f), Pt(450f, 450f))
        assertEquals(emptyList<Int>(), page().wordsInLasso(empty, radius = 3f))
        assertEquals(listOf(0, 1), page().wordsInLasso(listOf(Pt(90f, 115f), Pt(360f, 115f)), radius = 2f))
    }
}
