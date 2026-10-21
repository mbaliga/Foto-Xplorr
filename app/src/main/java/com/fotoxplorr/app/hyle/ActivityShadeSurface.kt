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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
                    // Fixed on purpose (reference design): the strip ground is the pure black
                    // sampled from the owner's screenshots.
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
 * The collapsed state, to the owner's screenshots (2026-10): one slot per activity, side by side
 * on a black band, each slot a thin grey track with the kind's colour run along it as far as its
 * progress, and the kind's white mark at the slot's right end.
 *
 * Equal slots rather than shares proportional to anything, so the number of running jobs is
 * countable at a glance — which is what this state is for. With one job the track runs the whole
 * width and carries no mark, exactly as the one-job screenshot draws it: the colour alone says
 * which job it is, and a mark beside a bar that spans the screen would be labelling the obvious.
 *
 * Geometry, in dp, measured off the three-job screenshot: 12 in from either edge, slots 28 apart,
 * 4 between a track and its 12 mark; tracks 8 tall with the fill inset 2 all round.
 */
@Composable
private fun CollapsedStrip(activities: List<BackgroundActivity>) {
    val shown = activities.take(MAX_COLLAPSED_SLIVERS)
    Row(
        Modifier
            .fillMaxWidth()
            .height(COLLAPSED_HEIGHT.dp)
            .padding(horizontal = STRIP_INSET.dp),
        horizontalArrangement = Arrangement.spacedBy(SLOT_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { activity ->
            // Keyed by id so a job finishing does not hand its half-animated bar to whichever job
            // shifts into its index — see BackgroundActivity.id.
            key(activity.id) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    ProgressTrack(
                        activity = activity,
                        modifier = Modifier
                            .weight(1f)
                            .height(TRACK_HEIGHT.dp),
                    )
                    if (shown.size > 1) {
                        Spacer(Modifier.width(GLYPH_GAP.dp))
                        KindGlyph(activity)
                    }
                }
            }
        }
    }
}

/**
 * The kind's mark, at the reference's 12dp.
 *
 * The vector is drawn at [GLYPH_DP] (16) because its ink sits in the middle 12 units of its
 * viewport — see [ActivityGlyphs] — so the layout box is 12 and the drawing overhangs it by the
 * vector's own 2 units of air on each side. Nothing else in the slot is near enough to care.
 */
@Composable
private fun KindGlyph(activity: BackgroundActivity) {
    Box(Modifier.size(GLYPH_SLOT.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = activity.kind.glyph,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.requiredSize(GLYPH_DP.dp),
        )
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
                color = if (activity.error != null) ERROR_ACCENT else Color.White,
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
            Box(Modifier.size(width = 132.dp, height = 108.dp), contentAlignment = Alignment.Center) {
                // Drawn back to front, so the accented plate is the one the eye lands on. The two
                // behind are OUTLINED rather than filled: at 18% white on a black ground they were
                // invisible and the stack rendered as a single card, which is the whole point of
                // the illustration lost.
                listOf(14f, -8f, 0f).forEachIndexed { index, angle ->
                    val front = index == 2
                    Box(
                        Modifier
                            .size(width = 72.dp, height = 84.dp)
                            .graphicsLayer { rotationZ = angle }
                            .clip(RoundedCornerShape(8.dp))
                            // Fixed on purpose (reference design): the back plate is the sampled
                            // neutral, the kind colour is the only hue.
                            .background(if (front) activity.kind.accent else Color(0xFF1C1C22))
                            .then(
                                if (front) {
                                    Modifier
                                } else {
                                    Modifier.border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.30f),
                                        RoundedCornerShape(8.dp),
                                    )
                                },
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
                color = if (activity.error != null) ERROR_ACCENT else Color.White,
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
                .height(TRACK_HEIGHT.dp),
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
 *
 * The track is the screenshots' (2026-10): a pill whose grey lightens very slightly from top to
 * bottom, with the colour inset 2dp inside it as a thinner pill of its own. Every per-frame value
 * — the eased progress, the sweep — is read inside the draw lambda, so a moving bar never
 * recomposes anything.
 */
@Composable
private fun ProgressTrack(activity: BackgroundActivity, modifier: Modifier = Modifier) {
    val fraction = activity.fraction
    val failed = activity.error != null
    val animated by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(SHADE_SETTLE_MILLIS, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "shade-progress",
    )
    val sweep by rememberIndeterminateSweep(enabled = fraction == null && !failed)
    // One brush per track, not one per frame: a shader brush caches its shader by size, so a
    // remembered instance is one allocation for the life of the bar.
    val trackBrush = remember { Brush.verticalGradient(listOf(TRACK_TOP, TRACK_BOTTOM)) }

    Canvas(modifier) {
        drawRoundRect(brush = trackBrush, cornerRadius = CornerRadius(size.height / 2f))
        val inset = FILL_INSET.dp.toPx()
        val innerWidth = size.width - inset * 2f
        val innerHeight = size.height - inset * 2f
        if (innerWidth <= 0f || innerHeight <= 0f) return@Canvas
        val radius = CornerRadius(innerHeight / 2f)
        val accent = if (failed) ERROR_ACCENT else activity.kind.accent
        when {
            // A failed job's bar stops where it was. One that never learnt its total has no
            // honest width to stop at, so the failure shows as a full run of the error colour.
            failed -> drawFill(accent, inset, innerWidth * (fraction ?: 1f), innerHeight, radius)
            fraction != null -> drawFill(accent, inset, innerWidth * animated, innerHeight, radius)
            else -> {
                // A short block travelling the track, clipped to it at both ends so it slides in
                // and out rather than appearing and vanishing whole.
                val blockWidth = innerWidth * INDETERMINATE_BLOCK
                val leadingEdge = (innerWidth + blockWidth) * sweep
                val left = (leadingEdge - blockWidth).coerceIn(0f, innerWidth)
                val right = leadingEdge.coerceIn(0f, innerWidth)
                if (right > left) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(inset + left, inset),
                        size = Size(right - left, innerHeight),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

/** The colour inside the track: a pill from its left end, [width] long, or nothing at zero. */
private fun DrawScope.drawFill(
    color: Color,
    inset: Float,
    width: Float,
    height: Float,
    radius: CornerRadius,
) {
    if (width <= 0f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(width, height),
        cornerRadius = radius,
    )
}

/** The small turning disc beside an activity's name. */
@Composable
private fun SpinnerGlyph(activity: BackgroundActivity) {
    val sweep by rememberIndeterminateSweep(enabled = activity.error == null && !activity.isFinished)
    Canvas(Modifier.size(GLYPH.dp)) {
        drawCircle(color = Color.White.copy(alpha = 0.16f), radius = size.minDimension / 2f)
        val accent = if (activity.error != null) ERROR_ACCENT else activity.kind.accent
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

/** The collapsed strip's geometry, in dp, measured off the owner's screenshots (2026-10). */
private const val STRIP_INSET = 12
private const val SLOT_GAP = 28
private const val TRACK_HEIGHT = 8
private const val FILL_INSET = 2
private const val GLYPH_SLOT = 12
private const val GLYPH_GAP = 4

// Fixed on purpose (reference design): the track greys are sampled from the strip screenshots.
/** The track's grey, lightening a little from top to bottom, as the screenshots draw it. */
private val TRACK_TOP = Color(0xFF383838)
private val TRACK_BOTTOM = Color(0xFF4D4D4D)

// Fixed on purpose (semantic): the error salmon, the same value as the Material dark error, never
// the accent.
/** What a failed job's bar and name turn. Nothing in [ActivityKind] may use it. */
private val ERROR_ACCENT = Color(0xFFFFB4AB)

private const val GLYPH = 20
private const val INDETERMINATE_BLOCK = 0.35f

/** One turn of an indeterminate bar or spinner. */
private const val SWEEP_MILLIS = 1_400

/** Matches SpatialMotion.settleSpec's 320ms, so the shade arrives on the app's own timing. */
private const val SHADE_SETTLE_MILLIS = 320
