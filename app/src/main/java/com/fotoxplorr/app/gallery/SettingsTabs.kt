package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_LAST_GRID
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_MIN
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_STAMPS
import com.fotoxplorr.app.adaptive.zoomRungValue
import com.fotoxplorr.app.backup.BackupCopy
import com.fotoxplorr.app.backup.EncryptedBackupRows
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
import com.fotoxplorr.app.spatial.offline.OfflineMapsSettingsRow
import com.fotoxplorr.app.ui.BUILT_IN_THEMES
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.ThemeJsonResult
import com.fotoxplorr.app.ui.accentFor
import com.fotoxplorr.app.ui.builtInThemeJson
import com.fotoxplorr.app.ui.parseThemeJson
import com.fotoxplorr.app.ui.HelpCopy

/**
 * The Settings pane's tabs (owner, 2026-08-14: *"The settings are pathetically few. And 'more
 * settings' is strange as well. Those things should be in tabs."*).
 *
 * Both halves of that are the same defect. Settings used to be a room of **two depths** — a compact panel,
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
    /** Which tab opens first. The app starts on Appearance; screen renders pick others. */
    initialTab: SettingsTab = SettingsTab.APPEARANCE,
    /**
     * Draws the "Settings" title and clears the status bar. The Settings pane passes false: its
     * [com.fotoxplorr.app.ui.PaneScaffold] already owns both, and a second title and a second
     * inset would double them. Screen renders that call this directly keep the default.
     */
    showHeader: Boolean = true,
) {
    // Saved, so leaving Settings for a tab-specific screen and coming back (or rotating) lands on
    // the tab you were reading rather than on Appearance every time.
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val preferences = state.preferences
    // One stable sample for the whole session: a preview that reshuffled on every toggle would
    // make it impossible to see what the toggle actually changed. Drawn from the one visibility
    // filter (P0-01), so the settings preview can never show a locked, archived or hidden photo.
    val sampleAsset = remember(
        state.assets,
        state.library.archivedIds,
        state.sensitiveIds,
        state.lockedFolders,
        state.unlockedFolders,
        preferences.hideSensitive,
    ) {
        browsableAssets(
            assets = state.assets,
            archivedIds = state.library.archivedIds,
            sensitiveIds = state.sensitiveIds,
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            hideSensitive = preferences.hideSensitive,
        ).firstOrNull { !it.isVideo }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (showHeader) Modifier.statusBarsPadding().padding(top = 16.dp) else Modifier)
            .padding(horizontal = RoomStyle.GutterStart),
    ) {
        if (showHeader) {
            Text(
                "Settings",
                style = RoomStyle.Title,
                color = RoomStyle.Ink,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        // All nine at once. A scrolling row clipped at the screen edge ("Vie...") with no sign
        // that Viewer, Data, Background, Pro and About were off to the right.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsTab.entries.forEach { entry ->
                SettingsChip(
                    label = entry.label,
                    selected = entry == tab,
                    role = Role.Tab,
                    verticalPadding = 10.dp,
                    onClick = { tab = entry },
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            when (tab) {
                SettingsTab.APPEARANCE -> {
                    ChoiceRow(
                        "Theme",
                        ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        preferences.themeMode,
                        actions.onSetThemeMode,
                    )
                    ThemePaletteRow(
                        preferences = preferences,
                        onSetAccentPalette = actions.onSetAccentPalette,
                        onSetCustomTheme = actions.onSetCustomTheme,
                    )
                    // The rungs are 1..6 columns and then the stamps; a masonry grid has no stamps.
                    val topRung = if (preferences.fitToTile) ZOOM_RUNG_STAMPS else ZOOM_RUNG_LAST_GRID
                    val rung = preferences.gridZoomRung.coerceAtMost(topRung)
                    StepperRow(
                        label = "Grid zoom",
                        value = zoomRungValue(rung),
                        onDecrease = { actions.onSetGridZoomRung(rung - 1) },
                        onIncrease = { actions.onSetGridZoomRung(rung + 1) },
                        canDecrease = rung > ZOOM_RUNG_MIN,
                        canIncrease = rung < topRung,
                    )
                }

                SettingsTab.MEDIA -> {
                    if (state.partialMediaAccess) {
                        SectionLabel("MEDIA ACCESS", first = true)
                        Paragraph(
                            HelpCopy.Caption.PARTIAL_ACCESS,
                            strong = true,
                        )
                        ActionText("Manage selected photos and videos", actions.onManageSelectedMedia)
                    }
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
                        HelpCopy.Caption.FILL_TILE,
                        preferences.fitToTile,
                        actions.onSetFitToTile,
                    )
                    SwitchRow(
                        "Play animations",
                        HelpCopy.Caption.LOOP_ANIMATIONS,
                        preferences.loopAnimations,
                        actions.onSetLoopAnimations,
                    )
                    ChoiceRow(
                        "Long press does",
                        GridLongPress.entries.map { it to it.label },
                        preferences.gridLongPress,
                        actions.onSetGridLongPress,
                    )
                    Paragraph(HelpCopy.Caption.LONG_PRESS)
                    SectionLabel("SAVING EDITS")
                    ChoiceRow(
                        "Saving edits",
                        com.fotoxplorr.app.editor.EditorSaveMode.entries.map { it to it.label },
                        preferences.editorSaveMode,
                        actions.onSetEditorSaveMode,
                    )
                    Paragraph(
                        preferences.editorSaveMode.description,
                    )
                }

                SettingsTab.LIBRARY -> {
                    val horizontal = gridScrollsHorizontally(
                        preferences.scrollAxis, preferences.gridZoomRung, masonry = !preferences.fitToTile,
                    )
                    ChoiceRow(
                        "Sort",
                        GallerySort.entries.map { it to timelineSortLabel(it, horizontal) },
                        preferences.sort,
                        actions.onSetSort,
                    )
                    SwitchRow(
                        "Timeline",
                        HelpCopy.Caption.TIMELINE,
                        preferences.timelineOn,
                        actions.onSetTimelineOn,
                    )
                    ChoiceRow(
                        "Timeline edge",
                        TimelinePlacement.entries.map { it to it.label },
                        preferences.timelinePlacement,
                        actions.onSetTimelinePlacement,
                    )
                    ChoiceRow(
                        "Group timeline by",
                        TimelineGrouping.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        preferences.timelineGrouping,
                        actions.onSetTimelineGrouping,
                    )
                    ChoiceRow(
                        "Scroll direction",
                        ScrollAxis.entries.map { it to it.label },
                        preferences.scrollAxis,
                        actions.onSetScrollAxis,
                    )
                    Paragraph(HelpCopy.Caption.SCROLL_AXIS)
                    ChoiceRow(
                        "Open the app on",
                        HyleDestination.entries.map { it to it.label },
                        preferences.defaultDestination,
                        actions.onSetDefaultDestination,
                    )
                    SwitchRow(
                        "Show videos",
                        null,
                        preferences.showVideos,
                        actions.onSetShowVideos,
                    )
                    SectionLabel("BROWSE")
                    // Navigation, not settings -- but these screens have no other entry point,
                    // so they are kept here rather than stranded.
                    LegacyScreen.entries.forEach { screen ->
                        RoomRow(label = screen.label, onClick = { onOpenLegacyScreen(screen) })
                    }
                }

                SettingsTab.PRIVACY -> {
                    SwitchRow(
                        "Blur sensitive photos",
                        HelpCopy.Caption.BLUR_GRID_ONLY,
                        preferences.blurSensitive,
                        actions.onSetBlurSensitive,
                    )
                    SwitchRow(
                        "Hide sensitive photos",
                        HelpCopy.Caption.HIDE_FROM_GRID,
                        preferences.hideSensitive,
                        actions.onSetHideSensitive,
                    )
                    Paragraph(HelpCopy.Hint.PROTECTED_FOLDERS)
                    Paragraph(HelpCopy.Note.PROTECTED_NOT_ENCRYPTED)
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
                        HelpCopy.Caption.SHUFFLE,
                        preferences.slideshowShuffle,
                        actions.onSetSlideshowShuffle,
                    )
                    SwitchRow(
                        "Keep the screen on",
                        HelpCopy.Caption.KEEP_SCREEN_ON,
                        preferences.keepScreenOn,
                        actions.onSetKeepScreenOn,
                    )
                    SwitchRow(
                        "Play videos automatically",
                        HelpCopy.Caption.AUTOPLAY,
                        preferences.autoplayVideos,
                        actions.onSetAutoplayVideos,
                    )
                    SwitchRow(
                        "Filmstrip",
                        HelpCopy.Caption.FILMSTRIP,
                        preferences.showFilmstrip,
                        actions.onSetShowFilmstrip,
                    )
                }

                SettingsTab.DATA -> {
                    // Two named options, never one "backup" that could mean either. This one is the
                    // plain file; the encrypted whole-library backup gets its own section below it.
                    SectionLabel("BACKUP: TAGS AND COLLECTIONS", first = true)
                    Paragraph(HelpCopy.Hint.BACKUP, strong = true)
                    Paragraph(HelpCopy.Hint.BACKUP_NOT_ENCRYPTED)
                    ActionText("Save tags and collections to a file", actions.onExportMetadata)
                    ActionText("Restore tags and collections from a file", actions.onImportMetadata)
                    Paragraph(HelpCopy.Hint.BACKUP_RESTORE)

                    // The second option. Its rows and dialogs are self-contained (backup/BackupUi.kt),
                    // so no field was added to GalleryActions or GalleryUiState for it.
                    SectionLabel(BackupCopy.SECTION_LABEL)
                    Paragraph(HelpCopy.Hint.BACKUP_ENCRYPTED_ADDS, strong = true)
                    Paragraph(HelpCopy.Note.BACKUP_NO_RECOVERY)
                    EncryptedBackupRows()

                    SectionLabel("MAPS")
                    OfflineMapsSettingsRow()

                    SectionLabel("LIBRARY")
                    ActionText("Rescan for new photos", actions.onRefresh)
                    Paragraph(HelpCopy.Hint.RESCAN_AUTO)
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

                    SectionLabel("WHEN", first = true)
                    SwitchRow(
                        "Use background rules",
                        HelpCopy.Caption.RULES_SWITCH,
                        rules.enabled,
                        rulesStore::setEnabled,
                    )
                    SwitchRow(
                        "Only when the phone is idle",
                        HelpCopy.Caption.IDLE_RULE,
                        rules.requireIdle,
                        rulesStore::setRequireIdle,
                        enabled = rules.enabled,
                    )
                    SwitchRow(
                        "Only while charging",
                        HelpCopy.Caption.CHARGING_RULE,
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
                    Paragraph(HelpCopy.Note.BATTERY_FLOOR)

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
                    Paragraph(
                        if (rules.activeHoursStart == rules.activeHoursEnd) {
                            HelpCopy.Caption.HOURS_ALWAYS
                        } else if (rules.activeHoursStart > rules.activeHoursEnd) {
                            HelpCopy.overnight(
                                formatHourOfDay(rules.activeHoursStart),
                                formatHourOfDay(rules.activeHoursEnd),
                            )
                        } else {
                            HelpCopy.Caption.HOURS_SAME_DAY
                        },
                    )

                    SectionLabel("NETWORK")
                    SwitchRow(
                        "Only on Wi-Fi",
                        if (com.fotoxplorr.app.BuildConfig.NETWORK_FEATURES) {
                            "Skips a pass entirely while on mobile data."
                        } else {
                            HelpCopy.Caption.WIFI_OFFLINE
                        },
                        rules.onlyOnUnmetered,
                        rulesStore::setOnlyOnUnmetered,
                        enabled = rules.enabled && com.fotoxplorr.app.BuildConfig.NETWORK_FEATURES,
                    )

                    SectionLabel("RIGHT NOW")
                    Paragraph(
                        summarize(rules),
                        strong = true,
                    )
                    Text(
                        workStatus.describe(),
                        color = RoomStyle.Ink,
                        style = RoomStyle.Row,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Paragraph(HelpCopy.Caption.STATUS_CHECKED)
                }

                SettingsTab.PRO -> {
                    // Built the same way the share sheet's own Pro row is: read the entitlement
                    // straight off Context rather than threading it through GalleryUiState, so
                    // this section is a self-contained addition and does not require wiring
                    // Pro status through every screen that hosts SettingsTabsRoom.
                    val proContext = LocalContext.current
                    val entitlement = remember { LocalProEntitlement(proContext.applicationContext) }
                    val isPro by entitlement.isPro.collectAsState()

                    SectionLabel(if (isPro) "YOU HAVE PRO" else "FREE PLAN", first = true)
                    Paragraph(
                        if (isPro) {
                            HelpCopy.Caption.PRO_ON
                        } else {
                            HelpCopy.Caption.PRO_OFF
                        },
                        strong = true,
                    )

                    SectionLabel("WHAT PRO REMOVES")
                    Paragraph(HelpCopy.Caption.PRO_ONLY_MARK, strong = true)

                    if (!isPro) {
                        SectionLabel("UNLOCK")
                        ActionText("Unlock Pro", entitlement::recordUnlock)
                        Paragraph(
                            // Same honest disclosure as the share sheet's unlock action -- see
                            // ProEntitlement's KDoc for why there is no store, no charge and no
                            // receipt behind this button yet.
                            HelpCopy.Hint.BILLING,
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
                    Paragraph(
                        HelpCopy.Caption.SUPPORT,
                        strong = true,
                    )
                    ActionText(SUPPORT_EMAIL, onOpenSupport)

                    SectionLabel("MORE FROM A SYSTEM OF CELLS")
                    Paragraph(
                        HelpCopy.Caption.FAMILY,
                        strong = true,
                    )
                    ActionText("asystemofcells.com", onOpenMoreApps)

                    SectionLabel("WHAT THIS BUILD CAN REACH")
                    Paragraph(
                        text = if (com.fotoxplorr.app.BuildConfig.NETWORK_FEATURES) {
                            HelpCopy.Note.CONNECT_STATEMENT
                        } else {
                            HelpCopy.Note.OFFLINE_STATEMENT
                        },
                        strong = true,
                    )
                    SectionLabel("OPEN SOURCE")
                    Paragraph(
                        "Fotoz uses AndroidX and Jetpack Compose, Coil, ML Kit and " +
                            "SQLite, each under its own licence. Recognition runs entirely on " +
                            "this device.",
                        strong = true,
                    )
                }
            }
        }
    }
}

/** Where support mail goes (owner, 2026-08-15). */
const val SUPPORT_EMAIL = "fotoz@asystemofcells.com"

/**
 * A group's eyebrow, set off from the group above by a hairline.
 *
 * The spacing lives here and not in a blanket gap between every child: a heading that sits as far
 * below the previous group as it does above its own rows reads as floating between the two.
 * [first] drops the rule for the heading that opens a tab, where there is nothing to separate from.
 */
@Composable
private fun SectionLabel(text: String, first: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 0.dp)) {
        if (!first) RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow(text)
    }
}

/**
 * A line of explanation under a row or an eyebrow, in the rooms' caption type.
 *
 * [strong] is the sentence a section hangs on; the default is the footnote under it. Both are
 * above the rooms' faint ink on purpose -- these paragraphs are what the setting is, not decoration.
 */
@Composable
private fun Paragraph(text: String, strong: Boolean = false) {
    Text(
        text = text,
        color = if (strong) RoomStyle.InkMuted else RoomStyle.InkFaint,
        style = RoomStyle.Caption.copy(lineHeight = 18.sp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun LabelledValue(label: String, value: String) {
    RoomRow(
        label = label,
        trailing = { Text(value, color = RoomStyle.InkMuted, style = RoomStyle.Row) },
    )
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    RoomRow(label = label, onClick = onClick)
}

@Composable
private fun SwitchRow(
    label: String,
    caption: String?,
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
    RoomRow(
        label = label,
        caption = caption,
        enabled = enabled,
        modifier = Modifier
            // The whole row is the one switch, so a screen reader finds one node per setting (the
            // row used to be clickable AND wrap a toggle that was clickable, which announced two).
            // The toggle at its end is drawn only; it carries the state, the row carries the tap.
            // Off-limits is stated by the label's and the toggle's own disabled fades and by
            // nothing else. A further alpha over the whole row used to stack on those: the caption
            // fell to about 2.2:1 and the toggle's on and off states became indistinguishable,
            // which is the one thing a disabled switch still has to say.
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = {
            com.fotoxplorr.app.hyle.HyleToggle(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
    )
}

/**
 * One pill: a settings tab, an option of a [ChoiceRow], or an accent. Selected is the accent, so
 * the choice you made is the one thing on the pane wearing the colour you picked.
 */
@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    role: Role,
    onClick: () -> Unit,
    verticalPadding: Dp = 8.dp,
    swatch: Color? = null,
) {
    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else RoomStyle.InkMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            // First, so the pill is drawn at its own size and only the touch target grows to 48dp:
            // the chip is 33-37dp tall, under the minimum, and this does not change how it looks.
            .minimumInteractiveComponentSize()
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(50),
            )
            .selectable(selected = selected, role = role, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = verticalPadding),
    ) {
        if (swatch != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(swatch, CircleShape)
                    .border(1.dp, ink.copy(alpha = 0.6f), CircleShape),
            )
        }
        Text(
            text = label,
            color = ink,
            style = RoomStyle.Caption,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, color = RoomStyle.Ink, style = RoomStyle.Row)
        // Wraps, where it used to scroll: "Open the app on" has nine options and the ones past the
        // edge were simply not there.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            options.forEach { (value, text) ->
                SettingsChip(
                    label = text,
                    selected = value == selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

/**
 * The theme palette picker: the five bundled presets (now JSON-backed -- see `ThemeJson.kt`),
 * plus "Custom…" for a theme pasted or imported as JSON -- the owner's JSON-theming ask, applied
 * to this app's own accent palette.
 *
 * A built-in chip applies immediately, exactly as this row always worked -- nothing about
 * choosing VIOLET/OCEAN/FOREST/AMBER/MONOCHROME changed. "Custom…" is different on purpose: it
 * opens [CustomThemeJsonDialog], whose own doc covers the mandatory preview-before-apply flow.
 */
@Composable
private fun ThemePaletteRow(
    preferences: GalleryPreferencesState,
    onSetAccentPalette: (AccentPalette) -> Unit,
    onSetCustomTheme: (String) -> Unit,
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    val builtIns = remember { AccentPalette.entries.filter { it != AccentPalette.CUSTOM } }
    // Each chip previews the accent it will apply, in the mode the app is showing right now, so a
    // person can see Forest before choosing it. The mode is read off the live theme's own ground
    // (ThemeMode.SYSTEM resolves to light or dark there), not off the stored preference.
    val darkNow = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val customSwatch = remember(preferences.customThemeJson, darkNow) {
        (parseThemeJson(preferences.customThemeJson) as? ThemeJsonResult.Valid)?.spec?.accentFor(darkNow)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Accent", color = RoomStyle.Ink, style = RoomStyle.Row)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            builtIns.forEach { palette ->
                SettingsChip(
                    label = palette.name.lowercase().replaceFirstChar(Char::uppercase),
                    selected = preferences.accentPalette == palette,
                    role = Role.RadioButton,
                    onClick = { onSetAccentPalette(palette) },
                    swatch = BUILT_IN_THEMES[palette]?.accentFor(darkNow),
                )
            }
            SettingsChip(
                label = "Custom…",
                selected = preferences.accentPalette == AccentPalette.CUSTOM,
                role = Role.RadioButton,
                onClick = { showCustomDialog = true },
                swatch = customSwatch,
            )
        }
        if (preferences.accentPalette == AccentPalette.CUSTOM) {
            // Re-parsed here rather than trusted as already-valid: this reads back whatever is
            // actually persisted, which is the honest thing to show even in the (should-not-
            // happen) case that it is no longer parsable -- see resolveThemeSpec's own doc for
            // why FotoXplorrTheme itself falls back safely in that same case.
            val activeName = (parseThemeJson(preferences.customThemeJson) as? ThemeJsonResult.Valid)?.spec?.name
            Text(
                text = if (activeName != null) "Active: $activeName -- tap to edit" else "Tap to fix the active custom theme",
                color = RoomStyle.InkMuted,
                style = RoomStyle.Caption,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable(role = Role.Button) { showCustomDialog = true },
            )
        }
    }

    if (showCustomDialog) {
        CustomThemeJsonDialog(
            // A fresh custom theme starts from the bundled JSON of the accent in force -- a valid,
            // familiar template to edit rather than a blank box, so choosing Custom... while on
            // Ocean starts from Ocean. It is the SAME text BUILT_IN_THEMES itself is parsed from
            // (see builtInThemeJson's own doc), not a second copy of it; VIOLET is the fallback.
            initialJson = preferences.customThemeJson.ifBlank {
                builtInThemeJson(preferences.accentPalette)
                    ?: builtInThemeJson(AccentPalette.VIOLET).orEmpty()
            },
            onDismiss = { showCustomDialog = false },
            onApply = { json ->
                onSetCustomTheme(json)
                showCustomDialog = false
            },
        )
    }
}

/**
 * The minus / value / plus control. Extracted because the same three-part pattern was written out
 * longhand for the grid zoom and again for the slideshow interval, and a stepper copied twice is a
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
    RoomRow(
        label = label,
        enabled = canDecrease || canIncrease,
        trailing = {
            com.fotoxplorr.app.hyle.HyleStepper(
                label = label,
                value = value,
                onDecrease = onDecrease,
                onIncrease = onIncrease,
                canDecrease = canDecrease,
                canIncrease = canIncrease,
            )
        },
    )
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
        RoomEyebrow("PREVIEW")
        Box(
            Modifier
                .padding(top = 8.dp, bottom = 10.dp)
                .size(140.dp)
                .background(Color.Black, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (sample == null) {
                Text("No photos yet", color = RoomStyle.InkFaint, style = RoomStyle.Caption)
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
