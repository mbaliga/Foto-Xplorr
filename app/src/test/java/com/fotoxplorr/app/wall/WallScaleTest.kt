package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallScaleTest {

    @Test
    fun `an A4 sheet lying across a quarter of the image makes the wall four sheets wide`() {
        // 29.7 cm spans 400 of 1600 px, so 1600 px is 4 x 29.7 cm.
        assertEquals(118.8f, WallScale.fromReference(29.7f, 400f, 1600)!!, 0.01f)
    }

    @Test
    fun `reference and pixels per cm agree with each other`() {
        val wall = WallScale.fromReference(203f, 800f, 1600)!!
        assertEquals(800f / 203f, WallScale.pxPerCm(1600, wall), 1e-3f)
    }

    @Test
    fun `a reference that is too short or has no size is refused`() {
        assertNull(WallScale.fromReference(29.7f, 1f, 1600))
        assertNull(WallScale.fromReference(0f, 400f, 1600))
        assertNull(WallScale.fromReference(-3f, 400f, 1600))
        assertNull(WallScale.fromReference(29.7f, 400f, 0))
    }

    @Test
    fun `an implausible wall width is refused rather than drawn`() {
        // 0.01 cm across a quarter of the image is a 0.04 cm wall; 500 cm across 2 px is a 4 km one.
        assertNull(WallScale.fromReference(0.01f, 400f, 1600))
        assertNull(WallScale.fromReference(500f, 2f, 1600))
    }

    @Test
    fun `a 26 mm phone at two metres sees about two metres of wall across a 4 by 3 portrait`() {
        // diagonal cover = 200 * 43.267 / 26 = 332.8 cm; a 3000 x 4000 portrait's width is 0.6 of its diagonal.
        assertEquals(199.7f, WallScale.fromDistance(200f, 26f, 3000, 4000)!!, 0.2f)
    }

    @Test
    fun `a 3 by 2 landscape sees more wall across than a portrait of the same lens`() {
        val landscape = WallScale.fromDistance(200f, 26f, 6000, 4000)!!
        val portrait = WallScale.fromDistance(200f, 26f, 4000, 6000)!!
        assertEquals(332.8f * 0.832f, landscape, 0.5f)
        assertEquals(true, landscape > portrait)
    }

    @Test
    fun `a longer lens or a shorter distance sees less wall`() {
        assertEquals(
            WallScale.fromDistance(200f, 26f, 4000, 3000)!! / 2f,
            WallScale.fromDistance(100f, 26f, 4000, 3000)!!,
            0.01f,
        )
        assertEquals(
            WallScale.fromDistance(200f, 26f, 4000, 3000)!! / 2f,
            WallScale.fromDistance(200f, 52f, 4000, 3000)!!,
            0.01f,
        )
    }

    @Test
    fun `no or negative distance and focal are refused, not divided by`() {
        assertNull(WallScale.fromDistance(0f, 26f, 4000, 3000))
        assertNull(WallScale.fromDistance(-5f, 26f, 4000, 3000))
        assertNull(WallScale.fromDistance(200f, 0f, 4000, 3000))
        assertNull(WallScale.fromDistance(200f, 26f, 0, 3000))
    }

    @Test
    fun `an uncalibrated wall has no scale`() {
        assertEquals(0f, WallScale.pxPerCm(1600, 0f), 0f)
    }

    @Test
    fun `length between two taps is the straight line`() {
        assertEquals(5f, WallScale.lengthPx(0f, 0f, 3f, 4f), 1e-6f)
    }
}
