package com.fotoxplorr.app.lift

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The tappable "lift a subject" layer over a photo, entered from a long press or the actions
 * room's "Lift subject" row.
 *
 * ## Gesture precedence -- read this before changing [com.fotoxplorr.app.viewer.ViewerScreen]
 * This composable renders NOTHING at all -- no `Box`, no `pointerInput`, nothing hit-testable --
 * whenever [active] is false. That single `if (!active) return` at the top is the entire strategy
 * for not breaking the viewer's existing gestures, and it is deliberately the same strategy
 * [com.fotoxplorr.app.viewer.LiveTextOverlay] already uses ("empty renders nothing at all, so a
 * photo with no text carries no invisible tap targets"). The alternative -- keeping this
 * composed all the time and toggling some internal "am I armed" flag before deciding whether to
 * consume a tap -- would still occupy a `pointerInput` node in the tree permanently, and Compose's
 * gesture dispatch has no reliable way for ViewerScreen's own tap/pinch/page detectors to tell
 * "an armed-but-currently-inert sibling node" apart from "an armed-and-about-to-consume one" ahead
 * of time. Not being composed at all removes the ambiguity by construction instead of resolving it
 * by ordering, which is exactly the class of bug this file's header comment on the THREE-detector
 * rewrite already paid for once.
 *
 * When this IS composed (a long press armed it), its own `pointerInput` sits as a CHILD of
 * ViewerScreen's photo `Box`, at the same tree depth as [com.fotoxplorr.app.viewer.LiveTextOverlay]
 * -- both children are hit-tested before their ancestors during Compose's pointer dispatch, so a
 * tap that lands here is consumed here and never reaches the outer chrome-toggle /
 * double-tap-zoom detector. That is the intended behaviour while a lift is in progress: nothing
 * else on the photo should react to a tap meant to choose a subject.
 */
@Composable
fun LiftOverlay(
    asset: MediaAsset,
    active: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Declared before the early return and keyed on `active`, so Back is intercepted exactly while
    // a lift is on screen and a later re-entry starts from a clean slate. Without this the system
    // Back gesture closed the whole viewer (or backed out of a room) underneath a half-finished
    // lift, which reads as "the app threw my selection away".
    var phase by remember(asset.id, active) { mutableStateOf<LiftPhase>(LiftPhase.Loading) }
    BackHandler(enabled = active && !asset.isVideo) {
        if (phase is LiftPhase.Ready) phase = LiftPhase.Picking else onDismiss()
    }

    if (!active || asset.isVideo) return

    val context = LocalContext.current
    var source by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Decoded once per photo, lazily -- this LaunchedEffect only exists in the composition at all
    // once `active` is true, so a photo nobody ever long-presses never pays for this decode.
    // Bounded well below the editor's own export size: segmentation is an O(pixels) flood fill
    // over the WHOLE image regardless of subject size, not a windowed operation, so this has to
    // stay small enough to run on a background thread without the user noticing, at some cost to
    // how finely a hair-fine edge can be resolved -- see the file KDoc's honest-limits note.
    //
    // Every outcome ends in a state the person can see. The decode used to run bare: a null
    // result left the "Tap the subject" banner up over a tap handler that was never installed
    // (every tap silently ignored, forever), and an exception from the EXIF read -- which sits
    // outside decodeUpright's own runCatching -- escaped the LaunchedEffect and took the
    // composition down with it.
    LaunchedEffect(asset.id) {
        val decoded = catchingResult {
            decodeUpright(context, asset.contentUri, LIFT_DECODE_LIMITS)?.bitmap
                ?: throw IOException("the photo could not be decoded")
        }
        decoded.fold(
            onSuccess = { bitmap ->
                source = bitmap
                phase = LiftPhase.Picking
            },
            onFailure = { phase = LiftPhase.Failed(classifyLiftFailure(it), canRetry = false) },
        )
    }

    // A transient status line for save/share feedback, self-contained rather than routed through
    // the host Activity's own message channel: ViewerScreen has no `onMessage` callback today, and
    // adding one would mean widening its public API (and FotoXplorrActivity's call site, which
    // this change does not own) for a two-line toast. Auto-clears itself; nothing here needs to be
    // remembered past the moment the user reads it.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(STATUS_MESSAGE_MS)
            statusMessage = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(asset.id, source, phase) {
                // Outside the picking step the overlay still owns the touch: swallowing taps here
                // keeps a tap on the progress scrim or an error from falling through to the photo
                // underneath and toggling the viewer's chrome.
                val bitmap = source
                if (phase !is LiftPhase.Picking || bitmap == null) {
                    detectTapGestures { }
                    return@pointerInput
                }
                containerSize = Size(size.width.toFloat(), size.height.toFloat())
                detectTapGestures(
                    onTap = { point ->
                        // Mapped through the DECODED bitmap's own size, not asset.width/height --
                        // see imagePointToBitmapPixel for the failure that avoids.
                        val pixel = imagePointToBitmapPixel(point, containerSize, bitmap.width, bitmap.height)
                        if (pixel == null) {
                            // Tapped the letterbox rather than the photo -- read as "never mind",
                            // the same way a tap on empty space dismisses a sheet everywhere else
                            // in this app.
                            onDismiss()
                            return@detectTapGestures
                        }

                        phase = LiftPhase.Processing
                        scope.launch {
                            val outcome = withContext(Dispatchers.Default) {
                                catchingResult { LiftRenderer.cutOut(bitmap, pixel.x, pixel.y) }
                            }
                            phase = outcome.fold(
                                onSuccess = { cutout ->
                                    if (cutout != null) LiftPhase.Ready(cutout.bitmap) else LiftPhase.Failed(LiftFailure.NoSubject, canRetry = true)
                                },
                                onFailure = { LiftPhase.Failed(classifyLiftFailure(it), canRetry = true) },
                            )
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val current = phase) {
            is LiftPhase.Loading -> LoadingChrome(onCancel = onDismiss)
            is LiftPhase.Picking -> PickingChrome(onCancel = onDismiss)
            is LiftPhase.Processing -> ProcessingChrome()
            is LiftPhase.Failed -> FailedChrome(
                message = current.failure.userMessage(),
                onTryAgain = if (current.canRetry) ({ phase = LiftPhase.Picking }) else null,
                onCancel = onDismiss,
            )
            is LiftPhase.Ready -> ReadyChrome(
                cutout = current.bitmap,
                statusMessage = statusMessage,
                onTryAgain = { phase = LiftPhase.Picking },
                onDismiss = onDismiss,
                onSave = {
                    scope.launch {
                        val exporter = StickerExporter(context)
                        val result = exporter.saveToGallery(current.bitmap, asset.displayName)
                        statusMessage = result.fold(
                            onSuccess = { "Saved to Pictures" },
                            onFailure = { it.message ?: "Could not save the sticker" },
                        )
                    }
                },
                onShare = {
                    scope.launch {
                        val exporter = StickerExporter(context)
                        val result = exporter.prepareForShare(current.bitmap)
                        result.fold(
                            onSuccess = { uri ->
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(exporter.shareIntent(uri), "Send sticker"),
                                    )
                                }.onFailure { statusMessage = "No compatible app was found" }
                            },
                            onFailure = { statusMessage = it.message ?: "Could not prepare the sticker" },
                        )
                    }
                },
            )
        }
    }
}

/** Where a lift attempt currently is. Private: this is UI-flow state, not something worth exposing. */
private sealed interface LiftPhase {
    /** The photo is still being decoded; no tap handler exists yet, so say so. */
    data object Loading : LiftPhase
    data object Picking : LiftPhase
    data object Processing : LiftPhase
    data class Ready(val bitmap: Bitmap) : LiftPhase

    /** [canRetry] is false when the photo itself is the problem, so trying another tap cannot help. */
    data class Failed(val failure: LiftFailure, val canRetry: Boolean) : LiftPhase
}

@Composable
private fun BoxScope.LoadingChrome(onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {}
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Color.White)
        Text("Opening photo…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
    InstructionBanner(
        text = "Getting ready to lift",
        modifier = Modifier.align(Alignment.TopCenter),
        onCancel = onCancel,
    )
}

@Composable
private fun BoxScope.PickingChrome(onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))) {}
    InstructionBanner(
        text = "Tap the subject to lift it",
        modifier = Modifier.align(Alignment.TopCenter),
        onCancel = onCancel,
    )
}

@Composable
private fun BoxScope.ProcessingChrome() {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {}
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Color.White)
        Text("Lifting…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BoxScope.FailedChrome(message: String, onTryAgain: (() -> Unit)?, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {}
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onTryAgain != null) {
                Text("Try again", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onTryAgain))
            }
            Text("Close", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickable(onClick = onCancel))
        }
    }
}

@Composable
private fun BoxScope.ReadyChrome(
    cutout: Bitmap,
    statusMessage: String?,
    onTryAgain: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {}
    Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A flat mid-grey card behind the cutout, not a photo backdrop: the whole point of this
        // preview is to show the alpha channel worked, and a mid-tone is the one background that
        // reads as "not part of the subject" against almost any photo's colours.
        Box(
            modifier = Modifier
                .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                .aspectRatio(cutout.width.toFloat() / cutout.height.toFloat().coerceAtLeast(1f))
                // Fixed on purpose (semantic): a deliberate mid-grey so the cut-out's alpha shows,
                // not an accent.
                .background(Color(0xFF3A3A3A), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = cutout.asImageBitmap(),
                contentDescription = "Lifted sticker",
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit,
            )
        }
        if (statusMessage != null) {
            Text(statusMessage, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ActionLabel("Save", onSave)
            ActionLabel("Share", onShare)
            ActionLabel("Try again", onTryAgain)
            ActionLabel("Close", onDismiss, emphasised = false)
        }
    }
}

@Composable
private fun ActionLabel(label: String, onClick: () -> Unit, emphasised: Boolean = true) {
    Text(
        label,
        color = if (emphasised) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun InstructionBanner(text: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            // The TOP banner clears the status bar, not the navigation bar: it used the latter, so
            // on a gesture-nav phone it sat under the clock and the Cancel button was half hidden.
            .statusBarsPadding()
            .padding(top = 24.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Text(
            "Cancel",
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                .clickable(onClick = onCancel)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** The bound for [decodeUpright] when decoding for segmentation: big enough that a cut-out looks
 * sharp at the sticker sizes people actually use, small enough that flood fill -- an O(pixels)
 * pass regardless of the subject's size -- stays interactive on a background thread. Deliberately
 * smaller than the editor's preview budget. */
private val LIFT_DECODE_LIMITS = DecodeLimits(maxLongEdge = LIFT_DECODE_EDGE_PX, maxPixels = LIFT_DECODE_EDGE_PX.toLong() * LIFT_DECODE_EDGE_PX)
private const val LIFT_DECODE_EDGE_PX = 1024

private const val STATUS_MESSAGE_MS = 2600L
