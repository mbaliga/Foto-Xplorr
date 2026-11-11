package com.fotoxplorr.app.vision

import com.fotoxplorr.app.vision.VisionTestKit.argb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The seeded k-means colour index. */
class ColourIndexTest {
    private val red = argb(220, 30, 30)
    private val green = argb(30, 200, 40)
    private val blue = argb(30, 40, 220)

    private fun stripes(w: Int, h: Int, colours: List<Int>) =
        IntArray(w * h) { colours[(it % w) * colours.size / w] }

    @Test
    fun `three flat colours come back as exactly three clusters of those colours`() {
        val px = stripes(30, 10, listOf(red, green, blue))
        val img = ColourIndex.quantize(px, 30, 10, k = 3, seed = 5)
        assertEquals(3, img.centres.size)
        assertEquals(setOf(red, green, blue), img.centres.toSet())
        for (i in px.indices) assertEquals(px[i], img.centres[img.index[i].toInt()])
    }

    @Test
    fun `clusters are numbered darkest first`() {
        val img = ColourIndex.quantize(stripes(30, 4, listOf(argb(250, 250, 250), argb(10, 10, 10), argb(120, 120, 120))), 30, 4, 3)
        val l = img.centres.map { ColourSpace.lab(it).l }
        assertEquals(l.sorted(), l)
    }

    @Test
    fun `the same pixels and seed give the same answer, always`() {
        val rng = Random(3)
        val px = IntArray(40 * 40) { argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)) }
        val a = ColourIndex.quantize(px, 40, 40, 5, seed = 99)
        val b = ColourIndex.quantize(px, 40, 40, 5, seed = 99)
        assertArrayEquals(a.centres, b.centres)
        assertArrayEquals(a.index, b.index)
    }

    @Test
    fun `k is capped at six and at the number of colours present`() {
        val rng = Random(4)
        val px = IntArray(50 * 50) { argb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)) }
        val img = ColourIndex.quantize(px, 50, 50, k = 40)
        assertTrue(img.centres.size in 1..ColourIndex.MAX_K)
        for (v in img.index) assertTrue(v.toInt() in img.centres.indices)
        val two = ColourIndex.quantize(IntArray(100) { if (it < 50) red else blue }, 10, 10, k = 6)
        assertEquals(2, two.centres.size)
    }

    @Test
    fun `an empty image and a one pixel image are fine`() {
        assertEquals(0, ColourIndex.quantize(IntArray(0), 0, 0, 3).centres.size)
        val one = ColourIndex.quantize(intArrayOf(red), 1, 1, 4)
        assertEquals(listOf(red), one.centres.toList())
        assertEquals(0, one.index[0].toInt())
    }

    @Test
    fun `a large image is sampled but every pixel is still assigned`() {
        val px = stripes(200, 100, listOf(red, green, blue, argb(240, 240, 20)))
        val img = ColourIndex.quantize(px, 200, 100, 4)
        assertEquals(4, img.centres.size)
        assertEquals(px.size, img.index.size)
        for (i in px.indices) assertEquals(px[i], img.centres[img.index[i].toInt()])
    }
}
