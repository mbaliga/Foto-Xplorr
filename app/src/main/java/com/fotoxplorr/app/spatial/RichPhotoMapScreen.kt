@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.spatial

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as ComposeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.hillshadeAccentColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeExaggeration
import org.maplibre.android.style.layers.PropertyFactory.hillshadeHighlightColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.ln
import kotlin.math.max

private enum class MapTimeFilter(val label: String, val windowMillis: Long?) {
    ALL("All time", null),
    THIRTY_DAYS("30 days", 30L * 24L * 60L * 60L * 1_000L),
    ONE_YEAR("1 year", 365L * 24L * 60L * 60L * 1_000L),
}

@Composable
fun RichPhotoMapScreen(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    tagsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by remember { mutableStateOf("") }
    var timeFilter by remember { mutableStateOf(MapTimeFilter.ALL) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<MediaId?>(null) }

    val assetById = remember(assets) { assets.associateBy { it.id } }
    val selectedAsset = selectedId?.let { id -> assetById[id] }
    val albums = remember(assets) {
        assets.mapNotNull { it.bucketName?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .sortedBy(String::lowercase)
            .take(MAX_ALBUM_FILTERS)
    }
    val now = System.currentTimeMillis()
    val filtered = remember(assets, geoState.metadataById, tagsByMediaId, query, timeFilter, selectedAlbum) {
        val normalized = query.trim().lowercase()
        assets.filter { asset ->
            val hasCoordinates = geoState.metadataById.containsKey(asset.id)
            val afterCutoff = timeFilter.windowMillis?.let { asset.dateTakenMillis >= now - it } ?: true
            val inAlbum = selectedAlbum == null || asset.bucketName == selectedAlbum
            val matches = normalized.isEmpty() ||
                asset.displayName.lowercase().contains(normalized) ||
                asset.bucketName?.lowercase()?.contains(normalized) == true ||
                tagsByMediaId[asset.id].orEmpty().any { it.lowercase().contains(normalized) }
            hasCoordinates && !asset.isTrashed && afterCutoff && inAlbum && matches
        }
    }

    val controller = remember {
        RichPhotoMapController(onSelected = { mediaId: MediaId -> selectedId = mediaId })
    }
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { view ->
            view.onCreate(Bundle())
            view.getMapAsync(controller::attach)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close photo map")
            }
            Column(Modifier.weight(1f)) {
                ComposeText("Photo map", style = MaterialTheme.typography.titleLarge)
                ComposeText(
                    "${filtered.size} geotagged items · OpenFreeMap / OpenStreetMap",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onIndexLocations) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Update embedded locations")
            }
            Icon(Icons.Outlined.Layers, contentDescription = null)
        }

        if (geoState.isIndexing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Map, contentDescription = null) },
            placeholder = { ComposeText("Search filename, album or tag") },
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(MapTimeFilter.entries, key = { it.name }) { filter ->
                FilterChip(
                    selected = filter == timeFilter,
                    onClick = { timeFilter = filter },
                    label = { ComposeText(filter.label) },
                )
            }
            item {
                FilterChip(
                    selected = selectedAlbum == null,
                    onClick = { selectedAlbum = null },
                    label = { ComposeText("All albums") },
                )
            }
            items(albums, key = { it }) { album ->
                FilterChip(
                    selected = selectedAlbum == album,
                    onClick = { selectedAlbum = if (selectedAlbum == album) null else album },
                    label = { ComposeText(album) },
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { mapView },
                update = { controller.update(filtered, geoState.metadataById) },
                modifier = Modifier.fillMaxSize(),
            )
            if (geoState.scannedCount == 0 && !geoState.isIndexing) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ComposeText("Location metadata has not been indexed")
                    AssistChip(onClick = onIndexLocations, label = { ComposeText("Index locations locally") })
                }
            }
        }

        selectedAsset?.let { selected ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .padding(8.dp)
                    .combinedClickable(
                        onClick = { onOpenAsset(selected, filtered) },
                        onLongClick = { onOpenAsset(selected, filtered) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MediaImage(
                    asset = selected,
                    modifier = Modifier.size(96.dp),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.weight(1f)) {
                    ComposeText(selected.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    ComposeText(selected.bucketName ?: "Unknown album", style = MaterialTheme.typography.bodySmall)
                    geoState.metadataById[selected.id]?.let { metadata ->
                        ComposeText(
                            "%.5f, %.5f".format(metadata.latitude, metadata.longitude),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    ComposeText("Tap to open viewer", color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { selectedId = null; controller.clearSelection() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear selected photo")
                }
            }
        }

        ComposeText(
            "Pitched vector map with clustered media, client-side hillshade and 3D building extrusion. MapLibre Native Android does not currently expose an elevated terrain mesh, so hillshade is the terrain fallback.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class RichPhotoMapController(
    private val onSelected: (MediaId) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var selectedSource: GeoJsonSource? = null
    private var pendingAssets: List<MediaAsset> = emptyList()
    private var pendingMetadata: Map<MediaId, GeoMetadata> = emptyMap()
    private var didFitInitialCamera = false
    private var clickListenerInstalled = false

    fun attach(map: MapLibreMap) {
        this.map = map
        map.uiSettings.apply {
            isCompassEnabled = true
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
            isAttributionEnabled = true
            isLogoEnabled = true
        }
        map.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) { loadedStyle ->
            installTerrainStyling(loadedStyle)
            installPhotoLayers(loadedStyle)
            if (!clickListenerInstalled) {
                clickListenerInstalled = true
                map.addOnMapClickListener { latLng -> handleClick(map, latLng) }
            }
            updateSource()
        }
    }

    fun update(assets: List<MediaAsset>, metadata: Map<MediaId, GeoMetadata>) {
        pendingAssets = assets
        pendingMetadata = metadata
        updateSource()
    }

    fun clearSelection() {
        selectedSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    private fun installPhotoLayers(style: Style) {
        val photoSource = GeoJsonSource(
            PHOTO_SOURCE,
            FeatureCollection.fromFeatures(emptyArray()),
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(56)
                .withClusterMaxZoom(14),
        )
        source = photoSource
        style.addSource(photoSource)

        val clusterLayer = CircleLayer(CLUSTER_LAYER, PHOTO_SOURCE).apply {
            setFilter(Expression.has("point_count"))
            setProperties(
                circleColor(Color.rgb(94, 77, 176)),
                circleRadius(22f),
                circleOpacity(0.88f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2.2f),
            )
        }
        style.addLayer(clusterLayer as Layer)

        val pointLayer = CircleLayer(POINT_LAYER, PHOTO_SOURCE).apply {
            setFilter(Expression.not(Expression.has("point_count")))
            setProperties(
                circleColor(Color.rgb(255, 171, 64)),
                circleRadius(8.5f),
                circleOpacity(0.94f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2f),
            )
        }
        style.addLayer(pointLayer as Layer)

        val selectionSource = GeoJsonSource(
            SELECTED_SOURCE,
            FeatureCollection.fromFeatures(emptyArray()),
        )
        selectedSource = selectionSource
        style.addSource(selectionSource)
        val selectedLayer = CircleLayer(SELECTED_LAYER, SELECTED_SOURCE).apply {
            setProperties(
                circleColor(Color.WHITE),
                circleRadius(14f),
                circleOpacity(0.35f),
                circleStrokeColor(Color.rgb(255, 92, 92)),
                circleStrokeWidth(4f),
            )
        }
        style.addLayer(selectedLayer as Layer)
    }

    private fun installTerrainStyling(style: Style) {
        runCatching {
            if (style.getSource(HILLSHADE_SOURCE) == null) {
                val tiles = TileSet("2.1.0", TERRARIUM_TILE_URL).apply {
                    attribution = "Elevation tiles: AWS Open Data / Mapzen terrain"
                    setMinZoom(0.0)
                    setMaxZoom(15.0)
                }
                runCatching {
                    tiles.javaClass.getMethod("setEncoding", String::class.java).invoke(tiles, "terrarium")
                }
                style.addSource(RasterDemSource(HILLSHADE_SOURCE, tiles, 256))
                val hillshadeLayer = HillshadeLayer(HILLSHADE_LAYER, HILLSHADE_SOURCE).apply {
                    setProperties(
                        hillshadeExaggeration(0.42f),
                        hillshadeShadowColor(Color.rgb(22, 25, 34)),
                        hillshadeHighlightColor(Color.rgb(240, 232, 210)),
                        hillshadeAccentColor(Color.rgb(70, 83, 105)),
                    )
                }
                style.addLayer(hillshadeLayer as Layer)
            }
        }

        runCatching {
            if (style.getLayer(BUILDING_LAYER) == null && style.getSource("openmaptiles") != null) {
                val buildingLayer = FillExtrusionLayer(BUILDING_LAYER, "openmaptiles").apply {
                    sourceLayer = "building"
                    setFilter(
                        Expression.all(
                            Expression.has("render_height"),
                            Expression.has("render_min_height"),
                        ),
                    )
                    minZoom = 15f
                    setProperties(
                        fillExtrusionColor(Color.rgb(197, 202, 212)),
                        fillExtrusionHeight(Expression.get("render_height")),
                        fillExtrusionBase(Expression.get("render_min_height")),
                        fillExtrusionOpacity(0.82f),
                    )
                }
                style.addLayer(buildingLayer as Layer)
            }
        }
    }

    private fun updateSource() {
        val currentMap = map ?: return
        val currentSource = source ?: return
        val features = pendingAssets.mapNotNull { asset ->
            val metadata = pendingMetadata[asset.id] ?: return@mapNotNull null
            Feature.fromGeometry(
                Point.fromLngLat(metadata.longitude, metadata.latitude),
                JsonObject().apply {
                    addProperty("media_id", asset.id.value.toString())
                    addProperty("name", asset.displayName)
                    addProperty("album", asset.bucketName.orEmpty())
                    addProperty("is_video", asset.isVideo)
                    addProperty("captured_at", asset.dateTakenMillis)
                },
            )
        }
        currentSource.setGeoJson(FeatureCollection.fromFeatures(features))
        if (!didFitInitialCamera && features.isNotEmpty()) {
            didFitInitialCamera = true
            val locations = features.mapNotNull { feature ->
                (feature.geometry() as? Point)?.let { LatLng(it.latitude(), it.longitude()) }
            }
            val centre = LatLng(
                locations.map { it.latitude }.average(),
                locations.map { it.longitude }.average(),
            )
            val latitudeSpan = locations.maxOf { it.latitude } - locations.minOf { it.latitude }
            val longitudeSpan = locations.maxOf { it.longitude } - locations.minOf { it.longitude }
            val spread = max(latitudeSpan, longitudeSpan).coerceAtLeast(0.0001)
            val zoom = (7.6 - ln(spread) / ln(2.0)).coerceIn(1.2, 15.5)
            val update = CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(centre)
                    .zoom(zoom)
                    .tilt(56.0)
                    .bearing(18.0)
                    .build(),
            )
            currentMap.animateCamera(update, 1_200, null)
        }
    }

    private fun handleClick(map: MapLibreMap, latLng: LatLng): Boolean {
        val screen = map.projection.toScreenLocation(latLng)
        val clusters = map.queryRenderedFeatures(screen, arrayOf(CLUSTER_LAYER))
        val cluster = clusters.firstOrNull()
        if (cluster != null) {
            val expansion = source?.getClusterExpansionZoom(cluster)
                ?: (map.cameraPosition.zoom + 2.0).toInt()
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(latLng, expansion.toDouble()),
                650,
                null,
            )
            return true
        }

        val points = map.queryRenderedFeatures(screen, arrayOf(POINT_LAYER))
        val feature = points.firstOrNull() ?: return false
        val id = feature.getStringProperty("media_id")?.toLongOrNull()?.let(::MediaId) ?: return false
        selectedSource?.setGeoJson(FeatureCollection.fromFeature(feature))
        onSelected(id)
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(map.cameraPosition)
                .target(latLng)
                .tilt(max(48.0, map.cameraPosition.tilt))
                .build(),
        )
        map.animateCamera(update, 500, null)
        return true
    }

    private companion object {
        const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        const val PHOTO_SOURCE = "foto-xplorr-media"
        const val CLUSTER_LAYER = "foto-xplorr-clusters"
        const val POINT_LAYER = "foto-xplorr-points"
        const val SELECTED_SOURCE = "foto-xplorr-selected-source"
        const val SELECTED_LAYER = "foto-xplorr-selected"
        const val HILLSHADE_SOURCE = "foto-xplorr-dem"
        const val HILLSHADE_LAYER = "foto-xplorr-hillshade"
        const val BUILDING_LAYER = "foto-xplorr-buildings-3d"
        const val TERRARIUM_TILE_URL =
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    }
}

private const val MAX_ALBUM_FILTERS = 16
