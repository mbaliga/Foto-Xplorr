package com.fotoxplorr.app.videoeditor

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.video.CropAspect
import com.fotoxplorr.app.video.VideoCodec
import com.fotoxplorr.app.video.VideoExporter
import com.fotoxplorr.app.video.VideoQuality
import com.fotoxplorr.app.video.deviceSupportsVideoEncoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The video editor: trim, rotate, flip, speed, mute, a centred aspect crop and brightness/
 * contrast/saturation filters, previewed with the same engine that exports (ExoPlayer previews,
 * [VideoExporter]/Transformer exports). Like the photo editor it is non-destructive by
 * construction: Save always writes a new file beside the original, and a no-op plan cannot be
 * saved at all. Ported and extended from the Media3-based prototype built on
 * `origin/claude/fotoz-continue` (`videoedit/VideoEditorScreen.kt`) -- see
 * `docs/adr/ADR-008-video-transcode-pipeline.md` (revision 2).
 *
 * Preview honesty: trim, speed and mute are LIVE (the player is reconfigured); rotation and flip
 * are shown by transforming the surface; the aspect crop is previewed as a frame overlay rather
 * than pre-cropped pixels; brightness/contrast/saturation are NOT live-previewed at all (Media3's
 * effects pipeline only runs at export time through a plain [PlayerView], not through this
 * preview) -- pretending any of these three showed the real pixel result would be a lie waiting
 * for an edge case, so the filter row is labelled accordingly rather than silently wrong.
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

    // The asset's stored duration can be 0 for files MediaStore never probed; the player corrects
    // it once prepared. Seed with a floor so the plan's invariants hold meanwhile.
    var durationMs by remember(asset.id) { mutableStateOf(asset.durationMillis.coerceAtLeast(1L)) }
    var plan by rememberSaveable(asset.id, stateSaver = VideoEditPlan.Saver) {
        mutableStateOf(VideoEditPlan(sourceDurationMs = durationMs))
    }
    var codec by rememberSaveable(asset.id) { mutableStateOf(VideoCodec.H264) }
    var quality by rememberSaveable(asset.id) { mutableStateOf(VideoQuality.ORIGINAL) }
    var exportState by remember(asset.id) { mutableStateOf<EditorExportState>(EditorExportState.Idle) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var confirmDiscardExport by remember { mutableStateOf(false) }
    val exporting = exportState is EditorExportState.Running

    val hevcAvailable = remember { deviceSupportsVideoEncoder(VideoCodec.HEVC) }
    val exporter = remember(context) { VideoExporter(context) }
    val player = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(asset.contentUri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // The player is the authority on duration once it knows it; the plan follows, keeping any
    // trim the user already made where it still fits. Bounded, not `while (true)`: the known
    // hand-written pipeline's unbounded duration poll (see the ADR) never exited for an
    // unplayable source -- this one gives up after a few seconds and leaves the seeded floor in
    // place rather than spinning forever.
    LaunchedEffect(player) {
        var attempts = 0
        while (attempts < DURATION_POLL_MAX_ATTEMPTS) {
            val known = player.duration
            if (known > 0) {
                if (known != durationMs) {
                    durationMs = known
                    plan = plan.copy(
                        sourceDurationMs = known,
                        trimStartMs = plan.trimStartMs.coerceIn(0, known - 1),
                        trimEndMs = plan.trimEndMs.coerceIn(plan.trimStartMs + 1, known)
                            .let { if (plan.trimEndMs >= plan.sourceDurationMs) known else it },
                    )
                }
                break
            }
            attempts++
            delay(DURATION_POLL_INTERVAL_MS)
        }
    }

    // Live preview of trim/speed/mute: reconfigure the SAME player the screen shows. Debounced --
    // this effect restarts on every handle movement, and rebuilding a clipped media item per drag
    // frame would stutter the very preview the drag is aimed at.
    LaunchedEffect(plan.trimStartMs, plan.trimEndMs) {
        delay(TRIM_PREVIEW_DEBOUNCE_MS)
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
    LaunchedEffect(plan.speed) {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(plan.speed)
    }
    LaunchedEffect(plan.muted) { player.volume = if (plan.muted) 0f else 1f }
    LaunchedEffect(exporting) { player.playWhenReady = !exporting }

    fun startExport() {
        exportJob = scope.launch {
            exportState = EditorExportState.Running(percent = null)
            val options = plan.toExportOptions().copy(codec = codec, quality = quality)
            val result = exporter.export(asset, options, onProgress = { fraction ->
                exportState = EditorExportState.Running(percent = (fraction * 100).toInt())
            })
            result.fold(
                onSuccess = {
                    exportState = EditorExportState.Idle
                    onSaved("Saved an edited copy beside the original")
                },
                onFailure = { error ->
                    exportState = EditorExportState.Failed(error.message ?: "The export failed.")
                },
            )
        }
    }

    fun requestClose() {
        if (exporting) confirmDiscardExport = true else onClose()
    }

    BackHandler(onBack = ::requestClose)

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
            TextButton(onClick = ::requestClose) { Text("Cancel", color = Color.White) }
            Text(
                asset.displayName,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(
                // A no-op edit must not write a second copy of the video -- same rule as photos.
                enabled = !plan.isIdentity && !exporting,
                onClick = ::startExport,
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
                        // viewport; the exported file has the real geometry. Guarded against a
                        // zero-height layout pass (first frame, or a collapsed preview) where
                        // `size.width / size.height` would divide by zero and NaN every
                        // subsequent transform on this layer.
                        if (plan.swapsDimensions && size.height > 0f) {
                            val fit = size.width / size.height
                            if (fit > 0f) {
                                scaleX *= 1f / fit
                                scaleY = 1f / fit
                            }
                        }
                    },
            )
            plan.cropAspect?.let { aspect -> CropFrameOverlay(aspect, plan.swapsDimensions) }
            when (val state = exportState) {
                is EditorExportState.Running -> ExportOverlay(
                    percent = state.percent,
                    onCancel = {
                        exportJob?.cancel()
                        exportJob = null
                        exportState = EditorExportState.Idle
                    },
                )
                is EditorExportState.Failed -> ExportFailed(state.message) {
                    exportState = EditorExportState.Idle
                }
                EditorExportState.Idle -> Unit
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
                        // Dragging the END handle specifically (start unchanged) previews the
                        // FRAME AT the new end point rather than wherever playback last was --
                        // scrubbing the out-point is exactly when seeing that exact frame matters
                        // most, e.g. cutting a clip a hair before someone blinks.
                        if (end != plan.trimEndMs && start == plan.trimStartMs) {
                            player.playWhenReady = false
                            player.seekTo((end - start - 1).coerceAtLeast(0))
                        }
                        plan = plan.copy(trimStartMs = start, trimEndMs = end)
                    }
                },
                valueRange = 0f..durationMs.toFloat(),
                enabled = !exporting,
            )
            Text(
                "${formatMs(plan.trimStartMs)} → ${formatMs(plan.trimEndMs)}" +
                    "  ·  keeps ${formatMs(plan.trimmedDurationMs)}" +
                    if (plan.speed != 1f) {
                        ", exports ${formatMs(plan.exportedDurationMs)} at ${trimTrailingZero(plan.speed)}×"
                    } else {
                        ""
                    },
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

        FilterControls(plan = plan, enabled = !exporting, onPlanChange = { plan = it })

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Format", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            FilterChip(
                selected = codec == VideoCodec.H264,
                enabled = !exporting,
                onClick = { codec = VideoCodec.H264 },
                label = { Text("H.264") },
            )
            FilterChip(
                selected = codec == VideoCodec.HEVC,
                enabled = !exporting && hevcAvailable,
                onClick = { codec = VideoCodec.HEVC },
                label = { Text("HEVC") },
            )
            VideoQuality.entries.forEach { q ->
                FilterChip(
                    selected = quality == q,
                    enabled = !exporting,
                    onClick = { quality = q },
                    label = { Text(qualityLabel(q)) },
                )
            }
        }
    }

    if (confirmDiscardExport) {
        AlertDialog(
            onDismissRequest = { confirmDiscardExport = false },
            title = { Text("Stop exporting?") },
            text = { Text("Leaving now cancels the export in progress.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscardExport = false
                    exportJob?.cancel()
                    onClose()
                }) { Text("Stop and leave") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscardExport = false }) { Text("Keep exporting") } },
        )
    }
}

/** Brightness/contrast/saturation sliders plus a handful of named presets that set all three at
 *  once -- see the screen's own doc for why these are export-only, not live-previewed. */
@Composable
private fun FilterControls(plan: VideoEditPlan, enabled: Boolean, onPlanChange: (VideoEditPlan) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Filters (applied on export, not previewed live)",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPreset.entries.forEach { preset ->
                FilterChip(
                    selected = preset.matches(plan),
                    enabled = enabled,
                    onClick = { onPlanChange(preset.applyTo(plan)) },
                    label = { Text(preset.label) },
                )
            }
        }
        LabeledSlider("Brightness", plan.brightness, enabled) { onPlanChange(plan.copy(brightness = it)) }
        LabeledSlider("Contrast", plan.contrast, enabled) { onPlanChange(plan.copy(contrast = it)) }
        LabeledSlider("Saturation", plan.saturation, enabled) { onPlanChange(plan.copy(saturation = it)) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onChange, valueRange = -1f..1f, enabled = enabled)
    }
}

/** Named filter combinations -- a fixed, small set is easier to get right than a colour-grading
 *  tool, and is what the task calls for as the v1 shape. */
private enum class FilterPreset(val label: String, val brightness: Float, val contrast: Float, val saturation: Float) {
    NONE("None", 0f, 0f, 0f),
    VIVID("Vivid", 0.05f, 0.2f, 0.35f),
    MUTED("Muted", 0f, -0.1f, -0.3f),
    BLACK_AND_WHITE("B&W", 0f, 0.1f, -1f),
    ;

    fun matches(plan: VideoEditPlan): Boolean =
        plan.brightness == brightness && plan.contrast == contrast && plan.saturation == saturation

    fun applyTo(plan: VideoEditPlan): VideoEditPlan =
        plan.copy(brightness = brightness, contrast = contrast, saturation = saturation)
}

private fun qualityLabel(quality: VideoQuality): String = when (quality) {
    VideoQuality.ORIGINAL -> "Original"
    VideoQuality.P1080 -> "1080p"
    VideoQuality.P720 -> "720p"
}

/** The plain frame-overlay crop preview PR9's own doc names explicitly: the exported geometry
 *  comes from [VideoEditPlan.toExportOptions], not from these preview pixels, so this draws a
 *  border rather than pretending to crop. */
@Composable
private fun CropFrameOverlay(aspect: CropAspect, swapsDimensions: Boolean) {
    val displayedAspect = if (swapsDimensions) 1f / aspect.widthOverHeight else aspect.widthOverHeight
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (displayedAspect >= 1f) {
                        Modifier.size(width = 260.dp * displayedAspect, height = 260.dp)
                    } else {
                        Modifier.size(width = 260.dp, height = 260.dp / displayedAspect)
                    },
                )
                .border(1.dp, Color.White.copy(alpha = 0.8f)),
        )
    }
}

@Composable
private fun ExportOverlay(percent: Int?, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
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
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
        TextButton(onClick = onDismiss) { Text("OK", color = Color.White) }
    }
}

private sealed interface EditorExportState {
    data object Idle : EditorExportState
    data class Running(val percent: Int?) : EditorExportState
    data class Failed(val message: String) : EditorExportState
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

private const val DURATION_POLL_MAX_ATTEMPTS = 50
private const val DURATION_POLL_INTERVAL_MS = 100L
private const val TRIM_PREVIEW_DEBOUNCE_MS = 150L
