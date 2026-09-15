package com.fotoxplorr.app.videoeditor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.video.VideoConversionWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-clip video editing: trim and speed — see [VideoEditRecipe]'s own doc for why text
 * overlay, music replacement and filters (the rest of this roadmap phase's named scope) are not
 * here yet.
 *
 * Shaped like [com.fotoxplorr.app.editor.EditorScreen] on purpose (top bar with Cancel/name/Save,
 * a preview above, controls below) rather than reusing [com.fotoxplorr.app.viewer.VideoPlayer]:
 * that composable carries a viewer's OWN chrome — key-moment markers, "Create clip", scrubbing
 * playback — none of which belongs in a dedicated trim/speed editor, and bringing it in would mean
 * fighting its layout rather than building this screen's own. The preview here is a single
 * representative frame at the current trim start, decoded the same lightweight way
 * [com.fotoxplorr.app.moments.MomentFrameExporter] already does for exactly one frame at a time.
 *
 * Saving always writes a NEW file — see [VideoConversionWriter]'s own doc for why a re-encode can
 * never be done in place.
 */
@Composable
fun VideoEditorScreen(
    asset: MediaAsset,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val writer = remember(context) { VideoConversionWriter(context) }
    val scope = rememberCoroutineScope()
    // At least one second: a zero-duration MediaStore entry (metadata this app never got to scan)
    // would otherwise collapse every slider below to a single, undraggable point.
    val durationMs = asset.durationMillis.coerceAtLeast(1_000L)

    var trimStartMs by remember(asset.id) { mutableStateOf(0L) }
    var trimEndMs by remember(asset.id) { mutableStateOf(durationMs) }
    var speedFactor by remember(asset.id) { mutableStateOf(1f) }
    var saving by remember { mutableStateOf(false) }
    var previewBitmap by remember(asset.id) { mutableStateOf<Bitmap?>(null) }

    val recipe = VideoEditRecipe(
        trimStartUs = trimStartMs * 1_000L,
        // Full-duration end is "no trim end" (null), the same "leave it alone" convention
        // com.fotoxplorr.app.metadata.MetadataEdit already uses for an untouched field — not a
        // trimEndUs equal to the source's own last microsecond, which VideoTranscoder would still
        // have to seek to and confirm rather than skip entirely.
        trimEndUs = if (trimEndMs >= durationMs) null else trimEndMs * 1_000L,
        speedFactor = speedFactor,
    )

    // Re-decoded every time the trim start moves, so the preview always shows the frame the
    // exported clip will actually begin on rather than the source's own first frame.
    LaunchedEffect(asset.id, trimStartMs) {
        previewBitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, asset.contentUri)
                retriever.getFrameAtTime(trimStartMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (error: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    Column(modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onClose) { Text("Cancel", color = Color.White) }
            Text(
                asset.displayName,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(
                // A no-op edit must not write a needless second copy of the video.
                enabled = !recipe.isIdentity && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val result = writer.exportEdit(asset, recipe)
                        saving = false
                        onSaved(
                            result.fold(
                                onSuccess = { "Saved an edited copy." },
                                onFailure = { it.message ?: "Could not export this edit" },
                            ),
                        )
                    }
                },
            ) {
                Text(
                    if (saving) "Saving…" else "Save",
                    color = if (recipe.isIdentity) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shown = previewBitmap
            if (shown == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("TRIM", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            TimeSlider(
                label = "Start",
                valueMs = trimStartMs,
                // Kept at least a second before the end handle -- a trim window shorter than that
                // is not a meaningfully different clip, and a zero-length one is not a clip at all.
                range = 0f..(trimEndMs - MIN_TRIM_SPAN_MS).coerceAtLeast(0L).toFloat(),
                onChange = { trimStartMs = it.toLong() },
            )
            TimeSlider(
                label = "End",
                valueMs = trimEndMs,
                range = (trimStartMs + MIN_TRIM_SPAN_MS).coerceAtMost(durationMs).toFloat()..durationMs.toFloat(),
                onChange = { trimEndMs = it.toLong() },
            )

            Text("SPEED", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEED_PRESETS.forEach { preset ->
                    val selected = speedFactor == preset
                    Text(
                        speedLabel(preset),
                        color = if (selected) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                                RoundedCornerShape(50),
                            )
                            .clickable { speedFactor = preset }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            // Honest about the one real trade-off in this first pass -- see
            // speedAdjustedSampleRate's own doc for exactly why.
            if (speedFactor != 1f) {
                Text(
                    "Changes pitch along with speed.",
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TimeSlider(label: String, valueMs: Long, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(formatMs(valueMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = valueMs.toFloat().coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** A minimum trim window, in milliseconds — see [TimeSlider]'s own call sites for why. */
private const val MIN_TRIM_SPAN_MS = 1_000L

private val SPEED_PRESETS = listOf(0.5f, 1f, 1.5f, 2f)

/** "0.5x", "1x", "1.5x", "2x" — a whole-number factor drops its trailing ".0" rather than
 *  reading as a decimal someone might mistake for a range rather than one of four fixed steps. */
private fun speedLabel(factor: Float): String {
    val number = if (factor == factor.toInt().toFloat()) factor.toInt().toString() else factor.toString()
    return "${number}x"
}
