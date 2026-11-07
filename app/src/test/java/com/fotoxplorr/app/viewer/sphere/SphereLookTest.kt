package com.fotoxplorr.app.viewer.sphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereLookTest {
    private val full = SphereMeta(8000, 4000, 8000, 4000, 0, 0)
    private val partial = SphereMeta(6000, 3000, 2000, 1000, 2000, 1000) // lon -60..60, lat -30..30
    private val aspect = 0.5f // a portrait phone

    @Test
    fun `a whole sphere wraps yaw and limits pitch to the poles`() {
        val l = SphereLook.clamp(Look(190f, 120f, 75f), full, aspect)
        assertEquals(-170f, l.yaw, 1e-4f)
        assertEquals(90f - 75f / 2f, l.pitch, 1e-4f)
    }

    @Test
    fun `wrap is in minus 180 exclusive to 180 inclusive`() {
        assertEquals(180f, SphereLook.wrap(180f), 0f)
        assertEquals(180f, SphereLook.wrap(-180f), 0f)
        assertEquals(-170f, SphereLook.wrap(190f), 0f)
        assertEquals(10f, SphereLook.wrap(730f), 0f)
    }

    @Test
    fun `a partial panorama keeps the view's edges on the picture`() {
        val l = SphereLook.clamp(Look(500f, 500f, 60f), partial, 1f)
        val halfH = SphereLook.hFov(l.fov, 1f) / 2f
        assertTrue(l.yaw + halfH <= partial.lonMax + 1e-3f)
        assertTrue(l.pitch + l.fov / 2f <= partial.latMax + 1e-3f)
        val r = SphereLook.clamp(Look(-500f, -500f, 60f), partial, 1f)
        assertTrue(r.yaw - halfH >= partial.lonMin - 1e-3f)
        assertTrue(r.pitch - r.fov / 2f >= partial.latMin - 1e-3f)
    }

    @Test
    fun `data narrower than the view centres on it`() {
        val narrow = SphereMeta(6000, 3000, 600, 3000, 2700, 0) // 36 degrees wide
        val l = SphereLook.clamp(Look(30f, 0f, 75f), narrow, 1f)
        assertEquals(0f, l.yaw, 1e-3f)
    }

    @Test
    fun `field of view is bounded by the limits and by the data`() {
        assertEquals(SphereLook.MIN_FOV, SphereLook.clamp(Look(0f, 0f, 1f), full, aspect).fov, 0f)
        assertEquals(SphereLook.MAX_FOV, SphereLook.clamp(Look(0f, 0f, 500f), full, aspect).fov, 0f)
        assertEquals(60f, SphereLook.clamp(Look(0f, 0f, 90f), partial, 1f).fov, 1e-3f) // 60 degrees of latitude
    }

    @Test
    fun `dragging right turns the camera left and dragging down looks up`() {
        val start = Look(0f, 0f, 60f)
        val l = SphereLook.drag(start, dx = 100f, dy = 100f, viewHeightPx = 600f, meta = full, aspect = aspect)
        assertEquals(-10f, l.yaw, 1e-3f)
        assertEquals(10f, l.pitch, 1e-3f)
    }

    @Test
    fun `zooming in narrows the view and stays in range`() {
        val z = SphereLook.zoom(Look(0f, 0f, 75f), 1.25f, full, aspect)
        assertEquals(60f, z.fov, 1e-3f)
        assertEquals(SphereLook.MIN_FOV, SphereLook.zoom(Look(0f, 0f, 31f), 10f, full, aspect).fov, 0f)
        assertEquals(75f, SphereLook.zoom(Look(0f, 0f, 75f), Float.NaN, full, aspect).fov, 0f)
    }

    @Test
    fun `button steps reach everywhere a drag can`() {
        var l = Look(0f, 0f, 60f)
        repeat(12) { l = SphereLook.step(l, 1, 0, full, aspect) }
        assertEquals(0f, l.yaw, 1e-3f) // 12 x 30 degrees is a full turn
        l = SphereLook.step(Look(0f, 0f, 60f), 0, 1, full, aspect)
        assertEquals(20f, l.pitch, 1e-3f)
    }

    @Test
    fun `initial view follows the author's heading within limits`() {
        val m = full.copy(initialHeading = 200f, initialPitch = 5f, initialFov = 90f)
        val l = SphereLook.initial(m, aspect)
        assertEquals(-160f, l.yaw, 1e-3f); assertEquals(5f, l.pitch, 1e-3f); assertEquals(90f, l.fov, 1e-3f)
    }
}
