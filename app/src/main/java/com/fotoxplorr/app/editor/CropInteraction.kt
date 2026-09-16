package com.fotoxplorr.app.editor

/**
 * The eight drag handles an interactive crop box offers, plus dragging the box's own interior to
 * move it without resizing. Named by the edge(s) each one owns, which is exactly the set
 * [CropRect.dragged] uses to decide what moves.
 */
enum class CropHandle {
    TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, MOVE
}

/**
 * The result of dragging [handle] by ([dxNorm], [dyNorm]) — normalised 0..1 deltas in the SAME
 * space [CropRect] itself is expressed in, so the caller only has to divide a raw pixel drag by
 * the drawn image's own width/height once, not re-derive this maths per handle.
 *
 * @param lockedAspect the crop's wanted aspect ratio, ALREADY converted into this rect's own
 *   normalised space (see [CropRect.fitTo]'s identical parameter for why a raw width/height ratio
 *   is not the right number here — a square image aspect is not visually square in normalised
 *   space unless the underlying image itself is square). Null means free-form: each handle only
 *   ever touches the edge(s) it owns.
 *
 * [MOVE] translates the whole box, clamped so it cannot leave the image — aspect is irrelevant
 * to a move, since the box's own shape does not change.
 *
 * A CORNER handle ([TOP_LEFT] etc.) drags both its edges directly; with an aspect locked, the
 * larger of the two proportional changes wins and the other edge is derived from it, anchored on
 * the OPPOSITE corner, which is what keeps that corner exactly under the finger that grabbed it.
 *
 * An EDGE handle ([TOP]/[BOTTOM]/[LEFT]/[RIGHT]) only ever has one degree of freedom the user
 * actually dragged; with an aspect locked, the perpendicular dimension is derived from it and
 * kept centred on the box's own current midpoint, since an edge drag has no natural corner to
 * anchor the other axis on the way a corner drag does.
 */
fun CropRect.dragged(handle: CropHandle, dxNorm: Float, dyNorm: Float, lockedAspect: Float? = null): CropRect {
    if (handle == CropHandle.MOVE) {
        val w = width
        val h = height
        val newLeft = (left + dxNorm).coerceIn(0f, 1f - w)
        val newTop = (top + dyNorm).coerceIn(0f, 1f - h)
        return CropRect(newLeft, newTop, newLeft + w, newTop + h)
    }

    val drivesLeft = handle in LEFT_HANDLES
    val drivesRight = handle in RIGHT_HANDLES
    val drivesTop = handle in TOP_HANDLES
    val drivesBottom = handle in BOTTOM_HANDLES

    var newLeft = if (drivesLeft) (left + dxNorm).coerceIn(0f, right - MIN_CROP_SIZE) else left
    var newRight = if (drivesRight) (right + dxNorm).coerceIn(left + MIN_CROP_SIZE, 1f) else right
    var newTop = if (drivesTop) (top + dyNorm).coerceIn(0f, bottom - MIN_CROP_SIZE) else top
    var newBottom = if (drivesBottom) (bottom + dyNorm).coerceIn(top + MIN_CROP_SIZE, 1f) else bottom

    if (lockedAspect != null && lockedAspect > 0f) {
        if (handle in CORNER_HANDLES) {
            val anchorX = if (drivesLeft) newRight else newLeft
            val anchorY = if (drivesTop) newBottom else newTop
            val freeW = (newRight - newLeft).coerceAtLeast(MIN_CROP_SIZE)
            val freeH = (newBottom - newTop).coerceAtLeast(MIN_CROP_SIZE)
            // Whichever dimension changed more, proportionally, is treated as the one the user
            // actually meant to drive; the other is derived from it via the locked aspect. Both
            // are then clamped against the image bounds from the SAME anchor corner, and the
            // driven dimension is re-derived from whichever clamp actually bit, so a boundary
            // clamp can never leave the box off-aspect.
            var w: Float
            var h: Float
            if (kotlin.math.abs(freeW - width) / width.coerceAtLeast(1e-4f) >=
                kotlin.math.abs(freeH - height) / height.coerceAtLeast(1e-4f)
            ) {
                w = freeW; h = freeW / lockedAspect
            } else {
                h = freeH; w = freeH * lockedAspect
            }
            val maxW = if (drivesLeft) anchorX else 1f - anchorX
            val maxH = if (drivesTop) anchorY else 1f - anchorY
            if (w > maxW) { w = maxW; h = w / lockedAspect }
            if (h > maxH) { h = maxH; w = h * lockedAspect }
            w = w.coerceAtLeast(MIN_CROP_SIZE)
            h = h.coerceAtLeast(MIN_CROP_SIZE)
            if (drivesLeft) newLeft = anchorX - w else newRight = anchorX + w
            if (drivesTop) newTop = anchorY - h else newBottom = anchorY + h
        } else {
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            if (drivesTop || drivesBottom) {
                val h = (newBottom - newTop).coerceAtLeast(MIN_CROP_SIZE)
                val w = (h * lockedAspect).coerceIn(MIN_CROP_SIZE, 1f)
                newLeft = (centerX - w / 2f).coerceAtLeast(0f)
                newRight = (newLeft + w).coerceAtMost(1f)
                newLeft = newRight - w
            } else {
                val w = (newRight - newLeft).coerceAtLeast(MIN_CROP_SIZE)
                val h = (w / lockedAspect).coerceIn(MIN_CROP_SIZE, 1f)
                newTop = (centerY - h / 2f).coerceAtLeast(0f)
                newBottom = (newTop + h).coerceAtMost(1f)
                newTop = newBottom - h
            }
        }
    }

    return CropRect(
        left = newLeft.coerceIn(0f, 1f),
        top = newTop.coerceIn(0f, 1f),
        right = newRight.coerceIn(0f, 1f),
        bottom = newBottom.coerceIn(0f, 1f),
    )
}

/** The smallest crop box a handle drag can shrink to, in normalised units — small enough to feel
 *  unconstrained, large enough that the box never inverts or vanishes under a fast drag. */
private const val MIN_CROP_SIZE = 0.05f

private val LEFT_HANDLES = setOf(CropHandle.TOP_LEFT, CropHandle.LEFT, CropHandle.BOTTOM_LEFT)
private val RIGHT_HANDLES = setOf(CropHandle.TOP_RIGHT, CropHandle.RIGHT, CropHandle.BOTTOM_RIGHT)
private val TOP_HANDLES = setOf(CropHandle.TOP_LEFT, CropHandle.TOP, CropHandle.TOP_RIGHT)
private val BOTTOM_HANDLES = setOf(CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM, CropHandle.BOTTOM_RIGHT)
private val CORNER_HANDLES = setOf(CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT)

/**
 * Converts an aspect ratio expressed in real width/height units (a preset like 4:3, or an
 * image's own shape) into [CropRect]'s normalised space, where a value of 1 is NOT visually
 * square unless [imageAspect] itself is 1 — the identical conversion [CropRect.fitTo] already
 * performs, pulled out so the crop-drag handles and the aspect PRESETS agree about what "locked
 * to 4:3" means on the same photograph.
 */
fun normalizedAspect(wantedAspect: Float, imageAspect: Float): Float = wantedAspect / imageAspect
