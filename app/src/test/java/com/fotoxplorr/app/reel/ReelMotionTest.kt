package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Which part of a picture each motion shows, checked as exact geometry. */
class ReelMotionTest {

    private val wide = 16f / 9f
    private val eps = 1e-4f

    /** Width / height of [r] in pixels of a picture [w] x [h]. */
    private fun shape(r: NormRect, w: Int, h: Int) = (r.width * w) / (r.height * h)

    private fun inside(r: NormRect) = r.left >= -eps && r.top >= -eps && r.right <= 1f + eps && r.bottom <= 1f + eps

    @Test
    fun `no motion shows the covering window and never moves`() {
        // A 4:3 picture in a 16:9 frame: all the width, the middle 75% of the height.
        val a = ReelGeometry.windowAt(ReelMotion.None, 0f, 4000, 3000, wide)
        val b = ReelGeometry.windowAt(ReelMotion.None, 1f, 4000, 3000, wide)
        assertEquals(a, b)
        assertEquals(0f, a.left, eps)
        assertEquals(1f, a.right, eps)
        assertEquals(0.75f, a.height, eps)
        assertEquals(0.5f, a.centreY, eps)
        assertEquals(wide, shape(a, 4000, 3000), 1e-3f)
    }

    @Test
    fun `a picture already the frame's shape is covered whole`() {
        val w = ReelGeometry.windowAt(ReelMotion.None, 0.5f, 1600, 900, wide)
        assertEquals(NormRect.FULL.width, w.width, eps)
        assertEquals(NormRect.FULL.height, w.height, eps)
    }

    @Test
    fun `a zoom in ends a fifth closer and on the focus`() {
        val m = ReelMotion.ZoomIn(NormPoint(0.25f, 0.5f))
        val start = ReelGeometry.windowAt(m, 0f, 1600, 900, wide)
        val end = ReelGeometry.windowAt(m, 1f, 1600, 900, wide)
        assertEquals(1f, start.width, eps)
        assertEquals(1f / ReelMotion.ZOOM_SCALE, end.width, eps)
        assertTrue(inside(end))
        // The focus is at 0.25 across; the window cannot be centred there without leaving the picture,
        // so it is moved to touch the edge: the nearest it can be.
        assertEquals(0f, end.left, eps)
    }

    @Test
    fun `a zoom out is a zoom in backwards`() {
        val f = NormPoint(0.6f, 0.4f)
        val inn = ReelMotion.ZoomIn(f)
        val out = ReelMotion.ZoomOut(f)
        for (t in listOf(0f, 0.3f, 1f)) {
            val a = ReelGeometry.windowAt(inn, 1f - t, 3000, 2000, wide)
            val b = ReelGeometry.windowAt(out, t, 3000, 2000, wide)
            assertEquals(a.left, b.left, eps)
            assertEquals(a.top, b.top, eps)
            assertEquals(a.right, b.right, eps)
            assertEquals(a.bottom, b.bottom, eps)
        }
    }

    @Test
    fun `progress is clamped and the move is linear in between`() {
        val m = ReelMotion.ZoomIn()
        val half = ReelGeometry.windowAt(m, 0.5f, 1600, 900, wide)
        assertEquals((1f + 1f / ReelMotion.ZOOM_SCALE) / 2f, half.width, eps)
        assertEquals(ReelGeometry.windowAt(m, 0f, 1600, 900, wide), ReelGeometry.windowAt(m, -3f, 1600, 900, wide))
        assertEquals(ReelGeometry.windowAt(m, 1f, 1600, 900, wide), ReelGeometry.windowAt(m, 9f, 1600, 900, wide))
    }

    @Test
    fun `a pan goes from its first window to its second at the frame's shape`() {
        // Loose rectangles on a 3:2 picture, neither the frame's shape.
        val m = ReelMotion.Pan(NormRect(0f, 0f, 0.5f, 0.5f), NormRect(0.5f, 0.5f, 1f, 1f))
        val s = ReelGeometry.windowAt(m, 0f, 3000, 2000, wide)
        val e = ReelGeometry.windowAt(m, 1f, 3000, 2000, wide)
        assertEquals(wide, shape(s, 3000, 2000), 1e-3f)
        assertEquals(wide, shape(e, 3000, 2000), 1e-3f)
        assertTrue(inside(s) && inside(e))
        assertTrue(e.left > s.left)
        assertTrue(e.top > s.top)
    }

    @Test
    fun `a pan rectangle too big for the picture is shrunk to the covering window`() {
        val m = ReelMotion.Pan(NormRect.FULL, NormRect.FULL)
        val w = ReelGeometry.windowAt(m, 0.5f, 3000, 2000, wide)
        assertEquals(ReelGeometry.windowAt(ReelMotion.None, 0f, 3000, 2000, wide), w)
    }

    @Test
    fun `a tall frame crops the sides of a wide picture`() {
        val tall = 9f / 16f
        val w = ReelGeometry.windowAt(ReelMotion.None, 0f, 1600, 900, tall)
        assertEquals(1f, w.height, eps)
        assertEquals(tall / (16f / 9f), w.width, eps)
        assertEquals(tall, shape(w, 1600, 900), 1e-3f)
    }

    @Test
    fun `an unknown picture size is taken to have the frame's shape`() {
        val w = ReelGeometry.windowAt(ReelMotion.None, 0f, 0, 0, wide)
        assertEquals(NormRect.FULL, w)
    }

    @Test
    fun `any motion on any picture and frame stays inside the picture at the frame's shape`() {
        val rnd = Random(7)
        val frames = listOf(16f / 9f, 9f / 16f, 1f, 4f / 3f)
        repeat(300) {
            val w = rnd.nextInt(200, 6000)
            val h = rnd.nextInt(200, 6000)
            val frame = frames[rnd.nextInt(frames.size)]
            val f = NormPoint(rnd.nextFloat(), rnd.nextFloat())
            val x0 = rnd.nextFloat() * 0.8f
            val y0 = rnd.nextFloat() * 0.8f
            val pan = ReelMotion.Pan(
                NormRect(x0, y0, x0 + 0.05f + rnd.nextFloat() * (1f - x0 - 0.05f), y0 + 0.05f + rnd.nextFloat() * (1f - y0 - 0.05f)),
                NormRect(0.1f, 0.1f, 0.9f, 0.6f),
            )
            for (m in listOf(ReelMotion.None, ReelMotion.ZoomIn(f), ReelMotion.ZoomOut(f), pan)) {
                for (t in listOf(0f, 0.37f, 1f)) {
                    val r = ReelGeometry.windowAt(m, t, w, h, frame)
                    assertTrue("$m $t -> $r", inside(r))
                    assertEquals("$m $t", frame, shape(r, w, h), frame * 1e-3f)
                }
            }
        }
    }

    @Test
    fun `ease is zero at the start, one at the end and slow at both`() {
        assertEquals(0f, ReelGeometry.ease(0f), 0f)
        assertEquals(1f, ReelGeometry.ease(1f), 0f)
        assertEquals(0.5f, ReelGeometry.ease(0.5f), eps)
        assertTrue(ReelGeometry.ease(0.1f) < 0.1f)
        assertTrue(ReelGeometry.ease(0.9f) > 0.9f)
        assertEquals(0f, ReelGeometry.ease(-4f), 0f)
    }

    @Test
    fun `a rectangle is usable only inside the picture and not tiny`() {
        assertTrue(NormRect(0.1f, 0.1f, 0.9f, 0.9f).isUsable)
        assertFalse(NormRect(-0.1f, 0f, 0.5f, 0.5f).isUsable)
        assertFalse(NormRect(0f, 0f, 1.2f, 0.5f).isUsable)
        assertFalse(NormRect(0.1f, 0.1f, 0.12f, 0.9f).isUsable)
        assertFalse(NormRect(0.5f, 0.1f, 0.4f, 0.9f).isUsable)
        assertFalse(NormRect(Float.NaN, 0f, 1f, 1f).isUsable)
    }
}
