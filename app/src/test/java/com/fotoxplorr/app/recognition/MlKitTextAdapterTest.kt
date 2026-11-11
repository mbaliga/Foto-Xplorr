package com.fotoxplorr.app.recognition

import android.graphics.Point
import android.graphics.Rect
import com.fotoxplorr.app.textselect.Pt
import com.fotoxplorr.app.textselect.TextLayout
import com.google.mlkit.vision.text.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mapping from ML Kit's text objects to the plain layout input, pinned by building the ML Kit
 * objects by hand (their constructors are public) on a 400 x 200 frame. The frame is 2:1 on
 * purpose: a mapping that forgot which axis divides by which would pass on a square.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlKitTextAdapterTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int) = Rect(l, t, r, b)

    private fun corners(l: Int, t: Int, r: Int, b: Int) =
        listOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b))

    private fun element(text: String, box: Rect, points: List<Point>, lang: String = "en") =
        Text.Element(text, box, points, lang, null, 0f, 1f, emptyList<Text.Symbol>())

    private fun line(text: String, box: Rect, points: List<Point>, elements: List<Text.Element>, lang: String = "en") =
        Text.Line(text, box, points, lang, null, elements, 0f, 1f)

    private fun block(text: String, box: Rect, points: List<Point>, lines: List<Text.Line>, lang: String = "en") =
        Text.TextBlock(text, box, points, lang, null, lines)

    private fun twoWordBlock(): Text.TextBlock {
        val hello = element("Hello", rect(40, 20, 120, 40), corners(40, 20, 120, 40))
        val world = element("world", rect(130, 20, 210, 40), corners(130, 20, 210, 40), lang = "fr")
        val l = line("Hello world", rect(40, 20, 210, 40), corners(40, 20, 210, 40), listOf(hello, world))
        return block("Hello world", rect(40, 20, 210, 40), corners(40, 20, 210, 40), listOf(l))
    }

    @Test
    fun `words come out in delivered order with text, language and corners normalised to the frame`() {
        val raw = MlKitTextAdapter.toRawBlocks(Text("Hello world", listOf(twoWordBlock())), 400, 200)

        assertEquals(1, raw.size)
        val words = raw[0].lines.single().words
        assertEquals(listOf("Hello", "world"), words.map { it.text })
        assertEquals(listOf("en", "fr"), words.map { it.language })
        val q = words[0].quad
        assertEquals(40f / 400f, q.tl.x, 1e-6f)
        assertEquals(20f / 200f, q.tl.y, 1e-6f)
        assertEquals(120f / 400f, q.br.x, 1e-6f)
        assertEquals(40f / 200f, q.br.y, 1e-6f)
        assertEquals("en", raw[0].lines.single().language)
        assertEquals("en", raw[0].language)
    }

    @Test
    fun `the layout built from them is in frame pixels of the bitmap that was read`() {
        val raw = MlKitTextAdapter.toRawBlocks(Text("Hello world", listOf(twoWordBlock())), 400, 200)
        val layout = TextLayout.build(raw, 400, 200)

        assertTrue(layout.wordLevel)
        assertEquals(listOf("Hello", "world"), layout.words.map { it.text })
        assertEquals(40f, layout.words[0].quad.left, 1e-3f)
        assertEquals(210f, layout.lines[0].quad.right, 1e-3f)
        assertEquals(0, layout.wordAt(Pt(60f, 30f)))
        assertEquals(1, layout.wordAt(Pt(150f, 30f)))
    }

    @Test
    fun `a tilted line keeps its real quad and its angle`() {
        // A 100 x 20 line turned 30 degrees clockwise on screen about (200, 100), corners in text order.
        val a = 30.0 * PI / 180.0
        val ux = cos(a); val uy = sin(a)
        fun at(u: Double, v: Double) = Point((200 + u * ux - v * uy).toInt(), (100 + u * uy + v * ux).toInt())
        val pts = listOf(at(-50.0, -10.0), at(50.0, -10.0), at(50.0, 10.0), at(-50.0, 10.0))
        val box = rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        val w = element("Tilted", box, pts)
        val l = line("Tilted", box, pts, listOf(w))
        val raw = MlKitTextAdapter.toRawBlocks(Text("Tilted", listOf(block("Tilted", box, pts, listOf(l)))), 400, 200)
        val layout = TextLayout.build(raw, 400, 200)

        // The quad is the tilted shape, not its bounding box: its corner is where ML Kit said.
        assertEquals(pts[0].x.toFloat(), layout.words[0].quad.tl.x, 0.01f)
        assertEquals(pts[0].y.toFloat(), layout.words[0].quad.tl.y, 0.01f)
        assertTrue(layout.words[0].quad.tr.y > layout.words[0].quad.tl.y + 40f)
        assertEquals(a.toFloat(), layout.lines[0].angle, 0.02f)
        // A point in the bounding box's corner but off the tilted text is not on the word.
        assertNull(layout.wordAt(Pt(box.left + 2f, box.top + 2f)))
    }

    @Test
    fun `upside-down text reads as turned half way round`() {
        // Corner points start at the text's own top-left, which sits at the picture's bottom right.
        val pts = listOf(Point(300, 100), Point(200, 100), Point(200, 80), Point(300, 80))
        val box = rect(200, 80, 300, 100)
        val raw = MlKitTextAdapter.toRawBlocks(
            Text("x", listOf(block("x", box, pts, listOf(line("x", box, pts, listOf(element("x", box, pts))))))),
            400, 200,
        )
        val layout = TextLayout.build(raw, 400, 200)

        assertEquals(PI.toFloat(), abs(layout.lines[0].angle), 0.02f)
    }

    @Test
    fun `a level without usable corner points falls back to its bounding box`() {
        val box = rect(100, 60, 180, 80)
        val noPoints = emptyList<Point>()
        val raw = MlKitTextAdapter.toRawBlocks(
            Text("Box", listOf(block("Box", box, noPoints, listOf(line("Box", box, noPoints, listOf(element("Box", box, noPoints))))))),
            400, 200,
        )

        val q = raw.single().lines.single().words.single().quad
        assertEquals(100f / 400f, q.tl.x, 1e-6f)
        assertEquals(60f / 200f, q.tl.y, 1e-6f)
        assertEquals(180f / 400f, q.tr.x, 1e-6f)
        assertEquals(60f / 200f, q.tr.y, 1e-6f)
        assertEquals(180f / 400f, q.br.x, 1e-6f)
        assertEquals(80f / 200f, q.bl.y, 1e-6f)
        // The line and block shapes fall back the same way.
        assertEquals(q.br, raw.single().lines.single().quad!!.br)
        assertEquals(q.br, raw.single().quad!!.br)
    }

    @Test
    fun `three corner points are not a quad and fall back to the box`() {
        val box = rect(10, 10, 50, 30)
        val raw = MlKitTextAdapter.toRawBlocks(
            Text("T", listOf(block("T", box, listOf(Point(1, 1)), listOf(line("T", box, emptyList(), listOf(element("T", box, listOf(Point(1, 1), Point(2, 2), Point(3, 3))))))))),
            100, 100,
        )

        assertEquals(0.1f, raw.single().lines.single().words.single().quad.tl.x, 1e-6f)
    }

    @Test
    fun `a line with no words becomes one word holding the whole line`() {
        val box = rect(20, 20, 120, 40)
        val raw = MlKitTextAdapter.toRawBlocks(
            Text("Sale today", listOf(block("Sale today", box, corners(20, 20, 120, 40), listOf(line("Sale today", box, corners(20, 20, 120, 40), emptyList()))))),
            200, 200,
        )

        assertEquals(listOf("Sale today"), raw.single().lines.single().words.map { it.text })
    }

    @Test
    fun `blocks and lines keep their order and blank words are dropped by the layout`() {
        val b1 = block("A", rect(0, 0, 10, 10), corners(0, 0, 10, 10), listOf(line("A", rect(0, 0, 10, 10), corners(0, 0, 10, 10), listOf(element("A", rect(0, 0, 10, 10), corners(0, 0, 10, 10))))))
        val blank = element("  ", rect(20, 0, 30, 10), corners(20, 0, 30, 10))
        val b2 = block("B", rect(0, 50, 40, 60), corners(0, 50, 40, 60), listOf(line("B", rect(0, 50, 40, 60), corners(0, 50, 40, 60), listOf(blank, element("B", rect(0, 50, 10, 60), corners(0, 50, 10, 60))))))
        val raw = MlKitTextAdapter.toRawBlocks(Text("A B", listOf(b2, b1)), 100, 100)
        val layout = TextLayout.build(raw, 100, 100)

        // Delivered order is kept (B's block first), never re-sorted by position.
        assertEquals(listOf("B", "A"), layout.words.map { it.text })
        assertEquals(2, layout.blocks.size)
    }

    @Test
    fun `no text and a degenerate frame both give nothing`() {
        assertTrue(MlKitTextAdapter.toRawBlocks(Text("", emptyList<Text.TextBlock>()), 400, 200).isEmpty())
        assertTrue(MlKitTextAdapter.toRawBlocks(Text("Hello world", listOf(twoWordBlock())), 0, 200).isEmpty())
        assertTrue(MlKitTextAdapter.toRawBlocks(Text("Hello world", listOf(twoWordBlock())), 400, -1).isEmpty())
        assertFalse(TextLayout.build(MlKitTextAdapter.toRawBlocks(Text("Hello world", listOf(twoWordBlock())), 400, 200), 400, 200).isEmpty)
    }
}
