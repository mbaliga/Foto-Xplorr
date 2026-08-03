@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CompassCalibration
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private enum class SpatialMode {
    MAP,
    COMPASS,
    ELEVATION,
    DEPTH_TIMELINE,
}

@Composable
fun PlacesScreen(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    var mode by remember { androidx.compose.runtime.mutableStateOf(SpatialMode.MAP) }
    val locatedAssets = remember(assets, geoState.metadataById) {
        assets.filter { it.id in geoState.metadataById }
    }
    val points = locatedAssets.mapNotNull { asset ->
        geoState.metadataById[asset.id]?.let { metadata -> asset to metadata }
    }
    val depthMode = mode == SpatialMode.DEPTH_TIMELINE
    val displayedAssets = if (depthMode) assets else locatedAssets

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SpatialMode.entries, key = { it.name }) { candidate ->
                FilterChip(
                    selected = candidate == mode,
                    onClick = { mode = candidate },
                    leadingIcon = { Icon(candidate.icon(), contentDescription = null) },
                    label = { Text(candidate.label()) },
                )
            }
        }

        if (geoState.isIndexing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Reading embedded location metadata… ${geoState.scannedCount}/${geoState.totalCount}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
            assets.isEmpty() -> SpatialEmptyState(
                title = "No media available",
                message = "The permitted library does not contain media to explore.",
                action = "Scan locations",
                onAction = onIndexLocations,
            )
            !depthMode && geoState.scannedCount == 0 && !geoState.isIndexing -> SpatialEmptyState(
                title = "Explore where photos were captured",
                message = "Foto Xplorr reads embedded GPS metadata locally. It does not upload locations or require your current location.",
                action = "Index locations",
                onAction = onIndexLocations,
            )
            !depthMode && points.isEmpty() && !geoState.isIndexing -> SpatialEmptyState(
                title = "No embedded locations found",
                message = "The permitted media does not expose GPS coordinates, or Android supplied redacted copies. The depth timeline remains available.",
                action = "Scan again",
                onAction = onIndexLocations,
            )
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    when (mode) {
                        SpatialMode.MAP -> CoordinateMap(points)
                        SpatialMode.COMPASS -> CompassPlot(points)
                        SpatialMode.ELEVATION -> ElevationPlot(points)
                        SpatialMode.DEPTH_TIMELINE -> DepthTimelinePlot(assets)
                    }
                }
                Text(
                    when (mode) {
                        SpatialMode.MAP -> "Offline coordinate plot · no map tiles are downloaded"
                        SpatialMode.COMPASS -> "Photo bearings rotate with the device; capture direction is used when available"
                        SpatialMode.ELEVATION -> "Metadata-derived elevation plot · not a terrain dataset"
                        SpatialMode.DEPTH_TIMELINE -> "Perspective timeline ordered by capture time · experimental 2.5D view"
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(112.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayedAssets, key = { it.id.value }) { asset ->
                        Column(
                            modifier = Modifier
                                .size(width = 88.dp, height = 104.dp)
                                .combinedClickable(
                                    onClick = { onOpenAsset(asset, displayedAssets) },
                                    onLongClick = { onOpenAsset(asset, displayedAssets) },
                                ),
                        ) {
                            MediaImage(
                                asset = asset,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                asset.displayName,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoordinateMap(points: List<Pair<MediaAsset, GeoMetadata>>) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        for (longitude in -180..180 step 30) {
            val x = ((longitude + 180f) / 360f) * size.width
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        for (latitude in -90..90 step 30) {
            val y = ((90f - latitude) / 180f) * size.height
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        drawRect(text.copy(alpha = 0.28f), style = Stroke(width = 2f))
        points.forEach { (_, metadata) ->
            val x = (((metadata.longitude + 180.0) / 360.0) * size.width).toFloat()
            val y = (((90.0 - metadata.latitude) / 180.0) * size.height).toFloat()
            drawCircle(primary, radius = 7f, center = Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.82f), radius = 2.5f, center = Offset(x, y))
        }
    }
}

@Composable
private fun CompassPlot(points: List<Pair<MediaAsset, GeoMetadata>>) {
    val heading = rememberCompassHeading()
    val primary = MaterialTheme.colorScheme.primary
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val centre = centroid(points.map { it.second })

    Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val radius = min(size.width, size.height) * 0.42f
        val origin = center
        drawCircle(foreground.copy(alpha = 0.3f), radius, origin, style = Stroke(3f))
        drawCircle(foreground.copy(alpha = 0.15f), radius * 0.66f, origin, style = Stroke(2f))
        drawCircle(foreground.copy(alpha = 0.15f), radius * 0.33f, origin, style = Stroke(2f))

        val northAngle = Math.toRadians((-heading).toDouble() - 90.0)
        val northTip = Offset(
            origin.x + cos(northAngle).toFloat() * radius,
            origin.y + sin(northAngle).toFloat() * radius,
        )
        drawLine(Color(0xFFE53935), origin, northTip, strokeWidth = 6f)

        points.forEachIndexed { index, (_, metadata) ->
            val bearing = metadata.captureDirectionDegrees
                ?: bearingDegrees(centre.first, centre.second, metadata.latitude, metadata.longitude)
            val relative = Math.toRadians(bearing - heading - 90.0)
            val ring = radius * (0.42f + (index % 3) * 0.22f)
            val offset = Offset(
                origin.x + cos(relative).toFloat() * ring,
                origin.y + sin(relative).toFloat() * ring,
            )
            drawCircle(primary, radius = 7f, center = offset)
        }
    }
}

@Composable
private fun ElevationPlot(points: List<Pair<MediaAsset, GeoMetadata>>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val withAltitude = points.filter { it.second.altitudeMeters != null }
    val source = if (withAltitude.isNotEmpty()) withAltitude else points
    val longitudes = source.map { it.second.longitude }
    val latitudes = source.map { it.second.latitude }
    val altitudes = source.map { it.second.altitudeMeters ?: 0.0 }
    val minLon = longitudes.minOrNull() ?: -180.0
    val maxLon = longitudes.maxOrNull() ?: 180.0
    val minLat = latitudes.minOrNull() ?: -90.0
    val maxLat = latitudes.maxOrNull() ?: 90.0
    val minAlt = altitudes.minOrNull() ?: 0.0
    val maxAlt = altitudes.maxOrNull() ?: 1.0

    Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val baseline = size.height * 0.82f
        val path = Path()
        source.sortedBy { it.second.longitude }.forEachIndexed { index, (_, metadata) ->
            val nx = normalize(metadata.longitude, minLon, maxLon)
            val ny = normalize(metadata.latitude, minLat, maxLat)
            val nz = normalize(metadata.altitudeMeters ?: minAlt, minAlt, maxAlt)
            val x = size.width * (0.08f + nx.toFloat() * 0.84f)
            val perspectiveY = baseline - ny.toFloat() * size.height * 0.26f
            val topY = perspectiveY - nz.toFloat() * size.height * 0.46f
            drawLine(foreground.copy(alpha = 0.25f), Offset(x, perspectiveY), Offset(x, topY), 2f)
            drawCircle(if (metadata.altitudeMeters == null) secondary else primary, 7f, Offset(x, topY))
            if (index == 0) path.moveTo(x, topY) else path.lineTo(x, topY)
        }
        drawPath(path, primary.copy(alpha = 0.65f), style = Stroke(width = 3f))
        drawLine(foreground.copy(alpha = 0.4f), Offset(0f, baseline), Offset(size.width, baseline), 2f)
    }
}

@Composable
private fun DepthTimelinePlot(assets: List<MediaAsset>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val sorted = assets.sortedBy { it.dateTakenMillis }
    val minTime = sorted.firstOrNull()?.dateTakenMillis ?: 0L
    val maxTime = sorted.lastOrNull()?.dateTakenMillis ?: minTime + 1L

    Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val origin = Offset(size.width / 2f, size.height * 0.88f)
        val top = Offset(size.width / 2f, size.height * 0.08f)
        drawLine(foreground.copy(alpha = 0.42f), origin, top, strokeWidth = 4f)

        for (layer in 0..6) {
            val progress = layer / 6f
            val y = origin.y + (top.y - origin.y) * progress
            val width = size.width * (0.44f * (1f - progress * 0.68f))
            drawLine(
                foreground.copy(alpha = 0.18f),
                Offset(origin.x - width, y),
                Offset(origin.x + width, y),
                strokeWidth = 2f,
            )
        }

        val path = Path()
        sorted.forEachIndexed { index, asset ->
            val progress = normalize(
                asset.dateTakenMillis.toDouble(),
                minTime.toDouble(),
                maxTime.toDouble(),
            ).toFloat()
            val depthScale = 1f - progress * 0.72f
            val lane = ((index % 7) - 3) / 3f
            val x = origin.x + lane * size.width * 0.32f * depthScale
            val y = origin.y + (top.y - origin.y) * progress
            val point = Offset(x, y)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(
                color = if (asset.isVideo) secondary else primary,
                radius = 10f * depthScale.coerceAtLeast(0.35f),
                center = point,
            )
        }
        drawPath(path, primary.copy(alpha = 0.35f), style = Stroke(width = 2f))
    }
}

@Composable
private fun SpatialEmptyState(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(42.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun rememberCompassHeading(): Float {
    val context = LocalContext.current
    var heading by remember { mutableFloatStateOf(0f) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                heading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() % 360f) + 360f) % 360f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return heading
}

private fun SpatialMode.label(): String = when (this) {
    SpatialMode.MAP -> "Map"
    SpatialMode.COMPASS -> "Compass"
    SpatialMode.ELEVATION -> "Elevation"
    SpatialMode.DEPTH_TIMELINE -> "Depth timeline"
}

private fun SpatialMode.icon() = when (this) {
    SpatialMode.MAP -> Icons.Outlined.Map
    SpatialMode.COMPASS -> Icons.Outlined.CompassCalibration
    SpatialMode.ELEVATION -> Icons.Outlined.Landscape
    SpatialMode.DEPTH_TIMELINE -> Icons.Outlined.ViewInAr
}

private fun centroid(points: List<GeoMetadata>): Pair<Double, Double> {
    if (points.isEmpty()) return 0.0 to 0.0
    return points.map { it.latitude }.average() to points.map { it.longitude }.average()
}

private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val deltaLambda = (lon2 - lon1) * PI / 180.0
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return ((atan2(y, x) * 180.0 / PI) + 360.0) % 360.0
}

private fun normalize(value: Double, minimum: Double, maximum: Double): Double {
    val span = max(maximum - minimum, 0.000001)
    return ((value - minimum) / span).coerceIn(0.0, 1.0)
}
