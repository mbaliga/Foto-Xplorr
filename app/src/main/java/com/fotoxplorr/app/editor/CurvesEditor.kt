package com.fotoxplorr.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

/** The colour a channel is drawn in -- UI only, so it lives here rather than on the pure enum. */
val CurveChannel.color: Color
    get() = when (this) {
        CurveChannel.RGB -> Color.White
        CurveChannel.LUMA -> Color(0xFFFFB74D)
        CurveChannel.RED -> Color(0xFFEF5350)
        CurveChannel.GREEN -> Color(0xFF66BB6A)
        CurveChannel.BLUE -> Color(0xFF42A5F5)
    }

/** The histogram that sits behind [channel]'s graph: its own for R/G/B, brightness for the rest. */
fun ToneHistogram.binsFor(channel: CurveChannel): IntArray = when (channel) {
    CurveChannel.RED -> red
    CurveChannel.GREEN -> green
    CurveChannel.BLUE -> blue
    CurveChannel.RGB, CurveChannel.LUMA -> luma
}

/**
 * A Snapseed-style tone-curve editor: channel tabs, shortcuts that load control points, and a
 * graph over the photo's own histogram.
 *
 *  - touch a point to grab it; touch the line (or anywhere else) to add one and drag it;
 *  - drag to move, by the finger's delta, so the point does not jump under the fingertip;
 *  - remove a point by long-pressing it, double-tapping it, or dragging it off the graph and
 *    letting go there (it turns red while armed, and dragging back in disarms it);
 *  - the two end points move up and down only.
 *
 * All the curve arithmetic is [ToneCurve.moveTo] / [ToneCurve.insertAt] / [ToneCurve.removeAt] and
 * all the touch arithmetic is [CurveHit], both JVM-tested; this file is the gesture loop and the
 * drawing. One `awaitEachGesture` handler owns every gesture on the graph (the earlier tap and
 * drag handlers were separate `pointerInput` blocks, so a tap on an existing point also added a
 * second one beside it).
 *
 * The graph must NOT be placed inside a `verticalScroll`: a vertical drag on a point would be
 * contested by the scroll container, and the panel would move instead of the point.
 *
 * Gesture feel (slop, hit radius, long-press timing) is verified on a device only.
 *
 * @param curveFor reads whichever curve [channel] refers to.
 * @param onCurveChange called with the channel that changed and its new curve -- shaped this way,
 *   rather than one lambda per curve, so this signature does not grow with [Adjustments].
 * @param channel the channel being edited. Hoisted by the caller so it survives leaving the tab.
 * @param histogram of the photo being edited, or null while it is still being computed.
 * @param onDragActive true while a finger is actually moving a point, so the caller can render a
 *   cheaper preview for the duration.
 */
@Composable
fun CurvesEditor(
    curveFor: (CurveChannel) -> ToneCurve,
    onCurveChange: (CurveChannel, ToneCurve) -> Unit,
    channel: CurveChannel,
    onChannelChange: (CurveChannel) -> Unit,
    histogram: ToneHistogram?,
    modifier: Modifier = Modifier,
    onDragActive: (Boolean) -> Unit = {},
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var armedForRemoval by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val curve = curveFor(channel)
    val touchedChannels = CurveChannel.entries.filter { !curveFor(it).isIdentity }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CurveChannel.entries.forEach { entry ->
                val selected = entry == channel
                Box(
                    Modifier
                        .weight(1f)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(50),
                        )
                        .clickable { onChannelChange(entry) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        entry.label,
                        color = if (selected) Color.Black else entry.color,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    // A dot on any tab whose curve has been touched, so "where did my red go" is
                    // answerable without visiting every tab -- same idea as HslPanel's.
                    if (entry in touchedChannels) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 8.dp)
                                .size(6.dp)
                                .background(if (selected) Color.Black else entry.color, CircleShape),
                        )
                    }
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "reset") {
                ShortcutChip(
                    label = "Reset",
                    selected = false,
                    enabled = !curve.isIdentity,
                    onClick = { onCurveChange(channel, ToneCurve.IDENTITY) },
                )
            }
            if (touchedChannels.any { it != channel }) {
                item(key = "reset-all") {
                    ShortcutChip(
                        label = "Reset all",
                        selected = false,
                        enabled = true,
                        onClick = { touchedChannels.forEach { onCurveChange(it, ToneCurve.IDENTITY) } },
                    )
                }
            }
            // LINEAR is Reset under another name, so it is not offered twice.
            items(CurvePreset.entries.filter { it != CurvePreset.LINEAR }, key = { it.name }) { preset ->
                ShortcutChip(
                    label = preset.label,
                    selected = curve == preset.curve,
                    enabled = true,
                    onClick = { onCurveChange(channel, preset.curve) },
                )
            }
        }

        // Capped in both directions: never wider than the panel, never taller than a fifth of
        // the way up a phone's screen's worth of dp -- the photo above it is the point of the
        // editor, and a graph that eats it makes the live preview useless.
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side: Dp = minOf(maxWidth, MAX_GRAPH_SIDE, (screenHeightDp * GRAPH_SCREEN_FRACTION).dp)
                .coerceAtLeast(MIN_GRAPH_SIDE)
            val description = "Tone curve, ${channel.label} channel, ${curve.points.size} points" +
                if (curve.isIdentity) ", unchanged" else ""

            Box(
                Modifier
                    .size(side)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .semantics { contentDescription = description },
            ) {
                val normalised = remember(histogram, channel) {
                    histogram?.let { ToneHistogram.normalised(it.binsFor(channel)) }
                }
                val currentCurveFor by rememberUpdatedState(curveFor)
                val currentOnChange by rememberUpdatedState(onCurveChange)
                val currentOnDragActive by rememberUpdatedState(onDragActive)
                val currentChannel by rememberUpdatedState(channel)

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(HANDLE_PADDING)
                        .pointerInput(Unit) {
                            // Double-tap memory lives outside the per-gesture block. The point is
                            // remembered by x, not index: an index goes stale the moment anything
                            // inserts or removes a point.
                            var lastTapX = -1f
                            var lastTapDownMillis = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val width = size.width.toFloat()
                                val height = size.height.toFloat()
                                val which = currentChannel
                                val grab = CurveHit.planGrab(
                                    currentCurveFor(which), down.position.x, down.position.y,
                                    width, height, CurveHit.TOUCH_RADIUS_DP.dp.toPx(),
                                ) ?: return@awaitEachGesture
                                down.consume()

                                val previousTapX = lastTapX
                                val previousTapAt = lastTapDownMillis
                                lastTapX = -1f

                                var working = grab.curve
                                val index = grab.index
                                val interior = index > 0 && index < working.points.lastIndex
                                val slop = viewConfiguration.touchSlop
                                var dragging = !grab.existed
                                var signalled = false
                                var armed = false
                                selectedIndex = index
                                if (!grab.existed) currentOnChange(which, working)

                                fun finish() {
                                    selectedIndex = null
                                    armedForRemoval = false
                                    if (signalled) currentOnDragActive(false)
                                }

                                try {
                                    // One place that turns a finger position into an edit, used both by
                                    // the loop below and by the event that ended the long-press wait.
                                    fun follow(position: Offset) {
                                        if (!signalled) {
                                            signalled = true
                                            currentOnDragActive(true)
                                        }
                                        // Armed, not removed: the point stays where it was last
                                        // valid, turns red, and goes only if the finger lifts out
                                        // here. Dragging back in disarms it.
                                        val outside = interior && CurveHit.isDraggedOut(
                                            position.x, position.y, width, height,
                                            CurveHit.DRAG_OUT_MARGIN_DP.dp.toPx(),
                                        )
                                        if (outside != armed) {
                                            armed = outside
                                            armedForRemoval = outside
                                            if (outside) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        if (!outside) {
                                            val next = working.moveTo(
                                                index,
                                                CurveHit.toNormX(position.x - grab.offsetXPx, width),
                                                CurveHit.toNormY(position.y - grab.offsetYPx, height),
                                            )
                                            if (next != working) {
                                                working = next
                                                currentOnChange(which, working)
                                            }
                                        }
                                    }

                                    if (grab.existed && interior) {
                                        // null = still down after the long-press timeout; true = moved
                                        // past slop (a drag); false = lifted (a tap).
                                        var moved: Boolean? = null
                                        // Where the finger was when it crossed the slop: that same event
                                        // is the first move of the drag and must not be thrown away.
                                        var crossedAt = down.position
                                        val verdict = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            while (moved == null) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                moved = when {
                                                    change == null || !change.pressed -> false
                                                    (change.position - down.position).getDistance() > slop -> {
                                                        crossedAt = change.position
                                                        change.consume()
                                                        true
                                                    }
                                                    else -> null
                                                }
                                            }
                                            moved
                                        }
                                        when (verdict) {
                                            null -> {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                currentOnChange(which, working.removeAt(index))
                                                // Swallow the rest of the press so lifting does nothing.
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == down.id }
                                                    if (change == null || !change.pressed) break
                                                    change.consume()
                                                }
                                                return@awaitEachGesture
                                            }
                                            false -> {
                                                val pointX = working.points[index].x
                                                if (pointX == previousTapX &&
                                                    down.uptimeMillis - previousTapAt <= DOUBLE_TAP_WINDOW_MS
                                                ) {
                                                    currentOnChange(which, working.removeAt(index))
                                                } else {
                                                    lastTapX = pointX
                                                    lastTapDownMillis = down.uptimeMillis
                                                }
                                                return@awaitEachGesture
                                            }
                                            true -> {
                                                dragging = true
                                                follow(crossedAt)
                                            }
                                        }
                                    }

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        if (!dragging && (change.position - down.position).getDistance() > slop) {
                                            dragging = true
                                        }
                                        if (!dragging || !change.positionChanged()) continue
                                        change.consume()
                                        follow(change.position)
                                    }
                                    if (armed && interior) currentOnChange(which, working.removeAt(index))
                                } finally {
                                    finish()
                                }
                            }
                        },
                ) {
                    drawCurveGraph(
                        active = channel,
                        activeCurve = curve,
                        others = CurveChannel.entries.filter { it != channel && it in touchedChannels }
                            .map { it to curveFor(it) },
                        histogram = normalised,
                        selectedIndex = selectedIndex,
                        armedForRemoval = armedForRemoval,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            enabled -> Color.White
            else -> Color.White.copy(alpha = 0.35f)
        },
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun DrawScope.drawCurveGraph(
    active: CurveChannel,
    activeCurve: ToneCurve,
    others: List<Pair<CurveChannel, ToneCurve>>,
    histogram: FloatArray?,
    selectedIndex: Int?,
    armedForRemoval: Boolean,
) {
    val w = size.width
    val h = size.height
    val hairline = 1.dp.toPx()

    // Histogram first, so everything else reads over it.
    if (histogram != null && histogram.size > 1) {
        val path = Path().apply {
            moveTo(0f, h)
            for (i in histogram.indices) lineTo(i / (histogram.size - 1f) * w, h - histogram[i] * h * HISTOGRAM_HEIGHT_FRACTION)
            lineTo(w, h)
            close()
        }
        drawPath(path, active.color.copy(alpha = 0.28f))
    }

    // 4x4 grid at quarter steps, and the diagonal that "no change" would be.
    for (i in 1..3) {
        val f = i / 4f
        drawLine(Color.White.copy(alpha = 0.12f), Offset(f * w, 0f), Offset(f * w, h), hairline)
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, f * h), Offset(w, f * h), hairline)
    }
    drawLine(
        Color.White.copy(alpha = 0.22f), Offset(0f, h), Offset(w, 0f), hairline,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
    )

    // Other channels' curves, faint, so a red tweak is visible while editing blue.
    others.forEach { (channel, curve) ->
        drawPath(lutPath(curve.toLut(), w, h), channel.color.copy(alpha = 0.35f), style = Stroke(width = 2.dp.toPx()))
    }
    drawPath(lutPath(activeCurve.toLut(), w, h), active.color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

    activeCurve.points.forEachIndexed { index, point ->
        val center = Offset(CurveHit.toPixelX(point.x, w), CurveHit.toPixelY(point.y, h))
        val selected = index == selectedIndex
        val armed = selected && armedForRemoval
        val fill = if (armed) Color(0xFFE53935) else active.color
        if (selected) {
            drawCircle(fill.copy(alpha = 0.30f), radius = SELECTED_HANDLE_RADIUS_DP.dp.toPx(), center = center)
        }
        drawCircle(fill, radius = HANDLE_RADIUS_DP.dp.toPx(), center = center)
        drawCircle(Color.Black.copy(alpha = 0.85f), radius = (HANDLE_RADIUS_DP - 3f).dp.toPx(), center = center)
        drawCircle(fill, radius = (HANDLE_RADIUS_DP - 4.5f).dp.toPx(), center = center)
    }
}

private fun lutPath(lut: IntArray, w: Float, h: Float): Path {
    val path = Path()
    val step = w / (lut.size - 1)
    lut.forEachIndexed { i, value ->
        val y = h - (value / 255f) * h
        if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
    }
    return path
}

/** Room around the plotted area so a handle on x=0 or x=1 is not clipped and can be grabbed. */
private val HANDLE_PADDING = 16.dp

/** Never wider than this: a graph this size is plenty for a thumb and leaves the photo its room. */
private val MAX_GRAPH_SIDE = 200.dp
private val MIN_GRAPH_SIDE = 120.dp

/** ...and never taller than this fraction of the screen, for landscape and small phones. */
private const val GRAPH_SCREEN_FRACTION = 0.30f

private const val HANDLE_RADIUS_DP = 7f
private const val SELECTED_HANDLE_RADIUS_DP = 14f

/** How much of the graph's height the tallest histogram bin may fill. */
private const val HISTOGRAM_HEIGHT_FRACTION = 0.9f

/** Longest gap between two touch-downs on the same point that still counts as a double-tap. */
private const val DOUBLE_TAP_WINDOW_MS = 400L
