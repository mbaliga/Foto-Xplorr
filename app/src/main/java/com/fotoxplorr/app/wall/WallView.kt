package com.fotoxplorr.app.wall

import kotlin.math.min

/**
 * Where the wall photo sits in a drawing area, and how its three coordinate systems relate:
 * the photo's own pixels (as decoded), the area's pixels, and a frame's centre (a share of the
 * photo's width on both axes, see [WallFrame]). Pure, so the mapping the gestures rely on is
 * tested without a screen.
 *
 * The photo is fitted whole and centred. [pxPerCm] is area pixels per centimetre of wall, 0 until
 * the wall is calibrated.
 */
internal class WallView(
    val bitmapW: Int,
    val bitmapH: Int,
    val areaW: Float,
    val areaH: Float,
    val wallWidthCm: Float,
) {
    val scale: Float = if (bitmapW > 0 && bitmapH > 0) min(areaW / bitmapW, areaH / bitmapH) else 0f
    val offX: Float = (areaW - bitmapW * scale) / 2
    val offY: Float = (areaH - bitmapH * scale) / 2
    val pxPerCm: Float = WallScale.pxPerCm(bitmapW, wallWidthCm) * scale
    val wallHeightCm: Float get() = if (bitmapW > 0) wallWidthCm * bitmapH / bitmapW else 0f

    /** Area position of a frame centre given as shares of the photo's width. */
    fun toArea(fx: Float, fy: Float): Pair<Float, Float> = (offX + fx * bitmapW * scale) to (offY + fy * bitmapW * scale)

    /** The share-of-width centre at an area position. */
    fun toShare(x: Float, y: Float): Pair<Float, Float> = ((x - offX) / (bitmapW * scale)) to ((y - offY) / (bitmapW * scale))

    /** The photo's own pixel at an area position. */
    fun toBitmap(x: Float, y: Float): Pair<Float, Float> = ((x - offX) / scale) to ((y - offY) / scale)

    /** The wall position in cm at an area position (the same axes as a frame's centre in cm). */
    fun toCm(x: Float, y: Float): Pair<Float, Float> {
        val (sx, sy) = toShare(x, y)
        return sx * wallWidthCm to sy * wallWidthCm
    }

    /** Whether an area position is on the photo. */
    fun contains(x: Float, y: Float): Boolean =
        x >= offX && y >= offY && x <= offX + bitmapW * scale && y <= offY + bitmapH * scale

    /** The topmost frame whose bordered box (grown by [slopCm] each side) holds the area position, or null. */
    fun frameAt(frames: List<WallFrame>, borderCm: Float, slopCm: Float, x: Float, y: Float): Int? {
        if (wallWidthCm <= 0f) return null
        val (px, py) = toCm(x, y)
        return frames.indices.reversed().firstOrNull { i ->
            val f = frames[i]
            FramePresets.hits(
                f.cx * wallWidthCm, f.cy * wallWidthCm,
                f.wCm + 2 * (borderCm + slopCm), f.hCm + 2 * (borderCm + slopCm),
                f.rotationDeg, px, py,
            )
        }
    }
}
