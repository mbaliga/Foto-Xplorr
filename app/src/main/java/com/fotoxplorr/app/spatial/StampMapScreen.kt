package com.fotoxplorr.app.spatial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
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
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.FotoStamp
import com.fotoxplorr.app.viewer.PlaceMorph
import com.fotoxplorr.app.viewer.PlacePin
import com.fotoxplorr.core.model.MediaId
import kotlin.math.roundToInt
import com.fotoxplorr.app.ui.HelpCopy

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
 *
 * It is also the offline build's map pane inside the browser (`embedded`): the same field with no
 * header, no close glyph and no Back handling, drawn in place of the grid and fed the photos the
 * grid was showing. See [PhotoMapPane].
 */
@Composable
fun StampMapScreen(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opened from one photo's details room: that photo is drawn as its white-bordered tile, and
     * every other located photo as a filled colour dot rather than a stamp -- see [MapFocus].
     * Null is the whole-view map.
     */
    focus: MapFocus? = null,
    /**
     * Drawn as a pane of the browser rather than as a screen: no header and no close glyph, and
     * -- the part that matters -- no [BackHandler], because the browser owns the Back order and a
     * handler registered here would outrank it. Leaving the map is the browser's to do.
     */
    embedded: Boolean = false,
    /**
     * What the field is framed to. The projection is fitted to the bounds of the located photos,
     * so if it were fitted to [assets] alone, narrowing them (a time range) would re-fit it and
     * every stamp that stayed would jump. Fitting to this list (and [assets], so nothing is ever
     * placed off the field) keeps the field still while stamps come and go. Must already pass
     * the browser's visibility filter, or the bounds would leak where hidden photos were taken.
     */
    frameAssets: List<MediaAsset> = assets,
) {
    if (!embedded) BackHandler(onBack = onClose)

    val located = remember(assets, geoState.metadataById, focus) {
        val fromIndex = assets.mapNotNull { asset ->
            if (asset.isTrashed) null else geoState.metadataById[asset.id]?.let { asset to it }
        }
        // The focused photo may be located by its own EXIF and absent from the geo index (the
        // index is built on demand); it is still the one place this map is about, so it is
        // added -- never duplicated -- from the focus itself.
        if (focus != null && fromIndex.none { it.first.id == focus.assetId }) {
            val self = assets.firstOrNull { it.id == focus.assetId }
            if (self != null && PlaceMorph.isDrawable(focus.latitude, focus.longitude)) {
                fromIndex + (self to GeoMetadata(self.id, focus.latitude, focus.longitude, null, null))
            } else {
                fromIndex
            }
        } else {
            fromIndex
        }
    }
    val bounds = remember(located, assets, frameAssets, geoState.metadataById) {
        val framed = if (frameAssets === assets) {
            located
        } else {
            (frameAssets + assets).mapNotNull { asset ->
                if (asset.isTrashed) null else geoState.metadataById[asset.id]?.let { asset to it }
            }
        }
        StampMapProjection.boundsOf(framed.map { it.second.latitude to it.second.longitude })
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
        if (embedded && bounds != null) {
            // The library has places but this view has none of them (a time range, a search):
            // still the field, saying so, rather than the "nothing is indexed" screen.
            NothingLocatedHere(modifier)
        } else {
            EmptyMap(
                geoState = geoState,
                onIndexLocations = onIndexLocations,
                onClose = onClose,
                modifier = modifier,
                embedded = embedded,
            )
        }
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

            if (focus != null) {
                // Every located photo as one filled dot in its own colour, drawn in a single
                // Canvas pass: thousands of dots are one draw call each, no composables, and the
                // pan/zoom state is read inside the draw lambda so a drag re-draws without
                // recomposing. The focused photo is left out of the dots -- it is the tile.
                val dotRadiusPx = with(density) { FOCUS_DOT_DP.dp.toPx() }
                val dots = remember(located, bounds, focus) {
                    located.filter { it.first.id != focus.assetId }.map { (asset, geo) ->
                        val p = StampMapProjection.project(geo.latitude, geo.longitude, bounds)
                        Triple(p.x, p.y, Color(focus.colorFor(asset.id)))
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    dots.forEach { (x, y, color) ->
                        drawCircle(
                            color = color,
                            radius = dotRadiusPx,
                            center = Offset(
                                x = (x - 0.5f) * widthPx * zoom + widthPx / 2f + panX,
                                y = (y - 0.5f) * heightPx * zoom + heightPx / 2f + panY,
                            ),
                        )
                    }
                }
                val self = located.firstOrNull { it.first.id == focus.assetId }
                if (self != null) {
                    val point = StampMapProjection.project(self.second.latitude, self.second.longitude, bounds)
                    val pinWidthPx = with(density) { PlaceMorph.PIN_SIZE_DP.dp.toPx() }
                    val pinHeightPx = with(density) { PlaceMorph.PIN_GROUP_HEIGHT_DP.dp.toPx() }
                    PlacePin(
                        asset = self.first,
                        modifier = Modifier.offset {
                            IntOffset(
                                x = ((point.x - 0.5f) * widthPx * zoom + widthPx / 2f - pinWidthPx / 2f + panX).roundToInt(),
                                y = ((point.y - 0.5f) * heightPx * zoom + heightPx / 2f - pinHeightPx / 2f + panY).roundToInt(),
                            )
                        },
                    )
                }
            }

            if (focus == null) stamps.forEach { (asset, geo) ->
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

        // Embedded, the browser says how many are on the map in the pill, so there is no header.
        if (!embedded) Row(
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
                    if (focus != null) "WHERE THIS WAS TAKEN" else "MAP",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    // Says both numbers when they differ, so a thinned map never looks like it
                    // silently lost photos. In focus mode nothing is thinned: every dot is real.
                    if (focus != null || stamps.size == located.size) "${located.size} located"
                    else "${stamps.size} of ${located.size} located",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // The immersive half of the reference: selecting a stamp opens it as a poster with the
        // rest of that place's photos beneath it.
        if (focus == null) selected?.let { id ->
            val asset = stamps.firstOrNull { it.first.id == id }?.first
            if (asset != null) {
                val nearby = remember(stamps) { stamps.map { it.first } }
                PlacePoster(
                    asset = asset,
                    nearby = nearby,
                    onOpen = { onOpenAsset(asset, nearby) },
                    onDismiss = { selected = null },
                    // Embedded, the browser's pill and discs float along the bottom edge; the
                    // poster rises above them rather than under.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (embedded) EMBEDDED_CHROME_CLEARANCE.dp else 0.dp),
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
    embedded: Boolean = false,
) {
    // Still a map, still turning (owner, 2026-08-18: *"when there is no location data, you must
    // still show me the map, but keep it slowly spinning to indicate no location yet"*). A blank
    // panel of text where a map should be reads as a broken screen; a map with nothing on it,
    // visibly still turning, reads as one that has looked and found nothing yet.
    Box(modifier.fillMaxSize().background(FIELD)) {
        SpinningField(Modifier.fillMaxSize())

        if (!embedded) Text(
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
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                // Embedded, the map toggle and the pill sit along the bottom edge.
                .padding(bottom = if (embedded) EMBEDDED_CHROME_CLEARANCE.dp else 0.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when {
                    geoState.isIndexing -> "Reading locations…"
                    geoState.scannedCount == 0 -> "Locations have not been read yet"
                    else -> "No photo carries a location yet"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    geoState.isIndexing ->
                        "Checked ${geoState.scannedCount} of ${geoState.totalCount} so far."
                    geoState.scannedCount == 0 ->
                        HelpCopy.Hint.READS_GPS_LOCALLY
                    else ->
                        HelpCopy.Hint.ADD_BY_HAND
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!geoState.isIndexing) {
                Text(
                    if (geoState.scannedCount == 0) "Read locations" else "Check again",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onIndexLocations).padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The embedded map's answer when the view has no located photo but the library has: the same
 * field, still, with one line. Not [EmptyMap], whose copy is about locations not having been read.
 */
@Composable
private fun NothingLocatedHere(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(FIELD)) {
        Graticule()
        Text(
            "Nothing in this view has a location",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        )
    }
}

/** The stylized field, turning slowly on its own axis while there is nothing pinned to it. */
@Composable
private fun SpinningField(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "empty-map-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(EMPTY_SPIN_MILLIS, easing = LinearEasing)),
        label = "empty-map-angle",
    )
    Box(
        modifier
            .graphicsLayer { rotationZ = angle }
            .background(Brush.linearGradient(listOf(FIELD, FIELD_DEEP))),
    ) {
        Graticule()
    }
}

// Fixed on purpose (reference design): the offline stamp map's own ground and paper, not an accent
// role.
private val FIELD = Color(0xFF11141C)
private val FIELD_DEEP = Color(0xFF0A0C12)
private val STAMP_PAPER = Color(0xFFF5F1E8)
private const val STAMP_SIZE = 64

/**
 * How far an embedded map's own bottom-anchored content rises above the edge, to clear the
 * browser's pill and corner discs (a 48dp disc 20dp up, plus a gap).
 */
private const val EMBEDDED_CHROME_CLEARANCE = 80
/** Radius of one located photo's dot in focus mode: filled, no stroke, its own colour. */
private const val FOCUS_DOT_DP = 5
private const val GRATICULE_ROWS = 8

/**
 * The map is thinned to one stamp per cell of a [THIN_CELLS] x [THIN_CELLS] grid: at most 144
 * stamps on screen however large the library is. Chosen so the densest map still reads as separate
 * pinned photographs rather than a solid sheet of them.
 */
private const val THIN_CELLS = 12

/** One turn of the empty field. Slow enough to read as "still looking", not as an animation. */
private const val EMPTY_SPIN_MILLIS = 30_000
private const val MIN_ZOOM = 0.6f
private const val MAX_ZOOM = 6f
