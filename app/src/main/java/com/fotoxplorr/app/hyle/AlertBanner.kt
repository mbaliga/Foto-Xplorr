package com.fotoxplorr.app.hyle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ScanState
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.tokens.HyleTokens
import kotlinx.coroutines.delay

/**
 * The "Notifications & Alerts" band from the mockups: a warning glyph beside a single line
 * of status copy, centred on black above the grid.
 *
 * This file previously rendered an icon and a bare progress bar with no sentence at all,
 * on a reading of Hyle's law that a literal status line was forbidden. The mockups draw the
 * sentence explicitly, so it is here; the icon, motion and Hyle hues stay alongside it.
 *
 * The band is wired to the app's real [ScanState] (MediaIndexer's background scan), which is
 * the one genuine background-activity signal Foto Xplorr has -- so its text is factual
 * rather than a mock string. When there is nothing to report it shows the mockups' resting
 * line, [IDLE_MESSAGE], only if [showWhenIdle] is set by the caller; otherwise it collapses
 * so the grid can run edge to edge.
 */
@Composable
fun ScanActivityAlertBanner(
    scanState: ScanState,
    modifier: Modifier = Modifier,
    showWhenIdle: Boolean = false,
) {
    var showCompletionPulse by remember { mutableStateOf(false) }
    LaunchedEffect(scanState) {
        if (scanState is ScanState.Complete) {
            showCompletionPulse = true
            delay(COMPLETION_HOLD_MILLIS)
            showCompletionPulse = false
        }
    }

    val visible = showWhenIdle ||
        scanState is ScanState.Scanning ||
        scanState is ScanState.Error ||
        showCompletionPulse
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(HyleTokens.Duration.durationCalm)) + expandVertically(tween(HyleTokens.Duration.durationCalm)),
        exit = fadeOut(tween(HyleTokens.Duration.durationCalm)) + shrinkVertically(tween(HyleTokens.Duration.durationCalm)),
        modifier = modifier,
    ) {
        AlertBannerRow(
            scanState = scanState,
            completed = showCompletionPulse && scanState !is ScanState.Scanning,
        )
    }
}

/**
 * Empty by design.
 *
 * This used to read "Notifications & Alerts appear here" — a label describing a container
 * rather than saying anything true about the app's state, which is exactly the kind of
 * placeholder chrome the fonebrew pattern bans (docs/fonebrew-navigation.md). The banner now
 * collapses entirely when there is nothing to report, so the grid runs edge to edge.
 *
 * Kept as a constant rather than deleted because [alertBannerMessage] is a total function and
 * its callers still need a defined "nothing to say" result.
 */
const val IDLE_MESSAGE = ""

/**
 * The one line of copy the banner shows for a given state. Pure, so the wording is
 * unit-testable without composing anything.
 */
internal fun alertBannerMessage(scanState: ScanState, completed: Boolean): String = when {
    scanState is ScanState.Error -> scanState.message
    scanState is ScanState.Scanning && scanState.discovered > 0 ->
        "Indexing ${scanState.scanned} of ${scanState.discovered}"
    scanState is ScanState.Scanning -> "Indexing your library"
    // An incremental pass found a handful of changed items, so reporting the library total
    // would be a lie dressed as progress — the very thing that made a single screenshot look
    // like a full re-index. Say what actually happened instead.
    completed && scanState is ScanState.Complete && scanState.incremental -> when (scanState.total) {
        0 -> "Library up to date"
        1 -> "Added 1 new item"
        else -> "Added ${scanState.total} new items"
    }
    completed && scanState is ScanState.Complete -> "Library up to date · ${scanState.total} items"
    else -> IDLE_MESSAGE
}

@Composable
private fun AlertBannerRow(scanState: ScanState, completed: Boolean) {
    val isScanning = scanState is ScanState.Scanning
    val isError = scanState is ScanState.Error
    val idle = !isScanning && !isError && !completed
    val pulseAlpha by rememberPulseAlpha(Pulse.WATCHED)
    val spinTransition = rememberInfiniteTransition(label = "hyle-alert-spin")
    val rotationDegrees by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(HyleTokens.Duration.durationPane * 4, easing = LinearEasing)),
        label = "hyle-alert-rotation",
    )

    val icon = when {
        isScanning -> Icons.Outlined.Refresh
        completed && !isError -> Icons.Outlined.CheckCircle
        // The mockups draw a red warning triangle on the resting banner, so idle and error
        // share the glyph and differ only in what the sentence says.
        else -> Icons.Filled.Warning
    }
    val tint = when {
        isError || idle -> HyleTokens.Color.colorFeedbackDanger.toComposeColor()
        completed -> HyleTokens.Color.colorFeedbackSuccess.toComposeColor()
        else -> HyleTokens.Color.colorPaletteAccentViolet.toComposeColor().copy(alpha = pulseAlpha)
    }
    val message = alertBannerMessage(scanState, completed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (isScanning) rotationDegrees else 0f },
        )
        Text(
            text = message,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isScanning) {
            val scanning = scanState as ScanState.Scanning
            if (scanning.discovered > 0) {
                LinearProgressIndicator(
                    progress = { (scanning.scanned.toFloat() / scanning.discovered).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(3.dp),
                    color = tint,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.weight(1f).height(3.dp), color = tint)
            }
        }
    }
}

private const val COMPLETION_HOLD_MILLIS = 1_800L
