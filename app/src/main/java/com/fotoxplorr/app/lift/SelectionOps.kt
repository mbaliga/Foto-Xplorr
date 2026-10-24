package com.fotoxplorr.app.lift

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The editable history of a lift selection, as plain data.
 *
 * The editor never stores "the current mask" as the thing that is edited. It stores the list of
 * things the person DID -- scribbled here, painted there -- and derives the mask by replaying them
 * ([SelectionOps.replay]). That is what makes undo and redo exact for free: popping an operation
 * and replaying gives precisely the mask that existed before it, with no bitmap snapshots to hold
 * in memory and no way for the history and the picture to disagree. The cost is one solve per
 * undo, which at the ~512px working size is a fraction of a second.
 *
 * Coordinates are NORMALISED to the image (0..1 on both axes) and brush sizes are a fraction of
 * its long edge, so an operation means the same thing at the 512px working size, the 1024px
 * preview and the 2048px export, and survives the image being decoded at a different size.
 */
data class NormPoint(val x: Float, val y: Float)

/** A brush path: a round-capped polyline of [points] with a brush [radius] (fraction of the long edge). */
data class BrushStroke(val points: List<NormPoint>, val radius: Float)

sealed interface SelectionOp {
    /**
     * A mark for the SOLVER: "this is the subject" ([foreground]) or "this is not". A tap is a
     * one-point stroke. Hints steer the geodesic solve; they do not paint pixels directly.
     */
    data class Hint(val foreground: Boolean, val stroke: BrushStroke) : SelectionOp

    /** A direct correction: pixels under the brush are forced into ([add]) or out of the selection, after the solve. */
    data class Paint(val add: Boolean, val stroke: BrushStroke) : SelectionOp
}

/**
 * Undo/redo over [SelectionOp]s. Immutable: every operation returns the next session, so it sits in
 * a Compose `mutableStateOf` and "did it change" is a reference comparison.
 *
 * A new operation clears the redo stack, as in every editor a person has ever used: once you act
 * on top of an undone state, the abandoned future no longer applies to the present.
 */
class SelectionSession private constructor(
    val ops: List<SelectionOp>,
    private val redoStack: List<SelectionOp>,
) {
    constructor() : this(emptyList(), emptyList())

    val canUndo: Boolean get() = ops.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isEmpty: Boolean get() = ops.isEmpty()

    /** True once at least one foreground hint exists -- before that there is nothing to solve. */
    val hasForeground: Boolean get() = ops.any { it is SelectionOp.Hint && it.foreground }

    fun apply(op: SelectionOp): SelectionSession = SelectionSession(ops + op, emptyList())

    fun undo(): SelectionSession =
        if (ops.isEmpty()) this else SelectionSession(ops.dropLast(1), redoStack + ops.last())

    fun redo(): SelectionSession =
        if (redoStack.isEmpty()) this else SelectionSession(ops + redoStack.last(), redoStack.dropLast(1))

    /** Back to nothing selected. Not itself undoable: it is the "start over" button, and says so. */
    fun reset(): SelectionSession = SelectionSession()
}

object SelectionOps {

    /**
     * The mask a [ops] history produces over [pixels]: every [SelectionOp.Hint] goes to the
     * solver together (later hints win a pixel two of them cover), THEN every [SelectionOp.Paint]
     * is applied in order on top, THEN the edge is feathered. Corrections therefore stay put when
     * a later hint re-solves the surroundings, which is what a person who has just carefully
     * painted a strap back in expects.
     *
     * Empty (all zero alpha) while there is no foreground hint.
     */
    fun replay(
        pixels: IntArray,
        width: Int,
        height: Int,
        ops: List<SelectionOp>,
        params: GeodesicSegmenter.Params = GeodesicSegmenter.Params(),
        shouldStop: () -> Boolean = { false },
    ): SubjectSegmentation.Mask {
        val n = width * height
        val foreground = BooleanArray(n)
        val background = BooleanArray(n)
        for (op in ops) {
            if (op !is SelectionOp.Hint) continue
            val target = if (op.foreground) foreground else background
            val other = if (op.foreground) background else foreground
            stamp(op.stroke, width, height) { i ->
                target[i] = true
                other[i] = false
            }
        }

        val region = GeodesicSegmenter.solveRegion(pixels, width, height, foreground, background, params, shouldStop)
        for (op in ops) {
            if (op !is SelectionOp.Paint) continue
            stamp(op.stroke, width, height) { i -> region[i] = op.add }
        }
        return SubjectSegmentation.feather(region, width, height, params.featherRadius)
    }

    /** The brush radius of [stroke] in pixels of a [width]x[height] image: a fraction of the LONG edge, never below half a pixel. */
    fun radiusPx(stroke: BrushStroke, width: Int, height: Int): Float =
        max(0.5f, stroke.radius * max(width, height))

    /**
     * Call [visit] with the index of every pixel the round-capped [stroke] covers. Discs are
     * stamped along each segment at no more than a third of a radius apart, which leaves no
     * visible scallops; a one-point stroke (a tap) is a single disc. Points outside the image are
     * clamped onto it, and indices may repeat -- callers set flags, so that is harmless.
     */
    fun stamp(stroke: BrushStroke, width: Int, height: Int, visit: (Int) -> Unit) {
        if (width <= 0 || height <= 0 || stroke.points.isEmpty()) return
        val radius = radiusPx(stroke, width, height)
        val spacing = max(0.5f, radius / 3f)
        fun disc(cx: Float, cy: Float) {
            val x0 = max(0, (cx - radius).toInt())
            val x1 = min(width - 1, (cx + radius).toInt() + 1)
            val y0 = max(0, (cy - radius).toInt())
            val y1 = min(height - 1, (cy + radius).toInt() + 1)
            val r2 = radius * radius
            for (y in y0..y1) {
                val dy = y + 0.5f - cy
                for (x in x0..x1) {
                    val dx = x + 0.5f - cx
                    if (dx * dx + dy * dy <= r2) visit(y * width + x)
                }
            }
        }
        // Pixel CENTRES live at +0.5, so a tap at normalised (0.5, 0.5) of a 10px image lands at
        // pixel-space 5.0, the seam between pixels 4 and 5, not inside pixel 5.
        var prevX = stroke.points[0].x.coerceIn(0f, 1f) * width
        var prevY = stroke.points[0].y.coerceIn(0f, 1f) * height
        disc(prevX, prevY)
        for (k in 1 until stroke.points.size) {
            val x = stroke.points[k].x.coerceIn(0f, 1f) * width
            val y = stroke.points[k].y.coerceIn(0f, 1f) * height
            val dx = x - prevX
            val dy = y - prevY
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            val steps = max(1, (length / spacing).toInt())
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                disc(prevX + dx * t, prevY + dy * t)
            }
            prevX = x
            prevY = y
        }
    }

    /**
     * Resample an alpha plane to a new size with bilinear interpolation, for taking the 512px
     * working mask to the 2048px export. Sample positions are pixel CENTRES, so the result is
     * aligned with the image rather than shifted by half a source pixel, and stays inside 0..1
     * (bilinear weights are convex).
     */
    fun resampleAlpha(mask: SubjectSegmentation.Mask, newWidth: Int, newHeight: Int): SubjectSegmentation.Mask {
        if (newWidth == mask.width && newHeight == mask.height) return mask
        val out = FloatArray(max(0, newWidth * newHeight))
        if (mask.width <= 0 || mask.height <= 0 || newWidth <= 0 || newHeight <= 0) {
            return SubjectSegmentation.Mask(max(0, newWidth), max(0, newHeight), out)
        }
        val sx = mask.width.toFloat() / newWidth
        val sy = mask.height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (mask.height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(mask.height - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until newWidth) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (mask.width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(mask.width - 1, x0 + 1)
                val wx = fx - x0
                val top = mask.alpha[y0 * mask.width + x0] * (1 - wx) + mask.alpha[y0 * mask.width + x1] * wx
                val bottom = mask.alpha[y1 * mask.width + x0] * (1 - wx) + mask.alpha[y1 * mask.width + x1] * wx
                out[y * newWidth + x] = top * (1 - wy) + bottom * wy
            }
        }
        return SubjectSegmentation.Mask(newWidth, newHeight, out)
    }

    /** The working size for an image: long edge [longEdge] (never upscaled), aspect kept, each side at least 1. */
    fun workingSize(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val long = max(width, height)
        if (long <= longEdge) return width to height
        val scale = longEdge.toFloat() / long
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** A tightly-cropped run of ARGB pixels, see [cutPixels]. */
    class CutPixels(val width: Int, val height: Int, val pixels: IntArray)

    /**
     * Punch [mask] out of [pixels] and crop to its bounds plus [padding]: transparent where the
     * mask is, the photo's own colour where it is not, alpha = the photo's alpha x the mask's. The
     * mask is resampled first when it is not the photo's size (a 512px working mask applied to the
     * 2048px export), so the mask never has to be decoded at the size it is applied at.
     *
     * Null when the mask selects nothing at all.
     */
    fun cutPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: SubjectSegmentation.Mask,
        padding: Int,
    ): CutPixels? {
        require(pixels.size == width * height) { "pixels size ${pixels.size} does not match ${width}x$height" }
        val fitted = resampleAlpha(mask, width, height)
        val box = SubjectSegmentation.boundingBox(fitted) ?: return null
        val left = (box.left - padding).coerceIn(0, width - 1)
        val top = (box.top - padding).coerceIn(0, height - 1)
        val right = (box.right + padding).coerceIn(left + 1, width)
        val bottom = (box.bottom + padding).coerceIn(top + 1, height)
        val cutW = right - left
        val cutH = bottom - top
        val out = IntArray(cutW * cutH)
        for (y in 0 until cutH) {
            val srcRow = (y + top) * width
            val dstRow = y * cutW
            for (x in 0 until cutW) {
                val src = srcRow + x + left
                val alpha = fitted.alpha[src]
                if (alpha <= 0f) continue
                val pixel = pixels[src]
                val outAlpha = (((pixel ushr 24) and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
                out[dstRow + x] = (outAlpha shl 24) or (pixel and 0x00FFFFFF)
            }
        }
        return CutPixels(cutW, cutH, out)
    }

    /**
     * The selection as a translucent colour wash: [rgb] at up to [maxAlpha] (0..255) wherever the
     * mask is set, transparent elsewhere. This is what the editor draws over the photo.
     */
    fun overlayPixels(mask: SubjectSegmentation.Mask, rgb: Int, maxAlpha: Int): IntArray {
        val colour = rgb and 0x00FFFFFF
        return IntArray(mask.alpha.size) { i ->
            val a = (mask.alpha[i] * maxAlpha).roundToInt().coerceIn(0, 255)
            if (a == 0) 0 else (a shl 24) or colour
        }
    }

    /** The photo with [mask] applied as alpha but NOT cropped -- the live "preview cut-out" frame. */
    fun maskedPixels(pixels: IntArray, width: Int, height: Int, mask: SubjectSegmentation.Mask): IntArray {
        val fitted = resampleAlpha(mask, width, height)
        return IntArray(pixels.size) { i ->
            val alpha = fitted.alpha[i]
            if (alpha <= 0f) 0 else {
                val pixel = pixels[i]
                ((((pixel ushr 24) and 0xFF) * alpha).roundToInt().coerceIn(0, 255) shl 24) or (pixel and 0x00FFFFFF)
            }
        }
    }
}
