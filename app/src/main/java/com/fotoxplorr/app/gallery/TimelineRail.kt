package com.fotoxplorr.app.gallery

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.cellshell.ScrubberEdge
import dev.aarso.cellshell.ScrubberStop
import kotlin.math.abs
import kotlinx.coroutines.delay

private const val TRACK_INSET_DP = 7f
private const val TRACK_WIDTH_DP = 1.5f
private const val TICK_LENGTH_DP = 5f
private const val MARKER_LENGTH_DP = 13f
private const val MARKER_WIDTH_DP = 2.5f
private const val TICK_ALPHA = 0.55f
private const val LABEL_INSET_DP = 15f
private const val LABEL_SP = 10f
private const val LABEL_PITCH_V_DP = 14f
private const val LABEL_PITCH_H_DP = 40f
private const val BUBBLE_GAP_DP = 12f
private const val BUBBLE_MIN_DP = 48
private const val BUBBLE_SP = 18f
private const val EDGE_ZONE_DP = 56f
private const val EXCLUSION_SPAN_DP = 200
private const val RISE_MS = 120
private const val FADE_MS = 220

/**
 * The timeline rail (owner, batch 9 #5): the strip that scrubs the whole library, redrawn app-side
 * so it can do what the owner's reference recordings show -- while a finger is on it the labels
 * bend into an arc that bulges away from the finger ([RailBulge]), a round bubble with the current
 * month sits level with the finger on the far side of the arc, and on release the rail eases
 * straight again.
 *
 * It replaces the library's `EdgeTimelineScrubber` in the gallery (the library is read-only from
 * this repo and draws a flat strip). The gesture rules are the library's: the touch must begin in
 * the 44 dp strip and unconsumed, nothing is claimed until the finger has travelled past touch slop
 * along the rail, and a sideways drag is left for the shell (TRAPS #72).
 *
 * Fills its parent: the strip is the touch target on [edge], and the labels and bubble are drawn
 * over the rest of the parent, unclipped and pointer-free, so they can bulge out of the strip.
 *
 * With [TimelineVisibility.WHILE_SCRUBBING] nothing is drawn at rest but the 44 dp touch target is
 * live; the rail appears when a scrub is claimed and fades out [RailBulge.LINGER_MS] after release.
 * TalkBack gets the stepper (previous / next month) as semantic actions and a "Scrub timeline"
 * action that reveals the rail; none of those needs a drag.
 *
 * Nothing here has run on a device: the maths is pinned by [RailBulgeTest], the feel is not.
 */
@Composable
internal fun TimelineRail(
    stops: List<ScrubberStop>,
    itemCount: Int,
    currentIndex: Int,
    onScrubTo: (itemIndex: Int) -> Unit,
    inkColor: Color,
    accentColor: Color,
    bubbleTextColor: Color,
    edge: ScrubberEdge,
    visibility: TimelineVisibility,
    restAlpha: Float,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 0 || stops.isEmpty()) return

    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current
    // Read once: the person's "remove animations" (animator scale 0) makes every motion here instant.
    val reduceMotion = remember(context) {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f
        } catch (e: Exception) {
            false
        }
    }
    val spec = remember(density.density) { RailBulgeSpec.forDensity(density.density) }

    val stopsState = rememberUpdatedState(stops)
    val countState = rememberUpdatedState(itemCount)
    val currentIndexState = rememberUpdatedState(currentIndex)
    val onScrubToState = rememberUpdatedState(onScrubTo)
    val edgeState = rememberUpdatedState(edge)

    var pressed by remember { mutableStateOf(false) }
    // True from the claim until LINGER_MS after release; the scrubbing-only mode draws while it is.
    var lingering by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Bumped by TalkBack's "Scrub timeline": show the rail for a linger without a drag.
    var revealTick by remember { mutableIntStateOf(0) }
    // Read only from layout / layer / draw lambdas, so a finger move never recomposes the rail.
    val touchAlong = remember { mutableFloatStateOf(0f) }
    val touchDepth = remember { mutableFloatStateOf(0f) }
    val scrubIndex = remember { mutableIntStateOf(0) }
    val strength = remember { Animatable(0f) }

    LaunchedEffect(pressed) {
        if (pressed) {
            lingering = true
            if (reduceMotion) strength.snapTo(1f) else strength.animateTo(1f, tween(RISE_MS))
        } else {
            if (reduceMotion) {
                strength.snapTo(0f)
            } else {
                strength.animateTo(0f, tween(RailBulge.SNAP_BACK_MS, easing = Easing { RailBulge.snapBackEase(it) }))
            }
            delay(RailBulge.LINGER_MS)
            lingering = false
        }
    }

    LaunchedEffect(revealTick) {
        if (revealTick == 0) return@LaunchedEffect
        lingering = true
        delay(RailBulge.LINGER_MS)
        if (!pressed) lingering = false
    }

    val targetAlpha = if (pressed || lingering || focused) 1f else railRestAlpha(visibility, restAlpha)
    val railAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (reduceMotion) tween(0) else tween(if (targetAlpha > 0.5f) RISE_MS else FADE_MS),
        label = "timelineRailAlpha",
    )

    val shownStop by remember {
        derivedStateOf {
            val index = if (pressed) scrubIndex.intValue else currentIndexState.value
            RailMapping.stopForIndex(stopsState.value, index)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val vertical = edge == ScrubberEdge.LEFT || edge == ScrubberEdge.RIGHT
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val lengthPx = if (vertical) heightPx else widthPx
        val stripPx = with(density) { RailBulge.TOUCH_TARGET_DP.dp.toPx() }
        val pitchPx = with(density) { (if (vertical) LABEL_PITCH_V_DP else LABEL_PITCH_H_DP).dp.toPx() }
        val labels = remember(stops, itemCount, lengthPx, pitchPx) {
            RailMapping.thinned(stops, itemCount, lengthPx, pitchPx)
        }

        // ---- the touch target: always there, drawn or not ----
        val stripAlign = when (edge) {
            ScrubberEdge.LEFT -> Alignment.CenterStart
            ScrubberEdge.RIGHT -> Alignment.CenterEnd
            ScrubberEdge.TOP -> Alignment.TopCenter
            ScrubberEdge.BOTTOM -> Alignment.BottomCenter
        }
        if (vertical) {
            // Same system-gesture exclusion band the library strip asked for, so a deliberate drag
            // reaches the pointer handler instead of being read as "back" (see its own KDoc).
            Box(
                Modifier
                    .align(stripAlign)
                    .width(RailBulge.TOUCH_TARGET_DP.dp)
                    .height(EXCLUSION_SPAN_DP.dp)
                    .systemGestureExclusion(),
            )
        }
        Box(
            Modifier
                .align(stripAlign)
                .then(
                    if (vertical) Modifier.fillMaxHeight().width(RailBulge.TOUCH_TARGET_DP.dp)
                    else Modifier.fillMaxWidth().height(RailBulge.TOUCH_TARGET_DP.dp),
                )
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .semantics {
                    contentDescription = TimelineRailCopy.RAIL
                    shownStop?.let { stateDescription = it.label }
                    customActions = listOf(
                        CustomAccessibilityAction(TimelineRailCopy.SCRUB) {
                            revealTick++
                            true
                        },
                        CustomAccessibilityAction(TimelineRailCopy.PREVIOUS) {
                            RailMapping.neighbourStop(stopsState.value, currentIndexState.value, forward = false)
                                ?.let { onScrubToState.value(it.itemIndex) } != null
                        },
                        CustomAccessibilityAction(TimelineRailCopy.NEXT) {
                            RailMapping.neighbourStop(stopsState.value, currentIndexState.value, forward = true)
                                ?.let { onScrubToState.value(it.itemIndex) } != null
                        },
                    )
                }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val e = edgeState.value
                        val isVertical = e == ScrubberEdge.LEFT || e == ScrubberEdge.RIGHT
                        val length = (if (isVertical) size.height else size.width).toFloat()
                        val thickness = (if (isVertical) size.width else size.height).toFloat()
                        fun along(p: Offset) = if (isVertical) p.y else p.x
                        fun across(p: Offset) = if (isVertical) p.x else p.y
                        // Distance of the touch from the screen edge the rail hugs.
                        fun depth(p: Offset): Float {
                            val a = across(p).coerceIn(0f, thickness)
                            return if (e == ScrubberEdge.RIGHT || e == ScrubberEdge.BOTTOM) thickness - a else a
                        }

                        var claimed = false
                        var lastStopKey: Int? = null
                        var primed = false

                        fun scrubTo(p: Offset) {
                            val pos = along(p).coerceIn(0f, length)
                            touchAlong.floatValue = pos
                            touchDepth.floatValue = depth(p)
                            val index = RailMapping.indexForFraction(pos / length, countState.value)
                            if (index != scrubIndex.intValue || !primed) {
                                scrubIndex.intValue = index
                                onScrubToState.value(index)
                            }
                            val key = RailMapping.stopForIndex(stopsState.value, index)?.itemIndex
                            if (primed && key != lastStopKey) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            lastStopKey = key
                            primed = true
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) break
                            if (!claimed) {
                                val dAlong = along(change.position) - along(down.position)
                                val dAcross = across(change.position) - across(down.position)
                                if (abs(dAcross) > slop && abs(dAcross) > abs(dAlong)) break
                                if (abs(dAlong) <= slop) continue
                                claimed = true
                                touchDepth.floatValue = depth(down.position)
                                pressed = true
                                lastStopKey = RailMapping.stopForIndex(
                                    stopsState.value,
                                    RailMapping.indexForFraction(along(down.position) / length, countState.value),
                                )?.itemIndex
                            }
                            scrubTo(change.position)
                            change.consume()
                        }
                        if (claimed) pressed = false
                    }
                },
        )

        // ---- the drawing: pointer-free, so it can bulge out of the strip ----
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = railAlpha }) {
            Canvas(Modifier.fillMaxSize()) {
                val trackDepth = TRACK_INSET_DP.dp.toPx()
                val tick = TICK_LENGTH_DP.dp.toPx()
                fun at(along: Float, depth: Float): Offset {
                    val (x, y) = RailBulge.toOffset(edge, along, depth, size.width, size.height)
                    return Offset(x, y)
                }
                val length = if (vertical) size.height else size.width
                drawLine(
                    color = inkColor.copy(alpha = TICK_ALPHA),
                    start = at(0f, trackDepth),
                    end = at(length, trackDepth),
                    strokeWidth = TRACK_WIDTH_DP.dp.toPx(),
                )
                for (stop in stops) {
                    val pos = RailMapping.centerAlong(stop.itemIndex, itemCount, length)
                    drawLine(
                        color = inkColor.copy(alpha = TICK_ALPHA),
                        start = at(pos, trackDepth),
                        end = at(pos, trackDepth + tick),
                        strokeWidth = TRACK_WIDTH_DP.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                val markerPos = if (pressed) {
                    touchAlong.floatValue.coerceIn(0f, length)
                } else {
                    RailMapping.centerAlong(currentIndex, itemCount, length)
                }
                drawLine(
                    color = accentColor,
                    start = at(markerPos, trackDepth),
                    end = at(markerPos, trackDepth + MARKER_LENGTH_DP.dp.toPx()),
                    strokeWidth = MARKER_WIDTH_DP.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            for (stop in labels) {
                val active = stop.itemIndex == shownStop?.itemIndex
                val labelAlong = RailMapping.centerAlong(stop.itemIndex, itemCount, lengthPx)
                BasicText(
                    text = stop.label,
                    style = TextStyle(
                        color = if (active) accentColor else inkColor,
                        fontSize = LABEL_SP.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.layout { measurable, _ ->
                        val placeable = measurable.measure(
                            Constraints(maxWidth = (if (vertical) stripPx else pitchPx).toInt()),
                        )
                        layout(0, 0) {
                            // Resting top-left; the arc is a layer translation read in the draw
                            // phase, so a finger move re-draws and never re-lays-out.
                            // Inner edge of the label 15 dp from the screen edge, clear of the track
                            // and its ticks, like the library strip: so its centre depth depends on
                            // the label's own size.
                            val restDepth = with(density) { LABEL_INSET_DP.dp.toPx() } +
                                (if (vertical) placeable.width else placeable.height) / 2f
                            val rest = RailBulge.toOffset(edge, labelAlong, restDepth, widthPx, heightPx)
                            placeable.placeWithLayer(
                                (rest.first - placeable.width / 2f).toInt(),
                                (rest.second - placeable.height / 2f).toInt(),
                            ) {
                                val s = strength.value
                                val push = RailBulge.displacement(
                                    labelAlong, touchAlong.floatValue, touchDepth.floatValue, s, spec,
                                )
                                val moved = RailBulge.toOffset(edge, labelAlong, restDepth + push, widthPx, heightPx)
                                translationX = moved.first - rest.first
                                translationY = moved.second - rest.second
                                val scale = RailBulge.scale(labelAlong, touchAlong.floatValue, s, spec)
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin.Center
                            }
                        }
                    },
                )
            }
        }

        // ---- the bubble: only while a finger is down and there is a month to name ----
        val bubbleStop = shownStop
        if (pressed && bubbleStop != null) {
            val gapPx = with(density) { BUBBLE_GAP_DP.dp.toPx() }
            val zonePx = with(density) { EDGE_ZONE_DP.dp.toPx() }
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints(maxWidth = (widthPx * 0.6f).toInt()))
                        layout(0, 0) {
                            val p = RailBulge.bubble(
                                edge = edge,
                                fingerAlongPx = touchAlong.floatValue,
                                fingerDepthPx = touchDepth.floatValue,
                                bubbleWidthPx = placeable.width.toFloat(),
                                bubbleHeightPx = placeable.height.toFloat(),
                                screenWidthPx = widthPx,
                                screenHeightPx = heightPx,
                                edgeZonePx = zonePx,
                                gapPx = gapPx,
                                spec = spec,
                            )
                            placeable.place(p.x.toInt(), p.y.toInt())
                        }
                    }
                    .defaultMinSize(BUBBLE_MIN_DP.dp, BUBBLE_MIN_DP.dp)
                    .background(accentColor, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = bubbleStop.label,
                    style = TextStyle(
                        color = bubbleTextColor,
                        fontSize = BUBBLE_SP.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
