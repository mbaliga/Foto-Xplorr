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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.hyle.BackupCounts
import com.fotoxplorr.app.hyle.FloatingPillControl
import com.fotoxplorr.app.hyle.PanelSide
import com.fotoxplorr.app.hyle.PullToBackupHost
import com.fotoxplorr.app.hyle.ScanActivityAlertBanner
import com.fotoxplorr.app.hyle.SlideInPanel
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
 * The app shell, rebuilt around the owner's mockups.
 *
 * The primary IA is now the nine-category rail (Pets / People / Identity / Screenshots /
 * Photos / Videos / Favourites / Places / Protected), presented as a panel that slides in
 * over the grid from the left edge -- the grid stays visible at the right edge throughout,
 * exactly as the mockups draw it. Launching lands on the grid with the rail one edge-swipe
 * (or one tap on the header's menu button) away.
 *
 * The previous four-tab bottom `NavigationBar` (Photos / Albums / Discover / Library) is
 * retired as the default IA. Its screens are not deleted: Albums, Discover and Library are
 * reachable from the settings panel, so nothing that worked before became unreachable.
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
    var railOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsDialogVisible by remember { mutableStateOf(false) }
    var createCollectionVisible by remember { mutableStateOf(false) }
    var renameCollection by remember { mutableStateOf<BrowserRoute.Collection?>(null) }
    var renameAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var addToCollectionIds by remember { mutableStateOf<Set<MediaId>?>(null) }
    var addTagIds by remember { mutableStateOf<Set<MediaId>?>(null) }
    var passwordRequest by remember { mutableStateOf<PasswordRequest?>(null) }
    var legacyScreen by remember { mutableStateOf<LegacyScreen?>(null) }

    val gridState = rememberLazyGridState()
    val gridScope = rememberCoroutineScope()

    // Recognition runs once per library state change, on demand rather than at every
    // recomposition, so opening the app does not restart a completed pass.
    LaunchedEffect(state.assets.size) {
        if (state.assets.isNotEmpty()) actions.onIndexRecognition()
    }

    val destinationAssets = destinationAssets(destination, state, query)
    val currentAssets = when (val current = route) {
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
    val selectedAssets = currentAssets.filter { it.id in selection.selectedIds }
    val currentIds = currentAssets.mapTo(linkedSetOf()) { it.id }
    val inTrash = (route as? BrowserRoute.Smart)?.album == SmartAlbum.TRASH
    val inArchive = (route as? BrowserRoute.Smart)?.album == SmartAlbum.ARCHIVED
    val collectionRoute = route as? BrowserRoute.Collection
    val albumRoute = route as? BrowserRoute.DeviceAlbum
    val tagRoute = route as? BrowserRoute.Tag

    LaunchedEffect(currentIds) {
        selection = selection.retainAvailable(currentIds)
    }
    LaunchedEffect(destination, route) {
        selection = selection.clear()
        query = ""
        searchVisible = false
    }

    BackHandler(
        enabled = railOpen || settingsOpen || legacyScreen != null ||
            selection.isActive || searchVisible || route != BrowserRoute.Root,
    ) {
        when {
            railOpen -> railOpen = false
            settingsOpen -> settingsOpen = false
            legacyScreen != null -> legacyScreen = null
            selection.isActive -> selection = selection.clear()
            searchVisible -> {
                searchVisible = false
                query = ""
            }
            else -> route = BrowserRoute.Root
        }
    }

    // Fraction of the collection currently scrolled past, driving the pill's scrubber handle.
    val scrollFraction by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) 0f else gridState.firstVisibleItemIndex.toFloat() / (total - 1)
        }
    }

    val railItems = remember {
        HyleDestination.entries.map { com.fotoxplorr.app.hyle.HyleRailItem(it.name, it.label) }
    }

    SlideInPanel(
        open = railOpen,
        onOpenChange = { railOpen = it },
        side = PanelSide.LEFT,
        panelWidth = RAIL_PANEL_WIDTH.dp,
        panel = {
            DestinationRailPanel(
                items = railItems,
                selectedId = destination.name,
                onSelect = { id ->
                    destination = HyleDestination.valueOf(id)
                    route = BrowserRoute.Root
                    railOpen = false
                    gridScope.launch { gridState.scrollToItem(0) }
                },
                state = state,
            )
        },
    ) {
        SlideInPanel(
            open = settingsOpen,
            onOpenChange = { settingsOpen = it },
            side = PanelSide.RIGHT,
            panelWidth = SETTINGS_PANEL_WIDTH.dp,
            panel = {
                SettingsPanel(
                    preferences = state.preferences,
                    onOpenAllSettings = {
                        settingsOpen = false
                        settingsDialogVisible = true
                    },
                    onSetDefaultDestination = actions.onSetDefaultDestination,
                    onOpenLegacyScreen = {
                        settingsOpen = false
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
                        CenterAlignedTopAppBar(
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = Color.Black,
                                titleContentColor = Color.White,
                                navigationIconContentColor = Color.White,
                                actionIconContentColor = Color.White,
                            ),
                            title = {
                                Text(
                                    route.title(destination),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            navigationIcon = {
                                if (route != BrowserRoute.Root) {
                                    TextButton(onClick = { route = BrowserRoute.Root }) { Text("Back") }
                                } else {
                                    // The obvious tap affordance for the rail, alongside the
                                    // left-edge swipe the mockups imply.
                                    IconButton(onClick = { railOpen = true }) {
                                        Icon(Icons.Outlined.Menu, contentDescription = "Destinations")
                                    }
                                }
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
                                            settingsOpen = true
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
                                onOpenSettings = { settingsDialogVisible = true },
                            )
                            route == BrowserRoute.Root -> PullToBackupHost(
                                onBackupTriggered = {
                                    // Foto Xplorr has no cloud-backup subsystem; the local
                                    // metadata export is the one real backup action that
                                    // exists, so that is what this gesture fires. The active
                                    // phase acknowledges that the OS document picker was
                                    // launched, not that a file has been written.
                                    actions.onExportMetadata()
                                    kotlinx.coroutines.delay(900)
                                },
                                counts = BackupCounts(
                                    total = destinationAssets.size,
                                    backedUp = destinationAssets.size,
                                ),
                                header = {
                                    ScanActivityAlertBanner(
                                        scanState = state.scanState,
                                        showWhenIdle = state.recognitionProgress.running ||
                                            state.recognitionProgress.message != null,
                                    )
                                },
                            ) {
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

                    // The floating pill from the mockups, replacing the retired bottom nav.
                    if (!selection.isActive && legacyScreen == null) {
                        FloatingPillControl(
                            scrollFraction = scrollFraction,
                            onScrub = { fraction ->
                                val total = gridState.layoutInfo.totalItemsCount
                                if (total > 0) {
                                    gridScope.launch {
                                        gridState.scrollToItem(
                                            ((total - 1) * fraction).toInt().coerceIn(0, total - 1),
                                        )
                                    }
                                }
                            },
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
    }

    if (settingsDialogVisible) {
        GallerySettingsDialog(
            preferences = state.preferences,
            onDismiss = { settingsDialogVisible = false },
            actions = actions,
        )
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

private const val RAIL_PANEL_WIDTH = 300
private const val SETTINGS_PANEL_WIDTH = 320
