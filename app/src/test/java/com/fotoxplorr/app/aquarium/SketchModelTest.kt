package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchModelTest {

    private val line = listOf(NormPoint(0.1f, 0.5f), NormPoint(0.5f, 0.5f), NormPoint(0.9f, 0.5f))

    private fun Sketch.addLine(argb: Int = 0xFFFF0000.toInt()) = add(line, argb, BrushSize.MEDIUM.width, autoFill = false)

    @Test
    fun `a new sketch is empty with nothing to undo or redo`() {
        val s = Sketch()
        assertTrue(s.isEmpty)
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertFalse(s.isFull)
    }

    @Test
    fun `add undo redo mirror the selection session contract`() {
        val one = Sketch().addLine(1)
        val two = one.addLine(2)
        assertEquals(listOf(1, 2), two.strokes.map { it.argb })

        val back = two.undo()
        assertEquals(listOf(1), back.strokes.map { it.argb })
        assertTrue(back.canRedo)

        val forward = back.redo()
        assertEquals(listOf(1, 2), forward.strokes.map { it.argb })
        assertFalse(forward.canRedo)
    }

    @Test
    fun `a new stroke clears redo`() {
        val undone = Sketch().addLine(1).addLine(2).undo()
        assertTrue(undone.canRedo)
        val next = undone.addLine(3)
        assertFalse(next.canRedo)
        assertEquals(listOf(1, 3), next.strokes.map { it.argb })
    }

    @Test
    fun `undo and redo on an empty history return the same instance`() {
        val s = Sketch()
        assertSame(s, s.undo())
        assertSame(s, s.redo())
    }

    @Test
    fun `clear empties the sketch and is not undoable`() {
        val cleared = Sketch().addLine().addLine().clear()
        assertTrue(cleared.isEmpty)
        assertFalse(cleared.canUndo)
        assertFalse(cleared.canRedo)
        assertSame(cleared, cleared.undo())
    }

    @Test
    fun `the stroke count is bounded and a full sketch refuses more`() {
        var s = Sketch()
        repeat(Sketch.MAX_STROKES) { s = s.addLine() }
        assertTrue(s.isFull)
        assertSame(s, s.addLine())
        assertEquals(Sketch.MAX_STROKES, s.strokes.size)
        // Undo frees a slot again.
        assertFalse(s.undo().isFull)
    }

    @Test
    fun `points are clamped to the board and non-finite ones are dropped`() {
        val s = Sketch().add(
            listOf(NormPoint(-3f, 0.5f), NormPoint(Float.NaN, 0.5f), NormPoint(0.5f, Float.POSITIVE_INFINITY), NormPoint(7f, 2f)),
            argb = 1, width = 0.02f, autoFill = false,
        )
        val pts = s.strokes.single().points
        assertEquals(listOf(NormPoint(0f, 0.5f), NormPoint(1f, 1f)), pts)
    }

    @Test
    fun `a stroke with no usable point is refused`() {
        val s = Sketch()
        assertSame(s, s.add(emptyList(), 1, 0.02f))
        assertSame(s, s.add(listOf(NormPoint(Float.NaN, 0f)), 1, 0.02f))
    }

    @Test
    fun `a single point is a valid dot`() {
        val s = Sketch().add(listOf(NormPoint(0.5f, 0.5f)), 1, 0.05f)
        assertEquals(1, s.strokes.size)
        assertFalse(s.strokes.single().filled)
    }

    @Test
    fun `an over-long stroke is thinned to the cap and keeps both ends`() {
        val many = List(Sketch.MAX_POINTS * 3 + 7) { NormPoint(it.toFloat() / (Sketch.MAX_POINTS * 3 + 6), (it % 5) / 10f) }
        val pts = Sketch().add(many, 1, 0.02f, autoFill = false).strokes.single().points
        assertTrue(pts.size <= Sketch.MAX_POINTS)
        assertEquals(many.first(), pts.first())
        assertEquals(many.last(), pts.last())
    }

    @Test
    fun `width is clamped into the sane range`() {
        assertEquals(Sketch.MIN_WIDTH, Sketch().add(line, 1, -5f).strokes.single().width, 0f)
        assertEquals(Sketch.MAX_WIDTH, Sketch().add(line, 1, 99f).strokes.single().width, 0f)
        assertEquals(BrushSize.THIN.width, Sketch().add(line, 1, Float.NaN).strokes.single().width, 0f)
    }

    @Test
    fun `a nearly closed stroke is marked filled only when auto fill is on`() {
        val ring = ringPoints(0.5f, 0.5f, 0.3f, 40, gapDegrees = 8f)
        assertTrue(Sketch().add(ring, 1, 0.02f, autoFill = true).strokes.single().filled)
        assertFalse(Sketch().add(ring, 1, 0.02f, autoFill = false).strokes.single().filled)
        assertFalse(Sketch().add(line, 1, 0.02f, autoFill = true).strokes.single().filled)
    }

    @Test
    fun `brush sizes grow and the palette has named distinct colours`() {
        val widths = BrushSize.entries.map { it.width }
        assertEquals(widths.sorted(), widths)
        assertEquals(widths.distinct(), widths)
        assertTrue(widths.all { it in Sketch.MIN_WIDTH..Sketch.MAX_WIDTH })

        val palette = SketchPalette.swatches
        assertEquals(8, palette.size)
        assertEquals(palette.size, palette.map { it.name }.distinct().size)
        assertEquals(palette.size, palette.map { it.argb }.distinct().size)
        assertTrue(palette.all { it.name.isNotBlank() && (it.argb ushr 24) == 0xFF })
    }
}

/** A closed-ish ring of [n] points; [gapDegrees] leaves a wedge open at angle 0. */
internal fun ringPoints(cx: Float, cy: Float, r: Float, n: Int, gapDegrees: Float = 0f): List<NormPoint> {
    val start = Math.toRadians((gapDegrees / 2f).toDouble())
    val end = Math.toRadians((360f - gapDegrees / 2f).toDouble())
    return List(n + 1) { i ->
        val a = start + (end - start) * i / n
        NormPoint(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat())
    }
}
