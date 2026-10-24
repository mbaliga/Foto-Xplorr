package com.fotoxplorr.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.toSize
import com.fotoxplorr.app.recognition.TextBlock
import com.fotoxplorr.app.textselect.FrameMap
import com.fotoxplorr.app.textselect.Pt
import com.fotoxplorr.app.textselect.Quad
import com.fotoxplorr.app.textselect.TextLayout
import com.fotoxplorr.app.textselect.TextSelection
import kotlin.math.roundToInt

/**
 * Selecting text off a photograph, the screen half: what the Select text mode draws over the photo
 * and how a finger on it becomes a selection.
 *
 * The model underneath is [com.fotoxplorr.app.textselect] (layout, hit-testing, strokes, lassos,
 * text assembly), all of it pure; the layout comes from the stored whole-block boxes at once and
 * from a word-level read of the viewed photo a moment later (see [TextSelectState]). Nothing here
 * runs OCR.
 */

/**
 * Where a normalised OCR box lands on screen, given a container and the image's own aspect.
 *
 * Pure, and separated from the composable on purpose: this is the part that goes subtly wrong
 * (letterboxing, portrait vs landscape, an image wider than its container) and the part worth
 * pinning in a JVM test rather than discovering on a device.
 *
 * Assumes the image is drawn with `ContentScale.Fit` — scaled to fit whole, centred, letterboxed
 * on whichever axis has slack. That is what the viewer does.
 */
internal fun fittedImageRect(container: Size, imageWidth: Int, imageHeight: Int): Rect {
    if (imageWidth <= 0 || imageHeight <= 0 || container.width <= 0f || container.height <= 0f) {
        return Rect(Offset.Zero, container)
    }
    val scale = minOf(container.width / imageWidth, container.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/** Map one normalised text block onto the drawn image. */
internal fun TextBlock.toScreenRect(imageRect: Rect): Rect = Rect(
    left = imageRect.left + left * imageRect.width,
    top = imageRect.top + top * imageRect.height,
    right = imageRect.left + right * imageRect.width,
    bottom = imageRect.top + bottom * imageRect.height,
)

/** Id of the active overlay, for tests: present exactly while the mode is on. */
internal const val TEXT_SELECT_OVERLAY_TAG = "textSelectOverlay"

/**
 * The text layer over a photo, drawn and listened to only while Select text is [active].
 *
 * Renders NOTHING AT ALL when it is not active: no `Box`, no `pointerInput`, no semantics. That is
 * the same rule [com.fotoxplorr.app.lift.LiftOverlay] follows and it is what removes the dead zone
 * the old always-on layer made (TRAPS 50): a photo that is merely being looked at carries no
 * invisible tap targets, so the viewer's tap-to-hide-chrome, double-tap zoom and long-press Lift all
 * work on a photo with text. Before the mode there is also no faint box on any word; the outlines
 * appear on entry.
 *
 * Placed INSIDE the viewer's zoom/pan/rotate `graphicsLayer`, so the outlines track the image under
 * pinch and rotation without this knowing the transform. The cost of that arrangement is that
 * everything measured in pixels grows with the zoom, so strokes, hit slop and the finger radius are
 * all divided by [scale] (see [TextSelectTouch]).
 *
 * Gestures: a tap selects the block under it (tap it again, or bare photo, to let go); a one-finger
 * stroke selects the words it touched; a stroke that closes into a loop is a lasso. A second finger
 * hands the touch to the viewer's pinch. See [detectTextSelectGestures] for what is and is not
 * consumed.
 *
 * @param scale the viewer's current zoom, read live (each draw and each gesture), never captured.
 */
@Composable
internal fun LiveTextOverlay(
    state: TextSelectState,
    active: Boolean,
    scale: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (!active) return

    val currentScale by rememberUpdatedState(scale)
    // The accent role follows the chosen accent; drawBehind is not composable, hence resolved here.
    val accent = MaterialTheme.colorScheme.primary
    val selectionFill = accent.copy(alpha = SELECTION_ALPHA)

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(TEXT_SELECT_OVERLAY_TAG)
            // Drawing cannot be done with a screen reader; the toolbar's Select all and Copy are
            // the way, and this says so instead of leaving a silent photo.
            .semantics { contentDescription = TextSelectCopy.OVERLAY_DESCRIPTION }
            .pointerInput(state) {
                var mark: TextSelectState.Held? = null
                fun touch() = TextSelectTouch(state.layout, size.toSize(), density, currentScale())
                detectTextSelectGestures(
                    scale = { currentScale() },
                    onTap = { point ->
                        val layout = state.layout
                        val touch = touch()
                        state.select(state.selection.tapBlock(layout, touch.frame(point), touch.tapSlop))
                    },
                    onStart = { mark = state.mark() },
                    onMove = { path ->
                        state.trail = path
                        state.select(touch().drawn(state.layout, path))
                    },
                    onEnd = { path ->
                        val drawn = touch().drawn(state.layout, path)
                        // A stroke that touched no word leaves what was selected as it was.
                        if (drawn.isEmpty) mark?.let(state::restore) else state.select(drawn)
                        state.trail = emptyList()
                        mark = null
                    },
                    onCancel = {
                        mark?.let(state::restore)
                        state.trail = emptyList()
                        mark = null
                    },
                )
            }
            .drawBehind {
                val layout = state.layout
                val map = frameMapFor(layout, size)
                val unit = density / currentScale().coerceAtLeast(TextSelectTouch.MIN_ZOOM)
                // Selected words first, so the outlines stay legible over them.
                for (quad in state.selection.highlightQuads(layout)) {
                    drawPath(quadPath(quad, map), selectionFill)
                }
                for (block in layout.blocks) {
                    val path = quadPath(block.quad, map)
                    drawPath(path, OUTLINE_SHADOW, style = Stroke(width = OUTLINE_SHADOW_DP * unit, join = StrokeJoin.Round))
                    drawPath(path, OUTLINE, style = Stroke(width = OUTLINE_DP * unit, join = StrokeJoin.Round))
                }
                drawTrail(state.trail, accent, unit)
            },
    )
}

/** The Fit rectangle of [layout]'s frame inside [container], as the map between frame and layer pixels. */
internal fun frameMapFor(layout: TextLayout, container: Size): FrameMap {
    val fit = fittedImageRect(container, layout.frameWidth.roundToInt(), layout.frameHeight.roundToInt())
    return FrameMap(layout.frameWidth, layout.frameHeight, fit.left, fit.top, fit.width, fit.height)
}

private fun quadPath(quad: Quad, map: FrameMap): Path {
    val tl = map.toScreen(quad.tl)
    val tr = map.toScreen(quad.tr)
    val br = map.toScreen(quad.br)
    val bl = map.toScreen(quad.bl)
    return Path().apply {
        moveTo(tl.x, tl.y)
        lineTo(tr.x, tr.y)
        lineTo(br.x, br.y)
        lineTo(bl.x, bl.y)
        close()
    }
}

private fun DrawScope.drawTrail(trail: List<Offset>, color: Color, unit: Float) {
    if (trail.size < 2) return
    val path = Path().apply {
        moveTo(trail[0].x, trail[0].y)
        for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y)
    }
    drawPath(
        path,
        color.copy(alpha = TRAIL_ALPHA),
        style = Stroke(width = TRAIL_DP * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Screen distances for one gesture, turned into frame distances.
 *
 * Built per event from what is true at that moment (the layout in force, the overlay's size, the
 * current zoom) rather than held, so a word-level layout arriving mid-stroke or a pinch changing
 * the zoom cannot leave a gesture measuring in a stale frame. Everything is in dp first and
 * divided by [zoom] because the overlay sits inside the zoom layer: 12 dp of finger is 12 / zoom
 * layer pixels. The conversion to the frame goes through [FrameMap.frameDistance], which is one
 * factor for both axes, so a finger is round on a non-square photo (the bug in normalised units).
 */
internal class TextSelectTouch(layout: TextLayout, container: Size, density: Float, zoom: Float) {
    private val map = frameMapFor(layout, container)
    // Layer pixels in one dp.
    private val unit = density / zoom.coerceAtLeast(MIN_ZOOM)

    /** A layer-pixel position as a frame point. */
    fun frame(point: Offset): Pt = map.toFrame(point.x, point.y)

    fun frame(path: List<Offset>): List<Pt> = path.map(::frame)

    /** How far from a block a tap still counts as on it. */
    val tapSlop: Float get() = map.frameDistance(TAP_SLOP_DP * unit)

    /** Half the width of the finger drawing a stroke. */
    val fingerRadius: Float get() = map.frameDistance(FINGER_RADIUS_DP * unit)

    /** How close the end of a stroke must come to its start for the stroke to be a loop. */
    val closeDistance: Float get() = map.frameDistance(LASSO_CLOSE_DP * unit)

    /** What drawing [path] selects: a lasso if it closed on itself, otherwise the words it touched. */
    fun drawn(layout: TextLayout, path: List<Offset>): TextSelection =
        TextSelection.NONE.drawSelect(layout, frame(path), fingerRadius, closeDistance)

    companion object {
        /** Zoom is never read below this, so a zero can not blow a radius up. */
        const val MIN_ZOOM = 0.1f
        const val TAP_SLOP_DP = 8f
        const val FINGER_RADIUS_DP = 12f
        const val LASSO_CLOSE_DP = 48f
    }
}

private const val OUTLINE_DP = 1.5f
private const val OUTLINE_SHADOW_DP = 3f
private const val TRAIL_DP = 6f
private const val SELECTION_ALPHA = 0.40f
private const val TRAIL_ALPHA = 0.55f

/** White over a dark edge, so an outline reads on a white sign and on a dark one. */
private val OUTLINE = Color(0xCCFFFFFF)
private val OUTLINE_SHADOW = Color(0x66000000)
