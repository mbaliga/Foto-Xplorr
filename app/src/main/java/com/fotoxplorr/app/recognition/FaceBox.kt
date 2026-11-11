package com.fotoxplorr.app.recognition

import kotlin.math.max
import kotlin.math.min

/**
 * Where one detected face sat in the photo it came from, normalised to 0..1 of the UPRIGHT frame
 * the detector saw (the indexer decodes with `decodeUpright`, so EXIF orientation is already in
 * the pixels and the box is directly comparable with any other upright decode of the same file).
 *
 * [frameAspect] is that frame's width over its height. It is stored because a square crop is a
 * square in PIXELS, and a normalised box alone cannot say how wide a pixel is relative to a row:
 * the asset's catalogue width/height are not reliably post-orientation.
 */
data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameAspect: Float,
) {
    /** False for a box that cannot be cropped (inverted, outside the frame, or a zero-size frame). */
    val isUsable: Boolean
        get() = frameAspect > 0f && right > left && bottom > top &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f
}

/** A rectangle in 0..1 of the frame, as [FaceCrop] returns it. */
data class NormalisedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The same rectangle on a [width] x [height] bitmap, at least one pixel each way, inside it. */
    fun toPixels(width: Int, height: Int): IntArray {
        val l = (left * width).toInt().coerceIn(0, max(width - 1, 0))
        val t = (top * height).toInt().coerceIn(0, max(height - 1, 0))
        val r = (right * width).toInt().coerceIn(l + 1, max(width, l + 1))
        val b = (bottom * height).toInt().coerceIn(t + 1, max(height, t + 1))
        return intArrayOf(l, t, r - l, b - t)
    }
}

/**
 * The crop a person's card shows: the face with air round it, as a square in pixels, kept inside
 * the photo.
 *
 * Pure so the geometry is testable. The three rules, in order: grow the box by [margin] of its
 * longer side on every side (a tight box cuts off the chin and hair, and a card of just a nose is
 * not a face anyone recognises); make it square about the same centre; then clamp -- shrink to the
 * frame's shorter side if it is bigger than the frame, and slide it back inside if it hangs over
 * an edge, rather than cropping the face off-centre-and-short.
 */
object FaceCrop {
    const val DEFAULT_MARGIN = 0.45f

    fun squareAround(box: FaceBox, margin: Float = DEFAULT_MARGIN): NormalisedRect? {
        if (!box.isUsable) return null
        // Work in "frame units": height 1, width = aspect, so a square is a square.
        val aspect = box.frameAspect
        val x0 = box.left * aspect
        val x1 = box.right * aspect
        val y0 = box.top
        val y1 = box.bottom
        val longSide = max(x1 - x0, y1 - y0)
        val side = min(longSide * (1f + 2f * margin.coerceAtLeast(0f)), min(aspect, 1f))
        val centreX = (x0 + x1) / 2f
        val centreY = (y0 + y1) / 2f
        val left = (centreX - side / 2f).coerceIn(0f, aspect - side)
        val top = (centreY - side / 2f).coerceIn(0f, 1f - side)
        return NormalisedRect(left / aspect, top, (left + side) / aspect, top + side)
    }
}
