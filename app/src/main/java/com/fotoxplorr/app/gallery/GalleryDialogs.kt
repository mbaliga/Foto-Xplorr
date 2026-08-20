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
