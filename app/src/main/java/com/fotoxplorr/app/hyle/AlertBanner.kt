package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.recognition.RecognitionProgress
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
 * The layer has two sizes (owner, 2026-08-18: *"the top notification needs a compact view, and an
 * expanded view (when a user pulls down on it)"*). Compact is one line -- glyph, sentence,
 * progress -- and is what a background scan is worth on its own. Pulling down on it opens the
 * expanded view: the same status with its numbers spelled out and somewhere to act on them. It is
 * the room gesture applied to the notification itself, which is the point: the pane recedes
 * further and the layer that was always behind it is simply uncovered more.
 *
 * @param showWhenIdle keep the layer revealed even with nothing to report. Off by default, so
 *   the pane runs edge to edge and the notification costs no screen.
 * @param expanded whether the expanded view is showing. Hoisted, because the host has to reserve
 *   the same strip from the spatial shell's own top-edge gesture -- see [notificationBandHeight].
 */
@Composable
fun NotificationRoom(
    scanState: ScanState,
    modifier: Modifier = Modifier,
    recognition: RecognitionProgress = RecognitionProgress(),
    showWhenIdle: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    onRescan: (() -> Unit)? = null,
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

    // Reveal only when the banner actually has something to say. Previously these two conditions
    // were independent, so the layer could open on a state the copy had no wording for -- which
    // is how an empty red triangle ended up on screen.
    val hasMessage = alertBannerMessage(
        scanState,
        showCompletionPulse && scanState !is ScanState.Scanning,
        recognition,
    ) != null
    val revealed = hasMessage && (
        showWhenIdle ||
            scanState is ScanState.Scanning ||
            scanState is ScanState.Error ||
            showCompletionPulse
        )

    // The shell's own settle, not a local curve. "Borrows the room navigation" is a claim about
    // feel, and a pane that receded on a different timing to every other pane in the app would
    // break it -- SpatialMotion documents these as a contract precisely so this stays true.
    val reveal = animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = SpatialMotion.settleSpec,
        label = "notification-reveal",
    )

    val density = LocalDensity.current
    // The pane recedes by exactly the height the layer is drawing at, compact or expanded, so the
    // two can never disagree about how much of the notification is uncovered.
    val bandHeight = if (expanded) EXPANDED_HEIGHT.dp else BAND_HEIGHT.dp
    val animatedBand by animateFloatAsState(
        targetValue = with(density) { bandHeight.toPx() },
        animationSpec = SpatialMotion.settleSpec,
        label = "notification-band",
    )
    val bandPx = animatedBand

    Box(modifier = modifier.fillMaxWidth()) {
        // Deeper layer, drawn first. It does not animate: it is uncovered, not introduced.
        AlertBannerRow(
            scanState = scanState,
            completed = showCompletionPulse && scanState !is ScanState.Scanning,
            recognition = recognition,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            onRescan = onRescan,
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
 * The one line of copy the banner shows for a given state, or **null when there is nothing to
 * say**. Pure, so the wording is unit-testable without composing anything.
 *
 * Null rather than an empty string, deliberately. This used to return `""` for the idle case,
 * and the row rendered anyway — so the resting state of the app was a red warning triangle with
 * no text beside it, on a screen where nothing was actually wrong. A caller cannot accidentally
 * render nothing-as-something if "nothing" is not a String.
 *
 * [recognition] is a parameter because the notification layer is revealed by recognition state
 * (see the `showWhenIdle` argument at the call site) while the copy was written only from
 * [scanState]. The input that opens the banner has to be the input that writes it, or the banner
 * opens with nothing to report — which is precisely how the bare triangle appeared.
 */
internal fun alertBannerMessage(
    scanState: ScanState,
    completed: Boolean,
    recognition: RecognitionProgress = RecognitionProgress(),
): String? = when {
    scanState is ScanState.Error -> scanState.message
    recognition.message != null -> recognition.message
    scanState is ScanState.Scanning && scanState.discovered > 0 ->
        "Indexing ${scanState.scanned} of ${scanState.discovered}"
    scanState is ScanState.Scanning -> "Indexing your library"
    recognition.running && recognition.total > 0 ->
        "Recognising ${recognition.completed} of ${recognition.total}"
    recognition.running -> "Recognising your photos"
    // An incremental pass found a handful of changed items, so reporting the library total
    // would be a lie dressed as progress — the very thing that made a single screenshot look
    // like a full re-index. Say what actually happened instead.
    completed && scanState is ScanState.Complete && scanState.incremental -> when (scanState.total) {
        0 -> "Library up to date"
        1 -> "Added 1 new item"
        else -> "Added ${scanState.total} new items"
    }
    completed && scanState is ScanState.Complete -> "Library up to date · ${scanState.total} items"
    else -> null
}

@Composable
private fun AlertBannerRow(
    scanState: ScanState,
    completed: Boolean,
    recognition: RecognitionProgress,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRescan: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val message = alertBannerMessage(scanState, completed, recognition) ?: return
    val isError = scanState is ScanState.Error || recognition.message != null
    val isWorking = scanState is ScanState.Scanning || recognition.running
    val pulseAlpha by rememberPulseAlpha(Pulse.WATCHED)
    val spinTransition = rememberInfiniteTransition(label = "hyle-alert-spin")
    val rotationDegrees by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(HyleTokens.Duration.durationPane * 4, easing = LinearEasing)),
        label = "hyle-alert-rotation",
    )

    // The warning glyph now means only one thing: something is actually wrong. It used to be the
    // resting icon too, so a perfectly healthy app wore an error mark permanently.
    val icon = when {
        isError -> Icons.Filled.Warning
        isWorking -> Icons.Outlined.Refresh
        else -> Icons.Outlined.CheckCircle
    }
    val tint = when {
        isError -> HyleTokens.Color.colorFeedbackDanger.toComposeColor()
        isWorking -> HyleTokens.Color.colorPaletteAccentViolet.toComposeColor().copy(alpha = pulseAlpha)
        else -> HyleTokens.Color.colorFeedbackSuccess.toComposeColor()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Pull down to open, up to close (owner: *"an expanded view (when a user pulls down
            // on it)"*). A tap does the same thing, because a one-line strip is a small target
            // to start a drag in and discovering the gesture should not be the only way through.
            //
            // The host reserves this strip from the spatial shell's top-edge gesture -- see
            // SpatialShell's topReserve -- or the shell would claim these pointers on the Initial
            // pass and every pull here would open the settings room instead.
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    if (delta > PULL_THRESHOLD_PX) onExpandedChange(true)
                    if (delta < -PULL_THRESHOLD_PX) onExpandedChange(false)
                },
            )
            .clickable(
                onClickLabel = if (expanded) "Collapse the status message" else "Show the full status message",
                role = Role.Button,
            ) { onExpandedChange(!expanded) },
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BAND_HEIGHT.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            // Was null, so the one piece of status the app surfaces was invisible to TalkBack.
            contentDescription = when {
                isError -> "Warning"
                isWorking -> "Working"
                else -> "Up to date"
            },
            tint = tint,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (isWorking) rotationDegrees else 0f },
        )
        Text(
            text = message,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            // A scan error is a full sentence and was being cut off at one line with no way to
            // read the rest. Tapping the row gives it the room to finish.
            maxLines = if (expanded) MAX_EXPANDED_LINES else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isWorking && scanState is ScanState.Scanning) {
            val scanning = scanState
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

        if (expanded) {
            NotificationDetail(
                scanState = scanState,
                recognition = recognition,
                message = message,
                onRescan = onRescan,
            )
        }

        // The grab handle, at the bottom edge of whatever height the layer is at, because that is
        // the edge the finger is pulling from. Doubles as the only thing on screen saying the
        // strip can be pulled at all.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 28.dp, height = 3.dp)
                    .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

/**
 * The expanded half: the same status with its numbers spelled out.
 *
 * Deliberately not a second, richer notification -- it is the compact line with the detail the
 * compact line had to drop. A status surface that says *different* things depending on its size
 * makes the small one look like it was hiding something.
 */
@Composable
private fun NotificationDetail(
    scanState: ScanState,
    recognition: RecognitionProgress,
    message: String,
    onRescan: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The full sentence, no ellipsis. A scan error is the case that needs this -- it is a
        // whole sentence from the OS and the compact line can only ever show its first clause.
        Text(
            text = message,
            style = TextStyle(fontSize = 13.sp),
            color = Color.White.copy(alpha = 0.75f),
        )
        when (val scan = scanState) {
            is ScanState.Scanning -> DetailLine("Reading", "${scan.scanned} of ${scan.discovered}")
            is ScanState.Complete -> DetailLine("In the library", "${scan.total}")
            is ScanState.Error -> DetailLine("Scan", "stopped")
            ScanState.Idle -> Unit
        }
        if (recognition.total > 0) {
            DetailLine("Recognising", "${recognition.completed} of ${recognition.total}")
        }
        recognition.message?.let { DetailLine("Recognition", it) }
        if (onRescan != null) {
            Text(
                text = "Rescan now",
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = HyleTokens.Color.colorPaletteAccentViolet.toComposeColor(),
                modifier = Modifier
                    .clickable(onClick = onRescan)
                    .padding(top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp),
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = TextStyle(fontSize = 12.sp),
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.weight(0.58f),
        )
    }
}

/**
 * How tall the notification layer is right now, or zero when it has nothing to say.
 *
 * The host needs this to reserve the same strip from [dev.aarso.cellshell.SpatialShell]'s
 * top-edge gesture, so one function answers it for both. Getting these out of step means either
 * the notification cannot be pulled or the top room cannot be opened.
 */
internal fun notificationBandHeight(
    scanState: ScanState,
    recognition: RecognitionProgress,
    revealed: Boolean,
    expanded: Boolean,
): androidx.compose.ui.unit.Dp = when {
    !revealed -> 0.dp
    alertBannerMessage(scanState, false, recognition) == null &&
        alertBannerMessage(scanState, true, recognition) == null -> 0.dp
    expanded -> EXPANDED_HEIGHT.dp
    else -> BAND_HEIGHT.dp
}

private const val COMPLETION_HOLD_MILLIS = 1_800L

/**
 * How far the status line may grow when tapped open. Enough for a scan error to finish its
 * sentence; short of becoming a panel, which is what the rooms are for.
 */
private const val MAX_EXPANDED_LINES = 4

/**
 * How tall the layer stands when pulled open. Enough for the counts and an action, and short of
 * becoming a panel -- panels are what the four rooms are for.
 */
private const val EXPANDED_HEIGHT = 168

/**
 * Drag past this many pixels in one frame to change the layer's size.
 *
 * A frame threshold rather than an accumulated distance, deliberately: the strip is 44dp tall and
 * a user pulling it open moves fast, so what identifies the gesture is speed rather than travel.
 * A slow graze across the row while scrolling the grid never reaches it.
 */
private const val PULL_THRESHOLD_PX = 6f

/**
 * How much of the pane the notification layer takes when revealed, in dp.
 *
 * One number doing two jobs, and they must agree: it is the height the layer draws itself at,
 * and it is how far the pane's frame recedes to uncover it. Drifting them apart either clips
 * the sentence or opens a gap of nothing under it -- the same reason the shell keeps a single
 * BAND_DP for its parked card.
 */
private const val BAND_HEIGHT = 44
