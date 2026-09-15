package com.fotoxplorr.app.lift

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * The Android-facing half of lift: moves pixels in and out of a [Bitmap] and hands the actual
 * decision-making to [SubjectSegmentation], which is pure Kotlin and carries its own unit tests.
 * Mirrors how [com.fotoxplorr.app.editor.AdjustmentRenderer] relates to `Adjustments.kt` --
 * anything with a decision in it belongs on the pure side of that line, not here.
 */
object LiftRenderer {

    /** A cut-out ready to become a sticker: transparent outside the subject, tight to its bounds. */
    data class Cutout(val bitmap: Bitmap, val seedColor: Int)

    /**
     * Segment [source] from ([seedX], [seedY]) and return a tightly-cropped, alpha-punched-out
     * bitmap, or null if the seed landed outside the image or nothing qualified (an all-different
     * neighbourhood at tolerance 0, or a zero-size source).
     *
     * [source] is read but never modified or recycled, matching every other renderer in this app.
     */
    fun cutOut(
        source: Bitmap,
        seedX: Int,
        seedY: Int,
        tolerance: Int = SubjectSegmentation.DEFAULT_TOLERANCE,
        featherRadius: Int = SubjectSegmentation.DEFAULT_FEATHER_RADIUS,
    ): Cutout? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0 || seedX !in 0 until width || seedY !in 0 until height) return null

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val seedColor = pixels[seedY * width + seedX]

        val mask = SubjectSegmentation.grow(pixels, width, height, seedX, seedY, tolerance, featherRadius)
        val box = SubjectSegmentation.boundingBox(mask) ?: return null

        // A little padding beyond the mask's own bounds, clamped to the source: the feather pass
        // fades alpha out gradually rather than stopping dead at the mask's hard edge, and cropping
        // exactly to that edge would clip the fade-out and put a hard edge straight back where the
        // whole point of feathering was to remove one.
        val left = (box.left - PADDING_PX).coerceIn(0, width - 1)
        val top = (box.top - PADDING_PX).coerceIn(0, height - 1)
        val right = (box.right + PADDING_PX).coerceIn(left + 1, width)
        val bottom = (box.bottom + PADDING_PX).coerceIn(top + 1, height)
        val cutW = right - left
        val cutH = bottom - top

        val output = IntArray(cutW * cutH)
        for (y in 0 until cutH) {
            val srcRow = (y + top) * width
            val dstRow = y * cutW
            for (x in 0 until cutW) {
                val srcIndex = srcRow + (x + left)
                val alpha = mask.alpha[srcIndex]
                if (alpha <= 0f) {
                    output[dstRow + x] = 0
                    continue
                }
                val pixel = pixels[srcIndex]
                val sourceAlpha = (pixel ushr 24) and 0xFF
                val outAlpha = (sourceAlpha * alpha).roundToInt().coerceIn(0, 255)
                output[dstRow + x] = (outAlpha shl 24) or (pixel and 0x00FFFFFF)
            }
        }

        val bitmap = Bitmap.createBitmap(cutW, cutH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(output, 0, cutW, 0, 0, cutW, cutH)
        return Cutout(bitmap, seedColor)
    }

    /** Padding, in source pixels, added around the mask's tight bounding box. See [cutOut]. */
    private const val PADDING_PX = 4
}
