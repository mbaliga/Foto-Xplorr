package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Which set of controls the bottom bar is showing.
 *
 * Grouped the way a photographer works rather than by what the code does: light before colour
 * before detail, because fixing exposure changes what the colour looks like and sharpening before
 * either amplifies whatever you were about to correct. All of them are always offered, in one
 * scrollable strip -- there used to be a Simple/Pro switch beside it, and its layout starved the
 * switch to zero width so Curves could not be reached at all (docs/TRAPS.md, the LazyRow note).
 * [CURVES] sits third because it is the tool people come looking for.
 */
private enum class EditTool(val label: String) {
    LIGHT("Light"),
    COLOUR("Colour"),
    CURVES("Curves"),
    PRESETS("Presets"),
    DETAIL("Detail"),
    HSL("HSL"),
    CROP("Crop"),
    ROTATE("Rotate"),
    HEAL("Heal"),
}

/**
 * The in-app photo editor.
 *
 * Built rather than pulled in — see `docs/adr/ADR-007-photo-editing.md`. The short version: the
 * obvious library (uCrop) declares an OkHttp dependency and would hard-fail the offline flavour's
 * classpath gate, and the strongest open-source galleries in this space are GPL-3.0, which this
 * app cannot take without becoming GPL itself.
 *
 * Editing itself is non-destructive: the screen only ever holds an [EditRecipe] and previews it at
 * a bounded size, never touching the original's bytes until Save is actually tapped. Saving a
 * COPY (this editor's long-standing default, and the one the save sheet lists first) still never
 * opens the original for writing at all. Saving with OVERWRITE does — deliberately, and only after
 * the same per-file write consent Android already requires for [com.fotoxplorr.app.metadata.MetadataWriter]
 * and rename — see [onOverwrite]'s own doc and [EditedCopyWriter.overwrite].
 *
 * A recipe in progress is autosaved per photo by [RecipeStore] and offered back as "Continue
 * editing" the next time this asset is opened here — see that class's own doc for exactly how
 * "the same photo" is decided, and [EditHistory] for the in-session undo/redo stack this same
 * autosave feeds on a debounce so a slider drag does not flood either one with intermediate steps.
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
    /**
     * Replaces [asset]'s own file with [Bitmap], in place. Fire-and-forget: only an `Activity` can
     * run the write-consent dance a per-file grant needs (see [EditedCopyWriter.overwrite]'s own
     * doc), so this screen hands the rendered bitmap off — see the OVERWRITE branch of
     * `performSave` for why it does NOT also close itself afterward any more. The caller decides
     * when to close: on success, once the write has actually happened, and on the system consent
     * being declined it is expected to keep this screen open and offer a copy instead, exactly
     * because the edit the user just made is still sitting right here, unsaved either way.
     */
    onOverwrite: (asset: MediaAsset, bitmap: Bitmap) -> Unit = { _, _ -> },
    /**
     * Optional. Called alongside [onSaved] whenever a save produced a Uri this app can point at —
     * a fresh COPY or a "Save to…" destination, never OVERWRITE (the Uri does not change) and
     * never a failure. Additive: existing callers that only pass [onSaved] see no change at all.
     */
    onSavedUri: ((Uri) -> Unit)? = null,
) {
    val context = LocalContext.current
    val recipeStore = remember(context) { RecipeStore(context) }
    val presetStore = remember(context) { PresetStore(context) }

    // A stored recipe for THIS asset (same id, same dateModifiedSeconds -- see RecipeStore's own
    // doc) is offered rather than silently resumed: silently discarding a still-relevant autosave
    // because the user actually wanted a fresh start would be just as wrong as silently reviving
    // one they had already abandoned on purpose.
    var pendingResumeChoice by remember(asset.id) { mutableStateOf(recipeStore.has(asset)) }
    var recipe by remember(asset.id) { mutableStateOf(EditRecipe()) }
    var history by remember(asset.id) { mutableStateOf(EditHistory.of(EditRecipe())) }

    // Saveable, so a process kill while the user is mid-edit (the editor is a full-screen host,
    // and Android reclaims those freely) returns them to the tab they were on rather than Light.
    var tool by rememberSaveable(stateSaver = EditToolSaver) { mutableStateOf(EditTool.LIGHT) }
    // Hoisted out of CurvesEditor, which leaves composition whenever another tab is open: state
    // kept inside it would reset to RGB every time the user stepped away and back.
    var curveChannel by rememberSaveable(stateSaver = CurveChannelSaver) { mutableStateOf(CurveChannel.RGB) }
    // True only while a finger is actually moving a curve point; see the render effect below.
    var curveDragging by remember { mutableStateOf(false) }
    var source by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var preview by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveRequested by remember { mutableStateOf(false) }
    val writer = remember(context) { EditedCopyWriter(context) }
    val scope = rememberCoroutineScope()

    var userPresets by remember { mutableStateOf(presetStore.list()) }
    fun refreshPresets() { userPresets = presetStore.list() }

    // Press-and-hold on the preview to compare with the untouched original -- see the preview
    // Box's own pointerInput branch below, which is also where the Heal tool's tap-to-place-spot
    // gesture lives, mutually exclusive by construction (one pointerInput block per tool).
    var comparing by remember { mutableStateOf(false) }
    var healRadius by remember { mutableStateOf(HealSpot.DEFAULT_RADIUS) }
    var selectedAspect by remember(asset.id) { mutableStateOf(AspectPreset.FREE) }
    var previewContainerSize by remember { mutableStateOf(IntSize.Zero) }

    var exportOptions by remember { mutableStateOf(ExportOptions()) }
    val heicAvailable = remember { isHeicExportSupported() }

    // Decode once, at a bounded size. Full resolution would mean the whole pipeline over tens of
    // megapixels on every slider frame.
    LaunchedEffect(asset.id) {
        val edge = EditRenderer.previewEdge(PREVIEW_VIEWPORT_EDGE_PX)
        source = decodeUpright(context, asset.contentUri, DecodeLimits(maxLongEdge = edge, maxPixels = Long.MAX_VALUE))?.bitmap
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

    // The histogram behind the curve graph, measured from the UNEDITED source for the same reason
    // the auto-fix offers are: it describes the photograph, and a backdrop that moved while the
    // user dragged a point would make the point they are dragging impossible to judge.
    var histogram by remember(asset.id) { mutableStateOf<ToneHistogram?>(null) }
    LaunchedEffect(source) {
        val base = source ?: return@LaunchedEffect
        histogram = withContext(Dispatchers.Default) {
            runCatching { histogramOf(base) }.getOrNull()
        }
    }

    // A much smaller copy of the source, used for the preview only while a curve point is being
    // dragged. The full preview is up to 2048px on a side and every move re-renders it from
    // scratch; a drag produces a render per touch event, and at that size they queue faster than
    // they finish. The graph is the thing under the finger, so the photo can be coarse until it
    // lets go -- the settled recipe then re-renders from the full source.
    var dragSource by remember(asset.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source) {
        val base = source ?: return@LaunchedEffect
        dragSource = withContext(Dispatchers.Default) {
            runCatching { downscaledForDrag(base) }.getOrNull()
        }
    }

    // Re-render off the main thread: the matrix runs over every previewed pixel, and doing that
    // in composition would drop frames on the very drag that requested it.
    val renderBase = if (curveDragging) (dragSource ?: source) else source
    LaunchedEffect(renderBase, recipe) {
        val base = renderBase ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            runCatching { EditRenderer.render(base, recipe) }.getOrNull()
        }
    }

    // Autosave and undo/redo both feed off the SAME settled-recipe signal, debounced: a slider
    // drag fires this effect on every intermediate frame, and LaunchedEffect keyed on `recipe`
    // cancels and restarts its own delay each time, so only the value the user actually stopped
    // on ever reaches disk or the history stack -- not every frame in between.
    LaunchedEffect(recipe, asset.id, pendingResumeChoice) {
        if (!shouldAutosave(pendingResumeChoice)) return@LaunchedEffect
        delay(HISTORY_COMMIT_DEBOUNCE_MS)
        if (recipe != history.current) history = history.commit(recipe)
        recipeStore.save(asset, recipe)
    }

    var pendingSaveToBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingSaveToFormat by remember { mutableStateOf(OutputFormat.JPEG) }
    var pendingSaveToQuality by remember { mutableStateOf(OutputFormat.JPEG.exportQuality()) }
    // " at reduced size (W × H)" when the export being picked a location for was downscaled, else
    // empty -- see FullSizeRender.reducedSizeNote.
    var pendingSaveToNote by remember { mutableStateOf("") }
    val saveToLauncher = rememberLauncherForActivityResult(remember { CreateImageDocumentContract() }) { uri ->
        val bitmap = pendingSaveToBitmap
        pendingSaveToBitmap = null
        if (uri == null || bitmap == null) {
            if (saving) { saving = false; onSaved("Cancelled — nothing was saved") }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = writer.saveTo(uri, asset, bitmap, pendingSaveToFormat, pendingSaveToQuality)
            saving = false
            result.onSuccess { onSavedUri?.invoke(uri) }
            onSaved(
                result.fold(
                    onSuccess = { "Saved to the chosen location$pendingSaveToNote" },
                    onFailure = { it.message ?: "Could not save the edited photo" },
                ),
            )
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
        // Undo and Redo live in the top bar with Cancel and Save rather than on a row of their
        // own: that row cost ~48dp of the photo's height on every tab, and with a curve graph in
        // the bottom half the photo has none to spare.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction("Cancel", enabled = true, color = Color.White, onClick = onClose)
            BarAction(
                "Undo",
                enabled = history.canUndo,
                color = Color.White,
                onClick = { history = history.undo(); recipe = history.current },
            )
            BarAction(
                "Redo",
                enabled = history.canRedo,
                color = Color.White,
                onClick = { history = history.redo(); recipe = history.current },
            )
            Text(
                asset.displayName,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            BarAction(
                if (saving) "Saving…" else "Save",
                // A no-op edit must not write a second copy of the photo.
                enabled = !recipe.isIdentity && !saving && source != null,
                color = MaterialTheme.colorScheme.primary,
                bold = true,
                onClick = { saveRequested = true },
            )
        }

        // A format this file's own bytes could actually be replaced in, or null when they can't
        // be — see overwriteFormatFor's own doc. Read once here rather than per-save so both the
        // sheet (which hides the row entirely) and performSave (which must not attempt one) agree.
        val canOverwriteInPlace = overwriteFormatFor(asset.mimeType) != null

        // Saving in one place, whichever route asked for it. Two call sites for "write the file"
        // is how a save path ends up with two different sets of error handling.
        //
        // [options] only ever affects the COPY path: its format is null for "keep the source's own
        // format" (outputFormatFor), a non-null value is an explicit override from the save
        // sheet's format chips. OVERWRITE has no format choice at all -- it can only ever re-encode
        // into the file's OWN existing format, by construction (see EditedCopyWriter.overwrite) --
        // and, for the same reason, ignores the sheet's quality and long-edge controls entirely.
        fun performSave(mode: EditorSaveMode, options: ExportOptions) {
            saveRequested = false
            saving = true
            scope.launch {
                val overwriting = mode == EditorSaveMode.OVERWRITE && canOverwriteInPlace
                // An overwrite is rendered at the absolute ceiling and on a single rung, never the
                // user's long-edge cap and never stepped down after an OutOfMemoryError: a
                // smaller export is a fine COPY, but silently re-encoding the ORIGINAL at reduced
                // size would destroy pixels that cannot be recovered (docs/TRAPS.md #17). A render
                // that fails for lack of memory is harmless -- it reports and nothing is written
                // -- and a render that still had to downscale is refused below. A COPY starts at
                // the user's cap (or the ceiling) and steps down only after a real OOM.
                val ladder = if (overwriting) listOf(MAX_EXPORT_EDGE) else exportEdgeLadder(options.renderEdge())
                val rendered = renderFullSize(context, asset, recipe, ladder)
                if (rendered == null) {
                    saving = false
                    onSaved("Could not read the photo at full size")
                    return@launch
                }
                val full = rendered.bitmap

                if (overwriting && !rendered.downscaled) {
                    saving = false
                    // Hands off to the Activity and does NOT close this screen -- see onOverwrite's
                    // own doc. The host closes on success and, on a declined consent prompt, is
                    // expected to keep this editor open (the edit is still right here) and offer a
                    // copy instead.
                    onOverwrite(asset, full)
                    return@launch
                }

                // The sheet's format chips belong to the COPY row: a refused overwrite falls back
                // to a copy in the source's own format, not whatever the chips last said.
                val resolvedFormat = options.format.takeUnless { overwriting } ?: outputFormatFor(asset.mimeType)
                if (resolvedFormat == OutputFormat.HEIC) {
                    pendingSaveToBitmap = full
                    pendingSaveToFormat = resolvedFormat
                    pendingSaveToQuality = options.resolvedQuality(resolvedFormat)
                    pendingSaveToNote = rendered.reducedSizeNote
                    // HEIC has no MediaStore fast path in this app (see EditedCopyWriter's HEIC
                    // section) -- every HEIC export goes through the same SAF picker "Save to…"
                    // uses, saving that path from having to exist twice.
                    saveToLauncher.launch(SaveToRequest(editedName(asset.displayName, resolvedFormat), resolvedFormat.mimeType))
                    return@launch
                }
                val result = writer.save(asset, full, resolvedFormat, options.resolvedQuality(resolvedFormat))
                saving = false
                result.onSuccess { uri -> onSavedUri?.invoke(uri) }
                onSaved(
                    result.fold(
                        onSuccess = {
                            when {
                                // OVERWRITE was chosen and this format could take it, but the
                                // photo is too large to re-encode at full size on this device: the
                                // original is left exactly as it was and a copy is saved instead.
                                overwriting ->
                                    "Saved a copy${rendered.reducedSizeNote} — the original was left untouched " +
                                        "because it is too large to replace at full size here"
                                // Honest about what actually happened: OVERWRITE was chosen but
                                // this format has no in-place path at all (see canOverwriteInPlace
                                // above), so the safe fallback ran instead and needs to say so.
                                mode == EditorSaveMode.OVERWRITE ->
                                    "Saved a copy — ${asset.mimeType} can't be replaced in place"
                                options.format != null ->
                                    "Saved a copy as ${resolvedFormat.label}${rendered.reducedSizeNote}"
                                rendered.downscaled -> "Saved a copy${rendered.reducedSizeNote}"
                                else -> "Saved a copy at full resolution"
                            }
                        },
                        onFailure = { it.message ?: "Could not save the edited photo" },
                    ),
                )
            }
        }

        fun performSaveTo(options: ExportOptions) {
            saveRequested = false
            saving = true
            scope.launch {
                val resolvedFormat = options.format ?: outputFormatFor(asset.mimeType)
                val rendered = renderFullSize(context, asset, recipe, exportEdgeLadder(options.renderEdge()))
                if (rendered == null) {
                    saving = false
                    onSaved("Could not read the photo at full size")
                    return@launch
                }
                pendingSaveToBitmap = rendered.bitmap
                pendingSaveToFormat = resolvedFormat
                pendingSaveToQuality = options.resolvedQuality(resolvedFormat)
                pendingSaveToNote = rendered.reducedSizeNote
                saveToLauncher.launch(SaveToRequest(editedName(asset.displayName, resolvedFormat), resolvedFormat.mimeType))
            }
        }

        if (saveRequested) {
            if (saveMode == EditorSaveMode.ASK) {
                SaveChoiceSheet(
                    canOverwrite = canOverwriteInPlace,
                    heicAvailable = heicAvailable,
                    initial = exportOptions,
                    onDismiss = { saveRequested = false },
                    onChoose = { mode, options, rememberChoice ->
                        exportOptions = options
                        if (rememberChoice) onSetSaveMode(mode)
                        performSave(mode, options)
                    },
                    onSaveTo = { options -> exportOptions = options; performSaveTo(options) },
                )
            } else {
                LaunchedEffect(saveRequested) { performSave(saveMode, exportOptions) }
            }
        }

        if (pendingResumeChoice) {
            ContinueEditingDialog(
                onContinue = {
                    pendingResumeChoice = false
                    recipeStore.load(asset)?.let { stored -> recipe = stored; history = EditHistory.of(stored) }
                },
                onStartOver = {
                    pendingResumeChoice = false
                    recipe = recipeStore.revert(asset)
                    history = EditHistory.of(recipe)
                },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shown = preview
            if (shown == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val displayed = if (comparing) (source ?: shown) else shown
                val imageAspect = if (shown.height > 0) shown.width.toFloat() / shown.height else 1f
                val lockedAspect = selectedAspect.ratio?.let { normalizedAspect(it, imageAspect) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .onSizeChanged { previewContainerSize = it }
                        // One pointerInput block per tool, not one each, so the Heal tap and the
                        // before/after press-hold can never both try to own the same gesture at
                        // once -- Crop's own drag lives entirely in the CropOverlay below instead.
                        .pointerInput(tool, shown.width, shown.height, healRadius) {
                            when (tool) {
                                EditTool.HEAL -> detectTapGestures { tap ->
                                    val imageRect = letterboxRect(
                                        size.width.toFloat(), size.height.toFloat(),
                                        shown.width.toFloat(), shown.height.toFloat(),
                                    )
                                    val nx = (tap.x - imageRect.left) / imageRect.width
                                    val ny = (tap.y - imageRect.top) / imageRect.height
                                    if (nx in 0f..1f && ny in 0f..1f) {
                                        recipe = recipe.copy(heals = recipe.heals + HealSpot(nx, ny, healRadius))
                                    }
                                }
                                EditTool.CROP -> { /* CropOverlay below owns every gesture here. */ }
                                else -> detectTapGestures(
                                    onPress = {
                                        comparing = true
                                        tryAwaitRelease()
                                        comparing = false
                                    },
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = displayed.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    if (tool == EditTool.CROP && !comparing) {
                        val imageRect = letterboxRect(
                            previewContainerSize.width.toFloat(), previewContainerSize.height.toFloat(),
                            shown.width.toFloat(), shown.height.toFloat(),
                        )
                        CropOverlay(
                            crop = recipe.crop,
                            imageRect = imageRect,
                            lockedAspect = lockedAspect,
                            onCropChange = { recipe = recipe.copy(crop = it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (comparing) {
                        Text(
                            "Original",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
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
            // Not on the Curves tab: the graph needs every dp it can get, and the offers are one
            // tab away.
            if (autoFixes.isNotEmpty() && tool != EditTool.CURVES) {
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

            // ONE strip, full width, scrollable. It used to share a Row with a "Pro tools" chip,
            // and a LazyRow without a width of its own takes everything the Row offers, so the
            // chip behind it was measured at nothing and the tabs it controlled were unreachable.
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(EditTool.entries.toList(), key = { it.name }) { entry ->
                    val selected = entry == tool
                    Text(
                        entry.label,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
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
            // Reads `recipe` afresh rather than the `adjust` captured at composition: the curve
            // graph can fire several edits between two recompositions (an insert then a move), and
            // each must build on the one before it, not on the snapshot the first one started from.
            fun set(block: Adjustments.() -> Adjustments) {
                recipe = recipe.copy(adjustments = recipe.adjustments.block())
            }

            // The curve graph is OUTSIDE the scrolling column below, on purpose: a vertical drag
            // on a control point inside a verticalScroll is contested by the scroll container and
            // the panel moves instead of the point.
            if (tool == EditTool.CURVES) {
                CurvesEditor(
                    curveFor = { channel -> recipe.adjustments.curveFor(channel) },
                    onCurveChange = { channel, curve -> set { withCurve(channel, curve) } },
                    channel = curveChannel,
                    onChannelChange = { curveChannel = it },
                    histogram = histogram,
                    onDragActive = { curveDragging = it },
                )
            }

            // Every other tool's controls scroll within a cap, so a tab with many sliders (Light,
            // Detail, HSL) cannot grow past the photo; the cap leaves the preview at least half
            // the screen.
            val controlsCap = (LocalConfiguration.current.screenHeightDp * CONTROLS_MAX_SCREEN_FRACTION).dp
            if (tool != EditTool.CURVES) Column(
                Modifier.heightIn(max = controlsCap).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tool) {
                    EditTool.LIGHT -> {
                        // Exposure is in stops, so its range is not the -1..1 the others use: a photo
                        // that needs rescuing needs two stops, not a fraction of one.
                        LabelledSlider("Exposure", adjust.exposure, range = -2f..2f, unit = " EV") {
                            set { copy(exposure = it) }
                        }
                        LabelledSlider("Contrast", adjust.contrast) { set { copy(contrast = it) } }
                        // Highlights/Shadows/Whites/Blacks are the finer, region-targeted half of tone.
                        LabelledSlider("Highlights", adjust.highlights) { set { copy(highlights = it) } }
                        LabelledSlider("Shadows", adjust.shadows) { set { copy(shadows = it) } }
                        LabelledSlider("Whites", adjust.whites) { set { copy(whites = it) } }
                        LabelledSlider("Blacks", adjust.blacks) { set { copy(blacks = it) } }
                    }

                    EditTool.COLOUR -> {
                        LabelledSlider("Temperature", adjust.temperature) { set { copy(temperature = it) } }
                        LabelledSlider("Saturation", adjust.saturation) { set { copy(saturation = it) } }
                        // Tint and Vibrance are the more surgical pair -- a green/magenta cast and a
                        // saturation boost that spares skin tones.
                        LabelledSlider("Tint", adjust.tint) { set { copy(tint = it) } }
                        LabelledSlider("Vibrance", adjust.vibrance) { set { copy(vibrance = it) } }
                    }

                    EditTool.PRESETS -> {
                        PresetsPanel(
                            userPresets = userPresets,
                            currentAdjustments = adjust,
                            onApply = { preset -> recipe = recipe.copy(adjustments = preset.adjustments) },
                            onSaveCurrent = { name -> presetStore.save(name, adjust); refreshPresets() },
                            onRename = { preset, newName -> presetStore.rename(preset, newName); refreshPresets() },
                            onDelete = { preset -> presetStore.delete(preset); refreshPresets() },
                        )
                    }

                    EditTool.DETAIL -> {
                        // These run neighbourhood passes rather than a lookup, so they are the slow
                        // ones, grouped together so it is obvious which controls cost time.
                        LabelledSlider("Sharpen", adjust.sharpen, range = 0f..1f) { set { copy(sharpen = it) } }
                        LabelledSlider("Clarity", adjust.clarity, range = 0f..1f) { set { copy(clarity = it) } }
                        LabelledSlider("Denoise", adjust.denoise, range = 0f..1f) { set { copy(denoise = it) } }
                        LabelledSlider("Vignette", adjust.vignette) { set { copy(vignette = it) } }
                    }

                    // Drawn above, outside this scrolling column -- see the CurvesEditor call.
                    EditTool.CURVES -> Unit

                    EditTool.HSL -> {
                        HslPanel(hsl = adjust.hsl, onChange = { set { copy(hsl = it) } })
                    }

                    EditTool.CROP -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AspectPreset.entries.toList(), key = { it.name }) { preset ->
                                val selected = preset == selectedAspect
                                Text(
                                    preset.label,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                                            RoundedCornerShape(50),
                                        )
                                        .clickable {
                                            selectedAspect = preset
                                            val imageAspect = source?.let {
                                                if (it.height > 0) it.width.toFloat() / it.height else 1f
                                            } ?: 1f
                                            recipe = recipe.copy(
                                                crop = when (preset) {
                                                    AspectPreset.FREE -> recipe.crop
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
                            "Drag a handle to resize, drag inside the box to move it. Straighten " +
                                "(in Rotate) still applies before the crop.",
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
                            TextButton(onClick = { recipe = EditRecipe(); history = EditHistory.of(recipe) }) {
                                Text("Reset all", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                        // "Revert to original" is stronger than "Reset all": it also forgets the
                        // autosave, so re-opening this photo later starts blank rather than offering
                        // to continue an edit the user just explicitly threw away.
                        TextButton(
                            enabled = !recipe.isIdentity || recipeStore.has(asset),
                            onClick = {
                                recipe = recipeStore.revert(asset)
                                history = EditHistory.of(recipe)
                            },
                        ) {
                            Text("Revert to original", color = Color(0xFFEF5350))
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

                    EditTool.HEAL -> {
                        // Ported from PR #9: tap the photograph (handled
                        // in the preview Box's own pointerInput above) to place a spot, a spot-size
                        // slider, remove-last / clear-all, and a spot count.
                        Text(
                            "Tap a blemish to heal it — a matching patch from nearby covers the " +
                                "spot. Heal after framing: changing crop or rotation moves the spots.",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        LabelledSlider(
                            "Spot size",
                            healRadius,
                            range = HealSpot.MIN_RADIUS..HealSpot.MAX_RADIUS,
                        ) { healRadius = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = recipe.heals.isNotEmpty(),
                                onClick = { recipe = recipe.copy(heals = recipe.heals.dropLast(1)) },
                            ) {
                                Text("Remove last", color = Color.White)
                            }
                            TextButton(
                                enabled = recipe.heals.isNotEmpty(),
                                onClick = { recipe = recipe.copy(heals = emptyList()) },
                            ) {
                                Text("Clear all", color = Color.White.copy(alpha = 0.7f))
                            }
                            if (recipe.heals.isNotEmpty()) {
                                Text(
                                    "${recipe.heals.size} ${if (recipe.heals.size == 1) "spot" else "spots"}",
                                    color = Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Continue editing / Start over", offered on opening an asset [RecipeStore] already has a
 *  recipe for — see that class's own doc for exactly when this fires. */
@Composable
private fun ContinueEditingDialog(onContinue: () -> Unit, onStartOver: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onContinue) {
        Column(
            Modifier.background(Color(0xFF121212), RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Unsaved edit found", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                "This photo has changes from last time that were never saved.",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onStartOver) { Text("Start over", color = Color.White.copy(alpha = 0.7f)) }
                TextButton(onClick = onContinue) { Text("Continue editing", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/**
 * The choice at save time, when the stored mode is ASK.
 *
 * Copy is listed first and is the safe one. The order is not decoration: the destructive option
 * being second means the muscle-memory tap is the one that cannot lose a photograph.
 *
 * @param canOverwrite whether the photo being edited can be replaced in its own format at all (see
 *   [overwriteFormatFor]). The OVERWRITE row is omitted entirely rather than shown-then-refused —
 *   an option that always fails when tapped is worse than no option, the same reasoning
 *   [com.fotoxplorr.app.viewer.PhotoDetailRoom] already applies to every editable row it draws.
 * @param heicAvailable whether [OutputFormat.HEIC] should even be offered as a format chip — see
 *   [isHeicExportSupported]'s own doc for why this is a real device capability check, not a flag.
 * @param onChoose the format is null for OVERWRITE (meaningless there — it can only ever re-encode
 *   into the file's own existing format) and for a COPY where the user left the format chips on
 *   their default, "keep the original".
 * @param onSaveTo a third destination, alongside COPY and OVERWRITE: a location the user picks
 *   through the system document picker rather than either of this app's own two defaults.
 */
@Composable
private fun SaveChoiceSheet(
    canOverwrite: Boolean,
    heicAvailable: Boolean,
    initial: ExportOptions,
    onDismiss: () -> Unit,
    onChoose: (EditorSaveMode, ExportOptions, Boolean) -> Unit,
    onSaveTo: (ExportOptions) -> Unit,
) {
    var remember by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(initial) }
    val resolvedFormat = options.format ?: OutputFormat.JPEG
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                // Fixed on purpose (reference design): a neutral panel, no accent role.
                .background(Color(0xFF121212), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Save this edit", color = Color.White, style = MaterialTheme.typography.titleMedium)

            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(EditorSaveMode.COPY, options, remember) }
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column {
                    Text(EditorSaveMode.COPY.label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        EditorSaveMode.COPY.description,
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Narrows what tapping the row above produces; tapping a chip does not itself
                // save. Kept inside the same clickable region as a separate row of smaller
                // clickables, the same nested-clickable shape TagChip's remove glyph already uses
                // elsewhere in this app -- Compose resolves the innermost tap target correctly.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatChip("Original", selected = options.format == null) { options = options.copy(format = null) }
                    OutputFormat.entries.forEach { format ->
                        if (format == OutputFormat.HEIC && !heicAvailable) return@forEach
                        FormatChip(format.label, selected = options.format == format) { options = options.copy(format = format) }
                    }
                }
            }

            if (resolvedFormat != OutputFormat.PNG) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Quality: ${options.resolvedQuality(resolvedFormat)}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = options.resolvedQuality(resolvedFormat).toFloat(),
                        onValueChange = { options = options.copy(quality = it.toInt()) },
                        valueRange = ExportOptions.MIN_QUALITY.toFloat()..ExportOptions.MAX_QUALITY.toFloat(),
                    )
                }
            }

            Text("Long edge", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaxEdgePreset.entries.forEach { preset ->
                    FormatChip(preset.label, selected = options.maxEdge == preset) { options = options.copy(maxEdge = preset) }
                }
            }

            if (canOverwrite) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(EditorSaveMode.OVERWRITE, options, remember) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(EditorSaveMode.OVERWRITE.label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        EditorSaveMode.OVERWRITE.description,
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            TextButton(onClick = { onSaveTo(options) }) {
                Text("Save to…", color = MaterialTheme.colorScheme.primary)
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
                "You can change this any time in Settings › Media.",
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun FormatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The bitmap actually written on save, plus whether decoding it had to downscale below the
 * source's own resolution (P0-03) -- [EditorScreen]'s save-result message reports this rather
 * than always claiming "full resolution". */
private data class FullSizeRender(val bitmap: Bitmap, val downscaled: Boolean) {
    /** " at reduced size (W × H)" when decoding had to downscale, else empty -- appended to a
     *  save-result message so a reduced export never reads as a full-resolution one. */
    val reducedSizeNote: String
        get() = if (downscaled) " at reduced size (${bitmap.width} × ${bitmap.height})" else ""
}

/**
 * Render at FULL resolution (or the first rung of [ladder], whichever is smaller) and return the
 * result.
 *
 * A second decode rather than reusing the preview bitmap, which is the whole point: the preview is
 * capped at 2048px so a slider drag stays interactive, and saving that would hand the user a
 * downscaled copy of their own photograph. The recipe is resolution-independent by construction —
 * the crop is normalised and the colour work is per-pixel — so the same description renders
 * correctly at either size.
 *
 * Decoded through [decodeUpright] (docs/TRAPS.md #34), which applies EXIF orientation and samples
 * to the exact bound, so a sideways-shot photo is never exported sideways. Capped at the rung's
 * edge / [MAX_EXPORT_PIXELS] because a 108-megapixel phone photo is 432 MB as ARGB_8888 and several
 * copies of that exist at once inside the pipeline. Above the cap the export is downscaled, and
 * the caller says so ([FullSizeRender.downscaled]).
 *
 * The cap is a fixed decode limit, not a guess at the device's free memory. If the decode or the
 * render actually throws [OutOfMemoryError], the next, smaller rung of [ladder] is tried (see
 * [renderDownTheLadder]) and the result reports `downscaled = true` even when the source was
 * smaller than the first rung's edge. A caller that must not accept a smaller render (OVERWRITE)
 * passes a one-rung ladder and gets null on an OOM.
 */
private suspend fun renderFullSize(
    context: android.content.Context,
    asset: MediaAsset,
    recipe: EditRecipe,
    ladder: List<Int>,
): FullSizeRender? = withContext(Dispatchers.IO) {
    val outcome = renderDownTheLadder(ladder) { edge ->
        val limits = DecodeLimits(maxLongEdge = edge, maxPixels = MAX_EXPORT_PIXELS)
        val decoded = decodeUpright(context, asset.contentUri, limits) ?: return@renderDownTheLadder null
        val rendered = withContext(Dispatchers.Default) {
            // Exception, not Throwable: an OutOfMemoryError must reach renderDownTheLadder.
            try {
                EditRenderer.render(decoded.bitmap, recipe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return@renderDownTheLadder null
        FullSizeRender(rendered, decoded.downscaled)
    } ?: return@withContext null
    outcome.value.copy(downscaled = outcome.value.downscaled || outcome.steppedDown)
}

/** What [CreateImageDocumentContract] asks the system document picker to create. */
private data class SaveToRequest(val suggestedName: String, val mimeType: String)

/**
 * `ActivityResultContracts.CreateDocument`, but with the MIME type supplied at LAUNCH time rather
 * than fixed when the contract was constructed.
 *
 * The stock contract takes its MIME type in its constructor, so a format chosen a moment before
 * `launch` (the save sheet's chips, or HEIC's always-through-the-picker path) would reach the
 * picker as whatever the previous composition last held -- state written in the same coroutine
 * step that launches has not recomposed yet. A picker told "image/jpeg" for a ".heic" suggested
 * name is free to append ".jpg" to it.
 */
private class CreateImageDocumentContract : ActivityResultContract<SaveToRequest, Uri?>() {
    override fun createIntent(context: android.content.Context, input: SaveToRequest): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(android.content.Intent.EXTRA_TITLE, input.suggestedName)

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): Uri? =
        intent.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}

@Composable
internal fun LabelledSlider(
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
 * The tool controls (everything but the curve graph) scroll inside this fraction of the screen's
 * height, which keeps at least about half of it for the photo.
 */
private const val CONTROLS_MAX_SCREEN_FRACTION = 0.40f

/** Long edge of the copy of the source the preview is rendered from while a curve point is dragged. */
private const val DRAG_PREVIEW_EDGE_PX = 640

/** Long edge of the copy the histogram is measured from. A statistic converges long before this. */
private const val HISTOGRAM_EDGE_PX = 256

/** Tab and channel as their names, so the saved state is a String rather than a private enum. */
private val EditToolSaver = Saver<EditTool, String>(
    save = { it.name },
    restore = { name -> EditTool.entries.firstOrNull { it.name == name } },
)

private val CurveChannelSaver = Saver<CurveChannel, String>(
    save = { it.name },
    restore = { name -> CurveChannel.entries.firstOrNull { it.name == name } },
)

/** [bitmap] scaled so its long edge is [maxEdge], aspect kept; the bitmap itself if already smaller. */
private fun scaledToEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    if (longEdge <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

private fun downscaledForDrag(source: Bitmap): Bitmap = scaledToEdge(source, DRAG_PREVIEW_EDGE_PX)

private fun histogramOf(source: Bitmap): ToneHistogram {
    val sampled = scaledToEdge(source, HISTOGRAM_EDGE_PX)
    val pixels = IntArray(sampled.width * sampled.height)
    sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
    if (sampled !== source) sampled.recycle()
    return ToneHistogram.of(pixels)
}

/**
 * A top-bar action. A plain tappable label rather than a [TextButton]: four of them plus the
 * photo's name share one row, and a TextButton's 58dp minimum width and 12dp padding would leave
 * the name no room on a 360dp phone.
 */
@Composable
private fun BarAction(
    label: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    bold: Boolean = false,
) {
    Text(
        label,
        color = if (enabled) color else color.copy(alpha = 0.35f),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (bold) FontWeight.Bold else null,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

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

/**
 * How long a recipe must sit unchanged before it is committed to [EditHistory] and autosaved by
 * [RecipeStore]. Long enough that a slider drag (which fires a new `recipe` value on every frame)
 * produces exactly one commit when the finger lifts, short enough that a genuine pause to look at
 * the photo still autosaves promptly.
 */
private const val HISTORY_COMMIT_DEBOUNCE_MS = 400L

/**
 * Whether the settled-recipe effect may touch history and disk. While the "Continue editing"
 * choice is pending the live recipe is still the identity placeholder, and [RecipeStore.save]
 * deletes the stored file for an identity recipe -- so an answer slower than the debounce would
 * destroy the very edit the dialog is offering to resume.
 */
internal fun shouldAutosave(pendingResumeChoice: Boolean): Boolean = !pendingResumeChoice
