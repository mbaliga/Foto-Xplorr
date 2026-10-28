package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.RoomStyle

/** For tests: the name field, the colour field and the recolour preview. */
internal const val NAME_TAG = "palette-name"
internal const val COLOUR_TAG = "palette-colour-field"
internal const val PREVIEW_TAG = "palette-recolour-preview"

// ---- start --------------------------------------------------------------------------------------------

@Composable
internal fun StartPage(
    draft: PaletteDraft,
    saved: SavedPalettes?,
    confirming: String?,
    unsavedHeld: Boolean,
    onPhotos: () -> Unit,
    onColour: () -> Unit,
    onKeep: () -> Unit,
    onOpenSaved: (Palette) -> Unit,
    onAskDelete: (String?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hint") { Text(PaletteBuilderCopy.START_HINT, style = MaterialTheme.typography.bodyMedium) }
        item(key = "begin") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.palette != null) PbButton(PaletteBuilderCopy.KEEP_EDITING, onKeep, primary = true, modifier = Modifier.fillMaxWidth())
                PbButton(PaletteBuilderCopy.FROM_PHOTOS, onPhotos, modifier = Modifier.fillMaxWidth(), primary = draft.palette == null)
                PbButton(PaletteBuilderCopy.FROM_COLOUR, onColour, modifier = Modifier.fillMaxWidth())
            }
        }
        if (unsavedHeld) {
            item(key = "unsaved") { Text(PaletteBuilderCopy.UNSAVED_LEAVE, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium) }
        }
        item(key = "saved-title") { PbHeading(PaletteBuilderCopy.SAVED_TITLE) }
        when {
            saved == null -> Unit
            saved.unreadable -> item(key = "saved-unreadable") { PbCaption(PaletteBuilderCopy.SAVED_UNREADABLE) }
            else -> {
                if (saved.damaged) item(key = "saved-damaged") { PbCaption(PaletteBuilderCopy.SAVED_DAMAGED) }
                if (saved.palettes.isEmpty()) item(key = "saved-none") { PbCaption(PaletteBuilderCopy.NO_SAVED) }
                // By place, not by name: the store's idea of "the same name" and lower-casing do not always agree, and a repeated key crashes the list.
                itemsIndexed(saved.palettes, key = { index, _ -> "saved-$index" }) { _, palette ->
                    SavedRow(palette, asking = confirming.equals(palette.name, ignoreCase = true), onOpen = { onOpenSaved(palette) }, onAskDelete = onAskDelete, onDelete = onDelete)
                }
            }
        }
        // Clear of the bottom edge zone, as the hub's last item is.
        item(key = "end") { Spacer(Modifier.size(72.dp)) }
    }
}

/** One saved palette: its colours, its name, Open and Delete; Delete asks once, in place, before it deletes. */
@Composable
private fun SavedRow(
    palette: Palette,
    asking: Boolean,
    onOpen: () -> Unit,
    onAskDelete: (String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniStrip(
            palette.argbs,
            Modifier.semantics { contentDescription = PaletteBuilderCopy.stripDescription(palette.colors.map { it.hex }) },
        )
        Text(PaletteBuilderCopy.savedRow(palette.name, palette.size), style = MaterialTheme.typography.bodyMedium)
        if (asking) {
            Text(PaletteBuilderCopy.deleteAsk(palette.name), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PbButton(PaletteBuilderCopy.DELETE_YES, { onDelete(palette.name) })
                PbButton(PaletteBuilderCopy.DELETE_KEEP, { onAskDelete(null) }, primary = true)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PbButton(PaletteBuilderCopy.OPEN, onOpen, modifier = Modifier.semantics { contentDescription = PaletteBuilderCopy.openSaved(palette.name) })
                PbButton(PaletteBuilderCopy.DELETE, { onAskDelete(palette.name) }, modifier = Modifier.semantics { contentDescription = PaletteBuilderCopy.deleteSaved(palette.name) })
            }
        }
    }
}

// ---- photos -------------------------------------------------------------------------------------------

@Composable
internal fun PhotosPage(
    stills: List<MediaAsset>,
    draft: PaletteDraft,
    reading: Boolean,
    progress: Float,
    error: String?,
    onToggle: (MediaAsset) -> Unit,
    onMake: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        PhotoGrid(
            stills = stills,
            chosen = draft.picks,
            canChoose = !draft.isFull && !reading,
            onToggle = { asset -> if (!reading) onToggle(asset) },
            modifier = Modifier.weight(1f),
        ) {
            fullWidth("hint") { Text(PaletteBuilderCopy.PICK_HINT, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            if (stills.isEmpty()) {
                fullWidth("none") { Text(PaletteBuilderCopy.NO_PHOTOS, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint) }
            }
        }
        // Clear of the bottom edge zone, and where Make palette is: the one thing this page leads to.
        Column(
            Modifier.fillMaxWidth().background(RoomStyle.Surface).navigationBarsPadding().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (reading) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
                Text(PaletteBuilderCopy.READING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            } else if (error != null) {
                Text(error, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(PaletteBuilderCopy.chosen(draft.picks.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (reading) {
                    PbButton(PaletteBuilderCopy.CANCEL, onCancel)
                } else {
                    PbButton(PaletteBuilderCopy.MAKE_PALETTE, onMake, enabled = draft.canMake, primary = true)
                }
            }
        }
    }
}

// ---- colour -------------------------------------------------------------------------------------------

@Composable
internal fun ColourPage(text: String, onText: (String) -> Unit, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val parsed = ColorMath.parseHex(text)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(PaletteBuilderCopy.COLOUR_HINT, style = MaterialTheme.typography.bodyMedium)
        DarkField(
            value = text,
            onValueChange = onText,
            label = PaletteBuilderCopy.COLOUR_LABEL,
            modifier = Modifier.fillMaxWidth().testTag(COLOUR_TAG),
            imeAction = ImeAction.Done,
            onDone = { if (parsed != null) onStart() },
        )
        if (parsed == null) {
            Text(PaletteBuilderCopy.COLOUR_BAD, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .background(Color(parsed), RoundedCornerShape(8.dp))
                    .semantics { contentDescription = ColorMath.hex(parsed) },
            )
        }
        PbButton(PaletteBuilderCopy.COLOUR_START, onStart, enabled = parsed != null, primary = true)
        Spacer(Modifier.size(72.dp))
    }
}

/** A text field in the room's colours: the pane is black whatever the theme, so the field's own colours are set. */
@Composable
internal fun DarkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RoomStyle.Ink,
            unfocusedTextColor = RoomStyle.Ink,
            focusedBorderColor = RoomStyle.Ink,
            unfocusedBorderColor = RoomStyle.InkFaint,
            focusedLabelColor = RoomStyle.Ink,
            unfocusedLabelColor = RoomStyle.InkMuted,
            cursorColor = RoomStyle.Ink,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

// ---- edit ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditPage(
    draft: PaletteDraft,
    notice: String?,
    onPick: (Int) -> Unit,
    onNudge: (HslChannel, Int) -> Unit,
    actions: SwatchActions,
    onHarmony: (HarmonyKind) -> Unit,
    onUndo: () -> Unit,
    onRename: (String) -> Unit,
    onSave: (String) -> Unit,
    onShareText: (ExportFormat) -> Unit,
    onShareCard: () -> Unit,
    onRecolour: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = draft.palette ?: return
    // The name being typed is its own text; it is cleaned and kept when the field loses focus or Save is pressed.
    // A new key (a palette opened, made or undone) starts the field again from that palette's name.
    var name by rememberSaveable(draft.generation) { mutableStateOf(palette.name) }
    fun commitName() {
        onRename(name)
        name = Palette.cleanName(name)
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (notice != null) Text(notice, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)

        DarkField(
            value = name,
            onValueChange = { name = it.take(Palette.MAX_NAME_LENGTH * 2) },
            label = PaletteBuilderCopy.NAME_LABEL,
            modifier = Modifier.fillMaxWidth().testTag(NAME_TAG).onFocusChanged { if (!it.isFocused) commitName() },
            onDone = ::commitName,
        )

        SwatchStrip(palette, draft.selected, onPick)

        draft.readout()?.let { read ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    PaletteBuilderCopy.PICKED + " " + (draft.colour?.hex ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.polite(),
                )
                HslSteppers(read, onNudge)
                SwatchButtons(draft, actions)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PbCaption(PaletteBuilderCopy.HARMONY_HINT)
            HarmonyButtons(canUndo = draft.undo.isNotEmpty(), onHarmony = onHarmony, onUndo = onUndo)
        }

        ContrastSection(palette)
        VisionSection(palette)

        PbButton(PaletteBuilderCopy.RECOLOUR_OPEN, onRecolour, modifier = Modifier.fillMaxWidth())

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PbButton(PaletteBuilderCopy.SAVE, { commitName(); onSave(name) }, primary = true)
            PbHeading(PaletteBuilderCopy.SHARE_TITLE)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { format ->
                    PbButton(
                        PaletteBuilderCopy.shareAs(format.label),
                        { commitName(); if (format == ExportFormat.CARD) onShareCard() else onShareText(format) },
                    )
                }
            }
        }
        // Clear of the bottom edge zone, as the hub's last item is.
        Spacer(Modifier.size(72.dp))
    }
}

// ---- recolour -----------------------------------------------------------------------------------------

@Composable
internal fun RecolourPage(
    stills: List<MediaAsset>,
    draft: PaletteDraft,
    preview: PreviewState,
    copying: Boolean,
    canSave: Boolean,
    onPick: (MediaAsset) -> Unit,
    onSettings: ((RecolourSettings) -> RecolourSettings) -> Unit,
    onPreview: () -> Unit,
    onCancelPreview: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = draft.palette ?: return
    val ready = (preview as? PreviewState.Ready)?.bitmap
    val failed = (preview as? PreviewState.Failed)?.message
    val running = preview == PreviewState.Running
    val picked = draft.recolourPick
    Column(modifier.fillMaxWidth()) {
        PhotoGrid(
            stills = stills,
            chosen = listOfNotNull(picked),
            canChoose = true,
            onToggle = onPick,
            modifier = Modifier.weight(1f),
        ) {
            fullWidth("strip") {
                MiniStrip(
                    palette.argbs,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { contentDescription = PaletteBuilderCopy.stripDescription(palette.colors.map { it.hex }) },
                    height = 36.dp,
                )
            }
            fullWidth("hint") { Text(PaletteBuilderCopy.RECOLOUR_HINT, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
            fullWidth("settings") { RecolourSettingsRow(draft.recolour, onSettings) }
            if (ready != null) fullWidth("preview") { PreviewPicture(ready) }
            if (failed != null) fullWidth("failed") { Text(failed, modifier = Modifier.padding(horizontal = 16.dp).polite(), style = MaterialTheme.typography.bodyMedium) }
            if (running) fullWidth("running") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
                    Text(PaletteBuilderCopy.PREVIEWING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (stills.isEmpty()) {
                fullWidth("none") { Text(PaletteBuilderCopy.NO_PHOTOS, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint) }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(RoomStyle.Surface).navigationBarsPadding().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (picked == null) Text(PaletteBuilderCopy.NEED_PHOTO, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    PbButton(PaletteBuilderCopy.CANCEL, onCancelPreview)
                } else {
                    PbButton(PaletteBuilderCopy.PREVIEW, onPreview, enabled = picked != null && !copying)
                }
                if (canSave) PbButton(PaletteBuilderCopy.SAVE_COPY, onSave, enabled = picked != null && !copying && !running, primary = true)
            }
        }
    }
}

@Composable
private fun RecolourSettingsRow(settings: RecolourSettings, onSettings: ((RecolourSettings) -> RecolourSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(PaletteBuilderCopy.STRENGTH, style = MaterialTheme.typography.bodyMedium)
                Text(PaletteBuilderCopy.strengthValue(settings.strength), modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            }
            PbButton(PaletteBuilderCopy.WEAKER, { onSettings { it.weaker() } }, enabled = settings.strength > RecolourSettings.MIN)
            PbButton(PaletteBuilderCopy.STRONGER, { onSettings { it.stronger() } }, enabled = settings.strength < RecolourSettings.FULL)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = settings.keepShading, role = Role.Switch, onValueChange = { keep -> onSettings { it.copy(keepShading = keep) } }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(PaletteBuilderCopy.KEEP_SHADING, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = settings.keepShading,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color.White,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.Black,
                    uncheckedBorderColor = RoomStyle.InkFaint,
                ),
            )
        }
    }
}

@Composable
private fun PreviewPicture(bitmap: Bitmap) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = PaletteBuilderCopy.PREVIEW_DESCRIPTION,
        // Polite: the picture appearing is how a screen reader learns the preview is done.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(image.width.toFloat() / image.height)
            .background(Color.Black)
            .testTag(PREVIEW_TAG)
            .polite(),
        contentScale = ContentScale.Fit,
    )
}
