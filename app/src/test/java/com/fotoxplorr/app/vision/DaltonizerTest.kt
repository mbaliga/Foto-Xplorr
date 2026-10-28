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

    private fun linearOf(c: Int) = doubleArrayOf(
        VisionTestKit.decode(VisionTestKit.r(c)), VisionTestKit.decode(VisionTestKit.g(c)), VisionTestKit.decode(VisionTestKit.b(c)),
    )

    private fun product(m: DoubleArray, rgb: DoubleArray) =
        DoubleArray(3) { m[it * 3] * rgb[0] + m[it * 3 + 1] * rgb[1] + m[it * 3 + 2] * rgb[2] }

    @Test
    fun `the result is the fitted matrix product - in range, the same direction, never past the product`() {
        for (d in Deficiency.entries) {
            val m = Daltonizer.matrix(d, 1.0)
            for (c in GRID9) {
                val rgb = linearOf(c)
                val full = product(m, rgb)
                val fitted = Daltonizer.fitted(m, rgb)
                val got = Daltonizer.daltonize(c, d, 1.0)
                val want = VisionTestKit.fromLinear(fitted)
                assertTrue("$d ${c.toUInt().toString(16)}", VisionTestKit.maxChannelDiff(want, got) <= 1)
                for (i in 0..2) {
                    assertTrue("$d ${c.toUInt().toString(16)} channel $i is $fitted", fitted[i] >= -1e-12 && fitted[i] <= 1.0 + 1e-12)
                    // On the line from the colour to the product, no further than the product.
                    val moved = fitted[i] - rgb[i]
                    val wanted = full[i] - rgb[i]
                    assertTrue("$d $i", moved * wanted >= -1e-12 && kotlin.math.abs(moved) <= kotlin.math.abs(wanted) + 1e-12)
                }
                assertEquals(255, got ushr 24)
            }
        }
    }

    @Test
    fun `one scale for the whole colour - the fitted correction keeps its direction`() {
        val rng = Random(31)
        for (d in Deficiency.entries) {
            val m = Daltonizer.matrix(d, 1.0)
            repeat(2000) {
                val rgb = doubleArrayOf(rng.nextDouble(), rng.nextDouble(), rng.nextDouble())
                val full = product(m, rgb)
                val fitted = Daltonizer.fitted(m, rgb)
                // Every channel moved by the same share of what was wanted.
                var share = -1.0
                for (i in 0..2) {
                    val wanted = full[i] - rgb[i]
                    if (kotlin.math.abs(wanted) < 1e-6) continue
                    val s = (fitted[i] - rgb[i]) / wanted
                    if (share < 0) share = s else assertEquals(share, s, 1e-9)
                }
            }
        }
    }

    @Test
    fun `a correction that fits is not touched`() {
        // Mid grey with a small push: well inside the gamut after the matrix, so fitted equals the product.
        val rgb = doubleArrayOf(0.45, 0.4, 0.38)
        for (d in Deficiency.entries) {
            val m = Daltonizer.matrix(d, 0.3)
            val full = product(m, rgb)
            val fitted = Daltonizer.fitted(m, rgb)
            for (i in 0..2) assertEquals(full[i], fitted[i], 1e-12)
        }
    }

    /** Colours like leaves and grass: hue 0.18 to 0.45, saturation 0.2 to 0.7, value 0.2 to 0.9. */
    private fun foliage(rng: Random): Int {
        val h = 0.18 + rng.nextDouble() * 0.27
        val sat = 0.2 + rng.nextDouble() * 0.5
        val v = 0.2 + rng.nextDouble() * 0.7
        val i = (h * 6).toInt()
        val f = h * 6 - i
        val p = v * (1 - sat)
        val q = v * (1 - f * sat)
        val t = v * (1 - (1 - f) * sat)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return argb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    @Test
    fun `leaves and grass are not clipped flat - the red of a deutan correction keeps its texture`() {
        val rng = Random(77)
        val m = Daltonizer.matrix(Deficiency.DEUTAN, 1.0)
        var leaves = 0
        var wouldClip = 0
        var flat = 0
        repeat(5000) {
            val c = foliage(rng)
            if (VisionTestKit.r(c) < 25) return@repeat
            leaves++
            if (product(m, linearOf(c)).any { it < 0.0 || it > 1.0 }) wouldClip++
            if (VisionTestKit.r(Daltonizer.daltonize(c, Deficiency.DEUTAN, 1.0)) == 0) flat++
        }
        println("DALTONIZE foliage: $wouldClip of $leaves leave the gamut as a plain matrix, $flat of $leaves end with red 0")
        assertTrue("the fixture must show the problem it guards against ($wouldClip of $leaves)", wouldClip * 4 > leaves)
        assertTrue("$flat of $leaves leaves ended with no red at all", flat * 50 < leaves)
    }

    @Test
    fun `a brighter version of the same colour stays in proportion - shading survives the fit`() {
        val c = argb(90, 140, 60)
        val rgb = linearOf(c)
        val m = Daltonizer.matrix(Deficiency.DEUTAN, 1.0)
        val a = Daltonizer.fitted(m, rgb)
        val half = Daltonizer.fitted(m, DoubleArray(3) { rgb[it] * 0.5 })
        for (i in 0..2) assertEquals(a[i] * 0.5, half[i], 1e-9)
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
        for ((d, floor) in listOf(Deficiency.PROTAN to 0.8, Deficiency.DEUTAN to 0.8, Deficiency.TRITAN to 0.6)) {
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
