package com.fotoxplorr.app.hyle

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape

/**
 * The Hyle "notch" shapes: black surfaces that meet a screen edge with a concave *cove* — an
 * inverted-radius flare where the side of the surface curves outward to blend into the edge,
 * rather than meeting it at a hard corner. This cove is the signature of the language; a plain
 * rounded rectangle reads as a floating card, and the whole point of these is that they read as
 * *cut into the edge*.
 *
 * The three shapes are ported verbatim from the owner's mockup export (the 440 x 956 SVG), so the
 * curves are the designer's own, not an approximation fitted here. Each is expressed in that
 * mockup's own dp coordinates, translated so the shape's bounding box starts at (0, 0), and then
 * scaled to whatever size the composable is drawn at. Because each surface is laid out at its
 * native mockup size (not stretched to fill), that scale is uniform and the cove keeps its
 * designed radius.
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
    moveTo(209.076f * sx, 0f)
    lineTo(0f, 0f)
    lineTo(7.6056f * sx, 0f)
    cubicTo(14.1794f * sx, 0f, 20.0842f * sx, 4.02094f * sy, 22.4929f * sx, 10.1376f * sy)
    lineTo(34.5088f * sx, 40.6515f * sy)
    cubicTo(36.9175f * sx, 46.7682f * sy, 42.8223f * sx, 50.7891f * sy, 49.3961f * sx, 50.7891f * sy)
    lineTo(161.308f * sx, 50.7891f * sy)
    cubicTo(167.854f * sx, 50.7891f * sy, 173.739f * sx, 46.8025f * sy, 176.167f * sx, 40.7241f * sy)
    lineTo(188.413f * sx, 10.065f * sy)
    cubicTo(190.841f * sx, 3.98662f * sy, 196.726f * sx, 0f, 203.272f * sx, 0f)
    lineTo(209.076f * sx, 0f)
    close()
}

/** Design-space width x height of the bottom-left count pill (including its right cove cap). */
const val PILL_DESIGN_W = 316f
const val PILL_DESIGN_H = 44f

/**
 * Bottom-left count pill: a 16dp-radius rounded bar whose right end is not a symmetric round but a
 * cove cap — it bulges out above centre and slants back in toward the bottom, so it reads as the
 * mate of the trash notch beside it. Built as the union of the mockup's rounded rect (x 15..312)
 * and its `Group 1000003488` extension (origin-shifted by (-15, -909)); the two sub-paths overlap
 * and fill as one.
 */
val SelectionPillShape: Shape = notchShape(PILL_DESIGN_W, PILL_DESIGN_H) { sx, sy ->
    // Rounded-rect body: local x[0..297], y[0..44], corner radius 16.
    val r = 16f
    moveTo(r * sx, 0f)
    lineTo(297f * sx, 0f)
    // right side of the body is subsumed by the extension below, so round only the left corners
    // and let the extension carry the right cap.
    lineTo(297f * sx, 44f * sy)
    lineTo(r * sx, 44f * sy)
    cubicTo(7.163f * sx, 44f * sy, 0f, 36.837f * sy, 0f, 28f * sy)
    lineTo(0f, 16f * sy)
    cubicTo(0f, 7.163f * sy, 7.163f * sx, 0f, r * sx, 0f)
    close()
    // Extension cap (the cove) — a second sub-path, same fill.
    moveTo(157f * sx, 16f * sy)
    cubicTo(157f * sx, 7.163f * sy, 164.163f * sx, 0f, 173f * sx, 0f)
    lineTo(299.663f * sx, 0f)
    cubicTo(311.168f * sx, 0f, 318.913f * sx, 11.78f * sy, 314.352f * sx, 22.343f * sy)
    lineTo(309.17f * sx, 34.343f * sy)
    cubicTo(306.639f * sx, 40.204f * sy, 300.866f * sx, 44f * sy, 294.481f * sx, 44f * sy)
    lineTo(173f * sx, 44f * sy)
    cubicTo(164.163f * sx, 44f * sy, 157f * sx, 36.837f * sy, 157f * sx, 28f * sy)
    lineTo(157f * sx, 16f * sy)
    close()
}

/** Design-space width x height of the bottom-right trash notch. */
const val TRASH_DESIGN_W = 134f
const val TRASH_DESIGN_H = 47.053f

/**
 * Bottom-right trash notch: rises from the bottom edge, coves into it at both bottom corners, and
 * narrows to a rounded top — the inverse of the toolbar, and shaped unlike the pill on purpose, so
 * the one destructive control never reads as "how many". Path is the mockup's `filter0` shape,
 * origin-shifted by (-303, -909).
 */
val SelectionTrashShape: Shape = notchShape(TRASH_DESIGN_W, TRASH_DESIGN_H) { sx, sy ->
    moveTo(35.86f * sx, 9.741f * sy)
    cubicTo(38.37f * sx, 3.835f * sy, 44.167f * sx, 0f, 50.585f * sx, 0f)
    lineTo(83.42f * sx, 0f)
    cubicTo(89.836f * sx, 0f, 95.631f * sx, 3.832f * sy, 98.143f * sx, 9.735f * sy)
    lineTo(109.869f * sx, 37.293f * sy)
    cubicTo(112.375f * sx, 43.182f * sy, 118.15f * sx, 47.011f * sy, 124.55f * sx, 47.028f * sy)
    lineTo(134f * sx, 47.053f * sy)
    lineTo(0f, 47f * sy)
    lineTo(9.385f * sx, 47.025f * sy)
    cubicTo(15.818f * sx, 47.042f * sy, 21.636f * sx, 43.204f * sy, 24.152f * sx, 37.284f * sy)
    lineTo(35.86f * sx, 9.741f * sy)
    close()
}
