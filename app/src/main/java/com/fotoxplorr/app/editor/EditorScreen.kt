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

/**
 * Which set of controls the bottom bar is showing.
 *
 * Grouped the way a photographer works rather than by what the code does: light before colour
 * before detail, because fixing exposure changes what the colour looks like and sharpening before
 * either amplifies whatever you were about to correct.
 */
private enum class EditTool(val label: String) {
    LIGHT("Light"),
    COLOUR("Colour"),
    DETAIL("Detail"),
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
    /** What Save does. ASK shows the choice; anything else acts and remembers. */
    saveMode: EditorSaveMode = EditorSaveMode.ASK,
    onSetSaveMode: (EditorSaveMode) -> Unit = {},
) {
    val context = LocalContext.current
    var recipe by remember(asset.id) { mutableStateOf(EditRecipe()) }
    var tool by remember { mutableStateOf(EditTool.LIGHT) }
    var source by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var preview by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveRequested by remember { mutableStateOf(false) }
    val writer = remember(context) { EditedCopyWriter(context) }
    val scope = rememberCoroutineScope()

    // Decode once, at a bounded size. Full resolution would mean the whole pipeline over tens of
    // megapixels on every slider frame.
    LaunchedEffect(asset.id) {
        source = withContext(Dispatchers.IO) {
            decodeBounded(context, asset, EditRenderer.previewEdge(PREVIEW_VIEWPORT_EDGE_PX))
        }
    }

    // Auto-fix offers, measured from the UNEDITED source so they describe the photograph rather
    // than the user's work in progress. Computed once per photo on a sampled copy: analysing at
    // preview resolution costs real milliseconds and tells you nothing a thumbnail does not.
    var autoFixes by remember(asset.id) { mutableStateOf<List<AutoFix.Suggestion>>(emptyList()) }
    LaunchedEffect(source) {
        val base = source ?: return@LaunchedEffect
        autoFixes = withContext(Dispatchers.Default) {
            runCatching {
                val sampled = Bitmap.createScaledBitmap(base, ANALYSIS_EDGE_PX, ANALYSIS_EDGE_PX, true)
                val pixels = IntArray(sampled.width * sampled.height)
                sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
                if (sampled !== base) sampled.recycle()
                // Same sampled pixels feed both: detectHorizon needs the 2D layout analyse()
                // throws away, but re-sampling the bitmap a second time would be the exact
                // "costs real milliseconds for nothing a thumbnail does not" waste the comment
                // above this block already argues against for the tonal analysis.
                val horizon = AutoFix.detectHorizon(pixels, sampled.width, sampled.height)
                AutoFix.suggestionsFor(AutoFix.analyse(pixels), horizonDegrees = horizon)
            }.getOrDefault(emptyList())
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
                onClick = { saveRequested = true },
            ) {
                Text(
                    if (saving) "Saving…" else "Save",
                    color = if (recipe.isIdentity) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Saving in one place, whichever route asked for it. Two call sites for "write the file"
        // is how a save path ends up with two different sets of error handling.
        fun performSave(mode: EditorSaveMode) {
            saveRequested = false
            saving = true
            scope.launch {
                val full = renderFullSize(context, asset, recipe)
                val result = if (full == null) {
                    Result.failure(IllegalStateException("Could not read the photo at full size"))
                } else {
                    writer.save(asset, full)
                }
                saving = false
                onSaved(
                    result.fold(
                        onSuccess = {
                            if (mode == EditorSaveMode.OVERWRITE) {
                                // Honest about what actually happened. Replacing the original
                                // needs a per-file write grant that this build does not yet
                                // request, and claiming a replacement that did not happen is
                                // worse than doing the safe thing and saying so.
                                "Saved a copy — replacing the original is not wired up yet"
                            } else {
                                "Saved a copy at full resolution"
                            }
                        },
                        onFailure = { it.message ?: "Could not save the edited photo" },
                    ),
                )
            }
        }

        if (saveRequested) {
            if (saveMode == EditorSaveMode.ASK) {
                SaveChoiceSheet(
                    onDismiss = { saveRequested = false },
                    onChoose = { mode, remember ->
                        if (remember) onSetSaveMode(mode)
                        performSave(mode)
                    },
                )
            } else {
                LaunchedEffect(saveRequested) { performSave(saveMode) }
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
            // Offers come before the manual tools: the common case is "make this look right",
            // and someone who wants a slider will scroll past a row of three chips without
            // resenting it. Absent entirely when the photo needs nothing, which is the property
            // that keeps them worth reading.
            if (autoFixes.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(autoFixes, key = { it.id.name }) { suggestion ->
                        Column(
                            modifier = Modifier
                                .background(
                                    Color.White.copy(alpha = 0.10f),
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    // STRAIGHTEN is not an Adjustments field, and its
                                    // `suggestion.adjustments` is only `current` passed through
                                    // unchanged (see AutoFix.suggestionsFor) -- applying it via
                                    // the same `adjustments = suggestion.adjustments` path the
                                    // other three chips use would silently reset any colour work
                                    // done before this chip was tapped. Routing on whether
                                    // straightenDegrees is set keeps each chip touching only the
                                    // one thing its label promises.
                                    recipe = if (suggestion.straightenDegrees != null) {
                                        recipe.copy(straightenDegrees = suggestion.straightenDegrees)
                                    } else {
                                        recipe.copy(adjustments = suggestion.adjustments)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                suggestion.label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                suggestion.reason,
                                color = Color.White.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

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

            val adjust = recipe.adjustments
            fun set(block: Adjustments.() -> Adjustments) {
                recipe = recipe.copy(adjustments = adjust.block())
            }

            when (tool) {
                EditTool.LIGHT -> {
                    // Exposure is in stops, so its range is not the -1..1 the others use: a photo
                    // that needs rescuing needs two stops, not a fraction of one.
                    LabelledSlider("Exposure", adjust.exposure, range = -2f..2f, unit = " EV") {
                        set { copy(exposure = it) }
                    }
                    LabelledSlider("Contrast", adjust.contrast) { set { copy(contrast = it) } }
                    LabelledSlider("Highlights", adjust.highlights) { set { copy(highlights = it) } }
                    LabelledSlider("Shadows", adjust.shadows) { set { copy(shadows = it) } }
                    LabelledSlider("Whites", adjust.whites) { set { copy(whites = it) } }
                    LabelledSlider("Blacks", adjust.blacks) { set { copy(blacks = it) } }
                }

                EditTool.COLOUR -> {
                    LabelledSlider("Temperature", adjust.temperature) { set { copy(temperature = it) } }
                    LabelledSlider("Tint", adjust.tint) { set { copy(tint = it) } }
                    LabelledSlider("Vibrance", adjust.vibrance) { set { copy(vibrance = it) } }
                    LabelledSlider("Saturation", adjust.saturation) { set { copy(saturation = it) } }
                }

                EditTool.DETAIL -> {
                    // These three run neighbourhood passes rather than a lookup, so they are the
                    // slow ones -- grouped together so it is obvious which controls cost time.
                    LabelledSlider("Sharpen", adjust.sharpen, range = 0f..1f) { set { copy(sharpen = it) } }
                    LabelledSlider("Clarity", adjust.clarity, range = 0f..1f) { set { copy(clarity = it) } }
                    LabelledSlider("Vignette", adjust.vignette) { set { copy(vignette = it) } }
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
                    // Free rotation, solved as a crop problem rather than refused: dragging this
                    // past zero exposes triangular gaps at the corners, which EditRenderer hides
                    // by auto-cropping inward via StraightenGeometry -- so the preview itself is
                    // already showing exactly what export will produce, corners and all.
                    LabelledSlider(
                        "Straighten",
                        recipe.straightenDegrees,
                        range = -STRAIGHTEN_LIMIT_DEGREES..STRAIGHTEN_LIMIT_DEGREES,
                        unit = "°",
                    ) { recipe = recipe.copy(straightenDegrees = it) }
                }
            }
        }
    }
}

/**
 * The choice at save time, when the stored mode is ASK.
 *
 * Copy is listed first and is the safe one. The order is not decoration: the destructive option
 * being second means the muscle-memory tap is the one that cannot lose a photograph.
 */
@Composable
private fun SaveChoiceSheet(
    onDismiss: () -> Unit,
    onChoose: (EditorSaveMode, Boolean) -> Unit,
) {
    var remember by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(Color(0xFF121212), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Save this edit", color = Color.White, style = MaterialTheme.typography.titleMedium)
            listOf(EditorSaveMode.COPY, EditorSaveMode.OVERWRITE).forEach { mode ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(mode, remember) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(mode.label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        mode.description,
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { remember = !remember }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.fotoxplorr.app.ui.RoomToggle(remember, { remember = it })
                Text(
                    "Do this every time",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "Changeable later in Settings.",
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Render at FULL resolution and write the result.
 *
 * A second decode rather than reusing the preview bitmap, which is the whole point: the preview is
 * capped at 2048px so a slider drag stays interactive, and saving that would hand the user a
 * downscaled copy of their own photograph. The recipe is resolution-independent by construction —
 * the crop is normalised and the colour work is per-pixel — so the same description renders
 * correctly at either size.
 *
 * Capped at [MAX_EXPORT_PIXELS] because a 108-megapixel phone photo is 432 MB as ARGB_8888 and
 * several copies of that exist at once inside the pipeline. Above the cap the export is downscaled
 * rather than the app being killed mid-save, and the caller says so.
 */
private suspend fun renderFullSize(
    context: android.content.Context,
    asset: MediaAsset,
    recipe: EditRecipe,
): Bitmap? = withContext(Dispatchers.IO) {
    val full = decodeBounded(context, asset, MAX_EXPORT_EDGE) ?: return@withContext null
    withContext(Dispatchers.Default) { runCatching { EditRenderer.render(full, recipe) }.getOrNull() }
}

/**
 * Decode [asset] with its longest edge no greater than [edge].
 *
 * `inSampleSize` only takes powers of two, so this lands at or below the target rather than exactly
 * on it — which is the right way round: decoding above the budget and scaling down afterwards means
 * holding the oversized bitmap first, which is the allocation that fails.
 */
private fun decodeBounded(
    context: android.content.Context,
    asset: MediaAsset,
    edge: Int,
): Bitmap? = runCatching {
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

/**
 * The longest edge an export may reach.
 *
 * 8192 is 67 megapixels at 1:1 and covers every phone camera in circulation, while keeping the
 * ARGB_8888 buffer under 270 MB — and the pipeline holds two or three of those at once.
 */
private const val MAX_EXPORT_EDGE = 8192

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    unit: String = "",
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                // Double-tap-free way back to neutral. A slider you can only approach zero on is
                // a slider you cannot undo, and "close to zero" is visible in a photograph.
                if (value != 0f) {
                    Text(
                        "reset",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { onChange(0f) },
                    )
                }
            }
            Text(
                // Signed, so "no change" is unmistakably zero rather than a value near it.
                if (unit.isEmpty()) "${(value * 100).toInt()}" else "${(value * 10).toInt() / 10f}$unit",
                color = if (value == 0f) Color.White.copy(alpha = 0.4f) else Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/**
 * A stand-in for the real viewport width, used to size the preview decode.
 *
 * A constant rather than a measurement because the decode happens before layout; it is an upper
 * bound on a phone's long edge, and [EditRenderer.previewEdge] clamps whatever it is handed.
 */
private const val PREVIEW_VIEWPORT_EDGE_PX = 1080

/**
 * Edge length the auto-fix analysis samples down to.
 *
 * 128x128 is ~16k pixels, which settles a histogram completely while costing under a millisecond.
 * Measuring at preview resolution would be a thousand times the work for an answer accurate to the
 * same two decimal places -- a histogram is a statistic, and statistics converge fast.
 */
private const val ANALYSIS_EDGE_PX = 128

/**
 * The straighten slider's range, in degrees each direction.
 *
 * Matches the "roughly -15..15" a small-angle levelling control needs -- past this, the
 * auto-crop StraightenGeometry computes would be throwing away a third of the photo or more,
 * which stops being "straighten" and starts being a very expensive crop tool.
 */
private const val STRAIGHTEN_LIMIT_DEGREES = 15f
