package com.fotoxplorr.app.viewer.sphere

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.metadata.xmpAttributeUtf8
import com.fotoxplorr.core.metadata.XmpPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** Short sentences for the sphere viewer, in its own object. */
object SphereCopy {
    const val OPEN = "View in 360"
    const val CLOSE = "Close 360 view"
    const val FAILED = "This photo could not be opened as a 360 view."
    const val NO_MEMORY = "There is not enough memory to open this 360 view."
    const val LEFT = "Turn left"
    const val RIGHT = "Turn right"
    const val UP = "Look up"
    const val DOWN = "Look down"
    const val ZOOM_IN = "Zoom in"
    const val ZOOM_OUT = "Zoom out"
    const val RESET = "Reset the view"
    const val GYRO = "Turn the phone to look around"
}

/** Reads the photo's GPano block, or null when it is not an equirectangular sphere. Blocking: call off the main thread. */
internal fun readSphereMeta(context: Context, asset: MediaAsset, imageWidth: Int, imageHeight: Int): SphereMeta? = runCatching {
    context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
        val xmp = ExifInterface(stream).xmpAttributeUtf8()?.let { XmpPacket.parse(it) } ?: return@use null
        SphereMeta.parse({ name -> xmp.stringValue(XmpPacket.GPANO_NS, name) }, imageWidth, imageHeight)
    }
}.getOrNull()


/**
 * The 360 viewer: look around a photo sphere by dragging, pinching, the buttons (every gesture has a
 * tap alternative), or turning the phone. Rendering is the CPU projection ([SphereProjection]) of a
 * decoded copy into a small frame stretched to the screen, redone when the view changes. Everything
 * that decides what is seen is pure and tested; frame rate, the gyro feel and memory on a 4096 px
 * sphere are device checks. No sound: the product has no audio features.
 */
@Composable
internal fun SphereScreen(asset: MediaAsset, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    var loaded by remember(asset.id) { mutableStateOf<LoadedSphere?>(null) }
    var failure by remember(asset.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(asset.id) {
        // Every failure is the one-line message, never a crash: the decode seam can throw before its
        // own guard (TRAPS #49), and the pixel copy can run out of memory.
        val outcome: Any? = SphereLoading.guarded(onFailure = { SphereLoading.messageFor(it) }) {
            withContext(Dispatchers.Default) { loadSphere(context, asset) }
        }
        when (outcome) {
            is LoadedSphere -> loaded = outcome
            is String -> failure = outcome
            else -> failure = SphereCopy.FAILED
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black).testTag(SPHERE_TAG)
            // Over everything, and a pointer target in its own right, so a touch while loading or
            // failed is hit here and never falls through to the viewer drawn beneath: hit testing
            // stops at this topmost sibling. It consumes NOTHING: the Final pass runs ancestor
            // first, so consuming here would cancel the Close and pad clicks on any finger jitter.
            .pointerInput(Unit) { sphereTouchTarget() },
    ) {
        val data = loaded
        if (data == null) {
            Text(
                text = failure.orEmpty(),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            SphereStage(data.pixels, data.w, data.h, data.meta)
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).size(48.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .semantics { contentDescription = SphereCopy.CLOSE },
        ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = Color.White) }
    }
}

/**
 * Makes the sphere's root a pointer target without consuming anything: it only follows each gesture
 * to its last finger up. Hit testing already keeps the viewer beneath (a lower sibling) from seeing
 * the touch; the controls inside see every change unconsumed, as a clickable needs.
 */
internal suspend fun PointerInputScope.sphereTouchTarget() {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
        } while (event.changes.any { it.pressed })
    }
}

/** The decoded sphere: its pixels (the decoded Bitmap is already recycled) and its GPano meta. */
private class LoadedSphere(val pixels: IntArray, val w: Int, val h: Int, val meta: SphereMeta)

/**
 * Decodes the sphere at the edge the free heap allows, reads its GPano block against the ORIGINAL
 * size (FullPano* is in original pixels), copies the pixels out and recycles the Bitmap at once.
 * A String is the one-line message for a known failure. Blocking: call off the main thread.
 */
private suspend fun loadSphere(context: Context, asset: MediaAsset): Any {
    val edge = SphereLoading.decodeEdge(SphereLoading.availableHeapBytes()) ?: return SphereCopy.NO_MEMORY
    val d = decodeUpright(context, asset.contentUri, DecodeLimits(edge, edge.toLong() * (edge / 2)))
        ?: return SphereCopy.FAILED
    val bmp = d.bitmap
    try {
        val (metaW, metaH) = SphereLoading.metaSize(d.sourceWidth, d.sourceHeight, bmp.width, bmp.height)
        val meta = withContext(Dispatchers.IO) { readSphereMeta(context, asset, metaW, metaH) }
            ?: return SphereCopy.FAILED
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        return LoadedSphere(px, bmp.width, bmp.height, meta)
    } finally {
        // A private software copy: the IntArray is all the projection reads.
        bmp.recycle()
    }
}

@Composable
private fun SphereStage(pixels: IntArray, srcW: Int, srcH: Int, meta: SphereMeta) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val aspect = if (size.height > 0) size.width.toFloat() / size.height else 0.5f
    var look by remember(meta) { mutableStateOf(SphereLook.initial(meta, aspect)) }
    // The first look was clamped before layout (aspect unknown); clamp again when the real size or a
    // rotation arrives, so a partial panorama never opens showing past its edge.
    LaunchedEffect(meta, aspect) { look = SphereLook.clamp(look, meta, aspect) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val buffers = remember { SphereFrameBuffers() }
    var gyroOn by remember { mutableStateOf(false) }
    val liveAspect by rememberUpdatedState(aspect)
    val gyroAvailable = remember(context) { gameRotationSensor(context) != null }

    // Re-render when the view or the size changes; collectLatest drops a frame the finger has already left behind.
    LaunchedEffect(meta, size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        snapshotFlow { look }.collectLatest { l ->
            val fw = SphereLoading.FRAME_W
            val fh = (fw * size.height.toFloat() / size.width).toInt().coerceAtLeast(1)
            val out = withContext(Dispatchers.Default) { SphereProjection.render(pixels, srcW, srcH, meta, l, fw, fh) }
            frame = buffers.fill(out, fw, fh).asImageBitmap()
        }
    }

    if (gyroOn) GyroEffect(onTurn = { dYaw, dPitch, base ->
        look = SphereLook.clamp(base.copy(yaw = base.yaw + dYaw, pitch = dPitch), meta, liveAspect)
    }, current = { look })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(meta) {
                detectTransformGestures { _, pan, zoom, _ ->
                    var l = SphereLook.drag(look, pan.x, pan.y, size.height.toFloat(), meta, liveAspect)
                    if (zoom != 1f) l = SphereLook.zoom(l, zoom, meta, liveAspect)
                    look = l
                }
            },
    ) {
        frame?.let { img ->
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "360 photo" }) {
                drawImage(
                    image = img,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        // The tap alternative to every drag and pinch.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(Icons.Outlined.KeyboardArrowLeft, SphereCopy.LEFT, SPHERE_LEFT_TAG) { look = SphereLook.step(look, -1, 0, meta, liveAspect) }
                PadButton(Icons.Outlined.KeyboardArrowUp, SphereCopy.UP, SPHERE_UP_TAG) { look = SphereLook.step(look, 0, 1, meta, liveAspect) }
                PadButton(Icons.Outlined.KeyboardArrowDown, SphereCopy.DOWN, SPHERE_DOWN_TAG) { look = SphereLook.step(look, 0, -1, meta, liveAspect) }
                PadButton(Icons.Outlined.KeyboardArrowRight, SphereCopy.RIGHT, SPHERE_RIGHT_TAG) { look = SphereLook.step(look, 1, 0, meta, liveAspect) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(Icons.Outlined.ZoomOut, SphereCopy.ZOOM_OUT, SPHERE_ZOOM_OUT_TAG) { look = SphereLook.zoom(look, 1f / SphereLook.STEP_ZOOM, meta, liveAspect) }
                PadButton(Icons.Outlined.ZoomIn, SphereCopy.ZOOM_IN, SPHERE_ZOOM_IN_TAG) { look = SphereLook.zoom(look, SphereLook.STEP_ZOOM, meta, liveAspect) }
                PadButton(Icons.Outlined.Refresh, SphereCopy.RESET, SPHERE_RESET_TAG) { look = SphereLook.initial(meta, liveAspect) }
                if (gyroAvailable) PadButton(Icons.Outlined.ScreenRotation, SphereCopy.GYRO, SPHERE_GYRO_TAG) { gyroOn = !gyroOn }
            }
        }
    }
}

@Composable
private fun PadButton(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).testTag(tag)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .semantics { contentDescription = description },
    ) { Icon(icon, contentDescription = null, tint = Color.White) }
}

private fun gameRotationSensor(context: Context): Sensor? =
    (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

/**
 * Listens to the game rotation vector while composed AND the host is started ([StartedGate]): Home
 * or the lock key unregisters it on ON_STOP. Turns are relative to the view when listening (re)started.
 */
@Composable
private fun GyroEffect(onTurn: (Float, Float, Look) -> Unit, current: () -> Look) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val turn by rememberUpdatedState(onTurn)
    val now by rememberUpdatedState(current)
    DisposableEffect(context, lifecycle) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val gyro = GyroLook()
        var base = now()
        val matrix = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, e.values)
                val (yaw, pitch) = gyro.update(matrix)
                turn(yaw, pitch, base)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val gate = StartedGate(
            start = {
                gyro.reset()
                base = now()
                if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            },
            stop = { sm?.unregisterListener(listener) },
        )
        lifecycle.addObserver(gate)
        onDispose {
            lifecycle.removeObserver(gate)
            gate.close()
        }
    }
}

internal const val SPHERE_TAG = "viewer-sphere"
internal const val SPHERE_LEFT_TAG = "viewer-sphere-left"
internal const val SPHERE_RIGHT_TAG = "viewer-sphere-right"
internal const val SPHERE_UP_TAG = "viewer-sphere-up"
internal const val SPHERE_DOWN_TAG = "viewer-sphere-down"
internal const val SPHERE_ZOOM_IN_TAG = "viewer-sphere-zoom-in"
internal const val SPHERE_ZOOM_OUT_TAG = "viewer-sphere-zoom-out"
internal const val SPHERE_RESET_TAG = "viewer-sphere-reset"
internal const val SPHERE_GYRO_TAG = "viewer-sphere-gyro"
