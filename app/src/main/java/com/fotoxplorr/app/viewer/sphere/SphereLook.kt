package com.fotoxplorr.app.viewer.sphere

import kotlin.math.atan
import kotlin.math.tan

/** Where the camera points: yaw (degrees, east positive), pitch (up positive) and VERTICAL field of view. */
data class Look(val yaw: Float, val pitch: Float, val fov: Float)

/**
 * Look-around maths: dragging, pinching, button steps, inertia and the limits of a partial sphere.
 * Pure, so "you cannot look past the edge of a panorama" and "a whole sphere wraps" are tests.
 */
object SphereLook {
    const val MIN_FOV = 30f
    const val MAX_FOV = 100f
    const val DEFAULT_FOV = 75f

    /** The step a button press turns by, so every drag has a tap alternative. */
    const val STEP_YAW = 30f
    const val STEP_PITCH = 20f
    const val STEP_ZOOM = 1.25f

    /** Horizontal field of view for a vertical [fov] at [aspect] (width / height). */
    fun hFov(fov: Float, aspect: Float): Float =
        Math.toDegrees(2.0 * atan(tan(Math.toRadians(fov / 2.0)) * aspect)).toFloat()

    fun initial(meta: SphereMeta, aspect: Float): Look =
        clamp(Look(meta.initialHeading, meta.initialPitch, meta.initialFov ?: DEFAULT_FOV), meta, aspect)

    /**
     * Pulls [look] inside what the picture covers: the field of view no wider than the data, pitch
     * and (for a partial sphere) yaw kept so the view's edges stay on the picture; a whole round
     * sphere just wraps yaw into (-180, 180]. When the data is narrower than the view the view centres on it.
     */
    fun clamp(look: Look, meta: SphereMeta, aspect: Float): Look {
        val maxVFov = minOf(MAX_FOV, meta.latSpan.coerceAtLeast(MIN_FOV))
        val fov = look.fov.coerceIn(MIN_FOV.coerceAtMost(maxVFov), maxVFov)
        val halfV = fov / 2f
        val pitch = rangeClamp(look.pitch, meta.latMin + halfV, meta.latMax - halfV)
        val yaw = if (meta.wrapsHorizontally) {
            wrap(look.yaw)
        } else {
            val halfH = hFov(fov, aspect) / 2f
            rangeClamp(look.yaw, meta.lonMin + halfH, meta.lonMax - halfH)
        }
        return Look(yaw, pitch, fov)
    }

    /** Clamp into [lo]..[hi]; an empty range (data narrower than the view) gives its middle. */
    private fun rangeClamp(v: Float, lo: Float, hi: Float): Float = if (lo >= hi) (lo + hi) / 2f else v.coerceIn(lo, hi)

    /** Into (-180, 180]. */
    fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** A drag of ([dx], [dy]) px on a view [viewHeightPx] tall: the world follows the finger, so the camera turns the other way. */
    fun drag(look: Look, dx: Float, dy: Float, viewHeightPx: Float, meta: SphereMeta, aspect: Float): Look {
        if (viewHeightPx <= 0f) return look
        val perPx = look.fov / viewHeightPx
        return clamp(look.copy(yaw = look.yaw - dx * perPx, pitch = look.pitch + dy * perPx), meta, aspect)
    }

    fun zoom(look: Look, factor: Float, meta: SphereMeta, aspect: Float): Look {
        if (!factor.isFinite() || factor <= 0f) return look
        return clamp(look.copy(fov = look.fov / factor), meta, aspect)
    }

    /** One button step: [dx] -1/+1 turns left/right, [dy] -1/+1 down/up. */
    fun step(look: Look, dx: Int, dy: Int, meta: SphereMeta, aspect: Float): Look =
        clamp(look.copy(yaw = look.yaw + dx * STEP_YAW, pitch = look.pitch + dy * STEP_PITCH), meta, aspect)
}
