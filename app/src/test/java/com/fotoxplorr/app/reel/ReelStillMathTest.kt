package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The Ken Burns numbers: which part of a picture the crop shows, and how big a staged picture must be. */
class ReelStillMathTest {
    private val wide = ReelFrame(1920, 1080)
    private val tall = ReelFrame(1080, 1920)

    private fun mapX(w: NdcWindow, x: Float) = x * w.scaleX + w.shiftX
    private fun mapY(w: NdcWindow, y: Float) = y * w.scaleY + w.shiftY

    @Test
    fun `the whole picture is the identity crop`() {
        val w = NdcWindow.of(NormRect.FULL)
        assertEquals(NdcWindow.FULL, w)
        assertEquals(1f, w.scaleX, 0f)
        assertEquals(0f, w.shiftX, 0f)
        assertEquals(1f, w.scaleY, 0f)
        assertEquals(0f, w.shiftY, 0f)
    }

    @Test
    fun `a window's corners land on the corners of the frame`() {
        val rnd = Random(5)
        repeat(200) {
            val l = rnd.nextFloat() * 0.6f
            val t = rnd.nextFloat() * 0.6f
            val r = l + 0.1f + rnd.nextFloat() * (1f - l - 0.1f)
            val b = t + 0.1f + rnd.nextFloat() * (1f - t - 0.1f)
            val w = NdcWindow.of(NormRect(l, t, r, b))
            // A point on the picture at (u, v) from the top left is at x = 2u - 1, y = 1 - 2v in the frame's coordinates (y up).
            assertEquals(-1f, mapX(w, 2f * l - 1f), 1e-4f)
            assertEquals(1f, mapX(w, 2f * r - 1f), 1e-4f)
            assertEquals(1f, mapY(w, 1f - 2f * t), 1e-4f)
            assertEquals(-1f, mapY(w, 1f - 2f * b), 1e-4f)
        }
    }

    @Test
    fun `the window of a move always has the frame's shape in pixels`() {
        val rnd = Random(9)
        val motions = listOf(
            ReelMotion.None,
            ReelMotion.ZoomIn(NormPoint(0.3f, 0.7f)),
            ReelMotion.ZoomOut(NormPoint(0.9f, 0.1f)),
            ReelMotion.Pan(NormRect(0f, 0f, 0.4f, 0.5f), NormRect(0.5f, 0.4f, 1f, 1f)),
        )
        repeat(300) {
            val iw = 200 + rnd.nextInt(4000)
            val ih = 200 + rnd.nextInt(4000)
            val frame = listOf(wide, tall, ReelFrame(1080, 1080))[rnd.nextInt(3)]
            val motion = motions[rnd.nextInt(motions.size)]
            val w = ReelStillMath.cropAt(motion, rnd.nextFloat(), iw, ih, frame.ratio)
            val pixelShape = (w.halfWidth * iw) / (w.halfHeight * ih)
            assertEquals("no squash for ${iw}x$ih in $frame with $motion", frame.ratio, pixelShape, frame.ratio * 0.01f)
            assertTrue(w.centerX - w.halfWidth >= -1.0001f && w.centerX + w.halfWidth <= 1.0001f)
            assertTrue(w.centerY - w.halfHeight >= -1.0001f && w.centerY + w.halfHeight <= 1.0001f)
        }
    }

    @Test
    fun `a still with no move is the covering window the whole time`() {
        val a = ReelStillMath.cropAt(ReelMotion.None, 0f, 4000, 3000, wide.ratio)
        val b = ReelStillMath.cropAt(ReelMotion.None, 1f, 4000, 3000, wide.ratio)
        assertEquals(a, b)
        assertEquals(1f, a.halfWidth, 1e-5f)
        assertEquals(0.75f, a.halfHeight, 1e-5f)
        assertEquals(NdcWindow.FULL, ReelStillMath.cropAt(ReelMotion.None, 0.5f, 1920, 1080, wide.ratio))
    }

    @Test
    fun `a zoom in starts on the whole cover and ends a fifth closer, eased`() {
        val start = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 0f, 4000, 3000, wide.ratio)
        val end = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 1f, 4000, 3000, wide.ratio)
        val mid = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 0.5f, 4000, 3000, wide.ratio)
        assertEquals(1f, start.halfWidth, 1e-5f)
        assertEquals(1f / ReelMotion.ZOOM_SCALE, end.halfWidth, 1e-5f)
        assertEquals("ease passes the middle at the middle", (start.halfWidth + end.halfWidth) / 2f, mid.halfWidth, 1e-5f)
        val early = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 0.1f, 4000, 3000, wide.ratio)
        assertTrue("slow at the start: less than a tenth of the way", (start.halfWidth - early.halfWidth) < 0.1f * (start.halfWidth - end.halfWidth))
    }

    @Test
    fun `a zoom out is a zoom in backwards`() {
        val rnd = Random(2)
        repeat(50) {
            val p = rnd.nextFloat()
            val inn = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 1f - p, 3000, 2000, wide.ratio)
            val out = ReelStillMath.cropAt(ReelMotion.ZoomOut(), p, 3000, 2000, wide.ratio)
            assertEquals(inn.halfWidth, out.halfWidth, 1e-4f)
            assertEquals(inn.centerX, out.centerX, 1e-4f)
        }
    }

    @Test
    fun `progress outside 0 to 1 is held at the ends`() {
        val a = ReelStillMath.cropAt(ReelMotion.ZoomIn(), -3f, 4000, 3000, wide.ratio)
        val b = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 0f, 4000, 3000, wide.ratio)
        val c = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 7f, 4000, 3000, wide.ratio)
        val d = ReelStillMath.cropAt(ReelMotion.ZoomIn(), 1f, 4000, 3000, wide.ratio)
        assertEquals(b, a)
        assertEquals(d, c)
    }

    // ---- how big a staged picture must be ------------------------------------------------------------------

    @Test
    fun `a still with no move needs only the frame's long side`() {
        assertEquals(1920, ReelStillMath.stagedLongEdge(ReelMotion.None, 4000, 3000, wide))
        assertEquals(1920, ReelStillMath.stagedLongEdge(ReelMotion.None, 0, 0, tall))
    }

    @Test
    fun `a zoom on a 4 by 3 photo in a wide frame needs about a fifth more than the cover`() {
        // The closest window is 5/6 of the width of a 4:3 picture and must hold 1920 pixels: 2304 across.
        assertEquals(2304, ReelStillMath.stagedLongEdgeFor(ReelMotion.ZoomIn(), 4f / 3f, wide))
    }

    @Test
    fun `a tall photo in a wide frame needs more, because only a strip of it shows`() {
        val edge = ReelStillMath.stagedLongEdgeFor(ReelMotion.ZoomIn(), 3f / 4f, wide)
        assertTrue("$edge", edge > 2304)
    }

    @Test
    fun `a listed size that may be turned asks for the larger of the two readings`() {
        val listed = ReelStillMath.stagedLongEdge(ReelMotion.ZoomIn(), 4000, 3000, wide)
        val turned = ReelStillMath.stagedLongEdge(ReelMotion.ZoomIn(), 3000, 4000, wide)
        assertEquals(listed, turned)
        assertEquals(maxOf(2304, ReelStillMath.stagedLongEdgeFor(ReelMotion.ZoomIn(), 3f / 4f, wide)), listed)
    }

    @Test
    fun `an unknown size is read as the frame's own shape`() {
        assertEquals(
            ReelStillMath.stagedLongEdgeFor(ReelMotion.ZoomIn(), wide.ratio, wide),
            ReelStillMath.stagedLongEdge(ReelMotion.ZoomIn(), 0, 0, wide),
        )
    }

    @Test
    fun `a nonsense shape is read as no move`() {
        assertEquals(1920, ReelStillMath.stagedLongEdgeFor(ReelMotion.ZoomIn(), 0f, wide))
        assertEquals(1920, ReelStillMath.stagedLongEdgeFor(ReelMotion.ZoomIn(), Float.NaN, wide))
    }

    @Test
    fun `a move never asks for more than the ceiling, nor less than the frame`() {
        val rnd = Random(4)
        repeat(300) {
            val motion = listOf(
                ReelMotion.ZoomIn(),
                ReelMotion.Pan(NormRect(0f, 0f, 0.06f, 0.06f), NormRect(0.9f, 0.9f, 1f, 1f)),
            )[rnd.nextInt(2)]
            val hw = if (rnd.nextBoolean()) 0 else 100 + rnd.nextInt(8000)
            val hh = if (hw == 0) 0 else 100 + rnd.nextInt(8000)
            val frame = listOf(wide, tall)[rnd.nextInt(2)]
            val edge = ReelStillMath.stagedLongEdge(motion, hw, hh, frame)
            assertTrue(edge in 1920..ReelStillMath.MAX_STAGED_EDGE)
        }
    }

    @Test
    fun `a panorama in a tall frame is held at the ceiling`() {
        val pan = ReelMotion.Pan(NormRect(0f, 0f, 0.3f, 1f), NormRect(0.7f, 0f, 1f, 1f))
        assertEquals(ReelStillMath.MAX_STAGED_EDGE, ReelStillMath.stagedLongEdge(pan, 12000, 2000, tall))
    }

    @Test
    fun `a bigger move never needs a smaller picture`() {
        val small = ReelStillMath.stagedLongEdge(ReelMotion.ZoomIn(), 4000, 3000, wide)
        val none = ReelStillMath.stagedLongEdge(ReelMotion.None, 4000, 3000, wide)
        assertTrue(small >= none)
    }
}
