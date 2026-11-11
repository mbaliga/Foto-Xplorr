package com.fotoxplorr.app.wall

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A standard print size, held in cm and in portrait (width <= height). */
internal data class FrameSize(val label: String, val wCm: Float, val hCm: Float)

/** The moulding drawn around a picture. [moldingCm] is its width; [argb] its colour. */
internal enum class FrameStyle(val key: String, val moldingCm: Float, val argb: Long) {
    THIN_BLACK("black", 1.2f, 0xFF141414),
    WHITE("white", 1.8f, 0xFFF4F2EC),
    WOOD("wood", 2.4f, 0xFF9A6B43),
    NONE("none", 0f, 0x00000000),
    ;

    companion object {
        fun fromKey(key: String): FrameStyle? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The print sizes the screen offers and the pure geometry of a frame on the wall. Sizes are of the
 * PICTURE (the part that is printed); the mat and the moulding are drawn around it, outside, so
 * "40 x 50" always means a 40 x 50 cm print and the dpi chip never has to subtract anything.
 */
internal object FramePresets {
    /** cm first, then inches. */
    val all: List<FrameSize> = listOf(
        FrameSize("A4", 21.0f, 29.7f),
        FrameSize("A3", 29.7f, 42.0f),
        FrameSize("A2", 42.0f, 59.4f),
        FrameSize("20 x 30 cm", 20f, 30f),
        FrameSize("30 x 40 cm", 30f, 40f),
        FrameSize("40 x 50 cm", 40f, 50f),
        FrameSize("50 x 70 cm", 50f, 70f),
        FrameSize("61 x 91 cm", 61f, 91f),
        FrameSize("8 x 10 in", 20.32f, 25.4f),
        FrameSize("11 x 14 in", 27.94f, 35.56f),
        FrameSize("16 x 20 in", 40.64f, 50.8f),
        FrameSize("24 x 36 in", 60.96f, 91.44f),
    )

    const val MIN_SIDE_CM = 5f
    const val MAX_SIDE_CM = 200f
    const val MAT_MAX_CM = 8f

    /** How close (as a share of each side) a size must be to a preset to snap to it. */
    const val SNAP = 0.06f

    /**
     * The preset a [wCm] x [hCm] size is within [SNAP] of, in either orientation, returned in the
     * orientation asked for; null if none is close. The closest wins.
     */
    fun snap(wCm: Float, hCm: Float): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        var bestError = SNAP
        for (p in all) {
            for (flip in listOf(false, true)) {
                val pw = if (flip) p.hCm else p.wCm
                val ph = if (flip) p.wCm else p.hCm
                val error = max(abs(wCm - pw) / pw, abs(hCm - ph) / ph)
                if (error < bestError) {
                    bestError = error
                    best = pw to ph
                }
            }
        }
        return best
    }

    /** [wCm] x [hCm] times [factor], same shape, kept between [MIN_SIDE_CM] and [MAX_SIDE_CM] on both sides. */
    fun scaled(wCm: Float, hCm: Float, factor: Float): Pair<Float, Float> {
        val lowest = max(MIN_SIDE_CM / wCm, MIN_SIDE_CM / hCm)
        val highest = min(MAX_SIDE_CM / wCm, MAX_SIDE_CM / hCm)
        val f = factor.coerceIn(lowest, max(lowest, highest))
        return wCm * f to hCm * f
    }

    /** Whether the point ([px], [py]) is inside a [wCm] x [hCm] box centred ([cx], [cy]) and turned [rotationDeg]. */
    fun hits(cx: Float, cy: Float, wCm: Float, hCm: Float, rotationDeg: Float, px: Float, py: Float): Boolean {
        val r = Math.toRadians(-rotationDeg.toDouble())
        val dx = px - cx
        val dy = py - cy
        val lx = dx * cos(r) - dy * sin(r)
        val ly = dx * sin(r) + dy * cos(r)
        return abs(lx) <= wCm / 2 && abs(ly) <= hCm / 2
    }

    /** "29.7" or "30": a size without a trailing ".0". */
    fun number(v: Float): String {
        val tenths = (v * 10).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }

    fun sizeLabel(wCm: Float, hCm: Float, inches: Boolean): String =
        if (inches) {
            "${number(wCm / PrintMath.CM_PER_INCH.toFloat())} x ${number(hCm / PrintMath.CM_PER_INCH.toFloat())} in"
        } else {
            "${number(wCm)} x ${number(hCm)} cm"
        }
}
