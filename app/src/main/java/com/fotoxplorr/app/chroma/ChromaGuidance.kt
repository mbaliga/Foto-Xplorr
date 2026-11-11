package com.fotoxplorr.app.chroma

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * What the room's own light looks like from the exposure the camera chose for it. Auto exposure
 * makes every preview about as bright as any other, so the picture cannot say how bright the room
 * is; the exposure time times the ISO can: the brighter the room, the smaller the product.
 *
 * The threshold is a guess from typical phone exposures (a lit indoor room is about 1/60 s at ISO
 * 400, a product near 7; daylight through a window is well under 2). It only picks between two
 * suggested distances; nothing is refused on it.
 */
internal object RoomLight {
    /** Exposure seconds times ISO below which the room counts as bright. */
    const val BRIGHT_BELOW = 2.0

    fun isBright(exposureNanos: Long?, iso: Int?): Boolean {
        if (exposureNanos == null || iso == null || exposureNanos <= 0L || iso <= 0) return false
        return exposureNanos / 1e9 * iso < BRIGHT_BELOW
    }
}

/**
 * The distance to hold the phone from the surface. The flash must outshine the room at the spot,
 * and its light falls with the square of the distance, so a bright room wants the phone nearer; but
 * the lens cannot focus nearer than its minimum, and a long way gives a thin flash.
 */
internal object HoldDistance {
    const val NEAR_CM = 10
    const val USUAL_CM = 15
    const val FARTHEST_CM = 30

    /** How far from the target, as a fraction, still counts as the right distance. */
    const val SLACK = 0.25

    fun targetCm(minFocusDiopters: Float?, brightRoom: Boolean): Int {
        val want = if (brightRoom) NEAR_CM else USUAL_CM
        // Nearest focus in cm, plus a little, so the autofocus can still settle and report a distance.
        val floor = if (minFocusDiopters != null && minFocusDiopters > 0f) ceil(100.0 / minFocusDiopters).toInt() + 2 else 0
        return min(max(want, floor), FARTHEST_CM)
    }

    fun hint(measuredMetres: Double?, targetCm: Int): DistanceHint {
        if (measuredMetres == null) return DistanceHint.UNKNOWN
        val ratio = measuredMetres * 100.0 / targetCm
        return when {
            ratio > 1.0 + SLACK -> DistanceHint.CLOSER
            ratio < 1.0 - SLACK -> DistanceHint.FARTHER
            else -> DistanceHint.GOOD
        }
    }
}

internal enum class DistanceHint { UNKNOWN, CLOSER, FARTHER, GOOD }

/**
 * How still the phone is, from the gyroscope's angular speed in radians per second (the length of the
 * three-axis vector). Samples are smoothed so one jolt does not flip the hint.
 *
 * The limits are guesses for a handheld phone (a steady hand is a few hundredths of a rad/s, a shaky
 * one several tenths) and have not been checked against a device. [beginWindow] to [moved] is how a
 * measurement notices that the phone moved between its two shots.
 */
internal class MotionWindow {
    private var smoothed = 0f
    private var seen = false
    private var windowPeak = 0f
    private var inWindow = false

    fun push(angularSpeed: Float) {
        if (angularSpeed.isNaN()) return
        smoothed = if (seen) smoothed + SMOOTHING * (angularSpeed - smoothed) else angularSpeed
        seen = true
        if (inWindow) windowPeak = max(windowPeak, smoothed)
    }

    /** Null until the gyroscope has reported (or on a phone without one). */
    val steady: Boolean? get() = if (seen) smoothed <= STEADY_BELOW else null

    fun beginWindow() {
        inWindow = true
        windowPeak = if (seen) smoothed else 0f
    }

    fun endWindow() {
        inWindow = false
    }

    /** True when the smoothed speed went above [MOVED_ABOVE] since [beginWindow]. */
    val moved: Boolean get() = windowPeak > MOVED_ABOVE

    companion object {
        const val SMOOTHING = 0.3f
        const val STEADY_BELOW = 0.15f
        const val MOVED_ABOVE = 0.35f
    }
}

/**
 * The shape the preview is shown in. The camera delivers its buffer in the sensor's orientation; for a
 * view that shows it rotated to the display, the width and height swap when the sensor and the display
 * differ by a quarter turn. This ASSUMES the camera service rotates a SurfaceView's picture for the
 * display, as it does for the system camera; if a phone does not, the picture is sideways but the
 * measurement (the middle patch) is unaffected.
 */
internal object PreviewShape {
    fun displayAspect(bufferWidth: Int, bufferHeight: Int, sensorOrientation: Int, displayRotationDegrees: Int): Double {
        val relative = ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360
        val swapped = relative == 90 || relative == 270
        return if (swapped) bufferHeight.toDouble() / bufferWidth else bufferWidth.toDouble() / bufferHeight
    }
}
