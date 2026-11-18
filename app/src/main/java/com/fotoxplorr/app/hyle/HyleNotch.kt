package com.fotoxplorr.app.hyle

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The Hyle "notch" shapes: black surfaces that meet a screen edge with a concave *cove* — an
 * inverted-radius flare where the side of the surface curves outward to blend into the edge,
 * rather than meeting it at a hard corner. This cove is the signature of the language; a plain
 * rounded rectangle reads as a floating card, and the whole point of these is that they read as
 * *cut into the edge*.
 *
 * Two families live here. [SelectionToolbarShape] (and its tall variant, which the slideshow bar
 * uses) is ported verbatim from the owner's first mockup export (the 440 x 956 SVG), expressed in
 * that mockup's dp and scaled to the box it is drawn in. The multi-select chrome itself
 * ([SelectionTab], [SelectionTrashTab], [SelectionCountBarShape]) is instead fitted to the
 * owner's later reference image (2026-11-18): an [EdgeTab] built from measured flank lines and
 * true circular fillets, which matched that image where the mockup vectors did not.
 *
 * Shared here rather than inlined at the one call site because the toggle and the field controls
 * in this package draw the same cove, and "the cove is drawn one way" is the property that keeps
 * the language coherent as more controls take it up.
 */

/**
 * Build a [Shape] from a path expressed in a design-space box [designW] x [designH] (mockup dp).
 * [block] receives per-axis scale factors that map design dp onto the composable's actual pixels.
 */
private inline fun notchShape(
    designW: Float,
    designH: Float,
    crossinline block: Path.(sx: Float, sy: Float) -> Unit,
): Shape = GenericShape { size: Size, _ ->
    block(size.width / designW, size.height / designH)
}

/** Design-space width x height of the top action bar, from the mockup's own bounding box. */
const val TOOLBAR_DESIGN_W = 209.076f
const val TOOLBAR_DESIGN_H = 50.7891f

/**
 * Top-left action bar: hangs from the top edge, coves into it at both top corners, and narrows to
 * a flat-bottomed trapezoid. Path is the mockup's `filter1` shape, origin-shifted by (-8, 0).
 */
val SelectionToolbarShape: Shape = notchShape(TOOLBAR_DESIGN_W, TOOLBAR_DESIGN_H) { sx, sy ->
    toolbarPath(sx, sy, 0f)
}

/**
 * [SelectionToolbarShape] taller than its design height: the two top coves keep their designed
 * radius and meet the top edge exactly as they do on the original, and every extra dp goes into
 * the straight run of the sides, which only lengthens (a little steeper). For a bar that hangs
 * from a screen edge with something drawn over that edge, such as a status bar, which the bar's
 * glyphs must sit below; at the design height it is the same shape as [SelectionToolbarShape].
 *
 * Drawn into a box [TOOLBAR_DESIGN_W] dp wide, so one dp of box is one dp of design. A box
 * shorter than the design height is clamped to it.
 */
val SelectionToolbarTallShape: Shape = GenericShape { size: Size, _ ->
    val scale = size.width / TOOLBAR_DESIGN_W
    val extra = (size.height / scale - TOOLBAR_DESIGN_H).coerceAtLeast(0f)
    toolbarPath(scale, scale, extra)
}

/**
 * The toolbar's outline in design units scaled by [sx] and [sy]. [extra] design dp are added to
 * the straight run of the sides: everything below the two top coves moves down by it.
 */
private fun Path.toolbarPath(sx: Float, sy: Float, extra: Float) {
    moveTo(209.076f * sx, 0f)
    lineTo(0f, 0f)
    lineTo(7.6056f * sx, 0f)
    cubicTo(14.1794f * sx, 0f, 20.0842f * sx, 4.02094f * sy, 22.4929f * sx, 10.1376f * sy)
    lineTo(34.5088f * sx, (40.6515f + extra) * sy)
    cubicTo(
        36.9175f * sx, (46.7682f + extra) * sy,
        42.8223f * sx, (50.7891f + extra) * sy,
        49.3961f * sx, (50.7891f + extra) * sy,
    )
    lineTo(161.308f * sx, (50.7891f + extra) * sy)
    cubicTo(
        167.854f * sx, (50.7891f + extra) * sy,
        173.739f * sx, (46.8025f + extra) * sy,
        176.167f * sx, (40.7241f + extra) * sy,
    )
    lineTo(188.413f * sx, 10.065f * sy)
    cubicTo(190.841f * sx, 3.98662f * sy, 196.726f * sx, 0f, 203.272f * sx, 0f)
    lineTo(209.076f * sx, 0f)
    close()
}

/**
 * Height of the selection count bar.
 *
 * A bare dimension with no shape beside it any more: the bottom-left pill this once described,
 * with its right-hand cove cap, was retired when the owner's later mockups moved the count to the
 * top right and drew it as a plain rounded bar. The cove is an edge-meeting device, and a bar
 * floating below the top edge has no edge to meet — see the reasoning at the call site in
 * `GalleryScreen.SelectionOverlay`, which records what rendering the mirrored cap actually looked
 * like.
 */
const val PILL_DESIGN_H = 44f


/**
 * A tab cut into a screen edge, in dp: a trapezoid whose straight flanks lean in as it leaves the
 * edge, with a concave fillet where each flank meets the edge and a rounded corner at each end of
 * its flat far side. Coordinates are "hanging" ones: the edge is y = 0 and the far side is
 * y = [height]; a tab that rises from the bottom edge is the same outline flipped (see [flip]).
 *
 * The left flank is x = [leftAt0] + [leftSlope]·y and the right flank x = [rightAt0] −
 * [rightSlope]·y, with x in the coordinates the numbers were measured in (screen dp for the
 * selection chrome). Every fillet and corner is a true circular arc tangent to both lines it
 * joins, so the outline is fixed by these nine numbers and nothing else.
 */
internal class EdgeTab(
    val leftAt0: Float,
    val leftSlope: Float,
    val rightAt0: Float,
    val rightSlope: Float,
    val height: Float,
    val edgeFilletLeft: Float,
    val edgeFilletRight: Float,
    val cornerLeft: Float,
    val cornerRight: Float,
) {
    private val nL = sqrt(1f + leftSlope * leftSlope)
    private val nR = sqrt(1f + rightSlope * rightSlope)

    // Concave fillets: centres outside the tab, a radius below the edge, tangent to the edge at
    // their top point and to the flank at the foot of the perpendicular.
    private val filletLx = leftAt0 + leftSlope * edgeFilletLeft - edgeFilletLeft * nL
    private val filletLy = edgeFilletLeft
    private val filletLtx = filletLx + edgeFilletLeft / nL
    private val filletLty = filletLy - edgeFilletLeft * leftSlope / nL
    private val filletRx = rightAt0 - rightSlope * edgeFilletRight + edgeFilletRight * nR
    private val filletRy = edgeFilletRight
    private val filletRtx = filletRx - edgeFilletRight / nR
    private val filletRty = filletRy - edgeFilletRight * rightSlope / nR

    // Convex corners on the far side: centres inside the tab, a radius above the far side.
    private val cornerLy = height - cornerLeft
    private val cornerLx = leftAt0 + leftSlope * cornerLy + cornerLeft * nL
    private val cornerLtx = cornerLx - cornerLeft / nL
    private val cornerLty = cornerLy + cornerLeft * leftSlope / nL
    private val cornerRy = height - cornerRight
    private val cornerRx = rightAt0 - rightSlope * cornerRy - cornerRight * nR
    private val cornerRtx = cornerRx + cornerRight / nR
    private val cornerRty = cornerRy + cornerRight * rightSlope / nR

    /** Where the outline leaves the edge on the left: the left fillet's tangent point on y = 0. */
    val minX: Float get() = filletLx

    /** Where the outline leaves the edge on the right. */
    val maxX: Float get() = filletRx

    /** Footprint along the edge, fillet to fillet. */
    val width: Float get() = filletRx - filletLx

    /**
     * This tab grown by [by] dp on its flanks and far side (a parallel offset of the outline;
     * concave fillets shrink by the same amount, convex corners grow). Used to cut a constant
     * gap around the tab out of a neighbour.
     */
    fun grown(by: Float): EdgeTab = EdgeTab(
        leftAt0 = leftAt0 - by * nL,
        leftSlope = leftSlope,
        rightAt0 = rightAt0 + by * nR,
        rightSlope = rightSlope,
        height = height + by,
        edgeFilletLeft = (edgeFilletLeft - by).coerceAtLeast(MIN_RADIUS),
        edgeFilletRight = (edgeFilletRight - by).coerceAtLeast(MIN_RADIUS),
        cornerLeft = cornerLeft + by,
        cornerRight = cornerRight + by,
    )

    /**
     * Add the outline to [path], in pixels: x maps to (x + [dx]) · [scale] and y to
     * (y' + [dy]) · [scale], where y' = y for a tab hanging from the top edge, or
     * [height] − y with [flip] for one rising from the bottom edge.
     */
    fun addTo(path: Path, scale: Float, dx: Float = 0f, dy: Float = 0f, flip: Boolean = false) {
        fun px(x: Float) = (x + dx) * scale
        fun py(y: Float) = ((if (flip) height - y else y) + dy) * scale
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
            path.arcTo(
                rect = Rect(center = Offset(px(cx), py(cy)), radius = r * scale),
                startAngleDegrees = if (flip) -start else start,
                sweepAngleDegrees = if (flip) -sweep else sweep,
                forceMoveTo = false,
            )
        }
        val aTr = angle(filletRtx - filletRx, filletRty - filletRy)
        val aBr = angle(cornerRtx - cornerRx, cornerRty - cornerRy)
        val aBl = angle(cornerLtx - cornerLx, cornerLty - cornerLy)
        val aTl = angle(filletLtx - filletLx, filletLty - filletLy)
        path.moveTo(px(filletLx), py(0f))
        path.lineTo(px(filletRx), py(0f))
        arc(filletRx, filletRy, edgeFilletRight, -90f, aTr + 90f)
        path.lineTo(px(cornerRtx), py(cornerRty))
        arc(cornerRx, cornerRy, cornerRight, aBr, 90f - aBr)
        path.lineTo(px(cornerLx), py(height))
        arc(cornerLx, cornerLy, cornerLeft, 90f, aBl - 90f)
        path.lineTo(px(filletLtx), py(filletLty))
        arc(filletLx, filletLy, edgeFilletLeft, aTl, -90f - aTl)
        path.close()
    }

    /** The outline's left edge at depth [y] from the edge (0..[height]), in the tab's own x. */
    fun leftAt(y: Float): Float = when {
        y <= filletLty -> filletLx + sqrt((edgeFilletLeft * edgeFilletLeft - (y - filletLy).let { it * it }).coerceAtLeast(0f))
        y >= cornerLty -> cornerLx - sqrt((cornerLeft * cornerLeft - (y - cornerLy).let { it * it }).coerceAtLeast(0f))
        else -> leftAt0 + leftSlope * y
    }

    /** The outline's right edge at depth [y] from the edge (0..[height]). */
    fun rightAt(y: Float): Float = when {
        y <= filletRty -> filletRx - sqrt((edgeFilletRight * edgeFilletRight - (y - filletRy).let { it * it }).coerceAtLeast(0f))
        y >= cornerRty -> cornerRx + sqrt((cornerRight * cornerRight - (y - cornerRy).let { it * it }).coerceAtLeast(0f))
        else -> rightAt0 - rightSlope * y
    }

    private fun angle(x: Float, y: Float): Float = (atan2(y, x) * 180.0 / PI).toFloat()

    private companion object {
        const val MIN_RADIUS = 0.01f
    }
}

// ---- the multi-select chrome, fitted to the owner's reference (2026-11-18) ----
// The owner's reference image is a 440 x 956 dp phone at 1 px = 1 dp. Every number below was
// measured from it row by row (sub-pixel, from anti-alias coverage) and the curves were then fitted
// by least squares: each outline lands within 1 dp of the reference on every measured row. All
// x values are screen dp on that 440 dp screen; the composables position the boxes so that holds.

/**
 * The black tab hanging from the top edge at the left, holding the zip / move / copy glyphs.
 * Its flanks lean in about 22° from vertical; it is 50.76 dp tall.
 */
internal val SelectionTab = EdgeTab(
    leftAt0 = 30.4f, leftSlope = 0.395f,
    rightAt0 = 204.75f, rightSlope = 0.406f,
    height = 50.76f,
    edgeFilletLeft = 14.5f, edgeFilletRight = 15f,
    cornerLeft = 18.3f, cornerRight = 17.3f,
)

/**
 * The red tab rising from the bottom edge near the right, holding the trash glyph: the same
 * construction as [SelectionTab], flipped, 46.2 dp tall. Here y counts up from the bottom edge.
 */
internal val SelectionTrashTab = EdgeTab(
    leftAt0 = 309.64f, leftSlope = 0.418f,
    rightAt0 = 402.10f, rightSlope = 0.408f,
    height = 46.2f,
    edgeFilletLeft = 12.18f, edgeFilletRight = 9.06f,
    cornerLeft = 19.25f, cornerRight = 17.46f,
)

/** Screen width the chrome was measured on; [SelectionTrashTab]'s x values assume it. */
internal const val SELECTION_DESIGN_SCREEN_W = 440f

/** Gap kept between the tab's right flank and the count bar's left end (perpendicular dp). */
internal const val SELECTION_TAB_GAP = 5.8f

/** Count bar corners: the top-left one is rounder than the other three in the reference. */
internal const val COUNT_BAR_RADIUS = 16f
internal const val COUNT_BAR_TOP_LEFT_RADIUS = 22.5f

/** A shape whose outline is one [EdgeTab], drawn into a box starting at the tab's [EdgeTab.minX]. */
internal class EdgeTabShape(private val tab: EdgeTab, private val flip: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().also { tab.addTo(it, density.density, dx = -tab.minX, flip = flip) })
}

/** The top-left tab, for a box of [SelectionTab.width] x [SelectionTab.height] at x = [SelectionTab.minX]. */
internal val SelectionTabShape: Shape = EdgeTabShape(SelectionTab, flip = false)

/** The trash tab, for a box of [SelectionTrashTab.width] x [SelectionTrashTab.height] on the bottom edge. */
internal val SelectionTrashTabShape: Shape = EdgeTabShape(SelectionTrashTab, flip = true)

/**
 * The count bar: a rounded rectangle with [SelectionTab] grown by [SELECTION_TAB_GAP] cut out of
 * it, so wherever the bar reaches the tab a band of photograph of constant width runs between the
 * two, parallel to the tab's flank. A short count leaves the bar clear of the tab and the cut
 * removes nothing.
 *
 * The bar is laid out from the right, so its left end in screen dp is [parentWidth] − [rightInset]
 * − its own width; [top] is its distance from the top edge. Both are needed to put the cut where
 * the tab really is.
 */
internal class SelectionCountBarShape(
    private val parentWidth: Dp,
    private val rightInset: Dp,
    private val top: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = density.density
        val bar = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, size.width, size.height),
                    topLeft = CornerRadius(COUNT_BAR_TOP_LEFT_RADIUS * s),
                    topRight = CornerRadius(COUNT_BAR_RADIUS * s),
                    bottomRight = CornerRadius(COUNT_BAR_RADIUS * s),
                    bottomLeft = CornerRadius(COUNT_BAR_RADIUS * s),
                ),
            )
        }
        val leftDp = parentWidth.value - rightInset.value - size.width / s
        if (leftDp >= SelectionTab.grown(SELECTION_TAB_GAP).maxX) return Outline.Generic(bar)
        val cut = Path().also {
            SelectionTab.grown(SELECTION_TAB_GAP).addTo(it, s, dx = -leftDp, dy = -top.value)
        }
        return Outline.Generic(Path().apply { op(bar, cut, PathOperation.Difference) })
    }
}
