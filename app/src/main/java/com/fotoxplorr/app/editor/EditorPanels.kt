package com.fotoxplorr.app.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The PRESETS tab: built-in looks, then user presets, then "Save current as preset".
 *
 * Applying a preset REPLACES [Adjustments] outright (see [Preset]'s own doc for why that is the
 * right amount of "partial") — crop, rotation and heal spots are never touched by this tab.
 *
 * @param currentAdjustments what "Save current as preset" would actually capture — the live
 *   recipe's adjustments, read by the caller rather than this composable holding a reference to
 *   the whole [EditRecipe] it has no other reason to know about.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetsPanel(
    userPresets: List<Preset>,
    currentAdjustments: Adjustments,
    onApply: (Preset) -> Unit,
    onSaveCurrent: (name: String) -> Unit,
    onRename: (Preset, String) -> Unit,
    onDelete: (Preset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var saveDialogOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Preset?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "A look, applied on top of crop and rotation — never a replacement for them.",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BuiltInPreset.entries.toList(), key = { "built-in-${it.name}" }) { builtIn ->
                val preset = builtIn.toPreset()
                Text(
                    preset.name,
                    color = if (preset.adjustments == currentAdjustments) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(
                            if (preset.adjustments == currentAdjustments) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(50),
                        )
                        .combinedClickable(onClick = { onApply(preset) })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            items(userPresets, key = { it.id }) { preset ->
                Text(
                    preset.name,
                    color = if (preset.adjustments == currentAdjustments) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(
                            if (preset.adjustments == currentAdjustments) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                            RoundedCornerShape(50),
                        )
                        // Long-press for rename/delete rather than a second, always-visible
                        // affordance on every chip: a user preset is edited far less often than
                        // it is tapped to apply, and a permanent "x" glyph on every chip would
                        // make the common action (apply) fight the rare one for thumb space.
                        .combinedClickable(onClick = { onApply(preset) }, onLongClick = { renameTarget = preset })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        TextButton(onClick = { saveDialogOpen = true }) {
            Text("Save current as preset…", color = MaterialTheme.colorScheme.primary)
        }
    }

    if (saveDialogOpen) {
        NamePromptDialog(
            title = "Save preset",
            initial = "",
            confirmLabel = "Save",
            onDismiss = { saveDialogOpen = false },
            onConfirm = { name -> saveDialogOpen = false; if (name.isNotBlank()) onSaveCurrent(name) },
        )
    }
    renameTarget?.let { preset ->
        PresetActionsDialog(
            preset = preset,
            onDismiss = { renameTarget = null },
            onRename = { newName -> renameTarget = null; onRename(preset, newName) },
            onDelete = { renameTarget = null; onDelete(preset) },
        )
    }
}

@Composable
private fun PresetActionsDialog(preset: Preset, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        NamePromptDialog(
            title = "Rename preset",
            initial = preset.name,
            confirmLabel = "Rename",
            onDismiss = onDismiss,
            onConfirm = { name -> if (name.isNotBlank()) onRename(name) },
        )
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(Color(0xFF121212), RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(preset.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { renaming = true }) { Text("Rename", color = Color.White) }
            TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFEF5350)) }
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
        }
    }
}

@Composable
private fun NamePromptDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(Color(0xFF121212), RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
                TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirmLabel, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/**
 * The HSL tab: eight hue-band chips, each opening the same three hue/saturation/luminance
 * sliders for whichever band is selected.
 */
@Composable
fun HslPanel(
    hsl: HslAdjustments,
    onChange: (HslAdjustments) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(HueBand.RED) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HueBand.entries.toList(), key = { it.name }) { band ->
                val isSelected = band == selected
                val touched = !hsl[band].isIdentity
                Text(
                    band.label,
                    color = if (isSelected) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (touched) 0.28f else 0.12f),
                            RoundedCornerShape(50),
                        )
                        .clickableSelect { selected = band }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        val band = hsl[selected]
        LabelledSlider("Hue", band.hue) { onChange(hsl.with(selected, band.copy(hue = it))) }
        LabelledSlider("Saturation", band.saturation) { onChange(hsl.with(selected, band.copy(saturation = it))) }
        LabelledSlider("Luminance", band.luminance) { onChange(hsl.with(selected, band.copy(luminance = it))) }
    }
}

/** Tiny local alias so [HslPanel]'s chip row reads like every other chip row in this package. */
private fun Modifier.clickableSelect(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
