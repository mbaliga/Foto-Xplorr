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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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

/**
 * Photos placed around you in real space, seen through the phone.
 *
 * Immersive by the same rule as the rest of the app (owner: *"Immersive! Immersive! Immersive!"*):
 * the scene is full-bleed and carries no visible controls until asked for. A tap that lands on a
 * photo opens it; a tap that lands on empty sky summons the chrome. The one thing that stays on
 * screen is the bearing -- a compass that will not tell you which way you are facing is not a
 * compass -- and it is drawn as a rose over the scene rather than as a bar above it.
 */
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
    var chromeVisible by remember { mutableStateOf(false) }
    // Written from the sensor callback, which SensorManager delivers on the main thread because
    // the controller registers without a Handler. Read only inside the rose's draw lambda, so a
    // new bearing repaints the overlay without recomposing the screen.
    val heading = remember { mutableFloatStateOf(0f) }

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
                onEmptyTap = { chromeVisible = !chromeVisible },
                onHeadingChanged = { bearing -> heading.floatValue = bearing },
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

    // The scene is live only when there is something to stand inside. Everything else is an
    // opt-in panel, and a panel is not immersive -- it needs its controls on screen.
    val sceneLive = surface != null &&
        location != null &&
        placements.isNotEmpty() &&
        geoState.scannedCount > 0

    Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black)) {
        when {
            geoState.scannedCount == 0 -> SpatialOptInPanel(
                title = "Read embedded coordinates first",
                message = "Foto Xplorr reads each photo's GPS tag on this device. Nothing is uploaded.",
                action = "Read locations",
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
                message = if (geoState.locatedCount == 0) {
                    "None of your photos carry a location. Photos saved from messaging apps and " +
                        "websites usually have theirs removed before they reach your phone."
                } else {
                    "Choose a wider range, or a longer stretch of time."
                },
                action = "Show all distances",
                onAction = { distanceFilter = SpatialDistanceFilter.ALL },
            )
            surface != null -> {
                val activeSurface = surface
                AndroidView(factory = { activeSurface }, modifier = Modifier.fillMaxSize())
                CompassRose(
                    heading = heading,
                    sensorAccuracy = sensorAccuracy,
                    locationAccuracyMeters = location?.accuracy,
                    modifier = Modifier.fillMaxSize(),
                )
                BearingReadout(
                    heading = heading,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp),
                )
            }
        }

        // Chrome. Hidden over a live scene until a tap on empty sky asks for it; always present
        // over a panel, which has nothing to be immersive about.
        AnimatedVisibility(
            visible = chromeVisible || !sceneLive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Close spatial compass",
                            tint = ComposeColor.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (sceneLive) {
                        IconButton(onClick = ::requestLocation) {
                            Icon(
                                Icons.Outlined.GpsFixed,
                                contentDescription = "Refresh current location",
                                tint = ComposeColor.White,
                            )
                        }
                        IconButton(onClick = { surface?.recalibrate() }) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Recalibrate orientation",
                                tint = ComposeColor.White,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(SCRIM)
                        .navigationBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (location == null) "${placements.size} photos around you"
                        else "${placements.size} of ${geoState.locatedCount} located photos in range",
                        color = ComposeColor.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SpatialDistanceFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = distanceFilter == filter,
                                onClick = { distanceFilter = filter },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SpatialTimeFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = timeFilter == filter,
                                onClick = { timeFilter = filter },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                    Text(
                        locationMessage ?: sensorStatusMessage(sensorAccuracy),
                        color = if (sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                            ComposeColor(0xFFFFB4AB)
                        } else {
                            ComposeColor.White.copy(alpha = 0.72f)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * The bearing, in the one place a compass user looks for it.
 *
 * The only thing that survives the immersive rule, because a scene that shows photos "to the
 * north-east" without saying which way is north-east is a puzzle rather than a compass.
 */
@Composable
private fun BearingReadout(heading: MutableFloatState, modifier: Modifier = Modifier) {
    // Rounded to whole degrees before it becomes state the text reads, so a phone held still does
    // not recompose on every sensor sample.
    val degrees = heading.floatValue.roundToInt()
    Text(
        text = bearingLabel(degrees.toFloat()),
        color = ComposeColor.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(SCRIM, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun SpatialOptInPanel(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.Explore, contentDescription = null, tint = ComposeColor.White)
        Text(title, color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
        Text(message, color = ComposeColor.White.copy(alpha = 0.72f))
        Button(onClick = onAction) { Text(action) }
    }
}

/**
 * A compass rose over the scene: rings for distance, a graduated bezel that turns with the phone,
 * and a fixed sight line straight ahead.
 *
 * The bezel counter-rotates by the bearing, which is what makes it read as a real compass -- the
 * card stays pointed at north while the phone turns underneath it, exactly like the floating card
 * in a liquid-filled one. Rotating it *with* the heading instead is the classic inversion, and it
 * looks convincing until you actually turn around.
 *
 * [heading] is read inside the draw lambda rather than captured as a parameter value, so a new
 * sensor sample repaints this overlay without recomposing the screen around it.
 */
@Composable
private fun CompassRose(
    heading: MutableFloatState,
    sensorAccuracy: Int,
    locationAccuracyMeters: Float?,
    modifier: Modifier = Modifier,
) {
    val ring = ComposeColor.White.copy(alpha = 0.14f)
    val unreliable = sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
    Canvas(modifier) {
        val centre = center
        val maxRadius = min(size.width, size.height) * 0.43f
        listOf(0.33f, 0.66f, 1f).forEach { fraction ->
            drawCircle(ring, maxRadius * fraction, centre, style = Stroke(1.5f))
        }

        // The graduated bezel. Cardinals get a long tick, every 15 degrees a short one.
        val bearing = heading.floatValue
        for (degrees in 0 until 360 step 15) {
            val cardinal = degrees % 90 == 0
            val angle = ((degrees - bearing) - 90f) * PI.toFloat() / 180f
            val outer = maxRadius
            val inner = maxRadius - if (cardinal) maxRadius * 0.12f else maxRadius * 0.05f
            val cos = cos(angle)
            val sin = sin(angle)
            drawLine(
                color = when {
                    degrees == 0 -> NORTH
                    cardinal -> ComposeColor.White.copy(alpha = 0.55f)
                    else -> ComposeColor.White.copy(alpha = 0.22f)
                },
                start = Offset(centre.x + cos * inner, centre.y + sin * inner),
                end = Offset(centre.x + cos * outer, centre.y + sin * outer),
                strokeWidth = if (degrees == 0) 5f else if (cardinal) 3f else 1.5f,
            )
        }

        // The sight line: fixed to the screen, showing where the phone itself points.
        drawLine(
            color = ComposeColor.White.copy(alpha = 0.5f),
            start = Offset(centre.x, centre.y - maxRadius * 0.2f),
            end = Offset(centre.x, centre.y - maxRadius),
            strokeWidth = 2f,
        )
        drawCircle(
            color = if (unreliable) ComposeColor(0xFFFFB4AB) else ComposeColor.White,
            radius = 5f,
            center = centre,
        )
        // Location uncertainty as a disc around you, so a coarse fix looks coarse.
        locationAccuracyMeters?.let { accuracy ->
            val normalized = (accuracy / 100f).coerceIn(0.05f, 1f)
            drawCircle(ComposeColor.White.copy(alpha = 0.08f), maxRadius * normalized, centre)
        }
    }
}

/** The eight-point compass name for a bearing in degrees from north. */
internal fun cardinalName(degrees: Float): String {
    val normalized = ((degrees % 360f) + 360f) % 360f
    return CARDINALS[(((normalized + 22.5f) / 45f).toInt()) % CARDINALS.size]
}

/** A bearing as a reader sees it: three zero-padded degrees and the compass point, e.g. `047° NE`. */
internal fun bearingLabel(degrees: Float): String {
    val normalized = ((degrees % 360f) + 360f) % 360f
    // Padded by hand rather than with String.format, which would render the digits in whatever
    // locale the device happens to be in; a bearing is written in Western digits on every compass.
    val whole = (normalized.roundToInt() % 360).toString().padStart(3, '0')
    return "$whole° ${cardinalName(normalized)}"
}

private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

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

/** Scrim behind overlaid text, so a bright photo behind it cannot make the label unreadable. */
private val SCRIM = ComposeColor.Black.copy(alpha = 0.62f)

/** North on the bezel. The one tick that has to be findable at a glance. */
private val NORTH = ComposeColor(0xFFE65A58)

private const val MAX_SPATIAL_CARDS = 800
private const val MAX_LAST_LOCATION_AGE_MILLIS = 5L * 60L * 1_000L
private const val LOCATION_TIMEOUT_MILLIS = 12_000L
