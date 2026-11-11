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
            // HSL last within this block: it targets the colour the LUTs and saturation/vibrance
            // just produced, the same "grade first, then fine-tune specific hues" order a real
            // HSL panel implies by living in its own tab, separate from the basic colour sliders.
            if (!adjustments.hsl.isIdentity) applyHsl(pixels, adjustments.hsl)
        }
        if (adjustments.vignette != 0f) {
            applyVignette(pixels, width, height, adjustments.vignette)
        }
        // Denoise BEFORE sharpen/clarity: an unsharp mask amplifies whatever high-frequency
        // detail is in the buffer when it runs, noise included, so smoothing first is what
        // keeps a strong Sharpen from re-amplifying the grain Denoise just calmed down.
        if (adjustments.denoise != 0f) {
            applyDenoise(pixels, width, height, adjustments.denoise)
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
    if (sharpen == 0f && clarity == 0f) return
    // ONE pair of full-image scratch buffers, shared by both passes rather than each of
    // `unsharp`'s two possible calls allocating its own -- sharpen and clarity together used to
    // hold four extra full-resolution IntArrays (a `blurred` copy and boxBlur's own internal
    // scratch, twice over) on top of the five-odd copies already alive in EditorScreen's export
    // path at MAX_EXPORT_EDGE; at 8192px that pair alone is ~500 MB. Reused sequentially here
    // means at most two.
    val blurred = IntArray(pixels.size)
    val scratch = IntArray(pixels.size)
    if (sharpen != 0f) unsharp(pixels, blurred, scratch, width, height, SHARPEN_RADIUS, sharpen * SHARPEN_STRENGTH)
    if (clarity != 0f) unsharp(pixels, blurred, scratch, width, height, CLARITY_RADIUS, clarity * CLARITY_STRENGTH)
}

private fun unsharp(
    pixels: IntArray,
    blurred: IntArray,
    scratch: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    amount: Float,
) {
    if (amount == 0f || radius < 1) return
    System.arraycopy(pixels, 0, blurred, 0, pixels.size)
    boxBlur(blurred, scratch, width, height, radius)

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

/** A separable box blur, in place. Horizontal pass then vertical, into a caller-owned [scratch]
 *  buffer rather than allocating its own -- see [applyUnsharpMask]'s own doc for why that matters
 *  at export resolution. */
private fun boxBlur(pixels: IntArray, scratch: IntArray, width: Int, height: Int, radius: Int) {
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

/**
 * Simple noise reduction: blend the original with a small-radius box blur of itself, in place.
 *
 * A plain box blur, not a true bilateral or median filter — a deliberate, stated simplification
 * (see [Adjustments.denoise]'s own doc) rather than a dead field left unimplemented. A real
 * edge-preserving filter needs a per-pixel neighbourhood comparison that costs meaningfully more
 * at export resolution; a camera sensor's own noise is high-frequency enough that even a small,
 * edge-blind blur calms it visibly at the slider's normal range, and [amount] is a BLEND fraction
 * rather than the blur's own radius — at `amount = 1` this is a full box blur, but the slider's
 * useful range sits well below that, trading a little edge softness for a lot less speckle.
 */
internal fun applyDenoise(pixels: IntArray, width: Int, height: Int, amount: Float) {
    if (width <= 0 || height <= 0 || amount <= 0f) return
    val blurred = pixels.copyOf()
    val scratch = IntArray(pixels.size)
    boxBlur(blurred, scratch, width, height, DENOISE_RADIUS)

    val blend = amount.coerceIn(0f, 1f)
    for (i in pixels.indices) {
        val original = pixels[i]
        val blur = blurred[i]
        val alpha = original ushr 24 and 0xFF
        val r = denoiseChannel(original ushr 16 and 0xFF, blur ushr 16 and 0xFF, blend)
        val g = denoiseChannel(original ushr 8 and 0xFF, blur ushr 8 and 0xFF, blend)
        val b = denoiseChannel(original and 0xFF, blur and 0xFF, blend)
        pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }
}

private fun denoiseChannel(original: Int, blurred: Int, blend: Float): Int =
    (original * (1f - blend) + blurred * blend).toInt().coerceIn(0, 255)

/** Small enough to calm sensor-grain noise without visibly softening real detail even at the top
 *  of the slider's blend range. */
private const val DENOISE_RADIUS = 2
