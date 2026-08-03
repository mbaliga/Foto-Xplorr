package com.fotoxplorr.app.experience

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fotoxplorr.app.media.MediaAsset

@Composable
fun PhotoWallScreen(
    assets: List<MediaAsset>,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var gyroEnabled by remember { mutableStateOf(true) }
    val visibleAssets = remember(assets) {
        assets.filterNot { it.isTrashed }.take(MAX_SCENE_ASSETS)
    }
    val cards = remember(visibleAssets) { PhotoWallLayout.build(visibleAssets) }
    val surface = remember(cards) {
        PhotoWallSurfaceView(
            context = context,
            cards = cards,
            onAssetSelected = { selected -> onOpenAsset(selected, visibleAssets) },
        )
    }

    DisposableEffect(lifecycleOwner, surface) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                surface.resumeScene()
            }

            override fun onPause(owner: LifecycleOwner) {
                surface.pauseScene()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        surface.resumeScene()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surface.releaseScene()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.88f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close 3D photo wall",
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("3D photo wall", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Drag to turn and travel · pinch to move through depth · tap a photo",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Icon(Icons.Outlined.Explore, contentDescription = null, tint = Color.White)
            Switch(
                checked = gyroEnabled,
                onCheckedChange = { enabled ->
                    gyroEnabled = enabled
                    surface.setGyroEnabled(enabled)
                },
            )
            IconButton(onClick = surface::resetCamera) {
                Icon(
                    Icons.Outlined.RestartAlt,
                    contentDescription = "Reset 3D camera",
                    tint = Color.White,
                )
            }
        }
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
    }
}

private class PhotoWallSurfaceView(
    context: Context,
    cards: List<PhotoSceneCard>,
    onAssetSelected: (MediaAsset) -> Unit,
) : GLSurfaceView(context) {
    private val renderer = PhotoWallRenderer(
        context = context.applicationContext,
        cards = cards,
        requestFrame = ::requestRender,
        onAssetSelected = onAssetSelected,
    )
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.moveDepth((1f - detector.scaleFactor) * DEPTH_PINCH_SPEED)
                return true
            }
        },
    )
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                renderer.pick(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                renderer.focusNearest(e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!scaleDetector.isInProgress) renderer.drag(distanceX, distanceY)
                return true
            }
        },
    )
    private val orientation = SceneOrientationController(
        context = context,
        onOrientation = { yaw: Float, pitch: Float ->
            renderer.setSensorOrientation(yaw, pitch)
        },
    )

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setGyroEnabled(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        if (scaleHandled || gestureHandled) return true
        return super.onTouchEvent(event)
    }

    fun setGyroEnabled(enabled: Boolean) {
        orientation.setEnabled(enabled)
        renderer.setGyroEnabled(enabled)
    }

    fun resetCamera() = renderer.resetCamera()

    fun resumeScene() {
        onResume()
        orientation.start()
    }

    fun pauseScene() {
        orientation.stop()
        onPause()
    }

    fun releaseScene() {
        orientation.stop()
        queueEvent(renderer::release)
    }

    private companion object {
        const val DEPTH_PINCH_SPEED = 8.5f
    }
}

private const val MAX_SCENE_ASSETS = 20_000
