package com.fotoxplorr.app.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Layout is arithmetic and bookkeeping, so these tests are exact: chained offsets reproduce absolute
 * positions, a pair that does not join splits the sweep at the right index and names the right photo,
 * and the edge cases (one photo, two, twelve, none confident) all answer instead of throwing.
 */
class PanoLayoutTest {

    private val size = PanoSize(1200, 900)

    private fun sizes(n: Int) = List(n) { size }

    private fun ok(dx: Int, dy: Int = 0) = Offset(dx, dy, 0.9f, 0.4f, true)

    private fun bad(dx: Int = 700, dy: Int = 0) = Offset(dx, dy, 0.3f, 0.1f, false)

    @Test
    fun `a single photo is a layout of one that cannot be joined`() {
        val layout = PanoLayout.layout(sizes(1), emptyList())
        assertEquals(0..0, layout.run)
        assertFalse(layout.joinable)
        assertTrue(layout.complete)
        assertNull(layout.cannotJoin)
        assertTrue(layout.isolated.isEmpty())
        assertTrue(layout.outside.isEmpty())
        assertEquals(listOf(PanoPoint(0, 0)), layout.positions)
        assertEquals(1200L, layout.canvasWidth)
        assertEquals(900L, layout.canvasHeight)
        assertEquals(0, layout.verticalDrift)
        assertEquals(0, layout.verticalSpread)
        assertFalse(layout.drifting)
    }

    @Test
    fun `two joined photos sit side by side with the second after the offset`() {
        val layout = PanoLayout.layout(sizes(2), listOf(ok(720, 12)))
        assertTrue(layout.joinable)
        assertTrue(layout.complete)
        assertEquals(listOf(PanoPoint(0, 0), PanoPoint(720, 12)), layout.positions)
        assertEquals(1920L, layout.canvasWidth)
        assertEquals(912L, layout.canvasHeight)
        assertEquals(12, layout.verticalDrift)
        assertEquals(12, layout.verticalSpread)
        assertTrue(layout.breaks.isEmpty())
        assertNull(layout.cannotJoin)
    }

    @Test
    fun `a photo held higher moves the others down so nothing is negative`() {
        val layout = PanoLayout.layout(sizes(3), listOf(ok(700, -30), ok(710, 10)))
        assertEquals(listOf(PanoPoint(0, 30), PanoPoint(700, 0), PanoPoint(1410, 10)), layout.positions)
        assertEquals(-20, layout.verticalDrift)
        assertEquals(30, layout.verticalSpread)
        assertTrue(layout.positions.all { it.x >= 0 && it.y >= 0 })
        assertEquals(930L, layout.canvasHeight)
    }

    @Test
    fun `chained offsets reproduce absolute positions for any sweep of 2 to 12`() {
        val random = Random(7)
        for (n in 2..PanoLayout.MAX_SWEEP) {
            repeat(20) {
                val offsets = List(n - 1) { ok(300 + random.nextInt(600), random.nextInt(61) - 30) }
                val layout = PanoLayout.layout(sizes(n), offsets)
                var x = 0
                var y = 0
                val expected = ArrayList<PanoPoint>()
                expected += PanoPoint(0, 0)
                for (o in offsets) { x += o.dx; y += o.dy; expected += PanoPoint(x, y) }
                val minY = expected.minOf { it.y }
                val minX = expected.minOf { it.x }
                assertEquals("n=$n", expected.map { PanoPoint(it.x - minX, it.y - minY) }, layout.positions)
                assertEquals(n, layout.rects.size)
                // Each rect keeps its photo's own size and the canvas covers every one of them.
                for (r in layout.rects) {
                    assertEquals(1200, r.width); assertEquals(900, r.height)
                    assertTrue(r.right <= layout.canvasWidth && r.bottom <= layout.canvasHeight)
                }
                assertEquals(layout.rects.maxOf { it.right }.toLong(), layout.canvasWidth)
                assertEquals(y, layout.verticalDrift)
            }
        }
    }

    @Test
    fun `mixed photo sizes keep their own rects`() {
        val layout = PanoLayout.layout(
            listOf(PanoSize(1200, 900), PanoSize(900, 1200), PanoSize(1200, 900)),
            listOf(ok(800), ok(600, 5)),
        )
        assertEquals(1200, layout.rects[0].width)
        assertEquals(900, layout.rects[1].width)
        assertEquals(1200, layout.rects[1].height)
        assertEquals(2600L, layout.canvasWidth)
        assertEquals(1200L, layout.canvasHeight)
        // The shortest photo (900) sets the drift measure.
        assertEquals(5f / 900f, layout.spreadFraction, 1e-6f)
    }

    @Test
    fun `a broken pair in the middle splits the sweep and names the photo after it`() {
        // 8 photos, 7 pairs; pair 4 (photo 4 to photo 5) does not join: runs 0..4 and 5..7.
        val offsets = List(7) { if (it == 4) bad() else ok(600) }
        val layout = PanoLayout.layout(sizes(8), offsets)
        assertEquals(listOf(0..4, 5..7), layout.runs)
        assertEquals(0..4, layout.run)
        assertEquals(listOf(4), layout.breaks)
        assertEquals(5, layout.cannotJoin)
        assertEquals(listOf(5, 6, 7), layout.outside)
        assertTrue(layout.isolated.isEmpty())
        assertTrue(layout.joinable)
        assertFalse(layout.complete)
        // Only the pairs inside the run were used: the broken pair's offset must not leak in.
        assertEquals(5, layout.positions.size)
        assertEquals(2400, layout.positions.last().x)
        assertEquals(3600L, layout.canvasWidth)
    }

    @Test
    fun `the longest run wins and the earliest on a tie`() {
        val first = PanoLayout.layout(sizes(6), List(5) { if (it == 1) bad() else ok(600) })
        assertEquals(listOf(0..1, 2..5), first.runs)
        assertEquals(2..5, first.run)
        assertEquals(2, first.cannotJoin) // the first broken pair is pair 1: photo 2 did not match photo 1
        assertEquals(listOf(0, 1), first.outside)

        val tie = PanoLayout.layout(sizes(6), List(5) { if (it == 2) bad() else ok(600) })
        assertEquals(0..2, tie.run)
        assertEquals(3, tie.cannotJoin)
    }

    @Test
    fun `a photo that matches neither neighbour is isolated`() {
        // Photo 3 is a stranger: pairs 2 and 3 are both broken.
        val layout = PanoLayout.layout(sizes(6), List(5) { if (it == 2 || it == 3) bad() else ok(600) })
        assertEquals(listOf(0..2, 3..3, 4..5), layout.runs)
        assertEquals(listOf(3), layout.isolated)
        assertEquals(listOf(2, 3), layout.breaks)
        assertEquals(3, layout.cannotJoin)
    }

    @Test
    fun `a stranger at either end is isolated too`() {
        val head = PanoLayout.layout(sizes(4), listOf(bad(), ok(600), ok(600)))
        assertEquals(1..3, head.run)
        assertEquals(listOf(0), head.isolated)
        assertEquals(1, head.cannotJoin)
        val tail = PanoLayout.layout(sizes(4), listOf(ok(600), ok(600), bad()))
        assertEquals(0..2, tail.run)
        assertEquals(listOf(3), tail.isolated)
    }

    @Test
    fun `when nothing is confident the answer is one photo and nothing joinable`() {
        val layout = PanoLayout.layout(sizes(5), List(4) { bad() })
        assertFalse(layout.joinable)
        assertEquals(0..0, layout.run)
        assertEquals(listOf(0, 1, 2, 3, 4), layout.isolated)
        assertEquals(listOf(0, 1, 2, 3), layout.breaks)
        assertEquals(1, layout.cannotJoin)
        assertEquals(listOf(1, 2, 3, 4), layout.outside)
        assertEquals(listOf(PanoPoint(0, 0)), layout.positions)
        assertEquals(0, layout.verticalDrift)
    }

    @Test
    fun `a broken pair ignores its unreliable offset even when it is huge`() {
        val layout = PanoLayout.layout(sizes(3), listOf(ok(600), bad(dx = 2_000_000_000, dy = 2_000_000_000)))
        assertEquals(0..1, layout.run)
        assertEquals(1800L, layout.canvasWidth)
    }

    @Test
    fun `vertical drift is reported net and as a spread and flagged past a quarter of the height`() {
        // Climbs 40 px per pair over five pairs: the sweep ends 200 px lower, 22% of 900 px.
        val gentle = PanoLayout.layout(sizes(6), List(5) { ok(600, 40) })
        assertEquals(200, gentle.verticalDrift)
        assertEquals(200, gentle.verticalSpread)
        assertEquals(200f / 900f, gentle.spreadFraction, 1e-6f)
        assertFalse(gentle.drifting)
        val steep = PanoLayout.layout(sizes(6), List(5) { ok(600, 50) })
        assertEquals(250, steep.verticalDrift)
        assertTrue(steep.drifting)
        // A wobble that ends where it began has no net drift but its spread still costs rows.
        val wobble = PanoLayout.layout(sizes(5), listOf(ok(600, 80), ok(600, 80), ok(600, -80), ok(600, -80)))
        assertEquals(0, wobble.verticalDrift)
        assertEquals(160, wobble.verticalSpread)
    }

    @Test
    fun `a sweep that turns round is flagged as out of order`() {
        assertTrue(PanoLayout.layout(sizes(4), List(3) { ok(600) }).oneWay)
        assertTrue(PanoLayout.layout(sizes(4), List(3) { ok(-600) }).oneWay)
        assertTrue(PanoLayout.layout(sizes(3), listOf(ok(600), ok(0, 5))).oneWay)
        assertFalse(PanoLayout.layout(sizes(3), listOf(ok(600), ok(-500))).oneWay)
    }

    @Test
    fun `positions scale with one rounding and stay non-negative`() {
        val layout = PanoLayout.layout(sizes(4), listOf(ok(401, -3), ok(399, 7), ok(402, 1)))
        val scaled = layout.scaledPositions(3.3333)
        for (k in scaled.indices) {
            assertEquals(Math.round(layout.positions[k].x * 3.3333).toInt(), scaled[k].x)
            assertEquals(Math.round(layout.positions[k].y * 3.3333).toInt(), scaled[k].y)
        }
        assertEquals(0, scaled.minOf { it.x })
        assertEquals(0, scaled.minOf { it.y })
        assertEquals(Math.round(1202 * 3.3333).toInt(), scaled[3].x)
    }

    @Test
    fun `bad inputs are refused with a message`() {
        try { PanoLayout.layout(emptyList(), emptyList()); fail("empty sweep") } catch (_: IllegalArgumentException) {}
        try { PanoLayout.layout(sizes(13), List(12) { ok(600) }); fail("13 photos") } catch (_: IllegalArgumentException) {}
        try { PanoLayout.layout(sizes(3), listOf(ok(600))); fail("one offset short") } catch (_: IllegalArgumentException) {}
        try { PanoLayout.layout(sizes(2), listOf(ok(600))).scaledPositions(0.0); fail("zero scale") } catch (_: IllegalArgumentException) {}
    }
}
