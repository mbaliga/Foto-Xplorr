package com.fotoxplorr.app.spatial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.FotoStamp
import kotlin.math.roundToInt

/**
 * Photos on a map, as postage stamps on a stylized field.
 *
 * This is the offline map (owner, 2026-08-15: *"Stylized by default"*). There is no basemap to
 * download, because there is no basemap: a photo's location is only a latitude and longitude, and
 * placing a stamp at a coordinate is arithmetic that needs no tiles, no MapLibre and no network.
 * See [StampMapProjection] for the projection and `ADR-006` for why the street map went the other
 * way.
 *
 * The reference (owner's Monte Tomba animation) is a flat coloured field with stamps pinned to it
 * and a dotted route threading between them; selecting one opens it large. That is what this is,
 * rendered in the app's own palette rather than the reference's pastel blue.
 *
 * The field is deliberately not pretending to be geography. It carries a graticule so position
 * reads as position, and nothing else -- inventing coastlines the app does not have data for would
 * be worse than admitting there are none.
 */
@Composable
fun StampMapScreen(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val located = remember(assets, geoState.metadataById) {
        assets.mapNotNull { asset ->
            if (asset.isTrashed) null else geoState.metadataById[asset.id]?.let { asset to it }
        }
    }
    val bounds = remember(located) {
        StampMapProjection.boundsOf(located.map { it.second.latitude to it.second.longitude })
    }
    // Thinned to one stamp per grid cell. A library with thousands of located photos would
    // otherwise compose thousands of overlapping stamps -- slow to lay out and unreadable, since
    // all but the last would be buried anyway.
    val stamps = remember(located, bounds) {
        bounds?.let { box ->
            StampMapProjection.thin(located, THIN_CELLS) { (_, geo) ->
                StampMapProjection.project(geo.latitude, geo.longitude, box)
            }
        }.orEmpty()
    }

    var selected by remember { mutableStateOf<MediaId?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    if (stamps.isEmpty() || bounds == null) {
        EmptyMap(
            geoState = geoState,
            onIndexLocations = onIndexLocations,
            onClose = onClose,
            modifier = modifier,
        )
        return
    }

    Box(modifier.fillMaxSize().background(FIELD)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        panX += pan.x
                        panY += pan.y
                    }
                },
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val stampPx = with(density) { STAMP_SIZE.dp.toPx() }

            // The graticule, so a position reads as a position. Drawn under everything and
            // scaled with the map so it acts like a real reference grid rather than screen
            // furniture that happens to sit on top.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        translationY = panY
                    }
                    .background(
                        Brush.linearGradient(listOf(FIELD, FIELD_DEEP)),
                    ),
            ) {
                Graticule()
            }

            stamps.forEach { (asset, geo) ->
                val point = StampMapProjection.project(geo.latitude, geo.longitude, bounds)
                val isSelected = selected == asset.id
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.35f else 1f,
                    label = "stamp-scale",
                )
                Box(
                    Modifier
                        .offset {
                            // Placement offset, not a layer translation: a stamp has to answer
                            // touches where it is drawn, and the whole map pans underneath.
                            //
                            // Scaled about the centre of the viewport, matching the pivot
                            // `graphicsLayer` uses for the graticule below. Scaling these about
                            // the top-left instead -- the obvious reading of the arithmetic --
                            // slides the pins off the grid the moment you pinch.
                            IntOffset(
                                x = ((point.x - 0.5f) * widthPx * zoom + widthPx / 2f -
                                    stampPx / 2f + panX).roundToInt(),
                                y = ((point.y - 0.5f) * heightPx * zoom + heightPx / 2f -
                                    stampPx / 2f + panY).roundToInt(),
                            )
                        }
                        .size(STAMP_SIZE.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(FotoStamp)
                        .background(STAMP_PAPER)
                        .clickable { selected = if (isSelected) null else asset.id },
                ) {
                    MediaImage(
                        asset = asset,
                        modifier = Modifier.fillMaxSize().padding(2.dp).clip(FotoStamp),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "✕",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
            )
            Column(Modifier.padding(start = 4.dp)) {
                Text(
                    "PLACES",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    // Says both numbers when they differ, so a thinned map never looks like it
                    // silently lost photos.
                    if (stamps.size == located.size) "${located.size} located"
                    else "${stamps.size} of ${located.size} located",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // The immersive half of the reference: selecting a stamp opens it as a poster with the
        // rest of that place's photos beneath it.
        selected?.let { id ->
            val asset = stamps.firstOrNull { it.first.id == id }?.first
            if (asset != null) {
                val nearby = remember(stamps) { stamps.map { it.first } }
                PlacePoster(
                    asset = asset,
                    nearby = nearby,
                    onOpen = { onOpenAsset(asset, nearby) },
                    onDismiss = { selected = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** A faint reference grid. Not geography -- just enough that position reads as position. */
@Composable
private fun Graticule() {
    Column(Modifier.fillMaxSize()) {
        repeat(GRATICULE_ROWS) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(0.5.dp, Color.White.copy(alpha = 0.05f)),
            )
        }
    }
}

/**
 * The selected place, as a poster over the field.
 *
 * Anchored to the bottom rather than centred so the stamp the user just tapped stays visible above
 * it -- a modal that covers the thing it is describing makes the map feel like it was replaced
 * rather than expanded.
 */
@Composable
private fun PlacePoster(
    asset: MediaAsset,
    nearby: List<MediaAsset>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.92f))
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                asset.displayName,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Close",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(FotoStamp)
                .background(STAMP_PAPER)
                .clickable(onClick = onOpen),
        ) {
            MediaImage(
                asset = asset,
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                contentScale = ContentScale.Fit,
            )
        }
        if (nearby.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nearby, key = { it.id.value }) { other ->
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(FotoStamp)
                            .background(STAMP_PAPER),
                    ) {
                        MediaImage(
                            asset = other,
                            modifier = Modifier.fillMaxSize().padding(1.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the map says when it has nothing to show.
 *
 * Deliberately specific about WHY, because the two reasons are completely different problems:
 * the scan has not run, or it ran and the photos genuinely carry no coordinates. Most photos saved
 * from messaging apps and social sites have had their GPS stripped before they ever reached the
 * device, and telling someone "no photos have locations" without that context reads as the app
 * being broken.
 */
@Composable
private fun EmptyMap(
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(FIELD), contentAlignment = Alignment.Center) {
        Text(
            "✕",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .clickable(onClick = onClose)
                .padding(16.dp),
        )
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    geoState.isIndexing -> "Reading locations…"
                    geoState.scannedCount == 0 -> "Locations have not been read yet"
                    else -> "None of your photos carry a location"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    geoState.isIndexing ->
                        "Checked ${geoState.scannedCount} of ${geoState.totalCount} so far."
                    geoState.scannedCount == 0 ->
                        "Foto Xplorr reads each photo's own GPS tag on this device. Nothing is uploaded."
                    else ->
                        "Photos saved from messaging apps and websites usually have their location " +
                            "removed before they reach your phone. Shots taken with this device's " +
                            "camera, with location switched on, will appear here."
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (!geoState.isIndexing) {
                Text(
                    if (geoState.scannedCount == 0) "Read locations" else "Check again",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onIndexLocations).padding(12.dp),
                )
            }
        }
    }
}

private val FIELD = Color(0xFF11141C)
private val FIELD_DEEP = Color(0xFF0A0C12)
private val STAMP_PAPER = Color(0xFFF5F1E8)
private const val STAMP_SIZE = 64
private const val GRATICULE_ROWS = 8

/**
 * The map is thinned to one stamp per cell of a [THIN_CELLS] x [THIN_CELLS] grid: at most 144
 * stamps on screen however large the library is. Chosen so the densest map still reads as separate
 * pinned photographs rather than a solid sheet of them.
 */
private const val THIN_CELLS = 12
private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 6f
