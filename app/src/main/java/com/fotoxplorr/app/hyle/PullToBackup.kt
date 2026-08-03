package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.Provenance
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.tokens.HyleTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pull-to-backup, governed by Hyle's law (state is SHOWN by material, never SAID by
 * language): no "PULL TO CREATE BACKUP" / "RELEASE TO CREATE BACKUP" / "Backing up N of M"
 * copy anywhere in this file. Pull progress, the release threshold, and the in-progress
 * state are expressed entirely through [BackupPulseIndicator]'s shape, motion and Hyle's
 * Pulse ("heartbeat, not weather") idiom; a numeric percentage is available but demoted to
 * small, secondary, optional text -- never the headline, and never shown while active.
 */
enum class BackupPullPhase { IDLE, ARMED, ACTIVE }

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
        // An upward drag while mid-pull retracts the indicator before the list itself scrolls,
        // so releasing never leaves it stranded half-open.
        if (source == NestedScrollSource.UserInput && available.y < 0f && state.pullPx > 0f) {
            val consumedY = -state.consumePull(-available.y)
            return Offset(0f, consumedY)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // The scrollable content had nothing left to consume for a downward drag, i.e. it's
        // already at its top edge -- treat the remainder as a pull.
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
 * Wraps a scrollable [content] (a gallery grid) with a pull-to-backup affordance revealed
 * above it. See [BackupPullPhase]'s doc header for the no-literal-status-text rule.
 */
@Composable
fun PullToBackupHost(
    onBackupTriggered: suspend () -> Unit,
    modifier: Modifier = Modifier,
    thresholdDp: Dp = 64.dp,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToBackupState(thresholdDp, onBackupTriggered)
    val connection = remember(state) { PullToBackupNestedScrollConnection(state) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { thresholdDp.toPx() }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .offset { IntOffset(0, (state.pullPx * 0.6f).roundToInt()) },
        ) {
            content()
        }
        if (state.pullPx > 0f || state.phase == BackupPullPhase.ACTIVE) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = HyleSpacing.md.dp)
                    .offset {
                        IntOffset(0, ((state.pullPx - thresholdPx) * 0.35f).roundToInt().coerceAtMost(0))
                    },
            ) {
                BackupPulseIndicator(phase = state.phase, progressFraction = state.progressFraction)
            }
        }
    }
}

/**
 * Idle -> armed -> active expressed as shape + motion + Hyle's colour-blind-safe Provenance
 * idiom (glyph + hue, never hue alone): a neutral groove fills clockwise while pulling
 * (shape/position only -- no colour claim yet since nothing has happened); at the armed
 * threshold it switches to Provenance.Cloud's cyan + hollow-ring glyph; while active it
 * spins (motion) and breathes via [Pulse.WATCHED] (Hyle's "heartbeat, not weather" idiom).
 * The optional numeric readout is small, secondary text -- shown only while idle-pulling,
 * never during the active phase, and never the headline.
 */
@Composable
fun BackupPulseIndicator(
    phase: BackupPullPhase,
    progressFraction: Float,
    modifier: Modifier = Modifier,
) {
    val cloud = Provenance.Cloud
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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
        BackupPullPhase.ARMED -> cloud.composeHue
        BackupPullPhase.ACTIVE -> cloud.composeHue.copy(alpha = pulseAlpha)
    }
    val sweepDegrees = when (phase) {
        BackupPullPhase.IDLE -> 360f * progressFraction
        BackupPullPhase.ARMED -> 360f
        BackupPullPhase.ACTIVE -> 120f
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(HyleTokens.Dimension.sizeControlLg.dp)) {
            val stroke = Stroke(width = size.minDimension * 0.12f, cap = StrokeCap.Round)
            drawCircle(color = neutral.copy(alpha = neutral.alpha * 0.4f), style = stroke)
            if (sweepDegrees > 0f) {
                if (phase == BackupPullPhase.ACTIVE) {
                    withTransform({ rotate(rotationDegrees, pivot = center) }) {
                        drawArc(color = ringColor, startAngle = -90f, sweepAngle = sweepDegrees, useCenter = false, style = stroke)
                    }
                } else {
                    drawArc(color = ringColor, startAngle = -90f, sweepAngle = sweepDegrees, useCenter = false, style = stroke)
                }
            }
            // Redundant non-colour channel (Provenance rule, WCAG 1.4.1): Cloud's own glyph is a
            // hollow ring (vs on-device's filled disc) -- echoed here as a small centre ring so the
            // affordance still reads by shape alone in greyscale / under colour-blindness.
            if (phase != BackupPullPhase.IDLE) {
                drawCircle(color = ringColor, radius = size.minDimension * 0.14f, style = Stroke(width = size.minDimension * 0.045f))
            }
        }
        if (phase == BackupPullPhase.IDLE && progressFraction > 0.08f) {
            Text(
                "${(progressFraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}
