package com.fotoxplorr.app.lift

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
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
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.viewer.fittedImageRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tappable "lift a subject" layer over a photo, entered from a long press.
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
    if (!active || asset.isVideo) return

    val context = LocalContext.current
    var phase by remember(asset.id) { mutableStateOf<LiftPhase>(LiftPhase.Picking) }
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
    LaunchedEffect(asset.id) {
        source = withContext(Dispatchers.IO) { decodeBoundedForLift(context, asset) }
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
                if (phase !is LiftPhase.Picking) return@pointerInput
                val bitmap = source ?: return@pointerInput
                containerSize = Size(size.width.toFloat(), size.height.toFloat())
                detectTapGestures(
                    onTap = { point ->
                        val rect = fittedImageRect(containerSize, asset.width, asset.height)
                        if (!rect.contains(point)) {
                            // Tapped the letterbox rather than the photo -- read as "never mind",
                            // the same way a tap on empty space dismisses a sheet everywhere else
                            // in this app.
                            onDismiss()
                            return@detectTapGestures
                        }
                        val fx = ((point.x - rect.left) / rect.width).coerceIn(0f, 1f)
                        val fy = ((point.y - rect.top) / rect.height).coerceIn(0f, 1f)
                        val seedX = (fx * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                        val seedY = (fy * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)

                        phase = LiftPhase.Processing
                        scope.launch {
                            val cutout = withContext(Dispatchers.Default) {
                                runCatching { LiftRenderer.cutOut(bitmap, seedX, seedY) }.getOrNull()
                            }
                            phase = if (cutout != null) {
                                LiftPhase.Ready(cutout.bitmap)
                            } else {
                                LiftPhase.Failed
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val current = phase) {
            is LiftPhase.Picking -> PickingChrome(onCancel = onDismiss)
            is LiftPhase.Processing -> ProcessingChrome()
            is LiftPhase.Failed -> FailedChrome(
                onTryAgain = { phase = LiftPhase.Picking },
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
    data object Picking : LiftPhase
    data object Processing : LiftPhase
    data class Ready(val bitmap: Bitmap) : LiftPhase
    data object Failed : LiftPhase
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
private fun BoxScope.FailedChrome(onTryAgain: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {}
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Couldn't find a clear subject there",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Try again", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onTryAgain))
            Text("Cancel", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickable(onClick = onCancel))
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
            .padding(top = 24.dp)
            .navigationBarsPadding()
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

/**
 * Decode [asset] at a bound suitable for segmentation: big enough that a cut-out looks sharp at
 * the sticker sizes people actually use, small enough that flood fill -- an O(pixels) pass
 * regardless of the subject's size -- stays interactive on a background thread.
 */
private fun decodeBoundedForLift(context: android.content.Context, asset: MediaAsset): Bitmap? = runCatching {
    context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
        val bytes = stream.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / sample > LIFT_DECODE_EDGE_PX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrNull()

/** See [decodeBoundedForLift]. Deliberately smaller than the editor's preview budget. */
private const val LIFT_DECODE_EDGE_PX = 1024

private const val STATUS_MESSAGE_MS = 2600L
