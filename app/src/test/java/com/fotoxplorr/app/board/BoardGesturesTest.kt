package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGesturesTest {

    // ---- placing the board on the screen ----

    @Test
    fun `the board fits the box whole, by the tighter side`() {
        val tall = BoardGestures.fit(CanvasSize(1080, 1350), boxW = 400f, boxH = 900f)
        assertEquals(400f / 1080f, tall.pxPerUnit, 1e-6f)
        assertEquals(400f, tall.widthPx, 0.01f)
        assertEquals(500f, tall.heightPx, 0.01f)
        val wide = BoardGestures.fit(CanvasSize(1920, 1080), boxW = 900f, boxH = 300f)
        assertEquals(300f / 1080f, wide.pxPerUnit, 1e-6f)
        assertEquals(533.33f, wide.widthPx, 0.05f)
        assertEquals(300f, wide.heightPx, 0.01f)
    }

    @Test
    fun `a box of no size still gives a usable scale`() {
        val f = BoardGestures.fit(CanvasSize(1080, 1080), 0f, -5f)
        assertTrue(f.pxPerUnit > 0f)
        assertEquals(0f, BoardGestures.units(10f, 0f), 0f)
        assertEquals(10f, BoardGestures.units(5f, 0.5f), 0.0001f)
    }

    @Test
    fun `the preview edge is the shown size rounded up to 64 and held to the limits`() {
        assertEquals(448, BoardGestures.previewEdge(BoardGestures.Fit(1f, 400f, 300f)))
        assertEquals(448, BoardGestures.previewEdge(BoardGestures.Fit(1f, 441f, 300f)))
        assertEquals(BoardGestures.MIN_PREVIEW_EDGE, BoardGestures.previewEdge(BoardGestures.Fit(1f, 50f, 50f)))
        assertEquals(BoardGestures.MAX_PREVIEW_EDGE, BoardGestures.previewEdge(BoardGestures.Fit(1f, 3000f, 2000f)))
    }

    // ---- a pinch and a twist ----

    private val at = Transform(cx = 600f, cy = 400f, scale = 1f, rotationDeg = 0f)

    @Test
    fun `a drag with no zoom or turn moves the layer by the pan`() {
        val t = BoardGestures.transformAbout(at, Pt(100f, 100f), 30f, -20f, 1f, 0f)
        assertEquals(630f, t.cx, 0.001f)
        assertEquals(380f, t.cy, 0.001f)
        assertEquals(1f, t.scale, 0f)
        assertEquals(0f, t.rotationDeg, 0f)
    }

    @Test
    fun `a pinch about the layer's own centre only changes its size`() {
        val t = BoardGestures.transformAbout(at, Pt(600f, 400f), 0f, 0f, 2f, 0f)
        assertEquals(600f, t.cx, 0.001f)
        assertEquals(400f, t.cy, 0.001f)
        assertEquals(2f, t.scale, 0.0001f)
    }

    @Test
    fun `a pinch about a point holds that point still, so the centre moves away from it`() {
        val t = BoardGestures.transformAbout(at, Pt(500f, 400f), 0f, 0f, 2f, 0f)
        assertEquals("the centre was 100 right of the fingers, now 200", 700f, t.cx, 0.001f)
        assertEquals(400f, t.cy, 0.001f)
    }

    @Test
    fun `a clockwise twist carries a centre that is right of the fingers to below them`() {
        val t = BoardGestures.transformAbout(at, Pt(500f, 400f), 0f, 0f, 1f, 90f)
        assertEquals(500f, t.cx, 0.01f)
        assertEquals(500f, t.cy, 0.01f)
        assertEquals(90f, t.rotationDeg, 0.001f)
    }

    @Test
    fun `the fingers' middle carries the layer while it zooms and turns`() {
        val t = BoardGestures.transformAbout(at, Pt(500f, 400f), 50f, 10f, 1f, 90f)
        assertEquals(550f, t.cx, 0.01f)
        assertEquals(510f, t.cy, 0.01f)
    }

    @Test
    fun `a twist and its opposite give back the start`() {
        val there = BoardGestures.transformAbout(at, Pt(321f, 77f), 0f, 0f, 1.7f, 33f)
        val back = BoardGestures.transformAbout(there, Pt(321f, 77f), 0f, 0f, 1f / 1.7f, -33f)
        assertEquals(at.cx, back.cx, 0.01f)
        assertEquals(at.cy, back.cy, 0.01f)
        assertEquals(at.scale, back.scale, 0.0001f)
        assertEquals(at.rotationDeg, back.rotationDeg, 0.001f)
    }

    @Test
    fun `the turn a gesture gives agrees with how the geometry reads a turn`() {
        // A layer 200 wide whose box turns 90 clockwise: its right-hand middle point lands below its centre.
        val l = layer(1, 500f, 500f, w = 200f, h = 100f, rot = 90f)
        val p = BoardGeometry.pointOf(l, 1f, 0.5f)
        assertEquals(500f, p.x, 0.01f)
        assertEquals(600f, p.y, 0.01f)
        // The same sign when a twist turns a centre round a point.
        val t = BoardGestures.transformAbout(Transform(600f, 500f), Pt(500f, 500f), 0f, 0f, 1f, 90f)
        assertEquals(500f, t.cx, 0.01f)
        assertEquals(600f, t.cy, 0.01f)
    }

    // ---- settling a gesture ----

    private val twoLayers = board(
        layer(1, 540f, 540f, w = 200f, h = 200f),
        layer(2, 200f, 200f, w = 100f, h = 100f),
    )

    @Test
    fun `a one finger move is pulled to the canvas centre and shows the guide`() {
        val s = BoardGestures.settle(twoLayers, 2L, Transform(cx = 537f, cy = 300f), turned = false, moveOnly = true, snap = SnapOptions())
        assertEquals(540f, s.transform.cx, 0.001f)
        assertTrue(s.guides.isNotEmpty())
    }

    @Test
    fun `a move with a second finger down is not pulled to anything`() {
        val s = BoardGestures.settle(twoLayers, 2L, Transform(cx = 537f, cy = 300f), turned = false, moveOnly = false, snap = SnapOptions())
        assertEquals(537f, s.transform.cx, 0f)
        assertTrue(s.guides.isEmpty())
    }

    @Test
    fun `snapping off leaves the position alone`() {
        val s = BoardGestures.settle(twoLayers, 2L, Transform(cx = 537f, cy = 300f), turned = false, moveOnly = true, snap = null)
        assertEquals(537f, s.transform.cx, 0f)
        assertTrue(s.guides.isEmpty())
    }

    @Test
    fun `a turn near a step is pulled to it, a turn far from one is not, and no turn is left alone`() {
        val near = BoardGestures.settle(twoLayers, 2L, Transform(1f, 1f, 1f, 43f), turned = true, moveOnly = false, snap = null)
        assertEquals(45f, near.transform.rotationDeg, 0.001f)
        val far = BoardGestures.settle(twoLayers, 2L, Transform(1f, 1f, 1f, 37f), turned = true, moveOnly = false, snap = null)
        assertEquals(37f, far.transform.rotationDeg, 0.001f)
        val still = BoardGestures.settle(twoLayers, 2L, Transform(1f, 1f, 1f, 44f), turned = false, moveOnly = false, snap = null)
        assertEquals("not turned by this gesture, so not pulled", 44f, still.transform.rotationDeg, 0.001f)
        assertFalse(still.guides.isNotEmpty())
    }
}
