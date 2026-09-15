package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.roundToInt

/**
 * Turns an [EditRecipe] into pixels.
 *
 * Deliberately `android.graphics` only — no OpenGL, no AGSL, no third-party dependency. The app
 * already carries a hand-written GLES pipeline for the spatial scenes, so the capability exists,
 * but crop / rotate / flip and a colour matrix are exactly what the 2D canvas is already
 * hardware-accelerated for. Reaching for GL here would add an EGL context, a surface lifecycle and
 * a class of device-specific driver failure this app has already been bitten by, in exchange for
 * nothing.
 *
 * See `docs/adr/ADR-007-photo-editing.md` for why no library is used: uCrop declares an OkHttp
 * dependency and would hard-fail the offline flavour's classpath gate, and the strongest
 * open-source galleries in this space are GPL-3.0, which this app cannot take without becoming
 * GPL itself.
 */
object EditRenderer {

    /**
     * Apply [recipe] to [source], returning a new bitmap. [source] is never modified or recycled.
     *
     * The order is fixed and it matters: rotate and flip first, then straighten (which needs the
     * quarter-turned image's own width/height for its auto-crop, not the original's -- a
     * portrait photo that was quarter-turned to landscape must have its straighten crop computed
     * against the landscape dimensions or the aspect preservation in [StraightenGeometry] is
     * silently wrong), then the user's own crop, which is expressed in the coordinates of
     * whatever came out of straighten; then colour last, so the matrix runs over the smallest
     * number of pixels.
     */
    fun render(source: Bitmap, recipe: EditRecipe): Bitmap {
        val oriented = orient(source, recipe)
        val straightened = straighten(oriented, recipe.straightenDegrees)
        recycleIntermediate(oriented, source, straightened)
        val cropped = crop(straightened, recipe.crop)
        recycleIntermediate(straightened, source, cropped)
        val result = colour(cropped, recipe)
        // colour() always returns a bitmap distinct from its input -- both of its branches copy
        // (`source.copy(...)` directly, or via AdjustmentRenderer.render's own copy) -- so
        // `cropped` is always superseded here. Folding this into the same helper as the two
        // stages above, rather than a bespoke check only on `oriented` (which is what this
        // function did before straighten existed), is what catches straighten's own intermediate
        // bitmap: with only the old single check, a recipe with straightenDegrees set but no
        // manual crop left the rotated-and-cropped bitmap unrecycled on every single render.
        recycleIntermediate(cropped, source, result)
        return result
    }

    /**
     * Recycle [candidate] unless it IS [source] (never recycled -- the caller's bitmap is not
     * ours to free) or IS [keep] (still needed by the caller of this pipeline stage).
     */
    private fun recycleIntermediate(candidate: Bitmap, source: Bitmap, keep: Bitmap) {
        if (candidate !== source && candidate !== keep) candidate.recycle()
    }

    private fun orient(source: Bitmap, recipe: EditRecipe): Bitmap {
        val turns = ((recipe.quarterTurns % 4) + 4) % 4
        if (turns == 0 && !recipe.flipHorizontal) return source
        val matrix = Matrix().apply {
            if (turns != 0) postRotate(90f * turns)
            // After the rotation, so the mirror is always about the *displayed* vertical axis --
            // flipping first would make the control mean something different at each rotation.
            if (recipe.flipHorizontal) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Rotate by a small free angle and auto-crop inward so no exposed corner reaches the output.
     *
     * `createBitmap(source, ..., matrix, filter=true)` grows the canvas to the rotated bounding
     * box and leaves the four newly-exposed corners at alpha 0 -- see its documented behaviour of
     * tightly bounding the mapped source rect. Those corners are always centred in that grown
     * canvas regardless of what pivot the matrix rotates about, because a rectangle has
     * point-symmetry about its own centre and rotating a point-symmetric shape by any angle keeps
     * that symmetry, so its axis-aligned bounding box stays centred on the same point -- which is
     * exactly why `postRotate(degrees)` with no explicit pivot (rotating about the bitmap's
     * top-left corner, not its centre) still produces a correctly-centred result here: the pivot
     * only translates the rotated shape, and `createBitmap` re-centres the tight bounding box
     * regardless. That is also why the crop below can simply centre itself in the grown bitmap.
     */
    private fun straighten(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f || source.width <= 0 || source.height <= 0) return source

        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        val radians = Math.toRadians(degrees.toDouble()).toFloat()
        val inscribed = StraightenGeometry.inscribedRect(
            source.width.toFloat(),
            source.height.toFloat(),
            radians,
        )
        // Rounded DOWN (via toInt, not roundToInt) rather than to the nearest pixel: rounding up
        // by even half a pixel could put the crop's edge back on top of the exposed corner that
        // StraightenGeometry.inscribedRect computed as the exact boundary -- exactly the crop
        // gone wrong in the direction this whole feature exists to prevent, and a fraction of a
        // pixel is never visible, so there is no quality reason to round the other way.
        val cropW = inscribed.width.toInt().coerceIn(1, rotated.width)
        val cropH = inscribed.height.toInt().coerceIn(1, rotated.height)
        val cropX = ((rotated.width - cropW) / 2).coerceIn(0, rotated.width - cropW)
        val cropY = ((rotated.height - cropH) / 2).coerceIn(0, rotated.height - cropH)

        val cropped = Bitmap.createBitmap(rotated, cropX, cropY, cropW, cropH)
        if (rotated !== cropped) rotated.recycle()
        return cropped
    }

    private fun crop(source: Bitmap, rect: CropRect): Bitmap {
        if (rect.isFull) return source
        // Rounded and then clamped to at least one pixel: a crop that rounds to zero width would
        // throw inside createBitmap, and a slider can absolutely produce one on a small preview.
        val x = (rect.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val y = (rect.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val w = (rect.width * source.width).roundToInt().coerceIn(1, source.width - x)
        val h = (rect.height * source.height).roundToInt().coerceIn(1, source.height - y)
        return Bitmap.createBitmap(source, x, y, w, h)
    }

    private fun colour(source: Bitmap, recipe: EditRecipe): Bitmap {
        // copy() rather than returning `source`: the caller owns the result and may recycle it,
        // and handing back the input would make that recycle the ORIGINAL out from under whoever
        // else is holding it. AdjustmentRenderer.render already copies, so the identity case is
        // the only one that has to do it here.
        if (recipe.adjustments.isIdentity) return source.copy(Bitmap.Config.ARGB_8888, false)
        return AdjustmentRenderer.render(source, recipe.adjustments)
    }

    /**
     * The size to decode a preview at, given the space it will be shown in.
     *
     * The editor previews interactively — every slider drag re-renders — so previewing at full
     * resolution would mean a 48-megapixel colour matrix per frame. Capped well above the screen
     * so the preview still looks sharp, and expressed as a pure function so the budget is visible
     * and testable rather than buried in a decode call.
     */
    fun previewEdge(viewportEdgePx: Int): Int =
        (viewportEdgePx * PREVIEW_OVERSAMPLE).coerceIn(MIN_PREVIEW_EDGE, MAX_PREVIEW_EDGE)

    /** Preview a little above the viewport so a pinch does not immediately reveal soft pixels. */
    private const val PREVIEW_OVERSAMPLE = 2
    private const val MIN_PREVIEW_EDGE = 512
    private const val MAX_PREVIEW_EDGE = 2048
}
