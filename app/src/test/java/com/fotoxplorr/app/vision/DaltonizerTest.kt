package com.fotoxplorr.app.vision

import com.fotoxplorr.app.vision.VisionTestKit.GRID9
import com.fotoxplorr.app.vision.VisionTestKit.argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Daltonize: identity at zero, greys fixed, always in range, and confusable colours end up further apart. */
class DaltonizerTest {

    @Test
    fun `severity zero changes no colour`() {
        val rng = Random(11)
        for (d in Deficiency.entries) repeat(300) {
            val c = argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            assertEquals(c, Daltonizer.daltonize(c, d, 0.0))
        }
    }

    @Test
    fun `greys and white and black are unchanged - they have no error to move`() {
        for (d in Deficiency.entries) for (severity in listOf(0.2, 0.6, 1.0)) for (v in 0..255 step 5) {
            val grey = argb(v, v, v)
            val out = Daltonizer.daltonize(grey, d, severity)
            assertTrue("$d $severity $v", VisionTestKit.maxChannelDiff(grey, out) <= 1)
        }
    }

    @Test
    fun `the result is the clamped matrix product - nothing wraps or leaves 0 to 255`() {
        for (d in Deficiency.entries) {
            val m = Daltonizer.matrix(d, 1.0)
            for (c in GRID9) {
                val rgb = doubleArrayOf(VisionTestKit.decode(VisionTestKit.r(c)), VisionTestKit.decode(VisionTestKit.g(c)), VisionTestKit.decode(VisionTestKit.b(c)))
                val want = VisionTestKit.fromLinear(DoubleArray(3) { m[it * 3] * rgb[0] + m[it * 3 + 1] * rgb[1] + m[it * 3 + 2] * rgb[2] })
                val got = Daltonizer.daltonize(c, d, 1.0)
                assertTrue("$d ${c.toUInt().toString(16)}", VisionTestKit.maxChannelDiff(want, got) <= 1)
                for (ch in listOf(VisionTestKit.r(got), VisionTestKit.g(got), VisionTestKit.b(got))) assertTrue(ch in 0..255)
                assertEquals(255, got ushr 24)
            }
        }
    }

    @Test
    fun `alpha passes through`() {
        assertEquals(0x40, Daltonizer.daltonize(argb(200, 30, 30, 0x40), Deficiency.DEUTAN) ushr 24)
    }

    @Test
    fun `a red and a green a deutan cannot tell apart end up clearly apart after daltonizing`() {
        for (d in listOf(Deficiency.PROTAN, Deficiency.DEUTAN)) {
            val n = TestPlates.axisDirection(d)
            val base = doubleArrayOf(0.35, 0.35, 0.35)
            val red = VisionTestKit.fromLinear(DoubleArray(3) { base[it] + 0.2 * n[it] })
            val green = VisionTestKit.fromLinear(DoubleArray(3) { base[it] - 0.2 * n[it] })
            val before = ColourSpace.deltaE(Simulation.simulate(red, d), Simulation.simulate(green, d))
            val after = ColourSpace.deltaE(
                Simulation.simulate(Daltonizer.daltonize(red, d), d),
                Simulation.simulate(Daltonizer.daltonize(green, d), d),
            )
            assertTrue("$d before $before", before < 2.0)
            assertTrue("$d after $after", after > 20.0)
        }
    }

    @Test
    fun `most confusable pairs of random colours get further apart, for every kind`() {
        for ((d, floor) in listOf(Deficiency.PROTAN to 0.85, Deficiency.DEUTAN to 0.85, Deficiency.TRITAN to 0.6)) {
            val rng = Random(2024)
            var pairs = 0
            var better = 0
            repeat(20000) {
                val a = argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
                val c = argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
                val before = ColourSpace.deltaE(Simulation.simulate(a, d), Simulation.simulate(c, d))
                if (before > 12.0 || ColourSpace.deltaE(a, c) < 25.0) return@repeat
                pairs++
                val after = ColourSpace.deltaE(
                    Simulation.simulate(Daltonizer.daltonize(a, d), d),
                    Simulation.simulate(Daltonizer.daltonize(c, d), d),
                )
                if (after > before) better++
            }
            println("DALTONIZE $d improved $better of $pairs")
            assertTrue("$d only $pairs confusable pairs found", pairs >= 40)
            assertTrue("$d $better of $pairs improved", better.toDouble() / pairs >= floor)
        }
    }

    @Test
    fun `a weaker severity moves a colour less`() {
        val red = argb(210, 50, 50)
        for (d in Deficiency.entries) {
            var last = -1.0
            for (severity in listOf(0.0, 0.3, 0.6, 1.0)) {
                val moved = ColourSpace.deltaE(red, Daltonizer.daltonize(red, d, severity))
                assertTrue("$d $severity $moved after $last", moved >= last)
                last = moved
            }
        }
    }

    @Test
    fun `the bulk call matches the single call`() {
        val src = GRID9.toIntArray()
        val out = Daltonizer.daltonizeAll(src, Deficiency.DEUTAN, 0.8)
        for (i in src.indices) assertEquals(Daltonizer.daltonize(src[i], Deficiency.DEUTAN, 0.8), out[i])
    }
}
