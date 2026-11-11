package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The part of the board a sketch actually uses, so the rasteriser draws just that and a tiny
 * doodle gets the same sprite resolution as a big one (the strokes are vectors; nothing is scaled
 * up from pixels).
 *
 * Measured in BOARD-WIDTH units: a point (x, y) of the board is at u = x, v = y / [Sketch.BOARD_ASPECT],
 * so a brush width (a fraction of the board width) is the same length in both directions and a circle
 * stays round whatever the window.
 */
class SketchWindow private constructor(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val width: Float get() = u1 - u0
    val height: Float get() = v1 - v0

    /** The window's size in pixels with its long edge [longEdge] long; both sides at least 1. */
    fun pixelSize(longEdge: Int): Pair<Int, Int> {
        val long = max(1, longEdge)
        val scale = long / max(width, height)
        return max(1, ceil(width * scale - 1e-3f).toInt()) to max(1, ceil(height * scale - 1e-3f).toInt())
    }

    /** [p] (board-normalised) as a point normalised to this window: 0..1 across its width and height. */
    fun toWindow(p: NormPoint): NormPoint =
        NormPoint((p.x - u0) / width, (p.y / Sketch.BOARD_ASPECT - v0) / height)

    /** A brush width (fraction of the board width) as a fraction of this window's width. */
    fun widthInWindow(boardWidth: Float): Float = boardWidth / width

    companion object {
        /** Room around the ink so antialiasing is never cut, in board widths. */
        private const val PAD = 0.004f
        private const val MIN_SIZE = 0.01f

        /** The window around every stroke (ink thickness included), or null when there is no usable point. */
        fun of(strokes: List<DrawStroke>): SketchWindow? {
            var minU = Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (s in strokes) {
                val half = if (s.width.isFinite()) s.width / 2f else 0f
                for (p in s.points) {
                    if (!p.x.isFinite() || !p.y.isFinite()) continue
                    val v = p.y / Sketch.BOARD_ASPECT
                    minU = min(minU, p.x - half)
                    maxU = max(maxU, p.x + half)
                    minV = min(minV, v - half)
                    maxV = max(maxV, v + half)
                }
            }
            if (minU > maxU) return null
            val u0 = max(0f, minU - PAD)
            val u1 = min(1f, maxU + PAD)
            val v0 = max(0f, minV - PAD)
            val v1 = min(1f / Sketch.BOARD_ASPECT, maxV + PAD)
            // A window can never be empty, even for a dot exactly on a corner.
            return SketchWindow(u0, v0, max(u1, u0 + MIN_SIZE), max(v1, v0 + MIN_SIZE))
        }
    }
}

/** Pure pixel steps of making a sprite: paint a fill mask, then crop and pad. ARGB, row-major. */
object SpritePixels {
    /** Transparent margin kept around the picture, so the mesh warp never samples past its edge. */
    const val BORDER = 2

    /** The longest side the drawn part gets; with the border the sprite is at most [MAX_SIDE]. */
    const val CONTENT_LONG_EDGE = 284
    const val MAX_SIDE = CONTENT_LONG_EDGE + 2 * BORDER

    /** Sets every pixel under [mask] to [argb]; sizes that do not match leave [pixels] alone. */
    fun paintMask(pixels: IntArray, mask: BooleanArray, argb: Int) {
        if (mask.size != pixels.size) return
        for (i in pixels.indices) if (mask[i]) pixels[i] = argb
    }

    /** A cropped, padded image. */
    class Cropped(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Cuts [bounds] out of the [w] x [h] [pixels] and puts [border] transparent pixels round it.
     * Returns null when [bounds] is empty or the sizes disagree.
     */
    fun cropAndPad(pixels: IntArray, w: Int, h: Int, bounds: IntBounds, border: Int = BORDER): Cropped? {
        if (w <= 0 || h <= 0 || pixels.size.toLong() != w.toLong() * h || border < 0) return null
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > w || bounds.bottom > h) return null
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val outW = bounds.width + 2 * border
        val outH = bounds.height + 2 * border
        val out = IntArray(outW * outH)
        for (y in 0 until bounds.height) {
            System.arraycopy(pixels, (bounds.top + y) * w + bounds.left, out, (y + border) * outW + border, bounds.width)
        }
        return Cropped(out, outW, outH)
    }
}
