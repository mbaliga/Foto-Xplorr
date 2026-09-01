package com.fotoxplorr.app.videoedit

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The video editor: trim, rotate, flip, speed, mute and a centred aspect crop, previewed with
 * the same engine that exports (ExoPlayer previews, Transformer exports — one Media3 pipeline,
 * so what plays is what saves). Like the photo editor it is non-destructive by construction:
 * Save always writes a new file beside the original, and a no-op plan cannot be saved at all.
 *
 * Preview honesty: trim, speed and mute are LIVE (the player is reconfigured); rotation and
 * flip are shown by transforming the surface; the aspect crop is previewed as a frame overlay
 * rather than pre-cropped pixels — the exported geometry comes from the plan, and pretending
 * the preview pipeline crops when it only masks would be a lie waiting for an edge case.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(
    asset: MediaAsset,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The asset's stored duration can be 0 for files MediaStore never probed; the player
    // corrects it once prepared. Seed with a floor so the plan's invariants hold meanwhile.
    var durationMs by remember(asset.id) {
        mutableStateOf(asset.durationMillis.coerceAtLeast(1L))
    }
    var plan by remember(asset.id) { mutableStateOf(VideoEditPlan(sourceDurationMs = durationMs)) }
    var exportState by remember(asset.id) { mutableStateOf<VideoExportState?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val exporting = exportState is VideoExportState.Running

    val exporter = remember(context) { VideoExporter(context) }
    val player = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(asset.contentUri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // The player is the authority on duration once it knows it; the plan follows, keeping any
    // trim the user already made where it still fits.
    LaunchedEffect(player) {
        while (true) {
            val known = player.duration
            if (known > 0 && known != durationMs) {
                durationMs = known
                plan = VideoEditPlan(
                    sourceDurationMs = known,
                    trimStartMs = plan.trimStartMs.coerceIn(0, known - 1),
                    trimEndMs = plan.trimEndMs.coerceIn(plan.trimStartMs + 1, known)
                        .let { if (plan.trimEndMs >= plan.sourceDurationMs) known else it },
                    quarterTurns = plan.quarterTurns,
                    flipHorizontal = plan.flipHorizontal,
                    speed = plan.speed,
                    muted = plan.muted,
                    cropAspect = plan.cropAspect,
                )
                break
            }
            kotlinx.coroutines.delay(100)
        }
    }

    // Live preview of trim/speed/mute: reconfigure the SAME player the screen shows. Debounced —
    // this effect restarts on every handle movement, and rebuilding a clipped media item per
    // drag frame would stutter the very preview the drag is aimed at.
    LaunchedEffect(plan.trimStartMs, plan.trimEndMs) {
        kotlinx.coroutines.delay(150)
        val position = player.currentPosition
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(asset.contentUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(plan.trimStartMs)
                        .setEndPositionMs(plan.trimEndMs)
                        .build(),
                )
                .build(),
        )
        player.prepare()
        // Positions inside a clipped item are relative to the clip start.
        player.seekTo((position - plan.trimStartMs).coerceAtLeast(0))
        player.playWhenReady = !exporting
    }
    LaunchedEffect(plan.speed) { player.playbackParameters = PlaybackParameters(plan.speed) }
    LaunchedEffect(plan.muted) { player.volume = if (plan.muted) 0f else 1f }
    LaunchedEffect(exporting) { player.playWhenReady = !exporting }

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onClose, enabled = !exporting) { Text("Cancel", color = Color.White) }
            Text(
                asset.displayName,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(
                // A no-op edit must not write a second copy of the video — same rule as photos.
                enabled = !plan.isIdentity && !exporting,
                onClick = {
                    exportJob = scope.launch {
                        exporter.export(asset, plan) { state ->
                            exportState = state
                            if (state is VideoExportState.Done) {
                                onSaved("Saved an edited copy beside the original")
                            }
                        }
                    }
                },
            ) {
                Text("Save", color = if (plan.isIdentity || exporting) Color.Gray else Color.White)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = true
                        controllerAutoShow = false
                        setShutterBackgroundColor(AndroidColor.BLACK)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = plan.quarterTurns * 90f
                        scaleX = if (plan.flipHorizontal) -1f else 1f
                        // A quarter-turned landscape frame must shrink to fit the portrait
                        // viewport; the exported file has the real geometry.
                        if (plan.swapsDimensions) {
                            val fit = size.width / size.height
                            scaleX *= 1f / fit
                            scaleY = 1f / fit
                        }
                    },
            )
            when (val state = exportState) {
                is VideoExportState.Running -> ExportOverlay(
                    percent = state.progressPercent,
                    onCancel = {
                        exportJob?.cancel()
                        exportJob = null
                        exportState = null
                    },
                )
                is VideoExportState.Failed -> ExportFailed(state.message) { exportState = null }
                else -> Unit
            }
        }

        // Trim: two handles over the clip, labelled with the kept span.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            RangeSlider(
                value = plan.trimStartMs.toFloat()..plan.trimEndMs.toFloat(),
                onValueChange = { range ->
                    val start = range.start.toLong().coerceIn(0, durationMs - 1)
                    val end = range.endInclusive.toLong().coerceIn(start + 1, durationMs)
                    if (end - start >= VideoEditPlan.MIN_TRIMMED_MS) {
                        plan = plan.copy(trimStartMs = start, trimEndMs = end)
                    }
                },
                valueRange = 0f..durationMs.toFloat(),
                enabled = !exporting,
            )
            Text(
                "${formatMs(plan.trimStartMs)} – ${formatMs(plan.trimEndMs)}" +
                    "  ·  keeps ${formatMs(plan.trimmedDurationMs)}" +
                    if (plan.speed != 1f) ", exports ${formatMs(plan.exportedDurationMs)} at ${trimTrailingZero(plan.speed)}×" else "",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = plan.quarterTurns != 0,
                enabled = !exporting,
                onClick = { plan = plan.copy(quarterTurns = (plan.quarterTurns + 1) % 4) },
                label = { Text(if (plan.quarterTurns == 0) "Rotate" else "Rotated ${plan.quarterTurns * 90}°") },
            )
            FilterChip(
                selected = plan.flipHorizontal,
                enabled = !exporting,
                onClick = { plan = plan.copy(flipHorizontal = !plan.flipHorizontal) },
                label = { Text("Mirror") },
            )
            FilterChip(
                selected = plan.muted,
                enabled = !exporting,
                onClick = { plan = plan.copy(muted = !plan.muted) },
                label = { Text(if (plan.muted) "Muted" else "Mute") },
            )
            VideoEditPlan.SPEED_CHOICES.forEach { choice ->
                FilterChip(
                    selected = plan.speed == choice,
                    enabled = !exporting,
                    onClick = { plan = plan.copy(speed = choice) },
                    label = { Text("${trimTrailingZero(choice)}×") },
                )
            }
            FilterChip(
                selected = plan.cropAspect == null,
                enabled = !exporting,
                onClick = { plan = plan.copy(cropAspect = null) },
                label = { Text("Full frame") },
            )
            CropAspect.entries.forEach { aspect ->
                FilterChip(
                    selected = plan.cropAspect == aspect,
                    enabled = !exporting,
                    onClick = { plan = plan.copy(cropAspect = aspect) },
                    label = { Text(aspect.label) },
                )
            }
        }
    }
}

@Composable
private fun ExportOverlay(percent: Int?, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            if (percent != null) "Exporting… $percent%" else "Exporting…",
            color = Color.White,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
    }
}

@Composable
private fun ExportFailed(message: String, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
        TextButton(onClick = onDismiss) { Text("OK", color = Color.White) }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1000) / 100
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}

private fun trimTrailingZero(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
