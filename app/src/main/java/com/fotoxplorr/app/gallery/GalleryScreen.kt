@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.fotoxplorr.app.gallery

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import com.fotoxplorr.app.R
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.navigationOnlyWhen
import com.fotoxplorr.app.ui.RoomGeography
import com.fotoxplorr.app.ui.RoomKind
import com.fotoxplorr.app.ui.roomSlots
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.hyle.ActivityShade
import com.fotoxplorr.app.hyle.BackgroundActivity
import com.fotoxplorr.app.hyle.FloatingPillControl
import com.fotoxplorr.app.hyle.MapToggleFab
import com.fotoxplorr.app.hyle.SEARCH_FAB_DP
import com.fotoxplorr.app.hyle.SEARCH_FAB_MARGIN_BOTTOM
import com.fotoxplorr.app.hyle.SEARCH_FAB_MARGIN_END
import com.fotoxplorr.app.hyle.SearchFab
import com.fotoxplorr.app.hyle.PILL_DESIGN_H
import com.fotoxplorr.app.hyle.SelectionToolbarShape
import com.fotoxplorr.app.hyle.SelectionTrashShape
import com.fotoxplorr.app.hyle.ShadeState
import com.fotoxplorr.app.hyle.TOOLBAR_DESIGN_H
import com.fotoxplorr.app.hyle.TOOLBAR_DESIGN_W
import com.fotoxplorr.app.hyle.TRASH_DESIGN_H
import com.fotoxplorr.app.hyle.TRASH_DESIGN_W
import com.fotoxplorr.app.hyle.shadeHeight
import androidx.compose.foundation.layout.offset
import dev.aarso.cellshell.EdgeTimelineScrubber
import dev.aarso.cellshell.ShakeToRefresh
import dev.aarso.cellshell.ParkStyle
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.recognition.RecognitionIndex
import com.fotoxplorr.app.recognition.RecognitionProgress
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.PhotoMapPane
import com.fotoxplorr.app.spatial.SpatialExperience
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.organize.GridIndexMap
import com.fotoxplorr.core.search.ParsedQuery
import com.fotoxplorr.core.search.SearchDocument
import com.fotoxplorr.core.search.matchesQuery
import com.fotoxplorr.core.search.parseSearchQuery
import kotlinx.coroutines.launch
// ---- adaptive package: window sizing, the pinch/scroll zoom rungs, and keyboard shortcuts.
// See each file's own doc for why this stays pure Kotlin with no Compose/Android import of its
// own -- the mapping into real Compose types (Key, WindowMetrics, ParkStyle) happens only here,
// at this call site, same as this file already does for `dev.aarso.cellshell`'s ParkStyle.
import com.fotoxplorr.app.adaptive.ChromeMotion
import com.fotoxplorr.app.adaptive.GalleryShortcut
import com.fotoxplorr.app.adaptive.GalleryShortcutKey
import com.fotoxplorr.app.adaptive.NavRailPresentation
import com.fotoxplorr.app.adaptive.chromeMotionFor
import com.fotoxplorr.app.adaptive.galleryShortcutFor
import com.fotoxplorr.app.adaptive.navRailPresentation
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_LAST_GRID
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_STAMPS
import com.fotoxplorr.app.adaptive.pinchStep
import com.fotoxplorr.app.adaptive.stepRung
import com.fotoxplorr.app.adaptive.wrapRung
import com.fotoxplorr.app.adaptive.zoomRungLabel
import com.fotoxplorr.app.adaptive.windowSizeClassOf
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import com.fotoxplorr.app.ui.HelpCopy

/**
 * The app's real background jobs, as the shade's list.
 *
 * Pure and separate from the composable so the mapping can be asserted: which jobs appear, in what
 * order, and — the part worth pinning — that a FINISHED job does not linger in the list. A shade
 * showing a completed scan for ever is how a status surface becomes furniture nobody reads.
 *
 * Order is stable and deliberate: the scan first, because everything else depends on it having
 * run. The shade's expanded state gives its first entry the hero panel, so this order decides
 * which job gets the room.
 */
internal fun buildBackgroundActivities(state: GalleryUiState): List<BackgroundActivity> = buildList {
    when (val scan = state.scanState) {
        is ScanState.Scanning -> add(
            BackgroundActivity(
                id = "scan",
                kind = ActivityKind.SCANNING,
                completed = scan.scanned,
                total = scan.discovered,
            ),
        )
        is ScanState.Error -> add(
            BackgroundActivity(id = "scan", kind = ActivityKind.SCANNING, error = scan.message),
        )
        // Idle and Complete are not activities. "Finished" is the absence of a row, not a row
        // saying finished.
        else -> Unit
    }

    val recognition = state.recognitionProgress
    if (recognition.running || recognition.message != null) {
        add(
            BackgroundActivity(
                id = "recognition",
                kind = ActivityKind.RECOGNISING,
                completed = recognition.completed,
                total = recognition.total,
                error = recognition.message,
            ),
        )
    }
}

/** One geo index for the whole app; see [GalleryScreen]'s parameter of the same name. */
@Composable
fun rememberGeoRepository(): GeoMetadataRepository {
    val context = LocalContext.current
    return remember(context) { GeoMetadataRepository(context.applicationContext) }
}

data class GalleryUiState(
    val assets: List<MediaAsset>,
    val favoriteIds: Set<MediaId>,
    val sensitiveIds: Set<MediaId>,
    val lockedFolders: Set<String>,
    val unlockedFolders: Set<String>,
    val library: LibraryState,
    val permissionGranted: Boolean,
    /** Android 14+ selected-photos grant rather than full-library access. */
    val partialMediaAccess: Boolean = false,
    /** True once the media permission has been asked for and Android will not show its own
     *  rationale again -- Android's own signal for "the user said no and checked don't ask
     *  again" (or, on some OEM skins, no again at all). The empty state's button relabels itself
     *  to "Open app settings" rather than requesting a permission Android will silently refuse
     *  to even show a dialog for. */
    val mediaPermissionPermanentlyDenied: Boolean = false,
    val scanState: ScanState,
    val preferences: GalleryPreferencesState,
    /** On-device recognition results backing the Pets / People / Identity destinations. */
    val recognition: RecognitionIndex = RecognitionIndex.EMPTY,
    val recognitionProgress: RecognitionProgress = RecognitionProgress(),
    /**
     * Which ids actually animate, sniffed from real bytes (P0-14) rather than assumed from MIME
     * type -- replaces the deleted [com.fotoxplorr.app.media.MediaAsset.isAnimated] everywhere
     * this state flows: the Animated album, `is:animated` search, and which grid tiles decode
     * through the animated path at all.
     */
    val animatedIds: Set<MediaId> = emptySet(),
    /**
     * A search the user asked for from somewhere outside the grid — today, the viewer's
     * "Search inside this photo" card (see [com.fotoxplorr.app.lens.LensCard]).
     *
     * A one-shot value rather than the search field's actual contents: the field is the
     * browser's own state and stays that way, because making it a hoisted parameter would mean
     * every keystroke in it round-tripped through the activity. This carries an *instruction*
     * ("open search on this text"), which the browser obeys once and then reports back through
     * [GalleryActions.onPendingSearchConsumed]. Without that acknowledgement the instruction
     * would still be sitting here on the next recomposition, and typing over the seeded text
     * would snap back to it.
     */
    val pendingSearch: String? = null,
)

data class GalleryActions(
    val onRequestPermission: () -> Unit,
    val onRefresh: () -> Unit,
    val onSetSort: (GallerySort) -> Unit,
    /** Remembers how zoomed the grid is (rungs 1..6 are columns, 7 the stamps) -- see `GridZoom.kt`. */
    val onSetGridZoomRung: (Int) -> Unit = {},
    val onSetBlurSensitive: (Boolean) -> Unit,
    val onSetHideSensitive: (Boolean) -> Unit,
    val onSetShowVideos: (Boolean) -> Unit,
    val onSetTimelineGrouping: (TimelineGrouping) -> Unit,
    /** Turns the timeline (the edge strip, its gutter and the column rungs' headers) on or off. */
    val onSetTimelineOn: (Boolean) -> Unit = {},
    /** Which edge the timeline strip sits on. */
    val onSetTimelinePlacement: (TimelinePlacement) -> Unit = {},
    /** Which way the photos scroll (down or across); the stamps and masonry stay vertical. */
    val onSetScrollAxis: (ScrollAxis) -> Unit = {},
    val onSetThemeMode: (ThemeMode) -> Unit,
    val onSetAccentPalette: (AccentPalette) -> Unit,
    /** Applies validated custom theme JSON -- see `ThemeJson.kt` and `SettingsTabs.kt`'s
     *  "Custom…" flow, whose confirm dialog is what actually validates it before this is ever
     *  called. */
    val onSetCustomTheme: (String) -> Unit,
    val onSetSlideshowInterval: (Int) -> Unit,
    val onSetDefaultDestination: (HyleDestination) -> Unit,
    val onSetKeepScreenOn: (Boolean) -> Unit,
    val onSetSlideshowShuffle: (Boolean) -> Unit,
    val onSetAutoplayVideos: (Boolean) -> Unit,
    val onSetFitToTile: (Boolean) -> Unit,
    val onSetLoopAnimations: (Boolean) -> Unit,
    /** What a hold on a grid tile does -- see [GridLongPress]. */
    val onSetGridLongPress: (GridLongPress) -> Unit = {},
    /** Neighbouring photos along the bottom of an open photo -- surfaced in both the viewer's
     *  own settings room and Settings › Viewer, so it needs to be reachable from here too. */
    val onSetShowFilmstrip: (Boolean) -> Unit = {},
    /** Open one photo or video in the editor, as a grid hold set to Edit asks. Refused while an
     *  earlier edit is still being saved; the host says so itself. */
    val onEditAsset: (MediaAsset) -> Unit = {},
    /** What the editor's Save does by default -- see [com.fotoxplorr.app.editor.EditorSaveMode]. */
    val onSetEditorSaveMode: (com.fotoxplorr.app.editor.EditorSaveMode) -> Unit = {},
    /** Kick off (or resume) the on-device recognition pass. */
    val onIndexRecognition: () -> Unit,
    val onProtectFolder: suspend (String, CharArray) -> Result<Unit>,
    val onUnlockFolder: suspend (String, CharArray) -> Boolean,
    val onLockFolder: (String) -> Unit,
    val onRemoveFolderProtection: suspend (String, CharArray) -> Boolean,
    val onSetFavorite: (Set<MediaId>, Boolean) -> Unit,
    val onSetSensitive: (Set<MediaId>, Boolean) -> Unit,
    val onSetArchived: (Set<MediaId>, Boolean) -> Unit,
    val onShare: (List<MediaAsset>) -> Unit,
    val onShareClean: (List<MediaAsset>) -> Unit,
    val onCopyToFolder: (List<MediaAsset>) -> Unit,
    val onMoveToFolder: (List<MediaAsset>) -> Unit,
    val onRenameAsset: (MediaAsset, String) -> Unit,
    val onMoveToTrash: (List<MediaAsset>) -> Unit,
    val onRestore: (List<MediaAsset>) -> Unit,
    val onDeletePermanently: (List<MediaAsset>) -> Unit,
    val onCreateCollection: (String) -> String?,
    val onRenameCollection: (String, String) -> Unit,
    val onDeleteCollection: (String) -> Unit,
    val onAddToCollection: (String, Set<MediaId>) -> Unit,
    val onRemoveFromCollection: (String, Set<MediaId>) -> Unit,
    val onAddTag: (Set<MediaId>, String) -> Unit,
    val onRemoveTag: (Set<MediaId>, String) -> Unit,
    /** Pack the given photos into one archive and offer it to the share sheet. */
    val onExportZip: (List<MediaAsset>) -> Unit,
    val onExportMetadata: () -> Unit,
    val onImportMetadata: () -> Unit,
    val onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    val onStartSlideshow: (List<MediaAsset>) -> Unit,
    /** Clears [GalleryUiState.pendingSearch] once the browser has acted on it. */
    val onPendingSearchConsumed: () -> Unit,
    /**
     * "No, keep this one" in the archive review queue. Remembered permanently — see
     * [com.fotoxplorr.app.organize.LibraryStore.rejectArchiveSuggestions] — so the same photo is
     * never offered again. Declining a suggestion has to stick, or the queue becomes a thing you
     * dismiss the same items out of every time you open it.
     */
    val onRejectArchiveSuggestions: (Set<MediaId>) -> Unit,
    /** Reopens Android's media grant sheet so a partial-access user can change selection. */
    val onManageSelectedMedia: () -> Unit = {},
)

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    actions: GalleryActions,
    /**
     * Shared with the viewer, which writes hand-placed locations into it. Two instances over the
     * same database would each hold their own StateFlow, so a pin dropped in the viewer would not
     * appear on the map until the app was restarted.
     */
    geoRepository: GeoMetadataRepository = rememberGeoRepository(),
) {
    val context = LocalContext.current
    val geoState by geoRepository.observe().collectAsStateWithLifecycle()
    val spatialScope = rememberCoroutineScope()
    // Index input: EVERY asset, trashed included (Phase 1 owner decision 1, 24 Sep 2026,
    // reversing P0-01's own trashed exclusion here) -- a photo trashed before its location was
    // ever indexed must not sit permanently unindexed while trashed, and a still-locked or hidden
    // photo's location is already indexed so unlocking the folder never needs a re-index of its
    // own. Display filtering (what Places/the map/the 3D scenes actually draw) stays entirely in
    // `spatialDisplayAssets` below, per the same decision's own "display filtering stays in the
    // UI" instruction -- this list is never rendered directly.
    val spatialIndexInput = state.assets
    // Display list: what Places, the map, the compass and the 3D scenes actually draw. Must go
    // through the one visibility filter, or a locked, archived or hidden photo leaks onto them.
    val spatialDisplayAssets = remember(
        state.assets,
        state.library.archivedIds,
        state.sensitiveIds,
        state.lockedFolders,
        state.unlockedFolders,
        state.preferences.hideSensitive,
    ) {
        browsableAssets(
            assets = state.assets,
            archivedIds = state.library.archivedIds,
            sensitiveIds = state.sensitiveIds,
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            hideSensitive = state.preferences.hideSensitive,
        )
    }

    CompositionLocalProvider(
        LocalSpatialExperience provides SpatialExperience(
            assets = spatialDisplayAssets,
            indexInput = spatialIndexInput,
            geoState = geoState,
            onIndexLocations = {
                spatialScope.launch {
                    geoRepository.indexMissing(spatialIndexInput)
                    // Trashed included (pruneTo's own contract): a trashed-but-not-yet-purged
                    // photo is still in the catalogue and must keep its location row.
                    geoRepository.pruneTo(state.assets.mapTo(mutableSetOf()) { it.id })
                }
            },
            onOpenAsset = actions.onOpenAsset,
        ),
    ) {
        when {
            !state.permissionGranted -> GalleryEmptyState(
                title = "Your gallery stays on this device",
                message = if (state.mediaPermissionPermanentlyDenied) {
                    HelpCopy.Hint.PERMISSION_DENIED
                } else {
                    HelpCopy.Hint.PERMISSION_ASK
                },
                actionLabel = if (state.mediaPermissionPermanentlyDenied) "Open app settings" else "Choose media",
                onAction = actions.onRequestPermission,
            )
            state.assets.isEmpty() && state.scanState is ScanState.Scanning -> GalleryEmptyState(
                title = "Building your library",
                message = HelpCopy.Hint.SCANNING,
                progress = true,
            )
            state.assets.isEmpty() && state.scanState is ScanState.Error -> GalleryEmptyState(
                title = "Could not scan media",
                message = state.scanState.message,
                actionLabel = "Try again",
                onAction = actions.onRefresh,
            )
            state.assets.isEmpty() -> GalleryEmptyState(
                title = "No media found",
                message = HelpCopy.Hint.NOTHING_FOUND,
                actionLabel = "Scan again",
                onAction = actions.onRefresh,
            )
            else -> GalleryBrowser(state, actions)
        }
    }
}

/**
 * Drill-down routes *below* a destination. The nine top-level destinations are not routes:
 * they are the primary IA and live in the slide-in rail, so switching between them never
 * pushes anything.
 */
sealed interface BrowserRoute {
    data object Root : BrowserRoute
    data class DeviceAlbum(val key: String, val name: String) : BrowserRoute
    data class Collection(val id: String, val name: String) : BrowserRoute
    data class Smart(val album: SmartAlbum) : BrowserRoute
    data class Tag(val tag: String) : BrowserRoute
}

private enum class PasswordAction { PROTECT, UNLOCK, REMOVE }

private data class PasswordRequest(
    val action: PasswordAction,
    val folderKey: String,
    val folderName: String,
)

/**
 * The app shell, on the constellation's spatial navigation.
 *
 * The primary IA is the nine-category rail (Pets / People / Identity / Screenshots / Photos /
 * Videos / Favourites / Places / Protected), and it is a **room**: a surface parked off the
 * left edge that the grid lifts and parts to reveal. The other three edges are rooms too, by
 * [com.fotoxplorr.app.ui.RoomGeography]: the view's details at the top, its actions at the bottom (a short pull stops on a strip of
 * quick actions, a longer one opens them all), its view options at the right. None of them is a screen — opening one pushes no back-stack entry, the grid stays
 * alive and visible behind it, and dragging it back is the way out. The motion comes from
 * `dev.aarso:cell-shell`, which every app in the constellation now shares, because the owner
 * asked for one navigation feel everywhere and the only way to guarantee that is one
 * implementation.
 *
 * What this replaced, and why none of it survived:
 *  - Two nested `SlideInPanel`s. A slide-over is a different idea: it covers the screen you
 *    were on. The owner was explicit that the current view should *move and swivel away*, and
 *    that the slide-over was wrong.
 *  - The hamburger and the `CenterAlignedTopAppBar` behind it. With the rail an edge away, a
 *    button whose only job was to open it is chrome earning nothing; the header shrinks to the
 *    title and the two actions that are genuinely per-view.
 *  - The Material settings `AlertDialog`. Settings take over the pane the photos are in now, in
 *    the app's own theme — a modal window floating over a room is two contradictory ideas of
 *    where you are, and it was also the light-themed screen inside a black app the owner reported.
 *
 * The pull-down belongs to the details room, so no other gesture may claim it — which is why
 * refresh is [ShakeToRefresh] and not a pull.
 */
@Composable
private fun GalleryBrowser(
    state: GalleryUiState,
    actions: GalleryActions,
) {
    // rememberSaveable, not remember: which destination and drill-down route you were looking at,
    // what you had searched, and what was selected are exactly the kind of thing a process death
    // (not just a rotation) must not silently discard -- reopening the app to a killed process
    // used to always land back on the default destination with the selection gone, indistinguishable
    // from having navigated there on purpose. BrowserRoute and GallerySelection need custom Savers
    // (see BrowserRouteCodec.kt) since neither is one of rememberSaveable's built-in trusted types;
    // HyleDestination (an enum, therefore Serializable), String and Boolean need none.
    var destination by rememberSaveable { mutableStateOf(state.preferences.defaultDestination) }
    var route by rememberSaveable(stateSaver = BrowserRouteSaver) { mutableStateOf<BrowserRoute>(BrowserRoute.Root) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable(stateSaver = GallerySelectionSaver) { mutableStateOf(GallerySelection()) }
    var topMenuVisible by remember { mutableStateOf(false) }
    // The two rooms are the shell's state, not booleans here: a room is fractionally open for
    // most of its life (the finger is mid-drag), which a Boolean cannot express.
    val shell = rememberSpatialController()
    var createCollectionVisible by remember { mutableStateOf(false) }
    var renameCollection by remember { mutableStateOf<BrowserRoute.Collection?>(null) }
    var renameAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var addToCollectionIds by remember { mutableStateOf<Set<MediaId>?>(null) }
    var addTagIds by remember { mutableStateOf<Set<MediaId>?>(null) }
    var passwordRequest by remember { mutableStateOf<PasswordRequest?>(null) }
    var legacyScreen by remember { mutableStateOf<LegacyScreen?>(null) }
    // Settings is a pane, not a room: it takes over the place the photos are, the same way a
    // destination screen does, and is left by the back chevron or Back. Saveable because the
    // person who rotates the phone mid-setting expects to still be in it. Mutually exclusive
    // with [legacyScreen]: opening either clears the other.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val paneTakenOver = paneTakenOver(settingsOpen, legacyScreen != null)
    // The map is a MODE of the browser: the same photos on a map in the grid's place (see
    // MapMode.kt). Plain `remember`, deliberately NOT saveable: a cold start always lands on the
    // grid, so the Connect build can never reopen straight onto a map that fetches tiles. A
    // rotation does not lose it (the activity handles that configuration change itself), but a
    // trip through the viewer does, and so does process death.
    var mapOpen by remember { mutableStateOf(false) }
    // Narrows the grid and the map alike, until cleared. Saveable (it survives the viewer round
    // trip and process death, like the search) but not persisted across launches, and NOT reset
    // by the destination/route effect below: that effect also runs on the first composition and
    // would wipe a restored range. Only the person clears it.
    var timeRange by rememberSaveable(stateSaver = TimeRangeSaver) { mutableStateOf<TimeRange?>(null) }
    var timeEditorOpen by remember { mutableStateOf(false) }
    // Where the grid last opened at its latest end (see the anchor effect further down) and how
    // many times choosing a rail word has asked for it again. Saveable for the same reason as the
    // search: after the viewer round trip or a restore the grid's own scroll position comes back,
    // and an anchor that did not remember it had run would throw that position away.
    var anchoredToken by rememberSaveable { mutableStateOf("") }
    var anchorRequest by rememberSaveable { mutableIntStateOf(0) }
    // Whether the PINNED rail's wheel is being turned or is still settling. The pull-out room has
    // its own signal (atHome is false while it is open), but a pinned rail is a sibling of the
    // grid and commits the hovered word, Map included, while the finger is still down.
    var railTurning by remember { mutableStateOf(false) }
    // Over a pane or the map the grid is composed (or not) but out of reach: keys must not act on
    // photos nobody can see, and Escape follows the Back order.
    val gridOutOfReach = paneTakenOver || mapOpen
    // The rail's own paths clear the selection as they open a pane, but Settings -> a destination
    // screen and back, and anything added later, do not; a selection kept under a pane would
    // re-appear on the grid (and its count bar with it) the moment the pane is left.
    LaunchedEffect(paneTakenOver) {
        if (paneTakenOver && selection.isActive) selection = selection.clear()
    }
    // Hoisted because the shell must reserve the same strip the shade's pull gesture lives in
    // -- see topReserve below. One state, so the shade and the gesture zone cannot differ.
    var shadeState by remember { mutableStateOf(ShadeState.COLLAPSED) }

    // ---- pinch / Ctrl+scroll zoom: the grid's rungs (1..6 columns, then the stamps) ----
    //
    // [zoomRung] is the LIVE rung, stepped by a pinch as it goes; the stored preference only
    // follows when the gesture settles or a button is pressed (see onZoomEnd below), so a pinch
    // is not a SharedPreferences write per step. [zoomResidual] is the sub-rung motion
    // [pinchStep] has not yet spent: it has to survive between gesture frames rather than reset
    // on each one. Seeded from the stored rung so a pinch continues from wherever the grid
    // density already was.
    var zoomRung by remember { mutableIntStateOf(state.preferences.gridZoomRung) }
    var zoomResidual by remember { mutableFloatStateOf(0f) }
    // A change made elsewhere (the Zoom steppers in the view options and in Settings) arrives as
    // a new stored rung; the live one follows it. A pinch's own write comes back equal and changes
    // nothing.
    LaunchedEffect(state.preferences.gridZoomRung) { zoomRung = state.preferences.gridZoomRung }

    // Set by the pending-search seeding effect (further down, after the route-change reset it
    // has to outrun) immediately before it changes `route`, so the reset that route change
    // triggers knows to leave the seeded query alone this once. Consumed by that reset and by
    // nothing else.
    var seededRouteChange by remember { mutableStateOf(false) }
    // False until the destination/route reset effect below has run once, so that effect can tell
    // the browser's first composition (state restored, leave it alone) from a real change.
    // Plain `remember`: a browser composed anew after a restore is a first composition again.
    var browserSettled by remember { mutableStateOf(false) }

    // One bridge instance for the grid's mouse/touch chrome; see [GridChromeBridge]'s own doc for
    // why this is a `remember`ed instance whose FIELDS are mutated every recomposition rather than
    // a value re-provided fresh each time -- the latter would recompose all 22k tiles on every
    // keystroke this function's state changes, which is exactly the cost this pattern exists to
    // avoid.
    val chromeBridge = remember { GridChromeBridge() }
    // Desktop input: the container this screen's keyboard shortcuts attach to needs to actually
    // HOLD focus for a key event to reach its onKeyEvent at all (Compose only dispatches key
    // events starting from whichever node is currently focused, then bubbles them up through
    // ancestors) -- requested once, on entry, so arrows/Enter/etc. work immediately without a
    // click first. Tapping into the search field moves focus to it and its own key handling runs
    // FIRST (an ancestor's onKeyEvent only sees what a focused descendant did not consume), which
    // is what keeps typing "/" or using arrow keys inside search from ever reaching this table.
    val galleryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { galleryFocusRequester.requestFocus() }

    // ---- adaptive layout: how much room this window actually has ----
    //
    // LocalConfiguration rather than BoxWithConstraints: the latter changes THIS composable's own
    // measurement policy, which is exactly the kind of structural change this task's own method
    // warns against making to a screen this size. screenWidthDp/screenHeightDp already reflect
    // the CURRENT window (not the physical display) on every platform this app ships to,
    // including a resized desktop/ChromeOS window, and LocalConfiguration is itself a
    // CompositionLocal -- a resize recomposes this the same way rotating a phone already does.
    val configuration = LocalConfiguration.current
    val windowSizeClass = remember(configuration) {
        windowSizeClassOf(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    }
    val browserContext = LocalContext.current
    // A second GalleryPreferences instance over the same SharedPreferences file `state.preferences`
    // is ultimately read from -- deliberately, and safely, per `navRailCollapsed`'s own doc in
    // GalleryPreferences.kt: this is that field's ONLY reader anywhere in the app, so there is no
    // second writer for the two instances' MutableStateFlows to ever disagree about.
    val navRailPreferences = remember(browserContext) { GalleryPreferences(browserContext.applicationContext) }
    val navRailPreferencesState by navRailPreferences.observe().collectAsStateWithLifecycle()
    val railPresentation = navRailPresentation(windowSizeClass, navRailPreferencesState.navRailCollapsed)
    val chromeMotion = chromeMotionFor(railPresentation)
    // What the app is actually doing, as a list. Two real jobs today -- the library scan and the
    // recognition pass -- and the type takes any number, because they genuinely overlap and the
    // owner asked for several at once. A move or a backup registers here the same way.
    //
    // Never while selecting: the shade and the selection's action bar occupy the same strip, and
    // recognition runs for many minutes on a large library, so the two would collide for most of
    // the time the user spends choosing photos.
    val activities = remember(state.scanState, state.recognitionProgress, selection.isActive) {
        if (selection.isActive) emptyList() else buildBackgroundActivities(state)
    }
    // Collapse when the last job ends, so the shade does not reappear expanded next time one
    // starts. Keyed on emptiness rather than on the list, which changes on every progress tick.
    val anyActivity = activities.isNotEmpty()
    LaunchedEffect(anyActivity) { if (!anyActivity) shadeState = ShadeState.COLLAPSED }
    // The same number the shade draws itself at, handed to the spatial shell so its top-edge
    // gesture starts below the shade. Two sources for this would mean either the shade cannot be
    // pulled or the top room cannot be opened.
    //
    // Only where the shade is drawn (the panes and the root grid), and 0 elsewhere: in an album or
    // on the map there is no shade, and its reserve would be a dead strip at the top where the top
    // room could not be pulled down.
    val shadeDrawn = shadeIsDrawn(
        paneTakenOver = paneTakenOver,
        mapOpen = mapOpen,
        routeIsRoot = route == BrowserRoute.Root,
    )
    val notificationReserve = if (shadeDrawn) shadeHeight(shadeState, activities.size).dp else 0.dp

    val gridState = rememberLazyGridState()
    val gridScope = rememberCoroutineScope()

    // Recognition starts when a scan SETTLES, not on every change to the asset count.
    //
    // This used to be keyed on `state.assets.size`. The indexer publishes a batch at a time, so
    // during a first scan of a large library that count changes hundreds of times -- and each
    // change re-keyed this effect, which CANCELS the running recognition pass and starts a fresh
    // one. The pass could therefore never finish while a scan was in flight, and the work done so
    // far was thrown away every batch. Keying on the completed scan's total means it fires once,
    // when there is actually a settled library to recognise.
    val settledLibrarySize = (state.scanState as? ScanState.Complete)?.total
    LaunchedEffect(settledLibrarySize) {
        if (settledLibrarySize != null && state.assets.isNotEmpty()) actions.onIndexRecognition()
    }

    // The catalogue projection is MEMOISED on exactly the inputs it reads.
    //
    // This filters and sorts the whole library, so on a real device it is far and away the most
    // expensive thing a recomposition can do -- and it used to run on every single one. What made
    // that catastrophic is that RecognitionProgress is a field of GalleryUiState: a progress tick
    // recomposed this function, so the entire catalogue was re-derived even though the projection
    // never reads progress. Keying on the individual fields it actually consumes is what breaks
    // the loop -- a progress tick now redraws the status line and nothing else.
    //
    // Deliberately NOT keyed on `state` as a whole. That would reintroduce exactly the bug being
    // fixed, because `state` takes a new identity on every progress tick and every scan event.
    val destinationBase = remember(
        destination, query, state.assets, state.favoriteIds, state.sensitiveIds,
        state.library, state.lockedFolders, state.unlockedFolders, state.preferences,
        state.recognition,
    ) {
        destinationAssets(destination, state, query)
    }
    // The time range is a lens over the projection, applied AFTER it: destinationAssets() and the
    // projection fingerprints never see it. [destinationBase] and [routeBase] are the lists
    // before the range, which is what the range editor's slider spans.
    val destinationAssets = remember(destinationBase, timeRange) { destinationBase.inTimeRange(timeRange) }
    val routeBase = remember(
        route, destinationBase, query, state.assets, state.favoriteIds, state.sensitiveIds,
        state.library, state.lockedFolders, state.unlockedFolders, state.preferences,
    ) {
        when (val current = route) {
        BrowserRoute.Root -> destinationBase
        is BrowserRoute.DeviceAlbum -> assetsForAlbum(
            assets = state.assets,
            albumKey = current.key,
            archivedIds = state.library.archivedIds,
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            preferences = state.preferences,
            query = query,
            tagsByMediaId = state.library.tagsByMediaId,
        )
        is BrowserRoute.Collection -> sortAssets(
            state.assets.filter { asset ->
                asset.id in state.library.collections.firstOrNull { it.id == current.id }?.mediaIds.orEmpty() &&
                    !asset.isTrashed &&
                    // A collection is a curated list, not the timeline, so archived/sensitive
                    // items the user put there on purpose still show -- but a locked folder is
                    // never optional, so that part of the one visibility filter still applies.
                    asset.isPrivacyVisible(state.lockedFolders, state.unlockedFolders) &&
                    asset.matchesGallerySearch(query, state.library.tagsFor(asset.id), state.recognition, state.favoriteIds, state.animatedIds, state.library.archivedIds)
            },
            state.preferences.sort,
        )
        is BrowserRoute.Smart -> smartAlbumAssets(
            smartAlbum = current.album,
            assets = state.assets,
            favoriteIds = state.favoriteIds,
            sensitiveIds = state.sensitiveIds,
            archivedIds = state.library.archivedIds,
            tagsByMediaId = state.library.tagsByMediaId,
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            preferences = state.preferences,
            animatedIds = state.animatedIds,
        ).filter { it.matchesGallerySearch(query, state.library.tagsFor(it.id), state.recognition, state.favoriteIds, state.animatedIds, state.library.archivedIds) }
        is BrowserRoute.Tag -> sortAssets(
            state.assets.filter { asset ->
                current.tag in state.library.tagsFor(asset.id) &&
                    !asset.isTrashed &&
                    // Same rule as BrowserRoute.Collection just above: a tag view is a curated
                    // list too, but a locked folder is still never optional.
                    asset.isPrivacyVisible(state.lockedFolders, state.unlockedFolders) &&
                    asset.matchesGallerySearch(query, state.library.tagsFor(asset.id), state.recognition, state.favoriteIds, state.animatedIds, state.library.archivedIds)
            },
            state.preferences.sort,
        )
        }
    }
    // At the root this is the very list [destinationAssets] is, so the root grid keeps one
    // identity however the range changes.
    val currentAssets = remember(route, destinationAssets, routeBase, timeRange) {
        if (route == BrowserRoute.Root) destinationAssets else routeBase.inTimeRange(timeRange)
    }
    // Both of these walk the full projection, and both are only ever read while a selection is
    // active (the selection top bar). Building a 22k-entry set on every recomposition to answer a
    // question nobody is asking is pure waste, so they collapse to a constant when idle.
    val selectedAssets = remember(currentAssets, selection) {
        if (selection.isActive) currentAssets.filter { it.id in selection.selectedIds } else emptyList()
    }
    val currentIds = remember(currentAssets, selection.isActive) {
        if (selection.isActive) currentAssets.mapTo(linkedSetOf()) { it.id } else emptySet()
    }
    val inTrash = (route as? BrowserRoute.Smart)?.album == SmartAlbum.TRASH
    val inArchive = (route as? BrowserRoute.Smart)?.album == SmartAlbum.ARCHIVED
    val collectionRoute = route as? BrowserRoute.Collection
    val albumRoute = route as? BrowserRoute.DeviceAlbum
    val tagRoute = route as? BrowserRoute.Tag

    // Guarded on isActive because currentIds is deliberately emptySet() while idle (above);
    // retaining against an empty set unguarded would clear a selection the moment one began.
    LaunchedEffect(currentIds) {
        if (selection.isActive) selection = selection.retainAvailable(currentIds)
    }
    LaunchedEffect(destination, route) {
        // Not on the first composition: there, destination and route were not CHANGED, they were
        // restored (from the viewer round trip, see GALLERY_STATE_KEY, or from process death),
        // and the query and selection that came back with them are the point of restoring.
        if (!browserSettled) {
            browserSettled = true
            return@LaunchedEffect
        }
        selection = selection.clear()
        // A route change the seeding effect below made on purpose carries a search with it;
        // wiping that search here would make the Search pill in the photo details room close
        // the viewer and open an empty grid -- which is exactly what it did, because this effect
        // runs on the browser's first composition too, and effects run in declaration order.
        if (seededRouteChange) {
            seededRouteChange = false
            return@LaunchedEffect
        }
        query = ""
        searchVisible = false
    }

    // A search handed over from outside the grid -- today the viewer's "Search inside this photo"
    // card. Declared AFTER the reset effect above on purpose: on the browser's first composition
    // every effect runs once, in order, and this one has to be the last word on `query`. Three
    // things move together, because a search that lands on a surface which cannot show results
    // is the same as no search at all: the drill-down route resets to Root (a search scoped to
    // whichever album the photo happened to live in would silently hide most of its own matches),
    // the map gives way to the grid, and any pane over the grid is closed. The DESTINATION is
    // deliberately left alone: which of the nine categories the person is browsing is a choice they
    // made, not incidental state, and search reads as a filter within it.
    LaunchedEffect(state.pendingSearch) {
        val seed = state.pendingSearch ?: return@LaunchedEffect
        if (route != BrowserRoute.Root) {
            // Flag first, then change: the flag must already be set when the reset effect
            // re-runs for this route change on the next frame.
            seededRouteChange = true
            route = BrowserRoute.Root
        }
        query = seed
        searchVisible = true
        mapOpen = false
        // A search has to land on the grid it filters, not under a pane that covers it.
        settingsOpen = false
        legacyScreen = null
        // Acknowledged only after the seeding above has actually happened, so a consumer that
        // clears the value cannot race ahead of the state it is acknowledging.
        actions.onPendingSearchConsumed()
    }

    // Whether the time panel is actually drawn (see the block that draws it): the flag alone is not
    // enough, because a selection or a pane hides the panel without clearing it.
    val timeEditorIsShown = timeEditorShown(
        open = timeEditorOpen,
        selectionActive = selection.isActive,
        paneTakenOver = paneTakenOver,
        rangeApplies = timeRangeApplies(destination),
    )
    // What Back undoes next, and what Escape undoes while a pane is up: one list, so a hardware
    // keyboard cannot leave a pane that the Back gesture would have closed.
    fun currentBackTarget() = paneBackTarget(
        roomOpen = !shell.atHome,
        settingsOpen = settingsOpen,
        legacyScreenOpen = legacyScreen != null,
        selectionActive = selection.isActive,
        searchVisible = searchVisible,
        routeIsRoot = route == BrowserRoute.Root,
        mapOpen = mapOpen,
        timeEditorShown = timeEditorIsShown,
    )
    fun performBack(target: PaneBackTarget) {
        when (target) {
            // A room is not a back-stack entry, but Back is still the gesture people reach for
            // to leave one, so it closes the room rather than the app.
            PaneBackTarget.CLOSE_ROOM -> shell.closeAll()
            PaneBackTarget.CLOSE_SETTINGS -> settingsOpen = false
            PaneBackTarget.CLOSE_LEGACY_SCREEN -> legacyScreen = null
            PaneBackTarget.CLOSE_TIME_EDITOR -> timeEditorOpen = false
            PaneBackTarget.CLEAR_SELECTION -> selection = selection.clear()
            PaneBackTarget.CLOSE_SEARCH -> {
                searchVisible = false
                query = ""
            }
            PaneBackTarget.CLOSE_MAP -> mapOpen = false
            PaneBackTarget.LEAVE_ROUTE -> route = BrowserRoute.Root
            PaneBackTarget.NONE -> Unit
        }
    }
    BackHandler(
        enabled = !shell.atHome || paneTakenOver || mapOpen || timeEditorIsShown ||
            selection.isActive || searchVisible || route != BrowserRoute.Root,
    ) {
        performBack(currentBackTarget())
    }
    // The time editor is a transient panel over the grid or the map: Back closes it before
    // anything else (it is a target of the one handler above, so it cannot outrank or swallow
    // the others), and anything that hides it -- a room, a selection, a pane, a destination a
    // range does not apply to -- also drops the flag, so it does not come back by itself later.
    LaunchedEffect(shell.atHome, timeEditorIsShown) {
        if (!shell.atHome || (timeEditorOpen && !timeEditorIsShown)) timeEditorOpen = false
    }

    // Where the grid actually is, for the edge scrubber's resting marker. Derived so a scroll
    // invalidates only what draws the marker, not the whole browser.
    val firstVisibleIndex by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex }
    }
    val scrubberAssets = if (route == BrowserRoute.Root) destinationAssets else currentAssets
    // What the pill says on the map: how many of the photos in this view have a place. Only
    // counted while the map is up, and only when the geo index changes.
    val locatedMetadata = LocalSpatialExperience.current?.geoState?.metadataById
    val mapCaption = remember(mapOpen, scrubberAssets, locatedMetadata) {
        if (!mapOpen || locatedMetadata == null) {
            ""
        } else {
            val located = scrubberAssets.count { !it.isTrashed && it.id in locatedMetadata }
            if (located == scrubberAssets.size) "$located located" else "$located of ${scrubberAssets.size} located"
        }
    }
    // The months the range editor's slider spans: those of the view BEFORE the range, so the
    // slider never shrinks to the range it is setting. Only worked out while the editor is open.
    val rangeDomain = remember(routeBase, timeEditorOpen) {
        if (timeEditorOpen) monthExtent(routeBase) else null
    }
    // The masonry layout (the "fit to tile" setting off) is the one grid that has neither stamps nor
    // headers, and its rungs stop at the sparsest column grid.
    val masonryActive = !state.preferences.fitToTile
    val maxZoomRung = if (masonryActive) ZOOM_RUNG_LAST_GRID else ZOOM_RUNG_STAMPS
    val activeRung = zoomRung.coerceAtMost(maxZoomRung)
    // Whether the grid below draws header rows: the stamp view in a date order, and the column rungs
    // while the timeline is on (see headersShown). A plain Boolean so stepping between the column
    // rungs does not rebuild the layout.
    val timelineOn = state.preferences.timelineOn
    val headersActive = headersShown(
        activeRung, state.preferences.sort, masonryActive, timelineOn, state.preferences.timelineGrouping,
    )
    val layoutGrouping = headerGrouping(activeRung, state.preferences.timelineGrouping)
    // Whether the grid on screen scrolls sideways: the setting, except at the stamps and in masonry,
    // which stay vertical. Latest-first then reads left to right (see timelineSortLabel).
    val sidewaysGrid = gridScrollsHorizontally(state.preferences.scrollAxis, activeRung, masonryActive)
    // The one layout computation, shared by the grid (ZoomGrid, below) and everything that used to
    // assume grid index == asset index (the scrubber, the pill, arrow keys, and the list handed to
    // the viewer on open) -- see docs/TRAPS.md #5 and #9. Without headers this is the identity: one
    // group holding every asset, unreordered.
    val layout = remember(scrubberAssets, headersActive, layoutGrouping) {
        zoomLayoutFor(scrubberAssets, headersActive, layoutGrouping)
    }
    // What the grid actually draws, asset-wise, in draw order -- NOT scrubberAssets once headers
    // regroup it. This, not scrubberAssets, is what the scrubber, pill, arrow keys and viewer-open
    // must all key off from here down.
    val renderedAssets = layout.renderedAssets
    // Every grid this screen can show draws one trailing 88dp footer spacer after the last item
    // (ZoomGrid and both of MediaGridScreen's branches all do) -- see GridIndexMap's own doc for why
    // headers-off is just the identity plus this one footer.
    val gridIndexMap = remember(layout) {
        GridIndexMap(
            groupSizes = layout.groups.map { it.assets.size },
            hasHeaders = layout.hasHeaders,
            trailingFooter = true,
        )
    }
    // The scrubber's stops, in the GRID's index space (its own contract: it does not know or
    // care what an index means, only that stops/itemCount/currentIndex/onScrubTo all agree on
    // one space). timelineStops itself still reasons in asset-index space over renderedAssets --
    // its own month-bucketing has no idea headers exist -- so every stop's target is remapped
    // through the same map the grid was built from before it reaches the scrubber. Under month
    // headers every stop opens a run (stops and runs both cut where the month changes), so it
    // targets the header row just above that run's first photo, not the photo: landing on the
    // photo would leave the month's own header scrolled out of sight.
    val scrubberStops = remember(renderedAssets, gridIndexMap, layout.hasHeaders) {
        val headerRows = if (layout.hasHeaders) 1 else 0
        timelineStops(renderedAssets).map {
            it.copy(itemIndex = (gridIndexMap.gridIndexOf(it.itemIndex) - headerRows).coerceAtLeast(0))
        }
    }
    // P0-15: masonry's staggered grid keeps its own LazyStaggeredGridState, entirely unreachable
    // from here (see MediaGridScreen's own doc) -- gridState/GridIndexMap describe a grid that,
    // in masonry, is not even the one on screen. Rather than let the scrubber track and drive a
    // position nothing agrees with, it is hidden while masonry is active; the pill's position
    // text is hidden the same way just below, since there is no staggered-grid position to show
    // in its place without threading a second scroll state up through DestinationContent.
    // Nor does the map, which is drawn in the grid's place.
    val scrubberTrackable = !masonryActive && !mapOpen

    // ---- the timeline strip: whether it is part of the screen, and the gutter that goes with it ----
    //
    // One decision (stripShouldShow) for both the strip and the room the grid leaves for it, so
    // they cannot disagree: the grid makes room exactly when the strip is there (owner #25: no
    // overlap). The gutter does not follow the scroll or the selection, so the grid never jumps.
    val timelinePlacement = state.preferences.timelinePlacement
    val stripShown = stripShouldShow(
        timelineOn = timelineOn,
        sort = state.preferences.sort,
        masonry = masonryActive,
        mapOpen = mapOpen,
        paneTakenOver = paneTakenOver,
        hasStops = scrubberStops.isNotEmpty(),
    )
    val timelineGutter = timelineReserve(stripShown, timelinePlacement)

    // ---- open at the latest end ----
    //
    // The photos always run first item to last in the sort's order and the grid is never reversed
    // (that would move every header and break GridIndexMap), so "latest at the bottom" is the
    // Oldest first order with the grid opened at its end. Once per destination, route and sort
    // (and per rail choice), when the photos are there and the scan is not still adding them --
    // not on every batch, which would fight a scroll that has begun. Declared after the reset
    // effects above (TRAPS #22), and a token that already ran leaves a restored scroll alone.
    val anchorTokenNow = anchorToken(destination.name, route.toString(), state.preferences.sort, anchorRequest)
    val anchorIsReady = anchorReady(
        hasPhotos = renderedAssets.isNotEmpty(),
        // Only the latest-at-the-end order has anything to wait for: the start of the list is
        // the start however many batches follow.
        scanning = latestAtEnd(state.preferences.sort) && state.scanState is ScanState.Scanning,
    )
    LaunchedEffect(anchorTokenNow, anchorIsReady) {
        if (anchorIsReady && anchoredToken != anchorTokenNow) {
            anchoredToken = anchorTokenNow
            gridState.scrollToItem(
                anchorIndex(gridIndexMap.itemCount, latestAtEnd(state.preferences.sort)),
            )
        }
    }

    // ---- desktop input: fold pinch and Ctrl+scroll into the zoom rungs, and the right-click
    // context menu into real actions. Both gestures are already consumed in GalleryContent.kt
    // (gridZoomGestures, MediaTile's secondary-button handler and DropdownMenu) -- this is only
    // the wiring GridChromeBridge's own doc calls for.
    //
    // Reassigned every recomposition rather than set once: these lambdas close over the
    // preferences and scrubberAssets, which change across the browsing surface's life, and a
    // bridge captured once at `remember` time would forever act on the FIRST recomposition's
    // values. See GridChromeBridge's own doc for why mutating the remembered instance's fields --
    // not re-providing a new instance -- is what keeps this cheap: the 22k-tile grid below reads
    // the field, not the identity of the bridge that holds it.
    chromeBridge.onZoom = { scaleFactor ->
        val step = pinchStep(zoomRung, zoomResidual, scaleFactor, maxRung = maxZoomRung)
        zoomRung = step.rung
        zoomResidual = step.residual
    }
    // The pinch (or the wheel) has settled: now, not on every step, the rung is stored, and the
    // motion it did not spend is dropped so the next, unrelated pinch starts from nothing.
    chromeBridge.onZoomEnd = {
        zoomResidual = 0f
        val settled = zoomRung.coerceAtMost(maxZoomRung)
        if (settled != state.preferences.gridZoomRung) actions.onSetGridZoomRung(settled)
    }
    // A step with no gesture behind it (Ctrl +/-, a screen reader's zoom actions): stored at once.
    chromeBridge.onZoomStep = { delta ->
        val next = stepRung(zoomRung, delta, maxZoomRung)
        zoomRung = next
        zoomResidual = 0f
        if (next != state.preferences.gridZoomRung) actions.onSetGridZoomRung(next)
    }
    chromeBridge.onContextAction = { asset, action ->
        when (action) {
            MediaContextAction.OPEN -> actions.onOpenAsset(asset, renderedAssets)
            MediaContextAction.TOGGLE_FAVORITE ->
                actions.onSetFavorite(setOf(asset.id), asset.id !in state.favoriteIds)
            MediaContextAction.MOVE_TO_TRASH -> actions.onMoveToTrash(listOf(asset))
            MediaContextAction.SHARE -> actions.onShare(listOf(asset))
            MediaContextAction.EDIT -> actions.onEditAsset(asset)
        }
    }
    // The Trash has no use for a held favourite, share or edit (restore and delete live there).
    chromeBridge.gridLongPress = LongPressResolver.effectiveFor(state.preferences.gridLongPress, inTrash)

    // The destinations, with Map before Places (see MapMode.kt).
    val railItems = remember {
        railWords().map { (id, label) -> dev.aarso.cellshell.WheelItem(id, label) }
    }
    // What choosing a rail word does, once, for the pull-out room's rail and the pinned panel's:
    // a destination word leaves the map and the route; the Map word opens the map over the
    // current view and never touches the route or the grid's scroll position.
    fun selectRailWord(id: String) {
        val choice = railChoice(id, destination)
        mapOpen = choice.mapOpen
        destination = choice.destination
        if (choice.resetsRoute) route = BrowserRoute.Root
        legacyScreen = null
        settingsOpen = false
        selection = selection.clear()
        // The new word's photos are not in hand yet, so the grid cannot be moved to the right
        // end of them from here; ask the anchor effect, which runs once they are.
        if (choice.resetsRoute) anchorRequest++
    }

    // One dispatch point for every key this screen gives meaning to, translating the raw Compose
    // key into adaptive's own vocabulary via [galleryShortcutFor] -- see that function's own doc
    // for the exact table and why it returns null (rather than a default) for a key it has no
    // opinion about.
    //
    // Arrow-key navigation walks the rendered photos in strides of the tiles the grid has across
    // right now (rows, on a sideways grid -- see arrowKeyStep) (the stamp view's count depends on the window's width, so the grid reports it). That
    // is exact for the headerless grid, and an approximation under the stamps' month headers (a row
    // ends at each month, so a stride drifts by the unfilled slots) and in masonry mode (whose rows
    // do not actually align to a fixed column count) -- acceptable here because it still moves the
    // cursor roughly up/down/across rather than leaving the keyboard unable to drive those grids.
    fun handleGalleryShortcut(shortcut: GalleryShortcut) {
        // The grid is still composed under a pane but nobody can see it: Ctrl+A, Delete, Enter and
        // the arrows would select, trash and open photos on a screen that is not showing them.
        // The same goes for a room over it: Delete, Ctrl+A and Enter would act on the grid behind.
        if (!shortcutActsOverPane(shortcut, gridOutOfReach, roomOpen = !shell.atHome)) return
        when (shortcut) {
            is GalleryShortcut.MoveSelection -> {
                // renderedAssets, not scrubberAssets: this is asset-index-space arithmetic (a
                // column stride is a stride in assets, header rows aren't columns), but it must
                // walk the SAME order the grid draws once headers regroup it, then convert the
                // result through GridIndexMap before it ever reaches gridState.
                val ids = renderedAssets
                if (ids.isEmpty()) return
                val current = ids.indexOfFirst { it.id == chromeBridge.keyboardFocusedId }
                    .let { if (it < 0) 0 else it }
                // The long stride is the grid's short side (columns, or rows when it scrolls
                // sideways) and goes to the keys across the lines; the others step one photo.
                val next = (current + arrowKeyStep(shortcut.direction, chromeBridge.columnsAcross, sidewaysGrid))
                    .coerceIn(0, ids.lastIndex)
                chromeBridge.keyboardFocusedId = ids[next].id
                // scrollToItem, not animateScrollToItem: matches the edge scrubber's own choice
                // just below -- a held-down arrow key fires many of these in quick succession, and
                // an animated scroll queued behind an animated scroll is how that becomes a stutter.
                gridScope.launch { gridState.scrollToItem(gridIndexMap.gridIndexOf(next)) }
            }
            GalleryShortcut.OpenFocused -> {
                val ids = renderedAssets
                val focused = ids.firstOrNull { it.id == chromeBridge.keyboardFocusedId } ?: ids.firstOrNull()
                focused?.let { actions.onOpenAsset(it, ids) }
            }
            // Same priority order as BackHandler above: a room first, then a pane, then a
            // selection as the most specific thing to back out of, then search, then the
            // keyboard's own cursor.
            GalleryShortcut.CloseOrClear -> when {
                escapeFollowsBackOrder(gridOutOfReach, roomOpen = !shell.atHome) -> performBack(currentBackTarget())
                selection.isActive -> selection = selection.clear()
                searchVisible -> {
                    searchVisible = false
                    query = ""
                }
                chromeBridge.keyboardFocusedId != null -> chromeBridge.keyboardFocusedId = null
                else -> Unit
            }
            GalleryShortcut.TrashSelected -> {
                val targets = if (selection.isActive) {
                    selectedAssets
                } else {
                    scrubberAssets.filter { it.id == chromeBridge.keyboardFocusedId }
                }
                if (targets.isNotEmpty()) {
                    actions.onMoveToTrash(targets)
                    if (selection.isActive) selection = selection.clear()
                }
            }
            GalleryShortcut.SelectAll -> selection = selection.selectAll(scrubberAssets.map { it.id })
            GalleryShortcut.FocusSearch -> searchVisible = true
            GalleryShortcut.ZoomIn -> chromeBridge.onZoomStep(-1)
            GalleryShortcut.ZoomOut -> chromeBridge.onZoomStep(1)
        }
    }

    // Provided once, here, above every possible location of a grid tile -- the root Photos grid
    // reached through DestinationContent (a file this task does not own) AND the drill-down
    // MediaGridScreen below both sit inside SpatialShell's trailing content, so wrapping the
    // whole shell is what makes bridge.onZoom/onContextAction reach a tile regardless of which
    // of those two paths rendered it. See GridChromeBridge's own doc for the full reasoning.
    CompositionLocalProvider(LocalGridChromeBridge provides chromeBridge) {
    // Over a pane or the map the details, actions and view options rooms would act on the hidden
    // grid, so only the navigation room stays (and the shell refuses a drag from an edge with no
    // room).
    val rooms = roomSlots<@Composable () -> Unit>(
        navigation = {
            DestinationRailPanel(
                items = railItems,
                selectedId = railSelectedId(mapOpen, destination),
                // Selecting does NOT close the room: the rail commits the hovered word while the
                // wheel is still turning, so the room stays put and the pane behind it follows.
                // The user closes it by tapping the selected word, the parked card, or Back.
                // Every select drops a destination screen sitting on top (the `legacy != null`
                // branch below wins over `destination`), or picking Photos from Albums would
                // close the room and change nothing.
                onSelect = ::selectRailWord,
                // The selected word is the destination UNDER an open pane, so tapping it is how
                // the pane is left as well as how the room is closed.
                onReselect = {
                    val effect = railReselectEffect(inRoom = true, paneTakenOver = paneTakenOver)
                    if (effect.leavePane) {
                        legacyScreen = null
                        settingsOpen = false
                    }
                    if (effect.closeRoom) shell.closeAll()
                },
                // A scrub that rests on that word leaves the pane too, but the finger is still on
                // the wheel, so the room stays.
                paneOpen = paneTakenOver,
                onLeavePane = {
                    legacyScreen = null
                    settingsOpen = false
                },
                // The room swings closed and the card that comes back IS Settings.
                onOpenSettings = {
                    settingsOpen = true
                    legacyScreen = null
                    selection = selection.clear()
                    shell.closeAll()
                },
            )
        },
        // TOP = what this view is, pulled down over it.
        details = {
            GalleryInfoRoom(
                title = route.title(destination),
                assets = currentAssets,
                state = state,
            )
        },
        // BOTTOM = what you can do here, tilted up from under the grid.
        actions = {
            GalleryActionsRoom(
                state = state,
                actions = actions,
                selection = selection,
                selectedAssets = selectedAssets,
                currentIds = currentIds,
                inTrash = inTrash,
                inArchive = inArchive,
                tagRoute = tagRoute,
                collectionRoute = collectionRoute,
                onSelectionChange = { selection = it },
                onRenameAsset = { renameAsset = it },
                onAddToCollection = { addToCollectionIds = it },
                onAddTag = { addTagIds = it },
                onStartSelection = {
                    // Entering selection with nothing selected: the overlay appears, the grid
                    // switches to tap-to-add, and the count reads zero until the user picks one.
                    // This is the entry point long press used to be, before long press became a
                    // preview that ends when the finger lifts.
                    shell.closeAll()
                    selection = selection.beginSelecting()
                },
                onCloseRoom = { shell.closeAll() },
                hasBottomPeek = shell.hasBottomPeek,
                onNewCollection = {
                    shell.closeAll()
                    createCollectionVisible = true
                },
            )
        },
        // RIGHT = how the view is shown, in from the side so its effect can be watched.
        viewOptions = {
            GalleryViewOptionsRoom(state = state, actions = actions)
        },
    ).navigationOnlyWhen(gridOutOfReach)
    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        // The rooms sit on the same black the grid does, so opening one reads as the surface
        // moving rather than as a different app appearing behind it.
        scrimColor = Color.Black,
        cardColor = Color.Black,
        modifier = Modifier.fillMaxSize(),
        // Shrink AND swivel while a room is being PEEKED -- pulled in as a temporary reveal, on
        // any input method -- so it reads as a panel swinging away rather than a rectangle
        // sliding off (owner, 2026-08-14: the Magic Portal shape). Shrink ONLY once the rail is a
        // standing, pinned choice rather than a gesture in progress (owner, 2026-08-20: "if nav
        // is turned on, the central pane just shrinks, doesn't pivot") -- see chromeMotionFor's
        // own doc for the full distinction. SpatialShell's parkStyle is one setting for every
        // room it owns, not a per-room one, so pinning the rail also swaps the details, actions
        // and view options rooms' own motion to plain-shrink for as long as it stays pinned,
        // which matches the owner's own framing: peeking vs. pinned is a property of how this
        // WINDOW is being used right now, not of which particular room happens to be opening.
        parkStyle = when (chromeMotion) {
            ChromeMotion.SHRINK_ONLY -> ParkStyle.SLIDE
            ChromeMotion.SHRINK_AND_PIVOT -> ParkStyle.SWIVEL
        },
        // Hand the notification's own strip back to it. Without this the shell claims the top
        // 56dp on the Initial pass and every pull on the status line opens the top room (when
        // there is one) instead of expanding the line -- the two gestures live in the same
        // pixels. Still needed now the details room is there.
        topReserve = notificationReserve,
        // One geography for the whole app (owner, 2026-08-18: *"the model needs to remain the
        // same"*). Wherever you are, the same edge holds the same KIND of thing, and RoomGeography
        // holds the table the viewer reads too:
        //
        //   LEFT   where you can go        RIGHT   how it is shown (view options)
        //   TOP    what this is (details)  BOTTOM  what you can do (actions)
        //
        // Settings is not a room: it is a pane that takes over the place the photos are (see
        // settingsOpen), reached from the rail.
        left = rooms.left,
        top = rooms.top,
        right = rooms.right,
        bottom = rooms.bottom,
        // A short pull from the bottom stops on the actions room's quick-action strip and a longer
        // one opens the lot (RoomGeography holds how tall the strip is). The strip follows the
        // selection (see galleryQuickActions). None when no action is allowed right now, so the
        // pull stays single-stage rather than stopping on nothing.
        bottomPeek = RoomGeography.peekFor(RoomKind.ACTIONS)
            ?.takeIf { galleryQuickActions(selection.isActive, inTrash).isNotEmpty() },
    ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Black,
                topBar = {
                    // Nothing. Selection chrome is no longer an app bar either -- it floats over
                    // the mosaic as two clusters (owner screenshots, 2026-08-15), so it costs the
                    // grid no layout height and the photos stay edge to edge even while choosing.
                    // No header on the browsing surface at all. BrowserHeader used to be
                    // composed unconditionally into this slot, so the Scaffold reserved ~84dp of
                    // mosaic permanently for a title that repeated the word the rail already
                    // shows selected and the pill already says, plus a 3-dot menu whose last item
                    // merely opened a room that is one edge-drag away (owner, 2026-08-14: "I
                    // don't want to see Places and 3 dots at the top. Immersive!").
                    //
                    // The route-scoped actions that lived in that menu are NOT lost -- they move
                    // to RouteOverlayBar below, which appears only when you are actually inside
                    // an album, a collection or a tag. Refresh stays a shake, backup stays in the
                    // Library screen, and Settings is a pane reached from the rail.
                },
                floatingActionButton = {
                    if (!selection.isActive && legacyScreen == LegacyScreen.ALBUMS) {
                        FloatingActionButton(onClick = { createCollectionVisible = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = "Create collection")
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(padding)
                        .focusRequester(galleryFocusRequester)
                        .focusable()
                        // onKeyEvent, not onPreviewKeyEvent: this container must see a key AFTER
                        // whatever is currently focused has had a chance to consume it, which is
                        // what lets the search TextField's own text-editing keys (including its
                        // own arrows and its own `/`) win over this table without either widget
                        // needing to know the other exists. See galleryFocusRequester's own
                        // comment above for the rest of this reasoning.
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val shortcutKey = when (event.key) {
                                Key.DirectionUp -> GalleryShortcutKey.ARROW_UP
                                Key.DirectionDown -> GalleryShortcutKey.ARROW_DOWN
                                Key.DirectionLeft -> GalleryShortcutKey.ARROW_LEFT
                                Key.DirectionRight -> GalleryShortcutKey.ARROW_RIGHT
                                Key.Enter -> GalleryShortcutKey.ENTER
                                Key.Escape -> GalleryShortcutKey.ESCAPE
                                // Delete AND Backspace: many laptop keyboards (Mac included) have
                                // only the key labelled "delete" that actually sends Backspace.
                                Key.Delete, Key.Backspace -> GalleryShortcutKey.DELETE
                                Key.Slash -> GalleryShortcutKey.SLASH
                                Key.A -> GalleryShortcutKey.LETTER_A
                                // Ctrl + the plus key (which on most layouts is the equals key,
                                // unshifted) or the numpad's, and Ctrl + minus.
                                Key.Equals, Key.Plus, Key.NumPadAdd -> GalleryShortcutKey.PLUS
                                Key.Minus, Key.NumPadSubtract -> GalleryShortcutKey.MINUS
                                else -> null
                            } ?: return@onKeyEvent false
                            val shortcut = galleryShortcutFor(shortcutKey, event.isCtrlPressed)
                                ?: return@onKeyEvent false
                            // Not consumed over a pane, the map or a room, so a key the grid ignores there still
                            // reaches whatever else wants it.
                            if (!shortcutActsOverPane(shortcut, gridOutOfReach, roomOpen = !shell.atHome)) {
                                return@onKeyEvent false
                            }
                            handleGalleryShortcut(shortcut)
                            true
                        },
                ) {
                    // On a window wide enough to fit it beside the grid, the nine-destination
                    // rail sits here as an ordinary, always-visible sibling rather than living
                    // only in the pull-out room -- the room ITSELF is untouched below (`left =`
                    // on SpatialShell still renders it), so a wide-screen user can still peek it
                    // from the true screen edge; this is the "it's here permanently" half of the
                    // owner's ask, not a replacement for the room's own gesture.
                    Row(Modifier.fillMaxSize()) {
                        if (railPresentation != NavRailPresentation.PULL_OUT) {
                            PersistentNavRail(
                                presentation = railPresentation,
                                items = railItems,
                                selectedId = railSelectedId(mapOpen, destination),
                                onSelect = ::selectRailWord,
                                // No room is involved on a pinned rail, so there is nothing to close -- but
                                // the word you came from must still lead back out of a pane.
                                onReselect = {
                                    if (railReselectEffect(inRoom = false, paneTakenOver = paneTakenOver).leavePane) {
                                        legacyScreen = null
                                        settingsOpen = false
                                    }
                                },
                                paneOpen = paneTakenOver,
                                onLeavePane = {
                                    legacyScreen = null
                                    settingsOpen = false
                                },
                                onTurning = { railTurning = it },
                                onOpenSettings = {
                                    settingsOpen = true
                                    legacyScreen = null
                                    selection = selection.clear()
                                },
                                onExpand = { navRailPreferences.setNavRailCollapsed(false) },
                                onCollapse = { navRailPreferences.setNavRailCollapsed(true) },
                            )
                        }
                    Column(Modifier.fillMaxHeight().weight(1f)) {
                        if (searchVisible && !settingsOpen) {
                            com.fotoxplorr.app.hyle.HyleTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                placeholder = "Search names, albums, types and tags",
                                // Fixed on purpose (reference design): the Hyle idle-wedge neutral
                                // on the light search field, not an accent.
                                leading = { Icon(Icons.Outlined.Search, null, tint = Color(0xFF3A3A44)) },
                                trailing = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "Clear search",
                                                tint = Color(0xFF3A3A44),
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        // The grid's own box: the strip lives in it, not in the screen-wide box
                        // below, so a strip on the left starts where the pinned rail ends instead
                        // of being drawn over it. The inner box is the gutter -- the room the grid
                        // makes for the strip -- and holds whatever takes the grid's place.
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (timelinePlacement == TimelinePlacement.BOTTOM && !timelineGutter.isEmpty) {
                                            // Above the gesture bar too, or the strip's lower edge would be
                                            // the system's to take.
                                            Modifier.navigationBarsPadding()
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(
                                        start = timelineGutter.start.dp,
                                        top = timelineGutter.top.dp,
                                        end = timelineGutter.end.dp,
                                        bottom = timelineGutter.bottom.dp,
                                    ),
                            ) {
                                val legacy = legacyScreen
                                when {
                                    // The two panes. Settings is checked first only for readability: they
                                    // are mutually exclusive, because opening either clears the other.
                                    settingsOpen -> ShadedPane(
                                        activities = activities,
                                        shadeState = shadeState,
                                        onShadeStateChange = { shadeState = it },
                                        reserve = notificationReserve,
                                    ) {
                                        PaneScaffold(
                                            title = "Settings",
                                            onBack = { settingsOpen = false },
                                        ) {
                                            SettingsRoom(
                                                state = state,
                                                actions = actions,
                                                onOpenLegacyScreen = {
                                                    settingsOpen = false
                                                    legacyScreen = it
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                    legacy != null -> ShadedPane(
                                        activities = activities,
                                        shadeState = shadeState,
                                        onShadeStateChange = { shadeState = it },
                                        reserve = notificationReserve,
                                    ) {
                                        LegacyScreenHost(
                                            screen = legacy,
                                            state = state,
                                            actions = actions,
                                            query = query,
                                            onBack = { legacyScreen = null },
                                            onOpenRoute = { route = it; legacyScreen = null; mapOpen = false },
                                            onRequestUnlock = { key, name ->
                                                passwordRequest = PasswordRequest(PasswordAction.UNLOCK, key, name)
                                            },
                                            // How the Appearance > Accent picker is reached from these screens.
                                            onOpenSettings = {
                                                settingsOpen = true
                                                legacyScreen = null
                                            },
                                            onOpenDestination = {
                                                destination = it
                                                route = BrowserRoute.Root
                                                legacyScreen = null
                                                mapOpen = false
                                            },
                                        )
                                    }
                                    // ---- the map: the same photos, on a map, in the grid's place ----
                                    //
                                    // A mode of the browser rather than a rung or a destination (see
                                    // MapMode.kt), so it is checked ahead of everything that draws the
                                    // grid. It is fed [scrubberAssets] -- the destination, the route, the
                                    // search and the time range all applied, and the one visibility
                                    // filter -- not the whole library.
                                    mapOpen -> GalleryMapContent(
                                        assets = scrubberAssets,
                                        settled = mapPaneSettled(atHome = shell.atHome, railTurning = railTurning),
                                    )
                                    // Pull-to-backup is retired (owner direction, 2026-08-05): the
                                    // pull-down space at a room's top belongs to the fonebrew
                                    // top-room reveal, so no other gesture may claim it — and the
                                    // static "PULL TO CREATE BACKUP" copy went with it. Refresh is
                                    // now physical (ShakeToRefresh below); backup is an explicit,
                                    // named item in Settings' Data tab instead of a gesture.
                                    //
                                    // The shade is drawn OVER this content and never displaces it, so
                                    // the grid does not move as jobs start and finish -- see ActivityShade.
                                    route == BrowserRoute.Root -> ActivityShade(
                                        activities = activities,
                                        state = shadeState,
                                        onStateChange = { shadeState = it },
                                    ) {
                                        Column {
                                            ShakeToRefresh(onShake = actions.onRefresh)
                                            // Android 14's own partial grant used to be invisible outside
                                            // Settings › Media -- someone who picked "Selected photos"
                                            // once had no way, short of hunting through settings, to
                                            // notice they could add more without this strip naming it
                                            // right where the gap actually shows up.
                                            if (state.partialMediaAccess && destination != HyleDestination.PROTECTED) {
                                                PartialAccessStrip(onChooseMore = actions.onManageSelectedMedia)
                                            }
                                            DestinationContent(
                                                destination = destination,
                                                assets = destinationAssets,
                                                layout = layout,
                                                rung = activeRung,
                                                state = state,
                                                actions = actions,
                                                selection = selection,
                                                onSelectionChange = { selection = it },
                                                gridState = gridState,
                                                horizontal = sidewaysGrid,
                                                onRequestUnlock = { key, name ->
                                                    passwordRequest = PasswordRequest(
                                                        PasswordAction.UNLOCK, key, name,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    else -> ZoomGrid(
                                        layout = layout,
                                        rung = activeRung,
                                        favoriteIds = state.favoriteIds,
                                        sensitiveIds = state.sensitiveIds,
                                        blurSensitive = state.preferences.blurSensitive,
                                        selectedIds = selection.selectedIds,
                                        selectionActive = selection.isActive,
                                        emptyMessage = if (query.isBlank()) "Nothing here yet" else "No matching media",
                                        onOpen = { asset -> actions.onOpenAsset(asset, layout.renderedAssets) },
                                        onToggleSelection = { id -> selection = selection.toggle(id) },
                                        gridState = gridState,
                                        fitToTile = state.preferences.fitToTile,
                                        loopAnimations = state.preferences.loopAnimations,
                                        animatedIds = state.animatedIds,
                                        horizontal = sidewaysGrid,
                                    )
                                }
                            }
                            // The Niagara-style timeline scrubber: glide a finger along the strip and
                            // the grid sweeps with it, a bubble naming the month under the finger.
                            // This is what makes a 21,000-item continuous mosaic navigable, and it is
                            // why the pill no longer carries a scrubber of its own -- two position
                            // controls on one screen is one too many, and the edge is the one the owner
                            // asked for.
                            //
                            // Its edge is the Timeline setting (owner #25), and the grid has made room
                            // for it (the gutter above), so it sits beside the photos instead of on
                            // them. That answers the earlier "overlapping with the pictures" the other
                            // way from the fade it used to do: it is always drawn, at one quiet alpha.
                            // restAlpha rather than an outer graphicsLayer alpha, so the pixels and the
                            // touch target stay one thing; the library takes it to full opacity on touch.
                            //
                            // Where a room's edge zone and the strip share pixels, the shell goes first
                            // (its drag runs on the Initial pass) and the strip yields: a horizontal
                            // drag from the left or right 56dp opens the rail or the view options, a
                            // vertical one from the top or bottom 56dp opens the details or the actions.
                            // The strip's own drag runs along its axis, so on LEFT and RIGHT the two
                            // never compete -- the rail stays reachable from under a LEFT strip -- and
                            // on TOP and BOTTOM only the strip's two end 56dp can lose a horizontal
                            // drag to a side room. No topReserve/bottomReserve is spent on it: moving
                            // the shell's zones off the edge would put the details and actions rooms
                            // out of reach of the very pixels they are pulled from.
                            if (stripShown && (!selection.isActive || stripDrawnWhileSelecting(timelinePlacement))) {
                                EdgeTimelineScrubber(
                                    stops = scrubberStops,
                                    itemCount = gridIndexMap.itemCount,
                                    currentIndex = firstVisibleIndex,
                                    onScrubTo = { index ->
                                        // scrollToItem, not animateScrollToItem: the finger is already
                                        // moving, so the grid must track it rather than chase it.
                                        gridScope.launch { gridState.scrollToItem(index) }
                                    },
                                    inkColor = Color.White,
                                    accentColor = MaterialTheme.colorScheme.primary,
                                    bubbleTextColor = MaterialTheme.colorScheme.onPrimary,
                                    restAlpha = EDGE_SCRUBBER_REST_ALPHA,
                                    edge = timelinePlacement.edge(),
                                    modifier = Modifier
                                        .align(
                                            when (timelinePlacement) {
                                                TimelinePlacement.LEFT -> Alignment.CenterStart
                                                TimelinePlacement.RIGHT -> Alignment.CenterEnd
                                                TimelinePlacement.TOP -> Alignment.TopCenter
                                                TimelinePlacement.BOTTOM -> Alignment.BottomCenter
                                            },
                                        )
                                        .then(
                                            if (timelinePlacement == TimelinePlacement.BOTTOM) {
                                                Modifier.navigationBarsPadding()
                                            } else {
                                                Modifier
                                            },
                                        ),
                                )
                            }
                        }
                    }
                    }

                    if (selection.isActive && !paneTakenOver) {
                        SelectionOverlay(
                            selection = selection,
                            selectedAssets = selectedAssets,
                            inTrash = inTrash,
                            actions = actions,
                            onSelectionChange = { selection = it },
                        )
                    }

                    // The only header left, and only when you are somewhere you must be able to
                    // get back OUT of: inside an album, a collection or a tag. Drawn OVER the
                    // mosaic rather than in the Scaffold's topBar slot, so it costs the grid no
                    // height and the root browsing surface stays completely bare.
                    if (!selection.isActive && !paneTakenOver && route != BrowserRoute.Root) {
                        RouteOverlayBar(
                            title = route.title(destination),
                            onBack = { route = BrowserRoute.Root },
                            albumRoute = albumRoute,
                            collectionRoute = collectionRoute,
                            state = state,
                            actions = actions,
                            menuVisible = topMenuVisible,
                            onMenuVisibleChange = { topMenuVisible = it },
                            onPasswordRequest = { passwordRequest = it },
                            onRenameCollection = { renameCollection = it },
                            onLeaveRoute = { route = BrowserRoute.Root },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                // Below a strip along the top edge, which it would otherwise cover.
                                .padding(top = routeBarDrop(stripShown, timelinePlacement, searchVisible).dp),
                        )
                    }

                    // The floating pill from the mockups, replacing the retired bottom nav.
                    // Search still makes sense on the map (it drives which assets flow INTO it, same
                    // as the grid), so only the density control is grid-only -- the pill itself
                    // stays up throughout.
                    if (!selection.isActive && !paneTakenOver) {
                        // On a wide window the pinned rail is a sibling of the grid while these
                        // overlays belong to the whole box, so everything pinned to the left is
                        // inset by the rail's width or it would be drawn over the rail.
                        val railInset = railInsetDp(railPresentation).dp
                        // A strip along the bottom edge takes the row the pill and the discs sit
                        // on, so they all rise by its thickness (zero for every other placement).
                        val chromeLift = floatingChromeLift(stripShown, timelinePlacement)
                        val rangeApplies = timeRangeApplies(destination)
                        // The one way to zoom without a gesture: a stepper that wraps from the
                        // stamps back to one column, since a button has no way to go back with.
                        val toggleDensity: () -> Unit = {
                            val next = wrapRung(activeRung, maxZoomRung)
                            zoomRung = next
                            zoomResidual = 0f
                            actions.onSetGridZoomRung(next)
                        }
                        FloatingPillControl(
                            caption = when {
                                mapOpen -> mapCaption
                                scrubberTrackable ->
                                    pillCaption(renderedAssets, gridIndexMap.assetIndexAt(firstVisibleIndex))
                                else -> ""
                            },
                            onOpenTimeRange = if (rangeApplies) {
                                { timeEditorOpen = !timeEditorOpen }
                            } else {
                                null
                            },
                            onToggleDensity = if (mapOpen) null else toggleDensity,
                            densityState = zoomRungLabel(activeRung),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(start = railInset, bottom = chromeLift.dp),
                        )
                        // The search entry: the round button in the bottom-right corner of the
                        // owner's root-grid reference (2026-10). It used to be the pill's
                        // left-hand button; one entry, not two, so the pill no longer has one.
                        SearchFab(
                            onClick = { searchVisible = !searchVisible },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(end = SEARCH_FAB_MARGIN_END.dp, bottom = (SEARCH_FAB_MARGIN_BOTTOM + chromeLift).dp),
                        )
                        // The quick toggle between the grid and the map (owner #27): the mirror
                        // of the search disc, at the bottom left. Offered only where there are
                        // photos to put on a map. It clears a selection (there is none to hold:
                        // this block is hidden while selecting) and never touches the destination,
                        // the route or the grid's scroll position, so tapping back lands exactly
                        // where it left.
                        if (mapToggleOffered(destination)) {
                            MapToggleFab(
                                showingMap = mapOpen,
                                onClick = { mapOpen = !mapOpen },
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .navigationBarsPadding()
                                    .padding(
                                        start = SEARCH_FAB_MARGIN_END.dp + railInset,
                                        bottom = (SEARCH_FAB_MARGIN_BOTTOM + chromeLift).dp,
                                    ),
                            )
                        }

                        // The time range: its panel (when open) and its chip (while one is set),
                        // stacked above the pill row on the grid and on the map alike, clear of
                        // the nav bar, the pull zone, the pill and both discs.
                        if (rangeApplies && (timeEditorOpen || timeRange != null)) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(
                                        start = railInset + TIME_RANGE_SIDE_MARGIN.dp,
                                        end = TIME_RANGE_SIDE_MARGIN.dp,
                                        bottom = (SEARCH_FAB_MARGIN_BOTTOM + chromeLift).dp +
                                            SEARCH_FAB_DP.dp + TIME_RANGE_GAP.dp,
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(TIME_RANGE_GAP.dp),
                            ) {
                                if (timeEditorOpen) {
                                    TimeRangeEditor(
                                        domain = rangeDomain,
                                        range = timeRange,
                                        onApply = { timeRange = it },
                                        onClear = { timeRange = null },
                                        onDismiss = { timeEditorOpen = false },
                                    )
                                }
                                timeRange?.let { range ->
                                    TimeRangeChip(
                                        range = range,
                                        shownCount = scrubberAssets.size,
                                        onEdit = { timeEditorOpen = !timeEditorOpen },
                                        onClear = {
                                            timeRange = null
                                            timeEditorOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
    }

    if (createCollectionVisible) {
        TextEntryDialog(
            title = "New collection",
            label = "Collection name",
            confirmLabel = "Create",
            onDismiss = { createCollectionVisible = false },
            onConfirm = {
                actions.onCreateCollection(it)
                createCollectionVisible = false
            },
        )
    }
    renameCollection?.let { collection ->
        TextEntryDialog(
            title = "Rename collection",
            label = "Collection name",
            initialValue = collection.name,
            confirmLabel = "Rename",
            onDismiss = { renameCollection = null },
            onConfirm = {
                actions.onRenameCollection(collection.id, it)
                route = BrowserRoute.Collection(collection.id, it.trim())
                renameCollection = null
            },
        )
    }
    renameAsset?.let { asset ->
        TextEntryDialog(
            title = "Rename file",
            label = "File name",
            initialValue = asset.displayName,
            confirmLabel = "Rename",
            onDismiss = { renameAsset = null },
            onConfirm = {
                actions.onRenameAsset(asset, it)
                renameAsset = null
                selection = selection.clear()
            },
        )
    }
    addToCollectionIds?.let { ids ->
        CollectionPickerDialog(
            collections = state.library.collections,
            onDismiss = { addToCollectionIds = null },
            onCreateCollection = { name ->
                actions.onCreateCollection(name)?.let { collectionId ->
                    actions.onAddToCollection(collectionId, ids)
                }
                addToCollectionIds = null
                selection = selection.clear()
            },
            onChoose = { collectionId ->
                actions.onAddToCollection(collectionId, ids)
                addToCollectionIds = null
                selection = selection.clear()
            },
        )
    }
    addTagIds?.let { ids ->
        TextEntryDialog(
            title = "Tag selected media",
            label = "Tag",
            confirmLabel = "Add tag",
            suggestions = state.library.allTags,
            onDismiss = { addTagIds = null },
            onConfirm = {
                actions.onAddTag(ids, it)
                addTagIds = null
                selection = selection.clear()
            },
        )
    }
    passwordRequest?.let { request ->
        PasswordDialog(
            title = when (request.action) {
                PasswordAction.PROTECT -> "Protect ${request.folderName}"
                PasswordAction.UNLOCK -> "Unlock ${request.folderName}"
                PasswordAction.REMOVE -> "Remove protection"
            },
            confirmLabel = when (request.action) {
                PasswordAction.PROTECT -> "Protect"
                PasswordAction.UNLOCK -> "Unlock"
                PasswordAction.REMOVE -> "Remove"
            },
            failureMessage = if (request.action == PasswordAction.PROTECT) {
                "Use at least 6 characters"
            } else {
                "Incorrect password or temporarily locked"
            },
            onDismiss = { passwordRequest = null },
            onConfirm = { password ->
                when (request.action) {
                    PasswordAction.PROTECT -> actions.onProtectFolder(request.folderKey, password).isSuccess
                    PasswordAction.UNLOCK -> actions.onUnlockFolder(request.folderKey, password)
                    PasswordAction.REMOVE -> actions.onRemoveFolderProtection(request.folderKey, password)
                }
            },
            onSuccess = {
                if (request.action == PasswordAction.UNLOCK) {
                    route = BrowserRoute.DeviceAlbum(request.folderKey, request.folderName)
                    // Unlocking from the Albums screen would otherwise leave Albums on top of the
                    // album it just opened.
                    legacyScreen = null
                    mapOpen = false
                }
                passwordRequest = null
            },
        )
    }
}

/**
 * The map pane: [assets] on a map, in the grid's place (see [PhotoMapPane]).
 *
 * What it is framed to, and how a photo opens from it, come from the [SpatialExperience] that
 * [GalleryScreen] already provides to [DiscoverScreen]'s Places card: that is the privacy-filtered
 * list of browsable photos, the geo index and the way to open the viewer, all reachable through
 * composition with nothing new to thread past a file boundary. [assets] is NOT read from there --
 * the map shows what the grid was showing, with the search, the route and the time range applied.
 *
 * @param settled whether the shell is at rest with every room closed; the Connect build creates
 *   its street map only then.
 */
@Composable
private fun GalleryMapContent(assets: List<MediaAsset>, settled: Boolean) {
    val spatial = LocalSpatialExperience.current
    if (spatial != null) {
        PhotoMapPane(
            assets = assets,
            frameAssets = spatial.assets,
            geoState = spatial.geoState,
            onIndexLocations = spatial.onIndexLocations,
            onOpenAsset = spatial.onOpenAsset,
            settled = settled,
        )
    } else {
        // Unreachable in practice -- GalleryScreen always provides LocalSpatialExperience around
        // GalleryBrowser -- but a composition local's default is null, and a screen that trusted
        // an implicit non-null here would crash instead of degrading if that ever changed.
        GalleryMessage("Map is unavailable here")
    }
}

/**
 * A pane under the background-job shade.
 *
 * The shade is composed here, around the pane, because it used to be drawn only over the root
 * grid while the shell kept reserving its strip for every screen: Settings > Data showed no
 * progress for the jobs it starts, and the top-edge pull was blocked by a shade that was not there.
 *
 * Unlike the grid, which the shade deliberately covers rather than moves, a pane has a back
 * chevron and a title in exactly the strip the shade would cover, so the pane is pushed down by
 * the shade's own height ([reserve]) on the shade's own curve instead of being drawn under it.
 */
@Composable
private fun ShadedPane(
    activities: List<BackgroundActivity>,
    shadeState: ShadeState,
    onShadeStateChange: (ShadeState) -> Unit,
    reserve: Dp,
    content: @Composable () -> Unit,
) {
    // The shade's own settle curve (320 ms, 0.4/0/0.2/1), so the pane moves with its edge.
    val top by animateDpAsState(
        targetValue = reserve,
        animationSpec = tween(durationMillis = 320, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
        label = "pane-shade-reserve",
    )
    ActivityShade(activities = activities, state = shadeState, onStateChange = onShadeStateChange) {
        Box(Modifier.fillMaxSize().padding(top = top)) { content() }
    }
}

/**
 * The nav rail's PERSISTENT presentation -- [NavRailPresentation.PINNED] or
 * [NavRailPresentation.COLLAPSED] -- rendered as an ordinary sibling beside the grid rather than
 * inside SpatialShell's pull-out room. [DestinationRailPanel] already fills whatever box it is
 * given, which is what lets it be reused here inside a fixed-width [Box] instead of the room's
 * own geometry, with no change to that composable (a file this task does not own) at all.
 *
 * [NavRailPresentation.COLLAPSED] does NOT reuse [DestinationRailPanel] -- its own doc is explicit
 * that collapsed means "labels and covers are hidden", which a panel built only for the
 * always-expanded room has no way to do -- so collapsed is its own narrow strip carrying just the
 * expand affordance.
 *
 * The room itself (`left =` on `SpatialShell`, in [GalleryBrowser]) is untouched: on a wide window
 * this rail sits to its own left, so a peek dragged in from the true screen edge still opens the
 * room on top of it. That is a real, acknowledged redundancy -- SpatialShell's own controller has
 * no way from here to disable one edge's room without touching the shell integration itself, and
 * this task's method is explicit that restructuring a working integration is the wrong trade for
 * a cosmetic double-rail a user would have to deliberately drag in to ever see.
 */
@Composable
private fun PersistentNavRail(
    presentation: NavRailPresentation,
    items: List<dev.aarso.cellshell.WheelItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onReselect: () -> Unit = {},
    paneOpen: Boolean = false,
    onLeavePane: () -> Unit = {},
    onTurning: (Boolean) -> Unit = {},
) {
    if (presentation == NavRailPresentation.COLLAPSED) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(COLLAPSED_RAIL_WIDTH.dp)
                .background(Color.Black)
                .clickable(onClickLabel = "Expand navigation", onClick = onExpand),
        ) {
            Text(
                "»",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp),
            )
        }
        return
    }
    Box(Modifier.fillMaxHeight().width(PINNED_RAIL_WIDTH.dp)) {
        DestinationRailPanel(
            items = items,
            selectedId = selectedId,
            onSelect = onSelect,
            onOpenSettings = onOpenSettings,
            onReselect = onReselect,
            paneOpen = paneOpen,
            onLeavePane = onLeavePane,
            onTurning = onTurning,
        )
        // The collapse affordance: one tap back to NavRailPresentation.COLLAPSED. Pinned is a
        // standing choice, not a one-way door -- see navRailCollapsed's own doc in
        // GalleryPreferences.kt for why this survives rotation and relaunch instead of resetting.
        Text(
            "«",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 10.dp)
                .clickable(onClickLabel = "Collapse navigation", onClick = onCollapse),
        )
    }
}

/**
 * How far in from the left edge things pinned to the bottom left start, so they are not drawn over
 * a rail that is a sibling of the grid: the pinned rail's width, the collapsed strip's, or nothing
 * for the pull-out room (which draws over everything while it is open anyway).
 */
internal fun railInsetDp(presentation: NavRailPresentation): Int = when (presentation) {
    NavRailPresentation.PINNED -> PINNED_RAIL_WIDTH
    NavRailPresentation.COLLAPSED -> COLLAPSED_RAIL_WIDTH
    NavRailPresentation.PULL_OUT -> 0
}

/** A reasonable fixed width for a permanently-visible rail -- wide enough for the word rail's
 * labels and bullet, the same content the pull-out room already shows at full width. */
private const val PINNED_RAIL_WIDTH = 260

/** Just wide enough for a touch/click target and the expand glyph; the whole point of collapsed
 * is that it gives almost all of that width back to the grid. */
private const val COLLAPSED_RAIL_WIDTH = 56

/**
 * Selection chrome, built to the owner's mockup (2026-08-18, with its CSS and Android export).
 *
 * It was a `CenterAlignedTopAppBar`, which meant that choosing photos shoved the whole grid down
 * by an app bar's height -- on the one screen whose entire job is showing photos, and at the exact
 * moment the user is looking hardest at them. Floating it costs the grid nothing.
 *
 * Three pieces, and the mockup is specific about all three:
 *
 * - **Top left**, a black bar bled to the top and left edges, 209dp wide, square-cornered, with a
 *   soft shadow under it. Three 30dp white glyphs on a 46dp pitch, starting 44dp in — that leading
 *   run of bare black is in the mockup and is what makes the bar read as a cut-out rather than as
 *   a toolbar.
 * - **Top right**, on the toolbar's line, a plain rounded bar sized to its content: the count at
 *   28sp and `SELECTED` at 20sp uppercase and half opacity. It has no ✕; the bar itself clears the
 *   selection. (It was a 44dp pill across the bottom left, with a dismiss ✕, in the first mockup.)
 * - **Bottom right**, the trash: black, **square-cornered**, flush into the corner, separated from
 *   the count bar by the height of the screen. The one destructive control, as far from everything else
 *   as the screen allows and shaped differently from everything else so the hand knows it.
 *
 * The shapes differ on purpose. The count bar is rounded and the trash is not; that is the only
 * signal in the design that distinguishes "how many" from "destroy them", and it survives being
 * glanced at.
 */
@Composable
internal fun BoxScope.SelectionOverlay(
    selection: GallerySelection,
    selectedAssets: List<MediaAsset>,
    inTrash: Boolean,
    actions: GalleryActions,
    onSelectionChange: (GallerySelection) -> Unit,
) {
    // ---- top-left: what you can DO with the selection ----
    // Hangs from the top edge and coves into it — the [SelectionToolbarShape] carries the exact
    // flare from the mockup, so it reads as cut into the edge rather than as a card laid on top.
    // Flush to y=0 with the mockup; the glyphs sit at the mockup's own y, no status-bar inset,
    // because the selection overlay is shown in the immersive chrome where the bar is hidden.
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = TOOLBAR_LEFT.dp)
            .shadow(SELECTION_BAR_SHADOW.dp, SelectionToolbarShape, clip = false)
            .background(Color.Black, SelectionToolbarShape)
            .size(width = TOOLBAR_DESIGN_W.dp, height = TOOLBAR_DESIGN_H.dp),
    ) {
        if (!inTrash) {
            // Exactly the three glyphs the owner supplied — zip, move, copy — in the mockup's own
            // slot order, on its 46dp pitch. Everything else a selection can do lives in the
            // actions room at the bottom edge.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = TOOLBAR_GLYPH_INSET.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionGlyph(R.drawable.ic_action_zip, "Export as zip") {
                    actions.onExportZip(selectedAssets)
                }
                SelectionGlyph(R.drawable.ic_action_move, "Move to folder") {
                    actions.onMoveToFolder(selectedAssets)
                }
                SelectionGlyph(R.drawable.ic_action_copy, "Copy to folder") {
                    actions.onCopyToFolder(selectedAssets)
                }
            }
        }
    }

    // ---- top right, beside the toolbar: how many ----
    // The owner's later mockups put the count on the SAME line as the toolbar, at the right, with
    // a run of bare photograph between the two; it used to sit alone across the bottom left.
    //
    // A PLAIN rounded bar, and that is deliberate after getting it wrong once: the first attempt
    // mirrored the old bottom-left pill's cove cap so it would face the toolbar, and rendering it
    // showed why the mockups do not draw one there. The cap hangs BELOW the bar it caps, so at the
    // top of the screen it became a spike pointing down into the photographs, and its flare
    // reached far enough left to close the gap and fuse the count and the toolbar into one black
    // band. The cove is an edge-meeting device; two of them meeting each other in mid-air is not
    // what it is for.
    //
    // Sized to its content rather than to a fixed width, which a plain shape allows and a cove
    // shape did not: the vector cove has to be drawn at its design aspect or its curve distorts,
    // whereas a rounded rectangle can be any width. So "3 SELECTED" is a short bar and "9999
    // SELECTED" a long one, instead of every count padding out to the width of the longest.
    //
    // No ✕. The mockups do not draw one, and inventing chrome they leave out is exactly what this
    // pass is correcting — so the bar ITSELF clears the selection: the same target, the same
    // gesture, one fewer glyph than the design asks for. Back also clears it (see the BackHandler
    // in GalleryBrowser), which is what a person reaches for first anyway.
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = -COUNT_RIGHT.dp, y = COUNT_TOP.dp)
            .height(PILL_DESIGN_H.dp)
            .clip(RoundedCornerShape(COUNT_RADIUS.dp))
            .background(Color.Black)
            .clickable(
                onClickLabel = "Clear selection",
                onClick = { onSelectionChange(selection.clear()) },
            )
            .padding(horizontal = COUNT_TEXT_INSET.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${selection.count}",
            color = Color.White,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 28.sp, lineHeight = 36.sp),
        )
        Text(
            text = "SELECTED",
            color = Color.White.copy(alpha = 0.5f),
            // Uppercase in the string rather than via textTransform, and at half opacity: the
            // count is the number you read and this is the unit beside it.
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 20.sp, lineHeight = 26.sp),
            modifier = Modifier.padding(start = 6.dp),
        )
    }

    // ---- bottom-right: the destructive one, alone and shaped differently ----
    // Rises from the bottom edge and coves into it ([SelectionTrashShape]) — the inverse of the
    // toolbar, and shaped unlike the pill on purpose. Separated from the pill by a gap of bare
    // photograph. The one destructive control, shaped so the hand knows it before the eye reads it.
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = -TRASH_RIGHT.dp)
            .shadow(SELECTION_BAR_SHADOW.dp, SelectionTrashShape, clip = false)
            // RED, not black. Every other surface in this overlay is black; this one is not, and
            // that is the whole point — the mockups colour the single destructive control and
            // nothing else, so it is told apart before it is read. It already differs in shape
            // from the pill; colour is the second signal, and the two together mean a hand
            // reaching for the corner cannot mistake it for the count.
            .background(SELECTION_TRASH_RED, SelectionTrashShape)
            .size(width = TRASH_BOX_W.dp, height = TRASH_DESIGN_H.dp)
            .clickable(
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                onClickLabel = if (inTrash) "Delete permanently" else "Move to trash",
                onClick = {
                    if (inTrash) actions.onDeletePermanently(selectedAssets) else actions.onMoveToTrash(selectedAssets)
                    onSelectionChange(selection.clear())
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_action_delete),
            contentDescription = if (inTrash) "Delete permanently" else "Move to trash",
            tint = Color.White,
            modifier = Modifier
                .offset(y = TRASH_GLYPH_DROP.dp)
                .size(SELECTION_TRASH_GLYPH.dp),
        )
    }
}

/**
 * One 30dp white glyph in the top bar.
 *
 * A bare `Icon` with a click, not an `IconButton`: Material's button carries a 48dp box and a
 * ripple, and three of them would space themselves rather than sitting on the mockup's 46dp pitch.
 * The touch target is recovered by the padding, which lands at 46dp square.
 */
@Composable
private fun SelectionGlyph(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = description,
        tint = Color.White,
        modifier = Modifier
            .clickable(onClickLabel = description, onClick = onClick)
            // Padding OUTSIDE the size, and on both axes: this is what makes each glyph a 46dp
            // square target sitting on the mockup's 46dp pitch. It was vertical-only, which left
            // a 30dp-wide target and made the row space its glyphs by gaps instead of by pitch.
            .padding(SELECTION_GLYPH_PAD.dp)
            .size(SELECTION_GLYPH.dp),
    )
}

// ---- selection chrome geometry, taken from the owner's mockup (440 x 956 dp) ----
// Named rather than inlined because they are a spec someone handed over, not values that were
// tuned here. The shapes themselves live in [com.fotoxplorr.app.hyle.HyleNotch]; these are the
// mockup's own edge offsets and content insets that position them and their glyphs.

/** `box-shadow: 0 4px 8px` under the bar, `0 -4px 4px` above the trash — one value reads as deliberate. */
private const val SELECTION_BAR_SHADOW = 8

/** Toolbar hangs 8dp in from the left edge (mockup `left: 8px`). */
private const val TOOLBAR_LEFT = 8

/**
 * Glyphs: 30dp icon + 8dp all round = a 46dp target on a 46dp pitch. The first target's icon must
 * land at the toolbar's local x=44, so the padded target starts at 44 - 8 = 36.
 */
private const val TOOLBAR_GLYPH_INSET = 36
private const val SELECTION_GLYPH = 30
private const val SELECTION_GLYPH_PAD = 8

// Pill and trash: the vector shapes carry the designer's own curves, but the mockup PNG the owner
// diffs against draws them a shade smaller than the SVG export. Rendering each vector into a box a
// little narrower than its native design width lands the cap peak and the notch width on the PNG's
// own extents, without re-fitting the curves. PILL native is 316dp, trash 134dp.
/**
 * Count bar: 8dp in from the right edge, 6dp down from the top.
 *
 * Not flush to the corner the way the toolbar is flush to its own. The toolbar coves INTO the top
 * edge, so it has to touch it; this is a plain bar and would just look stuck to the corner. The
 * 6dp drop also centres it against the toolbar's glyph row, which is what the mockups show.
 */
private const val COUNT_RIGHT = 8
private const val COUNT_TOP = 6

/**
 * 20dp on a 44dp bar: rounded, but not a stadium. A full-radius end would read as a chip, and the
 * mockups draw a bar whose corners are clearly softer than its height.
 */
private const val COUNT_RADIUS = 20

/** Even margin either side of the count, since neither end carries a cove any more. */
private const val COUNT_TEXT_INSET = 18

/** Trash: right edge 15dp in; drawn into 110dp (native 134) so the notch matches the PNG's width. */
private const val TRASH_RIGHT = 15
private const val TRASH_BOX_W = 110
private const val SELECTION_TRASH_GLYPH = 32

// Fixed on purpose (semantic): destructive red stays the same whatever accent is chosen.
/**
 * The trash's fill, sampled from the mockups — the one saturated colour anywhere in this app's
 * chrome, spent on the one action that destroys something. Deliberately not a Material error
 * colour: those follow the theme, and this must be the same red whatever palette the user picks,
 * because "the red one deletes" stops being a rule the moment it is sometimes not red.
 */
private val SELECTION_TRASH_RED = Color(0xFFE0332A)

/** Nudge the trash glyph up from dead centre to sit with the mockup's own placement. */
private const val TRASH_GLYPH_DROP = -2

fun BrowserRoute.title(destination: HyleDestination): String = when (this) {
    BrowserRoute.Root -> destination.label
    is BrowserRoute.DeviceAlbum -> name
    is BrowserRoute.Collection -> name
    is BrowserRoute.Smart -> album.title()
    is BrowserRoute.Tag -> "#$tag"
}

/**
 * Does this asset satisfy the search box?
 *
 * Was a single `contains` over filename, MIME, folder and tags, which meant the two most useful
 * things the app had already computed -- what is IN the picture, and what it SAYS -- were invisible
 * to the one feature that wanted them, and a phrase like "flowers in August" was looked for
 * verbatim in a filename. It now parses into constraints and matches across every surface,
 * including AI labels and OCR text.
 *
 * The parse is memoised on the raw string because this is called once per asset per keystroke:
 * re-parsing 22k times for one query is exactly the shape that turns typing into a stutter.
 */
internal fun MediaAsset.matchesGallerySearch(
    query: String,
    tags: Set<String>,
    recognition: RecognitionIndex = RecognitionIndex.EMPTY,
    favouriteIds: Set<MediaId> = emptySet(),
    animatedIds: Set<MediaId> = emptySet(),
    archivedIds: Set<MediaId> = emptySet(),
): Boolean {
    val parsed = rememberParsedQuery(query)
    if (parsed.isEmpty) return true
    return matchesQuery(
        parsed,
        SearchDocument(
            mediaId = id,
            name = displayName,
            folder = folderIdentity(this).displayName,
            mimeType = mimeType,
            takenAtMillis = dateTakenMillis,
            tags = tags,
            labels = recognition.labelsByMedia[id].orEmpty().toSet(),
            text = recognition.textOf(id),
            // Pre-converted on the index (Lab computed once, off-thread): a map lookup per asset.
            palette = recognition.searchPaletteFor(id),
            // P0-16: the same category set the now-deleted search/GallerySearch.kt (P0-14) had,
            // this time actually reachable from the live search path -- see docs/handoff.
            categories = buildSet {
                if (isVideo) add("video") else add("photo")
                if (id in animatedIds) add("animated")
                if (isFavorite || id in favouriteIds) add("favourite")
                if (id in recognition.petMediaIds) add("pet")
                if (id in recognition.peopleMediaIds) add("person")
                if (id in recognition.identityMediaIds) add("document")
                if (id in archivedIds) add("archived")
                if (tags.isEmpty()) add("untagged")
                if (folderIdentity(this@matchesGallerySearch).displayName.contains("screenshot", ignoreCase = true)) {
                    add("screenshot")
                }
            },
            camera = "",
            iso = null,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
        ),
    )
}

/**
 * One-entry parse cache.
 *
 * Composition is single-threaded and every asset in a pass shares the same query string, so a
 * one-slot memo turns 22k parses per keystroke into one. Guarded anyway, because a background
 * projection could reach this from another thread and a torn read here would only ever cost a
 * redundant parse -- never a wrong answer, since the value is derived purely from the key.
 */
@Volatile private var cachedRawQuery: String? = null
@Volatile private var cachedParsedQuery: ParsedQuery? = null

internal fun rememberParsedQuery(raw: String): ParsedQuery {
    val hit = cachedParsedQuery
    if (hit != null && cachedRawQuery == raw) return hit
    val parsed = parseSearchQuery(raw)
    cachedRawQuery = raw
    cachedParsedQuery = parsed
    return parsed
}

@Composable
private fun GalleryEmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    progress: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress) CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * The strip naming Android's own partial-access grant, where the gap it describes actually shows
 * up -- the grid that is missing photos -- rather than only in a Settings row nobody visits
 * without already suspecting something is wrong.
 */
@Composable
private fun PartialAccessStrip(onChooseMore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C22))
            .clickable(onClick = onChooseMore)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            HelpCopy.Hint.PICKED_ONLY,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Choose more",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The header for the browsing surface: where you are, and the actions for it.
 *
 * Deliberately a plain Row rather than a `CenterAlignedTopAppBar`. The app bar's centred title
 * and its navigation slot were built around a hamburger that no longer exists — the rail is an
 * edge-drag away — and a centred title over an off-centre back affordance reads as chrome for
 * its own sake. Left-aligned, one line, no container colour of its own: the header sits on the
 * grid's black rather than drawing a bar across the top of it.
 */
@Composable
private fun BrowserHeader(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * The content of the Settings pane: the tabs, with the intents that leave the app wired up.
 *
 * One depth, nine tabs. It used to be two depths: a compact panel plus everything else behind an
 * "All settings…" button, which is what made that second depth feel strange (owner, 2026-08-14)
 * -- it was not a category, it was an overflow, and an overflow is what a surface grows when it
 * has no structure to put things in. It lived in a room off the top edge until the owner asked
 * for it to take over the place the photos are (2026-10); the title and the way back are the
 * hosting [PaneScaffold]'s, so the tabs draw no header of their own here.
 */
@Composable
private fun SettingsRoom(
    state: GalleryUiState,
    actions: GalleryActions,
    onOpenLegacyScreen: (LegacyScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SettingsTabsRoom(
        state = state,
        actions = actions,
        onOpenLegacyScreen = onOpenLegacyScreen,
        modifier = modifier,
        showHeader = false,
        // Both leave the app, so both are ordinary implicit intents rather than anything this
        // app handles itself -- and both are runCatching-guarded, because a device with no mail
        // client or no browser is a real device and must not crash for asking.
        onOpenSupport = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:$SUPPORT_EMAIL")
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Fotoz")
                    },
                )
            }
        },
        onOpenMoreApps = {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://asystemofcells.com"),
                    ),
                )
            }
        },
    )
}

/**
 * What the pill says between its two buttons.
 *
 * The pill used to hold a horizontal scrubber there. That became a second position control the
 * moment the edge scrubber arrived, and two of them disagreeing about where you are is worse
 * than either alone — so the space now reports position in words instead of competing to set
 * it: which month you are looking at, and how far through.
 */
private fun pillCaption(assets: List<MediaAsset>, firstVisibleIndex: Int): String {
    if (assets.isEmpty()) return ""
    val index = firstVisibleIndex.coerceIn(0, assets.lastIndex)
    val month = monthLabel(assets[index])
    return "$month · ${index + 1} of ${assets.size}"
}

/**
 * The one piece of header this app still draws while browsing, and it appears only inside a
 * drill-down route (an album, a collection, a tag).
 *
 * The root surface has none: its title repeated the word the rail shows selected and the pill
 * already says, and its 3-dot menu's last item merely opened a room one edge-drag away (owner,
 * 2026-08-14). A drill-down route is different in kind — `BackHandler` is otherwise the only way
 * out of one, and a surface you can enter but not visibly leave is a trap.
 *
 * It draws OVER the mosaic rather than living in the Scaffold's `topBar` slot, so it costs the
 * grid no height, and it carries only what is genuinely scoped to the route it is showing. The
 * items that were merely global (refresh, backup, settings) did not move here: refresh is the
 * shake, backup is in the Library screen, settings is a pane reached from the rail.
 */
@Composable
private fun RouteOverlayBar(
    title: String,
    onBack: () -> Unit,
    albumRoute: BrowserRoute.DeviceAlbum?,
    collectionRoute: BrowserRoute.Collection?,
    state: GalleryUiState,
    actions: GalleryActions,
    menuVisible: Boolean,
    onMenuVisibleChange: (Boolean) -> Unit,
    onPasswordRequest: (PasswordRequest) -> Unit,
    onRenameCollection: (BrowserRoute.Collection) -> Unit,
    onLeaveRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasRouteActions = albumRoute != null || collectionRoute != null
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.background(SCRIM_PILL, androidx.compose.foundation.shape.CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .padding(start = 8.dp)
                .background(SCRIM_PILL, androidx.compose.foundation.shape.CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (hasRouteActions) {
            IconButton(
                onClick = { onMenuVisibleChange(true) },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(SCRIM_PILL, androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Actions for this ${'$'}title", tint = Color.White)
            }
            DropdownMenu(expanded = menuVisible, onDismissRequest = { onMenuVisibleChange(false) }) {
                albumRoute?.let { album ->
                    val protected = album.key in state.lockedFolders
                    val unlocked = album.key in state.unlockedFolders
                    when {
                        !protected -> DropdownMenuItem(
                            text = { Text("Make folder private") },
                            onClick = {
                                onMenuVisibleChange(false)
                                onPasswordRequest(
                                    PasswordRequest(PasswordAction.PROTECT, album.key, album.name),
                                )
                            },
                        )
                        unlocked -> {
                            DropdownMenuItem(
                                text = { Text("Lock now") },
                                onClick = {
                                    onMenuVisibleChange(false)
                                    actions.onLockFolder(album.key)
                                    onLeaveRoute()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove protection") },
                                onClick = {
                                    onMenuVisibleChange(false)
                                    onPasswordRequest(
                                        PasswordRequest(PasswordAction.REMOVE, album.key, album.name),
                                    )
                                },
                            )
                        }
                    }
                }
                collectionRoute?.let { collection ->
                    DropdownMenuItem(
                        text = { Text("Rename collection") },
                        onClick = {
                            onMenuVisibleChange(false)
                            onRenameCollection(collection)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete collection") },
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onDeleteCollection(collection.id)
                            onLeaveRoute()
                        },
                    )
                }
            }
        }
    }
}

/** Ground under the drill-down bar's controls, so they stay legible over any photo beneath. */
private val SCRIM_PILL = Color.Black.copy(alpha = 0.55f)

/**
 * How visible the edge scrubber is, always: it has a gutter of its own now, so it no longer has to
 * stay out of the photos' way by fading. Quiet, and full strength on touch (the library's doing).
 */
private const val EDGE_SCRUBBER_REST_ALPHA = 0.6f

/** The time range panel's and chip's inset from the sides, and the gap between them and the pill row. */
private const val TIME_RANGE_SIDE_MARGIN = 12
private const val TIME_RANGE_GAP = 8
