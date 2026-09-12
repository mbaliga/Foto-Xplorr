@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.fileops.BulkRenameOutcome
import com.fotoxplorr.app.fileops.RenamePattern
import com.fotoxplorr.app.fileops.RenameSubject
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.MediaCollection
import kotlinx.coroutines.launch

@Composable
fun TextEntryDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialValue: String = "",
    suggestions: List<String> = emptyList(),
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(label) },
                    singleLine = true,
                )
                if (suggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { it }) { suggestion ->
                            AssistChip(
                                onClick = { value = suggestion },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CollectionPickerDialog(
    collections: List<MediaCollection>,
    onDismiss: () -> Unit,
    onCreateCollection: (String) -> Unit,
    onChoose: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(collections.isEmpty()) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to collection") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("New collection name") },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = { onCreateCollection(name.trim()) },
                    ) { Text("Create collection") }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(collections, key = { it.id }) { collection ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onChoose(collection.id) },
                            ) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text(collection.name)
                                    Text(
                                        "${collection.mediaIds.size} items",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { creating = !creating }) {
                Text(if (creating) "Choose existing" else "New collection")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PasswordDialog(
    title: String,
    confirmLabel: String,
    failureMessage: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (CharArray) -> Boolean,
    onSuccess: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !busy,
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    busy = true
                    scope.launch {
                        val success = runCatching { onConfirm(chars) }.getOrDefault(false)
                        busy = false
                        if (success) onSuccess() else error = failureMessage
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The pattern-input UI for bulk rename: one text pattern (see [RenamePattern] for the token
 * language), a starting number for `{counter}`, and a live preview against the actual photos
 * being renamed — so a typo in a token shows up as literal `{typo}` text in the preview *before*
 * forty files are renamed to something wrong, not after.
 */
@Composable
fun BulkRenameDialog(
    assets: List<MediaAsset>,
    onDismiss: () -> Unit,
    onConfirm: (pattern: String, startAt: Int) -> Unit,
) {
    var pattern by remember { mutableStateOf("{orig}") }
    var startAtText by remember { mutableStateOf("1") }
    val startAt = startAtText.toIntOrNull()

    // Only the first few previewed — the pattern is expanded fresh on every keystroke, and doing
    // that for all of (potentially) hundreds of selected photos on every recomposition is work
    // the dialog has no need to do just to show the user what the naming scheme looks like.
    val preview = remember(pattern, startAt, assets) {
        val at = startAt ?: return@remember emptyList()
        if (pattern.isBlank()) return@remember emptyList()
        val sample = assets.take(PREVIEW_COUNT)
        val subjects = sample.map { RenameSubject(it.displayName, it.dateTakenMillis, it.dateModifiedSeconds) }
        runCatching {
            RenamePattern.expand(pattern, subjects, at).mapIndexed { index, stem ->
                val extension = subjects[index].extension
                if (extension.isEmpty()) stem else "$stem.$extension"
            }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${assets.size} photos") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Naming pattern") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = startAtText,
                    onValueChange = { startAtText = it.filter(Char::isDigit).take(9) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Start counting at") },
                    isError = startAt == null,
                    singleLine = true,
                )
                Text(
                    "{counter} {counter:3} {orig} {yyyy} {yy} {MM} {dd} {HH} {mm} {ss} — " +
                        "the original extension is always kept.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (preview.isNotEmpty()) {
                    Text("Preview", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(preview) { name ->
                            Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        if (assets.size > preview.size) {
                            item {
                                Text(
                                    "… and ${assets.size - preview.size} more",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pattern.isNotBlank() && startAt != null,
                onClick = { onConfirm(pattern.trim(), startAt ?: 1) },
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How many expanded names [BulkRenameDialog] shows before collapsing the rest into "… and N more". */
private const val PREVIEW_COUNT = 6

/**
 * Shown while `MediaFileOperations.renameBatch` is actually running.
 *
 * No dismiss and no cancel: by the time this is on screen, some renames in the batch may already
 * be written to MediaStore, and offering a "Cancel" that does not actually stop in-flight
 * `ContentResolver.update` calls would be a control that lies about what it does.
 */
@Composable
fun BulkRenameProgressDialog(completed: Int, total: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Renaming photos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("$completed of $total")
            }
        },
        confirmButton = {},
    )
}

/**
 * What actually happened, honestly — including the failures.
 *
 * A silent "Renamed!" toast that does not mention the six photos Android refused to touch would
 * leave the user believing their whole selection was renamed when it was not; this lists exactly
 * which ones failed and why, the same standard [MediaFileOperations.renameBatch] itself holds by
 * returning [BulkRenameOutcome.failed] as a first-class result rather than an afterthought.
 */
@Composable
fun BulkRenameResultDialog(outcome: BulkRenameOutcome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (outcome.allSucceeded) {
                    "Renamed ${outcome.succeeded.size} photos"
                } else if (outcome.succeeded.isEmpty()) {
                    "Could not rename these photos"
                } else {
                    "Renamed ${outcome.succeeded.size} of ${outcome.attempted}"
                },
            )
        },
        text = {
            if (outcome.failed.isEmpty()) {
                Text("Every photo in the selection was renamed.")
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (outcome.succeeded.isEmpty()) {
                            "Android would not allow any of these to be renamed:"
                        } else {
                            "These could not be renamed:"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(outcome.failed, key = { it.first.id.value }) { (asset, reason) ->
                            Column {
                                Text(asset.displayName, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Every gallery setting, as a plain list.
 *
 * This used to be the body of a Material `AlertDialog`. The dialog is retired along with the
 * rest of the old chrome: settings are a *room* now (a surface parked off the right edge that
 * the home grid lifts and parts to reveal), and a modal window floating over that would be a
 * second, contradictory idea of where you are. Same content, no dialog — so it also inherits
 * the app's own dark theme instead of the platform's dialog surface.
 */
@Composable
fun GallerySettingsList(
    preferences: GalleryPreferencesState,
    actions: GalleryActions,
    modifier: Modifier = Modifier,
) {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SettingsSection("Sort order") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(GallerySort.entries, key = { it.name }) { sort ->
                                FilterChip(
                                    selected = preferences.sort == sort,
                                    onClick = { actions.onSetSort(sort) },
                                    label = { Text(sort.label()) },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsSection("Timeline grouping") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(TimelineGrouping.entries, key = { it.name }) { grouping ->
                                FilterChip(
                                    selected = preferences.timelineGrouping == grouping,
                                    onClick = { actions.onSetTimelineGrouping(grouping) },
                                    label = { Text(grouping.label()) },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsSection("Grid density") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                enabled = preferences.gridColumns > MIN_GRID_COLUMNS,
                                onClick = { actions.onSetGridColumns(preferences.gridColumns - 1) },
                            ) { Icon(Icons.Outlined.Remove, contentDescription = "Larger thumbnails") }
                            Text("${preferences.gridColumns} columns")
                            IconButton(
                                enabled = preferences.gridColumns < MAX_GRID_COLUMNS,
                                onClick = { actions.onSetGridColumns(preferences.gridColumns + 1) },
                            ) { Icon(Icons.Outlined.Add, contentDescription = "Smaller thumbnails") }
                        }
                    }
                }
                item { SettingsSwitch("Show videos", preferences.showVideos, actions.onSetShowVideos) }
                item { SettingsSwitch("Blur sensitive media", preferences.blurSensitive, actions.onSetBlurSensitive) }
                item { SettingsSwitch("Hide sensitive media from timeline", preferences.hideSensitive, actions.onSetHideSensitive) }
                item {
                    SettingsSection("Theme") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ThemeMode.entries, key = { it.name }) { mode ->
                                FilterChip(
                                    selected = preferences.themeMode == mode,
                                    onClick = { actions.onSetThemeMode(mode) },
                                    label = { Text(mode.label()) },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsSection("Accent") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AccentPalette.entries, key = { it.name }) { palette ->
                                FilterChip(
                                    selected = preferences.accentPalette == palette,
                                    onClick = { actions.onSetAccentPalette(palette) },
                                    label = { Text(palette.label()) },
                                )
                            }
                        }
                    }
                }
                item {
                    // The nine primary destinations from the mockups, not the four retired
                    // bottom-nav tabs this list used to offer.
                    SettingsSection("Default View") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(HyleDestination.entries, key = { it.name }) { candidate ->
                                FilterChip(
                                    selected = preferences.defaultDestination == candidate,
                                    onClick = { actions.onSetDefaultDestination(candidate) },
                                    label = { Text(candidate.label) },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsSection("Slideshow interval") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                enabled = preferences.slideshowIntervalSeconds > MIN_SLIDESHOW_INTERVAL_SECONDS,
                                onClick = { actions.onSetSlideshowInterval(preferences.slideshowIntervalSeconds - 1) },
                            ) { Icon(Icons.Outlined.Remove, null) }
                            Text("${preferences.slideshowIntervalSeconds} seconds")
                            IconButton(
                                enabled = preferences.slideshowIntervalSeconds < MAX_SLIDESHOW_INTERVAL_SECONDS,
                                onClick = { actions.onSetSlideshowInterval(preferences.slideshowIntervalSeconds + 1) },
                            ) { Icon(Icons.Outlined.Add, null) }
                        }
                    }
                }
            }
}

@Composable
private fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        com.fotoxplorr.app.hyle.HyleToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            description = title,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

private fun GallerySort.label() = when (this) {
    GallerySort.NEWEST -> "Newest"
    GallerySort.OLDEST -> "Oldest"
    GallerySort.NAME -> "Name"
    GallerySort.SIZE -> "Size"
}

private fun TimelineGrouping.label() = when (this) {
    TimelineGrouping.DAY -> "Day"
    TimelineGrouping.MONTH -> "Month"
    TimelineGrouping.NONE -> "None"
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun AccentPalette.label() = name.lowercase().replaceFirstChar { it.titlecase() }
