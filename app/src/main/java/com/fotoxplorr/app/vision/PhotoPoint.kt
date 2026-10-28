package com.fotoxplorr.app.vision

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A pixel of a decoded picture. */
internal data class PixelPoint(val x: Int, val y: Int)

/**
 * The geometry behind "tap a colour to name it": which pixel of the decoded picture lies under a
 * finger, which colour to read there, and where the answer is put. Pure; nothing here knows
 * Compose or a bitmap.
 */
internal object PhotoPoint {
    /**
     * The pixel of an [imageWidth] x [imageHeight] picture under the container point ([tapX], [tapY]),
     * or null when the point is outside the picture (the black around it, or past an edge once zoomed).
     *
     * The viewer draws the picture `ContentScale.Fit` in a [containerWidth] x [containerHeight] box,
     * then scales it by [scale] about the box centre, turns it by [rotationDegrees] about the same
     * centre and moves it by ([offsetX], [offsetY]) (its graphics layer). The tap arrives in the box's
     * own coordinates, outside that layer, so this undoes the layer step by step. The decoded picture
     * may be smaller than the file, so only its shape matters here, not its size.
     */
    fun toPixel(
        tapX: Float,
        tapY: Float,
        containerWidth: Float,
        containerHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        rotationDegrees: Float = 0f,
    ): PixelPoint? {
        if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
        if (scale <= 0f || scale.isNaN()) return null
        val centreX = containerWidth / 2f
        val centreY = containerHeight / 2f
        var dx = tapX - centreX - offsetX
        var dy = tapY - centreY - offsetY
        if (rotationDegrees != 0f) {
            val radians = Math.toRadians(-rotationDegrees.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            val rx = dx * c - dy * s
            val ry = dx * s + dy * c
            dx = rx
            dy = ry
        }
        val layerX = centreX + dx / scale
        val layerY = centreY + dy / scale

        val imageAspect = imageWidth.toFloat() / imageHeight
        val containerAspect = containerWidth / containerHeight
        val fittedWidth = if (imageAspect > containerAspect) containerWidth else containerHeight * imageAspect
        val fittedHeight = if (imageAspect > containerAspect) containerWidth / imageAspect else containerHeight
        val u = (layerX - (containerWidth - fittedWidth) / 2f) / fittedWidth
        val v = (layerY - (containerHeight - fittedHeight) / 2f) / fittedHeight
        if (u < 0f || u >= 1f || v < 0f || v >= 1f) return null
        return PixelPoint(
            min((u * imageWidth).toInt(), imageWidth - 1),
            min((v * imageHeight).toInt(), imageHeight - 1),
        )
    }

    /**
     * The colour to name at ([x], [y]): the mean, in linear light, of the square of pixels within
     * [radius] of it (clipped to the picture), so one noisy or sharpened pixel does not decide the
     * name. [radius] 0 is the single pixel. Alpha is ignored and the result is opaque.
     */
    fun sample(argb: IntArray, width: Int, height: Int, x: Int, y: Int, radius: Int = 2): Int {
        require(width > 0 && height > 0 && argb.size >= width * height) { "pixel array too short" }
        val x0 = max(0, x - radius)
        val x1 = min(width - 1, x + radius)
        val y0 = max(0, y - radius)
        val y1 = min(height - 1, y + radius)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (yy in y0..y1) for (xx in x0..x1) {
            val c = argb[yy * width + xx]
            r += ColourSpace.decode8(ColourSpace.red(c))
            g += ColourSpace.decode8(ColourSpace.green(c))
            b += ColourSpace.decode8(ColourSpace.blue(c))
            n++
        }
        if (n == 0) return 0xFF000000.toInt()
        return ColourSpace.pack(255, ColourSpace.encode8(r / n), ColourSpace.encode8(g / n), ColourSpace.encode8(b / n))
    }
}

/** Where the corner of a floating label goes. */
internal data class LabelPosition(val left: Float, val top: Float)

/** Puts the colour-name label near the finger without covering it and clear of the shell's edge zones. */
internal object LabelPlacement {
    /**
     * The top-left corner of a [labelWidth] x [labelHeight] label for a tap at ([tapX], [tapY]) in a
     * [containerWidth] x [containerHeight] box. It sits centred above the finger with [gap] between,
     * drops below the finger when there is no room above, and is kept [margin] from every side (the
     * shell's 56 dp edge zones plus a little, so nothing that takes a tap or a drag sits in them).
     * A box too small for the label gets the margin on the top-left.
     */
    fun place(
        tapX: Float,
        tapY: Float,
        labelWidth: Float,
        labelHeight: Float,
        containerWidth: Float,
        containerHeight: Float,
        margin: Float,
        gap: Float,
    ): LabelPosition {
        val minLeft = margin
        val maxLeft = max(minLeft, containerWidth - margin - labelWidth)
        val left = (tapX - labelWidth / 2f).coerceIn(minLeft, maxLeft)
        val above = tapY - gap - labelHeight
        val below = tapY + gap
        val minTop = margin
        val maxTop = max(minTop, containerHeight - margin - labelHeight)
        val top = (if (above >= minTop) above else below).coerceIn(minTop, maxTop)
        return LabelPosition(left, top)
    }
}
