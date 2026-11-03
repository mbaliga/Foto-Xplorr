package com.fotoxplorr.app.gallery

import dev.aarso.cellshell.ScrubberEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rail's raise-and-bulge maths (owner, batch 9 #5), pinned without a device. */
class RailBulgeTest {

    private val spec = RailBulgeSpec.forDensity(1f)
    private val edges = ScrubberEdge.entries

    @Test
    fun `displacement is zero at rest and far from the finger`() {
        assertEquals(0f, RailBulge.displacement(300f, 300f, 22f, 0f, spec), 0f)
        val far = spec.plateauPx + spec.fallPx + 1f
        assertEquals(0f, RailBulge.displacement(300f + far, 300f, 22f, 1f, spec), 0f)
        assertEquals(0f, RailBulge.displacement(300f - far, 300f, 22f, 1f, spec), 0f)
    }

    @Test
    fun `displacement peaks at the finger at about one finger width`() {
        val peak = RailBulge.displacement(300f, 300f, 22f, 1f, spec)
        assertTrue(peak >= 48f && peak <= 64f)
        for (d in 0..200 step 3) {
            assertTrue(RailBulge.displacement(300f + d, 300f, 22f, 1f, spec) <= peak + 1e-4f)
        }
    }

    @Test
    fun `displacement falls monotonically away from the finger and is symmetric`() {
        var last = Float.MAX_VALUE
        for (d in 0..200) {
            val up = RailBulge.displacement(500f + d, 500f, 22f, 1f, spec)
            val down = RailBulge.displacement(500f - d, 500f, 22f, 1f, spec)
            assertEquals(up, down, 1e-3f)
            assertTrue(up <= last + 1e-4f)
            last = up
        }
    }

    @Test
    fun `strength scales the arc and is clamped`() {
        val full = RailBulge.displacement(300f, 300f, 22f, 1f, spec)
        assertEquals(full / 2f, RailBulge.displacement(300f, 300f, 22f, 0.5f, spec), 1e-3f)
        assertEquals(full, RailBulge.displacement(300f, 300f, 22f, 9f, spec), 1e-3f)
        assertEquals(0f, RailBulge.displacement(300f, 300f, 22f, -1f, spec), 0f)
    }

    @Test
    fun `the arc is smooth where it meets the plateau and the far end`() {
        val e = spec.plateauPx
        val before = RailBulge.bump(e - 0.01f, spec)
        val after = RailBulge.bump(e + 0.01f, spec)
        assertEquals(1f, before, 1e-6f)
        assertEquals(1f, after, 1e-3f)
        val end = spec.plateauPx + spec.fallPx
        assertEquals(0f, RailBulge.bump(end - 0.01f, spec), 1e-3f)
    }

    @Test
    fun `a finger at the very ends of the rail still bulges and nothing is NaN`() {
        for (finger in listOf(0f, 10f, 1000f, 1010f, -50f, 5000f)) {
            for (label in 0..1000 step 10) {
                val d = RailBulge.displacement(label.toFloat(), finger, 22f, 1f, spec)
                assertTrue(d.isFinite() && d >= 0f)
            }
        }
        assertTrue(RailBulge.displacement(0f, 0f, 22f, 1f, spec) > 40f)
        assertTrue(RailBulge.displacement(1000f, 1000f, 22f, 1f, spec) > 40f)
    }

    @Test
    fun `no label is ever inside the finger footprint wherever on the strip the finger lands`() {
        // The strip is 44 dp thick: the finger can be anywhere from flush with the edge to the far side.
        for (density in listOf(1f, 2.625f, 3.5f)) {
            val s = RailBulgeSpec.forDensity(density)
            for (depthDp in 0..44 step 4) {
                val fingerDepth = depthDp * density
                val finger = 400f * density
                var label = finger - 3f * s.fingerRadiusPx
                while (label <= finger + 3f * s.fingerRadiusPx) {
                    assertTrue(
                        "label at $label, finger depth $depthDp dp, density $density",
                        RailBulge.isClearOfFinger(label, finger, fingerDepth, 1f, s),
                    )
                    label += 0.5f
                }
            }
        }
    }

    @Test
    fun `at rest labels are not displaced so the rail is a straight column`() {
        for (label in 0..1000 step 7) {
            assertEquals(
                spec.restDepthPx,
                RailBulge.labelDepth(label.toFloat(), 400f, 22f, 0f, spec),
                0f,
            )
        }
    }

    @Test
    fun `labels bulge toward the screen interior on all four edges`() {
        // Depth grows toward the interior; check the pixel direction for each edge.
        val w = 1080f
        val h = 2400f
        val rest = RailBulge.toOffset(ScrubberEdge.RIGHT, 500f, spec.restDepthPx, w, h)
        val bulged = RailBulge.toOffset(ScrubberEdge.RIGHT, 500f, spec.restDepthPx + 60f, w, h)
        assertTrue(bulged.first < rest.first && bulged.second == rest.second)
        val l0 = RailBulge.toOffset(ScrubberEdge.LEFT, 500f, 20f, w, h)
        val l1 = RailBulge.toOffset(ScrubberEdge.LEFT, 500f, 80f, w, h)
        assertTrue(l1.first > l0.first && l1.second == l0.second)
        val t0 = RailBulge.toOffset(ScrubberEdge.TOP, 500f, 20f, w, h)
        val t1 = RailBulge.toOffset(ScrubberEdge.TOP, 500f, 80f, w, h)
        assertTrue(t1.second > t0.second && t1.first == t0.first)
        val b0 = RailBulge.toOffset(ScrubberEdge.BOTTOM, 500f, 20f, w, h)
        val b1 = RailBulge.toOffset(ScrubberEdge.BOTTOM, 500f, 80f, w, h)
        assertTrue(b1.second < b0.second && b1.first == b0.first)
    }

    @Test
    fun `magnification is 1 at rest and far away, small under the finger`() {
        assertEquals(1f, RailBulge.scale(300f, 300f, 0f, spec), 0f)
        assertEquals(1f, RailBulge.scale(900f, 300f, 1f, spec), 0f)
        val under = RailBulge.scale(300f, 300f, 1f, spec)
        assertTrue(under > 1f && under <= 1.5f)
    }

    @Test
    fun `snap-back ease is exact at both ends and monotone`() {
        assertEquals(0f, RailBulge.snapBackEase(0f), 0f)
        assertEquals(1f, RailBulge.snapBackEase(1f), 0f)
        var last = -1f
        for (i in 0..100) {
            val v = RailBulge.snapBackEase(i / 100f)
            assertTrue(v >= last)
            last = v
        }
        assertEquals(1f, RailBulge.snapBackEase(5f), 0f)
    }

    @Test
    fun `the bubble never leaves the screen or enters an edge zone for any finger position on any edge`() {
        val w = 1080f
        val h = 2400f
        val zone = 56f * 2.625f
        val bw = 140f
        val bh = 110f
        for (edge in edges) {
            val vertical = edge == ScrubberEdge.LEFT || edge == ScrubberEdge.RIGHT
            val length = if (vertical) h else w
            var finger = -20f
            while (finger <= length + 20f) {
                for (depth in listOf(0f, 60f, 115f)) {
                    val p = RailBulge.bubble(edge, finger, depth, bw, bh, w, h, zone, 24f, spec)
                    assertTrue("$edge $finger: x ${p.x}", p.x >= zone - 0.01f && p.x + bw <= w - zone + 0.01f)
                    assertTrue("$edge $finger: y ${p.y}", p.y >= zone - 0.01f && p.y + bh <= h - zone + 0.01f)
                }
                finger += 37f
            }
        }
    }

    @Test
    fun `the bubble is level with the finger and clear of the bulge on its interior side`() {
        val w = 1080f
        val h = 2400f
        val zone = 56f * 2.625f
        val p = RailBulge.bubble(ScrubberEdge.RIGHT, 1000f, 60f, 140f, 110f, w, h, zone, 24f, spec)
        assertEquals(1000f, p.alongCenter, 0.01f)
        // Its right side is left of the arc's peak label.
        val peakDepth = spec.restDepthPx + RailBulge.peakFor(60f, spec)
        assertTrue(p.depthCenter - 70f > peakDepth)
        assertTrue(p.x + 140f < w - peakDepth)
    }

    @Test
    fun `the bubble on a small screen is still kept on screen`() {
        val p = RailBulge.bubble(ScrubberEdge.BOTTOM, 100f, 40f, 200f, 100f, 240f, 300f, 200f, 24f, spec)
        assertTrue(p.x >= 0f && p.x + 200f <= 240f && p.y >= 0f && p.y + 100f <= 300f)
    }
}

class RailMappingTest {
    private val stops = listOf(
        dev.aarso.cellshell.ScrubberStop("Jan", 0),
        dev.aarso.cellshell.ScrubberStop("Feb", 10),
        dev.aarso.cellshell.ScrubberStop("Mar", 30),
    )

    @Test
    fun `fractions clamp to the list and NaN takes the first item`() {
        assertEquals(0, RailMapping.indexForFraction(-1f, 100))
        assertEquals(99, RailMapping.indexForFraction(2f, 100))
        assertEquals(50, RailMapping.indexForFraction(0.5f, 100))
        assertEquals(0, RailMapping.indexForFraction(Float.NaN, 100))
        assertEquals(0, RailMapping.indexForFraction(0.5f, 0))
    }

    @Test
    fun `an item belongs to the last stop that begins at or before it`() {
        assertEquals("Jan", RailMapping.stopForIndex(stops, 9)?.label)
        assertEquals("Feb", RailMapping.stopForIndex(stops, 10)?.label)
        assertEquals("Mar", RailMapping.stopForIndex(stops, 99)?.label)
    }

    @Test
    fun `thinning keeps labels apart and the stepper walks the stops`() {
        val kept = RailMapping.thinned(stops, 40, 400f, 90f)
        assertEquals(listOf("Jan", "Feb", "Mar"), kept.map { it.label })
        assertEquals(listOf("Jan", "Mar"), RailMapping.thinned(stops, 40, 400f, 150f).map { it.label })
        assertEquals("Feb", RailMapping.neighbourStop(stops, 3, forward = true)?.label)
        assertEquals("Jan", RailMapping.neighbourStop(stops, 12, forward = false)?.label)
        assertEquals(null, RailMapping.neighbourStop(stops, 35, forward = true))
        assertEquals(null, RailMapping.neighbourStop(stops, 3, forward = false))
    }
}
