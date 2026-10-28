package com.fotoxplorr.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The dot plates: determinism, geometry, the confusion-axis property and the scoring. */
class TestPlatesTest {

    private fun same(a: Plate, b: Plate): Boolean =
        a.dots.size == b.dots.size && a.dots.indices.all {
            val x = a.dots[it]
            val y = b.dots[it]
            x.x == y.x && x.y == y.y && x.r == y.r && x.argb == y.argb && x.inFigure == y.inFigure
        }

    /** Mean linear colour of the figure dots or the ground dots, as a packed colour. */
    private fun mean(plate: Plate, figure: Boolean, through: ((Int) -> Int)? = null): Lab {
        val dots = plate.dots.filter { it.inFigure == figure }
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (d in dots) {
            val c = through?.invoke(d.argb) ?: d.argb
            r += VisionTestKit.decode(VisionTestKit.r(c))
            g += VisionTestKit.decode(VisionTestKit.g(c))
            b += VisionTestKit.decode(VisionTestKit.b(c))
        }
        return ColourSpace.labOfLinear(r / dots.size, g / dots.size, b / dots.size)
    }

    @Test
    fun `the same arguments always give the same plate and another seed gives another`() {
        for (axis in Deficiency.entries) {
            assertTrue(same(TestPlates.generate(5, axis, 42), TestPlates.generate(5, axis, 42)))
            assertTrue(!same(TestPlates.generate(5, axis, 42), TestPlates.generate(5, axis, 43)))
            assertTrue(!same(TestPlates.generate(5, axis, 42), TestPlates.generate(6, axis, 42)))
        }
    }

    @Test
    fun `dots are in the disc, do not overlap and are plentiful`() {
        val plate = TestPlates.generate(8, Deficiency.DEUTAN, 1)
        assertTrue("${plate.dots.size} dots", plate.dots.size > 350)
        for (d in plate.dots) {
            assertTrue(hypot(d.x - 0.5, d.y - 0.5) + d.r <= 0.4701)
            assertTrue(d.r in 0.0089f..0.0221f)
        }
        for (i in plate.dots.indices) for (j in i + 1 until plate.dots.size) {
            val a = plate.dots[i]
            val b = plate.dots[j]
            assertTrue("$i/$j overlap", hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) >= a.r + b.r)
        }
    }

    @Test
    fun `the figure is a sensible share of the disc and follows the digit`() {
        val dots = (0..9).associateWith { TestPlates.generate(it, Deficiency.DEUTAN, 3).dots }
        for ((digit, d) in dots) {
            val share = d.count { it.inFigure }.toDouble() / d.size
            assertTrue("digit $digit share $share", share in 0.05..0.5)
        }
        val one = dots.getValue(1).filter { it.inFigure }
        assertTrue(one.all { kotlin.math.abs(it.x - 0.5) < 0.07 })
        assertTrue(dots.getValue(8).count { it.inFigure } > 2 * one.size)
        assertTrue(dots.getValue(8).count { it.inFigure } > dots.getValue(7).count { it.inFigure })
    }

    @Test
    fun `the confusion axis is removed by the protan and deutan matrices and mostly by the tritan one`() {
        for (axis in listOf(Deficiency.PROTAN, Deficiency.DEUTAN)) {
            val n = TestPlates.axisDirection(axis)
            val s = MachadoMatrices.matrix(axis, 1.0)
            assertEquals(1.0, n.maxOf { kotlin.math.abs(it) }, 1e-9)
            for (r in 0 until 3) assertEquals("$axis row $r", 0.0, s[r * 3] * n[0] + s[r * 3 + 1] * n[1] + s[r * 3 + 2] * n[2], 1e-4)
        }
        val n = TestPlates.axisDirection(Deficiency.TRITAN)
        val s = MachadoMatrices.matrix(Deficiency.TRITAN, 1.0)
        val residue = (0 until 3).maxOf { r -> kotlin.math.abs(s[r * 3] * n[0] + s[r * 3 + 1] * n[1] + s[r * 3 + 2] * n[2]) }
        assertTrue("tritan residue $residue", residue < 0.2)
    }

    @Test
    fun `a normal eye sees the figure and the modelled dichromat sees almost nothing`() {
        for ((axis, ratio) in listOf(Deficiency.PROTAN to 0.1, Deficiency.DEUTAN to 0.1, Deficiency.TRITAN to 0.5)) {
            val plate = TestPlates.generate(4, axis, 9)
            val seen = ColourSpace.deltaE(mean(plate, true), mean(plate, false))
            val modelled = ColourSpace.deltaE(
                mean(plate, true) { Simulation.simulate(it, axis, 1.0) },
                mean(plate, false) { Simulation.simulate(it, axis, 1.0) },
            )
            println("PLATE $axis normal=${"%.1f".format(seen)} modelled=${"%.1f".format(modelled)}")
            assertTrue("$axis normal $seen", seen > 15.0)
            assertTrue("$axis modelled $modelled vs $seen", modelled < seen * ratio + 1.0)
        }
    }

    @Test
    fun `figure and ground differ in lightness by no more than the ground varies itself - no lightness cue`() {
        val plate = TestPlates.generate(2, Deficiency.DEUTAN, 12)
        val lf = plate.dots.filter { it.inFigure }.map { ColourSpace.lab(it.argb).l }
        val lg = plate.dots.filter { !it.inFigure }.map { ColourSpace.lab(it.argb).l }
        assertTrue(kotlin.math.abs(lf.average() - lg.average()) < 3.0)
    }

    @Test
    fun `the control plate is readable by lightness even through the full simulation`() {
        for (axis in Deficiency.entries) {
            val plate = TestPlates.generate(3, axis, 5, control = true)
            val lf = mean(plate, true) { Simulation.simulate(it, axis, 1.0) }.l
            val lg = mean(plate, false) { Simulation.simulate(it, axis, 1.0) }.l
            assertTrue("$axis control dL ${lf - lg}", lf - lg > 6.0)
        }
    }

    @Test
    fun `a session starts with a control and has distinct digits`() {
        val s = TestPlates.session(Deficiency.DEUTAN, 77, count = 4)
        assertEquals(5, s.size)
        assertTrue(s[0].control)
        assertTrue(s.drop(1).none { it.control })
        assertEquals(5, s.map { it.digit }.toSet().size)
        assertTrue(s.all { it.axis == Deficiency.DEUTAN })
        val again = TestPlates.session(Deficiency.DEUTAN, 77, count = 4)
        assertEquals(s.map { it.digit }, again.map { it.digit })
        assertNotEquals(s.map { it.digit }, TestPlates.session(Deficiency.DEUTAN, 78, count = 4).map { it.digit })
    }

    @Test
    fun `scoring counts only test plates and flags a missed control`() {
        val s = TestPlates.session(Deficiency.PROTAN, 5, count = 4)
        val all = s.map { it.digit }
        val perfect = TestPlates.score(s, all)
        assertEquals(4, perfect.total)
        assertEquals(4, perfect.correct)
        assertEquals(PlateBand.ALL_SEEN, perfect.band)

        val half = TestPlates.score(s, listOf(all[0], all[1], all[2], null, null))
        assertEquals(2, half.correct)
        assertEquals(PlateBand.SOME_MISSED, half.band)

        val none = TestPlates.score(s, listOf(all[0], null, null, null, null))
        assertEquals(0, none.correct)
        assertEquals(PlateBand.MOST_MISSED, none.band)

        val noControl = TestPlates.score(s, listOf(null) + all.drop(1))
        assertEquals(4, noControl.correct)
        assertEquals(PlateBand.UNRELIABLE, noControl.band)
    }

    @Test
    fun `a short or wrong answer list counts as not seen`() {
        val s = TestPlates.session(Deficiency.TRITAN, 6, count = 3)
        val short = TestPlates.score(s, listOf(s[0].digit))
        assertEquals(0, short.correct)
        assertEquals(3, short.total)
        val wrong = TestPlates.score(s, s.map { (it.digit + 1) % 10 })
        assertEquals(PlateBand.UNRELIABLE, wrong.band)
        assertEquals(PlateBand.ALL_SEEN, TestPlates.score(emptyList(), emptyList()).band)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a digit outside 0 to 9 is refused`() {
        TestPlates.generate(10, Deficiency.DEUTAN, 1)
    }
}
