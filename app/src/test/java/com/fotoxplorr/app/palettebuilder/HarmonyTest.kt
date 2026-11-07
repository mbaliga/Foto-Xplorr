package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HarmonyTest {

    private val base = 0xFF3366CC.toInt()

    private fun hueOf(c: Int) = ColorMath.hsl(c).h

    /** Smallest angle between two hues, 0..180. */
    private fun apart(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun assertHues(kind: HarmonyKind, offsets: List<Double>) {
        val out = Harmony.generate(base, kind, offsets.size)
        val h0 = hueOf(base)
        offsets.forEachIndexed { i, off ->
            val want = (h0 + off + 360.0) % 360.0
            assertTrue("$kind[$i] hue ${hueOf(out[i])} want $want", apart(hueOf(out[i]), want) < 1.5)
        }
    }

    @Test
    fun `complementary is the base and the opposite hue`() = assertHues(HarmonyKind.COMPLEMENTARY, listOf(0.0, 180.0))

    @Test
    fun `split complementary sits 150 and 210 degrees round`() = assertHues(HarmonyKind.SPLIT_COMPLEMENTARY, listOf(0.0, 150.0, 210.0))

    @Test
    fun `triad is a third of the wheel apart`() = assertHues(HarmonyKind.TRIAD, listOf(0.0, 120.0, 240.0))

    @Test
    fun `tetrad is a quarter of the wheel apart`() = assertHues(HarmonyKind.TETRAD, listOf(0.0, 90.0, 180.0, 270.0))

    @Test
    fun `analogous steps thirty degrees each side, base first`() =
        assertHues(HarmonyKind.ANALOGOUS, listOf(0.0, 30.0, -30.0, 60.0, -60.0))

    @Test
    fun `pure red complementary is exactly cyan`() {
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FFFF.toInt()), Harmony.generate(0xFFFF0000.toInt(), HarmonyKind.COMPLEMENTARY, 2))
    }

    @Test
    fun `pure red triad is red green blue`() {
        assertEquals(
            listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
            Harmony.generate(0xFFFF0000.toInt(), HarmonyKind.TRIAD, 3),
        )
    }

    @Test
    fun `every kind returns the asked count with the base first and unchanged`() {
        for (kind in HarmonyKind.entries) for (n in 1..12) {
            val out = Harmony.generate(base, kind, n)
            assertEquals("$kind $n", n, out.size)
            assertEquals("$kind $n", base, out[0])
            assertTrue(out.all { (it ushr 24) == 0xFF })
        }
    }

    @Test
    fun `a count outside one to twelve is clamped`() {
        assertEquals(1, Harmony.generate(base, HarmonyKind.TRIAD, 0).size)
        assertEquals(12, Harmony.generate(base, HarmonyKind.TRIAD, 99).size)
    }

    @Test
    fun `a wheel kind asked for more colours than hues varies lightness so none repeat`() {
        for (kind in listOf(HarmonyKind.COMPLEMENTARY, HarmonyKind.TRIAD, HarmonyKind.TETRAD, HarmonyKind.SPLIT_COMPLEMENTARY)) {
            val out = Harmony.generate(base, kind, 12)
            assertEquals(kind.name, 12, out.toSet().size)
        }
    }

    @Test
    fun `no colour repeats for a very dark or very light base at any count`() {
        val bases = listOf(0xFF1A1A33, 0xFF050510, 0xFF0D0D1D, 0xFFF2F0FF, 0xFFFAFAFF, 0xFFE8F4D0, 0xFF331A1A, 0xFF3366CC, 0xFF0A0014, 0xFFFFE6F0)
            .map { it.toInt() }
        for (base in bases) for (kind in listOf(HarmonyKind.COMPLEMENTARY, HarmonyKind.SPLIT_COMPLEMENTARY, HarmonyKind.TRIAD, HarmonyKind.TETRAD)) for (n in 1..12) {
            val out = Harmony.generate(base, kind, n)
            assertEquals("${ColorMath.hex(base)} $kind $n: ${out.map(ColorMath::hex)}", n, out.toSet().size)
        }
    }

    @Test
    fun `a base at the very edge of lightness still gives distinct rings`() {
        for (l in listOf(0.0, 0.02, 0.05, 0.5, 0.95, 0.98, 1.0)) {
            val base = ColorMath.fromHsl(210.0, 0.6, l)
            for (kind in listOf(HarmonyKind.COMPLEMENTARY, HarmonyKind.TRIAD)) {
                val out = Harmony.generate(base, kind, 12)
                // Black and white have no saturation left to carry a hue, so only lightness separates them.
                if (l in 0.02..0.98) assertEquals("$l $kind", 12, out.toSet().size)
            }
        }
    }

    @Test
    fun `shades step down in lightness at the same hue`() {
        val out = Harmony.generate(base, HarmonyKind.SHADES, 5)
        val ls = out.map { ColorMath.lch(it).l }
        for (i in 1 until ls.size) assertTrue("L must fall: $ls", ls[i] < ls[i - 1])
        ls.forEachIndexed { i, l -> assertEquals(ls[0] * (5 - i) / 5, l, 1.5) }
        val h0 = ColorMath.lch(base).h
        // The darkest step has so little chroma left that its hue is mostly rounding, so it is not checked.
        out.take(4).forEach { assertTrue(apart(ColorMath.lch(it).h, h0) < 6.0) }
    }

    @Test
    fun `monochrome spreads lightness over a wide range at one hue`() {
        val out = Harmony.generate(base, HarmonyKind.MONOCHROME, 5)
        val ls = out.map { ColorMath.lch(it).l }
        assertEquals(5, ls.map { it.toInt() }.toSet().size)
        assertTrue("darkest ${ls.min()}", ls.min() < 20.0)
        assertTrue("lightest ${ls.max()}", ls.max() > 85.0)
    }

    @Test
    fun `the same input always gives the same output`() {
        for (kind in HarmonyKind.entries) {
            assertEquals(Harmony.generate(base, kind, 7), Harmony.generate(base, kind, 7))
        }
    }

    @Test
    fun `regenerate keeps the base and the locked swatches and fills the rest by position`() {
        val start = Palette.of("t", listOf(base, 0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt()))!!
            .withLock(2, true)
        val generated = Harmony.generate(base, HarmonyKind.TETRAD, 4)
        val out = Harmony.regenerate(start, HarmonyKind.TETRAD, 0)
        assertEquals(base, out.colors[0].argb)
        assertEquals(generated[1], out.colors[1].argb)
        assertEquals(0xFF222222.toInt(), out.colors[2].argb)
        assertTrue(out.colors[2].locked)
        // Locking slot 2 did not move slot 3 onto the colour slot 2 would have had.
        assertEquals(generated[3], out.colors[3].argb)
    }

    @Test
    fun `regenerate around a later swatch keeps that swatch`() {
        val start = Palette.of("t", listOf(0xFF111111.toInt(), base, 0xFF222222.toInt()))!!
        val out = Harmony.regenerate(start, HarmonyKind.TRIAD, 1)
        assertEquals(base, out.colors[1].argb)
        assertEquals(3, out.size)
        assertEquals(start.name, out.name)
        val clamped = Harmony.regenerate(start, HarmonyKind.TRIAD, 99)
        assertEquals(0xFF222222.toInt(), clamped.colors[2].argb)
    }
}
