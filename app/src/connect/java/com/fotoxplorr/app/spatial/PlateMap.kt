package com.fotoxplorr.app.spatial

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The connect flavour's plate backdrop: a real street map under the pin, as ONE static image.
 *
 * Fetched only once [settled] is true -- the room fully open and at rest -- and never during the
 * drag, which keeps `PlacePlate`'s own rule (a gesture must not become a network request). It is
 * a `MapSnapshotter` render, not a `MapView`: no live tile fetching while the user looks at it, no
 * GL surface inside a scrolling room, and one bitmap that [PlateMapSnapshots] keeps per photo so
 * re-opening the same photo's details draws the map instantly. The style is OpenFreeMap's dark
 * one, the closest match to the owner's reference.
 *
 * Fades in over the plate's own graticule rather than popping, and draws nothing at all until it
 * has a bitmap, so a failed or slow fetch simply leaves the local plate as it was.
 */
@Composable
fun PlateMapBackdrop(
    assetId: MediaId,
    latitude: Double,
    longitude: Double,
    settled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(assetId, latitude, longitude) {
        mutableStateOf(PlateMapSnapshots.cached(assetId, latitude, longitude))
    }
    LaunchedEffect(settled, sizePx, assetId, latitude, longitude) {
        if (!settled || sizePx.width <= 0 || sizePx.height <= 0 || bitmap != null) return@LaunchedEffect
        bitmap = PlateMapSnapshots.snapshot(
            context = context,
            assetId = assetId,
            latitude = latitude,
            longitude = longitude,
            sizePx = sizePx,
            density = density,
        )
    }
    val alpha by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = tween(FADE_MILLIS),
        label = "plate-map-fade",
    )
    Box(modifier.onSizeChanged { sizePx = it }) {
        bitmap?.let { map ->
            Image(
                bitmap = map.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize().graphicsLayer { this.alpha = alpha },
                contentScale = ContentScale.Crop,
            )
            // The data is not ours; say whose it is, on the map itself, the way the Places map
            // does in its own subtitle.
            Text(
                text = "© OpenFreeMap · OpenStreetMap",
                color = Color.White.copy(alpha = 0.55f),
                style = TextStyle(fontSize = 9.sp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

/**
 * The connect flavour's full-screen map for one photo's place: the real MapLibre screen, centred
 * on the photo at street zoom, every other located photo a filled colour dot and this one its
 * white-bordered tile. See [MapFocus].
 */
@Composable
fun ExpandedPhotoMap(
    focus: MapFocus,
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onOpenAsset: (MediaAsset) -> Unit,
    onClose: () -> Unit,
) {
    RichPhotoMapScreen(
        assets = assets,
        geoState = geoState,
        onIndexLocations = {},
        onOpenAsset = { asset, _ -> onOpenAsset(asset) },
        onClose = onClose,
        focus = focus,
    )
}

/**
 * One cached snapshot per photo. Keyed on the photo, verified against the coordinate and size it
 * was rendered for, so a hand-placed location that moves re-renders rather than serving the old
 * place.
 */
object PlateMapSnapshots {

    private class Entry(
        val latitude: Double,
        val longitude: Double,
        val width: Int,
        val height: Int,
        val bitmap: Bitmap,
    )

    private val cache = LruCache<Long, Entry>(MAX_ENTRIES)

    fun cached(assetId: MediaId, latitude: Double, longitude: Double): Bitmap? = synchronized(cache) {
        cache.get(assetId.value)?.takeIf { it.latitude == latitude && it.longitude == longitude }?.bitmap
    }

    /**
     * Renders the plate's map, or null when it cannot (no native library on this device, the
     * style unreachable, the snapshotter refusing). Runs on the main dispatcher because the
     * snapshotter is a main-thread object; the actual rendering happens on MapLibre's own thread
     * and this only suspends until the callback.
     */
    suspend fun snapshot(
        context: Context,
        assetId: MediaId,
        latitude: Double,
        longitude: Double,
        sizePx: IntSize,
        density: Float,
    ): Bitmap? = withContext(Dispatchers.Main.immediate) {
        val appContext = context.applicationContext
        // MapLibre.getInstance loads the native library; on a device without it (or in a JVM
        // test) that is an UnsatisfiedLinkError, which runCatching catches as a Throwable.
        if (runCatching { MapLibre.getInstance(appContext) }.isFailure) return@withContext null
        val ratio = density.coerceAtLeast(1f)
        val widthDp = (sizePx.width / ratio).roundToInt().coerceAtLeast(1)
        val heightDp = (sizePx.height / ratio).roundToInt().coerceAtLeast(1)
        val options = runCatching {
            MapSnapshotter.Options(widthDp, heightDp)
                .withStyleBuilder(Style.Builder().fromUri(DARK_STYLE))
                .withCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(latitude, longitude))
                        .zoom(PLATE_ZOOM)
                        .build(),
                )
                .withPixelRatio(ratio)
                .withLogo(false)
                .withAttribution(false)
        }.getOrNull() ?: return@withContext null
        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            val snapshotter = runCatching { MapSnapshotter(appContext, options) }.getOrElse {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            runCatching {
                snapshotter.start(
                    { snapshot -> if (continuation.isActive) continuation.resume(snapshot.bitmap) },
                    { _ -> if (continuation.isActive) continuation.resume(null) },
                )
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
            continuation.invokeOnCancellation { runCatching { snapshotter.cancel() } }
        }
        if (bitmap != null) {
            synchronized(cache) {
                cache.put(assetId.value, Entry(latitude, longitude, sizePx.width, sizePx.height, bitmap))
            }
        }
        bitmap
    }

    /** OpenFreeMap's dark style -- the owner's reference is a dark street map. */
    private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

    /** Street zoom: a 400 dp plate spans roughly a neighbourhood, like the reference. */
    private const val PLATE_ZOOM = 15.4

    private const val MAX_ENTRIES = 8
}

private const val FADE_MILLIS = 420
