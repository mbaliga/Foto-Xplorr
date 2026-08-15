package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.ColorMatrixColorFilter
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
     * The order is fixed and it matters: rotate and flip first, because the crop is expressed in
     * the rotated image's coordinates; then crop; then colour. Colour last means the matrix runs
     * over the smallest number of pixels.
     */
    fun render(source: Bitmap, recipe: EditRecipe): Bitmap {
        val oriented = orient(source, recipe)
        val cropped = crop(oriented, recipe.crop)
        if (oriented !== source && oriented !== cropped) oriented.recycle()
        return colour(cropped, recipe)
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
        val hasColourEdit = recipe.brightness != 0f || recipe.contrast != 0f ||
            recipe.saturation != 0f || recipe.warmth != 0f
        // copy() rather than returning `source`: the caller owns the result and may recycle it,
        // and handing back the input would make that recycle the ORIGINAL out from under whoever
        // else is holding it.
        if (!hasColourEdit) return source.copy(Bitmap.Config.ARGB_8888, false)

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(recipe.toColorMatrix())
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        return output
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
