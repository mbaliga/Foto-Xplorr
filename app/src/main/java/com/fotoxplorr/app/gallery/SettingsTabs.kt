package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The settings room's tabs (owner, 2026-08-14: *"The settings are pathetically few. And 'more
 * settings' is strange as well. Those things should be in tabs."*).
 *
 * Both halves of that are the same defect. The room used to be **two depths** — a compact panel,
 * and everything else behind an "All settings…" button — which is what made the second depth feel
 * strange: it was not a category, it was an overflow, and an overflow is what a surface grows
 * when it has no structure to put things in. Tabs give it that structure, so there is one depth
 * and every setting is two taps from any other.
 *
 * The compact panel's second half was never settings at all: it was navigation ("MORE" → Albums /
 * Discover / Library). It lives under [SettingsTab.LIBRARY] as a browse row rather than being
 * deleted, because those screens have no other way in.
 */
enum class SettingsTab(val label: String) {
    APPEARANCE("Appearance"),
    LIBRARY("Library"),
    PRIVACY("Privacy"),
    VIEWER("Viewer"),
    DATA("Data"),
    ABOUT("About"),
}

@Composable
fun SettingsTabsRoom(
    state: GalleryUiState,
    actions: GalleryActions,
    onOpenLegacyScreen: (LegacyScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(SettingsTab.APPEARANCE) }
    val preferences = state.preferences

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SettingsTab.entries.toList(), key = { it.name }) { entry ->
                val selected = entry == tab
                Text(
                    text = entry.label,
                    color = if (selected) Color.Black else Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(50),
                        )
                        .clickable { tab = entry }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (tab) {
                SettingsTab.APPEARANCE -> {
                    ChoiceRow(
                        "Theme",
                        ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        preferences.themeMode,
                        actions.onSetThemeMode,
                    )
                    ChoiceRow(
                        "Accent",
                        AccentPalette.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        preferences.accentPalette,
                        actions.onSetAccentPalette,
                    )
                    StepperRow(
                        label = "Grid columns",
                        value = preferences.gridColumns.toString(),
                        onDecrease = { actions.onSetGridColumns(preferences.gridColumns - 1) },
                        onIncrease = { actions.onSetGridColumns(preferences.gridColumns + 1) },
                        canDecrease = preferences.gridColumns > MIN_GRID_COLUMNS,
                        canIncrease = preferences.gridColumns < MAX_GRID_COLUMNS,
                    )
                }

                SettingsTab.LIBRARY -> {
                    ChoiceRow(
                        "Sort",
                        GallerySort.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        preferences.sort,
                        actions.onSetSort,
                    )
                    ChoiceRow(
                        "Group timeline by",
                        TimelineGrouping.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        preferences.timelineGrouping,
                        actions.onSetTimelineGrouping,
                    )
                    ChoiceRow(
                        "Opens on",
                        HyleDestination.entries.map { it to it.label },
                        preferences.defaultDestination,
                        actions.onSetDefaultDestination,
                    )
                    SwitchRow(
                        "Show videos",
                        "Include video files alongside photos.",
                        preferences.showVideos,
                        actions.onSetShowVideos,
                    )
                    SectionLabel("BROWSE")
                    // Navigation, not settings -- but these screens have no other entry point,
                    // so they are kept here rather than stranded.
                    LegacyScreen.entries.forEach { screen ->
                        Text(
                            screen.label,
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLegacyScreen(screen) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }

                SettingsTab.PRIVACY -> {
                    SwitchRow(
                        "Blur sensitive photos",
                        "In the grid only. A photo you have opened deliberately is never blurred.",
                        preferences.blurSensitive,
                        actions.onSetBlurSensitive,
                    )
                    SwitchRow(
                        "Hide sensitive photos",
                        "Leaves them out of the grid entirely. They stay in their own album.",
                        preferences.hideSensitive,
                        actions.onSetHideSensitive,
                    )
                    Text(
                        "Protected folders are set up from an album's own menu, and their " +
                            "passwords are never stored in these settings.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SettingsTab.VIEWER -> {
                    StepperRow(
                        label = "Slideshow interval",
                        value = "${preferences.slideshowIntervalSeconds}s",
                        onDecrease = { actions.onSetSlideshowInterval(preferences.slideshowIntervalSeconds - 1) },
                        onIncrease = { actions.onSetSlideshowInterval(preferences.slideshowIntervalSeconds + 1) },
                        canDecrease = preferences.slideshowIntervalSeconds > MIN_SLIDESHOW_INTERVAL_SECONDS,
                        canIncrease = preferences.slideshowIntervalSeconds < MAX_SLIDESHOW_INTERVAL_SECONDS,
                    )
                    SwitchRow(
                        "Shuffle slideshows",
                        "Play in a random order instead of the current sort order.",
                        preferences.slideshowShuffle,
                        actions.onSetSlideshowShuffle,
                    )
                    SwitchRow(
                        "Keep the screen on",
                        "While a photo or video is open. Costs battery, so it is off by default.",
                        preferences.keepScreenOn,
                        actions.onSetKeepScreenOn,
                    )
                    SwitchRow(
                        "Play videos automatically",
                        "Start a video as soon as it opens rather than waiting for play.",
                        preferences.autoplayVideos,
                        actions.onSetAutoplayVideos,
                    )
                }

                SettingsTab.DATA -> {
                    ActionText("Refresh library now", actions.onRefresh)
                    ActionText("Export metadata backup", actions.onExportMetadata)
                    ActionText("Import metadata backup", actions.onImportMetadata)
                    Text(
                        "A backup holds your collections, tags, favourites and sensitive marks — " +
                            "the things Foto Xplorr knows that the files themselves do not. It " +
                            "never contains photos.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SettingsTab.ABOUT -> {
                    LabelledValue("Version", com.fotoxplorr.app.BuildConfig.VERSION_NAME)
                    LabelledValue(
                        "Build",
                        if (com.fotoxplorr.app.BuildConfig.DEBUG) "Debug" else "Release",
                    )
                    SectionLabel("WHAT THIS BUILD CAN REACH")
                    Text(
                        text = OFFLINE_STATEMENT,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SectionLabel("OPEN SOURCE")
                    Text(
                        "Foto Xplorr uses AndroidX and Jetpack Compose, Coil, ML Kit and " +
                            "SQLite, each under its own licence. Recognition runs entirely on " +
                            "this device.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * What this build can actually do, stated where a user can check it.
 *
 * Written per flavour rather than as one hedged sentence, because "offline" is this app's central
 * claim and a claim the user cannot verify is only marketing. The offline build's manifest
 * genuinely carries no INTERNET permission -- the OS refuses it a socket -- and that is a
 * stronger statement than any promise about intent.
 */
private val OFFLINE_STATEMENT: String
    get() = "This build holds no INTERNET permission at all: Android itself will refuse it a " +
        "network connection, so nothing here can leave the device even by accident."

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.5f),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Text(value, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 16.dp).weight(1f)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(
                caption,
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A labelled row of mutually exclusive chips.
 *
 * Generic over the option type so every enum-backed preference uses the ONE control rather than
 * each growing its own. Default View previously existed twice, in two different idioms — dot rows
 * in the compact panel and chips in the full list — which is how a single preference ends up
 * looking like two different features.
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            items(options, key = { it.second }) { (value, text) ->
                val isSelected = value == selected
                Text(
                    text = text,
                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(50),
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The minus / value / plus control. Extracted because the same three-part pattern was written out
 * longhand for grid columns and again for the slideshow interval, and a stepper copied twice is a
 * stepper that will be fixed once.
 */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrease, enabled = canDecrease) {
                Icon(
                    Icons.Outlined.Remove,
                    contentDescription = "Fewer",
                    tint = Color.White.copy(alpha = if (canDecrease) 1f else 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                value,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onIncrease, enabled = canIncrease) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "More",
                    tint = Color.White.copy(alpha = if (canIncrease) 1f else 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
