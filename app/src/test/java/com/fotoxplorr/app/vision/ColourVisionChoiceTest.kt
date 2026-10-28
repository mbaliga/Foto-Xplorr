package com.fotoxplorr.app.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer's colour-vision choice as pure functions: what is on, what the strength can be, what is kept. */
class ColourVisionChoiceTest {

    @Test
    fun `the default is off and asks for nothing`() {
        val c = ColourVisionChoice()
        assertFalse(c.filtering)
        assertFalse(c.hatching)
        assertFalse(c.needsPixels)
        assertNull(c.filterKey)
        assertEquals(1.0, c.severity, 0.0)
        assertEquals("100%", c.severityLabel)
    }

    @Test
    fun `a type turns the filter on and reads pixels`() {
        val c = ColourVisionChoice().withDeficiency(Deficiency.DEUTAN)
        assertTrue(c.filtering)
        assertTrue(c.needsPixels)
        assertEquals(FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10), c.filterKey)
    }

    @Test
    fun `names alone need pixels but no filter`() {
        val c = ColourVisionChoice().withNaming(true)
        assertFalse(c.filtering)
        assertTrue(c.needsPixels)
        assertNull(c.filterKey)
    }

    @Test
    fun `patterns are remembered but not applied while the type is off`() {
        val asked = ColourVisionChoice().withPatterns(true)
        assertTrue(asked.patterns)
        assertFalse(asked.hatching)
        assertFalse(asked.needsPixels)
        val applied = asked.withDeficiency(Deficiency.PROTAN)
        assertTrue(applied.hatching)
        assertTrue(applied.needsPixels)
        // And back off again: remembered, not applied.
        assertFalse(applied.withDeficiency(null).hatching)
        assertTrue(applied.withDeficiency(null).patterns)
    }

    @Test
    fun `strength moves one step at a time and stops at both ends`() {
        var c = ColourVisionChoice()
        assertFalse(c.canStrengthen)
        c = c.stepSeverity(1)
        assertEquals(10, c.severityStep)
        repeat(20) { c = c.stepSeverity(-1) }
        assertEquals(1, c.severityStep)
        assertFalse(c.canWeaken)
        assertEquals(0.1, c.severity, 1e-12)
        assertEquals("10%", c.severityLabel)
        assertEquals(7, c.stepSeverity(6).severityStep)
        assertEquals("70%", c.stepSeverity(6).severityLabel)
    }

    @Test
    fun `a slider position goes to the nearest step and survives nonsense`() {
        val c = ColourVisionChoice()
        assertEquals(4, c.withSeverityPosition(3.6f).severityStep)
        assertEquals(1, c.withSeverityPosition(-5f).severityStep)
        assertEquals(10, c.withSeverityPosition(99f).severityStep)
        assertEquals(10, c.withSeverityPosition(Float.NaN).severityStep)
        assertEquals(10, c.withSeverityPosition(Float.POSITIVE_INFINITY).severityStep)
    }

    @Test
    fun `every step is a tenth and the stepper reaches them all`() {
        var c = ColourVisionChoice().withSeverityPosition(1f)
        val seen = ArrayList<Int>()
        repeat(9) {
            seen += c.severityStep
            c = c.stepSeverity(1)
        }
        seen += c.severityStep
        assertEquals((1..10).toList(), seen)
    }

    @Test
    fun `encode and decode round trip every field`() {
        for (d in listOf(null) + Deficiency.entries) for (k in VisionKind.entries) {
            val c = ColourVisionChoice(d, k, 3, naming = true, patterns = d != null)
            assertEquals(c, ColourVisionChoice.decode(c.encode()))
        }
    }

    @Test
    fun `decode never throws and falls back to off`() {
        val off = ColourVisionChoice()
        assertEquals(off, ColourVisionChoice.decode(null))
        assertEquals(off, ColourVisionChoice.decode(""))
        assertEquals(off, ColourVisionChoice.decode("a,b,c"))
        assertEquals(off, ColourVisionChoice.decode("1,2,3"))
        assertEquals(off, ColourVisionChoice.decode("1,0,5,0,0,9"))
        // Out-of-range numbers are clamped, not trusted.
        val c = ColourVisionChoice.decode("9,9,99,1,1")
        assertNull(c.deficiency)
        assertEquals(VisionKind.SIMULATE, c.kind)
        assertEquals(10, c.severityStep)
    }

    @Test
    fun `the filter key's matrix is the exact simulation or daltonizer matrix`() {
        val sim = FilterKey(Deficiency.PROTAN, VisionKind.SIMULATE, 6)
        assertArrayEquals(Simulation.matrix(Deficiency.PROTAN, 0.6), sim.matrix(), 0.0)
        val help = FilterKey(Deficiency.TRITAN, VisionKind.HELP, 4)
        assertArrayEquals(Daltonizer.matrix(Deficiency.TRITAN, 0.4), help.matrix(), 0.0)
        assertArrayEquals(GpuFilter.simulate(Deficiency.PROTAN, 0.6), sim.previewMatrix(), 0f)
        assertArrayEquals(GpuFilter.daltonize(Deficiency.TRITAN, 0.4), help.previewMatrix(), 0f)
    }

    // ---- what is drawn ----

    private val deutan = ColourVisionChoice(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)

    @Test
    fun `with no filter the original is drawn whatever was computed`() {
        val ready = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
        assertTrue(displayPlan(ColourVisionChoice(), ready) is DisplayPlan.Original)
        assertTrue(displayPlan(ColourVisionChoice().withNaming(true), null) is DisplayPlan.Original)
    }

    @Test
    fun `with nothing computed yet the live preview answers at once`() {
        val plan = displayPlan(deutan, ready = null)
        assertTrue(plan is DisplayPlan.Preview)
        assertArrayEquals(GpuFilter.simulate(Deficiency.DEUTAN, 1.0), (plan as DisplayPlan.Preview).matrix, 0f)
    }

    @Test
    fun `the exact picture is used once it is for the same type and kind`() {
        val ready = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
        val plan = displayPlan(deutan, ready)
        assertTrue(plan is DisplayPlan.Exact)
        assertEquals(ready, (plan as DisplayPlan.Exact).key)
    }

    @Test
    fun `a stale strength keeps the exact picture until the next one lands`() {
        val ready = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
        assertTrue(displayPlan(deutan.stepSeverity(-3), ready) is DisplayPlan.Exact)
    }

    @Test
    fun `a different type or kind never shows the wrong exact picture`() {
        val ready = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
        assertTrue(displayPlan(deutan.withDeficiency(Deficiency.PROTAN), ready) is DisplayPlan.Preview)
        assertTrue(displayPlan(deutan.withKind(VisionKind.HELP), ready) is DisplayPlan.Preview)
    }

    @Test
    fun `a file that cannot be one still picture only gets the preview`() {
        val ready = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
        assertTrue(displayPlan(deutan, ready, exactAllowed = false) is DisplayPlan.Preview)
        assertNotNull((displayPlan(deutan, ready, exactAllowed = false) as DisplayPlan.Preview).matrix)
    }

    @Test
    fun `a photo that could not be read is drawn as it is, not through a wrong simulation`() {
        val ready = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
        assertTrue(displayPlan(deutan, null, exactAllowed = true, unreadable = true) is DisplayPlan.Original)
        assertTrue(displayPlan(deutan, ready, exactAllowed = true, unreadable = true) is DisplayPlan.Original)
        assertTrue(displayPlan(deutan, null, exactAllowed = true, unreadable = false) is DisplayPlan.Preview)
    }

    @Test
    fun `the job list is looked up once a library photo has had a filter, and kept for the viewer's life`() {
        val wanted = RunnerWanted()
        assertFalse("nobody who never filters pays for the look-up", wanted.update(canSave = true, filtering = false))
        assertTrue(wanted.update(canSave = true, filtering = true))
        // Turning the filter off must not drop a copy that is still being made: its notice and its Cancel need the list.
        assertTrue(wanted.update(canSave = true, filtering = false))
    }

    @Test
    fun `a file the library does not own never wants the job list`() {
        val wanted = RunnerWanted()
        assertFalse(wanted.update(canSave = false, filtering = true))
        assertFalse(wanted.update(canSave = false, filtering = false))
    }
}
