package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Which screen edge a [SlideInPanel] is anchored to. */
enum class PanelSide { LEFT, RIGHT }

/**
 * A panel that slides in over the content beneath it, leaving a strip of that content
 * visible at the opposite edge -- the way the mockups draw both the destination rail (panel
 * from the left, grid showing at the right edge) and the settings panel (panel from the
 * right, grid showing at the left edge).
 *
 * It is a genuine gesture surface, not a route: [content] stays composed and visible behind
 * the panel throughout, an edge swipe opens it, a swipe back or a tap on the exposed strip
 * closes it, and a partial drag tracks the finger and settles to whichever end is nearer.
 * That is the behaviour the mockups imply and the reason this replaces the previous
 * full-screen "Destinations" route buried in an overflow menu.
 */
@Composable
fun SlideInPanel(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    side: PanelSide,
    panelWidth: Dp,
    modifier: Modifier = Modifier,
    /** Width of the screen edge that starts an opening swipe while the panel is closed. */
    edgeGripWidth: Dp = 28.dp,
    panel: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val panelWidthPx = with(density) { panelWidth.toPx() }
    var dragProgress by remember { mutableFloatStateOf(-1f) }
    val settled by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(PANEL_DURATION_MS, easing = FastOutSlowInEasing),
        label = "hyle-panel-progress",
    )
    // A live drag overrides the animated value so the panel tracks the finger 1:1.
    val progress = if (dragProgress >= 0f) dragProgress else settled
    val directionSign = if (side == PanelSide.LEFT) -1f else 1f

    fun applyDrag(deltaPx: Float) {
        val base = if (dragProgress >= 0f) dragProgress else settled
        // Dragging away from the anchored edge opens; back towards it closes.
        val delta = (-directionSign * deltaPx) / panelWidthPx
        dragProgress = (base + delta).coerceIn(0f, 1f)
    }

    fun settleDrag() {
        val current = dragProgress
        dragProgress = -1f
        if (current >= 0f) onOpenChange(current >= SETTLE_THRESHOLD)
    }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) { content() }

        // Scrim over the exposed content strip: dims it and makes a tap there close the panel.
        if (progress > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_MAX_ALPHA * progress))
                    .pointerInput(progress) {
                        detectTapGestures { onOpenChange(false) }
                    }
                    .pointerInput(side, panelWidthPx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                applyDrag(amount)
                            },
                            onDragEnd = { settleDrag() },
                            onDragCancel = { settleDrag() },
                        )
                    },
            )
        }

        // Edge grip: only present while closed, so it never competes with the grid's own
        // horizontal gestures once the panel is showing.
        if (progress <= 0.01f) {
            Box(
                Modifier
                    .align(if (side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(edgeGripWidth)
                    .pointerInput(side, panelWidthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragProgress = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                applyDrag(amount)
                            },
                            onDragEnd = { settleDrag() },
                            onDragCancel = { settleDrag() },
                        )
                    },
            )
        }

        if (progress > 0.001f) {
            var measuredWidthPx by remember { mutableFloatStateOf(panelWidthPx) }
            Box(
                modifier = Modifier
                    .align(if (side == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(panelWidth)
                    .onSizeChanged { measuredWidthPx = it.width.toFloat() }
                    // Fully off-screen at progress 0, flush against its edge at progress 1.
                    .offset {
                        IntOffset(panelOffsetPx(progress, measuredWidthPx, side).roundToInt(), 0)
                    }
                    .shadow(
                        elevation = PANEL_ELEVATION.dp,
                        shape = panelShape(side),
                        clip = false,
                    )
                    .clip(panelShape(side))
                    .background(Color.Black)
                    .pointerInput(side, panelWidthPx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                applyDrag(amount)
                            },
                            onDragEnd = { settleDrag() },
                            onDragCancel = { settleDrag() },
                        )
                    },
            ) {
                Box(Modifier.fillMaxSize()) { panel() }
            }
        }
    }
}

/**
 * Horizontal offset of the panel for a given open [progress]. Pure and internal so the
 * geometry is unit-testable: at progress 0 the panel sits exactly one panel-width outside
 * its anchored edge, at progress 1 it is flush with it, and it moves linearly between.
 */
internal fun panelOffsetPx(progress: Float, panelWidthPx: Float, side: PanelSide): Float {
    val hidden = panelWidthPx * (1f - progress.coerceIn(0f, 1f))
    return if (side == PanelSide.LEFT) -hidden else hidden
}

/** Rounded on the inner edge only, flush against the screen on the anchored edge. */
private fun panelShape(side: PanelSide) = if (side == PanelSide.LEFT) {
    RoundedCornerShape(topEnd = PANEL_RADIUS.dp, bottomEnd = PANEL_RADIUS.dp)
} else {
    RoundedCornerShape(topStart = PANEL_RADIUS.dp, bottomStart = PANEL_RADIUS.dp)
}

private const val PANEL_DURATION_MS = 260
private const val PANEL_RADIUS = 22
private const val PANEL_ELEVATION = 18
private const val SCRIM_MAX_ALPHA = 0.55f
private const val SETTLE_THRESHOLD = 0.4f
