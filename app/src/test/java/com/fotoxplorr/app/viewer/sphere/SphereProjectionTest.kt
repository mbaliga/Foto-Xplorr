package com.fotoxplorr.app.viewer.sphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereProjectionTest {
    private val full = SphereMeta(8, 4, 8, 4, 0, 0)

    @Test
    fun `the centre ray at yaw 0 pitch 0 hits the middle of the picture`() {
        val uv = FloatArray(2)
        assertTrue(SphereProjection.sample(Look(0f, 0f, 60f), full, 100, 100, 50f, 50f, uv))
        assertEquals(0.5f, uv[0], 1e-4f); assertEquals(0.5f, uv[1], 1e-4f)
    }

    @Test
    fun `yaw 90 centres a quarter of the way round, east is to the right`() {
        val uv = FloatArray(2)
        SphereProjection.sample(Look(90f, 0f, 60f), full, 100, 100, 50f, 50f, uv)
        assertEquals(0.75f, uv[0], 1e-4f)
        // A pixel right of centre looks further east (larger longitude, larger u).
        val right = FloatArray(2)
        SphereProjection.sample(Look(0f, 0f, 60f), full, 100, 100, 80f, 50f, right)
        assertTrue(right[0] > 0.5f)
    }

    @Test
    fun `pitch up moves toward the top of the picture`() {
        val uv = FloatArray(2)
        SphereProjection.sample(Look(0f, 45f, 60f), full, 100, 100, 50f, 50f, uv)
        assertEquals(0.25f, uv[1], 1e-4f) // 45 up of 90 is a quarter down from the top
    }

    @Test
    fun `yaw wraps round the back of a whole sphere`() {
        val uv = FloatArray(2)
        SphereProjection.sample(Look(180f, 0f, 60f), full, 100, 100, 50f, 50f, uv)
        assertTrue(uv[0] < 1e-3f || uv[0] > 0.999f)
    }

    @Test
    fun `a partial picture is blank outside its window`() {
        val partial = SphereMeta(6000, 3000, 2000, 1000, 2000, 1000) // lon -60..60
        val uv = FloatArray(2)
        assertTrue(SphereProjection.sample(Look(0f, 0f, 40f), partial, 100, 100, 50f, 50f, uv))
        assertFalse(SphereProjection.sample(Look(120f, 0f, 40f), partial, 100, 100, 50f, 50f, uv))
    }

    @Test
    fun `render copies the right pixel through a uniform and a split picture`() {
        val w = 8; val h = 4
        // Left half of the sphere (lon -180..0) red, right half (0..180) blue.
        val src = IntArray(w * h) { if (it % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val west = SphereProjection.render(src, w, h, full, Look(-90f, 0f, 40f), 9, 9)
        val east = SphereProjection.render(src, w, h, full, Look(90f, 0f, 40f), 9, 9)
        assertEquals(0xFFFF0000.toInt(), west[4 * 9 + 4])
        assertEquals(0xFF0000FF.toInt(), east[4 * 9 + 4])
    }

    @Test
    fun `bilinear blends between pixels and wraps horizontally`() {
        val src = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val mid = SphereProjection.bilinear(src, 2, 1, 0.5f, 0f, wrapX = false)
        assertEquals(0xFF, mid ushr 24)
        assertEquals(128, mid and 0xFF)
        val wrapped = SphereProjection.bilinear(src, 2, 1, 1.5f, 0f, wrapX = true)
        assertEquals(128, wrapped and 0xFF)
        val clamped = SphereProjection.bilinear(src, 2, 1, 1.5f, 0f, wrapX = false)
        assertEquals(255, clamped and 0xFF)
    }

    @Test
    fun `render of an empty request is empty`() {
        assertEquals(0, SphereProjection.render(IntArray(0), 0, 0, full, Look(0f, 0f, 60f), 4, 4).count { it != 0 })
    }
}
