package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSnapTest {

    private val tol = 0.01f

    private fun snap(b: Board, id: Long, cx: Float, cy: Float, o: SnapOptions = SnapOptions()) = BoardSnap.snapMove(b, id, cx, cy, o)

    @Test
    fun `a layer near the canvas edge is pulled to it and the guide runs the whole canvas`() {
        val b = board(layer(1, 300f, 300f))
        val r = snap(b, 1, 55f, 300f)
        assertEquals(50f, r.cx, tol)
        assertTrue(r.snappedX)
        val g = r.guides.single { it.kind == GuideKind.EDGE && it.vertical }
        assertEquals(0f, g.at, tol)
        assertEquals(0f, g.from, tol)
        assertEquals(1080f, g.to, tol)
    }

    @Test
    fun `a layer farther than the threshold from everything does not move`() {
        val b = board(layer(1, 300f, 300f))
        val r = snap(b, 1, 300f, 300f)
        assertEquals(300f, r.cx, 0f)
        assertEquals(300f, r.cy, 0f)
        assertFalse(r.snappedX)
        assertFalse(r.snappedY)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun `the far edge snaps too`() {
        val b = board(layer(1, 300f, 300f))
        val r = snap(b, 1, 1024f, 300f)
        assertEquals(1030f, r.cx, tol)
    }

    @Test
    fun `the middle snaps to the canvas centre`() {
        val b = board(layer(1, 300f, 300f))
        val r = snap(b, 1, 545f, 535f)
        assertEquals(540f, r.cx, tol)
        assertEquals(540f, r.cy, tol)
        assertEquals(2, r.guides.count { it.kind == GuideKind.CENTRE })
    }

    @Test
    fun `the threshold is a limit not a suggestion`() {
        val b = board(layer(1, 300f, 300f))
        assertTrue(snap(b, 1, 548f, 300f).snappedX)
        assertFalse(snap(b, 1, 548.5f, 300f).snappedX)
        assertTrue(snap(b, 1, 548.5f, 300f, SnapOptions(threshold = 9f)).snappedX)
    }

    @Test
    fun `edges and centres of other layers are targets and a guide joins the two`() {
        val b = board(layer(1, 500f, 200f), layer(2, 700f, 600f))
        // Layer 2's left (653) is 3 from layer 1's right (550)? it is 103 away: move it close.
        val r = snap(b, 2, 603f, 602f)
        assertEquals(600f, r.cx, tol)
        val edge = r.guides.single { it.kind == GuideKind.EDGE && it.vertical }
        assertEquals(550f, edge.at, tol)
        // The guide reaches from the top of the upper layer to the bottom of the lower one.
        assertEquals(150f, edge.from, tol)
        assertTrue(edge.to >= 650f - tol)
    }

    @Test
    fun `middle snaps to middle`() {
        val b = board(layer(1, 300f, 200f), layer(2, 700f, 600f))
        val r = snap(b, 2, 304f, 600f)
        assertEquals(300f, r.cx, tol)
        assertTrue(r.guides.any { it.kind == GuideKind.CENTRE && it.vertical && Math.abs(it.at - 300f) < tol })
    }

    @Test
    fun `when two targets are equally near a centre beats an edge`() {
        // Layer 1 spans 250..350 (middle 300). The moving layer is 88 wide at 297: its middle is 3 short
        // of 300 and its left edge (253) is 3 past 250.
        val b = board(layer(1, 300f, 200f), layer(2, 700f, 600f, w = 88f))
        val r = snap(b, 2, 297f, 600f)
        assertEquals(300f, r.cx, tol)
    }

    @Test
    fun `a turned layer snaps by the corners that stick out`() {
        val b = board(layer(1, 300f, 300f, rot = 45f))
        val r = snap(b, 1, 75f, 300f)
        assertEquals(70.71f, r.cx, 0.01f)
    }

    @Test
    fun `locked layers are targets and layers off the canvas are not`() {
        val locked = board(layer(1, 500f, 200f, locked = true), layer(2, 700f, 600f))
        assertEquals(600f, snap(locked, 2, 603f, 600f).cx, tol)
        val off = board(layer(1, 500f, -900f), layer(2, 700f, 600f))
        assertFalse(snap(off, 2, 503f, 600f).snappedX)
    }

    @Test
    fun `each kind of target can be switched off`() {
        val b = board(layer(1, 500f, 200f), layer(2, 300f, 600f))
        assertFalse(snap(b, 2, 55f, 600f, SnapOptions(canvasEdges = false)).snappedX)
        assertFalse(snap(b, 2, 543f, 600f, SnapOptions(canvasCentre = false, layers = false, spacing = false)).snappedX)
        assertFalse(snap(b, 2, 503f, 600f, SnapOptions(layers = false, spacing = false)).snappedX)
    }

    @Test
    fun `an unknown layer is left where it was proposed`() {
        val r = snap(board(layer(1, 300f, 300f)), 9, 55f, 55f)
        assertEquals(55f, r.cx, 0f)
        assertFalse(r.snappedX)
    }

    private val row = SnapOptions(canvasCentre = false, canvasEdges = false, layers = false)

    @Test
    fun `placed between two neighbours the layer takes the middle so both gaps are equal`() {
        // A spans 50..150, B spans 450..550; a 100 wide layer fits with 100 either side at centre 300.
        val b = board(layer(1, 100f, 500f), layer(2, 500f, 500f), layer(3, 305f, 500f))
        val r = snap(b, 3, 305f, 500f, row)
        assertEquals(300f, r.cx, tol)
        assertTrue(r.snappedX)
        val gaps = r.guides.filter { it.kind == GuideKind.SPACING }
        assertEquals(2, gaps.size)
        assertTrue(gaps.all { !it.vertical })
        assertEquals(100f, gaps[0].to - gaps[0].from, tol)
        assertEquals(100f, gaps[1].to - gaps[1].from, tol)
    }

    @Test
    fun `a layer that does not fit between two neighbours is not centred between them`() {
        val b = board(layer(1, 100f, 500f), layer(2, 280f, 500f), layer(3, 205f, 500f))
        assertFalse(snap(b, 3, 205f, 500f, row).snappedX)
    }

    @Test
    fun `a gap already between two layers is repeated beside them, on either side`() {
        // A 50..150 and B 250..350: a gap of 100.
        val b = board(layer(1, 100f, 500f), layer(2, 300f, 500f), layer(3, 505f, 500f))
        assertEquals(500f, snap(b, 3, 505f, 500f, row).cx, tol)
        val left = board(layer(1, 700f, 500f), layer(2, 900f, 500f), layer(3, 495f, 500f))
        // A 650..750 and B 850..950: gap 100, so the layer's right edge goes to 550 and its centre to 500.
        assertEquals(500f, snap(left, 3, 495f, 500f, row).cx, tol)
    }

    @Test
    fun `a layer in another row is not part of the spacing`() {
        val b = board(layer(1, 100f, 100f), layer(2, 500f, 100f), layer(3, 305f, 800f))
        assertFalse(snap(b, 3, 305f, 800f, row).snappedX)
    }

    @Test
    fun `spacing works down a column the same way`() {
        val b = board(layer(1, 500f, 100f), layer(2, 500f, 500f), layer(3, 500f, 305f))
        val r = snap(b, 3, 500f, 305f, row)
        assertEquals(300f, r.cy, tol)
        assertTrue(r.snappedY)
        assertTrue(r.guides.filter { it.kind == GuideKind.SPACING }.all { it.vertical })
    }

    @Test
    fun `x and y snap independently`() {
        val b = board(layer(1, 300f, 300f))
        val r = snap(b, 1, 55f, 700f)
        assertTrue(r.snappedX)
        assertFalse(r.snappedY)
        assertNotNull(r.guides.firstOrNull())
    }

    @Test
    fun `rotation pulls to the nearest step inside the tolerance and leaves the rest`() {
        assertEquals(90f, BoardSnap.snapRotation(87f), tol)
        assertEquals(80f, BoardSnap.snapRotation(80f), tol)
        assertEquals(0f, BoardSnap.snapRotation(-2.5f), tol)
        assertEquals(180f, BoardSnap.snapRotation(178f), tol)
        assertEquals(-90f, BoardSnap.snapRotation(-91f), tol)
        assertEquals(45f, BoardSnap.snapRotation(47f, stepDeg = 45f, toleranceDeg = 3f), tol)
        assertEquals(47f, BoardSnap.snapRotation(47f, stepDeg = 0f), tol)
    }
}
