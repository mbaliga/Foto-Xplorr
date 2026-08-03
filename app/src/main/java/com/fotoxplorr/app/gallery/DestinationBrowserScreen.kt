package com.fotoxplorr.app.gallery

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.hyle.HyleDestinationRail
import com.fotoxplorr.app.hyle.HyleRailItem
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.PlacesScreen

/**
 * The new left-nav / destination list from the mockups: Pets, People, Identity, Screenshots,
 * Photos, Videos, Favourites, Places, Protected. A parallel, additive entry point to the
 * existing four-destination bottom-nav IA (Timeline/Albums/Discover/Library in
 * [GalleryScreen]) -- reachable from the root screen's overflow menu -- rather than a
 * replacement of it: rewiring the whole app's primary navigation without the ability to
 * compile-check the result here felt like the wrong risk trade-off. See the PR description
 * for what "wired into existing navigation" means concretely for each new screen.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationBrowserScreen(
    state: GalleryUiState,
    actions: GalleryActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(HyleDestination.PHOTOS) }
    var pendingUnlock by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun assetsFor(destination: HyleDestination): List<MediaAsset> = when (destination) {
        HyleDestination.PHOTOS -> everydayAssets(
            assets = state.assets,
            archivedIds = state.library.archivedIds,
            sensitiveIds = state.sensitiveIds,
            lockedFolders = state.lockedFolders,
            unlockedFolders = state.unlockedFolders,
            preferences = state.preferences,
            query = "",
            tagsByMediaId = state.library.tagsByMediaId,
        )
        HyleDestination.VIDEOS -> smartAlbumAssets(
            SmartAlbum.VIDEOS, state.assets, state.favoriteIds, state.sensitiveIds,
            state.library.archivedIds, state.library.tagsByMediaId, state.lockedFolders,
            state.unlockedFolders, state.preferences,
        )
        HyleDestination.SCREENSHOTS -> smartAlbumAssets(
            SmartAlbum.SCREENSHOTS, state.assets, state.favoriteIds, state.sensitiveIds,
            state.library.archivedIds, state.library.tagsByMediaId, state.lockedFolders,
            state.unlockedFolders, state.preferences,
        )
        HyleDestination.FAVOURITES -> smartAlbumAssets(
            SmartAlbum.FAVORITES, state.assets, state.favoriteIds, state.sensitiveIds,
            state.library.archivedIds, state.library.tagsByMediaId, state.lockedFolders,
            state.unlockedFolders, state.preferences,
        )
        // No pet/face/identity recognition pipeline exists in Foto Xplorr -- these are
        // honestly empty rather than populated with anything fabricated.
        HyleDestination.PETS, HyleDestination.PEOPLE, HyleDestination.IDENTITY,
        HyleDestination.PLACES, HyleDestination.PROTECTED,
        -> emptyList()
    }

    val icons = remember {
        mapOf(
            HyleDestination.PETS.name to Icons.Outlined.Pets,
            HyleDestination.PEOPLE.name to Icons.Outlined.Face,
            HyleDestination.IDENTITY.name to Icons.Outlined.Fingerprint,
            HyleDestination.SCREENSHOTS.name to Icons.Outlined.Screenshot,
            HyleDestination.PHOTOS.name to Icons.Outlined.PhotoLibrary,
            HyleDestination.VIDEOS.name to Icons.Outlined.Videocam,
            HyleDestination.FAVOURITES.name to Icons.Outlined.Favorite,
            HyleDestination.PLACES.name to Icons.Outlined.Map,
            HyleDestination.PROTECTED.name to Icons.Outlined.Lock,
        )
    }
    val railItems = remember { HyleDestination.entries.map { HyleRailItem(it.name, it.label) } }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Destinations") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close destinations")
                    }
                },
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            HyleDestinationRail(
                items = railItems,
                selectedId = selected.name,
                onSelect = { id -> selected = HyleDestination.valueOf(id) },
                icons = icons,
                modifier = Modifier
                    .width(232.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                trailingContent = { item ->
                    RailThumbnailCascade(assetsFor(HyleDestination.valueOf(item.id)).take(3))
                },
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (selected) {
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
                            MediaGridScreen(
                                assets = emptyList(), columns = state.preferences.gridColumns,
                                favoriteIds = emptySet(), sensitiveIds = emptySet(), blurSensitive = false,
                                selectedIds = emptySet(), emptyMessage = "Places is unavailable here",
                                onOpen = {}, onToggleSelection = {},
                            )
                        }
                    }
                    HyleDestination.PROTECTED -> ProtectedFoldersPane(
                        lockedFolders = state.lockedFolders,
                        unlockedFolders = state.unlockedFolders,
                        onOpenFolder = { key -> pendingUnlock = key to key },
                    )
                    HyleDestination.PETS, HyleDestination.PEOPLE, HyleDestination.IDENTITY -> MediaGridScreen(
                        assets = emptyList(),
                        columns = state.preferences.gridColumns,
                        favoriteIds = emptySet(),
                        sensitiveIds = emptySet(),
                        blurSensitive = false,
                        selectedIds = emptySet(),
                        emptyMessage = when (selected) {
                            HyleDestination.PETS -> "No pet-recognition pipeline in this build"
                            HyleDestination.PEOPLE -> "No face-grouping pipeline in this build"
                            else -> "No identity-recognition pipeline in this build"
                        },
                        onOpen = {},
                        onToggleSelection = {},
                    )
                    else -> {
                        val destinationAssets = assetsFor(selected)
                        MediaGridScreen(
                            assets = destinationAssets,
                            columns = state.preferences.gridColumns,
                            favoriteIds = state.favoriteIds,
                            sensitiveIds = state.sensitiveIds,
                            blurSensitive = state.preferences.blurSensitive,
                            selectedIds = emptySet(),
                            emptyMessage = "Nothing here yet",
                            onOpen = { asset -> actions.onOpenAsset(asset, destinationAssets) },
                            onToggleSelection = {},
                        )
                    }
                }
            }
        }
    }

    pendingUnlock?.let { (key, name) ->
        PasswordDialog(
            title = "Unlock $name",
            confirmLabel = "Unlock",
            failureMessage = "Incorrect password or temporarily locked",
            onDismiss = { pendingUnlock = null },
            onConfirm = { password -> actions.onUnlockFolder(key, password) },
            onSuccess = { pendingUnlock = null },
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No protected folders yet",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lockedFolders.toList().sorted(), key = { it }) { key ->
            val unlocked = key in unlockedFolders
            Card(onClick = { onOpenFolder(key) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(if (unlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, contentDescription = null)
                    Text(key, modifier = Modifier.weight(1f))
                }
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
