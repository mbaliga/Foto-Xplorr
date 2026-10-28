package com.fotoxplorr.app.lift

import android.graphics.Bitmap

/**
 * The Android-facing half of lift: moves pixels in and out of a [Bitmap] and hands the actual
 * decision-making to [SubjectSegmentation], which is pure Kotlin and carries its own unit tests.
 * Mirrors how [com.fotoxplorr.app.editor.AdjustmentRenderer] relates to `Adjustments.kt` --
 * anything with a decision in it belongs on the pure side of that line, not here.
 */
object LiftRenderer {

    /** A cut-out ready to become a sticker: transparent outside the subject, tight to its bounds. */
    data class Cutout(val bitmap: Bitmap, val seedColor: Int = 0)

    /**
     * Segment [source] from ([seedX], [seedY]) by colour similarity and return a tightly-cropped,
     * alpha-punched-out bitmap, or null if the seed landed outside the image or nothing qualified
     * (an all-different neighbourhood at tolerance 0, or a zero-size source).
     *
     * The original one-tap path, kept for callers that want a single-shot cut without the editor's
     * selection history. [source] is read but never modified or recycled, matching every other
     * renderer in this app.
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
        return cutOutPixels(pixels, width, height, mask)?.let { Cutout(it, seedColor) }
    }

    /**
     * Cut [mask] out of [source]. The mask may be any size -- the editor solves at ~512px and this
     * is also called on the 2048px export decode -- and is resampled to the source's grid first.
     * Null when the mask selects nothing. [source] is never modified or recycled.
     */
    fun cutOut(source: Bitmap, mask: SubjectSegmentation.Mask): Cutout? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        return cutOutPixels(pixels, width, height, mask)?.let { Cutout(it) }
    }

    /** [source] with [mask] applied as alpha, same size, uncropped: the editor's live cut-out preview. */
    fun maskedPreview(source: Bitmap, mask: SubjectSegmentation.Mask): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        return bitmapOf(SelectionOps.maskedPixels(pixels, width, height, mask), width, height)
    }

    /** The selection as a translucent [rgb] wash at the mask's own size, drawn over the photo by the editor. */
    fun selectionOverlay(mask: SubjectSegmentation.Mask, rgb: Int, maxAlpha: Int): Bitmap =
        bitmapOf(SelectionOps.overlayPixels(mask, rgb, maxAlpha), mask.width, mask.height)

    /** A copy of [source] scaled so its long edge is [longEdge], or [source] itself when already that small. */
    fun scaledToLongEdge(source: Bitmap, longEdge: Int): Bitmap {
        val (w, h) = SelectionOps.workingSize(source.width, source.height, longEdge)
        return if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun cutOutPixels(pixels: IntArray, width: Int, height: Int, mask: SubjectSegmentation.Mask): Bitmap? {
        // A little padding beyond the mask's own bounds, clamped to the source: the feather pass
        // fades alpha out gradually rather than stopping dead at the mask's hard edge, and cropping
        // exactly to that edge would clip the fade-out and put a hard edge straight back where the
        // whole point of feathering was to remove one.
        val cut = SelectionOps.cutPixels(pixels, width, height, mask, PADDING_PX) ?: return null
        return bitmapOf(cut.pixels, cut.width, cut.height)
    }

    private fun bitmapOf(pixels: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /** Padding, in source pixels, added around the mask's tight bounding box. See [cutOut]. */
    private const val PADDING_PX = 4
}
