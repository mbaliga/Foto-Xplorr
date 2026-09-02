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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import com.fotoxplorr.app.R
import com.fotoxplorr.app.ui.HyleGrotesk
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import dev.aarso.cellshell.SpatialMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.hyle.FloatingPillControl
import com.fotoxplorr.app.hyle.ActivityShade
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.hyle.BackgroundActivity
import com.fotoxplorr.app.hyle.ShadeState
import com.fotoxplorr.app.hyle.shadeHeight
import com.fotoxplorr.app.hyle.SelectionToolbarShape
import com.fotoxplorr.app.hyle.SelectionTrashShape
import com.fotoxplorr.app.hyle.TOOLBAR_DESIGN_W
import com.fotoxplorr.app.hyle.TOOLBAR_DESIGN_H
import com.fotoxplorr.app.hyle.PILL_DESIGN_H
import com.fotoxplorr.app.hyle.TRASH_DESIGN_W
import com.fotoxplorr.app.hyle.TRASH_DESIGN_H
import androidx.compose.foundation.layout.offset
import dev.aarso.cellshell.EdgeTimelineScrubber
import dev.aarso.cellshell.RoomEdge
import dev.aarso.cellshell.ShakeToRefresh
import dev.aarso.cellshell.ParkStyle
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.recognition.RecognitionIndex
import com.fotoxplorr.app.search.ParsedQuery
import com.fotoxplorr.app.search.SearchDocument
import com.fotoxplorr.app.search.matchesQuery
import com.fotoxplorr.app.search.parseSearchQuery
import com.fotoxplorr.app.recognition.RecognitionProgress
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.SpatialExperience
import com.fotoxplorr.app.spatial.PlacesScreen
import kotlinx.coroutines.launch
// ---- adaptive package: window sizing, the pinch/scroll zoom ladder, and keyboard shortcuts.
// See each file's own doc for why this stays pure Kotlin with no Compose/Android import of its
// own -- the mapping into real Compose types (Key, WindowMetrics, ParkStyle) happens only here,
// at this call site, same as this file already does for `dev.aarso.cellshell`'s ParkStyle.
import com.fotoxplorr.app.adaptive.ChromeMotion
import com.fotoxplorr.app.adaptive.GalleryShortcut
import com.fotoxplorr.app.adaptive.GalleryShortcutKey
import com.fotoxplorr.app.adaptive.GalleryZoomLevel
import com.fotoxplorr.app.adaptive.MoveDirection
import com.fotoxplorr.app.adaptive.NavRailPresentation
import com.fotoxplorr.app.adaptive.ZoomLadder
import com.fotoxplorr.app.adaptive.chromeMotionFor
import com.fotoxplorr.app.adaptive.galleryShortcutFor
import com.fotoxplorr.app.adaptive.navRailPresentation
import com.fotoxplorr.app.adaptive.step
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
    val scanState: ScanState,
    val preferences: GalleryPreferencesState,
    /** On-device recognition results backing the Pets / People / Identity destinations. */
    val recognition: RecognitionIndex = RecognitionIndex.EMPTY,
    val recognitionProgress: RecognitionProgress = RecognitionProgress(),
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
    val onSetGridColumns: (Int) -> Unit,
    val onSetBlurSensitive: (Boolean) -> Unit,
    val onSetHideSensitive: (Boolean) -> Unit,
    val onSetShowVideos: (Boolean) -> Unit,
    val onSetTimelineGrouping: (TimelineGrouping) -> Unit,
    val onSetThemeMode: (ThemeMode) -> Unit,
    val onSetAccentPalette: (AccentPalette) -> Unit,
    val onSetSlideshowInterval: (Int) -> Unit,
    val onSetDefaultDestination: (HyleDestination) -> Unit,
    val onSetKeepScreenOn: (Boolean) -> Unit,
    val onSetSlideshowShuffle: (Boolean) -> Unit,
    val onSetAutoplayVideos: (Boolean) -> Unit,
    val onSetFitToTile: (Boolean) -> Unit,
    val onSetLoopAnimations: (Boolean) -> Unit,
    val onSetLongPressPreview: (Boolean) -> Unit,
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
    val spatialAssets = remember(state.assets) { state.assets.filterNot { it.isTrashed } }

    CompositionLocalProvider(
        LocalSpatialExperience provides SpatialExperience(
            assets = spatialAssets,
            geoState = geoState,
            onIndexLocations = {
                spatialScope.launch { geoRepository.indexMissing(spatialAssets) }
            },
            onOpenAsset = actions.onOpenAsset,
        ),
    ) {
        when {
            !state.permissionGranted -> GalleryEmptyState(
                title = "Your gallery stays on this device",
                message = "Choose the photos and videos Foto Xplorr may index. Nothing is uploaded.",
                actionLabel = "Choose media",
                onAction = actions.onRequestPermission,
            )
            state.assets.isEmpty() && state.scanState is ScanState.Scanning -> GalleryEmptyState(
                title = "Building your library",
                message = "Scanning local photos and videos…",
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
                message = "Foto Xplorr could not find any permitted photos or videos.",
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
 * left edge that the grid lifts and parts to reveal. Settings is the same thing off the right.
 * Neither is a screen — switching to one pushes no back-stack entry, the grid stays alive and
 * visible behind it, and dragging it back is the way out. The motion comes from
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
 *  - The Material settings `AlertDialog`. Settings live in the right room now, in the app's own
 *    theme — a modal window floating over a room is two contradictory ideas of where you are,
 *    and it was also the light-themed screen inside a black app the owner reported.
 *
 * The top edge is deliberately left empty. It is reserved, and no other gesture may claim the
 * pull-down space — which is why refresh is [ShakeToRefresh] and not a pull.
 */
@Composable
private fun GalleryBrowser(
    state: GalleryUiState,
    actions: GalleryActions,
) {
    var destination by remember { mutableStateOf(state.preferences.defaultDestination) }
    var route by remember { mutableStateOf<BrowserRoute>(BrowserRoute.Root) }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(GallerySelection()) }
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
    // Hoisted because the shell must reserve the same strip the shade's pull gesture lives in
    // -- see topReserve below. One state, so the shade and the gesture zone cannot differ.
    var shadeState by remember { mutableStateOf(ShadeState.COLLAPSED) }

    // ---- pinch / Ctrl+scroll zoom ladder (grid density <-> Calendar <-> Map) ----
    //
    // One ladder instance for the browsing surface's whole lifetime -- it is stateless arithmetic
    // over minColumns..maxColumns, so there is nothing to key it on. [zoomLevel] is the ladder's
    // CURRENT rung; [zoomResidual] is the sub-rung motion [ZoomLadder.step] has not yet spent (see
    // its own doc for why that has to survive between gesture frames rather than resetting each
    // one). Seeded from the persisted column count so a pinch continues from wherever the grid
    // density preference already was, rather than snapping to whatever rung index 0 happens to be.
    val zoomLadder = remember { ZoomLadder(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS) }
    var zoomLevel by remember {
        mutableStateOf<GalleryZoomLevel>(GalleryZoomLevel.Grid(state.preferences.gridColumns))
    }
    var zoomResidual by remember { mutableStateOf(0f) }
    // Whether the grid is showing date-group headers (the "Timeline" entry in the view switcher).
    // Orthogonal to the ladder's own rungs -- it is still a Grid rung underneath, just drawn with
    // headers -- so it is its own flag rather than a fourth kind of [GalleryZoomLevel].
    var timelineHeadersOn by remember { mutableStateOf(false) }

    // Set by the pending-search seeding effect (further down, after the route-change reset it
    // has to outrun) immediately before it changes `route`, so the reset that route change
    // triggers knows to leave the seeded query alone this once. Consumed by that reset and by
    // nothing else.
    var seededRouteChange by remember { mutableStateOf(false) }

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
    val notificationReserve = shadeHeight(shadeState, activities.size).dp

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
    val destinationAssets = remember(
        destination, query, state.assets, state.favoriteIds, state.sensitiveIds,
        state.library, state.lockedFolders, state.unlockedFolders, state.preferences,
        state.recognition,
    ) {
        destinationAssets(destination, state, query)
    }
    val currentAssets = remember(
        route, destinationAssets, query, state.assets, state.favoriteIds, state.sensitiveIds,
        state.library, state.lockedFolders, state.unlockedFolders, state.preferences,
    ) {
        when (val current = route) {
        BrowserRoute.Root -> destinationAssets
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
                    asset.matchesGallerySearch(query, state.library.tagsFor(asset.id), state.recognition, state.favoriteIds)
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
        ).filter { it.matchesGallerySearch(query, state.library.tagsFor(it.id), state.recognition, state.favoriteIds) }
        is BrowserRoute.Tag -> sortAssets(
            state.assets.filter { asset ->
                current.tag in state.library.tagsFor(asset.id) &&
                    !asset.isTrashed &&
                    asset.matchesGallerySearch(query, state.library.tagsFor(asset.id), state.recognition, state.favoriteIds)
            },
            state.preferences.sort,
        )
        }
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
    // and the zoom ladder drops back to a grid rung (the Calendar and Map rungs draw months and
    // pins, not photos). The DESTINATION is deliberately left alone: which of the nine categories
    // the person is browsing is a choice they made, not incidental state, and search reads as a
    // filter within it.
    LaunchedEffect(state.pendingSearch) {
        val seed = state.pendingSearch ?: return@LaunchedEffect
        if (route != BrowserRoute.Root) {
            // Flag first, then change: the flag must already be set when the reset effect
            // re-runs for this route change on the next frame.
            seededRouteChange = true
            route = BrowserRoute.Root
        }
        if (zoomLevel !is GalleryZoomLevel.Grid) {
            zoomLevel = GalleryZoomLevel.Grid(state.preferences.gridColumns)
        }
        query = seed
        searchVisible = true
        // Acknowledged only after the seeding above has actually happened, so a consumer that
        // clears the value cannot race ahead of the state it is acknowledging.
        actions.onPendingSearchConsumed()
    }

    BackHandler(
        enabled = !shell.atHome || legacyScreen != null ||
            selection.isActive || searchVisible || route != BrowserRoute.Root,
    ) {
        when {
            // A room is not a back-stack entry, but Back is still the gesture people reach for
            // to leave one, so it closes the room rather than the app.
            !shell.atHome -> shell.closeAll()
            legacyScreen != null -> legacyScreen = null
            selection.isActive -> selection = selection.clear()
            searchVisible -> {
                searchVisible = false
                query = ""
            }
            else -> route = BrowserRoute.Root
        }
    }

    // Where the grid actually is, for the edge scrubber's resting marker. Derived so a scroll
    // invalidates only what draws the marker, not the whole browser.
    val firstVisibleIndex by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex }
    }
    // The edge scrubber is visible only while the grid is actually moving, plus a short hold so
    // it does not blink out from under a finger between two flings.
    val gridScrolling by remember(gridState) { derivedStateOf { gridState.isScrollInProgress } }
    var scrubberWanted by remember { mutableStateOf(false) }
    LaunchedEffect(gridScrolling) {
        if (gridScrolling) {
            scrubberWanted = true
        } else {
            kotlinx.coroutines.delay(SCRUBBER_HOLD_MILLIS)
            scrubberWanted = false
        }
    }
    val scrubberAlpha = animateFloatAsState(
        targetValue = if (scrubberWanted) 1f else 0f,
        animationSpec = SpatialMotion.settleSpec,
        label = "scrubber-reveal",
    )
    // The scrubber's stops, in the grid's index space. Both surfaces that show it render
    // headerless grids, so grid item n is asset n — see timelineStops.
    val scrubberAssets = if (route == BrowserRoute.Root) destinationAssets else currentAssets
    val scrubberStops = remember(scrubberAssets) { timelineStops(scrubberAssets) }
    // Whether the grid (square, masonry, or with Timeline's date headers) is what is actually on
    // screen right now, as opposed to Calendar or Map -- those two draw their own scrollable
    // surface and neither shares gridState with it, so the edge scrubber and the density pill
    // below would be tracking and driving a position nothing on screen agrees with.
    val gridActive = zoomLevel !is GalleryZoomLevel.Calendar && zoomLevel !is GalleryZoomLevel.MapView

    // ---- desktop input: fold pinch and Ctrl+scroll into the zoom ladder, and the right-click
    // context menu into real actions. Both gestures are already consumed in GalleryContent.kt
    // (gridZoomGestures, MediaTile's secondary-button handler and DropdownMenu) -- this is only
    // the wiring GridChromeBridge's own doc calls for.
    //
    // Reassigned every recomposition rather than set once: both lambdas close over zoomLevel,
    // zoomResidual and scrubberAssets, all of which change across the browsing surface's life,
    // and a bridge captured once at `remember` time would forever act on the FIRST
    // recomposition's values. See GridChromeBridge's own doc for why mutating the remembered
    // instance's fields -- not re-providing a new instance -- is what keeps this cheap: the
    // 22k-tile grid below reads the field, not the identity of the bridge that holds it.
    chromeBridge.onZoom = { scaleFactor ->
        val step = zoomLadder.step(zoomLevel, zoomResidual, scaleFactor)
        zoomLevel = step.level
        zoomResidual = step.residual
        // Only a Grid rung has a column count to persist; Calendar and Map do not touch the
        // preference, so stepping back INTO the grid resumes at whatever density it left off.
        (step.level as? GalleryZoomLevel.Grid)?.let { actions.onSetGridColumns(it.columns) }
    }
    chromeBridge.onContextAction = { asset, action ->
        when (action) {
            MediaContextAction.OPEN -> actions.onOpenAsset(asset, scrubberAssets)
            MediaContextAction.TOGGLE_FAVORITE ->
                actions.onSetFavorite(setOf(asset.id), asset.id !in state.favoriteIds)
            MediaContextAction.MOVE_TO_TRASH -> actions.onMoveToTrash(listOf(asset))
        }
    }

    val railItems = remember {
        HyleDestination.entries.map { dev.aarso.cellshell.WheelItem(it.name, it.label) }
    }

    // One dispatch point for every key this screen gives meaning to, translating the raw Compose
    // key into adaptive's own vocabulary via [galleryShortcutFor] -- see that function's own doc
    // for the exact table and why it returns null (rather than a default) for a key it has no
    // opinion about.
    //
    // Arrow-key navigation walks [scrubberAssets] in COLUMN-count strides, which is exact for the
    // square grid and Timeline's headerless variant, and an approximation in masonry mode (whose
    // rows do not actually align to a fixed column count) -- acceptable here because it still
    // moves the cursor roughly up/down/across rather than leaving the keyboard unable to drive a
    // masonry grid at all.
    fun handleGalleryShortcut(shortcut: GalleryShortcut) {
        when (shortcut) {
            is GalleryShortcut.MoveSelection -> {
                val ids = scrubberAssets
                if (ids.isEmpty()) return
                val columns = state.preferences.gridColumns.coerceAtLeast(1)
                val current = ids.indexOfFirst { it.id == chromeBridge.keyboardFocusedId }
                    .let { if (it < 0) 0 else it }
                val next = when (shortcut.direction) {
                    MoveDirection.UP -> current - columns
                    MoveDirection.DOWN -> current + columns
                    MoveDirection.LEFT -> current - 1
                    MoveDirection.RIGHT -> current + 1
                }.coerceIn(0, ids.lastIndex)
                chromeBridge.keyboardFocusedId = ids[next].id
                // scrollToItem, not animateScrollToItem: matches the edge scrubber's own choice
                // just below -- a held-down arrow key fires many of these in quick succession, and
                // an animated scroll queued behind an animated scroll is how that becomes a stutter.
                gridScope.launch { gridState.scrollToItem(next) }
            }
            GalleryShortcut.OpenFocused -> {
                val ids = scrubberAssets
                val focused = ids.firstOrNull { it.id == chromeBridge.keyboardFocusedId } ?: ids.firstOrNull()
                focused?.let { actions.onOpenAsset(it, ids) }
            }
            // Same priority order as BackHandler above: a selection is the most specific thing to
            // back out of, then search, then the keyboard's own cursor.
            GalleryShortcut.CloseOrClear -> when {
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
        }
    }

    // Provided once, here, above every possible location of a grid tile -- the root Photos grid
    // reached through DestinationContent (a file this task does not own) AND the drill-down
    // MediaGridScreen below both sit inside SpatialShell's trailing content, so wrapping the
    // whole shell is what makes bridge.onZoom/onContextAction reach a tile regardless of which
    // of those two paths rendered it. See GridChromeBridge's own doc for the full reasoning.
    CompositionLocalProvider(LocalGridChromeBridge provides chromeBridge) {
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
        // room it owns, not a per-room one, so pinning the rail also swaps Settings/Actions/Info's
        // own motion to plain-shrink for as long as it stays pinned, which matches the owner's own
        // framing: peeking vs. pinned is a property of how this WINDOW is being used right now,
        // not of which particular room happens to be opening.
        parkStyle = when (chromeMotion) {
            ChromeMotion.SHRINK_ONLY -> ParkStyle.SLIDE
            ChromeMotion.SHRINK_AND_PIVOT -> ParkStyle.SWIVEL
        },
        // Hand the notification's own strip back to it. Without this the shell claims the top
        // 56dp on the Initial pass and every pull on the status line opens the settings room
        // instead of expanding the line -- the two gestures live in the same pixels.
        topReserve = notificationReserve,
        // One geography for the whole app (owner, 2026-08-18: *"the model needs to remain the
        // same"*). Wherever you are, the same edge holds the same KIND of thing:
        //
        //   LEFT   where you can go        RIGHT   what you can do here
        //   TOP    settings                BOTTOM  what this is
        //
        // The viewer set that arrangement, the gallery now matches it, and only the contents
        // differ -- a grid and one open photo can be done different things to, but the user only
        // has to learn the four edges once. Settings used to be the gallery's RIGHT room, which
        // put settings and actions on the same edge depending on which screen you were on.
        left = {
            DestinationRailPanel(
                items = railItems,
                selectedId = destination.name,
                onSelect = { id ->
                    destination = HyleDestination.valueOf(id)
                    route = BrowserRoute.Root
                    shell.closeAll()
                    gridScope.launch { gridState.scrollToItem(0) }
                },
                state = state,
                onOpenSettings = { shell.open(RoomEdge.TOP) },
            )
        },
        top = {
            SettingsRoom(
                state = state,
                actions = actions,
                onOpenLegacyScreen = {
                    shell.closeAll()
                    legacyScreen = it
                },
            )
        },
        right = {
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
                onNewCollection = {
                    shell.closeAll()
                    createCollectionVisible = true
                },
            )
        },
        bottom = {
            GalleryInfoRoom(
                title = route.title(destination),
                assets = currentAssets,
                state = state,
            )
        },
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
                    // Library screen, and Settings is the right-hand room.
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
                                else -> null
                            } ?: return@onKeyEvent false
                            val shortcut = galleryShortcutFor(shortcutKey, event.isCtrlPressed)
                                ?: return@onKeyEvent false
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
                                selectedId = destination.name,
                                galleryState = state,
                                onSelect = { id ->
                                    destination = HyleDestination.valueOf(id)
                                    route = BrowserRoute.Root
                                    gridScope.launch { gridState.scrollToItem(0) }
                                },
                                onOpenSettings = { shell.open(RoomEdge.TOP) },
                                onExpand = { navRailPreferences.setNavRailCollapsed(false) },
                                onCollapse = { navRailPreferences.setNavRailCollapsed(true) },
                            )
                        }
                    Column(Modifier.fillMaxHeight().weight(1f)) {
                        if (searchVisible) {
                            com.fotoxplorr.app.hyle.HyleTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                placeholder = "Search names, albums, types and tags",
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

                        val legacy = legacyScreen
                        when {
                            legacy != null -> LegacyScreenHost(
                                screen = legacy,
                                state = state,
                                actions = actions,
                                query = query,
                                onOpenRoute = { route = it; legacyScreen = null },
                                onRequestUnlock = { key, name ->
                                    passwordRequest = PasswordRequest(PasswordAction.UNLOCK, key, name)
                                },
                                // One depth now, so opening settings is just opening the room.
                                onOpenSettings = { shell.open(RoomEdge.RIGHT) },
                            )
                            // ---- the zoom ladder's two sparse ends (owner: "zoom out past the
                            // sparsest grid and you reach Calendar; further out, Map") ----
                            //
                            // Checked ahead of the route branches below and independent of them
                            // on purpose: the ladder is a property of the whole browsing surface,
                            // not of any one route, so pinching out from inside an album reaches
                            // Calendar/Map exactly the same as pinching out from the root grid.
                            zoomLevel is GalleryZoomLevel.MapView -> GalleryMapZoomContent()
                            zoomLevel is GalleryZoomLevel.Calendar -> CalendarScreen(
                                assets = scrubberAssets,
                                // A day's cover opens straight into the viewer with that day as
                                // the paging context -- the calendar has no route of its own to
                                // drill into (BrowserRoute has no day-granularity variant, and
                                // adding one would touch selection/search wiring this task does
                                // not own), so "look at this day" is "open its first photo".
                                onOpenDay = { dayAssets ->
                                    dayAssets.firstOrNull()?.let { actions.onOpenAsset(it, dayAssets) }
                                },
                            )
                            // The Timeline entry in the view switcher: the SAME grid, drawn with
                            // date-group headers. TimelineScreen's grouped branch exists already
                            // in GalleryContent.kt but had no reachable caller before this task --
                            // DestinationContent (a file this task does not own) always passes
                            // `showDateHeaders = false`. Calling it directly from here reaches it
                            // without touching that file.
                            timelineHeadersOn -> TimelineScreen(
                                assets = scrubberAssets,
                                grouping = state.preferences.timelineGrouping,
                                columns = state.preferences.gridColumns,
                                favoriteIds = state.favoriteIds,
                                sensitiveIds = state.sensitiveIds,
                                blurSensitive = state.preferences.blurSensitive,
                                selectedIds = selection.selectedIds,
                                selectionActive = selection.isActive,
                                onOpen = { asset -> actions.onOpenAsset(asset, scrubberAssets) },
                                onToggleSelection = { id -> selection = selection.toggle(id) },
                                showDateHeaders = true,
                                gridState = gridState,
                                fitToTile = state.preferences.fitToTile,
                                loopAnimations = state.preferences.loopAnimations,
                                longPressPreview = state.preferences.longPressPreview,
                            )
                            // Pull-to-backup is retired (owner direction, 2026-08-05): the
                            // pull-down space at a room's top belongs to the fonebrew
                            // top-room reveal, so no other gesture may claim it — and the
                            // static "PULL TO CREATE BACKUP" copy went with it. Refresh is
                            // now physical (ShakeToRefresh below); backup is an explicit,
                            // named item in the settings room's Data tab instead of a gesture.
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
                                    DestinationContent(
                                        destination = destination,
                                        assets = destinationAssets,
                                        state = state,
                                        actions = actions,
                                        selection = selection,
                                        onSelectionChange = { selection = it },
                                        gridState = gridState,
                                        onRequestUnlock = { key, name ->
                                            passwordRequest = PasswordRequest(
                                                PasswordAction.UNLOCK, key, name,
                                            )
                                        },
                                    )
                                }
                            }
                            else -> MediaGridScreen(
                                assets = currentAssets,
                                columns = state.preferences.gridColumns,
                                favoriteIds = state.favoriteIds,
                                sensitiveIds = state.sensitiveIds,
                                blurSensitive = state.preferences.blurSensitive,
                                selectedIds = selection.selectedIds,
                                selectionActive = selection.isActive,
                                emptyMessage = if (query.isBlank()) "Nothing here yet" else "No matching media",
                                onOpen = { asset -> actions.onOpenAsset(asset, currentAssets) },
                                onToggleSelection = { id -> selection = selection.toggle(id) },
                                gridState = gridState,
                                fitToTile = state.preferences.fitToTile,
                                loopAnimations = state.preferences.loopAnimations,
                                longPressPreview = state.preferences.longPressPreview,
                            )
                        }
                    }
                    }

                    // The Niagara-style timeline scrubber: glide a finger down the right edge
                    // and the grid sweeps with it, a bubble naming the month under the finger.
                    // This is what makes a 21,000-item continuous mosaic navigable, and it is
                    // why the pill no longer carries a scrubber of its own — two position
                    // controls on one screen is one too many, and the edge is the one the owner
                    // asked for.
                    if (!selection.isActive && legacyScreen == null && gridActive && scrubberStops.isNotEmpty()) {
                        EdgeTimelineScrubber(
                            stops = scrubberStops,
                            itemCount = scrubberAssets.size,
                            currentIndex = firstVisibleIndex,
                            onScrubTo = { index ->
                                // scrollToItem, not animateScrollToItem: the finger is already
                                // moving, so the grid must track it rather than chase it.
                                gridScope.launch { gridState.scrollToItem(index) }
                            },
                            inkColor = Color.White,
                            accentColor = MaterialTheme.colorScheme.primary,
                            bubbleTextColor = Color.Black,
                            // The strip stops sitting ON the photos (owner, 2026-08-14: "the
                            // timeline thing is overlapping with the pictures"): it is invisible
                            // while the mosaic is still, fades in with the scroll it describes,
                            // and fades back out once the grid settles.
                            //
                            // Transient rather than a reserved 44dp gutter, deliberately -- a
                            // permanent black stripe down the side of an edge-to-edge mosaic
                            // would answer "overlapping" with "cluttered".
                            //
                            // Passed as restAlpha rather than applied as a graphicsLayer out
                            // here, because an outer alpha would hide the pixels while leaving
                            // the 44dp touch target live: a drag down the right edge would scrub
                            // a strip that never appeared, since an outer fade cannot know the
                            // strip has been pressed. The library takes it to full opacity on
                            // touch, so the strip is still grabbable from a standstill.
                            restAlpha = EDGE_SCRUBBER_REST_ALPHA * scrubberAlpha.value,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }

                    if (selection.isActive) {
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
                    if (!selection.isActive && route != BrowserRoute.Root) {
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
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }

                    // The floating pill from the mockups, replacing the retired bottom nav.
                    // Search still makes sense from Calendar/Map (it drives which assets flow
                    // INTO them, same as the grid), so only the density control is grid-only --
                    // the pill itself stays up throughout.
                    if (!selection.isActive && legacyScreen == null) {
                        FloatingPillControl(
                            caption = if (gridActive) pillCaption(scrubberAssets, firstVisibleIndex) else "",
                            onSearch = { searchVisible = !searchVisible },
                            onToggleDensity = {
                                val next = state.preferences.gridColumns + 1
                                val wrapped = if (next > MAX_GRID_COLUMNS) MIN_GRID_COLUMNS else next
                                actions.onSetGridColumns(wrapped)
                                // Keeps the ladder's own idea of "where we are" in step with this
                                // button -- without this a pinch right after tapping density would
                                // step from whatever rung the LAST pinch left off at, ignoring
                                // what the button just changed on screen.
                                zoomLevel = GalleryZoomLevel.Grid(wrapped)
                                zoomResidual = 0f
                                timelineHeadersOn = false
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding(),
                        )
                    }

                    // The view switcher: grid / calendar / map / timeline made directly
                    // reachable, per the owner's ask that these not stay "buried behind
                    // Discover -> Places". Up whenever the equivalent chrome above is -- not
                    // gated on gridActive, since this is the way BACK from Calendar/Map too.
                    if (!selection.isActive && legacyScreen == null) {
                        GalleryViewModeSwitcher(
                            active = when {
                                zoomLevel is GalleryZoomLevel.MapView -> GalleryViewMode.MAP
                                zoomLevel is GalleryZoomLevel.Calendar -> GalleryViewMode.CALENDAR
                                timelineHeadersOn -> GalleryViewMode.TIMELINE
                                else -> GalleryViewMode.GRID
                            },
                            onSelect = { mode ->
                                when (mode) {
                                    GalleryViewMode.GRID -> {
                                        zoomLevel = GalleryZoomLevel.Grid(state.preferences.gridColumns)
                                        zoomResidual = 0f
                                        timelineHeadersOn = false
                                    }
                                    GalleryViewMode.TIMELINE -> {
                                        zoomLevel = GalleryZoomLevel.Grid(state.preferences.gridColumns)
                                        zoomResidual = 0f
                                        timelineHeadersOn = true
                                    }
                                    GalleryViewMode.CALENDAR -> {
                                        zoomLevel = GalleryZoomLevel.Calendar
                                        zoomResidual = 0f
                                    }
                                    GalleryViewMode.MAP -> {
                                        zoomLevel = GalleryZoomLevel.MapView
                                        zoomResidual = 0f
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
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
                }
                passwordRequest = null
            },
        )
    }
}

/**
 * The far end of the zoom ladder: the offline map, reached by pinching out (or Ctrl+scrolling
 * out) past Calendar, or by picking Map in the view switcher.
 *
 * [PlacesScreen] lives in `com.fotoxplorr.app.spatial`, a package this task may CALL into but not
 * edit, and it needs exactly what [SpatialExperience] already carries -- assets, the geo index,
 * how to index missing coordinates, how to open a photo. Rather than re-deriving any of that here
 * (which would mean duplicating `GalleryScreen`'s own `spatialAssets`/`geoRepository` plumbing,
 * or worse, hacking a second copy of it), this reads the SAME `LocalSpatialExperience` composition
 * local that [GalleryScreen] already provides for [DiscoverScreen]'s Places card -- the data this
 * needs is already reachable through composition, with nothing new to thread past a file boundary.
 */
@Composable
private fun GalleryMapZoomContent() {
    val spatial = LocalSpatialExperience.current
    if (spatial != null) {
        PlacesScreen(
            assets = spatial.assets,
            geoState = spatial.geoState,
            onIndexLocations = spatial.onIndexLocations,
            onOpenAsset = spatial.onOpenAsset,
        )
    } else {
        // Unreachable in practice -- GalleryScreen always provides LocalSpatialExperience around
        // GalleryBrowser -- but a composition local's default is null, and a screen that trusted
        // an implicit non-null here would crash instead of degrading if that ever changed.
        GalleryMessage("Map is unavailable here")
    }
}

/**
 * The four views the switcher below makes directly reachable. Deliberately NOT the same type as
 * [GalleryZoomLevel]: TIMELINE is a Grid rung drawn with date headers, not a fifth rung on the
 * ladder, and encoding it as a `GalleryZoomLevel` would mean either inventing a rung the pure
 * ladder logic knows nothing about, or letting a pinch land on it by accident. Keeping it a
 * separate, UI-only enum is what lets `GalleryBrowser` derive it FROM `(zoomLevel,
 * timelineHeadersOn)` for display, while the two underlying pieces of state stay independently
 * driven by the ladder and by this switcher respectively.
 */
private enum class GalleryViewMode { GRID, CALENDAR, MAP, TIMELINE }

/**
 * Grid / Calendar / Map / Timeline, made directly reachable rather than left buried behind
 * Discover -> Places (owner's ask). Plain text pills rather than icons, matching this screen's
 * own brutalist chrome elsewhere ([RouteOverlayBar]'s pills, [CalendarScreen]'s `‹`/`›` glyphs) --
 * a fifth icon font import for four one-word labels would buy nothing this app's existing black
 * pill-on-photograph language does not already say.
 */
@Composable
private fun GalleryViewModeSwitcher(
    active: GalleryViewMode,
    onSelect: (GalleryViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp, end = 8.dp)
            .background(SCRIM_PILL, RoundedCornerShape(20.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GalleryViewModeButton("Grid", active == GalleryViewMode.GRID) { onSelect(GalleryViewMode.GRID) }
        GalleryViewModeButton("Cal", active == GalleryViewMode.CALENDAR) { onSelect(GalleryViewMode.CALENDAR) }
        GalleryViewModeButton("Map", active == GalleryViewMode.MAP) { onSelect(GalleryViewMode.MAP) }
        GalleryViewModeButton("Time", active == GalleryViewMode.TIMELINE) { onSelect(GalleryViewMode.TIMELINE) }
    }
}

@Composable
private fun GalleryViewModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label.uppercase(),
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clickable(onClickLabel = "$label view", onClick = onClick)
            .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
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
    galleryState: GalleryUiState,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
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
            state = galleryState,
            onOpenSettings = onOpenSettings,
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

/** A reasonable fixed width for a permanently-visible rail -- wide enough for WordWheelRail's
 * labels and thumbnail covers, the same content the pull-out room already shows at full width. */
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
 * - **Bottom left**, a 44dp rounded pill from the screen edge across about three quarters of the
 *   width: the count at 28sp, `SELECTED` at 20sp uppercase and half opacity, then a long empty run
 *   of black, then the dismiss ✕ near its right end.
 * - **Bottom right**, the trash: black, **square-cornered**, flush into the corner, separated from
 *   the pill by a gap of bare photograph. The one destructive control, as far from everything else
 *   as the screen allows and shaped differently from everything else so the hand knows it.
 *
 * The shapes differ on purpose. The pill is rounded and the trash is not; that is the only
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
            // actions room on the right edge.
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
            categories = buildSet {
                if (isVideo) add("video") else add("photo")
                if (isAnimated) add("animated")
                if (isFavorite || id in favouriteIds) add("favourite")
                if (id in recognition.petMediaIds) add("pet")
                if (id in recognition.peopleMediaIds) add("person")
                if (id in recognition.identityMediaIds) add("document")
                if (tags.isEmpty()) add("untagged")
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
 * The settings room — the surface parked off the right edge.
 *
 * One depth, six tabs. It used to be two depths: a compact panel plus everything else behind an
 * "All settings…" button, which is what made that second depth feel strange (owner, 2026-08-14)
 * -- it was not a category, it was an overflow, and an overflow is what a surface grows when it
 * has no structure to put things in.
 */
@Composable
private fun SettingsRoom(
    state: GalleryUiState,
    actions: GalleryActions,
    onOpenLegacyScreen: (LegacyScreen) -> Unit,
) {
    val context = LocalContext.current
    SettingsTabsRoom(
        state = state,
        actions = actions,
        onOpenLegacyScreen = onOpenLegacyScreen,
        // Both leave the app, so both are ordinary implicit intents rather than anything this
        // app handles itself -- and both are runCatching-guarded, because a device with no mail
        // client or no browser is a real device and must not crash for asking.
        onOpenSupport = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                        data = android.net.Uri.parse("mailto:$SUPPORT_EMAIL")
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Foto Xplorr")
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
 * shake, backup is in the Library screen, settings is the right-hand room.
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
 * How long the edge scrubber stays up after the grid stops. Long enough to survive the gap
 * between two flings, short enough that it is gone before the mosaic reads as settled.
 */
private const val SCRUBBER_HOLD_MILLIS = 1_200L

/**
 * How visible the edge scrubber is while the grid is moving. The constellation's shared
 * edge-affordance alpha; multiplied by the reveal so it lands on exactly that value mid-scroll
 * and on zero at rest.
 */
private const val EDGE_SCRUBBER_REST_ALPHA = 0.35f
