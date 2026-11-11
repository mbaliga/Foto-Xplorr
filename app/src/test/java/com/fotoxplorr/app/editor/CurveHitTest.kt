package com.fotoxplorr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveHitTest {

    private val points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))

    @Test
    fun `nearest picks the point inside the radius and nothing outside it`() {
        // 200x200 px canvas: the middle point is at (100, 100).
        assertEquals(1, CurveHit.nearest(points, 110f, 95f, 200f, 200f, 24f))
        assertNull(CurveHit.nearest(points, 140f, 100f, 200f, 200f, 24f))
    }

    @Test
    fun `the touch target is a circle whatever shape the canvas is`() {
        // A 328x234 and a 234x328 canvas: 20px away in x and in y must hit or miss alike.
        // Point at the middle of each.
        val wide = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))
        val radius = 24f
        assertEquals(1, CurveHit.nearest(wide, 164f + 20f, 117f, 328f, 234f, radius))
        assertEquals(1, CurveHit.nearest(wide, 164f, 117f + 20f, 328f, 234f, radius))
        assertEquals(1, CurveHit.nearest(wide, 117f + 20f, 164f, 234f, 328f, radius))
        assertEquals(1, CurveHit.nearest(wide, 117f, 164f + 20f, 234f, 328f, radius))
        assertNull(CurveHit.nearest(wide, 164f + 26f, 117f, 328f, 234f, radius))
        assertNull(CurveHit.nearest(wide, 117f, 164f + 26f, 234f, 328f, radius))
    }

    @Test
    fun `the nearer of two points inside the radius wins`() {
        val close = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(0.56f, 0.5f), CurvePoint(1f, 1f))
        // Points at x=100 and x=112 on a 200px canvas; touching at 109 is nearer the second.
        assertEquals(2, CurveHit.nearest(close, 109f, 100f, 200f, 200f, 24f))
        assertEquals(1, CurveHit.nearest(close, 103f, 100f, 200f, 200f, 24f))
    }

    @Test
    fun `a zero-sized canvas has nothing to hit and does not divide by zero`() {
        assertNull(CurveHit.nearest(points, 0f, 0f, 0f, 0f, 24f))
        assertNull(CurveHit.planGrab(ToneCurve.IDENTITY, 0f, 0f, 0f, 0f, 24f))
        assertEquals(0f, CurveHit.toNormX(10f, 0f), 0f)
        assertEquals(0f, CurveHit.toNormY(10f, 0f), 0f)
    }

    @Test
    fun `graph y runs up while canvas y runs down`() {
        assertEquals(200f, CurveHit.toPixelY(0f, 200f), 0f)
        assertEquals(0f, CurveHit.toPixelY(1f, 200f), 0f)
        assertEquals(1f, CurveHit.toNormY(0f, 200f), 0f)
        assertEquals(0.25f, CurveHit.toNormX(50f, 200f), 0f)
        assertEquals(1f, CurveHit.toNormX(900f, 200f), 0f) // clamped
    }

    @Test
    fun `distance to the curve is zero on it and grows away from it`() {
        val lut = ToneCurve.IDENTITY.toLut()
        assertEquals(0f, CurveHit.distanceToCurve(lut, 100f, 100f, 200f, 200f), 0.8f)
        // 40px straight above the diagonal's midpoint is about 28px from the line (45 degrees).
        val above = CurveHit.distanceToCurve(lut, 100f, 60f, 200f, 200f)
        assertEquals(28.3f, above, 1.5f)
    }

    @Test
    fun `dragging out needs to pass the margin on any side`() {
        assertFalse(CurveHit.isDraggedOut(-10f, 50f, 200f, 200f, 32f))
        assertTrue(CurveHit.isDraggedOut(-40f, 50f, 200f, 200f, 32f))
        assertTrue(CurveHit.isDraggedOut(240f, 50f, 200f, 200f, 32f))
        assertTrue(CurveHit.isDraggedOut(50f, -40f, 200f, 200f, 32f))
        assertTrue(CurveHit.isDraggedOut(50f, 240f, 200f, 200f, 32f))
        assertFalse(CurveHit.isDraggedOut(100f, 100f, 200f, 200f, 32f))
    }

    // ---- planGrab ----

    @Test
    fun `touching a point grabs it with the finger's offset from its centre`() {
        val grab = CurveHit.planGrab(ToneCurve(points), 108f, 94f, 200f, 200f, 24f)!!
        assertTrue(grab.existed)
        assertEquals(1, grab.index)
        assertEquals(8f, grab.offsetXPx, 1e-4f)
        assertEquals(-6f, grab.offsetYPx, 1e-4f)
        assertEquals(3, grab.curve.points.size)
    }

    @Test
    fun `touching the line away from a point inserts one on the line`() {
        // Identity curve, touch near (50, 150) which is on the diagonal (x=0.25, y=0.25).
        val grab = CurveHit.planGrab(ToneCurve.IDENTITY, 50f, 140f, 200f, 200f, 24f)!!
        assertFalse(grab.existed)
        assertEquals(3, grab.curve.points.size)
        assertEquals(0.25f, grab.curve.points[grab.index].x, 1e-6f)
        assertEquals(0.25f, grab.curve.points[grab.index].y, 1e-3f)
        assertTrue(grab.curve.isIdentity) // inserting changed nothing
    }

    @Test
    fun `touching empty space inserts a point under the finger`() {
        // (50, 20): well above the diagonal at x=0.25.
        val grab = CurveHit.planGrab(ToneCurve.IDENTITY, 50f, 20f, 200f, 200f, 24f)!!
        assertFalse(grab.existed)
        val p = grab.curve.points[grab.index]
        assertEquals(0.25f, p.x, 1e-6f)
        assertEquals(0.9f, p.y, 1e-6f)
        assertEquals(0f, grab.offsetXPx, 1e-4f)
        assertEquals(0f, grab.offsetYPx, 1e-4f)
    }

    @Test
    fun `a touch straight above a point too close in x to add another grabs that point`() {
        val curve = ToneCurve(points)
        // x = 0.51 (within the gap of the middle point), but 70px above it: outside the radius.
        val grab = CurveHit.planGrab(curve, 102f, 30f, 200f, 200f, 24f)
        assertNotNull(grab)
        assertTrue(grab!!.existed)
        assertEquals(1, grab.index)
    }

    @Test
    fun `a full curve grabs nothing in empty space`() {
        var curve = ToneCurve.IDENTITY
        var x = 0.05f
        while (curve.points.size < ToneCurve.MAX_POINTS) {
            curve = curve.insertAt(x, 0.5f)!!.curve
            x += 0.06f
        }
        // Far from every point and from the curve, and 0.05 from the nearest x: the only reason
        // to refuse is the size limit.
        val grab = CurveHit.planGrab(curve, 190f, 190f, 200f, 200f, 24f)
        assertNull(grab)
    }
}
