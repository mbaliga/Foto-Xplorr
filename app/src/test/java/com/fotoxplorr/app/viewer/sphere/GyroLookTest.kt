package com.fotoxplorr.app.viewer.sphere

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GyroLookTest {
    // Phone upright, back facing north: device x = east, y = up, z = south.
    private val facingNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    /** The same upright phone after turning clockwise (seen from above) by [deg]. */
    private fun facing(deg: Double): FloatArray {
        val c = cos(Math.toRadians(deg)).toFloat(); val s = sin(Math.toRadians(deg)).toFloat()
        // device x -> (c, -s, 0), y -> up, z (south-back) -> (-(s), -c, 0)... built from the back direction
        // b = (s, c, 0) (east, north): z = -b, x = up x ... keep right-handed: x = (c, -s, 0).
        return floatArrayOf(c, 0f, -s, -s, 0f, -c, 0f, 1f, 0f)
    }

    @Test
    fun `upright phone facing north looks at yaw 0 pitch 0`() {
        val (yaw, pitch) = GyroLook.yawPitchOf(facingNorth)
        assertEquals(0f, yaw, 1e-4f); assertEquals(0f, pitch, 1e-4f)
    }

    @Test
    fun `turning the phone clockwise raises yaw`() {
        val (yaw, _) = GyroLook.yawPitchOf(facing(90.0))
        assertEquals(90f, yaw, 1e-3f)
        assertEquals(-90f, GyroLook.yawPitchOf(facing(-90.0)).first, 1e-3f)
    }

    @Test
    fun `phone lying face up looks straight down and straight up reports no heading`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val (yaw, pitch) = GyroLook.yawPitchOf(identity)
        assertEquals(-90f, pitch, 1e-3f); assertEquals(0f, yaw, 0f)
    }

    @Test
    fun `the first reading is the starting view and later turns are relative`() {
        val g = GyroLook(smoothing = 1f)
        assertEquals(0f, g.update(facing(120.0)).first, 1e-3f)
        assertEquals(30f, g.update(facing(150.0)).first, 1e-3f)
    }

    @Test
    fun `smoothing takes the short way across the seam`() {
        val g = GyroLook(smoothing = 0.5f)
        g.update(facing(0.0))
        g.update(facing(179.0))
        val y = g.update(facing(-179.0)).first // 179 -> -179 is a 2 degree turn
        assertEquals(true, y > 100f || y < -100f)
    }
}
