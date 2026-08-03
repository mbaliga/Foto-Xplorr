package com.fotoxplorr.app.spatial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
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
import com.fotoxplorr.app.media.MediaAsset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
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
            override fun onResume(owner: LifecycleOwner) {
                surface?.resumeScene()
            }

            override fun onPause(owner: LifecycleOwner) {
                surface?.pauseScene()
            }
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
                    val activeSurface = surface
                    AndroidView(factory = { activeSurface }, modifier = Modifier.fillMaxSize())
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
            drawCircle(
                ring,
                radius = maxRadius * fraction,
                center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f),
            )
        }
        drawLine(ComposeColor(0xFFE65A58), centre, Offset(centre.x, centre.y - maxRadius), strokeWidth = 4f)
        drawCircle(
            color = if (sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) ComposeColor(0xFFFFB4AB)
            else ComposeColor.White,
            radius = 5f,
            center = centre,
        )
        locationAccuracyMeters?.let { accuracy ->
            val normalized = (accuracy / 100f).coerceIn(0.05f, 1f)
            drawCircle(ComposeColor.White.copy(alpha = 0.08f), maxRadius * normalized, centre)
        }
    }
}

private fun resolveCurrentLocation(
    context: Context,
    callback: (Location?, String?) -> Unit,
) {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
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
        @Suppress("MissingPermission")
        manager.getCurrentLocation(
            provider,
            CancellationSignal(),
            context.mainExecutor,
            Consumer<Location> { resolved -> callback(resolved, null) },
        )
        return
    }

    @Suppress("DEPRECATION", "MissingPermission")
    val last = manager.getLastKnownLocation(provider)
    if (last != null && System.currentTimeMillis() - last.time <= MAX_LAST_LOCATION_AGE_MILLIS) {
        callback(last, "Using a recent device location fix.")
        return
    }

    val handler = Handler(Looper.getMainLooper())
    val completed = AtomicBoolean(false)
    lateinit var timeout: Runnable
    val listener: LocationListener = object : LocationListener {
        override fun onLocationChanged(resolved: Location) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            manager.removeUpdates(this)
            callback(resolved, null)
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
    timeout = Runnable {
        if (!completed.compareAndSet(false, true)) return@Runnable
        manager.removeUpdates(listener)
        callback(
            last,
            if (last == null) "Location request timed out."
            else "Using an older cached location after a timeout.",
        )
    }

    @Suppress("MissingPermission", "DEPRECATION")
    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    handler.postDelayed(timeout, LOCATION_TIMEOUT_MILLIS)
}

private fun sensorStatusMessage(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Compass accuracy high. Turn the phone to look around."
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
        "Compass accuracy medium. A figure-eight motion can improve calibration."
    SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
        "Compass accuracy low. Move away from magnets and calibrate with a figure-eight motion."
    else -> "Compass unreliable or unavailable. Drag to adjust the view manually."
}

private const val MAX_SPATIAL_CARDS = 800
private const val MAX_LAST_LOCATION_AGE_MILLIS = 5L * 60L * 1_000L
private const val LOCATION_TIMEOUT_MILLIS = 12_000L
