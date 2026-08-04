@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.ai

import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun SimilarityExplorerScreen(
    assets: List<MediaAsset>,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val modelManager = remember(context) { LocalModelManager(context.applicationContext) }
    val repository = remember(context) { EmbeddingRepository(context.applicationContext) }
    val indexer = remember(context, repository) { SimilarityIndexer(context.applicationContext, repository) }
    val modelState by modelManager.observe().collectAsStateWithLifecycle()
    val embeddingState by repository.observe().collectAsStateWithLifecycle()
    val indexingState by indexer.observe().collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var points by remember { mutableStateOf<List<SimilarityPoint>>(emptyList()) }
    var selectedId by remember { mutableStateOf<MediaId?>(null) }
    var neighbours by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var indexJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val assetById = remember(assets) { assets.associateBy { it.id } }
    val imageAssets = remember(assets) { assets.filterNot { it.isVideo || it.isTrashed } }
    val readyModel = modelState as? LocalModelState.Ready

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                modelManager.installFromUri(uri).onFailure {
                    message = it.message ?: "Unable to import the selected model."
                }
            }
        }
    }

    LaunchedEffect(embeddingState.laidOutCount, readyModel?.sha256) {
        points = readyModel?.let { repository.readPoints(it.sha256) }.orEmpty()
        if (selectedId != null && points.none { it.mediaId == selectedId }) {
            selectedId = null
            neighbours = emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close similarity explorer")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Organise by similarity", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Local model neighbourhoods, not a definitive semantic taxonomy",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Outlined.Psychology, contentDescription = null)
        }

        when (val model = modelState) {
            LocalModelState.NotInstalled -> ModelRequiredPanel(
                onInstall = {
                    scope.launch {
                        modelManager.installRecommendedModel().onFailure {
                            message = it.message ?: "Model installation failed."
                        }
                    }
                },
                onImport = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            )
            is LocalModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = {
                        val total = model.totalBytes ?: 0L
                        if (total <= 0L) 0f else (model.bytesRead.toFloat() / total).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Downloading local embedding model… ${formatBytes(model.bytesRead)}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is LocalModelState.Failed -> ModelRequiredPanel(
                error = model.message,
                onInstall = { scope.launch { modelManager.installRecommendedModel() } },
                onImport = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            )
            is LocalModelState.Ready -> {
                SimilarityToolbar(
                    indexed = embeddingState.indexedCount,
                    total = imageAssets.size,
                    indexingState = indexingState,
                    onIndex = {
                        if (indexJob?.isActive == true) return@SimilarityToolbar
                        indexJob = scope.launch {
                            indexer.index(imageAssets, model).onFailure {
                                message = it.message ?: "Local similarity indexing failed."
                            }
                        }
                    },
                    onCancel = { indexJob?.cancel() },
                    onResetView = {
                        selectedId = null
                        neighbours = emptyList()
                    },
                )

                if (points.isEmpty()) {
                    EmptySimilarityState(
                        indexed = embeddingState.indexedCount,
                        onIndex = {
                            if (indexJob?.isActive != true) {
                                indexJob = scope.launch { indexer.index(imageAssets, model) }
                            }
                        },
                    )
                } else {
                    SimilarityMap(
                        points = points,
                        assetById = assetById,
                        selectedId = selectedId,
                        onSelect = { id ->
                            selectedId = id
                            scope.launch {
                                neighbours = repository.nearest(model.sha256, id, 64)
                                    .mapNotNull { (mediaId, _) -> assetById[mediaId] }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        val selected = selectedId?.let(assetById::get)
        if (selected != null) {
            SimilaritySelectionStrip(
                selected = selected,
                neighbours = neighbours,
                onOpen = { asset ->
                    val visible = listOf(selected) + neighbours.filterNot { it.id == selected.id }
                    onOpenAsset(asset, visible)
                },
            )
        }

        message?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModelRequiredPanel(
    error: String? = null,
    onInstall: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(42.dp))
        Text("Install the local image model", style = MaterialTheme.typography.titleLarge)
        Text(
            "The recommended MediaPipe model is downloaded once into app-private storage. Photos are embedded on-device and are not uploaded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onInstall) { Text("Install recommended model") }
        OutlinedButton(onClick = onImport) { Text("Import compatible .tflite model") }
    }
}

@Composable
private fun SimilarityToolbar(
    indexed: Int,
    total: Int,
    indexingState: SimilarityIndexingState,
    onIndex: () -> Unit,
    onCancel: () -> Unit,
    onResetView: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onIndex, enabled = indexingState !is SimilarityIndexingState.Running) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text(if (indexed == 0) " Index library" else " Update index")
            }
            if (indexingState is SimilarityIndexingState.Running || indexingState is SimilarityIndexingState.LayingOut) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
            IconButton(onClick = onResetView) {
                Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Clear selection")
            }
            Text("$indexed / $total images", style = MaterialTheme.typography.bodySmall)
        }
        when (indexingState) {
            is SimilarityIndexingState.Running -> {
                LinearProgressIndicator(
                    progress = {
                        if (indexingState.total == 0) 0f
                        else indexingState.completed.toFloat() / indexingState.total
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${indexingState.completed}/${indexingState.total} · ${indexingState.failed} skipped · ${indexingState.currentName}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            is SimilarityIndexingState.LayingOut -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Arranging ${indexingState.indexedCount} embeddings…",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            is SimilarityIndexingState.Failed -> Text(
                indexingState.message,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
    }
}

@Composable
private fun EmptySimilarityState(indexed: Int, onIndex: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(progress = { if (indexed == 0) 0f else 1f })
        Text("No similarity layout yet", style = MaterialTheme.typography.titleMedium)
        Text("Index the permitted image library to create an offline visual-neighbourhood map.")
        Button(onClick = onIndex) { Text("Start local indexing") }
    }
}

@Composable
private fun SimilarityMap(
    points: List<SimilarityPoint>,
    assetById: Map<MediaId, MediaAsset>,
    selectedId: MediaId?,
    onSelect: (MediaId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val thumbnailSizePx = with(density) { 38.dp.toPx() }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(0.65f, 12f)
        panX += panChange.x
        panY += panChange.y
    }

    fun screenPosition(point: SimilarityPoint): PointF {
        val scale = minOf(width, height) * 0.43f * zoom
        return PointF(
            width / 2f + point.x * scale + panX,
            height / 2f + point.y * scale + panY,
        )
    }

    val visibleThumbnails = remember(points, width, height, zoom, panX, panY) {
        val visible = points.asSequence()
            .map { it to screenPosition(it) }
            .filter { (_, position) ->
                position.x in -thumbnailSizePx..(width + thumbnailSizePx) &&
                    position.y in -thumbnailSizePx..(height + thumbnailSizePx)
            }
            .toList()
        val stride = (visible.size / MAX_VISIBLE_THUMBNAILS).coerceAtLeast(1)
        visible.filterIndexed { index, pair ->
            pair.first.mediaId == selectedId || index % stride == 0
        }.take(MAX_VISIBLE_THUMBNAILS)
    }

    // Plain Box, not BoxWithConstraints: this composable never reads the constraints scope
    // (it measures itself via onSizeChanged below), so BoxWithConstraints was paying for a
    // subcomposition it did not use -- which lint flags as an error.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0B0E))
            .onSizeChanged { size -> width = size.width; height = size.height }
            .pointerInput(points, width, height, zoom, panX, panY) {
                detectTapGestures { tap ->
                    val nearest = points.asSequence()
                        .map { point ->
                            val position = screenPosition(point)
                            point to hypot(
                                (tap.x - position.x).toDouble(),
                                (tap.y - position.y).toDouble(),
                            )
                        }
                        .minByOrNull { it.second }
                    if (nearest != null && nearest.second <= MAX_TAP_DISTANCE_PX) {
                        onSelect(nearest.first.mediaId)
                    }
                }
            }
            .transformable(transform),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            points.forEach { point ->
                val position = screenPosition(point)
                val selected = point.mediaId == selectedId
                drawCircle(
                    color = clusterColor(point.cluster).copy(alpha = if (selected) 1f else 0.52f),
                    radius = if (selected) 8f else 2.2f,
                    center = Offset(position.x, position.y),
                )
            }
        }

        visibleThumbnails.forEach { (point, position) ->
            val asset = assetById[point.mediaId] ?: return@forEach
            val selected = point.mediaId == selectedId
            MediaImage(
                asset = asset,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (position.x - thumbnailSizePx / 2f).roundToInt(),
                            (position.y - thumbnailSizePx / 2f).roundToInt(),
                        )
                    }
                    .size(if (selected) 54.dp else 38.dp)
                    .graphicsLayer {
                        shadowElevation = if (selected) 20f else 4f
                        clip = true
                    }
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) Color.White else clusterColor(point.cluster),
                    )
                    .combinedClickable(
                        onClick = { onSelect(point.mediaId) },
                        onLongClick = { onSelect(point.mediaId) },
                    ),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun SimilaritySelectionStrip(
    selected: MediaAsset,
    neighbours: List<MediaAsset>,
    onOpen: (MediaAsset) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Visually near ${selected.displayName}",
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(104.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(listOf(selected) + neighbours.filterNot { it.id == selected.id }, key = { it.id.value }) { asset ->
                    MediaImage(
                        asset = asset,
                        modifier = Modifier
                            .size(88.dp)
                            .combinedClickable(
                                onClick = { onOpen(asset) },
                                onLongClick = { onOpen(asset) },
                            ),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

private fun clusterColor(cluster: Int): Color = CLUSTER_COLORS[cluster.mod(CLUSTER_COLORS.size)]

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val CLUSTER_COLORS = listOf(
    Color(0xFF65D1FF), Color(0xFFFF6B9A), Color(0xFFFFCC66), Color(0xFF7BE495),
    Color(0xFFB897FF), Color(0xFFFF8C69), Color(0xFF48D1CC), Color(0xFFF38BA8),
    Color(0xFF89B4FA), Color(0xFFA6E3A1), Color(0xFFF9E2AF), Color(0xFFCBA6F7),
    Color(0xFF74C7EC), Color(0xFFF5C2E7), Color(0xFF94E2D5), Color(0xFFFAB387),
)

private const val MAX_VISIBLE_THUMBNAILS = 80
private const val MAX_TAP_DISTANCE_PX = 64.0
