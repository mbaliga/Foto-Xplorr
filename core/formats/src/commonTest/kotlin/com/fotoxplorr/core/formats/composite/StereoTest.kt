package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.composite.CompositeFixtures.mpo
import com.fotoxplorr.core.formats.composite.CompositeFixtures.source
import com.fotoxplorr.core.formats.composite.CompositeFixtures.spatialHeif
import com.fotoxplorr.core.formats.raw.jpeg
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StereoTest {

    @Test
    fun `an MPO index with two disparity images is a stereo pair`() {
        for (le in listOf(true, false)) {
            val file = mpo(littleEndian = le)
            val pair = MpoReader.stereoPair(source(file))!!
            assertEquals(0L, pair.left.range.offset)
            assertEquals(file.size.toLong(), pair.right.range.end)
            assertEquals(80, pair.right.width)
            assertEquals(60, pair.right.height)
        }
    }

    @Test
    fun `an MPF that lists only thumbnails is not stereo`() {
        val file = mpo(types = intArrayOf(0x030000, 0x010001))
        assertEquals(2, MpoReader.images(source(file)).size)
        assertNull(MpoReader.stereoPair(source(file)))
    }

    @Test
    fun `an MPO offset that misses the second JPEG drops that image`() {
        val file = mpo(secondOffsetDelta = 5)
        assertNull(MpoReader.stereoPair(source(file)))
    }

    @Test
    fun `a plain JPEG has no MP index`() {
        assertTrue(MpoReader.images(source(jpeg(64, 48, size = 600))).isEmpty())
    }

    @Test
    fun `mangled MPO files never throw`() {
        val random = Random(3)
        val file = mpo()
        repeat(300) {
            val m = file.copyOf()
            repeat(6) { m[random.nextInt(m.size)] = random.nextInt(256).toByte() }
            MpoReader.stereoPair(source(m))
        }
    }

    @Test
    fun `a HEIF ster group names the two eyes`() {
        assertEquals(HeifStereo.Group(1, 2), HeifStereo.find(source(spatialHeif())))
        assertNull(HeifStereo.find(source(spatialHeif(withGroup = false))))
        assertNull(HeifStereo.find(source(jpeg(64, 48))))
    }

    @Test
    fun `the anaglyph takes red from the left eye and cyan from the right`() {
        val left = intArrayOf(0xFFFF0000.toInt())
        val right = intArrayOf(0xFF00FF80.toInt())
        assertEquals(0xFFFFFF80.toInt(), StereoPixels.anaglyph(left, right, halfColour = false)[0])
        // Half colour: red is the left eye's luminance (pure red is about 30 percent).
        assertEquals(0xFF4CFF80.toInt(), StereoPixels.anaglyph(left, right, halfColour = true)[0])
    }

    @Test
    fun `wiggle alternates only when motion is allowed`() {
        assertFalse(StereoPixels.wiggleShowsRight(0, motionAllowed = true))
        assertTrue(StereoPixels.wiggleShowsRight(StereoPixels.WIGGLE_PERIOD_MS / 2, motionAllowed = true))
        assertFalse(StereoPixels.wiggleShowsRight(StereoPixels.WIGGLE_PERIOD_MS / 2, motionAllowed = false))
        assertFalse(StereoPixels.offered(StereoMode.WIGGLE, motionAllowed = false))
        assertTrue(StereoPixels.offered(StereoMode.ANAGLYPH, motionAllowed = false))
    }

    @Test
    fun `cross eye swaps the order`() {
        assertTrue(StereoPixels.leftFirst(StereoMode.SIDE_BY_SIDE))
        assertFalse(StereoPixels.leftFirst(StereoMode.CROSS_EYE))
    }
}
