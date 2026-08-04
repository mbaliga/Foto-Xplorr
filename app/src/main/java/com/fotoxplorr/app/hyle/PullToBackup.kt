package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.hyle.Provenance
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.tokens.HyleTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pull-to-backup.
 *
 * This file previously rendered no words at all, on the reading that Hyle's law ("state is
 * SHOWN by material, never SAID by language") forbade them. The owner's mockups settle it
 * the other way: all three states are drawn with explicit copy -- "PULL TO CREATE BACKUP"
 * over a "12,366 Images" count, "RELEASE TO CREATE BACKUP" over the same count, and
 * "Backing up" beside a live "12,322 of 12,366" counter. The mockups are the source of
 * truth for this app's surface, so the copy is here. The Hyle indicator (shape, sweep,
 * spin, Provenance hue + glyph) is kept alongside it, so the state is now both shown and
 * said rather than neither.
 */
enum class BackupPullPhase { IDLE, ARMED, ACTIVE }

/** Live counts for the header copy. [backedUp] only matters while a backup is running. */
data class BackupCounts(
    val total: Int = 0,
    val backedUp: Int = 0,
)

@Stable
class PullToBackupState internal constructor(
    private val thresholdPx: Float,
    private val coroutineScope: CoroutineScope,
    private val onBackupTriggered: suspend () -> Unit,
) {
    var pullPx by mutableFloatStateOf(0f)
        private set

    var isBackingUp by mutableStateOf(false)
        private set

    private var releaseJob: Job? = null

    val phase: BackupPullPhase
        get() = when {
            isBackingUp -> BackupPullPhase.ACTIVE
            pullPx >= thresholdPx -> BackupPullPhase.ARMED
            else -> BackupPullPhase.IDLE
        }

    /** 0f at rest, 1f at the release threshold (not clamped above 1: armed can overshoot visually). */
    val progressFraction: Float get() = (pullPx / thresholdPx).coerceIn(0f, 1f)

    /** Consumes [deltaPx] of a drag (positive = pulling down) and reports how much it absorbed. */
    fun consumePull(deltaPx: Float): Float {
        if (isBackingUp || deltaPx == 0f) return 0f
        releaseJob?.cancel()
        val target = (pullPx + deltaPx).coerceIn(0f, thresholdPx * MAX_OVERPULL_MULTIPLIER)
        val consumed = target - pullPx
        pullPx = target
        return consumed
    }

    /** Called on release (fling/drag-end): snaps to armed-then-backup, or retracts, never mid-air. */
    fun release() {
        if (isBackingUp || releaseJob?.isActive == true) return
        val armed = pullPx >= thresholdPx
        releaseJob = coroutineScope.launch {
            if (armed) {
                isBackingUp = true
                animate(
                    initialValue = pullPx,
                    targetValue = thresholdPx,
                    animationSpec = tween(HyleTokens.Duration.durationInstant),
                ) { value, _ -> pullPx = value }
                runCatching { onBackupTriggered() }
                isBackingUp = false
            }
            animate(
                initialValue = pullPx,
                targetValue = 0f,
                animationSpec = tween(HyleTokens.Duration.durationPane),
            ) { value, _ -> pullPx = value }
        }
    }

    private companion object {
        const val MAX_OVERPULL_MULTIPLIER = 1.6f
    }
}

@Composable
fun rememberPullToBackupState(
    thresholdDp: Dp = 64.dp,
    onBackupTriggered: suspend () -> Unit,
): PullToBackupState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(onBackupTriggered) {
        PullToBackupState(
            thresholdPx = with(density) { thresholdDp.toPx() },
            coroutineScope = scope,
            onBackupTriggered = onBackupTriggered,
        )
    }
}

private class PullToBackupNestedScrollConnection(
    private val state: PullToBackupState,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y < 0f && state.pullPx > 0f) {
            val consumedY = -state.consumePull(-available.y)
            return Offset(0f, consumedY)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) {
            val consumedY = state.consumePull(available.y)
            return Offset(0f, consumedY)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.pullPx > 0f) {
            state.release()
            return available
        }
        return Velocity.Zero
    }
}

/**
 * Wraps a scrollable [content] (the gallery grid) with the pull-to-backup header above it.
 *
 * The header is always laid out (not only mid-pull) so the grid never jumps when a pull
 * starts, matching the mockups where the header band sits above the first tile row at rest.
 */
@Composable
fun PullToBackupHost(
    onBackupTriggered: suspend () -> Unit,
    counts: BackupCounts,
    modifier: Modifier = Modifier,
    thresholdDp: Dp = 64.dp,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToBackupState(thresholdDp, onBackupTriggered)
    val connection = remember(state) { PullToBackupNestedScrollConnection(state) }

    Column(modifier.fillMaxSize()) {
        BackupHeader(
            phase = state.phase,
            progressFraction = state.progressFraction,
            counts = counts,
        )
        header?.invoke()
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .offset { IntOffset(0, (state.pullPx * 0.6f).roundToInt()) },
        ) {
            content()
        }
    }
}

/**
 * The header band from the mockups: indicator + status line, with a count line beneath while
 * idle/armed, and an inline "N of M" counter on the right while backing up.
 */
@Composable
fun BackupHeader(
    phase: BackupPullPhase,
    progressFraction: Float,
    counts: BackupCounts,
    modifier: Modifier = Modifier,
) {
    val active = phase == BackupPullPhase.ACTIVE
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (active) Arrangement.SpaceBetween else Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackupPulseIndicator(phase = phase, progressFraction = progressFraction)
                Text(
                    text = backupStatusText(phase),
                    style = TextStyle(
                        fontSize = if (active) 15.sp else 14.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.SemiBold,
                        letterSpacing = if (active) 0.sp else 1.4.sp,
                    ),
                    color = Color.White,
                )
            }
            if (active) {
                BackupCounter(counts)
            }
        }
        if (!active) {
            Text(
                text = imageCountText(counts.total),
                style = TextStyle(fontSize = 12.sp),
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun BackupCounter(counts: BackupCounts) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = formatCount(counts.backedUp.coerceIn(0, counts.total)),
            style = TextStyle(fontSize = 15.sp),
            color = Color.White.copy(alpha = 0.42f),
        )
        Text(
            text = "of",
            style = TextStyle(fontSize = 15.sp),
            color = Color.White.copy(alpha = 0.42f),
        )
        Text(
            text = formatCount(counts.total),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

/** The three status strings the mockups specify. Pure, so the wording is unit-testable. */
internal fun backupStatusText(phase: BackupPullPhase): String = when (phase) {
    BackupPullPhase.IDLE -> "PULL TO CREATE BACKUP"
    BackupPullPhase.ARMED -> "RELEASE TO CREATE BACKUP"
    BackupPullPhase.ACTIVE -> "Backing up"
}

/** "12,366 Images" / "1 Image" / "No Images", grouped per the mockups' thousands separator. */
internal fun imageCountText(total: Int, locale: Locale = Locale.getDefault()): String = when {
    total <= 0 -> "No Images"
    total == 1 -> "1 Image"
    else -> "${formatCount(total, locale)} Images"
}

internal fun formatCount(value: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(value.toLong())

/**
 * Idle -> armed -> active as shape + motion + Hyle's colour-blind-safe Provenance idiom
 * (glyph + hue, never hue alone): a neutral groove fills clockwise while pulling, switches
 * to Provenance.Cloud's cyan + hollow-ring glyph at the armed threshold, and spins while
 * active, breathing via [Pulse.WATCHED].
 */
@Composable
fun BackupPulseIndicator(
    phase: BackupPullPhase,
    progressFraction: Float,
    modifier: Modifier = Modifier,
) {
    val cloud = Provenance.Cloud
    val neutral = Color.White.copy(alpha = 0.55f)
    val pulseAlpha by rememberPulseAlpha(Pulse.WATCHED)
    val spinTransition = rememberInfiniteTransition(label = "hyle-backup-spin")
    val rotationDegrees by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(HyleTokens.Duration.durationPane * 3, easing = LinearEasing),
        ),
        label = "hyle-backup-rotation",
    )

    val ringColor = when (phase) {
        BackupPullPhase.IDLE -> neutral
        BackupPullPhase.ARMED -> Color.White
        BackupPullPhase.ACTIVE -> cloud.composeHue.copy(alpha = pulseAlpha)
    }
    val sweepDegrees = when (phase) {
        // A rest-state ring with no sweep would read as "broken"; the mockups draw a partial
        // arc at rest, so idle keeps a minimum visible sweep that grows with the pull.
        BackupPullPhase.IDLE -> (90f + 270f * progressFraction)
        BackupPullPhase.ARMED -> 360f
        BackupPullPhase.ACTIVE -> 120f
    }

    Canvas(modifier.size(INDICATOR_SIZE.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.14f, cap = StrokeCap.Round)
        drawCircle(color = Color.White.copy(alpha = 0.18f), style = stroke)
        if (phase == BackupPullPhase.ACTIVE) {
            withTransform({ rotate(rotationDegrees, pivot = center) }) {
                drawArc(
                    color = ringColor, startAngle = -90f, sweepAngle = sweepDegrees,
                    useCenter = false, style = stroke,
                )
            }
        } else {
            drawArc(
                color = ringColor, startAngle = -90f, sweepAngle = sweepDegrees,
                useCenter = false, style = stroke,
            )
        }
        // Redundant non-colour channel (Provenance rule, WCAG 1.4.1): Cloud's glyph is a
        // hollow ring, echoed here so the state still reads by shape alone in greyscale.
        if (phase == BackupPullPhase.ACTIVE) {
            drawCircle(
                color = ringColor,
                radius = size.minDimension * 0.14f,
                style = Stroke(width = size.minDimension * 0.05f),
            )
        }
    }
}

private const val INDICATOR_SIZE = 16
