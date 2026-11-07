package com.fotoxplorr.app.pano

import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max

/** The numbers under a sweep: focal length, the cylinder, and the canvas budget. */
class PanoGeometryTest {

    private fun sizes(n: Int, w: Int = 1000, h: Int = 800) = List(n) { PanoSize(w, h) }

    private fun offset(dx: Int, dy: Int = 0) = Offset(dx, dy, 0.9f, 0.4f, true)

    // ---- focal length ----

    @Test
    fun `focal length in pixels is f35 times the diagonal over 43 point 27`() {
        // 26 mm equivalent on 4000x3000: diagonal 5000 px -> 26 * 5000 / 43.27. (The design note said
        // "about 2.4k"; the formula it also gives says 3004, and the formula is the one that is right.)
        assertEquals(3004.4, PanoGeometry.focalPixels(26, 4000, 3000), 0.1)
    }

    @Test
    fun `focal length agrees with a sensor worked out the long way`() {
        // 36x24 mm full frame at 6000x4000, 50 mm lens: f in pixels is simply 50 mm * (6000 px / 36 mm).
        // The formula's 43.27 is the 43.2666 diagonal rounded, so it is off by a hundredth of a percent.
        assertEquals(50.0 * 6000 / 36, PanoGeometry.focalPixels(50, 6000, 4000), 1.0)
    }

    @Test
    fun `focal length does not care whether the photo is portrait or landscape`() {
        assertEquals(PanoGeometry.focalPixels(24, 4000, 3000), PanoGeometry.focalPixels(24, 3000, 4000), 1e-9)
    }

    @Test
    fun `a missing focal length falls back to the stated diagonal field of view`() {
        val f = PanoGeometry.focalPixels(null, 4000, 3000, fallbackFovDeg = 78.0)
        val diagonal = hypot(4000.0, 3000.0)
        assertEquals(78.0, Math.toDegrees(2 * atan(diagonal / 2 / f)), 1e-9)
    }

    @Test
    fun `an implausible focal length is a bad tag and falls back`() {
        val fallback = PanoGeometry.focalPixels(null, 4000, 3000)
        for (bad in listOf(0, -35, 3, 5000)) {
            assertEquals("f35=$bad", fallback, PanoGeometry.focalPixels(bad, 4000, 3000), 1e-9)
        }
    }

    @Test
    fun `an absurd fallback field of view is clamped rather than producing nonsense`() {
        for (fov in listOf(0.0, -10.0, 179.9, 400.0, Double.NaN)) {
            val f = PanoGeometry.focalPixels(null, 4000, 3000, fov)
            assertTrue("fov=$fov f=$f", f.isFinite() && f > 0)
        }
    }

    // ---- cylinder ----

    @Test
    fun `forward then inverse returns the starting point`() {
        val f = 3000.0
        for (x in -3000..3000 step 250) {
            for (y in -2000..2000 step 250) {
                val c = PanoGeometry.forward(x.toDouble(), y.toDouble(), f)
                val p = assertNotNull(PanoGeometry.inverse(c.x, c.y, f))
                assertEquals("x at ($x,$y)", x.toDouble(), p.x, 1e-6)
                assertEquals("y at ($x,$y)", y.toDouble(), p.y, 1e-6)
            }
        }
    }

    @Test
    fun `inverse then forward returns the starting point`() {
        val f = 2400.0
        for (u in -3000..3000 step 500) {
            for (v in -1500..1500 step 500) {
                val p = assertNotNull(PanoGeometry.inverse(u.toDouble(), v.toDouble(), f))
                val c = PanoGeometry.forward(p.x, p.y, f)
                assertEquals(u.toDouble(), c.x, 1e-6)
                assertEquals(v.toDouble(), c.y, 1e-6)
            }
        }
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    @Test
    fun `the centre column is untouched and the edges are pulled in`() {
        val f = 3000.0
        val centre = PanoGeometry.forward(0.0, 700.0, f)
        assertEquals(0.0, centre.x, 1e-12)
        assertEquals(700.0, centre.y, 1e-9)
        val edge = PanoGeometry.forward(2000.0, 1500.0, f)
        assertTrue("columns compress toward the edge", edge.x < 2000.0)
        assertTrue("rows shrink toward the edge", edge.y < 1500.0)
    }

    @Test
    fun `the cylinder angle is atan of x over f`() {
        val f = 1000.0
        assertEquals(PI / 4 * f, PanoGeometry.forward(1000.0, 0.0, f).x, 1e-9)
    }

    @Test
    fun `a quarter turn or more has no plane point`() {
        val f = 1000.0
        assertNull(PanoGeometry.inverse(f * PI / 2, 0.0, f))
        assertNull(PanoGeometry.inverse(-f * 2, 0.0, f))
        assertNotNull(PanoGeometry.inverse(f * (PI / 2 - 0.01), 0.0, f))
    }

    @Test
    fun `column helpers agree with the point functions`() {
        val f = 2200.0
        val p = assertNotNull(PanoGeometry.inverse(900.0, 300.0, f))
        assertEquals(p.x, PanoGeometry.cylinderToPlaneX(900.0, f), 1e-9)
        assertEquals(p.y, 300.0 * PanoGeometry.rowStretch(900.0, f), 1e-9)
        assertEquals(900.0, PanoGeometry.planeToCylinderX(p.x, f), 1e-9)
    }

    @Test
    fun `warped size narrows slightly and keeps its height`() {
        val f = PanoGeometry.focalPixels(26, 4000, 3000)
        val s = PanoGeometry.warpedSize(4000, 3000, f)
        assertEquals(3000, s.height)
        assertTrue("narrower than the flat photo", s.width < 4000)
        assertTrue("but not by much for a normal lens", s.width > 3000)
        assertEquals(2 * f * atan(2000 / f), s.width.toDouble(), 0.6)
    }

    // ---- chaining ----

    @Test
    fun `place chains pairwise offsets from the first photo`() {
        val points = PanoGeometry.place(listOf(offset(700, 5), offset(650, -12), offset(720, 3)))
        assertEquals(listOf(PanoPoint(0, 0), PanoPoint(700, 5), PanoPoint(1350, -7), PanoPoint(2070, -4)), points)
    }

    @Test
    fun `place refuses offsets that leave the grid`() {
        try {
            PanoGeometry.place(listOf(offset(Int.MAX_VALUE), offset(Int.MAX_VALUE)))
            fail("overflow must not wrap")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---- the canvas plan ----

    @Test
    fun `a small sweep is planned at full size`() {
        val plan = PanoGeometry.plan(sizes(3), listOf(offset(700, 5), offset(700, -10)))
        assertEquals(2400L, plan.fullWidth)
        assertEquals(810L, plan.fullHeight) // y spans -5 .. 810 relative to the top-most photo
        assertEquals(2400, plan.canvasWidth)
        assertEquals(810, plan.canvasHeight)
        assertEquals(1.0, plan.scale, 0.0)
        assertEquals(PanoGeometry.Limit.NONE, plan.limit)
    }

    @Test
    fun `the plan never exceeds the pixel budget and never enlarges`() {
        val random = Random(7)
        repeat(300) {
            val n = 2 + random.nextInt(11)
            val w = 500 + random.nextInt(7000)
            val h = 400 + random.nextInt(6000)
            val photos = List(n) { PanoSize(w, h) }
            val offsets = List(n - 1) { offset((w * (0.3 + 0.5 * random.nextDouble())).toInt(), random.nextInt(81) - 40) }
            val budget = 100_000L + (random.nextDouble() * 80_000_000).toLong()
            val plan = PanoGeometry.plan(photos, offsets, budget)
            assertTrue("pixels ${plan.pixels} over $budget", plan.pixels <= budget)
            assertTrue("over the editor ceiling", plan.pixels <= MAX_EXPORT_PIXELS)
            assertTrue(plan.canvasWidth <= MAX_EXPORT_EDGE && plan.canvasHeight <= MAX_EXPORT_EDGE)
            assertTrue(plan.scale <= 1.0 && plan.scale > 0.0)
            assertTrue(plan.canvasWidth >= 1 && plan.canvasHeight >= 1)
            assertTrue(plan.bandHeight in 1..plan.canvasHeight)
        }
    }

    @Test
    fun `a bigger budget never gives a smaller scale or fewer pixels`() {
        val photos = sizes(8, 4000, 3000)
        val offsets = List(7) { offset(2600, (it % 3) - 1) }
        var lastScale = 0.0
        var lastPixels = 0L
        for (budget in listOf(300_000L, 1_000_000L, 4_000_000L, 9_000_000L, 16_000_000L, 24_000_000L, 40_000_000L, 64_000_000L, 200_000_000L)) {
            val plan = PanoGeometry.plan(photos, offsets, budget)
            assertTrue("scale ${plan.scale} fell below $lastScale at $budget", plan.scale >= lastScale)
            assertTrue("pixels ${plan.pixels} fell below $lastPixels at $budget", plan.pixels >= lastPixels)
            lastScale = plan.scale
            lastPixels = plan.pixels
        }
    }

    @Test
    fun `the default is twenty four megapixels`() {
        val plan = PanoGeometry.plan(sizes(6, 3000, 2500), List(5) { offset(1800) })
        assertTrue(plan.pixels <= 24_000_000L)
        assertEquals(PanoGeometry.Limit.EDGE, PanoGeometry.plan(sizes(12, 4000, 3000), List(11) { offset(2600) }).limit)
    }

    @Test
    fun `a very wide sweep is held to the editor's edge ceiling and says so`() {
        // Twelve 4000 px photos at 35% overlap span 33000 px: the 8192 edge, not the pixel budget, bites.
        val plan = PanoGeometry.plan(sizes(12, 4000, 3000), List(11) { offset(2600) }, 64_000_000L)
        assertEquals(MAX_EXPORT_EDGE, plan.canvasWidth)
        assertEquals(PanoGeometry.Limit.EDGE, plan.limit)
        assertTrue(plan.canvasHeight < 3000)
    }

    @Test
    fun `a sweep short of both ceilings is limited by pixels`() {
        val plan = PanoGeometry.plan(sizes(3, 4000, 3000), List(2) { offset(2600) }, 5_000_000L)
        assertEquals(PanoGeometry.Limit.PIXELS, plan.limit)
        assertTrue(plan.pixels <= 5_000_000L)
        assertTrue("fills most of the budget", plan.pixels > 4_500_000L)
    }

    @Test
    fun `a huge budget is clamped to the editor ceiling`() {
        val plan = PanoGeometry.plan(sizes(4, 8000, 6000), List(3) { offset(5000) }, Long.MAX_VALUE)
        assertTrue(plan.pixels <= MAX_EXPORT_PIXELS)
        assertTrue(max(plan.canvasWidth, plan.canvasHeight) <= MAX_EXPORT_EDGE)
    }

    @Test
    fun `sizes beyond what an Int can count are planned without overflow`() {
        // 12 photos of 60000x40000: the full canvas is billions of pixels, past Int and near past Long/2.
        val plan = PanoGeometry.plan(sizes(12, 60_000, 40_000), List(11) { offset(40_000, 10) })
        assertTrue(plan.fullWidth.toDouble() * plan.fullHeight > Int.MAX_VALUE)
        assertTrue(plan.pixels in 1..24_000_000L)
        assertEquals(plan.fullWidth.toDouble() / plan.fullHeight, plan.canvasWidth.toDouble() / plan.canvasHeight, 0.01)
    }

    @Test
    fun `upward jitter is part of the canvas`() {
        val plan = PanoGeometry.plan(sizes(2), listOf(offset(700, -30)))
        assertEquals(830L, plan.fullHeight)
    }

    @Test
    fun `band height keeps a band to about two megapixels and stays inside the canvas`() {
        val wide = PanoGeometry.planPlaced(listOf(PanoSize(9000, 3000)), listOf(PanoPoint(0, 0)), 64_000_000L)
        assertEquals(MAX_EXPORT_EDGE, wide.canvasWidth)
        assertTrue(wide.bandHeight * wide.canvasWidth <= 2_100_000)
        val small = PanoGeometry.planPlaced(listOf(PanoSize(300, 40)), listOf(PanoPoint(0, 0)))
        assertEquals(40, small.bandHeight)
    }

    @Test
    fun `the ladder shrinks strictly and ends at the editor's floor`() {
        val photos = sizes(8, 4000, 3000)
        val positions = PanoGeometry.place(List(7) { offset(2600, 2) })
        val ladder = PanoGeometry.ladder(photos, positions)
        assertTrue(ladder.size > 2)
        assertEquals(PanoGeometry.planPlaced(photos, positions).pixels, ladder.first().pixels)
        for (i in 1 until ladder.size) {
            assertTrue("rung $i not smaller", ladder[i].pixels < ladder[i - 1].pixels)
            assertTrue(ladder[i].scale < ladder[i - 1].scale)
        }
        assertTrue(max(ladder.last().canvasWidth, ladder.last().canvasHeight) <= 1536)
    }

    @Test
    fun `the ladder is one rung when the sweep is already small`() {
        val ladder = PanoGeometry.ladder(sizes(2, 600, 400), PanoGeometry.place(listOf(offset(350))))
        assertEquals(1, ladder.size)
        assertFalse(ladder.isEmpty())
    }

    @Test
    fun `plan insists on one offset between each pair`() {
        try {
            PanoGeometry.plan(sizes(3), listOf(offset(700)))
            fail("two offsets are needed for three photos")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `plan places photos wherever their positions put them`() {
        val plan = PanoGeometry.planPlaced(
            listOf(PanoSize(100, 100), PanoSize(100, 100)),
            listOf(PanoPoint(-50, 20), PanoPoint(80, -10)),
        )
        assertEquals(230L, plan.fullWidth)
        assertEquals(130L, plan.fullHeight)
        assertTrue(abs(plan.scale - 1.0) < 1e-12)
    }
}
