package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SwatchCardTest {

    private fun palette(n: Int, seed: Long = 3L): Palette {
        val rnd = Random(seed)
        return Palette.of("Card", List(n) { ColorMath.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) })!!
    }

    private fun overlap(a: SwatchCell, b: SwatchCell) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test
    fun `there is one block per colour, in palette order, labelled with its hex`() {
        val p = palette(7)
        val spec = SwatchCard.layout(p)
        assertEquals(p.argbs, spec.cells.map { it.argb })
        assertEquals(p.colors.map { it.hex }, spec.cells.map { it.label })
    }

    @Test
    fun `blocks sit inside the card and never overlap, for every size`() {
        for (n in 2..12) for (w in listOf(100, 240, 800, 1080, 4096, 9000)) {
            val spec = SwatchCard.layout(palette(n), w)
            assertTrue(spec.widthPx in SwatchCard.MIN_WIDTH_PX..SwatchCard.MAX_WIDTH_PX)
            spec.cells.forEach {
                assertTrue("n=$n w=$w $it", it.left >= 0 && it.top > 0 && it.right <= spec.widthPx && it.bottom <= spec.heightPx)
                assertTrue(it.width > 0 && it.height > 0)
            }
            for (i in spec.cells.indices) for (j in i + 1 until spec.cells.size) {
                assertTrue("n=$n w=$w $i/$j", !overlap(spec.cells[i], spec.cells[j]))
            }
        }
    }

    @Test
    fun `rows hold up to six`() {
        assertEquals(1, SwatchCard.layout(palette(6)).cells.map { it.top }.toSet().size)
        assertEquals(2, SwatchCard.layout(palette(7)).cells.map { it.top }.toSet().size)
        assertEquals(2, SwatchCard.layout(palette(12)).cells.map { it.top }.toSet().size)
        assertEquals(6, SwatchCard.layout(palette(12)).cells.count { it.top == SwatchCard.layout(palette(12)).cells[0].top })
    }

    @Test
    fun `the label is always readable on its block`() {
        val spec = SwatchCard.layout(palette(12, seed = 11L))
        spec.cells.forEach { assertTrue(it.label, Contrast.ratio(it.labelArgb, it.argb) >= 4.5) }
        val dark = SwatchCard.layout(Palette.of("x", listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))!!).cells
        assertEquals(0xFFFFFFFF.toInt(), dark[0].labelArgb)
        assertEquals(0xFF000000.toInt(), dark[1].labelArgb)
    }

    @Test
    fun `the title carries the palette name and sits above the blocks`() {
        val spec = SwatchCard.layout(palette(3).renamed("Harbour"))
        assertEquals("Harbour", spec.title)
        assertTrue(spec.titleBaselineY < spec.cells.minOf { it.top })
        assertTrue(spec.titleSizePx > 0f)
    }

    @Test
    fun `the same palette lays out the same way`() {
        assertEquals(SwatchCard.layout(palette(9)), SwatchCard.layout(palette(9)))
    }
}
