package com.fotoxplorr.app.viewer

/**
 * Where the pinch-zoomed viewport sits within the full image, as fractions of the image's own
 * extent -- the one piece of arithmetic behind the zoom minimap, pulled out so it can be checked
 * without a container or a gesture.
 *
 * The maths assumes the image is laid out `ContentScale.Fit`, centred in the container, and that
 * the zoom layer scales and translates about that same centre -- which is exactly how the viewer
 * drives `scale`/`offsetX`/`offsetY`.
 */
object ZoomViewport {

    /** Fractions of the image, 0..1 on each axis, that the container currently shows. */
    data class Fractions(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * @param imageWidth/[imageHeight] the source photo's pixel size. Zero or negative (a file whose
     *   dimensions MediaStore never recorded) is treated as square rather than dividing by it.
     * @param containerWidth/[containerHeight] the viewer's size in px. Zero returns the whole image.
     * @param scale the current zoom, >= 1.
     * @param offsetX/[offsetY] the current pan in px, as applied AFTER the scale about the centre.
     */
    fun fractions(
        imageWidth: Int,
        imageHeight: Int,
        containerWidth: Float,
        containerHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Fractions = withFractions(imageWidth, imageHeight, containerWidth, containerHeight, scale, offsetX, offsetY) {
        left, top, right, bottom ->
        Fractions(left, top, right, bottom)
    }

    /**
     * The same answer as [fractions], handed to [block] as four floats. Inline, so a draw lambda
     * that runs on every frame of a pan can read the viewport without allocating anything.
     */
    inline fun <R> withFractions(
        imageWidth: Int,
        imageHeight: Int,
        containerWidth: Float,
        containerHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        block: (left: Float, top: Float, right: Float, bottom: Float) -> R,
    ): R {
        if (containerWidth <= 0f || containerHeight <= 0f || scale <= 0f || scale.isNaN()) {
            return block(0f, 0f, 1f, 1f)
        }
        val imageAspect = aspect(imageWidth, imageHeight)
        val containerAspect = containerWidth / containerHeight
        val fittedWidth = if (imageAspect > containerAspect) containerWidth else containerHeight * imageAspect
        val fittedHeight = if (imageAspect > containerAspect) containerWidth / imageAspect else containerHeight
        val centreX = containerWidth / 2f
        val centreY = containerHeight / 2f
        val left = (0.5f + (0f - centreX - offsetX) / scale / fittedWidth).coerceIn(0f, 1f)
        val right = (0.5f + (containerWidth - centreX - offsetX) / scale / fittedWidth).coerceIn(0f, 1f)
        val top = (0.5f + (0f - centreY - offsetY) / scale / fittedHeight).coerceIn(0f, 1f)
        val bottom = (0.5f + (containerHeight - centreY - offsetY) / scale / fittedHeight).coerceIn(0f, 1f)
        return block(left, top, maxOf(right, left), maxOf(bottom, top))
    }

    /** Width / height, or 1 for a photo whose size is unknown. Never zero, never infinite. */
    fun aspect(imageWidth: Int, imageHeight: Int): Float =
        if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 1f

    /**
     * The minimap's own box, in dp: [longEdgeDp] on its longer side, the other side following the
     * image's aspect, clamped so a panorama or a tall scan still produces a box a finger can read.
     */
    fun boxSizeDp(imageWidth: Int, imageHeight: Int, longEdgeDp: Float): Pair<Float, Float> {
        val a = aspect(imageWidth, imageHeight).coerceIn(MIN_BOX_ASPECT, MAX_BOX_ASPECT)
        return if (a >= 1f) longEdgeDp to longEdgeDp / a else longEdgeDp * a to longEdgeDp
    }

    private const val MIN_BOX_ASPECT = 0.4f
    private const val MAX_BOX_ASPECT = 2.5f
}
