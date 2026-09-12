package com.fotoxplorr.app.curate

/**
 * A variance-of-Laplacian sharpness score, for [ArchiveAdvisor]'s "very blurry" signal.
 *
 * Pure Kotlin over a pixel array -- no Bitmap, no Android -- the same shape as
 * [com.fotoxplorr.app.editor.AutoFix.analyse] and for the same reason: the arithmetic is
 * unit-tested against synthetic pixel data instead of eyeballed against a handful of photos on a
 * device. The caller downsamples the bitmap before calling, exactly as
 * [com.fotoxplorr.app.editor.AutoFix]'s own KDoc puts it -- analysis at full photo resolution
 * would cost real time for a signal this function only needs a few thousand pixels to compute.
 *
 * ## The method, and what "simple" means here
 *
 * The Laplacian of an image is large wherever brightness changes sharply in every direction at
 * once -- an in-focus edge, a crisp texture -- and near zero wherever it does not. A photo that
 * is genuinely in focus is FULL of such places; a blurred one has had exactly that
 * high-frequency content smoothed away by whatever produced the blur, whether that was a shaky
 * hand or an out-of-focus lens. The VARIANCE of the Laplacian response across the whole frame is
 * therefore a single number that is large for a sharp photo and small for a blurred one, without
 * needing to find or track any specific edge the way [com.fotoxplorr.app.editor.AutoFix]'s own
 * horizon detector does.
 *
 * This is deliberately the textbook version and nothing more: a fixed 3x3 kernel, one pass,
 * mean and variance accumulated together. What it explicitly does NOT do is normalise for the
 * photo's own contrast -- a genuinely low-contrast but perfectly sharp photo (fog, an overcast
 * sky) scores lower than a high-contrast sharp one, for the same reason a flat, empty frame does:
 * there is simply less brightness range for an edge to change across. Accepted, because
 * [ArchiveAdvisor] only ever uses this score to suggest a REVIEW, never to act on its own, and a
 * hazy landscape landing in a "possibly blurry" queue for a human to glance at and dismiss costs
 * a second of someone's attention -- a fully contrast-normalised metric would be meaningfully
 * more code for a task that already has a human in the loop by design.
 */
object BlurDetector {

    /**
     * @param pixels Packed ARGB, as `Bitmap.getPixels` returns -- the same convention
     *   [com.fotoxplorr.app.editor.AutoFix.analyse] uses, so a caller already holding a decoded,
     *   downsampled frame for one purpose can hand the same array to both without re-decoding.
     * @return Larger is sharper, smaller is blurrier; the scale is only meaningful relative to
     *   another score from THIS function, never as an absolute unit -- see [ArchiveAdvisor] for
     *   the threshold this is compared against, chosen by testing synthetic sharp and blurred
     *   patterns rather than derived from any universal constant. `null` when [pixels] is too
     *   small or malformed to say anything -- a 1-pixel-wide sliver has no interior pixels for a
     *   3x3 kernel to sit on, the same guard [com.fotoxplorr.app.editor.AutoFix.detectHorizon]
     *   applies for the identical reason.
     */
    fun sharpness(pixels: IntArray, width: Int, height: Int): Float? {
        if (width < 3 || height < 3 || pixels.size != width * height) return null

        // Luma once, up front, at the same Rec.709 weights every other luma computation in this
        // app uses (see AutoFix.analyse and AutoFix.detectHorizon) -- a second definition of
        // "brightness" living only here would mean this score and, say, AutoFix's exposure
        // reading occasionally disagreeing about the same pixel for no reason a reader could see.
        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        // Single pass: accumulate the sum and the sum of squares of the Laplacian response
        // together, so the variance falls out of `E[x^2] - E[x]^2` without ever materialising a
        // second full-frame array. Safe from the usual cancellation risk that formula carries
        // for large values, because a Laplacian response is a difference of nearby luma samples
        // by construction -- it is already centred near zero, not a raw pixel value.
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            val rowAbove = row - width
            val rowBelow = row + width
            for (x in 1 until width - 1) {
                val center = luma[row + x]
                val laplacian = 4f * center -
                    luma[row + x - 1] - luma[row + x + 1] -
                    luma[rowAbove + x] - luma[rowBelow + x]
                sum += laplacian
                sumSquares += laplacian.toDouble() * laplacian.toDouble()
                count++
            }
        }
        if (count == 0) return null

        val mean = sum / count
        val variance = (sumSquares / count) - (mean * mean)
        // Floating-point noise can push a truly flat frame's variance a hair below zero; a
        // negative sharpness score would read as nonsense to any caller doing `< threshold`.
        return variance.coerceAtLeast(0.0).toFloat()
    }
}
