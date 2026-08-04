package com.fotoxplorr.app.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The floating control from the mockups that replaces the old four-tab bottom
 * `NavigationBar`: a dark rounded pill holding a search button on the left, a horizontal
 * position scrubber with a dot handle across the middle, and a grid-density toggle on the
 * right.
 *
 * The scrubber is a *position* control -- dragging it jumps the grid to that point in the
 * collection, which is what makes a 12,000-item single grid navigable without date headers.
 * [scrollFraction] drives the handle from the grid, and [onScrub] drives the grid from the
 * handle, so the two stay in sync in both directions.
 */
@Composable
fun FloatingPillControl(
    scrollFraction: Float,
    onScrub: (Float) -> Unit,
    onSearch: () -> Unit,
    onToggleDensity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableStateOf(0f) }
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val shownFraction = if (dragFraction >= 0f) dragFraction else scrollFraction.coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(PILL_HEIGHT.dp / 2))
            .background(PILL_BACKGROUND)
            .padding(horizontal = 6.dp)
            .height(PILL_HEIGHT.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIconButton(
            onClick = onSearch,
            description = "Search",
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(PILL_HEIGHT.dp)
                .padding(horizontal = 8.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .semantics { contentDescription = "Scroll position" }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (trackWidthPx > 0f) onScrub((offset.x / trackWidthPx).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset: Offset ->
                            if (trackWidthPx > 0f) {
                                dragFraction = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                                onScrub(dragFraction)
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            if (trackWidthPx > 0f) {
                                dragFraction = (dragFraction + amount / trackWidthPx).coerceIn(0f, 1f)
                                onScrub(dragFraction)
                            }
                        },
                        onDragEnd = { dragFraction = -1f },
                        onDragCancel = { dragFraction = -1f },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.85f)),
            )
            val handleOffsetPx = with(density) {
                handleOffsetPx(shownFraction, trackWidthPx, HANDLE_SIZE.dp.toPx())
            }
            Box(
                Modifier
                    .offset { IntOffset(handleOffsetPx.roundToInt(), 0) }
                    .size(HANDLE_SIZE.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }

        PillIconButton(
            onClick = onToggleDensity,
            description = "Change grid density",
        ) {
            Icon(Icons.Outlined.GridView, contentDescription = null, tint = Color.White)
        }
    }
}

/**
 * Left edge of the scrubber handle, in pixels, for a given [fraction] of a [trackWidthPx]
 * track. Kept pure and internal so the clamping (the handle must stay fully inside the
 * track at both ends) is unit-testable.
 */
internal fun handleOffsetPx(fraction: Float, trackWidthPx: Float, handleSizePx: Float): Float {
    if (trackWidthPx <= 0f) return 0f
    val travel = (trackWidthPx - handleSizePx).coerceAtLeast(0f)
    return travel * fraction.coerceIn(0f, 1f)
}

@Composable
private fun PillIconButton(
    onClick: () -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private val PILL_BACKGROUND = Color(0xFF141414)
private const val PILL_HEIGHT = 52
private const val BUTTON_SIZE = 44
private const val TRACK_HEIGHT = 3
private const val HANDLE_SIZE = 12
