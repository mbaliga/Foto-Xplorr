package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGeometryTest {

    private val tol = 0.001f

    @Test
    fun `an unturned box has its corners at plus and minus half the drawn size`() {
        val c = BoardGeometry.corners(layer(1, 200f, 100f, w = 100f, h = 50f))
        assertEquals(Pt(150f, 75f), c[0])
        assertEquals(Pt(250f, 75f), c[1])
        assertEquals(Pt(250f, 125f), c[2])
        assertEquals(Pt(150f, 125f), c[3])
    }

    @Test
    fun `the scale enlarges the box about its centre`() {
        val b = BoardGeometry.bounds(layer(1, 200f, 100f, w = 100f, h = 50f, scale = 2f))
        assertEquals(100f, b.left, tol)
        assertEquals(300f, b.right, tol)
        assertEquals(50f, b.top, tol)
        assertEquals(150f, b.bottom, tol)
    }

    @Test
    fun `a clockwise quarter turn swaps the width and height and sends the top left corner to the top right`() {
        val l = layer(1, 200f, 100f, w = 100f, h = 50f, rot = 90f)
        val c = BoardGeometry.corners(l)
        assertEquals(225f, c[0].x, tol)
        assertEquals(50f, c[0].y, tol)
        val b = BoardGeometry.bounds(l)
        assertEquals(50f, b.width, tol)
        assertEquals(100f, b.height, tol)
        assertEquals(200f, b.centreX, tol)
        assertEquals(100f, b.centreY, tol)
    }

    @Test
    fun `a square turned 45 degrees is a diamond and its bounds are the diagonal`() {
        val b = BoardGeometry.bounds(layer(1, 0f, 0f, rot = 45f))
        assertEquals(70.71f, b.right, 0.01f)
        assertEquals(-70.71f, b.left, 0.01f)
    }

    @Test
    fun `a point inside the diamond but outside the unturned box is hit, and the reverse`() {
        val diamond = layer(1, 0f, 0f, rot = 45f)
        assertTrue(BoardGeometry.contains(diamond, 65f, 0f))
        assertFalse(BoardGeometry.contains(diamond, 45f, 45f))
        val upright = layer(2, 0f, 0f)
        assertFalse(BoardGeometry.contains(upright, 65f, 0f))
        assertTrue(BoardGeometry.contains(upright, 45f, 45f))
    }

    @Test
    fun `local shares are one half at the centre and invert pointOf under any turn and scale`() {
        val l = layer(1, 300f, 220f, w = 120f, h = 80f, rot = 33f, scale = 1.7f)
        val centre = BoardGeometry.localShare(l, 300f, 220f)!!
        assertEquals(0.5f, centre.x, tol)
        assertEquals(0.5f, centre.y, tol)
        for ((u, v) in listOf(0f to 0f, 1f to 0f, 0.25f to 0.8f, 1f to 1f, -0.2f to 1.4f)) {
            val p = BoardGeometry.pointOf(l, u, v)
            val back = BoardGeometry.localShare(l, p.x, p.y)!!
            assertEquals(u, back.x, tol)
            assertEquals(v, back.y, tol)
        }
    }

    @Test
    fun `a box with no size cannot be hit`() {
        val l = layer(1, 0f, 0f, w = 0f, h = 0f)
        assertNull(BoardGeometry.localShare(l, 0f, 0f))
        assertFalse(BoardGeometry.contains(l, 0f, 0f, slop = 10f))
    }

    @Test
    fun `slop widens the box on every side`() {
        val l = layer(1, 100f, 100f, w = 40f, h = 40f)
        assertFalse(BoardGeometry.contains(l, 128f, 100f))
        assertTrue(BoardGeometry.contains(l, 128f, 100f, slop = 10f))
        assertFalse(BoardGeometry.contains(l, 135f, 100f, slop = 10f))
    }

    @Test
    fun `the topmost layer under the point wins`() {
        val b = board(layer(1, 100f, 100f), layer(2, 120f, 100f), layer(3, 500f, 500f))
        assertEquals(2L, BoardGeometry.hitTest(b, 110f, 100f))
        assertEquals(1L, BoardGeometry.hitTest(b, 60f, 100f))
        assertNull(BoardGeometry.hitTest(b, 300f, 300f))
        assertEquals(listOf(2L, 1L), BoardGeometry.hitStack(b, 110f, 100f))
    }

    @Test
    fun `a locked layer is skipped so the tap goes through to the one under it`() {
        val b = board(layer(1, 100f, 100f), layer(2, 100f, 100f, locked = true))
        assertEquals(1L, BoardGeometry.hitTest(b, 100f, 100f))
        assertEquals(2L, BoardGeometry.hitTest(b, 100f, 100f, includeLocked = true))
    }

    @Test
    fun `a part the mask hides is not hit, so the tap reaches the layer beneath`() {
        val top = layer(2, 100f, 100f, mask = LayerMask(MaskShape.Ellipse))
        val b = board(layer(1, 100f, 100f), top)
        // The top left corner of the box is outside the oval.
        assertEquals(1L, BoardGeometry.hitTest(b, 55f, 55f))
        assertEquals(2L, BoardGeometry.hitTest(b, 100f, 100f))
        assertEquals(2L, BoardGeometry.hitTest(b, 55f, 55f, respectMask = false))
    }

    @Test
    fun `the mask turns with the layer`() {
        // A wide oval turned a quarter: its long axis is now vertical.
        val l = layer(1, 100f, 100f, w = 200f, h = 40f, rot = 90f, mask = LayerMask(MaskShape.Ellipse))
        assertTrue(BoardGeometry.hits(l, 100f, 180f))
        assertFalse(BoardGeometry.hits(l, 180f, 100f))
    }

    @Test
    fun `a finger just beside a layer with a soft edge still hits it`() {
        // Top edge at y = 50. A soft mask shows nothing AT its edge, so the margin must not ask for coverage there.
        val soft = layer(1, 100f, 100f, w = 100f, h = 100f, mask = LayerMask(MaskShape.Full, feather = 0.3f))
        assertTrue(BoardGeometry.hits(soft, 100f, 45f, slop = 6f))
        assertFalse("beyond the finger's width", BoardGeometry.hits(soft, 100f, 40f, slop = 6f))
        // Inside the soft ramp's first sliver it is still not the layer.
        assertFalse(BoardGeometry.hits(soft, 100f, 52f, slop = 6f))
        // The corner of an oval's box is not the oval, margin or not.
        val oval = layer(2, 100f, 100f, w = 100f, h = 100f, mask = LayerMask(MaskShape.Ellipse))
        assertFalse(BoardGeometry.hits(oval, 47f, 47f, slop = 6f))
        assertTrue(BoardGeometry.hits(oval, 100f, 45f, slop = 6f))
    }

    @Test
    fun `a layer entirely off the canvas is not on it, and one that overlaps an edge is`() {
        val canvas = CanvasSize(1080, 1080)
        assertFalse(BoardGeometry.isOnCanvas(layer(1, -200f, 500f), canvas))
        assertTrue(BoardGeometry.isOnCanvas(layer(1, -40f, 500f), canvas))
        assertFalse(BoardGeometry.isOnCanvas(layer(1, 500f, 1200f), canvas))
        // A tall box turned flat may reach back onto the canvas.
        assertTrue(BoardGeometry.isOnCanvas(layer(1, -120f, 500f, w = 20f, h = 400f, rot = 90f), canvas))
    }

    @Test
    fun `a turned layer is tested by its own corners, not by the rectangle around them`() {
        val canvas = CanvasSize(1000, 1000)
        // A diamond (200 square turned 45 degrees) reaches 141 from its centre along the diagonals.
        // Its surrounding rectangle covers the canvas corner; the diamond itself does not.
        assertFalse(BoardGeometry.isOnCanvas(layer(1, -90f, -90f, w = 200f, h = 200f, rot = 45f), canvas))
        assertTrue(BoardGeometry.isOnCanvas(layer(1, -60f, -60f, w = 200f, h = 200f, rot = 45f), canvas))
        assertFalse(BoardGeometry.isOnCanvas(layer(1, 1090f, 1090f, w = 200f, h = 200f, rot = 45f), canvas))
        assertTrue(BoardGeometry.isOnCanvas(layer(1, 1060f, 1060f, w = 200f, h = 200f, rot = 45f), canvas))
        // A canvas that sits inside a big layer is covered by it.
        assertTrue(BoardGeometry.isOnCanvas(layer(1, 500f, 500f, w = 4000f, h = 4000f, rot = 30f), canvas))
    }

    @Test
    fun `the angle to a point reads clockwise from straight right`() {
        val t = Transform(0f, 0f)
        assertEquals(0f, BoardGeometry.angleTo(t, 10f, 0f), tol)
        assertEquals(90f, BoardGeometry.angleTo(t, 0f, 10f), tol)
        assertEquals(-90f, BoardGeometry.angleTo(t, 0f, -10f), tol)
    }
}
