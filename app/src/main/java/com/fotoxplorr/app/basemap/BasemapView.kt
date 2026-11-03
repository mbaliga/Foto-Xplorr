package com.fotoxplorr.app.basemap

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/** The bundled world, read once per process from `assets/basemap/world.fxmap`. */
object BasemapHolder {
    @Volatile private var loaded: SimplifiedBasemap? = null

    fun load(context: Context): SimplifiedBasemap? {
        loaded?.let { return it }
        return runCatching {
            val bytes = context.applicationContext.assets.open("basemap/world.fxmap").use { it.readBytes() }
            SimplifiedBasemap(BasemapCodec.decode(bytes))
        }.getOrNull()?.also { loaded = it }
    }
}

/** Land, sea and ink from the app's own colour scheme, so the map follows dark and light. */
data class BasemapColors(
    val sea: Color,
    val land: Color,
    val coast: Color,
    val border: Color,
    val label: Color,
    val dot: Color,
) {
    companion object {
        fun from(background: Color, onBackground: Color, accent: Color): BasemapColors {
            val dark = background.luminance() < 0.5f
            val sea = lerp(background, accent, if (dark) 0.10f else 0.14f)
            return BasemapColors(
                sea = sea,
                land = lerp(background, onBackground, if (dark) 0.16f else 0.07f),
                coast = onBackground.copy(alpha = 0.45f),
                border = onBackground.copy(alpha = 0.22f),
                label = onBackground.copy(alpha = 0.85f),
                dot = onBackground.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
fun rememberBasemapColors(): BasemapColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.background, scheme.onBackground, scheme.primary) {
        BasemapColors.from(scheme.background, scheme.onBackground, scheme.primary)
    }
}

/** The bundled basemap, or null until it has been read (or when the asset is missing). */
@Composable
fun rememberBasemap(): SimplifiedBasemap? {
    val context = LocalContext.current
    val state = produceState<SimplifiedBasemap?>(null, context) {
        value = withContext(Dispatchers.IO) { BasemapHolder.load(context) }
    }
    return state.value
}

/** The imported raster MBTiles file, reopened when it is imported or deleted; null if none. */
@Composable
fun rememberImportedTiles(): TileSource? {
    val context = LocalContext.current
    val version = MapFileStore.changes.get()
    val state = produceState<TileSource?>(null, context, version) {
        value = withContext(Dispatchers.IO) { MapFileStore.open(context) }
        awaitDispose { value?.close() }
    }
    return state.value
}

private class PathSet(val land: Path, val lakes: Path, val borders: Path, val worldPx: Double)

private fun buildPaths(s: SimplifiedBasemap.Simplified, bucket: Int): PathSet {
    val w = Simplify.REFERENCE_WORLD_PX * 2.0.pow(bucket)
    fun ring(path: Path, xy: IntArray, close: Boolean) {
        if (xy.size < 4) return
        for (i in 0 until xy.size / 2) {
            val x = (MapMath.lonToX(xy[2 * i] / 100.0) * w).toFloat()
            val y = (MapMath.latToY(xy[2 * i + 1] / 100.0) * w).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (close) path.close()
    }
    val land = Path().apply { fillType = PathFillType.EvenOdd }
    val lakes = Path().apply { fillType = PathFillType.EvenOdd }
    val borders = Path()
    s.land.forEach { ring(land, it, true) }
    s.lakes.forEach { ring(lakes, it, true) }
    s.borders.forEach { ring(borders, it, false) }
    return PathSet(land, lakes, borders, w)
}

/** Decoded tiles of the imported file, loaded off the draw thread; [version] changes as they arrive. */
private class TileImages(private val source: TileSource, private val scope: CoroutineScope) {
    var version by mutableIntStateOf(0)
        private set
    private val images = ConcurrentHashMap<TileAddress, ImageBitmap>()
    private val missing = ConcurrentHashMap.newKeySet<TileAddress>()
    private val inFlight = ConcurrentHashMap.newKeySet<TileAddress>()

    fun get(a: TileAddress): ImageBitmap? {
        images[a]?.let { return it }
        if (a in missing || !inFlight.add(a)) return null
        scope.launch(Dispatchers.IO) {
            val bytes = source.tile(a.z, a.x, a.y)
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            if (bmp != null) images[a] = bmp else missing.add(a)
            inFlight.remove(a)
            if (bmp != null) version++
        }
        return null
    }
}

/**
 * Draws the world: sea, land, lakes, imported raster tiles over the land when the zoom is past the
 * bundled data's detail, coastlines, borders, and (when [showCities]) the largest cities named by
 * rank without overlap. The camera is read in the draw phase, so a pan or pinch redraws without
 * recomposing. Hit-testing and gestures are the caller's: see [mapGestures].
 */
@Composable
fun BasemapCanvas(
    camera: () -> MapCamera,
    modifier: Modifier = Modifier,
    tiles: TileSource? = null,
    showCities: Boolean = true,
) {
    val basemap = rememberBasemap()
    val colors = rememberBasemapColors()
    val measurer = rememberTextMeasurer()
    val paths = remember(basemap) { ConcurrentHashMap<Int, PathSet>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val images = remember(tiles) { tiles?.let { TileImages(it, scope) } }
    LaunchedEffect(basemap) {
        if (basemap != null) withContext(Dispatchers.Default) {
            for (b in 0..2) paths.getOrPut(b) { buildPaths(basemap.at(b), b) }
        }
    }
    val labelStyle = remember(colors) { TextStyle(color = colors.label, fontSize = 11.sp) }

    Canvas(modifier) {
        drawRect(colors.sea)
        if (basemap == null) return@Canvas
        val cam = camera()
        val vp = MapViewport(size.width.toDouble(), size.height.toDouble())
        val wp = MapMath.worldPx(cam.level, vp)
        val bucket = SimplifiedBasemap.bucketFor(wp)
        val set = paths.getOrPut(bucket) { buildPaths(basemap.at(bucket), bucket) }
        val s = (wp / set.worldPx).toFloat()
        val tx = (vp.widthPx / 2.0 - MapMath.lonToX(cam.centerLon) * wp).toFloat()
        val ty = (vp.heightPx / 2.0 - MapMath.latToY(cam.centerLat) * wp).toFloat()
        val hair = 1.dp.toPx() / s
        withTransform({ translate(tx, ty); scale(s, s, Offset.Zero) }) {
            drawPath(set.land, colors.land)
            drawPath(set.lakes, colors.sea)
        }
        if (tiles != null && images != null) drawTiles(tiles, images, cam, vp, wp)
        withTransform({ translate(tx, ty); scale(s, s, Offset.Zero) }) {
            drawPath(set.borders, colors.border, style = Stroke(hair))
            drawPath(set.land, colors.coast, style = Stroke(hair * 1.1f))
        }
        if (showCities) drawCities(basemap.basemap.cities, cam, vp, measurer, labelStyle, colors)
    }
}

/** Imported tiles are drawn once the world is wide enough that the bundled outlines are coarse. */
const val TILES_FROM_TILE_ZOOM = 6.0

private fun DrawScope.drawTiles(src: TileSource, images: TileImages, cam: MapCamera, vp: MapViewport, wp: Double) {
    @Suppress("UNUSED_VARIABLE") val touch = images.version // re-draw as tiles arrive
    if (MapMath.tileZoom(cam.level, vp) < TILES_FROM_TILE_ZOOM) return
    val z = MbTilesMath.zoomFor(wp, src)
    for ((address, rect) in MbTilesMath.visibleTiles(cam, vp, z)) {
        val img = images.get(address) ?: continue
        val size = rect[2]
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(rect[0].toInt(), rect[1].toInt()),
            dstSize = IntSize(Math.ceil(size).toInt() + 1, Math.ceil(size).toInt() + 1),
        )
    }
}

private fun DrawScope.drawCities(
    cities: List<BasemapCity>,
    cam: MapCamera,
    vp: MapViewport,
    measurer: TextMeasurer,
    style: TextStyle,
    colors: BasemapColors,
) {
    val limit = LabelPlacer.maxRankForLevel(cam.level, cities.size)
    val candidates = ArrayList<LabelCandidate>()
    val laidOut = ArrayList<TextLayoutResult>()
    val dots = ArrayList<Offset>()
    for (i in 0 until limit) {
        val c = cities[i]
        val p = MapMath.toScreen(cam, vp, c.lon, c.lat)
        if (p.x < 0 || p.y < 0 || p.x > vp.widthPx || p.y > vp.heightPx) continue
        val layout = measurer.measure(c.name, style, maxLines = 1)
        candidates += LabelCandidate(c.rank, p.x.toFloat(), p.y.toFloat(), layout.size.width.toFloat(), layout.size.height.toFloat())
        laidOut += layout
        dots += Offset(p.x.toFloat(), p.y.toFloat())
    }
    val placed = LabelPlacer.place(candidates, size.width, size.height)
    for (l in placed) {
        drawCircle(colors.dot, radius = 2.dp.toPx(), center = dots[l.index])
        drawText(laidOut[l.index], topLeft = Offset(l.left, l.top))
    }
}

/**
 * Pan, pinch and double-tap zoom for a [BasemapCanvas], and taps. [onePointerPans] false leaves a
 * one-finger drag alone (not consumed), for a map inside a scrolling room that must still scroll;
 * two fingers always zoom and pan. A single tap is reported after the double-tap window, so a
 * double tap that zooms is never also a tap. A gesture another handler has already consumed (a pin
 * being dragged) is ignored. Edge rule (TRAPS #72): the shell takes horizontal drags that start
 * within 56 dp of the screen sides, so every drag here has a button alternative in the callers.
 */
fun Modifier.mapGestures(
    camera: () -> MapCamera,
    viewport: () -> MapViewport,
    onCamera: (MapCamera) -> Unit,
    maxLevel: () -> Double = { MapMath.MAX_LEVEL },
    onePointerPans: Boolean = true,
    onTap: (Offset) -> Unit = {},
): Modifier = pointerInput(onePointerPans) {
    coroutineScope {
        var pendingTap: Job? = null
        var lastTapAt = 0L
        var lastTapPos = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var movedPastSlop = false
            var total = Offset.Zero
            var cancelled = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.any { it.isConsumed }) { cancelled = true; break }
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                val multi = pressed >= 2
                if (multi || onePointerPans) {
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    total += pan
                    if (!movedPastSlop && (multi || total.getDistance() > viewConfiguration.touchSlop)) movedPastSlop = true
                    if (movedPastSlop) {
                        var cam = camera()
                        val vp = viewport()
                        val c = event.calculateCentroid()
                        if (zoom != 1f && c != Offset.Unspecified) {
                            cam = MapMath.zoomedAt(cam, vp, cam.level + kotlin.math.log2(zoom.toDouble()), c.x.toDouble(), c.y.toDouble(), maxLevel())
                        }
                        cam = MapMath.panned(cam, vp, pan.x.toDouble(), pan.y.toDouble(), maxLevel())
                        onCamera(cam)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } else if (!movedPastSlop) {
                    val moved = (event.changes.first().position - down.position).getDistance()
                    if (moved > viewConfiguration.touchSlop) { cancelled = true; break }
                }
            }
            if (!cancelled && !movedPastSlop) {
                val now = down.uptimeMillis
                val isDouble = pendingTap != null && now - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
                    (down.position - lastTapPos).getDistance() < viewConfiguration.touchSlop * 6
                if (isDouble) {
                    pendingTap?.cancel()
                    pendingTap = null
                    onCamera(MapMath.zoomedAt(camera(), viewport(), camera().level + 1.0, down.position.x.toDouble(), down.position.y.toDouble(), maxLevel()))
                } else {
                    lastTapAt = now
                    lastTapPos = down.position
                    val at = down.position
                    pendingTap = launch {
                        delay(viewConfiguration.doubleTapTimeoutMillis)
                        pendingTap = null
                        onTap(at)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChanged(): Boolean =
    position != previousPosition
