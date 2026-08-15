package com.fotoxplorr.app.editor

import android.graphics.ColorMatrix

/**
 * What has been done to a photo, as data rather than as pixels.
 *
 * The editor is **non-destructive by construction**: nothing here holds a bitmap, and no operation
 * mutates one. A recipe is a small value describing the edit; pixels are produced from it only
 * when something needs to be drawn or saved, and the original file is never written to. That is
 * not a nicety — it is the difference between an editor and an irreversible transformation of the
 * user's only copy of a photograph.
 *
 * Modelled on AOSP Gallery2's FilterShow representation stack (Apache-2.0), which is the design
 * this borrows; no code was copied, and nothing in the app's dependency set changed to get here.
 *
 * See `docs/adr/ADR-007-photo-editing.md` for why this is written rather than pulled in.
 */
data class EditRecipe(
    /** Quarter turns clockwise, 0..3. Quarter turns only: free rotation is a crop problem. */
    val quarterTurns: Int = 0,
    /** Mirror horizontally, applied after rotation. */
    val flipHorizontal: Boolean = false,
    /** -1f..1f, 0f is untouched. */
    val brightness: Float = 0f,
    /** -1f..1f, 0f is untouched. */
    val contrast: Float = 0f,
    /** -1f..1f, 0f is untouched; -1f is fully greyscale. */
    val saturation: Float = 0f,
    /** -1f..1f, 0f is untouched. Warms towards amber, cools towards blue. */
    val warmth: Float = 0f,
    /** The crop, in normalised 0..1 coordinates of the *rotated* image. */
    val crop: CropRect = CropRect.FULL,
) {
    /** True when applying this recipe would change nothing, so saving can be refused. */
    val isIdentity: Boolean
        get() = quarterTurns % 4 == 0 &&
            !flipHorizontal &&
            brightness == 0f &&
            contrast == 0f &&
            saturation == 0f &&
            warmth == 0f &&
            crop.isFull

    fun rotatedClockwise(): EditRecipe = copy(quarterTurns = (quarterTurns + 1) % 4)

    /**
     * The colour adjustments as a single [ColorMatrix].
     *
     * One matrix rather than four chained filters: composing them here means the whole colour
     * pipeline is a single multiply per pixel at draw time, and — more importantly — that the
     * order of operations is fixed and stated rather than depending on the order a caller happens
     * to apply things in.
     *
     * Order is brightness, then contrast, then saturation, then warmth. Contrast pivots around
     * mid-grey rather than black, which is what stops "more contrast" from also meaning "darker".
     */
    fun toColorMatrix(): ColorMatrix {
        val result = ColorMatrix()

        if (brightness != 0f) {
            // A translation, not a scale: scaling would crush highlights to white long before the
            // slider reached its end, and leave true black stubbornly black.
            val shift = brightness * BRIGHTNESS_RANGE
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, shift,
                        0f, 1f, 0f, 0f, shift,
                        0f, 0f, 1f, 0f, shift,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }

        if (contrast != 0f) {
            val scale = 1f + contrast
            // Pivot about mid-grey: without this term the image darkens as contrast rises.
            val translate = MID_GREY * (1f - scale)
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, translate,
                        0f, scale, 0f, 0f, translate,
                        0f, 0f, scale, 0f, translate,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }

        if (saturation != 0f) {
            result.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })
        }

        if (warmth != 0f) {
            // Push red and pull blue (or the reverse), leaving green alone: green carries most of
            // perceived luminance, so touching it would change exposure while claiming to change
            // only colour temperature.
            val shift = warmth * WARMTH_RANGE
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, shift,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, -shift,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        return result
    }

    companion object {
        /** How far a full brightness slider shifts each channel, in 0..255 units. */
        const val BRIGHTNESS_RANGE = 96f

        /** How far a full warmth slider shifts red against blue, in 0..255 units. */
        const val WARMTH_RANGE = 40f

        const val MID_GREY = 127.5f
    }
}

/**
 * A crop, in normalised coordinates of the rotated image, so it survives a change of resolution.
 *
 * Normalised rather than pixels deliberately: the editor previews at a downscaled size and exports
 * at full resolution, and a crop expressed in preview pixels would silently mean a different
 * region of the exported file.
 */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) {
            "crop must be normalised to 0..1, was ($left, $top, $right, $bottom)"
        }
        require(right > left && bottom > top) {
            "crop must have positive area, was ($left, $top, $right, $bottom)"
        }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left == 0f && top == 0f && right == 1f && bottom == 1f

    /**
     * The largest crop with the given aspect ratio that fits inside this one, centred on it.
     *
     * Used by the aspect presets. Shrinking to fit rather than expanding to the full image keeps
     * a preset from silently undoing a crop the user already made.
     *
     * @param aspect width / height, in the coordinates of the image this crop belongs to.
     * @param imageAspect the aspect of the underlying image, needed because this rect is in
     *   normalised space where a square is not square.
     */
    fun fitTo(aspect: Float, imageAspect: Float): CropRect {
        // Convert the wanted aspect into normalised space, where x and y have different scales.
        val wanted = aspect / imageAspect
        val current = width / height
        val (w, h) = if (current > wanted) {
            (height * wanted) to height
        } else {
            width to (width / wanted)
        }
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return CropRect(
            left = (cx - w / 2f).coerceIn(0f, 1f),
            top = (cy - h / 2f).coerceIn(0f, 1f),
            right = (cx + w / 2f).coerceIn(0f, 1f),
            bottom = (cy + h / 2f).coerceIn(0f, 1f),
        )
    }

    companion object {
        val FULL = CropRect()
    }
}

/** The aspect presets offered by the crop tool. */
enum class AspectPreset(val label: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    THREE_TWO("3:2", 3f / 2f),
    SIXTEEN_NINE("16:9", 16f / 9f),
}
