package com.fotoxplorr.app.vision

import com.fotoxplorr.app.vision.VisionTestKit.GRID9
import com.fotoxplorr.app.vision.VisionTestKit.argb
import com.fotoxplorr.app.vision.VisionTestKit.b
import com.fotoxplorr.app.vision.VisionTestKit.g
import com.fotoxplorr.app.vision.VisionTestKit.r
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The simulation: identity, greys, primaries, anomalous steps, and the honest idempotence figures. */
class SimulationTest {

    @Test
    fun `severity zero changes no colour at all`() {
        val rng = Random(7)
        for (d in Deficiency.entries) repeat(500) {
            val c = argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            assertEquals("$d ${c.toUInt().toString(16)}", c, Simulation.simulate(c, d, 0.0))
        }
    }

    @Test
    fun `black white and every grey are unchanged at every severity`() {
        for (d in Deficiency.entries) for (severity in listOf(0.1, 0.3, 0.5, 0.77, 1.0)) {
            for (v in 0..255) {
                val grey = argb(v, v, v)
                val out = Simulation.simulate(grey, d, severity)
                assertTrue("$d $severity grey $v -> ${r(out)},${g(out)},${b(out)}", VisionTestKit.maxChannelDiff(grey, out) <= 1)
            }
        }
    }

    @Test
    fun `full dichromacy of pure white stays white`() {
        for (d in Deficiency.entries) assertEquals(0xFFFFFFFF.toInt(), Simulation.simulate(0xFFFFFFFF.toInt(), d, 1.0))
    }

    @Test
    fun `pure primaries at full severity are the matrix columns, decoded and encoded`() {
        for (d in Deficiency.entries) {
            val m = MachadoMatrices.matrix(d, 1.0)
            val primaries = listOf(argb(255, 0, 0) to 0, argb(0, 255, 0) to 1, argb(0, 0, 255) to 2)
            for ((c, col) in primaries) {
                val out = Simulation.simulate(c, d, 1.0)
                val want = argb(
                    VisionTestKit.encode(m[col]),
                    VisionTestKit.encode(m[3 + col]),
                    VisionTestKit.encode(m[6 + col]),
                )
                assertTrue("$d col $col got ${r(out)},${g(out)},${b(out)} want ${r(want)},${g(want)},${b(want)}", VisionTestKit.maxChannelDiff(out, want) <= 1)
            }
        }
    }

    @Test
    fun `known look of pure red to a protan and a deutan - dark and olive, not red`() {
        val protan = Simulation.simulate(argb(255, 0, 0), Deficiency.PROTAN)
        val deutan = Simulation.simulate(argb(255, 0, 0), Deficiency.DEUTAN)
        // Linear (0.152, 0.115, 0) and (0.367, 0.280, 0): a dim then a brighter yellow-brown, with b gone.
        assertEquals(0, b(protan))
        assertEquals(0, b(deutan))
        assertTrue(r(protan) in 100..120 && g(protan) in 85..105)
        assertTrue(r(deutan) in 155..175 && g(deutan) in 140..160)
    }

    @Test
    fun `alpha passes through`() {
        assertEquals(0x80, Simulation.simulate(argb(200, 30, 30, 0x80), Deficiency.DEUTAN) ushr 24)
    }

    @Test
    fun `the bulk call matches the single call and may run in place`() {
        val src = GRID9.toIntArray()
        val bulk = Simulation.simulateAll(src, Deficiency.PROTAN, 0.6)
        for (i in src.indices) assertEquals(Simulation.simulate(src[i], Deficiency.PROTAN, 0.6), bulk[i])
        val inPlace = src.copyOf()
        Simulation.simulateAll(inPlace, Deficiency.PROTAN, 0.6, inPlace)
        assertTrue(bulk.contentEquals(inPlace))
    }

    @Test
    fun `a stronger severity moves a red further from where it started`() {
        val red = argb(220, 40, 40)
        for (d in listOf(Deficiency.PROTAN, Deficiency.DEUTAN)) {
            var last = -1.0
            for (severity in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                val dist = ColourSpace.deltaE(red, Simulation.simulate(red, d, severity))
                assertTrue("$d $severity: $dist after $last", dist > last)
                last = dist
            }
        }
    }

    @Test
    fun `protan and deutan stay put on a second pass, within the figures the KDoc gives`() {
        for ((d, meanLimit, maxLimit) in listOf(Triple(Deficiency.PROTAN, 5.0, 25.0), Triple(Deficiency.DEUTAN, 3.0, 16.0))) {
            val moves = GRID9.map { c ->
                val once = Simulation.simulate(c, d, 1.0)
                ColourSpace.deltaE(once, Simulation.simulate(once, d, 1.0))
            }
            val mean = moves.average()
            println("IDEMPOTENCE $d mean=${"%.2f".format(mean)} max=${"%.1f".format(moves.max())}")
            assertTrue("$d mean $mean", mean < meanLimit)
            assertTrue("$d max ${moves.max()}", moves.max() < maxLimit)
        }
    }

    @Test
    fun `tritan is not idempotent - the KDoc says so`() {
        val moves = GRID9.map { c ->
            val once = Simulation.simulate(c, Deficiency.TRITAN, 1.0)
            ColourSpace.deltaE(once, Simulation.simulate(once, Deficiency.TRITAN, 1.0))
        }
        println("IDEMPOTENCE TRITAN mean=${"%.2f".format(moves.average())} max=${"%.1f".format(moves.max())}")
        assertTrue("mean ${moves.average()}", moves.average() > 5.0)
    }

    @Test
    fun `a protan cannot tell a red from its confusion partner and a normal eye can`() {
        // Two linear colours that differ only along the direction the full protan matrix removes.
        val n = TestPlates.axisDirection(Deficiency.PROTAN)
        val base = doubleArrayOf(0.4, 0.4, 0.4)
        val a = VisionTestKit.fromLinear(DoubleArray(3) { base[it] + 0.2 * n[it] })
        val c = VisionTestKit.fromLinear(DoubleArray(3) { base[it] - 0.2 * n[it] })
        assertTrue(ColourSpace.deltaE(a, c) > 25.0)
        assertTrue(ColourSpace.deltaE(Simulation.simulate(a, Deficiency.PROTAN), Simulation.simulate(c, Deficiency.PROTAN)) < 2.0)
    }
}
