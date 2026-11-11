package com.fotoxplorr.app.textselect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class TextGeometryTest {

    private val unit = box(0f, 0f, 10f, 10f)

    @Test
    fun `an upright quad contains its inside and its edge but not outside`() {
        assertTrue(unit.contains(Pt(5f, 5f)))
        assertTrue(unit.contains(Pt(0f, 0f)))
        assertTrue(unit.contains(Pt(10f, 5f)))
        assertFalse(unit.contains(Pt(10.5f, 5f)))
        assertFalse(unit.contains(Pt(-1f, -1f)))
    }

    @Test
    fun `distance is zero inside and the straight line to the nearest edge or corner outside`() {
        assertEquals(0f, unit.distanceTo(Pt(3f, 3f)), 1e-4f)
        assertEquals(4f, unit.distanceTo(Pt(14f, 5f)), 1e-4f)
        assertEquals(3f, unit.distanceTo(Pt(5f, -3f)), 1e-4f)
        // Off a corner it is the distance to the corner, not to either side's extension.
        assertEquals(5f, unit.distanceTo(Pt(13f, 14f)), 1e-4f)
    }

    @Test
    fun `winding order does not matter`() {
        val reversed = Quad(unit.tl, unit.bl, unit.br, unit.tr)
        assertTrue(reversed.contains(Pt(5f, 5f)))
        assertFalse(reversed.contains(Pt(20f, 5f)))
    }

    @Test
    fun `a tilted quad does not contain the corner of its own bounding box`() {
        val q = turned(500f, 250f, 200f, 40f, 30f)
        assertTrue(q.contains(q.centre))
        // The case a stored axis-aligned rectangle gets wrong: inside the box, off the text.
        val boxCorner = Pt(q.right, q.top)
        assertFalse(q.contains(boxCorner))
        assertTrue(q.distanceTo(boxCorner) > 10f)
    }

    @Test
    fun `angle reads the direction the text runs in`() {
        assertEquals(0f, unit.angle, 1e-5f)
        assertEquals(30f, Math.toDegrees(turned(0f, 0f, 100f, 20f, 30f).angle.toDouble()).toFloat(), 1e-3f)
        // Upside down: the text's top-left corner is at the picture's bottom right.
        val upsideDown = Quad(unit.br, unit.bl, unit.tl, unit.tr)
        assertEquals(PI.toFloat(), abs(upsideDown.angle), 1e-5f)
        assertEquals(90f, Math.toDegrees(turned(0f, 0f, 100f, 20f, 90f).angle.toDouble()).toFloat(), 1e-3f)
    }

    @Test
    fun `scaled multiplies x and y separately`() {
        val q = Quad.fromBounds(0.1f, 0.2f, 0.3f, 0.4f).scaled(1000f, 500f)
        assertEquals(100f, q.left, 1e-3f)
        assertEquals(100f, q.top, 1e-3f)
        assertEquals(300f, q.right, 1e-3f)
        assertEquals(200f, q.bottom, 1e-3f)
    }

    @Test
    fun `segment to segment distance covers crossing, touching, parallel and skew cases`() {
        // Crossing.
        assertEquals(0f, TextGeometry.distanceBetweenSegments(Pt(0f, 0f), Pt(10f, 10f), Pt(0f, 10f), Pt(10f, 0f)), 1e-5f)
        // End touching the middle of the other (a T).
        assertEquals(0f, TextGeometry.distanceBetweenSegments(Pt(0f, 0f), Pt(10f, 0f), Pt(5f, 0f), Pt(5f, 8f)), 1e-5f)
        // Collinear overlap and collinear gap.
        assertEquals(0f, TextGeometry.distanceBetweenSegments(Pt(0f, 0f), Pt(10f, 0f), Pt(8f, 0f), Pt(20f, 0f)), 1e-5f)
        assertEquals(5f, TextGeometry.distanceBetweenSegments(Pt(0f, 0f), Pt(10f, 0f), Pt(15f, 0f), Pt(20f, 0f)), 1e-5f)
        // Parallel.
        assertEquals(3f, TextGeometry.distanceBetweenSegments(Pt(0f, 0f), Pt(10f, 0f), Pt(0f, 3f), Pt(10f, 3f)), 1e-5f)
        // Apart, nearest at an endpoint.
        assertEquals(5f, TextGeometry.distanceBetweenSegments(Pt(0f, 0f), Pt(3f, 0f), Pt(6f, 4f), Pt(6f, 9f)), 1e-5f)
    }

    @Test
    fun `point to segment distance clamps to the ends and handles a zero length segment`() {
        assertEquals(3f, TextGeometry.distanceToSegment(Pt(5f, 3f), Pt(0f, 0f), Pt(10f, 0f)), 1e-5f)
        assertEquals(5f, TextGeometry.distanceToSegment(Pt(13f, 4f), Pt(0f, 0f), Pt(10f, 0f)), 1e-5f)
        assertEquals(5f, TextGeometry.distanceToSegment(Pt(3f, 4f), Pt(0f, 0f), Pt(0f, 0f)), 1e-5f)
    }

    @Test
    fun `a segment is distance zero from a quad it crosses or ends inside`() {
        assertEquals(0f, unit.distanceToSegment(Pt(-5f, 5f), Pt(15f, 5f)), 1e-5f)
        assertEquals(0f, unit.distanceToSegment(Pt(5f, 5f), Pt(6f, 6f)), 1e-5f)
        assertEquals(0f, unit.distanceToSegment(Pt(5f, 5f), Pt(50f, 50f)), 1e-5f)
        assertEquals(4f, unit.distanceToSegment(Pt(14f, -10f), Pt(14f, 20f)), 1e-5f)
    }

    @Test
    fun `touchedBySegment respects the radius and rejects far boxes`() {
        assertTrue(unit.touchedBySegment(Pt(14f, -10f), Pt(14f, 20f), 4f))
        assertFalse(unit.touchedBySegment(Pt(14f, -10f), Pt(14f, 20f), 3.9f))
        assertFalse(unit.touchedBySegment(Pt(100f, 100f), Pt(200f, 100f), 5f))
        assertFalse(unit.touchedBySegment(Pt(-100f, 50f), Pt(100f, 50f), 5f))
    }

    @Test
    fun `polygon containment is even-odd and needs three points`() {
        val tri = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(0f, 10f))
        assertTrue(TextGeometry.polygonContains(tri, Pt(2f, 2f)))
        assertFalse(TextGeometry.polygonContains(tri, Pt(8f, 8f)))
        assertFalse(TextGeometry.polygonContains(listOf(Pt(0f, 0f), Pt(10f, 10f)), Pt(5f, 5f)))
        // A concave L: the notch is outside.
        val l = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 4f), Pt(4f, 4f), Pt(4f, 10f), Pt(0f, 10f))
        assertTrue(TextGeometry.polygonContains(l, Pt(2f, 8f)))
        assertFalse(TextGeometry.polygonContains(l, Pt(8f, 8f)))
    }

    @Test
    fun `signed area has a sign for the winding and is zero for a line`() {
        val square = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f))
        assertEquals(100f, abs(TextGeometry.signedArea(square)), 1e-4f)
        assertEquals(-TextGeometry.signedArea(square), TextGeometry.signedArea(square.reversed()), 1e-4f)
        assertEquals(0f, TextGeometry.signedArea(listOf(Pt(0f, 0f), Pt(5f, 5f), Pt(10f, 10f))), 1e-4f)
        assertEquals(0f, TextGeometry.signedArea(listOf(Pt(0f, 0f), Pt(5f, 5f))), 1e-4f)
    }

    @Test
    fun `decimate keeps the ends and thins the dense middle`() {
        val dense = (0..100).map { Pt(it.toFloat(), 0f) }
        val thin = TextGeometry.decimate(dense, 10f)
        assertEquals(dense.first(), thin.first())
        assertEquals(dense.last(), thin.last())
        assertTrue(thin.size in 10..12)
        assertEquals(listOf(Pt(1f, 1f)), TextGeometry.decimate(listOf(Pt(1f, 1f)), 10f))
        assertTrue(TextGeometry.decimate(emptyList(), 10f).isEmpty())
    }

    @Test
    fun `an oriented hull at angle zero is the bounding box and follows a tilt otherwise`() {
        val flat = Quad.orientedHull(listOf(Pt(10f, 20f), Pt(50f, 20f), Pt(50f, 40f), Pt(10f, 40f)), 0f)
        assertEquals(10f, flat.left, 1e-3f)
        assertEquals(50f, flat.right, 1e-3f)
        assertEquals(20f, flat.top, 1e-3f)
        assertEquals(40f, flat.bottom, 1e-3f)

        val a = turned(300f, 300f, 120f, 30f, 20f)
        val b = turned(460f, 330f, 120f, 30f, 20f)
        val hull = Quad.orientedHull(a.corners + b.corners, a.angle)
        assertEquals(a.angle, hull.angle, 1e-3f)
        for (corner in a.corners + b.corners) assertTrue(hull.distanceTo(corner) < 0.01f)
        // Text order: tl is the start of the line, so the hull runs the same way the text does.
        assertTrue(hull.tr.x > hull.tl.x)
    }

    @Test
    fun `frame map round trips and turns one screen distance into one frame distance`() {
        // A 1000 x 500 frame drawn into a 500 x 250 rectangle with 100 px of letterbox above.
        val map = FrameMap(1000f, 500f, fitLeft = 0f, fitTop = 100f, fitWidth = 500f, fitHeight = 250f)
        val p = map.toFrame(250f, 225f)
        assertEquals(500f, p.x, 1e-3f)
        assertEquals(250f, p.y, 1e-3f)
        val back = map.toScreen(p)
        assertEquals(250f, back.x, 1e-3f)
        assertEquals(225f, back.y, 1e-3f)
        assertEquals(24f, map.frameDistance(12f), 1e-3f)
    }

    @Test
    fun `a screen circle is a frame circle on a frame that is not square`() {
        // The guard against doing the reach in normalised units: 12 screen px must reach the same
        // frame distance to the right of a word as above it (a 2:1 frame drawn at half size).
        val map = FrameMap(FRAME_W.toFloat(), FRAME_H.toFloat(), 0f, 0f, FRAME_W / 2f, FRAME_H / 2f)
        val reach = map.frameDistance(12f)
        val layout = TextLayout.build(listOf(RawBlock(listOf(RawLine(listOf(rawWord("word", 100f, 100f, 200f, 130f)))))), FRAME_W, FRAME_H)

        fun touches(p: Pt) = layout.wordsTouchedBy(listOf(p), reach).isNotEmpty()
        assertTrue(touches(Pt(200f + reach - 0.5f, 115f)))
        assertFalse(touches(Pt(200f + reach + 0.5f, 115f)))
        assertTrue(touches(Pt(150f, 100f - reach + 0.5f)))
        assertFalse(touches(Pt(150f, 100f - reach - 0.5f)))
        // And diagonally off a corner it is the true (Euclidean) distance.
        val d = reach / sqrt(2f)
        assertTrue(touches(Pt(200f + d - 0.3f, 130f + d - 0.3f)))
        assertFalse(touches(Pt(200f + d + 0.3f, 130f + d + 0.3f)))
    }

    @Test
    fun `a degenerate fit rectangle does not divide by zero`() {
        val map = FrameMap(1000f, 500f, 0f, 0f, 0f, 0f)
        assertTrue(map.toFrame(10f, 10f).isFinite)
        assertTrue(map.frameDistance(12f).isFinite())
    }
}
