@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun ViewerScreen(
    asset: MediaAsset,
    position: Int,
    total: Int,
    isFavorite: Boolean,
    isSensitive: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    canMoveToTrash: Boolean,
    slideshowActive: Boolean,
    slideshowIntervalSeconds: Int,
    onToggleSlideshow: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSensitive: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onOpenWith: () -> Unit,
    onMoveToTrash: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    var controlsVisible by remember(asset.id) { mutableStateOf(true) }
    var metadataVisible by remember(asset.id) { mutableStateOf(false) }
    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(asset.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(asset.id) { mutableFloatStateOf(0f) }
    var dragDistance by remember(asset.id) { mutableFloatStateOf(0f) }

    LaunchedEffect(asset.id, slideshowActive, slideshowIntervalSeconds, metadataVisible) {
        if (slideshowActive && total > 1 && !metadataVisible) {
            delay(slideshowIntervalSeconds.coerceAtLeast(2) * 1_000L)
            onNext()
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (!asset.isVideo) {
            val nextScale = (scale * zoomChange).coerceIn(1f, 6f)
            if (nextScale == 1f) {
                offsetX = 0f
                offsetY = 0f
            } else {
                offsetX += panChange.x
                offsetY += panChange.y
            }
            scale = nextScale
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(asset.id, scale) {
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { change, amount ->
                        if (scale == 1f) {
                            change.consume()
                            dragDistance += amount
                        }
                    },
                    onDragEnd = {
                        when {
                            dragDistance <= -SWIPE_THRESHOLD_PX && hasNext -> onNext()
                            dragDistance >= SWIPE_THRESHOLD_PX && hasPrevious -> onPrevious()
                        }
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            }
            .pointerInput(asset.id) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = {
                        if (!asset.isVideo) {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    },
                )
            }
            .then(if (asset.isVideo) Modifier else Modifier.transformable(transformState)),
        contentAlignment = Alignment.Center,
    ) {
        if (asset.isVideo) {
            VideoPlayer(asset = asset, modifier = Modifier.fillMaxSize())
        } else {
            MediaImage(
                asset = asset,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX + if (scale == 1f) dragDistance else 0f
                        translationY = offsetY
                    },
                contentScale = ContentScale.Fit,
            )
        }

        if (controlsVisible) {
            ViewerControls(
                asset = asset,
                position = position,
                total = total,
                isFavorite = isFavorite,
                isSensitive = isSensitive,
                metadataVisible = metadataVisible,
                canMoveToTrash = canMoveToTrash,
                slideshowActive = slideshowActive,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onToggleSlideshow = onToggleSlideshow,
                onToggleFavorite = onToggleFavorite,
                onToggleSensitive = onToggleSensitive,
                onShare = onShare,
                onEdit = onEdit,
                onOpenWith = onOpenWith,
                onMoveToTrash = onMoveToTrash,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleMetadata = { metadataVisible = !metadataVisible },
                onClose = onClose,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        if (metadataVisible) {
            MetadataPanel(asset = asset, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ViewerControls(
    asset: MediaAsset,
    position: Int,
    total: Int,
    isFavorite: Boolean,
    isSensitive: Boolean,
    metadataVisible: Boolean,
    canMoveToTrash: Boolean,
    slideshowActive: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onToggleSlideshow: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSensitive: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onOpenWith: () -> Unit,
    onMoveToTrash: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleMetadata: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerAction("Close", onClose)
            Text(asset.displayName, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
            Text("$position / $total", color = Color.White)
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            maxItemsInEachRow = 3,
        ) {
            ViewerAction("Previous", onPrevious, hasPrevious)
            ViewerAction(if (slideshowActive) "Pause slideshow" else "Slideshow", onToggleSlideshow)
            ViewerAction("Next", onNext, hasNext || slideshowActive)
            ViewerAction(if (metadataVisible) "Hide info" else "Info", onToggleMetadata)
            ViewerAction("Share", onShare)
            ViewerAction("Edit", onEdit)
            ViewerAction("Open with", onOpenWith)
            ViewerAction(if (isSensitive) "Sensitive ✓" else "Sensitive", onToggleSensitive)
            ViewerAction(if (isFavorite) "★ Favourite" else "☆ Favourite", onToggleFavorite)
            ViewerAction(
                label = if (canMoveToTrash) "Move to trash" else "Trash unavailable",
                onClick = onMoveToTrash,
                enabled = canMoveToTrash,
            )
        }
    }
}

@Composable
private fun ViewerAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(enabled = enabled, onClick = onClick) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun MetadataPanel(asset: MediaAsset, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MetadataRow("Name", asset.displayName)
        MetadataRow("Type", asset.mimeType.ifBlank { "Unknown" })
        MetadataRow("Dimensions", "${asset.width} × ${asset.height}")
        MetadataRow("Size", formatBytes(asset.sizeBytes))
        if (asset.isVideo) MetadataRow("Duration", formatDuration(asset.durationMillis))
        asset.bucketName?.let { MetadataRow("Album", it) }
        asset.relativePath?.let { MetadataRow("Path", it) }
        MetadataRow("Taken", formatDate(asset.dateTakenMillis))
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.68f), modifier = Modifier.weight(0.3f))
        Text(value, color = Color.White, modifier = Modifier.weight(0.7f))
    }
}

private fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Unknown"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "Unknown"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) "${value.roundToInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "Unknown"
    val totalSeconds = durationMillis / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private const val SWIPE_THRESHOLD_PX = 180f
