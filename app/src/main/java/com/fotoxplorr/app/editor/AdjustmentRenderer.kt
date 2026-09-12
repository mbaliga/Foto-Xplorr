package com.fotoxplorr.app.editor

import android.graphics.Bitmap

/**
 * Applies [Adjustments] to a bitmap.
 *
 * The Android-facing half of the colour engine, and deliberately thin: it moves pixels in and out
 * of a `Bitmap` and hands the actual arithmetic to the pure functions in `Adjustments.kt`, which
 * are testable on the JVM. Anything with a decision in it belongs on that side of the line.
 *
 * The same code renders the interactive preview and the full-resolution export — the only
 * difference is the size of the bitmap handed in. Two pipelines would eventually disagree, and the
 * way you find out is a user exporting a photo that does not match what they were just looking at.
 */
object AdjustmentRenderer {

    /**
     * Render [source] through [adjustments], returning a new bitmap. [source] is never modified.
     *
     * Passes run in a fixed order, and the order is the reason this is one function rather than a
     * set of composable steps a caller sequences: tonal work must happen before the neighbourhood
     * passes, because sharpening a photo and then lifting its shadows amplifies the halo the
     * sharpen just created, while doing it the other way round does not.
     */
    fun render(source: Bitmap, adjustments: Adjustments): Bitmap {
        // ARGB_8888 because getPixels/setPixels is defined in terms of packed ARGB. A hardware
        // bitmap has no pixel array at all and throws here, which is exactly the sort of thing that
        // only shows up on the device that happened to decode one.
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Could not copy the photo for editing")
        if (adjustments.isIdentity) return output

        val width = output.width
        val height = output.height
        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)

        if (!adjustments.tonalIsIdentity) {
            applyColour(
                pixels = pixels,
                luts = adjustments.toChannelLuts(),
                saturation = adjustments.saturation,
                vibrance = adjustments.vibrance,
            )
        }
        if (adjustments.vignette != 0f) {
            applyVignette(pixels, width, height, adjustments.vignette)
        }
        if (adjustments.sharpen != 0f || adjustments.clarity != 0f) {
            applyUnsharpMask(pixels, width, height, adjustments.sharpen, adjustments.clarity)
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }
}

/**
 * Unsharp mask: subtract a blurred copy from the original to exaggerate the difference.
 *
 * Sharpen and clarity are the same operation at two radii, which is why they share a pass. Sharpen
 * works at a one-pixel radius and picks out edges; clarity works wide and picks out *local
 * contrast* — the difference between a face and the wall behind it rather than the difference
 * between one hair and the next. Running them as one pass over one blur each is half the work of
 * running them separately, and at 24 megapixels that is not a micro-optimisation.
 *
 * Separable box blur rather than a true Gaussian: two 1-D passes instead of one 2-D kernel, so the
 * cost is O(radius) per pixel rather than O(radius squared). At the radii used here the visual
 * difference from a Gaussian is not findable, and the cost difference at clarity's radius is
 * roughly twenty-fold.
 */
internal fun applyUnsharpMask(
    pixels: IntArray,
    width: Int,
    height: Int,
    sharpen: Float,
    clarity: Float,
) {
    if (width <= 0 || height <= 0) return
    if (sharpen != 0f) unsharp(pixels, width, height, SHARPEN_RADIUS, sharpen * SHARPEN_STRENGTH)
    if (clarity != 0f) unsharp(pixels, width, height, CLARITY_RADIUS, clarity * CLARITY_STRENGTH)
}

private fun unsharp(pixels: IntArray, width: Int, height: Int, radius: Int, amount: Float) {
    if (amount == 0f || radius < 1) return
    val blurred = pixels.copyOf()
    boxBlur(blurred, width, height, radius)

    for (i in pixels.indices) {
        val original = pixels[i]
        val blur = blurred[i]
        val alpha = original ushr 24 and 0xFF
        val r = unsharpChannel(original ushr 16 and 0xFF, blur ushr 16 and 0xFF, amount)
        val g = unsharpChannel(original ushr 8 and 0xFF, blur ushr 8 and 0xFF, amount)
        val b = unsharpChannel(original and 0xFF, blur and 0xFF, amount)
        pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }
}

private fun unsharpChannel(original: Int, blurred: Int, amount: Float): Int =
    (original + (original - blurred) * amount).toInt().coerceIn(0, 255)

/** A separable box blur, in place. Horizontal pass then vertical, using one scratch buffer. */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val scratch = IntArray(pixels.size)
    blurAxis(pixels, scratch, width, height, radius, horizontal = true)
    blurAxis(scratch, pixels, width, height, radius, horizontal = false)
}

private fun blurAxis(
    source: IntArray,
    destination: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val outer = if (horizontal) height else width
    val inner = if (horizontal) width else height

    for (o in 0 until outer) {
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0

        // Prime the window with the leading half, then slide it. A running sum makes each pixel
        // O(1) instead of O(radius) — the difference between a blur that takes a moment and one
        // that takes a minute at clarity's radius.
        for (i in 0..radius.coerceAtMost(inner - 1)) {
            val pixel = source[indexOf(o, i, width, horizontal)]
            sumR += pixel ushr 16 and 0xFF
            sumG += pixel ushr 8 and 0xFF
            sumB += pixel and 0xFF
            count++
        }

        for (i in 0 until inner) {
            val index = indexOf(o, i, width, horizontal)
            val alpha = source[index] ushr 24 and 0xFF
            destination[index] = (alpha shl 24) or
                ((sumR / count) shl 16) or
                ((sumG / count) shl 8) or
                (sumB / count)

            val leaving = i - radius
            val entering = i + radius + 1
            if (leaving >= 0) {
                val pixel = source[indexOf(o, leaving, width, horizontal)]
                sumR -= pixel ushr 16 and 0xFF
                sumG -= pixel ushr 8 and 0xFF
                sumB -= pixel and 0xFF
                count--
            }
            if (entering < inner) {
                val pixel = source[indexOf(o, entering, width, horizontal)]
                sumR += pixel ushr 16 and 0xFF
                sumG += pixel ushr 8 and 0xFF
                sumB += pixel and 0xFF
                count++
            }
        }
    }
}

private fun indexOf(outer: Int, inner: Int, width: Int, horizontal: Boolean): Int =
    if (horizontal) outer * width + inner else inner * width + outer

/** One pixel each side: the radius that reads as "sharper" rather than as "crunchy". */
private const val SHARPEN_RADIUS = 1

/** Wide enough that clarity picks out subjects rather than edges. */
private const val CLARITY_RADIUS = 12

/** Full-slider unsharp amounts, tuned so the end of the slider is strong but not artefacted. */
private const val SHARPEN_STRENGTH = 1.5f
private const val CLARITY_STRENGTH = 0.8f
