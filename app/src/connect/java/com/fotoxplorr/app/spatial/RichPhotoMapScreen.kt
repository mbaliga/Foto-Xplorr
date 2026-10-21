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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.viewer.PlaceMorph
import com.fotoxplorr.app.viewer.PlacePin
import com.fotoxplorr.core.model.MediaId
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
import kotlin.math.roundToInt

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
    /**
     * Opened from one photo's details room: the camera starts on that photo at street zoom, it is
     * drawn as its white-bordered tile riding the map, and every other located photo is a filled
     * dot in its own colour with no clustering -- see [MapFocus]. Null is the Places map exactly as
     * it always was.
     */
    focus: MapFocus? = null,
    modifier: Modifier = Modifier,
    /**
     * Drawn as a pane of the browser rather than as a screen: no header, no close button, no
     * search, filter chips or footer (the browser has its own search and time range, and they
     * would be two of each), and no [BackHandler] -- the browser owns the Back order, and a
     * handler here would outrank it. The map's own attribution and compass are kept, and moved
     * clear of the browser's controls.
     */
    embedded: Boolean = false,
) {
    if (!embedded) BackHandler(onBack = onClose)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by remember { mutableStateOf("") }
    var timeFilter by remember { mutableStateOf(MapTimeFilter.ALL) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<MediaId?>(null) }

    val assetsById = remember(assets) { assets.associateBy { it.id } }
    val selectedAsset = selectedId?.let(assetsById::get)
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
            val afterCutoff = timeFilter.windowMillis?.let { asset.dateTakenMillis >= now - it } ?: true
            val matchesQuery = normalized.isEmpty() ||
                asset.displayName.lowercase().contains(normalized) ||
                asset.bucketName?.lowercase()?.contains(normalized) == true ||
                tagsByMediaId[asset.id].orEmpty().any { it.lowercase().contains(normalized) }
            asset.id in geoState.metadataById &&
                !asset.isTrashed &&
                afterCutoff &&
                (selectedAlbum == null || asset.bucketName == selectedAlbum) &&
                matchesQuery
        }
    }
    // The focused photo may be located by its own EXIF and absent from the geo index (the index
    // is built on demand); it is still what this map is about, so its coordinate comes from the
    // focus itself.
    val focusAsset = focus?.let { assetsById[it.assetId] }

    // The cluster bubbles are the accent role, so they take the chosen accent. Read once, as the
    // controller is made: the accent cannot change while this full-screen map is open.
    val accentArgb = MaterialTheme.colorScheme.primary.toArgb()
    // Embedded, the browser's pill and corner discs float along the bottom edge and its view
    // switcher along the top: the attribution and logo (bottom left) and the compass (top right)
    // are lifted clear of them. Read once, as the controller is made, like the accent.
    val chromeDensity = LocalDensity.current
    val lowerChromePx = if (embedded) {
        with(chromeDensity) { EMBEDDED_LOWER_CHROME_DP.dp.roundToPx() } +
            WindowInsets.navigationBars.getBottom(chromeDensity)
    } else {
        0
    }
    val upperChromePx = if (embedded) {
        with(chromeDensity) { EMBEDDED_UPPER_CHROME_DP.dp.roundToPx() } +
            WindowInsets.statusBars.getTop(chromeDensity)
    } else {
        0
    }
    val controller = remember(focus, embedded) {
        RichPhotoMapController(
            focus = focus,
            accentArgb = accentArgb,
            lowerChromePx = lowerChromePx,
            upperChromePx = upperChromePx,
        ) { mediaId -> selectedId = mediaId }
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

    Column(modifier.fillMaxSize()) {
        if (!embedded) Row(
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

        if (geoState.isIndexing) LinearProgressIndicator(Modifier.fillMaxWidth())

        // The search and the filters are the Places map's own tools; a map opened for ONE photo
        // is about that photo's place, and the chrome would only push the map down.
        if (focus == null && !embedded) OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Map, contentDescription = null) },
            placeholder = { ComposeText("Search filename, album or tag") },
        )

        if (focus == null && !embedded) LazyRow(
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

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { mapView },
                update = { controller.update(filtered, geoState.metadataById) },
                modifier = Modifier.fillMaxSize(),
            )
            // The focused photo's tile rides the map: its screen position is re-projected on
            // every camera move by the controller and read here as a placement offset, so the
            // tile answers touches where it is drawn and the map pans underneath it.
            if (focus != null && focusAsset != null) {
                val density = LocalDensity.current
                val pinWidthPx = with(density) { PlaceMorph.PIN_SIZE_DP.dp.toPx() }
                val pinHeightPx = with(density) { PlaceMorph.PIN_GROUP_HEIGHT_DP.dp.toPx() }
                PlacePin(
                    asset = focusAsset,
                    modifier = Modifier
                        // The projected point is read inside the placement and layer lambdas,
                        // not in composition: a camera move then re-places and re-draws the tile
                        // without recomposing it (or anything else) per frame.
                        .offset {
                            val point = controller.focusScreenPoint.value
                            if (point == null) {
                                IntOffset.Zero
                            } else {
                                IntOffset(
                                    x = (point.x - pinWidthPx / 2f).roundToInt(),
                                    y = (point.y - pinHeightPx / 2f).roundToInt(),
                                )
                            }
                        }
                        .graphicsLayer { alpha = if (controller.focusScreenPoint.value == null) 0f else 1f },
                )
            }
            if (focus == null && geoState.scannedCount == 0 && !geoState.isIndexing) {
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
                    // Embedded, the card rises above the browser's pill and corner discs.
                    .padding(bottom = if (embedded) EMBEDDED_LOWER_CHROME_DP.dp else 0.dp)
                    .height(116.dp)
                    .padding(8.dp)
                    .combinedClickable(
                        onClick = { onOpenAsset(selected, filtered) },
                        onLongClick = { onOpenAsset(selected, filtered) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MediaImage(selected, Modifier.size(96.dp), ContentScale.Crop)
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

        if (!embedded) ComposeText(
            "Pitched vector map with clustered media, hillshade and 3D building extrusion. MapLibre Android currently provides hillshade rather than a raised terrain mesh.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class RichPhotoMapController(
    private val focus: MapFocus? = null,
    private val accentArgb: Int,
    /** Px to lift the attribution and logo off the bottom edge; 0 leaves them where MapLibre puts them. */
    private val lowerChromePx: Int = 0,
    /** Px to push the compass down from the top edge; 0 leaves it where MapLibre puts it. */
    private val upperChromePx: Int = 0,
    private val onSelected: (MediaId) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var photoSource: GeoJsonSource? = null
    private var selectionSource: GeoJsonSource? = null
    private var assets: List<MediaAsset> = emptyList()
    private var metadata: Map<MediaId, GeoMetadata> = emptyMap()
    private var cameraFitted = false
    private var clickListenerInstalled = false

    /** Where the focused photo's coordinate currently falls on screen, in px; null off-map or unfocused. */
    val focusScreenPoint = mutableStateOf<Offset?>(null)

    fun attach(map: MapLibreMap) {
        this.map = map
        map.uiSettings.apply {
            isCompassEnabled = true
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
            isAttributionEnabled = true
            isLogoEnabled = true
            // Moved, never hidden: the attribution is owed to the data's owners.
            if (lowerChromePx > 0) {
                setLogoMargins(logoMarginLeft, logoMarginTop, logoMarginRight, lowerChromePx)
                setAttributionMargins(
                    attributionMarginLeft, attributionMarginTop, attributionMarginRight, lowerChromePx,
                )
            }
            if (upperChromePx > 0) {
                setCompassMargins(compassMarginLeft, upperChromePx, compassMarginRight, compassMarginBottom)
            }
        }
        val style = if (focus != null) FOCUS_STYLE else OPEN_FREE_MAP_STYLE
        map.setStyle(Style.Builder().fromUri(style)) { loaded ->
            addTerrainStyling(loaded)
            addPhotoLayers(loaded)
            if (!clickListenerInstalled) {
                clickListenerInstalled = true
                map.addOnMapClickListener { location -> handleMapClick(map, location) }
            }
            if (focus != null && !cameraFitted) {
                // One photo's place: start ON it at street zoom, flat, rather than fitting the
                // whole library's spread -- that fit is the Places map's opening move, not this one's.
                cameraFitted = true
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(focus.latitude, focus.longitude))
                            .zoom(FOCUS_ZOOM)
                            .tilt(0.0)
                            .bearing(0.0)
                            .build(),
                    ),
                )
                map.addOnCameraMoveListener { projectFocus(map) }
                map.addOnCameraIdleListener { projectFocus(map) }
                projectFocus(map)
            }
            refreshSource()
        }
    }

    private fun projectFocus(map: MapLibreMap) {
        val target = focus ?: return
        val point = runCatching { map.projection.toScreenLocation(LatLng(target.latitude, target.longitude)) }
            .getOrNull()
        focusScreenPoint.value = point?.let { Offset(it.x, it.y) }
    }

    fun update(assets: List<MediaAsset>, metadata: Map<MediaId, GeoMetadata>) {
        // AndroidView.update runs on every recomposition of the screen, and a refresh rebuilds a
        // GeoJSON feature for every located photo. The same two lists mean nothing has changed.
        if (assets === this.assets && metadata === this.metadata) return
        this.assets = assets
        this.metadata = metadata
        refreshSource()
    }

    fun clearSelection() {
        selectionSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    private fun addPhotoLayers(style: Style) {
        // Focus mode draws EVERY located photo as its own dot (owner: "every located photo drawn
        // as a filled colour dot"), so clustering is off there; the Places map keeps its clusters.
        val source = GeoJsonSource(
            PHOTO_SOURCE,
            FeatureCollection.fromFeatures(emptyArray()),
            GeoJsonOptions()
                .withCluster(focus == null)
                .withClusterRadius(56)
                .withClusterMaxZoom(14),
        )
        photoSource = source
        style.addSource(source)

        val clusters: Layer = CircleLayer(CLUSTER_LAYER, PHOTO_SOURCE).apply {
            setFilter(Expression.has("point_count"))
            setProperties(
                circleColor(accentArgb),
                circleRadius(22f),
                circleOpacity(0.88f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2.2f),
            )
        }
        style.addLayer(clusters)

        val points: Layer = CircleLayer(POINT_LAYER, PHOTO_SOURCE).apply {
            setFilter(Expression.not(Expression.has("point_count")))
            if (focus != null) {
                // Filled, strokeless, each in the colour its feature carries -- the photo's own
                // dominant palette colour when known, the accent otherwise (see refreshSource).
                setProperties(
                    circleColor(Expression.toColor(Expression.get(COLOR_PROPERTY))),
                    circleRadius(6.5f),
                    circleOpacity(0.96f),
                    circleStrokeWidth(0f),
                )
            } else {
                setProperties(
                    // Fixed on purpose (semantic): map style colours (point, selection ring,
                    // terrain, extrusion) are not accent roles.
                    circleColor(Color.rgb(255, 171, 64)),
                    circleRadius(8.5f),
                    circleOpacity(0.94f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(2f),
                )
            }
        }
        style.addLayer(points)

        val selected = GeoJsonSource(
            SELECTED_SOURCE,
            FeatureCollection.fromFeatures(emptyArray()),
        )
        selectionSource = selected
        style.addSource(selected)
        val selectedLayer: Layer = CircleLayer(SELECTED_LAYER, SELECTED_SOURCE).apply {
            setProperties(
                circleColor(Color.WHITE),
                circleRadius(14f),
                circleOpacity(0.35f),
                circleStrokeColor(Color.rgb(255, 92, 92)),
                circleStrokeWidth(4f),
            )
        }
        style.addLayer(selectedLayer)
    }

    private fun addTerrainStyling(style: Style) {
        runCatching {
            if (style.getSource(HILLSHADE_SOURCE) == null) {
                val tiles = TileSet("2.1.0", TERRARIUM_TILE_URL).apply {
                    attribution = "Elevation tiles: AWS Open Data / Mapzen terrain"
                    setMinZoom(0f)
                    setMaxZoom(15f)
                }
                runCatching {
                    tiles.javaClass.getMethod("setEncoding", String::class.java)
                        .invoke(tiles, "terrarium")
                }
                style.addSource(RasterDemSource(HILLSHADE_SOURCE, tiles, 256))
                val hillshade: Layer = HillshadeLayer(HILLSHADE_LAYER, HILLSHADE_SOURCE).apply {
                    setProperties(
                        hillshadeExaggeration(0.42f),
                        hillshadeShadowColor(arrayOf("#161922")),
                        hillshadeHighlightColor(arrayOf("#F0E8D2")),
                        // hillshadeAccentColor has no String[] overload (unlike shadow/highlight
                        // above) -- only Int, String or Expression. PropertyFactory.PropertyValue
                        // is invariant in its generic type, so passing an Array<String> here fails
                        // to resolve against any overload at compile time.
                        hillshadeAccentColor("#465369"),
                    )
                }
                style.addLayer(hillshade)
            }
        }

        runCatching {
            if (style.getLayer(BUILDING_LAYER) == null && style.getSource("openmaptiles") != null) {
                val buildings: Layer = FillExtrusionLayer(BUILDING_LAYER, "openmaptiles").apply {
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
                style.addLayer(buildings)
            }
        }
    }

    private fun refreshSource() {
        val map = map ?: return
        val source = photoSource ?: return
        val features = assets.mapNotNull { asset ->
            // The focused photo is the tile riding the map, not a dot under it.
            if (focus != null && asset.id == focus.assetId) return@mapNotNull null
            val geo = metadata[asset.id] ?: return@mapNotNull null
            Feature.fromGeometry(
                Point.fromLngLat(geo.longitude, geo.latitude),
                JsonObject().apply {
                    addProperty(MEDIA_ID_PROPERTY, asset.id.value.toString())
                    addProperty("name", asset.displayName)
                    addProperty("album", asset.bucketName.orEmpty())
                    addProperty("is_video", asset.isVideo)
                    addProperty("captured_at", asset.dateTakenMillis)
                    if (focus != null) {
                        addProperty(COLOR_PROPERTY, "#%06X".format(0xFFFFFF and focus.colorFor(asset.id)))
                    }
                },
            )
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        if (!cameraFitted && features.isNotEmpty()) {
            cameraFitted = true
            val positions = features.mapNotNull { feature ->
                (feature.geometry() as? Point)?.let { LatLng(it.latitude(), it.longitude()) }
            }
            if (positions.isNotEmpty()) {
                val centre = LatLng(
                    positions.map { it.latitude }.average(),
                    positions.map { it.longitude }.average(),
                )
                val latitudeSpan = positions.maxOf { it.latitude } - positions.minOf { it.latitude }
                val longitudeSpan = positions.maxOf { it.longitude } - positions.minOf { it.longitude }
                val spread = max(latitudeSpan, longitudeSpan).coerceAtLeast(0.0001)
                val zoom = (7.6 - ln(spread) / ln(2.0)).coerceIn(1.2, 15.5)
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(centre)
                            .zoom(zoom)
                            .tilt(56.0)
                            .bearing(18.0)
                            .build(),
                    ),
                    1_200,
                    null,
                )
            }
        }
    }

    private fun handleMapClick(map: MapLibreMap, location: LatLng): Boolean {
        val screenPoint = map.projection.toScreenLocation(location)
        val cluster = map.queryRenderedFeatures(screenPoint, CLUSTER_LAYER).firstOrNull()
        if (cluster != null) {
            val expansion = photoSource?.getClusterExpansionZoom(cluster)
                ?: (map.cameraPosition.zoom + 2.0).toInt()
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(location, expansion.toDouble()),
                650,
                null,
            )
            return true
        }

        val feature = map.queryRenderedFeatures(screenPoint, POINT_LAYER).firstOrNull() ?: return false
        val mediaId = feature.properties()
            ?.get(MEDIA_ID_PROPERTY)
            ?.asString
            ?.toLongOrNull()
            ?.let(::MediaId)
            ?: return false
        selectionSource?.setGeoJson(FeatureCollection.fromFeature(feature))
        onSelected(mediaId)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(map.cameraPosition)
                    .target(location)
                    .tilt(max(48.0, map.cameraPosition.tilt))
                    .build(),
            ),
            500,
            null,
        )
        return true
    }

    private companion object {
        const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        /** The dark style the plate's snapshot uses, so the expanded map is the plate grown, not a different map. */
        const val FOCUS_STYLE = "https://tiles.openfreemap.org/styles/dark"
        const val FOCUS_ZOOM = 15.4
        const val COLOR_PROPERTY = "color"
        const val PHOTO_SOURCE = "foto-xplorr-media"
        const val CLUSTER_LAYER = "foto-xplorr-clusters"
        const val POINT_LAYER = "foto-xplorr-points"
        const val SELECTED_SOURCE = "foto-xplorr-selected-source"
        const val SELECTED_LAYER = "foto-xplorr-selected"
        const val HILLSHADE_SOURCE = "foto-xplorr-dem"
        const val HILLSHADE_LAYER = "foto-xplorr-hillshade"
        const val BUILDING_LAYER = "foto-xplorr-buildings-3d"
        const val MEDIA_ID_PROPERTY = "media_id"
        const val TERRARIUM_TILE_URL =
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
    }
}

private const val MAX_ALBUM_FILTERS = 16

/** Clears a 48dp disc raised 20dp from the bottom edge, plus a gap. */
private const val EMBEDDED_LOWER_CHROME_DP = 80

/** Clears the browser's view switcher at the top right. */
private const val EMBEDDED_UPPER_CHROME_DP = 56
