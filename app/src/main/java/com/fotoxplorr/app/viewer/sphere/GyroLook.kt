package com.fotoxplorr.app.viewer.sphere

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Look-around by turning the phone, from a rotation matrix as `SensorManager.getRotationMatrixFromVector`
 * gives it (row-major 3x3, device -> world, world x = east, y = north, z = up). The camera looks along
 * the device's back (-Z). A game rotation vector has an arbitrary heading, so [GyroLook] records the first
 * reading as the starting view and reports turns relative to it. Pure; the sensor registration is the
 * seam ([SphereScreen]) and is a device check.
 */
class GyroLook(private val smoothing: Float = 0.25f) {
    private var baseYaw: Float? = null
    private var yaw = 0f
    private var pitch = 0f
    private var seeded = false

    /** The latest smoothed view change since the first reading: (yaw degrees, pitch degrees). */
    fun update(matrix: FloatArray): Pair<Float, Float> {
        val (rawYaw, rawPitch) = yawPitchOf(matrix)
        val base = baseYaw ?: rawYaw.also { baseYaw = it }
        val targetYaw = SphereLook.wrap(rawYaw - base)
        if (!seeded) {
            yaw = targetYaw; pitch = rawPitch; seeded = true
        } else {
            // Smooth along the shortest way round so 179 -> -179 is a 2 degree step, not 358.
            yaw = SphereLook.wrap(yaw + smoothing * SphereLook.wrap(targetYaw - yaw))
            pitch += smoothing * (rawPitch - pitch)
        }
        return yaw to pitch
    }

    fun reset() { baseYaw = null; seeded = false }

    companion object {
        /** Where the device's back points: (yaw clockwise from north, pitch up), degrees. */
        fun yawPitchOf(r: FloatArray): Pair<Float, Float> {
            require(r.size >= 9) { "a rotation matrix has 9 values" }
            val east = -r[2]; val north = -r[5]; val up = -r[8]
            val yaw = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
            val pitch = Math.toDegrees(asin(up.toDouble().coerceIn(-1.0, 1.0))).toFloat()
            // Straight up or down has no heading: keep it at 0 instead of noise.
            return (if (abs(up) > 0.9995f) 0f else yaw) to pitch
        }
    }
}
