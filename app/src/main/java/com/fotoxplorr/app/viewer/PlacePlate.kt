package com.fotoxplorr.app.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.spatial.PlateMapBackdrop

/**
 * The place plate: where this photo was taken, and the surface its thumbnail flies into as the
 * details room opens. Measured off the owner's reference (6.png): a full-width rounded square
 * (radius [PLATE_CORNER_DP]) with the photo shrunk to an [PlaceMorph.PIN_SIZE_DP] tile that has a
 * [PlaceMorph.PIN_BORDER_DP] white border and a small white pointer under it, centred on the
 * location.
 *
 * **What draws during the drag, and what draws after it.** While the room is being pulled, the
 * plate is a *coordinate* visualisation drawn from the photo's own GPS fix — a graticule and the
 * pin, rendered locally. It deliberately downloads nothing then: the room opens on a drag, and a
 * surface that reached for map tiles every time a finger moved would turn a gesture into a
 * network request and leak the user's photo locations to a tile server for the privilege. Only
 * once the room is fully open AND settled ([settled]) does the connect flavour fade a real map in
 * under the pin — a one-shot static snapshot, cached per photo, never a live tile-fetching view —
 * through [PlateMapBackdrop], whose offline implementation draws nothing at all, so that build
 * keeps the graticule look and performs no network I/O. That is the `PhotoMapEntry` seam again:
 * one call, two flavours.
 *
 * The pin sits at the plate's centre because the plate is centred on the photo. The *graticule*
 * is what carries the actual position — its lines fall where the whole steps really land either
 * side of the coordinate, so two photos a street apart get visibly different grids.
 *
 * @param reveal how open the room is, 0..1, read on the draw pass. A lambda rather than a value
 *   so a drag re-draws the flight without recomposing the room around it.
 * @param settled true once the room has come to rest fully open with no finger on it; the only
 *   moment the backdrop may fetch anything. See the viewer for how it is derived.
 * @param onExpand a tap on the plate, with the plate's bounds in root coordinates so the caller
 *   can grow a full-screen map out of exactly this rectangle. Null leaves the plate inert.
 * @param mapBackdrop what to draw under the pin once settled. Defaults to the flavour's own
 *   [PlateMapBackdrop]; the render test substitutes a stand-in because MapLibre cannot render on
 *   the JVM.
 * @param flightActive true when a [PhotoToPinFlight] is carrying the photo to this pin. The tile
 *   then does NOT play its own hero shrink (two copies of the photo shrinking at once is the
 *   thing this flag exists to stop): it stays at 1x, hidden until the flier hands off to it. False
 *   (the default) keeps the original in-plate hero, which is what previews and render tests use.
 * @param flightFlies meaningful only with [flightActive]. False says the flier cannot reach this
 *   pin (the room is scrolled so the pin is out of reach), so the tile fades in in place instead
 *   of waiting for a flier that is not coming. See [PlaceMorph.canFly].
 * @param onPinPositioned the pin tile's bounds in ROOT coordinates, reported whenever its layout
 *   position changes (which, because the whole room slides during the pull, is every frame of
 *   it). The room converts this to room-local coordinates and de-duplicates it; see
 *   [PhotoDetailRoom]. Null measures nothing.
 */
@Composable
fun PlacePlate(
    asset: MediaAsset,
    latitude: Double,
    longitude: Double,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
    onExpand: ((Rect) -> Unit)? = null,
    flightActive: Boolean = false,
    flightFlies: Boolean = true,
    onPinPositioned: ((Rect) -> Unit)? = null,
    mapBackdrop: @Composable BoxScope.(settled: Boolean) -> Unit = { isSettled ->
        PlateMapBackdrop(
            assetId = asset.id,
            latitude = latitude,
            longitude = longitude,
            settled = isSettled,
            modifier = Modifier.matchParentSize(),
        )
    },
) {
    // A plain holder of the plate's layout coordinates, not snapshot state and not a Rect: the
    // plate moves on every frame of the shell's drag (the whole room does), and either writing
    // a MutableState or allocating a Rect each frame would be a cost nobody needs. The tap
    // handler resolves the bounds once, on the tap, from the coordinates kept here.
    val placed = remember { PlateCoordinates() }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(PLATE_ASPECT)
            .onGloballyPositioned { placed.coordinates = it }
            .clip(RoundedCornerShape(PLATE_CORNER_DP.dp))
            .background(PLATE_GROUND)
            .then(
                if (onExpand != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Open the map",
                    ) { placed.coordinates?.takeIf { it.isAttached }?.boundsInRoot()?.let(onExpand) }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = "Where this photo was taken" },
        contentAlignment = Alignment.Center,
    ) {
        // Derived from the measured plate rather than a constant so the flight covers the same
        // proportion of the plate on every screen size.
        val heroScale = PlaceMorph.heroScale(maxWidth.value)

        val latitudeLines = remember(latitude) { PlaceMorph.graticuleFractions(latitude) }
        val longitudeLines = remember(longitude) { PlaceMorph.graticuleFractions(longitude) }

        Canvas(Modifier.fillMaxSize()) {
            val resolved = PlaceMorph.plateAlpha(reveal())
            if (resolved <= 0.01f) return@Canvas
            val ink = GRATICULE.copy(alpha = GRATICULE.alpha * resolved)
            val stroke = GRATICULE_STROKE_DP.dp.toPx()
            // Latitude runs across, longitude runs down: a line of constant latitude is drawn
            // horizontally. Getting this the wrong way round would still look like a grid,
            // which is exactly why it is worth being explicit about.
            latitudeLines.forEach { fraction ->
                val y = size.height * (1f - fraction)
                drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
            longitudeLines.forEach { fraction ->
                val x = size.width * fraction
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            }
        }

        // The real map, if this flavour has one and the room has settled. Drawn OVER the
        // graticule (it is opaque where it exists) and under the pin.
        mapBackdrop(settled)

        RevealingPin(
            asset = asset,
            reveal = reveal,
            heroScale = heroScale,
            mode = PlaceMorph.pinMode(flightActive, flightFlies),
            modifier = Modifier.align(Alignment.Center),
            onTilePositioned = onPinPositioned,
        )
    }
}

/**
 * The pin as the details room reveals it: [PlacePin] wired to the room's [reveal] according to
 * [mode] ([PlaceMorph.PinMode]). Shared by the located plate and [PlaceUnknownPlate] so the two
 * can never arrive differently.
 *
 * @param ghost true for the unknown-location plate's pin: darkened by [PlaceMorph.GHOST_DIM] as
 *   it lands, no pointer (a pointer says "this is a place"), and described as what it is.
 */
@Composable
internal fun RevealingPin(
    asset: MediaAsset,
    reveal: () -> Float,
    heroScale: Float,
    mode: PlaceMorph.PinMode,
    modifier: Modifier = Modifier,
    ghost: Boolean = false,
    onTilePositioned: ((Rect) -> Unit)? = null,
) {
    PlacePin(
        asset = asset,
        modifier = modifier,
        thumbnailScale = { PlaceMorph.pinScale(mode, reveal(), heroScale) },
        landed = { PlaceMorph.plateAlpha(reveal()) },
        tileAlpha = { PlaceMorph.pinTileAlpha(mode, reveal()) },
        pointerGate = { if (ghost) 0f else PlaceMorph.pinPointerGate(mode, reveal()) },
        dim = { if (ghost) PlaceMorph.GHOST_DIM * PlaceMorph.plateAlpha(reveal()) else 0f },
        description = if (ghost) "Photo, location unknown" else "Photo location",
        onTilePositioned = onTilePositioned,
    )
}

/**
 * The pin: the photo as an [PlaceMorph.PIN_SIZE_DP] tile with a white border and a pointer under
 * it, the group centred on where it is placed. Shared by the plate and both expanded maps so the
 * tile a map highlights is the same tile the plate flew the photo into.
 *
 * @param thumbnailScale the tile's scale this frame, read at draw time: [PlaceMorph.heroScale]
 *   at the start of the flight, 1 once landed. The pointer never scales -- it belongs to the
 *   *landed* pin, so it tracks [landed] instead; a tail hanging off a full-size photo mid-air
 *   would read as a speech bubble.
 * @param landed 0..1, how far the plate has resolved: the border and the pointer fade in with it,
 *   so the hero photo starts borderless (it IS the photo the user was just looking at) and gains
 *   its frame as it becomes a pin.
 * @param tileAlpha opacity of the tile (frame and photo), read at draw time. Below 1 while a
 *   [PhotoToPinFlight] still carries the photo and this tile waits beneath it.
 * @param pointerGate a further multiplier on the pointer's opacity, 0 to hide it outright.
 * @param dim 0..1 black laid over the whole tile (frame included): how a "ghost" pin, for a photo
 *   with no place, is told apart from a real one.
 * @param description the tile's accessibility description.
 * @param onTilePositioned the tile's bounds in ROOT coordinates, measured BEFORE the tile's own
 *   scale layer, so it reports the 80 dp layout tile and not the hero-scaled one. Fires on every
 *   position change; callers that only want it once must de-duplicate.
 */
@Composable
fun PlacePin(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
    thumbnailScale: () -> Float = { 1f },
    landed: () -> Float = { 1f },
    tileAlpha: () -> Float = { 1f },
    pointerGate: () -> Float = { 1f },
    dim: () -> Float = { 0f },
    description: String = "Photo location",
    onTilePositioned: ((Rect) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(PlaceMorph.PIN_SIZE_DP.dp)
            .height(PlaceMorph.PIN_GROUP_HEIGHT_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(PlaceMorph.PIN_SIZE_DP.dp)
                // Measured ahead of the layer below on purpose: a coordinate read after a
                // graphicsLayer would be the hero-scaled tile, and the flight needs to know where
                // the tile LANDS, not where it currently is.
                .then(
                    if (onTilePositioned != null) {
                        Modifier.onGloballyPositioned { onTilePositioned(it.boundsInRoot()) }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer {
                    val scale = thumbnailScale()
                    scaleX = scale
                    scaleY = scale
                    alpha = tileAlpha().coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(PlaceMorph.PIN_CORNER_DP.dp))
                // The border is a white ground showing through a padded, clipped photo -- the
                // way a real frame works -- so it scales with the tile and stays crisp at 1x.
                .drawBehind { drawRect(Color.White.copy(alpha = landed().coerceIn(0f, 1f))) }
                // Over frame AND photo, so a ghost pin darkens as one object.
                .drawWithContent {
                    drawContent()
                    val darkness = dim().coerceIn(0f, 1f)
                    if (darkness > 0f) drawRect(Color.Black.copy(alpha = darkness))
                }
                .padding(PlaceMorph.PIN_BORDER_DP.dp)
                .clip(RoundedCornerShape((PlaceMorph.PIN_CORNER_DP - PlaceMorph.PIN_BORDER_DP).dp))
                .semantics { contentDescription = description },
        ) {
            MediaImage(
                asset = asset,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .width(PlaceMorph.POINTER_WIDTH_DP.dp)
                .height(PlaceMorph.POINTER_HEIGHT_DP.dp)
                // The pointer appears only once the tile has all but landed: it hangs off the
                // pin's bottom edge, and while the photo is still large that edge is nowhere
                // near here -- a triangle floating mid-photo reads as a stray mark.
                .graphicsLayer {
                    alpha = PlaceMorph.pointerAlpha(thumbnailScale(), landed()) *
                        pointerGate().coerceIn(0f, 1f)
                }
                .drawBehind {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2f, size.height)
                        close()
                    }
                    drawPath(path, Color.White)
                },
        )
    }
}

/** The plate's most recent layout coordinates; see [PlacePlate] for why this is not state. */
private class PlateCoordinates {
    var coordinates: LayoutCoordinates? = null
}

/** Square, like the reference's 400 x 400. */
internal const val PLATE_ASPECT = 1f
internal const val PLATE_CORNER_DP = 12
private const val GRATICULE_STROKE_DP = 1

// Fixed on purpose (reference design): map-plate neutrals and the white pin frame, measured off the
// reference.
internal val PLATE_GROUND = Color(0xFF12161C)
internal val GRATICULE = Color(0xFF2E3946)
