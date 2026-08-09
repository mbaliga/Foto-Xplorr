package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ScanState
import dev.aarso.hyle.Pulse
import dev.aarso.cellshell.SpatialMotion
import dev.aarso.hyle.tokens.HyleTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The notification layer: a warning glyph beside a single line of status copy, lying on the
 * black *behind* the surface it belongs to.
 *
 * **This is a room, not a banner.** It used to be a sibling above the grid in a `Column`,
 * expanding vertically to shove the content down — which is the popup shape the constellation's
 * navigation exists to avoid (owner, 2026-08-09: *"instead of giving a popup, shrinks the
 * viewpane to show the notification, which is a layer deeper and stays in the back"*). So the
 * notification borrows the room language wholesale: it is drawn first and never moves, and what
 * animates is [content] — the pane's frame recedes from the top, rounding its corners as it
 * goes, exactly as the spatial shell's home card does when it parks. The notification was
 * always there; the pane simply stopped covering it.
 *
 * Two consequences worth stating, because both are easy to undo by accident:
 * - The layer is drawn **before** the pane and is never raised above it. A notification that
 *   comes forward is a popup again, whatever it is called.
 * - The pane's contents do **not** scale. Its frame shrinks; the photos inside keep their size,
 *   which is what the owner's reference shows (the grid's column seams do not move — measured).
 *
 * The copy is wired to the app's real [ScanState] (MediaIndexer's background scan), which is the
 * one genuine background-activity signal Foto Xplorr has, so its text is factual rather than a
 * mock string. The reference clip's own wording is a generic stand-in for the interaction
 * (owner, same note) and is deliberately not reproduced.
 *
 * @param showWhenIdle keep the layer revealed even with nothing to report. Off by default, so
 *   the pane runs edge to edge and the notification costs no screen.
 */
@Composable
fun NotificationRoom(
    scanState: ScanState,
    modifier: Modifier = Modifier,
    showWhenIdle: Boolean = false,
    content: @Composable () -> Unit,
) {
    var showCompletionPulse by remember { mutableStateOf(false) }
    LaunchedEffect(scanState) {
        if (scanState is ScanState.Complete) {
            showCompletionPulse = true
            delay(COMPLETION_HOLD_MILLIS)
            showCompletionPulse = false
        }
    }

    val revealed = showWhenIdle ||
        scanState is ScanState.Scanning ||
        scanState is ScanState.Error ||
        showCompletionPulse

    // The shell's own settle, not a local curve. "Borrows the room navigation" is a claim about
    // feel, and a pane that receded on a different timing to every other pane in the app would
    // break it -- SpatialMotion documents these as a contract precisely so this stays true.
    val reveal = animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = SpatialMotion.settleSpec,
        label = "notification-reveal",
    )

    val density = LocalDensity.current
    val bandPx = with(density) { BAND_HEIGHT.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        // Deeper layer, drawn first. It does not animate: it is uncovered, not introduced.
        AlertBannerRow(
            scanState = scanState,
            completed = showCompletionPulse && scanState !is ScanState.Scanning,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Box(
            modifier = Modifier
                // A PLACEMENT offset, not a layer translation: the pane still has to answer
                // touches where it is drawn. The spatial shell learned this the same way.
                .offset { IntOffset(0, (bandPx * reveal.value).roundToInt()) }
                // Read on the draw pass, so a reveal costs no recomposition of the grid inside.
                .graphicsLayer {
                    val radius = SpatialMotion.PARK_CORNER_DP * reveal.value
                    shape = RoundedCornerShape(topStart = radius.dp, topEnd = radius.dp)
                    clip = radius > 0.01f
                },
        ) {
            content()
        }
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
private fun AlertBannerRow(
    scanState: ScanState,
    completed: Boolean,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier
            .fillMaxWidth()
            .height(BAND_HEIGHT.dp)
            .padding(horizontal = 16.dp),
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

/**
 * How much of the pane the notification layer takes when revealed, in dp.
 *
 * One number doing two jobs, and they must agree: it is the height the layer draws itself at,
 * and it is how far the pane's frame recedes to uncover it. Drifting them apart either clips
 * the sentence or opens a gap of nothing under it -- the same reason the shell keeps a single
 * BAND_DP for its parked card.
 */
private const val BAND_HEIGHT = 44
