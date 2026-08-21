package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.WordWheelRail
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.PlacesScreen

/**
 * The nine primary destinations from the owner's mockups. These are the app's top-level
 * information architecture -- reached from the rail room, not from a route -- and have
 * replaced the retired four-tab bottom navigation (Photos / Albums / Discover / Library) as
 * the default IA.
 *
 * A destination is a WORD, not a word and an icon.
 *
 * Each carried an [androidx.compose.ui.graphics.vector.ImageVector] until the rail's marker
 * became the destination's own covers (owner, 2026-08-14). That icon had exactly one reader --
 * the rail marker -- so once the covers took the gutter, every one of these was dead weight
 * whose only effect would have been to tempt a future surface into drawing a menu.
 */
enum class HyleDestination(val label: String) {
    PETS("Pets"),
    PEOPLE("People"),
    IDENTITY("Identity"),
    SCREENSHOTS("Screenshots"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    FAVOURITES("Favourites"),
    PLACES("Places"),
    PROTECTED("Protected"),
}

/** Which assets a destination shows. Kept separate from the UI so it is testable. */
fun destinationAssets(
    destination: HyleDestination,
    state: GalleryUiState,
    query: String = "",
): List<MediaAsset> {
    fun smart(album: SmartAlbum) = smartAlbumAssets(
        album, state.assets, state.favoriteIds, state.sensitiveIds,
        state.library.archivedIds, state.library.tagsByMediaId, state.lockedFolders,
        state.unlockedFolders, state.preferences,
    )

    fun everyday() = everydayAssets(
        assets = state.assets,
        archivedIds = state.library.archivedIds,
        sensitiveIds = state.sensitiveIds,
        lockedFolders = state.lockedFolders,
        unlockedFolders = state.unlockedFolders,
        preferences = state.preferences,
        query = "",
        tagsByMediaId = state.library.tagsByMediaId,
    )

    val base = when (destination) {
        HyleDestination.PHOTOS -> everyday()
        HyleDestination.VIDEOS -> smart(SmartAlbum.VIDEOS)
        HyleDestination.SCREENSHOTS -> smart(SmartAlbum.SCREENSHOTS)
        HyleDestination.FAVOURITES -> smart(SmartAlbum.FAVORITES)
        // Backed by the on-device recognition pass (com.fotoxplorr.app.recognition).
        HyleDestination.PEOPLE -> everyday().filter { it.id in state.recognition.peopleMediaIds }
        HyleDestination.PETS -> everyday().filter { it.id in state.recognition.petMediaIds }
        HyleDestination.IDENTITY -> everyday().filter { it.id in state.recognition.identityMediaIds }
        // Rendered by their own panes rather than a flat grid.
        HyleDestination.PLACES, HyleDestination.PROTECTED -> emptyList()
    }
    if (query.isBlank()) return base
    return base.filter { it.matchesGallerySearch(query, state.library.tagsFor(it.id), state.recognition, state.favoriteIds) }
}

/**
 * The empty-state sentence for a destination, given what the recognition pass currently
 * knows. Pure so the copy is unit-testable -- and so the honest distinction between "the
 * pass has not run yet", "it ran and found nothing" and "it failed" cannot quietly collapse
 * into a single vague string.
 */
fun destinationEmptyMessage(
    destination: HyleDestination,
    progress: com.fotoxplorr.app.recognition.RecognitionProgress,
): String {
    val recognitionBacked = destination in setOf(
        HyleDestination.PETS, HyleDestination.PEOPLE, HyleDestination.IDENTITY,
    )
    if (!recognitionBacked) return "Nothing here yet"
    progress.message?.let { return "On-device recognition stopped: $it" }
    if (progress.running) {
        return if (progress.total > 0) {
            "Looking through your photos on this device… ${progress.completed} of ${progress.total}"
        } else {
            "Looking through your photos on this device…"
        }
    }
    return when (destination) {
        HyleDestination.PETS -> "No cats, dogs or other pets found in your photos"
        HyleDestination.PEOPLE -> "No faces found in your photos"
        HyleDestination.IDENTITY -> "No identity documents found in your photos"
        else -> "Nothing here yet"
    }
}

/**
 * The left room: the nine destinations as the constellation's word wheel.
 *
 * The rail itself is `dev.aarso:cell-shell`'s [WordWheelRail], not a local one. The falloff maths
 * came *from* this app originally — the shared version is a port of `hyle/DestinationRail.kt` —
 * but it now carries the travelling bullet and the drag-driven weighting the owner's reference
 * video shows, and every app in the constellation gets the same wheel rather than each growing
 * its own.
 *
 * No scroll wrapper: the wheel measures its own rows against the height it is given and takes
 * care of overflow itself. Wrapping it in a `verticalScroll` would hand it an infinite height to
 * measure against, which is the fastest way to make a distance-weighted list stop weighting.
 *
 * Below the wheel, not inside a separate room, sits Settings (owner, 2026-08-14, reference
 * screenshot of a rail with a dimmer "Settings / Help" pair under the primary nav: *"The
 * settings should come below the menu nav itself"*). It used to be reachable only by already
 * knowing to swipe in from the right edge -- true, but not discoverable, especially now that
 * removing the header (item 4, previous round) took away the last visible entry point.
 * [WordWheelRail] is handed a bounded height via `Modifier.weight(1f)` so it keeps working
 * exactly as documented above; the settings row is the `Column`'s other, unweighted child.
 */
@Composable
fun DestinationRailPanel(
    items: List<WheelItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    state: GalleryUiState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
        WordWheelRail(
            items = items,
            selectedId = selectedId,
            onSelect = onSelect,
            inkColor = Color.White,
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Tighter than the old 24dp because the marker gutter grew: the covers are wider
                // than the icon they replaced, and the words must not march away from the edge.
                .padding(start = 12.dp, end = 16.dp, top = 40.dp),
            // The covers ARE the marker now, and they ride the left gutter (owner, 2026-08-14: the
            // icon goes, "the few images are indication enough... I would rather have them on the
            // left and for them to do the movement dance").
            //
            // Putting them in the marker slot rather than the trailing one is what buys the dance
            // for free: the marker is pinned to its row and animates on a longer curve than the
            // wheel, so a selection change makes the covers travel across the intervening rows
            // and dip in scale mid-flight, exactly as the reference video's morphing bullet did.
            // In the trailing slot they simply appeared beside whichever row was selected.
            marker = { item ->
                RailThumbnailCascade(
                    destinationAssets(HyleDestination.valueOf(item.id), state).take(3),
                )
            },
            // Nothing trails the word any more: the one indicator should not be shown twice.
            markerWidth = RAIL_MARKER_WIDTH.dp,
            markerHeight = RAIL_MARKER_HEIGHT.dp,
        )
        RailSettingsRow(onClick = onOpenSettings)
    }
}

/**
 * The one row under the wheel. Visually a step down from the primary destinations -- smaller,
 * lighter weight, dimmer -- so the eye reads it as secondary without needing a divider line to
 * say so, matching the reference screenshot's own "Settings / Help" treatment.
 *
 * A plain [Text], not a [WordWheelRail] row: this list is never scrolled, is never the wheel's
 * subject, and does not participate in its distance-weighted fade -- giving it one would be
 * pretending it is a tenth destination, which is exactly the confusion a fixed row avoids.
 */
@Composable
private fun RailSettingsRow(onClick: () -> Unit) {
    Text(
        text = "Settings",
        color = Color.White.copy(alpha = 0.55f),
        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // This is now the panel's bottom-most element, so it -- not a hardcoded inset --
            // is what has to clear the gesture-navigation bar.
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
    )
}

/** What a destination actually renders: a grid, the map, or the protected-folder list. */
@Composable
fun DestinationContent(
    destination: HyleDestination,
    assets: List<MediaAsset>,
    state: GalleryUiState,
    actions: GalleryActions,
    selection: GallerySelection,
    onSelectionChange: (GallerySelection) -> Unit,
    gridState: LazyGridState,
    onRequestUnlock: (String, String) -> Unit,
) {
    when (destination) {
        HyleDestination.PLACES -> {
            val spatial = LocalSpatialExperience.current
            if (spatial != null) {
                PlacesScreen(
                    assets = spatial.assets,
                    geoState = spatial.geoState,
                    onIndexLocations = spatial.onIndexLocations,
                    onOpenAsset = spatial.onOpenAsset,
                )
            } else {
                DestinationMessage("Places is unavailable here")
            }
        }
        HyleDestination.PROTECTED -> ProtectedFoldersPane(
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            onOpenFolder = { key -> onRequestUnlock(key, key) },
        )
        else -> Column(Modifier.fillMaxSize()) {
            if (destination == HyleDestination.PEOPLE && state.recognition.people.isNotEmpty()) {
                PeopleStrip(
                    clusters = state.recognition.people,
                    assets = state.assets,
                    onOpenPerson = { ids ->
                        val personAssets = assets.filter { it.id in ids }
                        personAssets.firstOrNull()?.let { actions.onOpenAsset(it, personAssets) }
                    },
                )
            }
            // Either the grid or the empty state, never both: TimelineScreen renders its own
            // generic "No media matches the current filters" when handed an empty list, which
            // would otherwise stack above this destination's far more useful message and push
            // it off-screen, since both fill the available height.
            if (assets.isEmpty()) {
                DestinationMessage(destinationEmptyMessage(destination, state.recognitionProgress))
            } else {
                TimelineScreen(
                    assets = assets,
                    grouping = state.preferences.timelineGrouping,
                    columns = state.preferences.gridColumns,
                    favoriteIds = state.favoriteIds,
                    sensitiveIds = state.sensitiveIds,
                    blurSensitive = state.preferences.blurSensitive,
                    selectedIds = selection.selectedIds,
                    selectionActive = selection.isActive,
                    onOpen = { asset -> actions.onOpenAsset(asset, assets) },
                    onToggleSelection = { id -> onSelectionChange(selection.toggle(id)) },
                    // The mockups' main grid is one continuous mosaic with no date headers.
                    showDateHeaders = false,
                    gridState = gridState,
                    fitToTile = state.preferences.fitToTile,
                    loopAnimations = state.preferences.loopAnimations,
                    longPressPreview = state.preferences.longPressPreview,
                )
            }
        }
    }
}

/** The people found by clustering, as a row of round covers above the People grid. */
@Composable
private fun PeopleStrip(
    clusters: List<com.fotoxplorr.app.recognition.PersonCluster>,
    assets: List<MediaAsset>,
    onOpenPerson: (Set<MediaId>) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(Color.Black),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(clusters, key = { it.id }) { cluster ->
            val cover = assets.firstOrNull { it.id in cluster.mediaIds }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF1A1A1A))
                        .clickable { onOpenPerson(cluster.mediaIds.toSet()) },
                ) {
                    cover?.let {
                        MediaImage(it, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                }
                Text(
                    // Clusters are unnamed: nothing on the device knows who these people are,
                    // and inventing names would be fabrication. Numbering them is honest.
                    "Person ${cluster.id + 1}",
                    style = TextStyle(fontSize = 11.sp),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DestinationMessage(message: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(
            message,
            modifier = Modifier.padding(32.dp),
            style = TextStyle(fontSize = 15.sp),
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun ProtectedFoldersPane(
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    onOpenFolder: (String) -> Unit,
) {
    if (lockedFolders.isEmpty()) {
        DestinationMessage("No protected folders yet")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lockedFolders.toList().sorted(), key = { it }) { key ->
            val unlocked = key in unlockedFolders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF141414))
                    .clickable { onOpenFolder(key) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (unlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                )
                Text(key, color = Color.White, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RailThumbnailCascade(assets: List<MediaAsset>) {
    if (assets.isEmpty()) return
    Box(Modifier.size(width = 28.dp + 10.dp * (assets.size - 1), height = 28.dp)) {
        assets.forEachIndexed { index, asset ->
            MediaImage(
                asset = asset,
                modifier = Modifier
                    .size(26.dp)
                    .offset(x = (index * 10).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * The screens the retired bottom navigation used to own. They are kept, and kept reachable
 * from the settings panel, so retiring that navigation removed a default rather than a
 * feature.
 */
enum class LegacyScreen(val label: String) {
    ALBUMS("Albums"),
    CALENDAR("Calendar"),
    DISCOVER("Discover"),
    LIBRARY("Library"),
}

@Composable
fun LegacyScreenHost(
    screen: LegacyScreen,
    state: GalleryUiState,
    actions: GalleryActions,
    query: String,
    onOpenRoute: (BrowserRoute) -> Unit,
    onRequestUnlock: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when (screen) {
            LegacyScreen.ALBUMS -> AlbumsScreen(
                assets = state.assets,
                collections = state.library.collections,
                archivedIds = state.library.archivedIds,
                lockedFolders = state.lockedFolders,
                unlockedFolders = state.unlockedFolders,
                showVideos = state.preferences.showVideos,
                query = query,
                onOpenAlbum = { album ->
                    if (album.key in state.lockedFolders && album.key !in state.unlockedFolders) {
                        onRequestUnlock(album.key, album.name)
                    } else {
                        onOpenRoute(BrowserRoute.DeviceAlbum(album.key, album.name))
                    }
                },
                onOpenCollection = { collection ->
                    onOpenRoute(BrowserRoute.Collection(collection.id, collection.name))
                },
            )
            LegacyScreen.CALENDAR -> CalendarScreen(
                assets = state.assets,
                // A day opens as a route rather than a nested grid, so the calendar hands off to
                // exactly the same browsing surface everything else uses.
                onOpenDay = { dayAssets ->
                    dayAssets.firstOrNull()?.let { actions.onOpenAsset(it, dayAssets) }
                },
            )
            LegacyScreen.DISCOVER -> DiscoverScreen(
                summaries = smartAlbumSummaries(
                    assets = state.assets,
                    favoriteIds = state.favoriteIds,
                    sensitiveIds = state.sensitiveIds,
                    archivedIds = state.library.archivedIds,
                    tagsByMediaId = state.library.tagsByMediaId,
                    lockedFolders = state.lockedFolders,
                    unlockedFolders = state.unlockedFolders,
                    preferences = state.preferences,
                ),
                onOpen = { onOpenRoute(BrowserRoute.Smart(it.album)) },
            )
            LegacyScreen.LIBRARY -> LibraryScreen(
                library = state.library,
                privateAlbumCount = state.lockedFolders.size,
                trashCount = state.assets.count { it.isTrashed },
                onOpenCollection = { onOpenRoute(BrowserRoute.Collection(it.id, it.name)) },
                onOpenTag = { onOpenRoute(BrowserRoute.Tag(it)) },
                onOpenArchive = { onOpenRoute(BrowserRoute.Smart(SmartAlbum.ARCHIVED)) },
                onOpenTrash = { onOpenRoute(BrowserRoute.Smart(SmartAlbum.TRASH)) },
                onOpenPrivateFolders = { },
                onOpenSettings = onOpenSettings,
                onExportMetadata = actions.onExportMetadata,
                onImportMetadata = actions.onImportMetadata,
            )
        }
    }
}

/**
 * The settings panel from the mockup: a small letterspaced "SETTINGS" label over large
 * light "Default View" type, sliding in from the right with the grid still showing at the
 * left edge.
 */
@Composable
fun SettingsPanel(
    preferences: GalleryPreferencesState,
    onOpenAllSettings: () -> Unit,
    onSetDefaultDestination: (HyleDestination) -> Unit,
    onOpenLegacyScreen: (LegacyScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 28.dp, end = 20.dp, top = 32.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "SETTINGS",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
            color = Color.White,
        )
        Text(
            "Default View",
            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light),
            color = Color.White,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        // Now over the nine destinations, not the four retired bottom-nav tabs.
        HyleDestination.entries.forEach { candidate ->
            val selected = preferences.defaultDestination == candidate
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetDefaultDestination(candidate) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 20.dp, height = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (selected) Box(Modifier.size(8.dp).background(Color.White))
                }
                Text(
                    candidate.label,
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Light,
                    ),
                    color = Color.White.copy(alpha = if (selected) 1f else 0.55f),
                )
            }
        }

        Text(
            "MORE",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
            color = Color.White,
            modifier = Modifier.padding(top = 26.dp, bottom = 4.dp),
        )
        LegacyScreen.entries.forEach { screen ->
            TextButton(
                onClick = { onOpenLegacyScreen(screen) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    screen.label,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        TextButton(onClick = onOpenAllSettings, modifier = Modifier.fillMaxWidth()) {
            Text(
                "All settings…",
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * The rail's marker gutter, sized for a three-cover cascade (28dp + 10dp per extra cover = 48dp)
 * with a little air. Handed to the rail so every row reserves it and the words keep one optical
 * left edge whether or not the covers are beside them.
 */
private const val RAIL_MARKER_WIDTH = 52

/** Cover height, matching [RailThumbnailCascade]'s own 28dp box. */
private const val RAIL_MARKER_HEIGHT = 28
