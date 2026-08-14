@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.fotoxplorr.app.gallery

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.hyle.FloatingPillControl
import com.fotoxplorr.app.hyle.NotificationRoom
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
import com.fotoxplorr.app.recognition.RecognitionProgress
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.SpatialExperience
import kotlinx.coroutines.launch

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
    val onExportMetadata: () -> Unit,
    val onImportMetadata: () -> Unit,
    val onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    val onStartSlideshow: (List<MediaAsset>) -> Unit,
)

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    actions: GalleryActions,
) {
    val context = LocalContext.current
    val geoRepository = remember(context) { GeoMetadataRepository(context.applicationContext) }
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
    var selectionMenuVisible by remember { mutableStateOf(false) }
    var topMenuVisible by remember { mutableStateOf(false) }
    // The two rooms are the shell's state, not booleans here: a room is fractionally open for
    // most of its life (the finger is mid-drag), which a Boolean cannot express.
    val shell = rememberSpatialController()
    var allSettingsOpen by remember { mutableStateOf(false) }
    var createCollectionVisible by remember { mutableStateOf(false) }
    var renameCollection by remember { mutableStateOf<BrowserRoute.Collection?>(null) }
    var renameAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var addToCollectionIds by remember { mutableStateOf<Set<MediaId>?>(null) }
    var addTagIds by remember { mutableStateOf<Set<MediaId>?>(null) }
    var passwordRequest by remember { mutableStateOf<PasswordRequest?>(null) }
    var legacyScreen by remember { mutableStateOf<LegacyScreen?>(null) }

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
                    asset.matchesGallerySearch(query, state.library.tagsFor(asset.id))
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
        ).filter { it.matchesGallerySearch(query, state.library.tagsFor(it.id)) }
        is BrowserRoute.Tag -> sortAssets(
            state.assets.filter { asset ->
                current.tag in state.library.tagsFor(asset.id) &&
                    !asset.isTrashed &&
                    asset.matchesGallerySearch(query, state.library.tagsFor(asset.id))
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
        query = ""
        searchVisible = false
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
    // The scrubber's stops, in the grid's index space. Both surfaces that show it render
    // headerless grids, so grid item n is asset n — see timelineStops.
    val scrubberAssets = if (route == BrowserRoute.Root) destinationAssets else currentAssets
    val scrubberStops = remember(scrubberAssets) { timelineStops(scrubberAssets) }

    val railItems = remember {
        HyleDestination.entries.map { dev.aarso.cellshell.WheelItem(it.name, it.label) }
    }

    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        // The rooms sit on the same black the grid does, so opening one reads as the surface
        // moving rather than as a different app appearing behind it.
        scrimColor = Color.Black,
        cardColor = Color.Black,
        modifier = Modifier.fillMaxSize(),
        // Shrink AND swivel (owner, 2026-08-14). The card turns about the hinge edge that
        // stays on screen, so opening a room reads as a panel swinging away rather than a
        // rectangle sliding off -- the Magic Portal shape. The shrink is kept; the swivel is
        // added to it.
        parkStyle = ParkStyle.SWIVEL,
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
            )
        },
        right = {
            SettingsRoom(
                state = state,
                actions = actions,
                allSettingsOpen = allSettingsOpen,
                onAllSettingsOpenChange = { allSettingsOpen = it },
                onOpenLegacyScreen = {
                    shell.closeAll()
                    legacyScreen = it
                },
            )
        },
    ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Black,
                topBar = {
                    if (selection.isActive) {
                        SelectionTopBar(
                            selection = selection,
                            selectedAssets = selectedAssets,
                            currentIds = currentIds,
                            inTrash = inTrash,
                            inArchive = inArchive,
                            state = state,
                            actions = actions,
                            menuVisible = selectionMenuVisible,
                            onMenuVisibleChange = { selectionMenuVisible = it },
                            onSelectionChange = { selection = it },
                            onRenameAsset = { renameAsset = it },
                            onAddToCollection = { addToCollectionIds = it },
                            onAddTag = { addTagIds = it },
                            tagRoute = tagRoute,
                            collectionRoute = collectionRoute,
                        )
                    } else {
                        // A plain header row, not a CenterAlignedTopAppBar. The app bar existed
                        // mostly to hold the hamburger, and the hamburger existed to open a rail
                        // that is now one edge-drag away — so both go, and the header keeps only
                        // what is genuinely per-view: where you are, and the two actions for it.
                        BrowserHeader(
                            title = route.title(destination),
                            onBack = if (route != BrowserRoute.Root) {
                                { route = BrowserRoute.Root }
                            } else {
                                null
                            },
                            actions = {
                                if (currentAssets.isNotEmpty()) {
                                    IconButton(onClick = { actions.onStartSlideshow(currentAssets) }) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Start slideshow")
                                    }
                                }
                                IconButton(onClick = { topMenuVisible = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = topMenuVisible,
                                    onDismissRequest = { topMenuVisible = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Refresh library") },
                                        onClick = {
                                            topMenuVisible = false
                                            actions.onRefresh()
                                        },
                                    )
                                    // Backup's explicit home since the pull gesture retired —
                                    // the same local metadata export the pull used to fire.
                                    DropdownMenuItem(
                                        text = { Text("Create backup") },
                                        onClick = {
                                            topMenuVisible = false
                                            actions.onExportMetadata()
                                        },
                                    )
                                    albumRoute?.let { album ->
                                        val protected = album.key in state.lockedFolders
                                        val unlocked = album.key in state.unlockedFolders
                                        when {
                                            !protected -> DropdownMenuItem(
                                                text = { Text("Make folder private") },
                                                onClick = {
                                                    topMenuVisible = false
                                                    passwordRequest = PasswordRequest(
                                                        PasswordAction.PROTECT, album.key, album.name,
                                                    )
                                                },
                                            )
                                            unlocked -> {
                                                DropdownMenuItem(
                                                    text = { Text("Lock now") },
                                                    onClick = {
                                                        topMenuVisible = false
                                                        actions.onLockFolder(album.key)
                                                        route = BrowserRoute.Root
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Remove protection") },
                                                    onClick = {
                                                        topMenuVisible = false
                                                        passwordRequest = PasswordRequest(
                                                            PasswordAction.REMOVE, album.key, album.name,
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
                                                topMenuVisible = false
                                                renameCollection = collection
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete collection") },
                                            onClick = {
                                                topMenuVisible = false
                                                actions.onDeleteCollection(collection.id)
                                                route = BrowserRoute.Root
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                        onClick = {
                                            topMenuVisible = false
                                            shell.open(RoomEdge.RIGHT)
                                        },
                                    )
                                }
                            },
                        )
                    }
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
                        .padding(padding),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (searchVisible) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                                placeholder = { Text("Search names, albums, types and tags") },
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
                                onOpenSettings = {
                                    allSettingsOpen = true
                                    shell.open(RoomEdge.RIGHT)
                                },
                            )
                            // Pull-to-backup is retired (owner direction, 2026-08-05): the
                            // pull-down space at a room's top belongs to the fonebrew
                            // top-room reveal, so no other gesture may claim it — and the
                            // static "PULL TO CREATE BACKUP" copy went with it. Refresh is
                            // now physical (ShakeToRefresh below); backup is an explicit,
                            // named item in the header overflow menu instead of a gesture.
                            //
                            // The notification is no longer a sibling in this Column. It is a
                            // layer *behind* the grid, and the grid's frame recedes to uncover
                            // it — see NotificationRoom.
                            // recognitionProgress is passed, not just consulted for showWhenIdle:
                            // it used to decide whether to OPEN the layer while the copy was
                            // written from scanState alone, so the banner could open with nothing
                            // to say and render a bare warning glyph.
                            route == BrowserRoute.Root -> NotificationRoom(
                                scanState = state.scanState,
                                recognition = state.recognitionProgress,
                                showWhenIdle = state.recognitionProgress.running ||
                                    state.recognitionProgress.message != null,
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
                                emptyMessage = if (query.isBlank()) "Nothing here yet" else "No matching media",
                                onOpen = { asset -> actions.onOpenAsset(asset, currentAssets) },
                                onToggleSelection = { id -> selection = selection.toggle(id) },
                                gridState = gridState,
                            )
                        }
                    }

                    // The Niagara-style timeline scrubber: glide a finger down the right edge
                    // and the grid sweeps with it, a bubble naming the month under the finger.
                    // This is what makes a 21,000-item continuous mosaic navigable, and it is
                    // why the pill no longer carries a scrubber of its own — two position
                    // controls on one screen is one too many, and the edge is the one the owner
                    // asked for.
                    if (!selection.isActive && legacyScreen == null && scrubberStops.isNotEmpty()) {
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
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }

                    // The floating pill from the mockups, replacing the retired bottom nav.
                    if (!selection.isActive && legacyScreen == null) {
                        FloatingPillControl(
                            caption = pillCaption(scrubberAssets, firstVisibleIndex),
                            onSearch = { searchVisible = !searchVisible },
                            onToggleDensity = {
                                val next = state.preferences.gridColumns + 1
                                actions.onSetGridColumns(
                                    if (next > MAX_GRID_COLUMNS) MIN_GRID_COLUMNS else next,
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding(),
                        )
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

@Composable
private fun SelectionTopBar(
    selection: GallerySelection,
    selectedAssets: List<MediaAsset>,
    currentIds: Set<MediaId>,
    inTrash: Boolean,
    inArchive: Boolean,
    state: GalleryUiState,
    actions: GalleryActions,
    menuVisible: Boolean,
    onMenuVisibleChange: (Boolean) -> Unit,
    onSelectionChange: (GallerySelection) -> Unit,
    onRenameAsset: (MediaAsset) -> Unit,
    onAddToCollection: (Set<MediaId>) -> Unit,
    onAddTag: (Set<MediaId>) -> Unit,
    tagRoute: BrowserRoute.Tag?,
    collectionRoute: BrowserRoute.Collection?,
) {
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Black,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
        title = { Text("${selection.count} selected") },
        navigationIcon = {
            IconButton(onClick = { onSelectionChange(selection.clear()) }) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = { onSelectionChange(selection.selectAll(currentIds)) }) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
            }
            if (!inTrash) {
                IconButton(onClick = { actions.onShare(selectedAssets) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
                IconButton(onClick = {
                    val mark = bulkMarkAction(selection.selectedIds, state.favoriteIds) == BulkMarkAction.MARK
                    actions.onSetFavorite(selection.selectedIds, mark)
                    onSelectionChange(selection.clear())
                }) {
                    Icon(Icons.Outlined.Favorite, contentDescription = "Toggle favourite")
                }
            }
            IconButton(onClick = { onMenuVisibleChange(true) }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(expanded = menuVisible, onDismissRequest = { onMenuVisibleChange(false) }) {
                if (inTrash) {
                    DropdownMenuItem(
                        text = { Text("Restore") },
                        leadingIcon = { Icon(Icons.Outlined.Restore, null) },
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onRestore(selectedAssets)
                            onSelectionChange(selection.clear())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete permanently") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onDeletePermanently(selectedAssets)
                            onSelectionChange(selection.clear())
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(if (inArchive) "Unarchive" else "Archive") },
                        leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onSetArchived(selection.selectedIds, !inArchive)
                            onSelectionChange(selection.clear())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark sensitive") },
                        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                        onClick = {
                            onMenuVisibleChange(false)
                            val mark = bulkMarkAction(selection.selectedIds, state.sensitiveIds) == BulkMarkAction.MARK
                            actions.onSetSensitive(selection.selectedIds, mark)
                            onSelectionChange(selection.clear())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share without common EXIF metadata") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        enabled = selectedAssets.all { !it.isVideo && it.mimeType.startsWith("image/") },
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onShareClean(selectedAssets)
                            onSelectionChange(selection.clear())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy to folder") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onCopyToFolder(selectedAssets)
                            onSelectionChange(selection.clear())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to folder safely") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onMoveToFolder(selectedAssets)
                            onSelectionChange(selection.clear())
                        },
                    )
                    if (selectedAssets.size == 1) {
                        DropdownMenuItem(
                            text = { Text("Rename file") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                onMenuVisibleChange(false)
                                onRenameAsset(selectedAssets.first())
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Add to collection") },
                        leadingIcon = { Icon(Icons.Outlined.Collections, null) },
                        onClick = {
                            onMenuVisibleChange(false)
                            onAddToCollection(selection.selectedIds)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add tag") },
                        leadingIcon = { Icon(Icons.Outlined.Label, null) },
                        onClick = {
                            onMenuVisibleChange(false)
                            onAddTag(selection.selectedIds)
                        },
                    )
                    tagRoute?.let { tag ->
                        DropdownMenuItem(
                            text = { Text("Remove #${tag.tag}") },
                            leadingIcon = { Icon(Icons.Outlined.Close, null) },
                            onClick = {
                                onMenuVisibleChange(false)
                                actions.onRemoveTag(selection.selectedIds, tag.tag)
                                onSelectionChange(selection.clear())
                            },
                        )
                    }
                    collectionRoute?.let { collection ->
                        DropdownMenuItem(
                            text = { Text("Remove from collection") },
                            leadingIcon = { Icon(Icons.Outlined.Close, null) },
                            onClick = {
                                onMenuVisibleChange(false)
                                actions.onRemoveFromCollection(collection.id, selection.selectedIds)
                                onSelectionChange(selection.clear())
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Move to trash") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                        onClick = {
                            onMenuVisibleChange(false)
                            actions.onMoveToTrash(selectedAssets)
                            onSelectionChange(selection.clear())
                        },
                    )
                }
            }
        },
    )
}

fun BrowserRoute.title(destination: HyleDestination): String = when (this) {
    BrowserRoute.Root -> destination.label
    is BrowserRoute.DeviceAlbum -> name
    is BrowserRoute.Collection -> name
    is BrowserRoute.Smart -> album.title()
    is BrowserRoute.Tag -> "#$tag"
}

internal fun MediaAsset.matchesGallerySearch(query: String, tags: Set<String>): Boolean {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) return true
    return displayName.lowercase().contains(normalized) ||
        mimeType.lowercase().contains(normalized) ||
        folderIdentity(this).displayName.lowercase().contains(normalized) ||
        tags.any { it.lowercase().contains(normalized) }
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
 * Two depths on one surface rather than a panel plus a dialog: the compact panel from the
 * mockups, and every setting behind "All settings…". Both render inside the app's own theme,
 * which is the fix for the light-coloured settings screen the owner found in an otherwise black
 * app: an `AlertDialog` paints on the platform's dialog surface, and no amount of theming the
 * app changes that.
 */
@Composable
private fun SettingsRoom(
    state: GalleryUiState,
    actions: GalleryActions,
    allSettingsOpen: Boolean,
    onAllSettingsOpenChange: (Boolean) -> Unit,
    onOpenLegacyScreen: (LegacyScreen) -> Unit,
) {
    // Back inside the room steps out of the full list before it closes the room, so "all
    // settings" is not a one-way door.
    BackHandler(enabled = allSettingsOpen) { onAllSettingsOpenChange(false) }

    if (!allSettingsOpen) {
        SettingsPanel(
            preferences = state.preferences,
            onOpenAllSettings = { onAllSettingsOpenChange(true) },
            onSetDefaultDestination = actions.onSetDefaultDestination,
            onOpenLegacyScreen = onOpenLegacyScreen,
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAllSettingsOpenChange(false) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to settings",
                    tint = Color.White,
                )
            }
            Text(
                "All settings",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        GallerySettingsList(
            preferences = state.preferences,
            actions = actions,
            modifier = Modifier.weight(1f),
        )
    }
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
