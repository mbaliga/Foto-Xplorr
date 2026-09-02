package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import com.fotoxplorr.app.background.BackgroundScheduler
import com.fotoxplorr.app.background.BackgroundWorkStatusCenter
import com.fotoxplorr.app.background.BATTERY_PERCENT_STEP
import com.fotoxplorr.app.background.MAX_BATTERY_PERCENT_ALLOWED
import com.fotoxplorr.app.background.MAX_HOUR_OF_DAY
import com.fotoxplorr.app.background.MIN_BATTERY_PERCENT_ALLOWED
import com.fotoxplorr.app.background.WorkRulesStore
import com.fotoxplorr.app.background.describe
import com.fotoxplorr.app.background.formatHourOfDay
import com.fotoxplorr.app.background.summarize
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.pro.LocalProEntitlement

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
    MEDIA("Media"),
    LIBRARY("Library"),
    PRIVACY("Privacy"),
    VIEWER("Viewer"),
    DATA("Data"),
    BACKGROUND("Background"),
    PRO("Pro"),
    ABOUT("About"),
}

@Composable
fun SettingsTabsRoom(
    state: GalleryUiState,
    actions: GalleryActions,
    onOpenLegacyScreen: (LegacyScreen) -> Unit,
    onOpenSupport: () -> Unit,
    onOpenMoreApps: () -> Unit,
    modifier: Modifier = Modifier,
    /** Which tab opens first. The app always starts on Appearance; screen renders pick others. */
    initialTab: SettingsTab = SettingsTab.APPEARANCE,
) {
    var tab by remember { mutableStateOf(initialTab) }
    val preferences = state.preferences
    // One stable sample for the whole session: a preview that reshuffled on every toggle would
    // make it impossible to see what the toggle actually changed.
    val sampleAsset = remember(state.assets.firstOrNull()?.id) {
        state.assets.firstOrNull { !it.isVideo }
    }

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

                SettingsTab.MEDIA -> {
                    // WYSIWYG (owner, 2026-08-15: "I'd want settings to be as visual as
                    // possible"). The sample is drawn from the user's OWN library and rendered by
                    // the same MediaImage the grid uses, so "fit to tile" is demonstrated rather
                    // than described -- a switch labelled "fit to tile" tells you nothing about
                    // what your mosaic will look like afterwards.
                    TilePreview(
                        sample = sampleAsset,
                        fitToTile = preferences.fitToTile,
                        loopAnimations = preferences.loopAnimations,
                    )
                    SwitchRow(
                        "Fill the tile",
                        "Crops each photo to fill its square. Off shows the whole frame, letterboxed.",
                        preferences.fitToTile,
                        actions.onSetFitToTile,
                    )
                    SwitchRow(
                        "Play animations",
                        "GIFs and animated images move in the grid instead of showing a still frame.",
                        preferences.loopAnimations,
                        actions.onSetLoopAnimations,
                    )
                    SwitchRow(
                        "Peek on long press",
                        "Hold a photo to see it large without opening it. Off makes a long press start a selection instead.",
                        preferences.longPressPreview,
                        actions.onSetLongPressPreview,
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
                    SectionLabel("BACKUP")
                    Text(
                        "A backup holds your collections, tags, favourites and sensitive marks -- " +
                            "everything Foto Xplorr knows that the files themselves do not. It " +
                            "never contains your photos: those are already on your device, and " +
                            "copying them into a backup would only make a second place to lose " +
                            "them from.",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ActionText("Back up now", actions.onExportMetadata)
                    ActionText("Restore from a backup", actions.onImportMetadata)

                    SectionLabel("LIBRARY")
                    ActionText("Rescan for new photos", actions.onRefresh)
                    Text(
                        "Foto Xplorr watches for new photos on its own; this is for when you " +
                            "want to be certain.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SettingsTab.BACKGROUND -> {
                    // Built the same way the PRO tab reads LocalProEntitlement, above: straight
                    // off Context rather than threaded through GalleryUiState/GalleryActions, so
                    // this whole tab is a self-contained addition. It has to be -- GalleryUiState
                    // and GalleryActions live in GalleryScreen.kt, which this change does not own
                    // and other agents are editing concurrently right now.
                    val backgroundContext = LocalContext.current
                    val rulesStore = remember { WorkRulesStore(backgroundContext.applicationContext) }
                    val scheduler = remember { BackgroundScheduler(backgroundContext.applicationContext) }
                    val rules by rulesStore.observe().collectAsState()
                    val workStatus by BackgroundWorkStatusCenter.status.collectAsState()

                    // Re-arms the platform job when the rules CHANGE -- drop(1) skips the value
                    // already in force when the tab opens. FotoXplorrApplication.startBackgroundWork()
                    // armed that one at process start, and re-arming it here on every visit had a
                    // visible cost: reconcile() publishes Pending, so opening this tab to find out
                    // why indexing was blocked overwrote the very reason you came to read with
                    // "Scheduled. Waiting for the system…". This exists so an EDIT takes effect
                    // immediately rather than at next launch, and only for that.
                    LaunchedEffect(Unit) {
                        snapshotFlow { rules }.drop(1).collect { scheduler.reconcile(it) }
                    }

                    SectionLabel("WHEN")
                    SwitchRow(
                        "Use background rules",
                        "On, the options below decide when indexing and other heavy passes run. " +
                            "Off, they run whenever the system schedules them, with nothing held back.",
                        rules.enabled,
                        rulesStore::setEnabled,
                    )
                    SwitchRow(
                        "Only when the phone is idle",
                        "Android decides exactly when \"idle\" starts, and can hold this off for " +
                            "a long time -- sometimes hours -- if it is being conservative about " +
                            "battery. That is the platform's own guarantee, not a number this app " +
                            "controls.",
                        rules.requireIdle,
                        rulesStore::setRequireIdle,
                        enabled = rules.enabled,
                    )
                    SwitchRow(
                        "Only while charging",
                        "Waits for the charger before spending battery on a long pass.",
                        rules.requireCharging,
                        rulesStore::setRequireCharging,
                        enabled = rules.enabled,
                    )
                    StepperRow(
                        label = "Minimum battery",
                        value = "${rules.minBatteryPercent}%",
                        onDecrease = {
                            rulesStore.setMinBatteryPercent(rules.minBatteryPercent - BATTERY_PERCENT_STEP)
                        },
                        onIncrease = {
                            rulesStore.setMinBatteryPercent(rules.minBatteryPercent + BATTERY_PERCENT_STEP)
                        },
                        canDecrease = rules.enabled && rules.minBatteryPercent > MIN_BATTERY_PERCENT_ALLOWED,
                        canIncrease = rules.enabled && rules.minBatteryPercent < MAX_BATTERY_PERCENT_ALLOWED,
                    )
                    Text(
                        "Charging counts as \"enough battery\" on its own, whatever this is set to. " +
                            "Android also will not wake a background pass below roughly 15-20% " +
                            "battery, however low you set this -- that floor is the platform's, " +
                            "not this app's, and it cannot be turned off from here.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    SectionLabel("ACTIVE HOURS")
                    StepperRow(
                        label = "From",
                        value = formatHourOfDay(rules.activeHoursStart),
                        onDecrease = {
                            rulesStore.setActiveHoursStart((rules.activeHoursStart - 1).mod(MAX_HOUR_OF_DAY + 1))
                        },
                        onIncrease = {
                            rulesStore.setActiveHoursStart((rules.activeHoursStart + 1).mod(MAX_HOUR_OF_DAY + 1))
                        },
                        canDecrease = rules.enabled,
                        canIncrease = rules.enabled,
                    )
                    StepperRow(
                        label = "Until",
                        value = formatHourOfDay(rules.activeHoursEnd),
                        onDecrease = {
                            rulesStore.setActiveHoursEnd((rules.activeHoursEnd - 1).mod(MAX_HOUR_OF_DAY + 1))
                        },
                        onIncrease = {
                            rulesStore.setActiveHoursEnd((rules.activeHoursEnd + 1).mod(MAX_HOUR_OF_DAY + 1))
                        },
                        canDecrease = rules.enabled,
                        canIncrease = rules.enabled,
                    )
                    Text(
                        if (rules.activeHoursStart == rules.activeHoursEnd) {
                            "Start and end are the same hour, which means always -- there is no " +
                                "window to wait for."
                        } else if (rules.activeHoursStart > rules.activeHoursEnd) {
                            "Wraps past midnight: covers ${formatHourOfDay(rules.activeHoursStart)} " +
                                "through to ${formatHourOfDay(rules.activeHoursEnd)} the next morning."
                        } else {
                            "A single window within one day."
                        },
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    SectionLabel("NETWORK")
                    SwitchRow(
                        "Only on Wi-Fi",
                        if (com.fotoxplorr.app.BuildConfig.NETWORK_FEATURES) {
                            "Skips a pass entirely while on mobile data."
                        } else {
                            "Not available in this build -- Foto Xplorr's offline build has no " +
                                "network connection of any kind, so there is no metered connection " +
                                "to avoid."
                        },
                        rules.onlyOnUnmetered,
                        rulesStore::setOnlyOnUnmetered,
                        enabled = rules.enabled && com.fotoxplorr.app.BuildConfig.NETWORK_FEATURES,
                    )

                    SectionLabel("RIGHT NOW")
                    Text(
                        summarize(rules),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        workStatus.describe(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Checked roughly every half hour while background rules are on, not " +
                            "watched continuously -- this can run a little behind what the phone " +
                            "is actually doing this second.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SettingsTab.PRO -> {
                    // Built the same way the share sheet's own Pro row is: read the entitlement
                    // straight off Context rather than threading it through GalleryUiState, so
                    // this section is a self-contained addition and does not require wiring
                    // Pro status through every screen that hosts SettingsTabsRoom.
                    val proContext = LocalContext.current
                    val entitlement = remember { LocalProEntitlement(proContext.applicationContext) }
                    val isPro by entitlement.isPro.collectAsState()

                    SectionLabel(if (isPro) "YOU HAVE PRO" else "FREE PLAN")
                    Text(
                        if (isPro) {
                            "Every share leaves the Foto Xplorr mark off. Thank you for " +
                                "supporting the app."
                        } else {
                            "Every share carries a small Foto Xplorr mark in the corner, " +
                                "unless you unlock Pro."
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    SectionLabel("WHAT PRO REMOVES")
                    Text(
                        "The Foto Xplorr mark that shared and exported photos carry by default " +
                            "-- the small signature bottom-right. Nothing else changes: Pro is " +
                            "not a paywall on any feature, only on that one mark.",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (!isPro) {
                        SectionLabel("UNLOCK")
                        ActionText("Unlock Pro", entitlement::recordUnlock)
                        Text(
                            // Same honest disclosure as the share sheet's unlock action -- see
                            // ProEntitlement's KDoc for why there is no store, no charge and no
                            // receipt behind this button yet.
                            "This build doesn't charge anything yet -- unlocking here just " +
                                "remembers Pro on this device, the same way it will once real " +
                                "billing is wired in.",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                SettingsTab.ABOUT -> {
                    LabelledValue("Version", com.fotoxplorr.app.BuildConfig.VERSION_NAME)
                    LabelledValue(
                        "Build",
                        if (com.fotoxplorr.app.BuildConfig.DEBUG) "Debug" else "Release",
                    )

                    SectionLabel("SUPPORT")
                    Text(
                        "Something wrong, or an idea? Write to us -- a real person reads it.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ActionText(SUPPORT_EMAIL, onOpenSupport)

                    SectionLabel("MORE FROM A SYSTEM OF CELLS")
                    Text(
                        "Foto Xplorr is one of a family of apps that share the same navigation " +
                            "and the same refusal to phone home.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ActionText("asystemofcells.com", onOpenMoreApps)

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
/** Where support mail goes (owner, 2026-08-15). */
const val SUPPORT_EMAIL = "fotoz@asystemofcells.com"

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
    // Defaulted so every existing call site (all of them switches that are always interactive)
    // compiles unchanged. Added for the BACKGROUND tab's "Only on Wi-Fi" row, which is a real
    // control in the connect flavour and a permanently-inapplicable one in offline -- disabling
    // it in place, with a caption saying why, is the "say so" this codebase's honesty rule asks
    // for; a switch that merely LOOKED interactive but silently did nothing would be worse than
    // either removing it or explaining it.
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 16.dp).weight(1f)) {
            Text(
                label,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                caption,
                color = Color.White.copy(alpha = if (enabled) 0.55f else 0.35f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        com.fotoxplorr.app.hyle.HyleToggle(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            description = label,
        )
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
 *
 * The control itself is [com.fotoxplorr.app.hyle.HyleStepper] (owner, 2026-09-01: *"As for the
 * internals like the binary toggles, please use Hyle and do not invent buttons."*). This row used
 * to draw two Material `IconButton`s around bare `+` / `-` glyphs, which was the last Material
 * chrome on a screen whose every other control is Hyle. The row's job is only the label, in the
 * room's own white type, exactly as [SwitchRow] does around its toggle.
 *
 * The label dims with the control for the same reason [SwitchRow]'s does: the BACKGROUND tab hands
 * both ends `canDecrease = canIncrease = false` while the whole feature is switched off, and a
 * full-strength label over a grey control claims the row is live when it is not.
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
        Text(
            label,
            color = Color.White.copy(alpha = if (canDecrease || canIncrease) 1f else 0.4f),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 16.dp).weight(1f),
        )
        com.fotoxplorr.app.hyle.HyleStepper(
            label = label,
            value = value,
            onDecrease = onDecrease,
            onIncrease = onIncrease,
            canDecrease = canDecrease,
            canIncrease = canIncrease,
        )
    }
}

/**
 * A live sample tile, rendered exactly as the grid would render it.
 *
 * The whole point of a visual setting: this is not a picture OF the feature, it is the feature,
 * drawn by the same [com.fotoxplorr.app.media.MediaImage] the mosaic uses with the same flags.
 * If it looks right here it will look right there, because it is the same code path.
 */
@Composable
private fun TilePreview(sample: MediaAsset?, fitToTile: Boolean, loopAnimations: Boolean) {
    Column {
        Text("PREVIEW", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(140.dp)
                .background(Color.Black, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (sample == null) {
                Text(
                    "No photos yet",
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                com.fotoxplorr.app.media.MediaImage(
                    asset = sample,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (fitToTile) ContentScale.Crop else ContentScale.Fit,
                    animate = loopAnimations,
                )
            }
        }
    }
}
