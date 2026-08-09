@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.fotoxplorr.app.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import kotlinx.coroutines.delay

/**
 * The full-screen viewer, as a spatial shell with one room above it.
 *
 * Pulling down from the top reveals [PhotoDetailRoom] — what this photo is and where it was
 * taken — with the photo itself still alive on the parked card behind it. That replaces two
 * separate surfaces that used to say overlapping things about the same file: a bottom
 * `MetadataPanel` behind an "Info" button, and a full-screen Material details screen behind a
 * "Details" button. Two buttons, two layouts and two half-answers about one photo is exactly
 * the drift `docs/fonebrew-navigation.md` describes; the room is the single answer.
 *
 * The top edge is the only edge with a room here, so the shell refuses drags from the other
 * three and draws no peek on them.
 */
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
    /**
     * The assets being paged through. Feeds both the top room's related-photos
     * grid and the bottom filmstrip scrubber; empty is a safe fallback (the strip hides
     * itself below two items).
     */
    relatedAssets: List<MediaAsset> = emptyList(),
    onOpenRelated: (MediaAsset) -> Unit = {},
    /** Jump straight to an asset in [relatedAssets], from the filmstrip. */
    onSelectAsset: (MediaAsset) -> Unit = onOpenRelated,
) {
    var controlsVisible by remember(asset.id) { mutableStateOf(true) }
    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(asset.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(asset.id) { mutableFloatStateOf(0f) }
    var dragDistance by remember(asset.id) { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val shell = rememberSpatialController()

    // Read here rather than inside the room: the shell only composes a room once it is slightly
    // open, so reading it there would start the EXIF load on the first pixel of the pull and
    // leave the card empty for the rest of the gesture.
    val context = LocalContext.current
    var exif by remember(asset.id) { mutableStateOf(ImageExifDetails()) }
    LaunchedEffect(asset.id) {
        exif = readImageExifDetails(context, asset)
    }

    // A room is not a back-stack entry, but Back is the gesture people reach for to leave one.
    // Disabled at home so the activity's own handler still closes the viewer.
    BackHandler(enabled = !shell.atHome) { shell.closeAll() }

    LaunchedEffect(asset.id, slideshowActive, slideshowIntervalSeconds, shell.anyRoomVisible) {
        if (slideshowActive && total > 1 && !shell.anyRoomVisible) {
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

    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        // The room sits on the same black the photo does, so opening it reads as the surface
        // moving rather than as a different screen appearing behind it.
        scrimColor = Color.Black,
        cardColor = Color.Black,
        modifier = Modifier.fillMaxSize(),
        top = {
            PhotoDetailRoom(
                asset = asset,
                exif = exif,
                relatedAssets = relatedAssets.filter { it.id != asset.id }.take(30),
                onOpenRelated = { related ->
                    shell.closeAll()
                    onOpenRelated(related)
                },
                reveal = { shell.vProgress },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
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
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // The filmstrip from the viewer mockup. It used to share the bottom edge with a
            // metadata panel and hide whenever that was up; the panel is the top room now, so
            // the strip simply owns the bottom edge.
            if (controlsVisible && relatedAssets.size > 1) {
                FilmstripScrubber(
                    assets = relatedAssets,
                    currentIndex = position - 1,
                    onSelect = onSelectAsset,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Zoomed-image minimap: only meaningful once the user has actually zoomed in.
            if (!asset.isVideo && scale > 1.05f) {
                ZoomMinimap(
                    asset = asset,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    containerSize = containerSize,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                )
            }
        }
    }
}

/**
 * A small semi-transparent rectangle showing where the current pinch-zoomed viewport sits
 * within the full image. The math assumes [MediaImage] is laid out to fill the container
 * (ContentScale.Fit, centred) and that the pinch-zoom graphicsLayer scales/translates about
 * that same centre -- true for how this screen drives `scale`/`offsetX`/`offsetY` above, but
 * this has only been checked against that code, not against a running app (no device/emulator
 * available in this environment).
 */
@Composable
private fun ZoomMinimap(
    asset: MediaAsset,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    containerSize: IntSize,
    modifier: Modifier = Modifier,
) {
    val containerW = containerSize.width.toFloat()
    val containerH = containerSize.height.toFloat()
    if (containerW <= 0f || containerH <= 0f) return

    val imageAspect = if (asset.width > 0 && asset.height > 0) {
        asset.width.toFloat() / asset.height.toFloat()
    } else {
        1f
    }
    val containerAspect = containerW / containerH
    val fittedWidth = if (imageAspect > containerAspect) containerW else containerH * imageAspect
    val fittedHeight = if (imageAspect > containerAspect) containerW / imageAspect else containerH
    val centerX = containerW / 2f
    val centerY = containerH / 2f

    val leftFraction = (0.5f + (0f - centerX - offsetX) / scale / fittedWidth).coerceIn(0f, 1f)
    val rightFraction = (0.5f + (containerW - centerX - offsetX) / scale / fittedWidth).coerceIn(0f, 1f)
    val topFraction = (0.5f + (0f - centerY - offsetY) / scale / fittedHeight).coerceIn(0f, 1f)
    val bottomFraction = (0.5f + (containerH - centerY - offsetY) / scale / fittedHeight).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .size(width = 72.dp, height = (72.dp / imageAspect.coerceIn(0.4f, 2.5f)))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.55f)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val strokeWidthPx = 1.5.dp.toPx()
                    val left = (leftFraction * size.width).coerceAtMost(size.width - strokeWidthPx)
                    val top = (topFraction * size.height).coerceAtMost(size.height - strokeWidthPx)
                    val right = (rightFraction * size.width).coerceAtLeast(left + strokeWidthPx)
                    val bottom = (bottomFraction * size.height).coerceAtLeast(top + strokeWidthPx)
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = strokeWidthPx),
                    )
                },
        )
    }
}

@Composable
private fun ViewerControls(
    asset: MediaAsset,
    position: Int,
    total: Int,
    isFavorite: Boolean,
    isSensitive: Boolean,
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

        // "Info" and "Details" are gone: both opened a surface describing this file, and that
        // surface is the top room now. A button that duplicates a gesture teaches people to
        // ignore the gesture.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            maxItemsInEachRow = 3,
        ) {
            ViewerAction("Previous", onPrevious, hasPrevious)
            ViewerAction(if (slideshowActive) "Pause slideshow" else "Slideshow", onToggleSlideshow)
            ViewerAction("Next", onNext, hasNext || slideshowActive)
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

private const val SWIPE_THRESHOLD_PX = 180f
