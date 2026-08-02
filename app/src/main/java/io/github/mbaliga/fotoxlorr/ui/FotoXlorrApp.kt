package io.github.mbaliga.fotoxlorr.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.format.Formatter
import android.util.Size
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mbaliga.fotoxlorr.data.MediaAccessLevel
import io.github.mbaliga.fotoxlorr.data.MediaStorePhotoRepository
import io.github.mbaliga.fotoxlorr.data.mediaAccessLevel
import io.github.mbaliga.fotoxlorr.data.requiredMediaPermissions
import io.github.mbaliga.fotoxlorr.model.PhotoAsset
import io.github.mbaliga.fotoxlorr.ui.theme.FotoXlorrTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class GalleryView { Grid, Timeline, Map, Compass }
private sealed interface LibraryState {
    data object Loading : LibraryState
    data class Ready(val photos: List<PhotoAsset>) : LibraryState
    data class Failed(val reason: String) : LibraryState
}
private sealed interface DecodeState {
    data object Loading : DecodeState
    data class Ready(val drawable: Drawable) : DecodeState
    data class Failed(val reason: String) : DecodeState
}

@Composable
fun FotoXlorrApp() = FotoXlorrTheme {
    val context = LocalContext.current
    val repository = remember(context) { MediaStorePhotoRepository(context) }
    var access by remember { mutableStateOf(mediaAccessLevel(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf(GalleryView.Grid) }
    var selected by remember { mutableStateOf<PhotoAsset?>(null) }
    var library by remember { mutableStateOf<LibraryState>(LibraryState.Loading) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        access = mediaAccessLevel(context)
        refresh++
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val newAccess = mediaAccessLevel(context)
                if (newAccess != access) {
                    access = newAccess
                    refresh++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(access, refresh) {
        library = if (access == MediaAccessLevel.Denied) {
            LibraryState.Ready(emptyList())
        } else {
            LibraryState.Loading
            runCatching { repository.loadRecent() }.fold(
                onSuccess = { LibraryState.Ready(it) },
                onFailure = { LibraryState.Failed(it.message ?: "Media scan failed") },
            )
        }
    }

    selected?.let { photo ->
        PhotoViewer(photo = photo, onBack = { selected = null })
        return@FotoXlorrTheme
    }

    GalleryHome(
        access = access,
        view = view,
        library = library,
        onView = { view = it },
        onRequestAccess = { permissionLauncher.launch(requiredMediaPermissions()) },
        onRefresh = { refresh++ },
        onPhoto = { selected = it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryHome(
    access: MediaAccessLevel,
    view: GalleryView,
    library: LibraryState,
    onView: (GalleryView) -> Unit,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onPhoto: (PhotoAsset) -> Unit,
) {
    val count = (library as? LibraryState.Ready)?.photos?.size
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Foto Xlorr")
                        Text(
                            when {
                                access == MediaAccessLevel.Partial -> "Selected photos"
                                count != null -> "$count recent"
                                else -> "Local gallery"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = access != MediaAccessLevel.Denied) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh library")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                GalleryView.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == view,
                        onClick = { onView(item) },
                        icon = { Icon(viewIcon(item), contentDescription = null) },
                        label = { Text(item.name) },
                    )
                }
            }
        },
    ) { padding ->
        if (access == MediaAccessLevel.Denied) {
            PermissionGate(Modifier.padding(padding), onRequestAccess)
        } else {
            Column(Modifier.padding(padding)) {
                if (access == MediaAccessLevel.Partial) {
                    PartialAccessBanner(onRequestAccess)
                }
                when (library) {
                    LibraryState.Loading -> Centered { CircularProgressIndicator() }
                    is LibraryState.Failed -> FailureState(library.reason, onRefresh)
                    is LibraryState.Ready -> when (view) {
                        GalleryView.Grid -> PhotoGrid(library.photos, onPhoto)
                        GalleryView.Timeline -> PhotoTimeline(library.photos, onPhoto)
                        GalleryView.Map -> SpatialPlaceholder(
                            Icons.Rounded.Map,
                            "Map",
                            "Offline maps and location extraction arrive after dependable file operations.",
                        )
                        GalleryView.Compass -> SpatialPlaceholder(
                            Icons.Rounded.Explore,
                            "Compass",
                            "This will rotate real geolocated clusters. Untagged photos will not be given invented directions.",
                        )
                    }
                }
            }
        }
    }
}

private fun viewIcon(view: GalleryView): ImageVector = when (view) {
    GalleryView.Grid -> Icons.Rounded.GridView
    GalleryView.Timeline -> Icons.Rounded.Timeline
    GalleryView.Map -> Icons.Rounded.Map
    GalleryView.Compass -> Icons.Rounded.Explore
}

@Composable
private fun PermissionGate(modifier: Modifier, onRequestAccess: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Your photos stay on this device", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Grant full or selected-photo access. Foto Xlorr needs no account and does not request broad all-files access.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Surface(
            onClick = onRequestAccess,
            shape = RoundedCornerShape(100.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text("Choose photo access", Modifier.padding(horizontal = 22.dp, vertical = 13.dp))
        }
    }
}

@Composable
private fun PartialAccessBanner(onManage: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Showing only selected photos", Modifier.weight(1f))
            TextButton(onClick = onManage) { Text("Manage") }
        }
    }
}

@Composable
private fun PhotoGrid(photos: List<PhotoAsset>, onPhoto: (PhotoAsset) -> Unit) {
    if (photos.isEmpty()) return EmptyLibrary()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(photos, key = { it.uri.toString() }) { photo ->
            PhotoTile(photo, Modifier.fillMaxWidth()) { onPhoto(photo) }
        }
    }
}

@Composable
private fun PhotoTimeline(photos: List<PhotoAsset>, onPhoto: (PhotoAsset) -> Unit) {
    if (photos.isEmpty()) return EmptyLibrary()
    val formatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
    val zone = remember { ZoneId.systemDefault() }
    val sections = remember(photos, zone) {
        photos.groupBy { photo ->
            if (photo.dateTakenMillis <= 0L) "Unknown date"
            else formatter.format(Instant.ofEpochMilli(photo.dateTakenMillis).atZone(zone))
        }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        sections.forEach { (label, sectionPhotos) ->
            item("header-$label") {
                Text(
                    label,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item("row-$label") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sectionPhotos, key = { it.uri.toString() }) { photo ->
                        PhotoTile(photo, Modifier.width(108.dp)) { onPhoto(photo) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(photo: PhotoAsset, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(126.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        MediaThumbnail(photo, Modifier.fillMaxSize())
        if (photo.mimeType == "image/gif") {
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text("GIF", Modifier.padding(5.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MediaThumbnail(photo: PhotoAsset, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(photo.uri, Size(512, 512), null) }.getOrNull()
        }
    }
    bitmap?.let {
        Image(it.asImageBitmap(), photo.displayName, modifier, contentScale = ContentScale.Crop)
    } ?: Box(modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyLibrary() = Centered {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text("No accessible photos", style = MaterialTheme.typography.titleLarge)
        Text("Add photos or expand selected-photo access.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailureState(reason: String, onRetry: () -> Unit) = Centered {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.ImageNotSupported, null, Modifier.size(42.dp))
        Spacer(Modifier.height(12.dp))
        Text("Could not read the library", style = MaterialTheme.typography.titleLarge)
        Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun SpatialPlaceholder(icon: ImageVector, title: String, body: String) = Centered {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(50.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoViewer(photo: PhotoAsset, onBack: () -> Unit) {
    val context = LocalContext.current
    val decoded by produceState<DecodeState>(DecodeState.Loading, photo.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, photo.uri))
            }.fold(
                onSuccess = { DecodeState.Ready(it) },
                onFailure = { DecodeState.Failed(it.message ?: "Unsupported or damaged image") },
            )
        }
    }
    val drawable = (decoded as? DecodeState.Ready)?.drawable
    LaunchedEffect(drawable) { (drawable as? Animatable)?.start() }
    DisposableEffect(drawable) { onDispose { (drawable as? Animatable)?.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(photo.displayName, maxLines = 1)
                        Text(
                            "${photo.mimeType} · ${Formatter.formatFileSize(context, photo.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = decoded) {
                DecodeState.Loading -> CircularProgressIndicator()
                is DecodeState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ImageNotSupported, null, Modifier.size(52.dp))
                    Text("This decoder cannot open the file")
                    Text(state.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is DecodeState.Ready -> AndroidView(
                    factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                    update = { it.setImageDrawable(state.drawable) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
