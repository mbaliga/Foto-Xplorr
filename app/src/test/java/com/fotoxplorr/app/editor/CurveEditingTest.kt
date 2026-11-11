package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Index-based curve editing: the operations a finger on the graph is built from.
 *
 * The regression this file exists for is the old drag, which went through `withPoint` and so never
 * moved a point at all -- it added one at the finger and dropped only neighbours within 0.02 of
 * it, leaving a trail of strays behind a fast drag.
 */
class CurveEditingTest {

    private val three = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f)))

    @Test
    fun `moveTo keeps the index and the order`() {
        val moved = three.moveTo(1, 0.6f, 0.8f)
        assertEquals(3, moved.points.size)
        assertEquals(CurvePoint(0.6f, 0.8f), moved.points[1])
        assertEquals(CurvePoint(0f, 0f), moved.points[0])
        assertEquals(CurvePoint(1f, 1f), moved.points[2])
    }

    @Test
    fun `moveTo stops short of a neighbour instead of crossing or swallowing it`() {
        val beyondRight = three.moveTo(1, 0.99f, 0.5f)
        assertEquals(3, beyondRight.points.size)
        assertEquals(1f - ToneCurve.MIN_POINT_GAP, beyondRight.points[1].x, 1e-6f)
        val beyondLeft = three.moveTo(1, -3f, 0.5f)
        assertEquals(ToneCurve.MIN_POINT_GAP, beyondLeft.points[1].x, 1e-6f)
        // Still sorted.
        assertEquals(beyondLeft.points.sortedBy { it.x }, beyondLeft.points)
    }

    @Test
    fun `end points move up and down only`() {
        val low = three.moveTo(0, 0.4f, 0.2f)
        assertEquals(CurvePoint(0f, 0.2f), low.points[0])
        val high = three.moveTo(2, 0.3f, 0.7f)
        assertEquals(CurvePoint(1f, 0.7f), high.points[2])
    }

    @Test
    fun `moveTo clamps y into the unit range and ignores non-finite input`() {
        assertEquals(1f, three.moveTo(1, 0.5f, 5f).points[1].y, 0f)
        assertEquals(0f, three.moveTo(1, 0.5f, -5f).points[1].y, 0f)
        assertSame(three, three.moveTo(1, Float.NaN, Float.NaN))
        assertSame(three, three.moveTo(7, 0.5f, 0.5f))
        assertSame(three, three.moveTo(-1, 0.5f, 0.5f))
    }

    @Test
    fun `a fast drag with large jumps never leaves strays behind`() {
        // 50 moves of one point, jumping across most of the graph each time. The old withPoint
        // path left a point behind on every jump that landed more than 0.02 away.
        var curve = three
        val random = java.util.Random(7)
        repeat(50) {
            curve = curve.moveTo(1, random.nextFloat(), random.nextFloat())
            assertEquals(3, curve.points.size)
            assertEquals(curve.points.sortedBy { it.x }, curve.points)
        }
    }

    @Test
    fun `the old withPoint drag is the thing that leaves strays`() {
        // Documents why moveTo exists: this is what dragging used to do.
        var curve = three
        listOf(0.2f, 0.6f, 0.85f).forEach { x -> curve = curve.withPoint(CurvePoint(x, 0.5f)) }
        assertTrue("withPoint stacks points", curve.points.size > 3)
    }

    @Test
    fun `insertAt puts the new point on the current curve so the shape does not change`() {
        val bent = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f)))
        val inserted = bent.insertAt(0.25f)
        assertNotNull(inserted)
        val curve = inserted!!.curve
        assertEquals(4, curve.points.size)
        assertEquals(1, inserted.index)
        assertEquals(0.25f, curve.points[inserted.index].x, 1e-6f)
        assertEquals(bent.valueAt(0.25f), curve.points[inserted.index].y, 1e-5f)
        // The point is ON the curve, so the curve passes through it; the tangents around a new
        // point are recomputed, so the shape shifts a little -- but only a little, nothing like the
        // jump of a point dropped at the finger.
        val before = bent.toLut()
        val after = curve.toLut()
        val worst = (0..255).maxOf { kotlin.math.abs(before[it] - after[it]) }
        assertTrue("shape moved by up to $worst levels", worst <= 8)
    }

    @Test
    fun `insertAt with a height puts the point exactly there`() {
        val inserted = three.insertAt(0.2f, 0.9f)!!
        assertEquals(CurvePoint(0.2f, 0.9f), inserted.curve.points[inserted.index])
    }

    @Test
    fun `insertAt refuses within the gap of an existing point`() {
        assertNull(three.insertAt(0.5f + ToneCurve.MIN_POINT_GAP / 2))
        assertNull(three.insertAt(0.005f)) // beside the black point
        assertNotNull(three.insertAt(0.5f + ToneCurve.MIN_POINT_GAP * 2))
    }

    @Test
    fun `insertAt refuses beyond the maximum number of points`() {
        var curve = ToneCurve.IDENTITY
        var x = 0.05f
        while (curve.points.size < ToneCurve.MAX_POINTS) {
            curve = curve.insertAt(x)!!.curve
            x += 0.06f
        }
        assertEquals(ToneCurve.MAX_POINTS, curve.points.size)
        assertNull(curve.insertAt(0.95f))
    }

    @Test
    fun `removeAt removes an interior point and refuses the ends`() {
        val removed = three.removeAt(1)
        assertEquals(2, removed.points.size)
        assertTrue(removed.isIdentity)
        assertSame(three, three.removeAt(0))
        assertSame(three, three.removeAt(2))
        assertSame(three, three.removeAt(9))
        assertSame(three, three.removeAt(-1))
    }

    @Test
    fun `an edited curve survives a round trip through JSON`() {
        val edited = three.moveTo(1, 0.4f, 0.65f).insertAt(0.8f)!!.curve
        val json = parseJson(edited.toJson().stringify())!!
        val back = ToneCurve.fromJson(json)
        assertEquals(edited, back)
        assertTrue(edited.toLut().contentEquals(back.toLut()))
        assertFalse(back.isIdentity)
    }
}
