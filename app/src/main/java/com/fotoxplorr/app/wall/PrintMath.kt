package com.fotoxplorr.app.wall

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How sharp a print will look, by the pixels that reach each inch of it. */
internal enum class PrintQuality(val minDpi: Double) {
    EXCELLENT(300.0),
    GOOD(200.0),

    /** Fine on a wall, seen from a step or two away; not for holding in the hand. */
    FINE(150.0),
    SOFT(0.0),
    ;

    companion object {
        fun of(dpi: Double): PrintQuality = entries.first { dpi >= it.minDpi }
    }
}

/** The part of a photo a frame shows, as fractions of the photo's width and height. */
internal data class CoverCrop(val keptWidth: Double, val keptHeight: Double) {
    val isCropped: Boolean get() = keptWidth < 0.999 || keptHeight < 0.999
}

/**
 * How many pixels a print needs, and what a photo's pixels give. Pure and in centimetres; a caller
 * with inches converts with [CM_PER_INCH]. A photo is always taken to *cover* its frame (the part
 * that does not fit the frame's shape is cut off), so every figure here includes that crop.
 *
 * The photo's size must be the upright decoded size (`DecodedBitmap.sourceWidth/Height`), not
 * `MediaAsset.width/height`, which can be the sensor's un-rotated grid (TRAPS #48).
 */
internal object PrintMath {
    const val CM_PER_INCH = 2.54

    /** The lowest dpi the "prints well up to" figure promises. */
    const val WELL_DPI = 150

    fun pixelsForCm(cm: Double, dpi: Int): Int = (cm / CM_PER_INCH * dpi).roundToInt()

    fun pixelsForInches(inches: Double, dpi: Int): Int = (inches * dpi).roundToInt()

    /** Pixels per inch reaching a [frameWcm] x [frameHcm] print from a photo, after the cover crop. 0 for a size of 0. */
    fun effectiveDpi(photoW: Int, photoH: Int, frameWcm: Double, frameHcm: Double): Double {
        if (photoW <= 0 || photoH <= 0 || frameWcm <= 0.0 || frameHcm <= 0.0) return 0.0
        return CM_PER_INCH * min(photoW / frameWcm, photoH / frameHcm)
    }

    fun coverCrop(photoW: Int, photoH: Int, frameWcm: Double, frameHcm: Double): CoverCrop {
        if (photoW <= 0 || photoH <= 0 || frameWcm <= 0.0 || frameHcm <= 0.0) return CoverCrop(1.0, 1.0)
        val photoAspect = photoW.toDouble() / photoH
        val frameAspect = frameWcm / frameHcm
        return if (frameAspect < photoAspect) {
            CoverCrop(keptWidth = frameAspect / photoAspect, keptHeight = 1.0)
        } else {
            CoverCrop(keptWidth = 1.0, keptHeight = photoAspect / frameAspect)
        }
    }

    /**
     * The largest print of the given shape ([aspect] = width over height) that still gets [dpi],
     * as (width, height) in cm. A shape other than the photo's loses the cropped part.
     */
    fun largestCm(photoW: Int, photoH: Int, aspect: Double, dpi: Int = WELL_DPI): Pair<Double, Double> {
        if (photoW <= 0 || photoH <= 0 || aspect <= 0.0 || dpi <= 0) return 0.0 to 0.0
        val height = min(photoW * CM_PER_INCH / (aspect * dpi), photoH * CM_PER_INCH / dpi)
        return aspect * height to height
    }

    /** The same, in the photo's own shape. */
    fun largestCm(photoW: Int, photoH: Int, dpi: Int = WELL_DPI): Pair<Double, Double> =
        largestCm(photoW, photoH, photoW.toDouble() / max(1, photoH), dpi)

    /** Whole centimetres, rounded down, so the promise is never a hair too big. */
    fun floorCm(cm: Double): Int = floor(cm + 1e-6).toInt()
}
