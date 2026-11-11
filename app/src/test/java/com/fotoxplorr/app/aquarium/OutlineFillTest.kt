package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineFillTest {

    private val w = 80
    private val h = 60

    private fun BooleanArray.at(x: Int, y: Int) = this[y * w + x]
    private fun BooleanArray.count() = count { it }

    /** A hand-made square ring of ink, [t] thick, outer box (x0,y0)-(x1,y1) inclusive. */
    private fun squareRing(x0: Int, y0: Int, x1: Int, y1: Int, t: Int = 2): BooleanArray {
        val ink = BooleanArray(w * h)
        for (y in y0..y1) for (x in x0..x1) {
            if (x < x0 + t || x > x1 - t || y < y0 + t || y > y1 - t) ink[y * w + x] = true
        }
        return ink
    }

    // ---- interior (flood from the border) ----

    @Test
    fun `the inside of a closed ring is filled and the outside is not`() {
        val ink = squareRing(20, 10, 59, 49)
        val inside = OutlineFill.interior(ink, w, h)
        assertTrue(inside.at(40, 30))
        assertTrue(inside.at(22, 12))
        assertFalse(inside.at(5, 5))
        assertFalse(inside.at(70, 55))
        // The ink itself is not "interior"; the ring is 40x40 with 2 thick walls, so 36x36 inside.
        assertFalse(inside.at(20, 10))
        assertEquals(36 * 36, inside.count())
    }

    @Test
    fun `an empty sketch has no interior`() {
        assertEquals(0, OutlineFill.interior(BooleanArray(w * h), w, h).count())
    }

    @Test
    fun `a ring with a one pixel hole fills nothing`() {
        val ink = squareRing(20, 10, 59, 49)
        ink[10 * w + 40] = false
        ink[11 * w + 40] = false // punch through the 2 thick top wall
        assertEquals(0, OutlineFill.interior(ink, w, h).count())
    }

    @Test
    fun `a diagonal ink line is a wall for the flood`() {
        // A diamond made of 1 pixel diagonal lines: 4-connected flood must not slip between pixels.
        val ink = BooleanArray(w * h)
        val cx = 40
        val cy = 30
        val r = 15
        for (i in 0..r) {
            ink[(cy - r + i) * w + (cx + i)] = true
            ink[(cy + i) * w + (cx + r - i)] = true
            ink[(cy + r - i) * w + (cx - i)] = true
            ink[(cy - i) * w + (cx - r + i)] = true
        }
        val inside = OutlineFill.interior(ink, w, h)
        assertTrue(inside.at(cx, cy))
        assertFalse(inside.at(2, 2))
    }

    @Test
    fun `nested rings give an inner and an annulus region`() {
        val ink = squareRing(5, 5, 74, 54, t = 1)
        val inner = squareRing(25, 20, 54, 39, t = 1)
        for (i in ink.indices) ink[i] = ink[i] || inner[i]
        val inside = OutlineFill.interior(ink, w, h)
        assertTrue(inside.at(40, 30)) // inside the inner ring
        assertTrue(inside.at(10, 10)) // between the rings
        assertFalse(inside.at(2, 2))  // outside both
    }

    @Test
    fun `a ring open against the border leaks and one closed by the border does not`() {
        // A ring whose top wall is the image border row: the row is ink, so flood from the
        // other border cells cannot reach the inside, but ink is on the border either way.
        val ink = squareRing(0, 0, 39, 39)
        val inside = OutlineFill.interior(ink, w, h)
        assertTrue(inside.at(20, 20)) // closed against the border by its own walls
        // An open C against the border leaks.
        val c = squareRing(0, 0, 39, 39)
        for (y in 15..25) for (x in 0..3) c[y * w + x] = false
        assertEquals(0, OutlineFill.interior(c, w, h).count())
    }

    @Test
    fun `degenerate sizes return an all false array of the right length`() {
        assertEquals(0, OutlineFill.interior(BooleanArray(0), 0, 0).size)
        assertEquals(6, OutlineFill.interior(BooleanArray(6), 0, 6).size)
        assertEquals(6, OutlineFill.interior(BooleanArray(6), 4, 4).size) // size mismatch
        assertEquals(0, OutlineFill.interior(BooleanArray(6), 4, 4).count())
        assertEquals(0, OutlineFill.interior(BooleanArray(1), 1, 1).count())
        assertEquals(0, OutlineFill.interior(BooleanArray(2), -2, -1).count())
    }

    // ---- closed enough ----

    @Test
    fun `a ring whose ends nearly meet is closed`() {
        assertTrue(OutlineFill.isNearlyClosed(ringPoints(0.5f, 0.5f, 0.3f, 40, gapDegrees = 8f), 0.02f))
        assertTrue(OutlineFill.isNearlyClosed(ringPoints(0.5f, 0.5f, 0.3f, 40, gapDegrees = 0f), 0.02f))
    }

    @Test
    fun `a wide open C a line and a dot are not closed`() {
        assertFalse(OutlineFill.isNearlyClosed(ringPoints(0.5f, 0.5f, 0.3f, 40, gapDegrees = 144f), 0.02f))
        assertFalse(OutlineFill.isNearlyClosed(List(10) { NormPoint(0.1f + it * 0.08f, 0.5f) }, 0.02f))
        assertFalse(OutlineFill.isNearlyClosed(listOf(NormPoint(0.5f, 0.5f)), 0.05f))
        assertFalse(OutlineFill.isNearlyClosed(emptyList(), 0.05f))
    }

    @Test
    fun `a scribble out and back is not closed because it encloses nothing`() {
        val out = List(8) { NormPoint(0.1f + it * 0.1f, 0.5f) }
        val back = out.reversed().map { NormPoint(it.x, it.y + 0.001f) }
        assertFalse(OutlineFill.isNearlyClosed(out + back, 0.02f))
    }

    @Test
    fun `a stroke that never moves or has non finite points is safe`() {
        assertFalse(OutlineFill.isNearlyClosed(List(10) { NormPoint(0.5f, 0.5f) }, 0.02f))
        assertFalse(OutlineFill.isNearlyClosed(List(10) { NormPoint(Float.NaN, 0.5f) }, 0.02f))
        assertFalse(OutlineFill.isNearlyClosed(ringPoints(0.5f, 0.5f, 0.3f, 40), Float.NaN))
    }

    @Test
    fun `closing a nearly closed outline appends the first point`() {
        val open = ringPoints(0.5f, 0.5f, 0.3f, 40, gapDegrees = 8f)
        val closed = OutlineFill.closedOutline(open)
        assertEquals(open.size + 1, closed.size)
        assertEquals(open.first(), closed.last())
        assertTrue(OutlineFill.closedOutline(listOf(NormPoint(0f, 0f))).size == 1)
    }

    // ---- strokes to fill, end to end ----

    @Test
    fun `a drawn ring fills its inside and not the outside`() {
        val ring = ringPoints(0.5f, 0.5f, 0.3f, 60)
        val region = OutlineFill.filledRegion(ring, 0.03f, w, h)
        assertTrue(region.at(40, 30))
        assertFalse(region.at(2, 2))
        assertFalse(region.at(77, 57))
        // Roughly pi r^2 of the cells: r = 0.3 of the width => 24 px, minus the ink band.
        assertTrue(region.count() in 1200..2000)
    }

    @Test
    fun `a ring with a small gap is closed by the outline and still fills`() {
        val ring = ringPoints(0.5f, 0.5f, 0.3f, 60, gapDegrees = 8f)
        assertTrue(OutlineFill.filledRegion(ring, 0.03f, w, h).at(40, 30))
    }

    @Test
    fun `a ring with a gap over the threshold does not leak a fill`() {
        val ring = ringPoints(0.5f, 0.5f, 0.3f, 60, gapDegrees = 70f)
        val region = OutlineFill.filledRegion(ring, 0.03f, w, h)
        assertEquals(0, region.count())
    }

    @Test
    fun `a huge brush covers the board and leaves no interior and no overflow`() {
        val ring = ringPoints(0.5f, 0.5f, 0.3f, 60)
        assertEquals(0, OutlineFill.filledRegion(ring, Float.MAX_VALUE, w, h).count())
        assertEquals(0, OutlineFill.filledRegion(ring, 1e9f, w, h).count())
        assertEquals(0, OutlineFill.filledRegion(ring, 1f, w, h).count())
    }

    @Test
    fun `a single dot a zero width and a zero size raster are safe`() {
        assertEquals(0, OutlineFill.filledRegion(listOf(NormPoint(0.5f, 0.5f)), 0.05f, w, h).count())
        // A zero width is a hairline, not nothing: the ring still closes and fills.
        assertTrue(OutlineFill.filledRegion(ringPoints(0.5f, 0.5f, 0.3f, 60), 0f, w, h).count() > 1000)
        assertEquals(w * h, OutlineFill.filledRegion(ringPoints(0.5f, 0.5f, 0.3f, 60), Float.NaN, w, h).size)
        assertEquals(0, OutlineFill.filledRegion(ringPoints(0.5f, 0.5f, 0.3f, 60), 0.03f, 0, 0).size)
        assertEquals(0, OutlineFill.filledRegion(emptyList(), 0.03f, w, h).count())
    }

    @Test
    fun `stamping a stroke marks ink along it and nothing far away`() {
        val ink = BooleanArray(w * h)
        OutlineFill.stampStroke(ink, w, h, listOf(NormPoint(0.1f, 0.5f), NormPoint(0.9f, 0.5f)), 0.05f)
        assertTrue(ink.at(40, 30))
        assertTrue(ink.at(10, 30))
        assertFalse(ink.at(40, 10))
        assertFalse(ink.at(40, 50))
    }

    @Test
    fun `points far outside the board do not crash or smear`() {
        val ink = BooleanArray(w * h)
        OutlineFill.stampStroke(ink, w, h, listOf(NormPoint(-1e9f, -1e9f), NormPoint(1e9f, 1e9f)), 0.02f)
        OutlineFill.stampStroke(ink, w, h, listOf(NormPoint(Float.NaN, 0.5f), NormPoint(0.5f, Float.POSITIVE_INFINITY)), 0.02f)
        assertEquals(ink.size, w * h)
    }

    // ---- opaque bounds ----

    private fun argb(a: Int) = (a shl 24) or 0x00FFFFFF

    @Test
    fun `bounds crop tightly to the opaque pixels`() {
        val px = IntArray(10 * 8)
        px[2 * 10 + 3] = argb(255)
        px[5 * 10 + 7] = argb(255)
        val b = OutlineFill.opaqueBounds(px, 10, 8)
        assertNotNull(b)
        assertEquals(IntBounds(3, 2, 8, 6), b)
        assertEquals(5, b!!.width)
        assertEquals(4, b.height)
    }

    @Test
    fun `bounds are null for an empty or fully transparent image`() {
        assertNull(OutlineFill.opaqueBounds(IntArray(20), 5, 4))
        assertNull(OutlineFill.opaqueBounds(IntArray(0), 0, 0))
        assertNull(OutlineFill.opaqueBounds(IntArray(5), 5, 5)) // size mismatch
        assertNull(OutlineFill.opaqueBounds(IntArray(5), -1, -5))
    }

    @Test
    fun `a one pixel image and a single dot give one pixel bounds`() {
        assertEquals(IntBounds(0, 0, 1, 1), OutlineFill.opaqueBounds(intArrayOf(argb(255)), 1, 1))
        val px = IntArray(25).also { it[12] = argb(200) }
        val b = OutlineFill.opaqueBounds(px, 5, 5)!!
        assertEquals(1, b.width)
        assertEquals(1, b.height)
    }

    @Test
    fun `the alpha threshold is inclusive`() {
        val px = intArrayOf(argb(7), argb(8))
        assertEquals(IntBounds(1, 0, 2, 1), OutlineFill.opaqueBounds(px, 2, 1, alphaMin = 8))
        assertNull(OutlineFill.opaqueBounds(px, 2, 1, alphaMin = 9))
    }

    @Test
    fun `bounds can be padded and stay inside the image`() {
        val b = IntBounds(3, 2, 8, 6)
        assertEquals(IntBounds(1, 0, 10, 8), b.expandedBy(2, 10, 8))
        assertEquals(IntBounds(0, 0, 10, 8), b.expandedBy(50, 10, 8))
    }

    @Test
    fun `a four thousand point stroke with the widest brush stays fast`() {
        val big = 384
        val bigH = 288
        val points = List(4000) { i ->
            val a = i * 0.05
            NormPoint((0.5 + 0.45 * Math.cos(a)).toFloat(), (0.5 + 0.45 * Math.sin(a * 1.3)).toFloat())
        }
        val started = System.nanoTime()
        val region = OutlineFill.filledRegion(points, 0.2f, big, bigH)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(big * bigH, region.size)
        assertTrue("took $ms ms", ms < 3000)
    }

    @Test
    fun `a sketch stroke that auto filled gives a fill and one that did not gives none`() {
        val ring = ringPoints(0.5f, 0.5f, 0.3f, 60, gapDegrees = 8f)
        val sketch = Sketch().add(ring, 1, BrushSize.MEDIUM.width, autoFill = true)
        val stroke = sketch.strokes.single()
        assertTrue(stroke.filled)
        assertTrue(OutlineFill.filledRegion(stroke.points, stroke.width, w, h).count() > 1000)

        val open = Sketch().add(ringPoints(0.5f, 0.5f, 0.3f, 60, gapDegrees = 120f), 1, BrushSize.MEDIUM.width).strokes.single()
        assertFalse(open.filled)
        assertEquals(0, OutlineFill.filledRegion(open.points, open.width, w, h).count())
    }
}
