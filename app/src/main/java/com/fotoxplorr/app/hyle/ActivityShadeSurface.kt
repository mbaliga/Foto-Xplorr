package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The activity shade: what the app is doing, over the top of what the app is showing.
 *
 * **The content does not move.** That is the whole correction this surface exists to carry (owner,
 * 2026-08-18: *"the content inside does not get pushed down, rather the viewpane... moves in over
 * the content... the content should not move up/down or jiggle around as the notifications and
 * controls come in/out"*). The previous version translated the grid downwards by the band's height,
 * so every scan starting or finishing nudged 22,000 photographs down the screen and back — the
 * exact jiggle the owner is describing.
 *
 * So this composable draws [content] once, at full size, and never offsets it. The shade is an
 * opaque black surface laid over the top edge, and growing it *covers* more of the grid rather than
 * pushing any of it anywhere. The top row of tiles ends up half-hidden behind the shade, which is
 * precisely what the mockups show.
 *
 * Three states, and every one of them describes the same list of activities at a different size —
 * see [ShadeState]. Dragging down opens, dragging up closes, one step per drag; tapping cycles the
 * same way for anyone who does not find the gesture.
 *
 * @param topReserve reported back so the host can hand the same strip to the spatial shell, whose
 *   top-edge gesture would otherwise swallow the drag. One number, one source.
 */
@Composable
fun ActivityShade(
    activities: List<BackgroundActivity>,
    state: ShadeState,
    onStateChange: (ShadeState) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val target = shadeHeight(state, activities.size)
    // The shell's own settle curve, respecified for Dp. SpatialMotion.settleSpec is typed to
    // Float; borrowing the FEEL means copying its duration and easing, not its instance.
    val height by animateDpAsState(
        targetValue = target.dp,
        animationSpec = tween(
            durationMillis = SHADE_SETTLE_MILLIS,
            easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f),
        ),
        label = "shade-height",
    )

    Box(modifier.fillMaxSize()) {
        // Drawn FIRST and never moved. Everything above it in this Box covers it; nothing
        // displaces it.
        content()

        if (activities.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // Opaque, because it is the app's own background coming forward rather than a
                    // translucent overlay. A scrim here would show the photographs through the
                    // progress bar and make both harder to read.
                    .background(Color.Black)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            val next = shadeAfterDrag(state, delta)
                            if (next != state) onStateChange(next)
                        },
                    )
                    .clickable {
                        onStateChange(
                            when (state) {
                                ShadeState.COLLAPSED -> ShadeState.NOTIFICATION
                                ShadeState.NOTIFICATION -> ShadeState.EXPANDED
                                ShadeState.EXPANDED -> ShadeState.COLLAPSED
                            },
                        )
                    }
                    .statusBarsPadding()
                    .height(height),
            ) {
                when (state) {
                    ShadeState.COLLAPSED -> CollapsedStrip(activities)
                    ShadeState.NOTIFICATION -> ActivityRows(activities)
                    ShadeState.EXPANDED -> {
                        HeroPanel(activities.first())
                        ActivityRows(activities.drop(1))
                    }
                }
            }
        }
    }
}

/**
 * The collapsed state: one coloured sliver per activity, side by side, no words.
 *
 * Each takes an equal share of the width rather than a share proportional to anything, so the
 * number of running jobs is countable at a glance — which is the only thing this state is for.
 */
@Composable
private fun CollapsedStrip(activities: List<BackgroundActivity>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(COLLAPSED_HEIGHT.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        activities.take(MAX_COLLAPSED_SLIVERS).forEach { activity ->
            ProgressTrack(
                activity = activity,
                modifier = Modifier
                    .weight(1f)
                    .height(COLLAPSED_BAR.dp),
            )
        }
    }
}

/** One row per activity: glyph, name, and the count, exactly as the mockup draws it. */
@Composable
private fun ActivityRows(activities: List<BackgroundActivity>) {
    activities.take(MAX_LISTED_ROWS).forEach { activity ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(NOTIFICATION_ROW.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinnerGlyph(activity)
            Text(
                text = activity.error ?: activity.kind.label,
                color = if (activity.error != null) Color(0xFFFFB4AB) else Color.White,
                style = TextStyle(fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            if (activity.error == null) CountReadout(activity)
        }
    }
    if (activities.size > MAX_LISTED_ROWS) {
        Text(
            text = "and ${activities.size - MAX_LISTED_ROWS} more",
            color = Color.White.copy(alpha = 0.45f),
            style = TextStyle(fontSize = 12.sp),
            modifier = Modifier
                .fillMaxWidth()
                .height(OVERFLOW_ROW.dp)
                .padding(start = 14.dp, top = 6.dp),
        )
    }
}

/**
 * The expanded state's hero: the activity given room.
 *
 * The mockup puts an illustration above the name; there is no illustration asset in the app, so
 * this uses the activity's own accent as a soft field instead of shipping a picture of a picture.
 * The information — name, count, bar — is the mockup's, at the mockup's sizes.
 */
@Composable
private fun HeroPanel(activity: BackgroundActivity) {
    Column(Modifier.fillMaxWidth().height(EXPANDED_HERO.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    Brush.radialGradient(
                        listOf(activity.kind.accent.copy(alpha = 0.28f), Color.Transparent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A stack of plates standing in for the mockup's stack of photographs: three
            // rectangles, fanned. Geometry rather than an asset, same as the album stacks.
            Box(Modifier.size(width = 116.dp, height = 96.dp), contentAlignment = Alignment.Center) {
                // Drawn back to front, so the accented plate is the one the eye lands on.
                listOf(10f, -6f, 0f).forEachIndexed { index, angle ->
                    Box(
                        Modifier
                            .size(width = 72.dp, height = 84.dp)
                            .graphicsLayer { rotationZ = angle }
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (index == 2) activity.kind.accent else Color.White.copy(alpha = 0.18f),
                            ),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = activity.error ?: activity.kind.label,
                color = if (activity.error != null) Color(0xFFFFB4AB) else Color.White,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (activity.error == null) CountReadout(activity, large = true)
        }
        ProgressTrack(
            activity = activity,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .height(EXPANDED_BAR.dp),
        )
    }
}

/** `4,822 of 12,366` — the count bold, the total muted, as one readout. */
@Composable
private fun CountReadout(activity: BackgroundActivity, large: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = grouped(activity.completed),
            color = Color.White,
            style = TextStyle(
                fontSize = if (large) 20.sp else 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        if (activity.total > 0) {
            Text(
                text = " of ${grouped(activity.total)}",
                color = Color.White.copy(alpha = 0.45f),
                style = TextStyle(fontSize = if (large) 14.sp else 12.sp),
                modifier = Modifier.padding(start = 3.dp, bottom = 1.dp),
            )
        }
    }
}

/**
 * The bar. Determinate when the total is known, and a travelling sweep when it is not.
 *
 * An indeterminate bar rather than a bar stuck at zero: a scan that has not counted its files yet
 * genuinely does not know how far along it is, and a motionless empty track reads as a job that has
 * hung rather than one that has started.
 */
@Composable
private fun ProgressTrack(activity: BackgroundActivity, modifier: Modifier = Modifier) {
    val fraction = activity.fraction
    val animated by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(SHADE_SETTLE_MILLIS, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "shade-progress",
    )
    val sweep by rememberIndeterminateSweep(enabled = fraction == null && activity.error == null)

    Canvas(modifier) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = Color.White.copy(alpha = 0.14f), cornerRadius = radius)
        val accent = if (activity.error != null) Color(0xFFFFB4AB) else activity.kind.accent
        if (fraction != null) {
            if (animated > 0f) {
                drawRoundRect(
                    color = accent,
                    size = Size(size.width * animated, size.height),
                    cornerRadius = radius,
                )
            }
        } else {
            // A short block travelling the track, clipped to it at both ends so it slides in and
            // out rather than appearing and vanishing whole.
            val blockWidth = size.width * INDETERMINATE_BLOCK
            val leadingEdge = (size.width + blockWidth) * sweep
            val left = (leadingEdge - blockWidth).coerceIn(0f, size.width)
            val right = leadingEdge.coerceIn(0f, size.width)
            if (right > left) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(left, 0f),
                    size = Size(width = right - left, height = size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/** The small turning disc beside an activity's name. */
@Composable
private fun SpinnerGlyph(activity: BackgroundActivity) {
    val sweep by rememberIndeterminateSweep(enabled = activity.error == null && !activity.isFinished)
    Canvas(Modifier.size(GLYPH.dp)) {
        drawCircle(color = Color.White.copy(alpha = 0.16f), radius = size.minDimension / 2f)
        val accent = if (activity.error != null) Color(0xFFFFB4AB) else activity.kind.accent
        drawArc(
            color = accent,
            startAngle = -90f + sweep * 360f,
            sweepAngle = 110f,
            useCenter = true,
        )
    }
}

/**
 * A 0..1 value that keeps travelling while [enabled], for the sweeps and the spinner.
 *
 * One helper for both, so the bar and the disc beside it turn at the same rate — two independently
 * chosen periods look like two unrelated things happening rather than one job running.
 */
@Composable
private fun rememberIndeterminateSweep(enabled: Boolean): State<Float> {
    val transition = rememberInfiniteTransition(label = "shade-sweep")
    val sweep = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MILLIS, easing = LinearEasing)),
        label = "shade-sweep-value",
    )
    // Held at zero rather than not composed at all: an animation that stops existing when a job
    // finishes would take its recomposition scope with it mid-frame.
    return if (enabled) sweep else remember { mutableFloatStateOf(0f) }
}

/** Thousands separators without pulling in a locale-formatted number for a progress readout. */
internal fun grouped(value: Int): String {
    val digits = value.coerceAtLeast(0).toString()
    if (digits.length <= 3) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

private const val MAX_COLLAPSED_SLIVERS = 6
private const val COLLAPSED_BAR = 6
private const val EXPANDED_BAR = 6
private const val GLYPH = 20
private const val INDETERMINATE_BLOCK = 0.35f

/** One turn of an indeterminate bar or spinner. */
private const val SWEEP_MILLIS = 1_400

/** Matches SpatialMotion.settleSpec's 320ms, so the shade arrives on the app's own timing. */
private const val SHADE_SETTLE_MILLIS = 320
