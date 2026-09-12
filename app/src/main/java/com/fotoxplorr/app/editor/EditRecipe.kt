package com.fotoxplorr.app.editor

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
    /**
     * Quarter turns clockwise, 0..3.
     *
     * For anything finer, see [straightenDegrees] rather than reaching for an arbitrary angle
     * here: this field stays multiples of 90 on purpose, because a quarter turn never exposes a
     * corner and therefore never needs a crop decision, and mixing the two concerns into one
     * float would mean every consumer of "how much is this rotated" has to re-derive whether an
     * auto-crop applies.
     */
    val quarterTurns: Int = 0,
    /** Mirror horizontally, applied after rotation. */
    val flipHorizontal: Boolean = false,
    /**
     * Small-angle rotation for levelling a horizon or a leaning wall, in degrees, roughly -15..15.
     *
     * 0f is the neutral value, like every other field in this codebase's persisted edit stacks --
     * see [Adjustments]'s KDoc for why that is not a style preference but a promise: a recipe
     * saved before this field existed decodes with `straightenDegrees = 0f` and must keep
     * rendering exactly as it did before, which only works if 0 truly does nothing.
     *
     * Unlike [quarterTurns], any non-zero value here exposes triangular gaps at the four corners
     * of the rotated image where there is no source pixel any more. [EditRenderer] does not show
     * those gaps: it auto-crops inward to the largest same-aspect rectangle that avoids all four,
     * using [StraightenGeometry.inscribedRect]. That crop is *derived* from this angle, not stored
     * separately, so there is no way for the two to disagree.
     */
    val straightenDegrees: Float = 0f,
    /**
     * Everything done to colour, as a non-destructive stack.
     *
     * This replaced four fields (brightness, contrast, saturation, warmth) and a `ColorMatrix`.
     * That model was a strictly worse duplicate of what [Adjustments] does: its brightness was an
     * offset applied to the gamma-encoded value rather than exposure in linear light, and its
     * contrast was a linear scale that clipped both ends of the histogram at the end of the
     * slider. Keeping both would have meant two colour pipelines that disagreed about the same
     * photograph.
     */
    val adjustments: Adjustments = Adjustments.NONE,
    /** The crop, in normalised 0..1 coordinates of the *rotated* image. */
    val crop: CropRect = CropRect.FULL,
) {
    /** True when applying this recipe would change nothing, so saving can be refused. */
    val isIdentity: Boolean
        get() = quarterTurns % 4 == 0 &&
            !flipHorizontal &&
            straightenDegrees == 0f &&
            adjustments.isIdentity &&
            crop.isFull

    fun rotatedClockwise(): EditRecipe = copy(quarterTurns = (quarterTurns + 1) % 4)

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
