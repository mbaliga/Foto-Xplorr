package com.fotoxplorr.app.textselect

import com.fotoxplorr.app.recognition.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectionTest {

    private val none = TextSelection.NONE
    private val layout = page()

    // ---- the basic verbs -----------------------------------------------------------------

    @Test
    fun `select word, line and block`() {
        assertEquals(listOf(2), none.selectWord(layout, 2).indices)
        assertEquals(listOf(2, 3), none.selectLine(layout, 1).indices)
        assertEquals(listOf(0, 1, 2, 3), none.selectBlock(layout, 0).indices)
        assertEquals(listOf(4, 5, 6), none.selectBlock(layout, 1).indices)
    }

    @Test
    fun `out of range names select nothing and leave the selection alone`() {
        val held = none.selectWord(layout, 1)
        assertEquals(none, none.selectWord(layout, 99))
        assertEquals(none, none.selectWord(layout, -1))
        assertSame(held, held.selectLine(layout, 17))
        assertSame(held, held.selectBlock(layout, -3))
        assertSame(held, held.toggleWord(layout, 40))
    }

    @Test
    fun `a range runs either way and clamps its ends`() {
        assertEquals(listOf(2, 3, 4), none.selectRange(layout, 2, 4).indices)
        assertEquals(listOf(2, 3, 4), none.selectRange(layout, 4, 2).indices)
        assertEquals(listOf(5, 6), none.selectRange(layout, 5, 99).indices)
        assertEquals(listOf(0, 1), none.selectRange(layout, -9, 1).indices)
        assertEquals(none, none.selectRange(TextLayout.empty(FRAME_W, FRAME_H), 0, 3))
    }

    @Test
    fun `select all and deselect`() {
        val all = none.selectAll(layout)
        assertEquals((0..6).toList(), all.indices)
        assertTrue(all.isAll(layout))
        assertFalse(none.selectWord(layout, 0).isAll(layout))
        assertFalse(none.isAll(layout))

        assertTrue(all.clear().isEmpty)
        assertEquals(none, all.clear())
        assertSame(none, none.clear())

        val empty = TextLayout.empty(FRAME_W, FRAME_H)
        assertTrue(none.selectAll(empty).isEmpty)
        assertFalse(none.isAll(empty))
    }

    @Test
    fun `toggling adds then removes a word`() {
        val one = none.toggleWord(layout, 3)
        assertEquals(listOf(3), one.indices)
        val two = one.toggleWord(layout, 1)
        assertEquals(listOf(1, 3), two.indices)
        assertEquals(listOf(1), two.toggleWord(layout, 3).indices)
        assertTrue(2 !in two)
        assertTrue(1 in two)
    }

    @Test
    fun `union merges sorted and without repeats, and selections compare by content`() {
        val a = none.selectRange(layout, 0, 2)
        val b = none.selectRange(layout, 2, 4)
        assertEquals((0..4).toList(), a.union(b).indices)
        assertSame(a, a.union(none))
        assertSame(b, none.union(b))
        assertEquals(none.selectRange(layout, 0, 2), a)
        assertEquals(none.selectRange(layout, 0, 2).hashCode(), a.hashCode())
        assertFalse(a == b)
    }

    // ---- tap a block ---------------------------------------------------------------------

    @Test
    fun `a tap selects the block under it, even from the gap between its lines`() {
        assertEquals(listOf(0, 1, 2, 3), none.tapBlock(layout, Pt(200f, 140f), slop = 0f).indices)
        assertEquals(listOf(4, 5, 6), none.tapBlock(layout, Pt(840f, 315f), slop = 0f).indices)
    }

    @Test
    fun `tapping another block replaces, the same block again lets go, bare photo clears`() {
        val first = none.tapBlock(layout, Pt(200f, 115f), 0f)
        val second = first.tapBlock(layout, Pt(840f, 315f), 0f)
        assertEquals(listOf(4, 5, 6), second.indices)

        assertTrue(second.tapBlock(layout, Pt(700f, 315f), 0f).isEmpty)
        assertTrue(second.tapBlock(layout, Pt(500f, 450f), 0f).isEmpty)
        // A partly selected block is completed by a tap, not emptied.
        val partial = none.selectWord(layout, 4)
        assertEquals(listOf(4, 5, 6), partial.tapBlock(layout, Pt(840f, 315f), 0f).indices)
    }

    // ---- strokes and lassos ----------------------------------------------------------------

    @Test
    fun `a stroke replaces the selection, or with additive joins it`() {
        val held = none.selectWord(layout, 6)
        val path = listOf(Pt(90f, 115f), Pt(360f, 115f))
        assertEquals(listOf(0, 1), held.strokeSelect(layout, path, radius = 2f).indices)
        assertEquals(listOf(0, 1, 6), held.strokeSelect(layout, path, radius = 2f, additive = true).indices)
        // A stroke that touches nothing replaces with nothing; additive keeps what was there.
        val miss = listOf(Pt(0f, 0f), Pt(50f, 0f))
        assertTrue(held.strokeSelect(layout, miss, radius = 2f).isEmpty)
        assertEquals(held, held.strokeSelect(layout, miss, radius = 2f, additive = true))
    }

    @Test
    fun `a closed loop is a lasso and an open scribble is not`() {
        val loop = listOf(Pt(80f, 90f), Pt(380f, 90f), Pt(380f, 140f), Pt(80f, 140f), Pt(82f, 92f))
        assertTrue(TextSelection.isClosedLasso(loop, closeDistance = 10f))

        val open = listOf(Pt(80f, 90f), Pt(380f, 90f), Pt(380f, 140f), Pt(80f, 140f))
        assertFalse(TextSelection.isClosedLasso(open, closeDistance = 10f))
    }

    @Test
    fun `out and back along a line encloses nothing so it stays a stroke`() {
        val there = listOf(Pt(100f, 115f), Pt(200f, 115f), Pt(300f, 115f), Pt(200f, 115.5f), Pt(101f, 115f))
        assertFalse(TextSelection.isClosedLasso(there, closeDistance = 10f))
        // And a press with a wobble is no loop either.
        val wobble = listOf(Pt(100f, 100f), Pt(102f, 101f), Pt(101f, 103f), Pt(100f, 101f))
        assertFalse(TextSelection.isClosedLasso(wobble, closeDistance = 10f))
        assertFalse(TextSelection.isClosedLasso(listOf(Pt(1f, 1f), Pt(2f, 2f)), 10f))
        assertFalse(TextSelection.isClosedLasso(emptyList(), 10f))
        assertFalse(TextSelection.isClosedLasso(listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(1f, 1f)), closeDistance = 0f))
    }

    @Test
    fun `drawSelect picks lasso or stroke from the shape of the path`() {
        val loop = listOf(Pt(80f, 90f), Pt(380f, 90f), Pt(380f, 140f), Pt(80f, 140f), Pt(82f, 92f))
        // Lassoed: PLATFORM and 9 are inside; the same path read as a stroke would also touch them
        // but would not need the closing edge.
        assertEquals(listOf(0, 1), none.drawSelect(layout, loop, radius = 2f, closeDistance = 10f).indices)

        val line = listOf(Pt(620f, 315f), Pt(860f, 315f))
        assertEquals(listOf(4, 5, 6), none.drawSelect(layout, line, radius = 2f, closeDistance = 10f).indices)
    }

    @Test
    fun `a lasso selects words inside and clipped, and can be additive`() {
        val clip = listOf(Pt(690f, 290f), Pt(710f, 290f), Pt(710f, 340f), Pt(690f, 340f))
        assertEquals(listOf(5), none.lassoSelect(layout, clip, 0f).indices)
        assertEquals(listOf(0, 5), none.selectWord(layout, 0).lassoSelect(layout, clip, 0f, additive = true).indices)
    }

    // ---- text assembly ---------------------------------------------------------------------

    private val five = layoutOf(RawBlock(listOf(lineOf("one", "two", "three", "four", "five"))))

    @Test
    fun `an empty selection is the empty string`() {
        assertEquals("", none.text(layout))
        assertEquals("", none.text(TextLayout.empty(FRAME_W, FRAME_H)))
    }

    @Test
    fun `part of a line is those words joined by single spaces`() {
        assertEquals("two three", none.selectRange(five, 1, 2).text(five))
        assertEquals("one two three four five", none.selectAll(five).text(five))
    }

    @Test
    fun `words that are not neighbours still join with one space, not a hole`() {
        assertEquals("one three five", none.selectWord(five, 0).toggleWord(five, 2).toggleWord(five, 4).text(five))
    }

    @Test
    fun `lines in a block and blocks on a page are separated by newlines`() {
        assertEquals("PLATFORM 9\nNorthern line", none.selectBlock(layout, 0).text(layout))
        assertEquals("Northern line\nExit only left", none.selectLine(layout, 1).union(none.selectBlock(layout, 1)).text(layout))
        assertEquals("PLATFORM 9\nNorthern line\nExit only left", none.selectAll(layout).text(layout))
        // Reading order, whatever order the selection was made in.
        assertEquals("9\nNorthern", none.selectWord(layout, 2).union(none.selectWord(layout, 1)).text(layout))
    }

    @Test
    fun `a stroke across two lines reads in reading order`() {
        val picked = none.strokeSelect(layout, listOf(Pt(150f, 105f), Pt(150f, 175f)), radius = 2f)
        assertEquals("PLATFORM\nNorthern", picked.text(layout))
    }

    @Test
    fun `chinese and japanese neighbours join without a space but latin and korean do not`() {
        fun joined(vararg words: String): String {
            val l = layoutOf(RawBlock(listOf(lineOf(*words))))
            return none.selectAll(l).text(l)
        }
        assertEquals("日本語", joined("日本", "語"))
        assertEquals("こんにちは世界", joined("こんにちは", "世界"))
        assertEquals("カタカナ", joined("カタ", "カナ"))
        assertEquals("abc 日本", joined("abc", "日本"))
        assertEquals("日本 abc", joined("日本", "abc"))
        assertEquals("안녕 하세요", joined("안녕", "하세요"))
        // Full-width punctuation belongs to the CJK run.
        assertEquals("日本。", joined("日本", "。"))
    }

    @Test
    fun `a right to left line keeps the order the words were delivered in`() {
        val shalom = "שלום"
        val olam = "עולם"
        val l = layoutOf(
            RawBlock(listOf(RawLine(listOf(rawWord(shalom, 500f, 100f, 600f, 130f), rawWord(olam, 380f, 100f, 480f, 130f))))),
        )
        assertTrue(l.lines[0].rtl)
        // Logical order (first word is the one on the right), as delivered.
        assertEquals("$shalom $olam", none.selectAll(l).text(l))
        assertEquals(olam, none.selectWord(l, 1).text(l))
        // A stroke from the right edge across the first word only.
        val stroke = none.strokeSelect(l, listOf(Pt(590f, 115f), Pt(520f, 115f)), radius = 2f)
        assertEquals(shalom, stroke.text(l))
    }

    @Test
    fun `a vertical line is hit and assembled by its quads and its delivery order`() {
        // Two characters running down the picture: quads turned a quarter-turn.
        val top = turned(300f, 200f, 60f, 30f, 90f)
        val below = turned(300f, 270f, 60f, 30f, 90f)
        val l = layoutOf(RawBlock(listOf(RawLine(listOf(RawWord("日本", norm(top)), RawWord("語", norm(below)))))))
        assertEquals(Math.PI.toFloat() / 2f, l.lines[0].angle, 1e-3f)
        assertEquals(1, l.wordAt(Pt(300f, 270f)))
        assertEquals("日本語", none.selectAll(l).text(l))
    }

    @Test
    fun `selected words that fall outside the layout are ignored by text`() {
        val big = none.selectAll(layout)
        val small = layoutOf(RawBlock(listOf(lineOf("only", "two"))))
        assertEquals("only two", big.text(small))
    }

    // ---- highlight ---------------------------------------------------------------------------

    @Test
    fun `an unbroken run on a line is one band`() {
        val quads = none.selectLine(layout, 0).highlightQuads(layout)
        assertEquals(1, quads.size)
        assertEquals(100f, quads[0].left, 1e-3f)
        assertEquals(350f, quads[0].right, 1e-3f)
        assertEquals(100f, quads[0].top, 1e-3f)
        assertEquals(130f, quads[0].bottom, 1e-3f)
    }

    @Test
    fun `a gap or a new line starts a new band`() {
        assertEquals(2, none.selectWord(layout, 4).toggleWord(layout, 6).highlightQuads(layout).size)
        assertEquals(2, none.selectBlock(layout, 0).highlightQuads(layout).size)
        assertEquals(3, none.selectAll(layout).highlightQuads(layout).size)
        assertEquals(emptyList<Quad>(), none.highlightQuads(layout))
    }

    @Test
    fun `a lone word keeps its own quad`() {
        assertEquals(layout.words[5].quad, none.selectWord(layout, 5).highlightQuads(layout).single())
    }

    @Test
    fun `a band across a tilted line is tilted with it`() {
        val a = turned(300f, 300f, 100f, 30f, 20f)
        val b = turned(420f, 344f, 100f, 30f, 20f)
        val tilted = layoutOf(RawBlock(listOf(RawLine(listOf(RawWord("one", norm(a)), RawWord("two", norm(b)))))))
        val band = none.selectAll(tilted).highlightQuads(tilted).single()
        assertEquals(Math.toRadians(20.0).toFloat(), band.angle, 1e-2f)
        assertTrue(band.contains(a.centre))
        assertTrue(band.contains(b.centre))
        for (corner in a.corners + b.corners) assertTrue(band.distanceTo(corner) < 0.05f)
    }

    // ---- language -----------------------------------------------------------------------------

    @Test
    fun `the dominant language is the commonest, earliest winning a tie, and null when unknown`() {
        val l = layoutOf(
            RawBlock(
                listOf(
                    RawLine(
                        listOf(
                            rawWord("a", 10f, 10f, 20f, 20f, "fr"), rawWord("b", 30f, 10f, 40f, 20f, "en"),
                            rawWord("c", 50f, 10f, 60f, 20f, "en"), rawWord("d", 70f, 10f, 80f, 20f),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("en", none.selectAll(l).dominantLanguage(l))
        assertEquals("fr", none.selectRange(l, 0, 1).dominantLanguage(l))
        assertNull(none.selectWord(l, 3).dominantLanguage(l))
        assertNull(none.dominantLanguage(l))
    }

    // ---- carrying a selection to a better layout --------------------------------------------

    @Test
    fun `a block tapped on the block only layout survives the word level layout arriving`() {
        val blockOnly = TextLayout.fromLegacyBlocks(
            listOf(
                TextBlock("PLATFORM 9\nNorthern line", fx(100f), fy(100f), fx(350f), fy(180f)),
                TextBlock("Exit only left", fx(600f), fy(300f), fx(880f), fy(330f)),
            ),
            FRAME_W, FRAME_H,
        )
        val picked = none.tapBlock(blockOnly, Pt(200f, 140f), 0f)
        assertEquals("PLATFORM 9\nNorthern line", picked.text(blockOnly))

        val carried = picked.carriedOver(blockOnly, layout)
        assertEquals(listOf(0, 1, 2, 3), carried.indices)
        assertEquals("PLATFORM 9\nNorthern line", carried.text(layout))
        assertSame(none, none.carriedOver(blockOnly, layout))
    }

    @Test
    fun `carrying over a selection that matches nothing in the new layout gives nothing`() {
        val elsewhere = layoutOf(RawBlock(listOf(RawLine(listOf(rawWord("far", 900f, 450f, 990f, 490f))))))
        assertTrue(none.selectBlock(layout, 0).carriedOver(layout, elsewhere).isEmpty)
    }
}
