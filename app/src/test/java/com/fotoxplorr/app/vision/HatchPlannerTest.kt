package com.fotoxplorr.app.vision

import com.fotoxplorr.app.vision.VisionTestKit.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hatching planner: patterns only where the deficiency confuses two clusters, each distinct. */
class HatchPlannerTest {
    private val n = TestPlates.axisDirection(Deficiency.DEUTAN)

    /** A colour that differs from [base] only along the deutan confusion axis. */
    private fun along(t: Double, base: Double = 0.35): Int =
        VisionTestKit.fromLinear(DoubleArray(3) { base + t * n[it] })

    private val red = along(0.2)
    private val green = along(-0.2)
    private val blue = argb(20, 30, 220)
    private val yellow = argb(240, 230, 30)

    @Test
    fun `a deutan confusable red and green get patterns and an unrelated blue does not`() {
        val plan = HatchPlanner.plan(intArrayOf(red, green, blue), Deficiency.DEUTAN)
        assertEquals(listOf(0 to 1), plan.pairs)
        assertTrue(plan.patterns[0] != null && plan.patterns[1] != null)
        assertNotEquals(plan.patterns[0], plan.patterns[1])
        assertNull(plan.patterns[2])
    }

    @Test
    fun `hatching is assigned only to clusters in a confusable pair`() {
        val centres = intArrayOf(red, green, blue, yellow, argb(0, 0, 0), argb(255, 255, 255))
        val plan = HatchPlanner.plan(centres, Deficiency.DEUTAN)
        val inPair = plan.pairs.flatMap { listOf(it.first, it.second) }.toSet()
        for (c in centres.indices) assertEquals("cluster $c", c in inPair, plan.patterns[c] != null)
        for ((a, b) in plan.pairs) {
            assertTrue(ColourSpace.deltaE(Simulation.simulate(centres[a], Deficiency.DEUTAN), Simulation.simulate(centres[b], Deficiency.DEUTAN)) < HatchPlanner.DEFAULT_THRESHOLD)
            assertTrue(ColourSpace.deltaE(centres[a], centres[b]) >= HatchPlanner.DEFAULT_THRESHOLD)
        }
    }

    @Test
    fun `a deficiency that does not confuse the pair leaves it plain`() {
        val plan = HatchPlanner.plan(intArrayOf(red, green), Deficiency.TRITAN)
        assertTrue(plan.isEmpty)
        assertTrue(plan.patterns.all { it == null })
    }

    @Test
    fun `severity zero confuses nothing`() {
        assertTrue(HatchPlanner.plan(intArrayOf(red, green, blue), Deficiency.DEUTAN, 0.0).isEmpty)
    }

    @Test
    fun `two clusters that already look alike to everyone get no pattern`() {
        val plan = HatchPlanner.plan(intArrayOf(argb(200, 40, 40), argb(202, 41, 40)), Deficiency.DEUTAN)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `up to six confusable clusters each get their own pattern`() {
        val centres = IntArray(6) { along(-0.25 + it * 0.1, base = 0.4) }
        val plan = HatchPlanner.plan(centres, Deficiency.DEUTAN, threshold = 30.0)
        val used = plan.patterns.filterNotNull()
        assertTrue("only ${used.size} hatched", used.size >= 3)
        assertEquals(used.size, used.toSet().size)
    }

    @Test
    fun `confusable neighbours never share a pattern even when there are more clusters than patterns`() {
        val centres = IntArray(9) { along(-0.3 + it * 0.07, base = 0.4) }
        val plan = HatchPlanner.plan(centres, Deficiency.DEUTAN, threshold = 25.0)
        for ((a, b) in plan.pairs) assertNotEquals("$a/$b", plan.patterns[a], plan.patterns[b])
    }

    @Test
    fun `the plan is deterministic`() {
        val a = HatchPlanner.plan(intArrayOf(red, green, blue, yellow), Deficiency.PROTAN)
        val b = HatchPlanner.plan(intArrayOf(red, green, blue, yellow), Deficiency.PROTAN)
        assertEquals(a.patterns, b.patterns)
        assertEquals(a.pairs, b.pairs)
    }

    @Test
    fun `every pattern marks a modest share of a tile and the six differ from one another`() {
        val tiles = HatchPattern.entries.map { p ->
            BooleanArray(64) { p.isInk(it % 8, it / 8) }
        }
        for ((i, t) in tiles.withIndex()) {
            val share = t.count { it } / 64.0
            assertTrue("${HatchPattern.entries[i]} $share", share in 0.1..0.55)
        }
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            assertFalse("${HatchPattern.entries[i]} == ${HatchPattern.entries[j]}", tiles[i].contentEquals(tiles[j]))
        }
    }

    @Test
    fun `patterns repeat with the period and work for negative coordinates`() {
        for (p in HatchPattern.entries) for (x in -9..9) for (y in -9..9) {
            assertEquals(p.isInk(x, y, 6), p.isInk(x + 6, y + 12, 6))
        }
    }

    @Test
    fun `the ink mask touches only hatched clusters`() {
        val w = 16
        val h = 8
        val index = ByteArray(w * h) { if ((it % w) < 8) 0 else 1 }
        val image = ColourIndexImage(intArrayOf(red, green), index, w, h)
        val plan = HatchPlan(listOf(HatchPattern.CROSS, null), listOf(0 to 1))
        val mask = HatchPlanner.inkMask(image, plan)
        for (i in mask.indices) {
            if (index[i].toInt() == 1) assertFalse(mask[i])
            else assertEquals(HatchPattern.CROSS.isInk(i % w, i / w), mask[i])
        }
        assertTrue(mask.any { it })
    }
}
