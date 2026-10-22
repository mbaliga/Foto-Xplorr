package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
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
import com.fotoxplorr.app.curate.ArchiveAdvisor
import com.fotoxplorr.app.curate.ArchiveReviewItem
import com.fotoxplorr.app.curate.ArchiveSuggestionsReview
import com.fotoxplorr.app.experimental.ExperimentalScreen
import com.fotoxplorr.app.experimental.KeepExperimentalState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.app.ui.HelpCopy

/**
 * The primary destinations from the owner's mockups. Fotoz is visuals only, so there is no Audio
 * destination; a saved default of the old Audio value falls back to Photos (see
 * `GalleryPreferences`). These are the app's top-level information architecture -- reached from the rail room, not from
 * a route -- and have replaced the retired four-tab bottom navigation (Photos / Albums / Discover
 * / Library) as the default IA.
 *
 * A destination is a WORD, not a word and an icon.
 *
 * Each carried an [androidx.compose.ui.graphics.vector.ImageVector] until the rail's marker
 * became the destination's own covers (owner, 2026-08-14). That icon had exactly one reader --
 * the rail marker -- so once the covers took the gutter, every one of these was dead weight
 * whose only effect would have been to tempt a future surface into drawing a menu. The covers
 * have since given way to a plain square bullet (owner's swivel reference, 2026-10 -- see
 * [DestinationRailPanel]), which reads nothing from the destination at all, so the word stays
 * the whole of what a destination is.
 */
enum class HyleDestination(val label: String) {
    PETS("Pets"),
    PEOPLE("People"),
    IDENTITY("Identity"),
    SCREENSHOTS("Screenshots"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    FAVOURITES("Favourites"),
    /**
     * The Experimental section (compass view, 3D wall, ...). The constant is still PLACES because
     * the name is saved ("Open the app on") and pinned by the goldens; only the label changed.
     */
    PLACES("Experimental"),
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
        state.unlockedFolders, state.preferences, state.animatedIds,
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
    return base.filter { it.matchesGallerySearch(query, state.library.tagsFor(it.id), state.recognition, state.favoriteIds, state.animatedIds, state.library.archivedIds) }
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
            "${HelpCopy.Hint.SCANNING_DESTINATION} ${progress.completed} of ${progress.total}"
        } else {
            HelpCopy.Hint.SCANNING_DESTINATION
        }
    }
    return when (destination) {
        HyleDestination.PETS -> HelpCopy.Hint.NO_PETS
        HyleDestination.PEOPLE -> HelpCopy.Hint.NO_FACES
        HyleDestination.IDENTITY -> HelpCopy.Hint.NO_ID_DOCUMENTS
        else -> "Nothing here yet"
    }
}

/**
 * The left room: the destinations as the owner's swivel reference (2026-10) draws them -- large
 * bold words on black, the current one white with a filled square beside it, the rest dimmed by
 * their distance from it -- with Settings below.
 *
 * The rail is [DestinationWordRail], an app-local re-cut of `dev.aarso:cell-shell`'s word wheel.
 * The falloff maths came *from* this app originally and the shared wheel is still the behaviour
 * (the run turns, the hovered word is committed as the wheel hesitates, release only settles), but its type, fade and marker
 * are private constants that cannot be set from outside and none of them is the reference's --
 * see that composable's own doc for the exact gap. The shared library is untouched.
 *
 * **The marker is a bullet again, not covers.** On 2026-08-14 the owner replaced the rail's
 * bullet with a cascade of each destination's own covers (*"the few images are indication
 * enough... I would rather have them on the left and for them to do the movement dance"*). The
 * 2026-10 reference draws the bullet and no covers, and a reference is a later decision than a
 * direction, so this follows it: the covers are gone, and so is the gallery state the panel only
 * ever took to fetch them. The dance survives -- the square hops across the rows with the wheel,
 * stretching along its path as it goes.
 *
 * No scroll wrapper: the wheel measures its own rows against the height it is given and takes
 * care of overflow itself. Wrapping it in a `verticalScroll` would hand it an infinite height to
 * measure against, which is the fastest way to make a distance-weighted list stop weighting.
 *
 * Below the wheel sits Settings (owner, 2026-08-14, reference screenshot of a rail with a dimmer
 * "Settings / Help" pair under the primary nav: *"The settings should come below the menu nav
 * itself"*). It was once a room reached by swiping in from an edge -- true, but not discoverable,
 * especially once removing the header took away the last visible entry point -- and is now a pane
 * that takes over the place the photos are, opened only from this row. [DestinationWordRail] is
 * handed a bounded height via `Modifier.weight(1f)` so it keeps working exactly as documented
 * above; the settings row is the `Column`'s other, unweighted child.
 *
 * [paneOpen] and [onLeavePane] are the rail's way back from that pane: the selected word is the
 * destination under it, so tapping it is [onReselect], and a scrub that rests on it is
 * [onLeavePane] (see [DestinationWordRail]). [onTurning] reports whether the wheel is being turned
 * or is still settling, for a host whose content must not react to a scrub that merely rests on a
 * word.
 */
@Composable
fun DestinationRailPanel(
    items: List<WheelItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onReselect: () -> Unit = {},
    paneOpen: Boolean = false,
    onLeavePane: () -> Unit = {},
    onTurning: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
        DestinationWordRail(
            items = items,
            selectedId = selectedId,
            onSelect = onSelect,
            onReselect = onReselect,
            paneOpen = paneOpen,
            onLeavePane = onLeavePane,
            onTurning = onTurning,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 16 in, so the 16dp square sits at 16..32 and the words, past the rail's own
                // 32dp gutter, at the reference's 48.
                .padding(start = RAIL_INSET.dp, end = 16.dp),
        )
        RailSettingsRow(onClick = onOpenSettings)
    }
}

/**
 * The one row under the wheel. Visually a step down from the primary destinations -- smaller,
 * lighter weight, dimmer -- so the eye reads it as secondary without needing a divider line to
 * say so, matching the reference screenshot's own "Settings / Help" treatment.
 *
 * A plain [Text], not a [DestinationWordRail] row: this list is never scrolled, is never the wheel's
 * subject, and does not participate in its distance-weighted fade -- giving it one would be
 * pretending it is a tenth destination, which is exactly the confusion a fixed row avoids.
 */
@Composable
private fun RailSettingsRow(onClick: () -> Unit) {
    Text(
        text = "Settings",
        color = Color.White.copy(alpha = 0.55f),
        // The rail's own face, not the platform default, and set on the same optical edge as the
        // words above it (the marker's inset plus the rail's gutter), not at the marker's edge.
        style = TextStyle(
            fontFamily = HyleGrotesk,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = (-0.3).sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // This is now the panel's bottom-most element, so it -- not a hardcoded inset --
            // is what has to clear the gesture-navigation bar.
            .navigationBarsPadding()
            .padding(start = (RAIL_INSET + RAIL_GUTTER_DP).dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
    )
}

/** The panel's inset from the room's left edge: the bullet's own left edge. */
private const val RAIL_INSET = 16

/**
 * What a destination actually renders: the zoomable grid, the Experimental section, or the protected-folder list.
 *
 * [layout] is the grid's groups and headers, built once by the screen over these same [assets] so
 * the scrubber, pill and arrow keys count the positions the grid draws; [rung] is how zoomed it is.
 * A photo tapped here opens the viewer on [ZoomLayout.renderedAssets], the order on screen.
 *
 * [experimentalState] is the one holder, above this call, that keeps the Experimental section's open
 * experiment and drafts while another pane or word has the place (see [KeepExperimentalState]).
 */
@Composable
internal fun DestinationContent(
    destination: HyleDestination,
    assets: List<MediaAsset>,
    layout: ZoomLayout,
    rung: Int,
    state: GalleryUiState,
    actions: GalleryActions,
    selection: GallerySelection,
    onSelectionChange: (GallerySelection) -> Unit,
    gridState: LazyGridState,
    anchor: LayoutAnchor,
    onRequestUnlock: (String, String) -> Unit,
    horizontal: Boolean = false,
    experimentalState: SaveableStateHolder? = null,
) {
    when (destination) {
        HyleDestination.PLACES -> {
            val spatial = LocalSpatialExperience.current
            if (spatial != null) {
                KeepExperimentalState(experimentalState) {
                    ExperimentalScreen(
                        assets = spatial.assets,
                        indexInput = spatial.indexInput,
                        geoState = spatial.geoState,
                        onIndexLocations = spatial.onIndexLocations,
                        onOpenAsset = spatial.onOpenAsset,
                        sensitiveIds = state.sensitiveIds,
                        blurSensitive = state.preferences.blurSensitive,
                    )
                }
            } else {
                DestinationMessage("Experimental is unavailable here")
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
                    // The already-filtered destination list, not raw state.assets: a cluster's
                    // cover photo must obey the same visibility rule as the grid right below it.
                    assets = assets,
                    onOpenPerson = { ids ->
                        val personAssets = assets.filter { it.id in ids }
                        personAssets.firstOrNull()?.let { actions.onOpenAsset(it, personAssets) }
                    },
                )
            }
            // Either the grid or the empty state, never both: the grid renders its own generic
            // message when handed an empty list, which would otherwise stack above this
            // destination's far more useful one and push it off-screen, since both fill the
            // available height.
            if (assets.isEmpty()) {
                DestinationMessage(destinationEmptyMessage(destination, state.recognitionProgress))
            } else {
                ZoomGrid(
                    layout = layout,
                    rung = rung,
                    favoriteIds = state.favoriteIds,
                    sensitiveIds = state.sensitiveIds,
                    blurSensitive = state.preferences.blurSensitive,
                    selectedIds = selection.selectedIds,
                    selectionActive = selection.isActive,
                    emptyMessage = HelpCopy.Hint.NO_MATCHES,
                    onOpen = { asset -> actions.onOpenAsset(asset, layout.renderedAssets) },
                    onToggleSelection = { id -> onSelectionChange(selection.toggle(id)) },
                    gridState = gridState,
                    fitToTile = state.preferences.fitToTile,
                    loopAnimations = state.preferences.loopAnimations,
                    animatedIds = state.animatedIds,
                    anchor = anchor,
                    horizontal = horizontal,
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
                        // Fixed on purpose (reference design): a neutral surface, no accent role.
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
        item {
            Text(
                HelpCopy.Note.PROTECTED_NOT_ENCRYPTED,
                color = Color.White.copy(alpha = 0.6f),
                style = RoomStyle.Caption,
            )
        }
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

    /**
     * The archive review queue. A [LegacyScreen] rather than a tenth rail destination: the nine
     * are the app's primary IA and the owner has been explicit that things which are not places
     * you browse do not belong there. This is a job you finish and leave, reached from Settings,
     * which is exactly what this enum exists for.
     */
    TIDY_UP("Tidy up"),
}

@Composable
fun LegacyScreenHost(
    screen: LegacyScreen,
    state: GalleryUiState,
    actions: GalleryActions,
    query: String,
    /** The pane's back chevron: hands the place the photos are back to the destination below. */
    onBack: () -> Unit,
    onOpenRoute: (BrowserRoute) -> Unit,
    onRequestUnlock: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    /** Switches the primary rail to a top-level destination -- Protected is one of the nine, not
     *  a drill-down [BrowserRoute], so "open Private folders" from here needs this rather than
     *  [onOpenRoute]. */
    onOpenDestination: (HyleDestination) -> Unit = {},
) {
    // The Calendar's display list: the one visibility filter (P0-01), so a locked, archived or
    // hidden-sensitive photo can never surface by date even though it isn't trashed.
    val calendarAssets = remember(
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

    // The same scaffold Settings uses, so the five of these and Settings read as one family: black
    // ground, a way out in the corner, a title, and white ink whatever the theme. Before this they
    // had no header and no way out but Back, and drew dark-on-black wherever a Text set no colour.
    PaneScaffold(title = screen.label, onBack = onBack) {
        // Each screen fills what the header leaves: an unweighted Column child is measured against
        // the height remaining after the children before it.
        when (screen) {
            LegacyScreen.ALBUMS -> AlbumsScreen(
                assets = state.assets,
                collections = state.library.collections,
                archivedIds = state.library.archivedIds,
                sensitiveIds = state.sensitiveIds,
                lockedFolders = state.lockedFolders,
                unlockedFolders = state.unlockedFolders,
                hideSensitive = state.preferences.hideSensitive,
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
                assets = calendarAssets,
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
                    animatedIds = state.animatedIds,
                ),
                onOpen = { onOpenRoute(BrowserRoute.Smart(it.album)) },
                onOpenExperimental = { onOpenDestination(HyleDestination.PLACES) },
            )
            LegacyScreen.LIBRARY -> LibraryScreen(
                library = state.library,
                privateAlbumCount = state.lockedFolders.size,
                // A count, not a display of content: trashed items in a locked folder still count
                // toward "how many things are in Trash", so this deliberately reads state.assets
                // rather than a filtered list.
                trashCount = state.assets.count { it.isTrashed },
                onOpenCollection = { onOpenRoute(BrowserRoute.Collection(it.id, it.name)) },
                onOpenTag = { onOpenRoute(BrowserRoute.Tag(it)) },
                onOpenArchive = { onOpenRoute(BrowserRoute.Smart(SmartAlbum.ARCHIVED)) },
                onOpenTrash = { onOpenRoute(BrowserRoute.Smart(SmartAlbum.TRASH)) },
                onOpenPrivateFolders = { onOpenDestination(HyleDestination.PROTECTED) },
                onOpenSettings = onOpenSettings,
                onExportMetadata = actions.onExportMetadata,
                onImportMetadata = actions.onImportMetadata,
            )
            LegacyScreen.TIDY_UP -> ArchiveSuggestionsReview(
                // remember, keyed on exactly the inputs the derivation reads: it groups the whole
                // library for duplicates, and this host recomposes on every recognition-progress
                // tick -- several a second while indexing -- which is a full O(n) pass per frame
                // on a 22k library without it.
                items = remember(state.assets, state.favoriteIds, state.library, state.lockedFolders, state.unlockedFolders) {
                    archiveReviewItems(state)
                },
                onAccept = { ids -> actions.onSetArchived(ids, true) },
                onReject = actions.onRejectArchiveSuggestions,
            )
        }
    }
}

/**
 * Everything [com.fotoxplorr.app.curate.ArchiveAdvisor] currently offers, paired with its photo.
 *
 * The candidate list is built here rather than inside the advisor because the advisor is pure and
 * reads no clock and no store — see its own KDoc. `now` is sampled once for the whole pass so
 * every photo's age is measured against the same instant; sampling per photo would make the
 * ordering of the list very slightly affect its contents.
 *
 * `sharpness` is left null, which the advisor reads as "unknown", not "sharp" — so no photo is
 * currently offered for being blurry. Computing it means decoding every photo in the library, and
 * doing that on the way into a settings screen would freeze it solid on a real library. The
 * signal is built and tested ([com.fotoxplorr.app.curate.BlurDetector]); what it is waiting for is
 * somewhere to cache a score per photo, so the background pass can fill it in over time instead of
 * this screen computing it all at once.
 */
private fun archiveReviewItems(state: GalleryUiState): List<ArchiveReviewItem> {
    val assetsById = state.assets.associateBy { it.id }
    val now = System.currentTimeMillis()
    val candidates = state.assets
        .filterNot { it.isTrashed || it.isVideo }
        // The same privacy rule every other surface applies. Without it the queue listed a
        // locked folder's old screenshots and duplicates with their thumbnails and filenames,
        // to anyone holding the phone, with the folder still locked.
        .filter { it.isPrivacyVisible(state.lockedFolders, state.unlockedFolders) }
        .map { asset ->
            ArchiveAdvisor.ArchiveCandidate(
                mediaId = asset.id,
                isFavorite = asset.id in state.favoriteIds,
                isArchived = asset.id in state.library.archivedIds,
                previouslyDismissed = state.library.isDismissedFromArchiveSuggestions(asset.id),
                isScreenshot = asset.isScreenshot(),
                ageMillis = now - asset.dateTakenMillis,
                sizeBytes = asset.sizeBytes,
                widthPx = asset.width,
                heightPx = asset.height,
                mimeType = asset.mimeType,
                dateModifiedSeconds = asset.dateModifiedSeconds,
                sharpness = null,
            )
        }
    return ArchiveAdvisor.suggestions(candidates).mapNotNull { suggestion ->
        assetsById[suggestion.mediaId]?.let { ArchiveReviewItem(suggestion, it) }
    }
}
