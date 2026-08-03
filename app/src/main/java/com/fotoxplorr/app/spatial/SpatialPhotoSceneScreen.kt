package com.fotoxplorr.app.spatial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fotoxplorr.app.experience.PhotoSceneCard
import com.fotoxplorr.app.experience.SceneOrientationController
import com.fotoxplorr.app.experience.SceneOrientationMode
import com.fotoxplorr.app.media.MediaAsset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private enum class SpatialDistanceFilter(val label: String, val maximumMeters: Double?) {
    NEARBY("1 km", 1_000.0),
    CITY("25 km", 25_000.0),
    REGION("250 km", 250_000.0),
    ALL("All", null),
}

private enum class SpatialTimeFilter(val label: String, val windowMillis: Long?) {
    RECENT("30 days", 30L * 24L * 60L * 60L * 1_000L),
    YEAR("1 year", 365L * 24L * 60L * 60L * 1_000L),
    ALL("All time", null),
}

@Composable
fun SpatialPhotoSceneScreen(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var location by remember { mutableStateOf<Location?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var sensorAccuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }
    var distanceFilter by remember { mutableStateOf(SpatialDistanceFilter.CITY) }
    var timeFilter by remember { mutableStateOf(SpatialTimeFilter.ALL) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            resolveCurrentLocation(context) { resolved, message ->
                location = resolved
                locationMessage = message
            }
        } else {
            locationMessage = "Location permission was not granted. This mode cannot position photos relative to you."
        }
    }

    fun requestLocation() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            resolveCurrentLocation(context) { resolved, message ->
                location = resolved
                locationMessage = message
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    val now = System.currentTimeMillis()
    val temporallyFiltered = remember(assets, timeFilter) {
        assets.filter { asset ->
            !asset.isTrashed && (timeFilter.windowMillis?.let { asset.dateTakenMillis >= now - it } ?: true)
        }
    }
    val allPlacements = remember(temporallyFiltered, geoState.metadataById, location) {
        location?.let { SpatialPhotoLayout.build(temporallyFiltered, geoState.metadataById, it) }.orEmpty()
    }
    val placements = remember(allPlacements, distanceFilter) {
        allPlacements.filter { placement ->
            distanceFilter.maximumMeters?.let { placement.distanceMeters <= it } ?: true
        }
    }
    val visibleAssets = remember(placements) { placements.map { it.card.asset } }
    val surface = remember(placements) {
        placements.take(MAX_SPATIAL_CARDS).takeIf { it.isNotEmpty() }?.let { selectedPlacements ->
            SpatialSceneSurfaceView(
                context = context,
                cards = selectedPlacements.map { it.card },
                onAssetSelected = { selected -> onOpenAsset(selected, visibleAssets) },
                onAccuracyChanged = { accuracy -> sensorAccuracy = accuracy },
            )
        }
    }

    DisposableEffect(lifecycleOwner, surface) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = surface?.resumeScene() ?: Unit
            override fun onPause(owner: LifecycleOwner) = surface?.pauseScene() ?: Unit
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        surface?.resumeScene()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surface?.releaseScene()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(ComposeColor.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close spatial compass", tint = ComposeColor.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Spatial compass", color = ComposeColor.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (location == null) "Current location is used only while this mode is open"
                    else "${placements.size} photos positioned around you",
                    color = ComposeColor.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = ::requestLocation) {
                Icon(Icons.Outlined.GpsFixed, contentDescription = "Refresh current location", tint = ComposeColor.White)
            }
            IconButton(onClick = { surface?.recalibrate() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Recalibrate orientation", tint = ComposeColor.White)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpatialDistanceFilter.entries.forEach { filter ->
                FilterChip(
                    selected = distanceFilter == filter,
                    onClick = { distanceFilter = filter },
                    label = { Text(filter.label) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpatialTimeFilter.entries.forEach { filter ->
                FilterChip(
                    selected = timeFilter == filter,
                    onClick = { timeFilter = filter },
                    label = { Text(filter.label) },
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                geoState.scannedCount == 0 -> SpatialOptInPanel(
                    title = "Index embedded coordinates first",
                    message = "Foto Xplorr reads GPS metadata locally. It does not upload it.",
                    action = "Index locations",
                    onAction = onIndexLocations,
                )
                location == null -> SpatialOptInPanel(
                    title = "See photos relative to where you stand",
                    message = "Location permission is requested only for this mode. The camera is not used and no location leaves the device.",
                    action = "Use current location",
                    onAction = ::requestLocation,
                )
                placements.isEmpty() -> SpatialOptInPanel(
                    title = "No photos in this distance and time range",
                    message = "Choose a wider range or update embedded location metadata.",
                    action = "Show all distances",
                    onAction = { distanceFilter = SpatialDistanceFilter.ALL },
                )
                surface != null -> {
                    AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
                    SpatialHud(
                        sensorAccuracy = sensorAccuracy,
                        locationAccuracyMeters = location?.accuracy,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Text(
            locationMessage ?: sensorStatusMessage(sensorAccuracy),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) ComposeColor(0xFFFFB4AB)
            else ComposeColor.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SpatialOptInPanel(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.Explore, contentDescription = null, tint = ComposeColor.White)
        Text(title, color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
        Text(message, color = ComposeColor.White.copy(alpha = 0.72f))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SpatialHud(
    sensorAccuracy: Int,
    locationAccuracyMeters: Float?,
    modifier: Modifier = Modifier,
) {
    val ring = ComposeColor.White.copy(alpha = 0.18f)
    Canvas(modifier) {
        val centre = center
        val maxRadius = min(size.width, size.height) * 0.43f
        listOf(0.33f, 0.66f, 1f).forEach { fraction ->
            drawCircle(ring, radius = maxRadius * fraction, center = centre, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        }
        drawLine(ComposeColor(0xFFE65A58), centre, Offset(centre.x, centre.y - maxRadius), strokeWidth = 4f)
        drawCircle(
            color = if (sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) ComposeColor(0xFFFFB4AB) else ComposeColor.White,
            radius = 5f,
            center = centre,
        )
        locationAccuracyMeters?.let { accuracy ->
            val normalized = (accuracy / 100f).coerceIn(0.05f, 1f)
            drawCircle(ComposeColor.White.copy(alpha = 0.08f), maxRadius * normalized, centre)
        }
    }
}

private class SpatialSceneSurfaceView(
    context: Context,
    cards: List<PhotoSceneCard>,
    onAssetSelected: (MediaAsset) -> Unit,
    onAccuracyChanged: (Int) -> Unit,
) : GLSurfaceView(context) {
    private val renderer = SpatialSceneRenderer(
        context.applicationContext,
        cards,
        ::requestRender,
        onAssetSelected,
    )
    private val orientation = SceneOrientationController(
        context = context,
        mode = SceneOrientationMode.ABSOLUTE_NORTH,
        onOrientation = renderer::setOrientation,
        onAccuracy = onAccuracyChanged,
    )
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            renderer.pick(e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            renderer.adjustTouch(distanceX, distanceY)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    fun resumeScene() {
        onResume()
        orientation.start()
    }

    fun pauseScene() {
        orientation.stop()
        onPause()
    }

    fun recalibrate() {
        renderer.resetTouch()
        orientation.calibrate()
    }

    fun releaseScene() {
        orientation.stop()
        queueEvent(renderer::release)
    }
}

private class SpatialSceneRenderer(
    private val context: Context,
    private val cards: List<PhotoSceneCard>,
    private val requestFrame: () -> Unit,
    private val onAssetSelected: (MediaAsset) -> Unit,
) : GLSurfaceView.Renderer {
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val centre = floatArrayOf(0f, 0f, 0f, 1f)
    private val clip = FloatArray(4)
    private val textures = object : LinkedHashMap<Long, Int>(MAX_TEXTURES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>): Boolean {
            if (size <= MAX_TEXTURES) return false
            GLES20.glDeleteTextures(1, intArrayOf(eldest.value), 0)
            return true
        }
    }
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val decoded = ConcurrentLinkedQueue<Decoded>()
    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val pickTargets = mutableListOf<PickTarget>()

    private var program = 0
    private var placeholder = 0
    private var width = 1
    private var height = 1
    @Volatile private var heading = 0f
    @Volatile private var pitch = 0f
    @Volatile private var touchYaw = 0f
    @Volatile private var touchPitch = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.006f, 0.008f, 0.014f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        placeholder = createPlaceholder()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(1, width)
        this.height = max(1, height)
        GLES20.glViewport(0, 0, this.width, this.height)
        Matrix.perspectiveM(projection, 0, 62f, this.width.toFloat() / this.height, 0.2f, 90f)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadDecoded()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val yaw = heading + touchYaw
        val finalPitch = (pitch + touchPitch).coerceIn(-72f, 72f)
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(finalPitch.toDouble())
        val directionX = kotlin.math.sin(yawRadians).toFloat() * kotlin.math.cos(pitchRadians).toFloat()
        val directionY = kotlin.math.sin(pitchRadians).toFloat()
        val directionZ = -kotlin.math.cos(yawRadians).toFloat() * kotlin.math.cos(pitchRadians).toFloat()
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, directionX, directionY, directionZ, 0f, 1f, 0f)

        pickTargets.clear()
        cards.forEach { card ->
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, card.position.x, card.position.y, card.position.z)
            Matrix.rotateM(model, 0, card.yawDegrees, 0f, 1f, 0f)
            Matrix.scaleM(model, 0, card.width, card.height, 1f)
            Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
            Matrix.multiplyMV(clip, 0, mvp, 0, centre, 0)
            if (clip[3] <= 0f) return@forEach
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            if (abs(ndcX) > 1.4f || abs(ndcY) > 1.4f) return@forEach
            val screenX = (ndcX * 0.5f + 0.5f) * width
            val screenY = (0.5f - ndcY * 0.5f) * height
            val radius = (min(card.width, card.height) / clip[3] * height * 0.4f).coerceIn(28f, 120f)
            pickTargets += PickTarget(card.asset, screenX, screenY, radius)
            val texture = textures[card.asset.id.value] ?: placeholder.also { requestTexture(card.asset) }
            draw(texture)
        }
    }

    fun setOrientation(yaw: Float, pitch: Float) {
        heading = yaw
        this.pitch = pitch
    }

    fun adjustTouch(distanceX: Float, distanceY: Float) {
        touchYaw += distanceX * 0.07f
        touchPitch = (touchPitch + distanceY * 0.06f).coerceIn(-35f, 35f)
    }

    fun resetTouch() {
        touchYaw = 0f
        touchPitch = 0f
    }

    fun pick(x: Float, y: Float) {
        val target = pickTargets.minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) } ?: return
        if (hypot((target.x - x).toDouble(), (target.y - y).toDouble()) <= target.radius) {
            handler.post { onAssetSelected(target.asset) }
        }
    }

    fun release() {
        textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        textures.clear()
        if (placeholder != 0) GLES20.glDeleteTextures(1, intArrayOf(placeholder), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        executor.shutdownNow()
        decoded.forEach { it.bitmap.recycle() }
        decoded.clear()
    }

    private fun draw(texture: Int) {
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrix = GLES20.glGetUniformLocation(program, "uMvp")
        val sampler = GLES20.glGetUniformLocation(program, "uTexture")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 5 * Float.SIZE_BYTES, vertices)
        vertices.position(3)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 5 * Float.SIZE_BYTES, vertices)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(uv)
    }

    private fun requestTexture(asset: MediaAsset) {
        val id = asset.id.value
        if (!pending.add(id)) return
        executor.execute {
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(asset.contentUri, Size(384, 384), null)
                } else {
                    context.contentResolver.openInputStream(asset.contentUri)?.use(android.graphics.BitmapFactory::decodeStream)
                        ?: error("Unable to decode media")
                }
            }.getOrNull()
            if (bitmap == null) {
                pending.remove(id)
            } else {
                decoded.add(Decoded(id, bitmap))
                requestFrame()
            }
        }
    }

    private fun uploadDecoded() {
        while (true) {
            val item = decoded.poll() ?: break
            try {
                val texture = IntArray(1)
                GLES20.glGenTextures(1, texture, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, item.bitmap, 0)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                textures.put(item.id, texture[0])?.let { previous ->
                    if (previous != texture[0]) GLES20.glDeleteTextures(1, intArrayOf(previous), 0)
                }
            } finally {
                item.bitmap.recycle()
                pending.remove(item.id)
            }
        }
    }

    private fun createPlaceholder(): Int {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 34, 44)) }
        return try {
            IntArray(1).also { texture ->
                GLES20.glGenTextures(1, texture, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            }[0]
        } finally {
            bitmap.recycle()
        }
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertexShader)
            GLES20.glAttachShader(result, fragmentShader)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(result) }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
    }

    private data class Decoded(val id: Long, val bitmap: Bitmap)
    private data class PickTarget(val asset: MediaAsset, val x: Float, val y: Float, val radius: Float)

    private companion object {
        const val MAX_TEXTURES = 72
        val QUAD = floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 1f,
             0.5f, -0.5f, 0f, 1f, 1f,
            -0.5f,  0.5f, 0f, 0f, 0f,
             0.5f,  0.5f, 0f, 1f, 0f,
        )
        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}

private fun resolveCurrentLocation(
    context: Context,
    callback: (Location?, String?) -> Unit,
) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) {
        callback(null, "Location permission is required for the relative spatial scene.")
        return
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = when {
        fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> manager.allProviders.firstOrNull()
    }
    if (provider == null) {
        callback(null, "No location provider is enabled.")
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.getCurrentLocation(
            provider,
            CancellationSignal(),
            context.mainExecutor,
        ) { location ->
            callback(location, if (location == null) "Android could not resolve a current location." else null)
        }
        return
    }

    @Suppress("DEPRECATION")
    val last = manager.getLastKnownLocation(provider)
    if (last != null && System.currentTimeMillis() - last.time <= MAX_LAST_LOCATION_AGE_MILLIS) {
        callback(last, "Using a recent device location fix.")
        return
    }
    val handler = Handler(Looper.getMainLooper())
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            manager.removeUpdates(this)
            callback(location, null)
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    @Suppress("MissingPermission")
    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    handler.postDelayed({
        manager.removeUpdates(listener)
        callback(last, if (last == null) "Location request timed out." else "Using an older cached location after a timeout.")
    }, LOCATION_TIMEOUT_MILLIS)
}

private fun sensorStatusMessage(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Compass accuracy high. Turn the phone to look around."
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Compass accuracy medium. A figure-eight motion can improve calibration."
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Compass accuracy low. Move away from magnets and calibrate with a figure-eight motion."
    else -> "Compass unreliable or unavailable. Drag to adjust the view manually."
}

private const val MAX_SPATIAL_CARDS = 800
private const val MAX_LAST_LOCATION_AGE_MILLIS = 5L * 60L * 1_000L
private const val LOCATION_TIMEOUT_MILLIS = 12_000L
