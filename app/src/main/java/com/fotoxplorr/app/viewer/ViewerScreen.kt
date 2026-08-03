package com.fotoxplorr.app.viewer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(
    asset: MediaAsset,
    position: Int,
    total: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(initialValue = null, asset.contentUri) {
        value = loadViewerBitmap(resolver, asset)
    }

    var controlsVisible by remember(asset.id) { mutableStateOf(false) }
    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(asset.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(asset.id) { mutableFloatStateOf(0f) }
    var dragDistance by remember(asset.id) { mutableFloatStateOf(0f) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
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
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                    onLongPress = { onClose() },
                )
            }
            .transformable(transformState),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                text = asset.displayName,
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = asset.displayName,
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
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = asset.displayName,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = "$position / $total",
                    color = Color.White,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            Text(
                text = "Long press to close",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private suspend fun loadViewerBitmap(
    resolver: ContentResolver,
    asset: MediaAsset,
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(asset.contentUri, Size(2048, 2048), null)
        } else {
            resolver.openInputStream(asset.contentUri)?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}

private const val SWIPE_THRESHOLD_PX = 180f
