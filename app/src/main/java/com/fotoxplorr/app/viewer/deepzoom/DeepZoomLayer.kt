package com.fotoxplorr.app.viewer.deepzoom

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.viewer.ZoomViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Sharp tiles over the ordinary picture while the viewer is zoomed. It lives INSIDE the viewer's zoom
 * layer (the same box the photo and the text layer share), so it is positioned in layout
 * coordinates -- the fitted image rectangle -- and the layer's own scale/pan carries it, with no
 * second transform to keep in step. The picture beneath keeps drawing, so an unloaded tile
 * shows the soft version instead of a hole, and a decode that fails shows exactly what the viewer
 * showed before this existed. Because the base still draws under a tile, a tile with alpha would be
 * composited twice; such a file stops tiling ([DeepZoomPolicy.tileDrawable]). [onLiveChanged] says
 * whether tiles really run (decoder open, drawable), which is what the viewer's ceiling follows.
 *
 * What is tested on the JVM is every decision (level, window, cache, orientation); that this
 * composable composes, that tiles land on the right pixels and that panning a 100 MP photo holds
 * 60 fps are device checks. Nothing here runs unless [enabled] ([DeepZoomPolicy.tilesWorthRunning]).
 */
@OptIn(FlowPreview::class)
@Composable
internal fun DeepZoomLayer(
    asset: MediaAsset,
    enabled: Boolean,
    containerSize: IntSize,
    scale: () -> Float,
    offsetX: () -> Float,
    offsetY: () -> Float,
    modifier: Modifier = Modifier,
    onLiveChanged: (Boolean) -> Unit = {},
) {
    if (containerSize.width <= 0 || containerSize.height <= 0) return
    val context = LocalContext.current
    var decoder by remember(asset.contentUriString, asset.dateModifiedSeconds) { mutableStateOf<RegionTileDecoder?>(null) }
    val cache = remember(asset.contentUriString, asset.dateModifiedSeconds) {
        TileCache<ImageBitmap>(CACHE_BYTES) { (it.width.toLong() * it.height * 4L) }
    }
    var version by remember(cache) { mutableIntStateOf(0) }
    // Set when the open's probe (or, as a backstop, a tile) finds alpha in a format that may carry it:
    // nothing is drawn over the base picture (DeepZoomPolicy.tileDrawable) and tiles are never live.
    var translucent by remember(asset.contentUriString, asset.dateModifiedSeconds) { mutableStateOf(false) }
    val live = decoder != null && !translucent
    val reportLive by rememberUpdatedState(onLiveChanged)
    LaunchedEffect(live) { reportLive(live) }

    DisposableEffect(asset.contentUriString, asset.dateModifiedSeconds) {
        onDispose {
            // Off the main thread: recycle() waits for a region decode still running on IO.
            decoder?.closeInBackground()
            cache.clear()
            reportLive(false)
        }
    }
    // Once the layer has been wanted it keeps its decoder and tiles while merely disabled (a pinch's
    // rotation noise), so re-enabling costs nothing; it only opens the file the first time it is wanted.
    var wanted by remember(asset.contentUriString, asset.dateModifiedSeconds) { mutableStateOf(false) }
    if (enabled) wanted = true
    LaunchedEffect(asset.contentUriString, asset.dateModifiedSeconds, wanted) {
        if (!wanted || decoder != null || translucent) return@LaunchedEffect
        // NonCancellable: an open that finishes after this effect was cancelled must still be closed,
        // not thrown away with its file descriptor and native decoder.
        val opened = withContext(NonCancellable + Dispatchers.IO) {
            RegionTileDecoder.open(context, android.net.Uri.parse(asset.contentUriString), asset.mimeType)
        }
        when {
            !isActive -> opened?.closeInBackground()
            // Alpha found by the open's probe: never reported live, so the ceiling and the 1:1
            // double-tap never offer a deep zoom that the first tile would take back.
            opened != null && !opened.tilesDrawable -> {
                opened.closeInBackground()
                translucent = true
            }
            else -> decoder = opened
        }
    }

    val d = decoder
    if (d != null && enabled && !translucent) {
        val oneToOne = DeepZoomPolicy.oneToOneScale(
            d.width, d.height, containerSize.width.toFloat(), containerSize.height.toFloat(),
        )
        // Re-plan when the window settles: a debounce so a fling does not queue a decode per frame, and
        // collectLatest so a window that moved on cancels the decode it no longer needs.
        LaunchedEffect(d, containerSize) {
            snapshotFlow { Triple(scale(), offsetX(), offsetY()) }
                .debounce(SETTLE_MS)
                .collectLatest { (s, ox, oy) ->
                    val sample = TilePyramid.sampleFor(DeepZoomPolicy.pixelsPerSourcePx(s, oneToOne))
                    // At fit the base picture is already as sharp as the screen needs.
                    if (s <= 1.05f) return@collectLatest
                    val keys = ZoomViewport.withFractions(
                        d.width, d.height, containerSize.width.toFloat(), containerSize.height.toFloat(), s, ox, oy,
                    ) { l, t, r, b -> TilePyramid.tilesCovering(d.width, d.height, l, t, r, b, sample, marginTiles = 1) }
                    for (key in keys) {
                        if (!isActive) return@collectLatest
                        if (cache.contains(key)) continue
                        val bmp: Bitmap = withContext(Dispatchers.IO) { d.decode(key) } ?: continue
                        if (!DeepZoomPolicy.tileDrawable(asset.mimeType, bmp.hasAlpha())) {
                            bmp.recycle()
                            cache.clear()
                            translucent = true
                            return@collectLatest
                        }
                        cache.put(key, bmp.asImageBitmap())
                        version++
                    }
                }
        }
        Canvas(modifier = modifier) {
            @Suppress("UNUSED_EXPRESSION") version // read: a new tile repaints
            drawTiles(cache, d.width, d.height, scale(), offsetX(), offsetY(), oneToOne, containerSize)
        }
    }
}

private fun DrawScope.drawTiles(
    cache: TileCache<ImageBitmap>,
    srcW: Int, srcH: Int,
    scale: Float, offsetX: Float, offsetY: Float, oneToOne: Float,
    container: IntSize,
) {
    if (scale <= 1.05f) return
    val aspect = ZoomViewport.aspect(srcW, srcH)
    val cw = container.width.toFloat()
    val ch = container.height.toFloat()
    val fittedW = if (aspect > cw / ch) cw else ch * aspect
    val fittedH = fittedW / aspect
    val originX = (cw - fittedW) / 2f
    val originY = (ch - fittedH) / 2f
    val perSrcX = fittedW / srcW
    val perSrcY = fittedH / srcH
    // Coarse first, fine on top: a finer tile that has landed covers the coarser one beneath it.
    val wanted = TilePyramid.sampleFor(DeepZoomPolicy.pixelsPerSourcePx(scale, oneToOne))
    val keys = cache.keys().filter { it.sample >= wanted }.sortedByDescending { it.sample }
    ZoomViewport.withFractions(srcW, srcH, cw, ch, scale, offsetX, offsetY) { l, t, r, b ->
        for (key in keys) {
            val rect = TilePyramid.rectOf(key, srcW, srcH)
            if (rect.right <= l * srcW || rect.left >= r * srcW || rect.bottom <= t * srcH || rect.top >= b * srcH) continue
            val image = cache[key] ?: continue
            drawImage(
                image = image,
                dstOffset = IntOffset((originX + rect.left * perSrcX).toInt(), (originY + rect.top * perSrcY).toInt()),
                dstSize = IntSize(
                    ((rect.width) * perSrcX + 1f).toInt().coerceAtLeast(1),
                    ((rect.height) * perSrcY + 1f).toInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private const val SETTLE_MS = 70L
private const val CACHE_BYTES = 64L * 1024L * 1024L
