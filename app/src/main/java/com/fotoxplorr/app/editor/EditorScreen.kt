package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.fotoxplorr.app.media.MediaAsset
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which set of controls the bottom bar is showing. */
private enum class EditTool(val label: String) {
    ADJUST("Adjust"),
    CROP("Crop"),
    ROTATE("Rotate"),
}

/**
 * The in-app photo editor.
 *
 * Built rather than pulled in — see `docs/adr/ADR-007-photo-editing.md`. The short version: the
 * obvious library (uCrop) declares an OkHttp dependency and would hard-fail the offline flavour's
 * classpath gate, and the strongest open-source galleries in this space are GPL-3.0, which this
 * app cannot take without becoming GPL itself.
 *
 * Everything here is non-destructive: the screen holds an [EditRecipe], previews it at a bounded
 * size, and writes a **new file** on save. The original is never opened for writing.
 */
@Composable
fun EditorScreen(
    asset: MediaAsset,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var recipe by remember(asset.id) { mutableStateOf(EditRecipe()) }
    var tool by remember { mutableStateOf(EditTool.ADJUST) }
    var source by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var preview by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val writer = remember(context) { EditedCopyWriter(context) }
    val scope = rememberCoroutineScope()

    // Decode once, at a bounded size. Full resolution would mean a colour matrix over tens of
    // megapixels on every slider frame.
    LaunchedEffect(asset.id) {
        source = withContext(Dispatchers.IO) {
            runCatching {
                val edge = EditRenderer.previewEdge(PREVIEW_VIEWPORT_EDGE_PX)
                context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                    var sample = 1
                    while (longest / sample > edge) sample *= 2
                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            }.getOrNull()
        }
    }

    // Re-render off the main thread: the matrix runs over every previewed pixel, and doing that
    // in composition would drop frames on the very drag that requested it.
    LaunchedEffect(source, recipe) {
        val base = source ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            runCatching { EditRenderer.render(base, recipe) }.getOrNull()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
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
                // A no-op edit must not write a second copy of the photo.
                enabled = !recipe.isIdentity && !saving && source != null,
                onClick = {
                    val base = source ?: return@TextButton
                    saving = true
                    // Saving re-renders from the decoded source, which is the PREVIEW resolution.
                    // Honest limitation, stated in the ADR: a full-resolution export needs a
                    // second decode and a memory budget, and is the editor's next step.
                    scope.launch {
                        val full = withContext(Dispatchers.Default) { EditRenderer.render(base, recipe) }
                        val result = writer.save(asset, full)
                        saving = false
                        onSaved(
                            result.fold(
                                onSuccess = { "Saved a copy" },
                                onFailure = { it.message ?: "Could not save the edited copy" },
                            ),
                        )
                    }
                },
            ) {
                Text(
                    if (saving) "Saving…" else "Save copy",
                    color = if (recipe.isIdentity) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shown = preview
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EditTool.entries.toList(), key = { it.name }) { entry ->
                    val selected = entry == tool
                    Text(
                        entry.label,
                        color = if (selected) Color.Black else Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                                RoundedCornerShape(50),
                            )
                            .clickable { tool = entry }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            when (tool) {
                EditTool.ADJUST -> {
                    LabelledSlider("Brightness", recipe.brightness) { recipe = recipe.copy(brightness = it) }
                    LabelledSlider("Contrast", recipe.contrast) { recipe = recipe.copy(contrast = it) }
                    LabelledSlider("Saturation", recipe.saturation) { recipe = recipe.copy(saturation = it) }
                    LabelledSlider("Warmth", recipe.warmth) { recipe = recipe.copy(warmth = it) }
                }

                EditTool.CROP -> {
                    val imageAspect = remember(source) {
                        val b = source
                        if (b != null && b.height > 0) b.width.toFloat() / b.height else 1f
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AspectPreset.entries.toList(), key = { it.name }) { preset ->
                            Text(
                                preset.label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                                    .clickable {
                                        recipe = recipe.copy(
                                            crop = when (preset) {
                                                AspectPreset.FREE -> CropRect.FULL
                                                AspectPreset.ORIGINAL -> CropRect.FULL
                                                else -> recipe.crop.fitTo(preset.ratio!!, imageAspect)
                                            },
                                        )
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Text(
                        "Presets crop from the centre. Dragging the crop box directly is the " +
                            "next step for this tool.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                EditTool.ROTATE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { recipe = recipe.rotatedClockwise() }) {
                            Text("Rotate 90°", color = Color.White)
                        }
                        TextButton(onClick = { recipe = recipe.copy(flipHorizontal = !recipe.flipHorizontal) }) {
                            Text(if (recipe.flipHorizontal) "Unflip" else "Flip", color = Color.White)
                        }
                        TextButton(onClick = { recipe = EditRecipe() }) {
                            Text("Reset all", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(
                // Shown as a signed percentage so "no change" is unmistakably zero.
                "${(value * 100).toInt()}",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = -1f..1f)
    }
}

/**
 * A stand-in for the real viewport width, used to size the preview decode.
 *
 * A constant rather than a measurement because the decode happens before layout; it is an upper
 * bound on a phone's long edge, and [EditRenderer.previewEdge] clamps whatever it is handed.
 */
private const val PREVIEW_VIEWPORT_EDGE_PX = 1080
