package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class DrawDraftTest {

    private fun line(n: Int, y: Float = 0.5f) = List(n) { NormPoint(0.1f + 0.8f * it / (n - 1), y) }

    private fun circle(n: Int = 40) = List(n + 1) {
        val a = (2 * Math.PI * (it % n) / n).toFloat()
        NormPoint(0.5f + 0.2f * cos(a), 0.5f + 0.25f * sin(a))
    }

    private fun draft(vararg strokes: List<NormPoint>): DrawDraft {
        var s = Sketch()
        for (p in strokes) s = s.add(p, 0xFFE53935.toInt(), 0.025f)
        return DrawDraft(s, colorIndex = 3, brush = BrushSize.THICK, headLeft = true)
    }

    @Test
    fun `a draft round trips with strokes, colour, brush and head direction`() {
        val d = draft(line(10), circle())
        val back = DrawDraftCodec.decode(DrawDraftCodec.encode(d))
        assertEquals(2, back.sketch.strokes.size)
        assertEquals(3, back.colorIndex)
        assertEquals(BrushSize.THICK, back.brush)
        assertTrue(back.headLeft)
        assertEquals(d.sketch.strokes[0].argb, back.sketch.strokes[0].argb)
        assertEquals(0.025f, back.sketch.strokes[0].width, 1e-3f)
    }

    @Test
    fun `points come back within a quantisation step`() {
        val d = draft(line(10))
        val back = DrawDraftCodec.decode(DrawDraftCodec.encode(d))
        val a = d.sketch.strokes[0].points
        val b = back.sketch.strokes[0].points
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertTrue(abs(a[i].x - b[i].x) < 1f / 4095)
            assertTrue(abs(a[i].y - b[i].y) < 1f / 4095)
        }
    }

    @Test
    fun `a closed outline is still filled after the round trip`() {
        val back = DrawDraftCodec.decode(DrawDraftCodec.encode(draft(circle())))
        assertTrue(back.sketch.strokes.single().filled)
    }

    @Test
    fun `an empty draft round trips`() {
        val back = DrawDraftCodec.decode(DrawDraftCodec.encode(DrawDraft()))
        assertTrue(back.sketch.isEmpty)
        assertEquals(DrawDraft.DEFAULT_COLOR_INDEX, back.colorIndex)
        assertFalse(back.headLeft)
    }

    @Test
    fun `the saved text is bounded however much was drawn`() {
        var s = Sketch()
        repeat(Sketch.MAX_STROKES) { s = s.add(List(Sketch.MAX_POINTS) { i -> NormPoint(i / 4000f, (it % 97) / 97f) }, 0xFF000000.toInt(), 0.02f) }
        val text = DrawDraftCodec.encode(DrawDraft(s))
        assertEquals(Sketch.MAX_STROKES, s.strokes.size)
        // 6 hex digits a point, at most MAX_POINTS_TOTAL points, plus a little per stroke.
        assertTrue("${text.length} chars", text.length < DrawDraftCodec.MAX_POINTS_TOTAL * 6 + Sketch.MAX_STROKES * 24 + 64)
        val back = DrawDraftCodec.decode(text)
        assertEquals(Sketch.MAX_STROKES, back.sketch.strokes.size)
    }

    @Test
    fun `thinning keeps both ends of a long stroke`() {
        val long = List(2000) { NormPoint(it / 2000f, 0.3f) }
        val back = DrawDraftCodec.decode(DrawDraftCodec.encode(draft(long)))
        val pts = back.sketch.strokes.single().points
        assertEquals(DrawDraftCodec.MAX_POINTS_PER_STROKE, pts.size)
        assertEquals(0f, pts.first().x, 1e-3f)
        assertEquals(1999 / 2000f, pts.last().x, 1e-3f)
    }

    @Test
    fun `garbage reads as an empty draft and never throws`() {
        val junk = listOf(
            "", "x", "1", "1~2~3", "2~1~1~0~", "1~a~b~c~d", "1~1~1~0~zzzzzzzz,5,000000", "1~1~1~0~ffffffff,5,00000",
            "1~1~1~0~ffffffff,5,", "1~99~99~1~|||", "~~~~", "1~1~1~0~ffffffff,x,000000", "1~-4~-1~0~ffffffff,250,000fff",
        )
        for (text in junk) {
            val d = DrawDraftCodec.decode(text)
            assertNotNull(text, d)
            assertTrue(text, d.colorIndex in SketchPalette.swatches.indices)
        }
        assertTrue(DrawDraftCodec.decode("1~1~1~0~zzzzzzzz,5,000000").sketch.isEmpty)
    }

    @Test
    fun `one damaged stroke is skipped and the others kept`() {
        val good = DrawDraftCodec.encode(draft(line(10)))
        val body = good.substringAfterLast('~')
        val mixed = good.substringBeforeLast('~') + "~" + "broken|" + body
        assertEquals(1, DrawDraftCodec.decode(mixed).sketch.strokes.size)
    }

    @Test
    fun `colour index is kept inside the palette`() {
        assertEquals(SketchPalette.swatches.last().argb, DrawDraft(colorIndex = 99).argb)
        assertEquals(SketchPalette.swatches.first().argb, DrawDraft(colorIndex = -3).argb)
    }

    @Test
    fun `every ready shape adds strokes and its body and tail are filled`() {
        for (shape in ReadyShape.entries) {
            val sketch = ReadyShapes.addTo(Sketch(), shape, 0xFFFB8C00.toInt(), BrushSize.MEDIUM.width)
            assertEquals(shape.name, 3, sketch.strokes.size)
            assertTrue(shape.name, sketch.strokes.all { it.points.all { p -> p.x in 0f..1f && p.y in 0f..1f } })
            // The first stroke is the body and always a closed loop.
            assertTrue(shape.name, sketch.strokes[0].filled)
            // The eye is a dot in the dark ink, not the body colour.
            assertEquals(1, sketch.strokes[2].points.size)
            assertTrue(sketch.strokes[2].argb != sketch.strokes[0].argb)
        }
        val fish = ReadyShapes.addTo(Sketch(), ReadyShape.ROUND_FISH, 0xFFFB8C00.toInt(), BrushSize.MEDIUM.width)
        assertTrue("the tail closes too", fish.strokes[1].filled)
    }

    @Test
    fun `a tadpole's tail is an open line, so it is not filled`() {
        val s = ReadyShapes.addTo(Sketch(), ReadyShape.TADPOLE, 0xFF1E88E5.toInt(), BrushSize.THIN.width)
        assertFalse(s.strokes[1].filled)
    }

    @Test
    fun `ready shapes add to what is already drawn and respect the stroke limit`() {
        var s = ReadyShapes.addTo(Sketch(), ReadyShape.ROUND_FISH, 0xFF000000.toInt(), 0.02f)
        repeat(30) { s = ReadyShapes.addTo(s, ReadyShape.LONG_FISH, 0xFF000000.toInt(), 0.02f) }
        assertEquals(Sketch.MAX_STROKES, s.strokes.size)
    }

    private fun filler(n: Int): Sketch {
        var s = Sketch()
        repeat(n) { s = s.add(line(5, y = 0.1f + 0.01f * it), 0xFF000000.toInt(), 0.02f) }
        return s
    }

    @Test
    fun `a ready shape comes out head left when the head is ticked left`() {
        for (shape in ReadyShape.entries) {
            val right = ReadyShapes.addTo(Sketch(), shape, 0xFFFB8C00.toInt(), 0.025f, headLeft = false)
            val left = ReadyShapes.addTo(Sketch(), shape, 0xFFFB8C00.toInt(), 0.025f, headLeft = true)
            assertEquals(shape.name, right.strokes.size, left.strokes.size)
            // The eye is the last stroke: on the right for a head on the right, mirrored for the left.
            val eyeRight = right.strokes.last().points.single()
            val eyeLeft = left.strokes.last().points.single()
            assertTrue(shape.name, eyeRight.x > 0.5f)
            assertEquals(shape.name, 1f - eyeRight.x, eyeLeft.x, 1e-6f)
            assertEquals(shape.name, eyeRight.y, eyeLeft.y, 1e-6f)
            // The body keeps its loop and its fill.
            assertTrue(shape.name, left.strokes[0].filled)
        }
    }

    @Test
    fun `a ready shape is added whole or not at all`() {
        val room = Sketch.MAX_STROKES - 3
        val fits = ReadyShapes.addTo(filler(room), ReadyShape.ROUND_FISH, 0xFF000000.toInt(), 0.02f)
        assertEquals(Sketch.MAX_STROKES, fits.strokes.size)
        for (used in room + 1..Sketch.MAX_STROKES) {
            val before = filler(used)
            assertFalse("$used strokes", ReadyShapes.fits(before, ReadyShape.TADPOLE))
            val after = ReadyShapes.addTo(before, ReadyShape.TADPOLE, 0xFF000000.toInt(), 0.02f)
            assertEquals("$used strokes: nothing added", used, after.strokes.size)
        }
        assertTrue(ReadyShapes.fits(filler(room), ReadyShape.TADPOLE))
    }

    @Test
    fun `the finished Swim clears the draft only when the sketch is the one that was taken`() {
        val taken = draft(line(10)).copy(headLeft = true)
        val cleared = draftAfterSwim(taken, taken)!!
        assertTrue(cleared.sketch.isEmpty)
        assertEquals(taken.colorIndex, cleared.colorIndex)
        assertTrue("head direction is kept", cleared.headLeft)
        // A stroke added since, or a start over and a new drawing, is left alone.
        val edited = taken.copy(sketch = taken.sketch.add(line(6, y = 0.2f), 0xFF000000.toInt(), 0.02f))
        assertEquals(null, draftAfterSwim(taken, edited))
        assertEquals(null, draftAfterSwim(taken, taken.copy(sketch = Sketch())))
        // Only the colour changed: the sketch is still the one saved.
        assertTrue(draftAfterSwim(taken, taken.copy(colorIndex = 0))!!.sketch.isEmpty)
    }

    @Test
    fun `shape labels are the copy names`() {
        assertEquals(AquariumCopy.SHAPE_TADPOLE, ReadyShape.TADPOLE.label)
    }
}
