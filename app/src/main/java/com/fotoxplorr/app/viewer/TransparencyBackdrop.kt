package com.fotoxplorr.app.viewer

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.TransparencyBackground
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.AlphaSniffer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the viewer draws behind a photo whose file declares transparency (owner, #17): the
 * grey-and-white grid, a flat white, or a flat black ([TransparencyBackground]).
 *
 * Drawn INSIDE the viewer's zoom layer, behind the photo, over the photo's own fitted rectangle
 * ([rect], in the layer's coordinates, which are the viewer's: the photo fills the layer with
 * `ContentScale.Fit`). Inside the layer means it pans, zooms and turns with the picture, and a
 * pinch makes the squares bigger like the picture they belong to; keeping them screen-sized would
 * need a matrix-aware shader for no gain anyone asked for. The letterbox around the photo is left
 * as the viewer's black.
 */
@Composable
internal fun TransparencyBackdrop(mode: TransparencyBackground, rect: Rect, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    // One shader for the life of the screen at this density: the draw below is a single textured
    // rectangle, so it costs nothing per frame under a pan.
    val brush = remember(density) { checkerBrush(density) }
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                if (rect.width <= 0f || rect.height <= 0f) return@drawBehind
                when (mode) {
                    TransparencyBackground.WHITE -> drawRect(Color.White, rect.topLeft, rect.size)
                    TransparencyBackground.BLACK -> drawRect(Color.Black, rect.topLeft, rect.size)
                    // Translated so the pattern is anchored to the picture's corner, not the screen's.
                    TransparencyBackground.CHECKERBOARD -> translate(rect.left, rect.top) {
                        drawRect(brush, size = rect.size)
                    }
                }
            },
    )
}

/** A repeating 2 x 2 tile of [CHECKER_CELL_DP] squares, light then dark. */
private fun checkerBrush(density: Float): ShaderBrush {
    val cell = (CHECKER_CELL_DP * density).roundToInt().coerceAtLeast(2)
    val tile = ImageBitmap(cell * 2, cell * 2)
    val canvas = androidx.compose.ui.graphics.Canvas(tile)
    val paint = Paint()
    for (row in 0..1) for (column in 0..1) {
        paint.color = if ((row + column) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK
        canvas.drawRect(
            (column * cell).toFloat(), (row * cell).toFloat(),
            ((column + 1) * cell).toFloat(), ((row + 1) * cell).toFloat(),
            paint,
        )
    }
    return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

/** Side of one checkerboard square, in dp. */
internal const val CHECKER_CELL_DP = 8

/** The two squares of the grid. Fixed, like the zoom navigator's blues: a convention, not a theme colour. */
internal val CHECKER_LIGHT = Color(0xFFFFFFFF)
internal val CHECKER_DARK = Color(0xFFCCCCCC)

/**
 * The chrome button's glyph: a swatch of the CURRENT choice, so the picture says what is behind
 * the photo now. Distinct by shape and fill as well as colour (the grid is a grid, white is a
 * filled disc, black is a disc with a white ring), each inside a white ring that keeps it visible
 * on the chip's dark disc.
 */
@Composable
internal fun TransparencySwatch(mode: TransparencyBackground, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val ringWidth = 1.5.dp.toPx()
        val inner = radius - ringWidth
        val centre = Offset(size.width / 2f, size.height / 2f)
        when (mode) {
            TransparencyBackground.WHITE -> drawCircle(Color.White, inner, centre)
            TransparencyBackground.BLACK -> drawCircle(Color.Black, inner, centre)
            TransparencyBackground.CHECKERBOARD -> {
                val disc = Path().apply {
                    addOval(Rect(centre, inner))
                }
                clipPath(disc) {
                    val cells = 4
                    val cell = (inner * 2f) / cells
                    val left = centre.x - inner
                    val top = centre.y - inner
                    for (row in 0 until cells) for (column in 0 until cells) {
                        drawRect(
                            color = if ((row + column) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK,
                            topLeft = Offset(left + column * cell, top + row * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
        drawCircle(Color.White, radius - ringWidth / 2f, centre, style = Stroke(ringWidth))
    }
}

/** The choice's name as the chrome button and the view-options row say it: "grid", "white", "black". */
internal fun TransparencyBackground.label(): String = when (this) {
    TransparencyBackground.CHECKERBOARD -> "grid"
    TransparencyBackground.WHITE -> "white"
    TransparencyBackground.BLACK -> "black"
}

/** [label] with a capital, for the row in the view options. */
internal fun TransparencyBackground.title(): String = label().replaceFirstChar { it.uppercase() }

/** Test tag on the chrome row's transparent-background button. */
internal const val TRANSPARENCY_BUTTON_TAG = "viewer-transparency-button"

/** Where the backdrop is drawn: pure, so the fallbacks can be pinned without a screen. */
internal object TransparencyBackdropGeometry {

    /**
     * The photo's fitted rectangle in a [containerWidth] x [containerHeight] container: from the
     * size the image DECODED to ([decoded], what is really on screen, and the only size an SVG has
     * when MediaStore reports 0 x 0) when it has landed, else [fallback] (the viewer's rectangle
     * from the file's own EXIF / stored size). An unspecified or non-positive [decoded] is ignored.
     */
    fun rect(containerWidth: Float, containerHeight: Float, decoded: Size?, fallback: Rect): Rect {
        if (decoded == null || !(decoded.width > 0f) || !(decoded.height > 0f)) return fallback
        return PlaceMorph.fitRect(containerWidth, containerHeight, decoded.width, decoded.height)
    }
}

/**
 * Whether a photo's FILE declares transparency, for the viewer to decide if a backdrop is worth
 * drawing (owner, #17). A bounded header read through [AlphaSniffer] -- no pixel is decoded -- off
 * the main thread, and remembered per photo so paging back and forth reads the file once.
 *
 * Reports what the file DECLARES: an RGBA PNG whose pixels are all opaque answers `true` and the
 * button it brings up changes nothing visible, which is harmless and costs no decode.
 */
internal object TransparencyProbe {

    /** Header bytes read: all [AlphaSniffer] will look at. */
    const val HEAD_BYTES = AlphaSniffer.MAX_READ_BYTES

    private const val CACHE_ENTRIES = 256

    // Keyed on the id AND the file's modified time: an overwrite changes the bytes under one id.
    private val cache = LruCache<String, Boolean>(CACHE_ENTRIES)

    private fun key(asset: MediaAsset) = "${asset.id.value}@${asset.dateModifiedSeconds}"

    /** The remembered answer, or null when this photo has not been probed yet. */
    fun cached(asset: MediaAsset): Boolean? = synchronized(cache) { cache.get(key(asset)) }

    /** The answer for the formats [AlphaSniffer] settles from the MIME type alone (JPEG, ICO, SVG), else null. */
    fun fromMimeAlone(mime: String): Boolean? = AlphaSniffer.sniff(ByteArray(0), mime)

    /** What a header [head] of a file of type [mime] says; a format the sniffer does not know reads as opaque. */
    fun verdict(head: ByteArray, mime: String): Boolean = AlphaSniffer.sniff(head, mime) ?: false

    suspend fun hasAlpha(context: Context, asset: MediaAsset): Boolean {
        if (asset.isVideo) return false
        cached(asset)?.let { return it }
        val mime = asset.mimeType
        val answer = fromMimeAlone(mime) ?: withContext(Dispatchers.IO) {
            val head = readHead(context, asset) ?: return@withContext null
            verdict(head, mime)
        } ?: return false // unreadable right now: opaque for this view, and not remembered
        synchronized(cache) { cache.put(key(asset), answer) }
        return answer
    }

    /** Null when the file cannot be opened or read (a revoked grant, a vanished file). */
    private fun readHead(context: Context, asset: MediaAsset): ByteArray? = try {
        context.contentResolver.openInputStream(asset.contentUri)?.use { input ->
            // InputStream.readNBytes needs API 33; this app's minSdk is 26.
            val buffer = ByteArray(HEAD_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            if (total == buffer.size) buffer else buffer.copyOf(total)
        }
    } catch (_: Exception) {
        null
    }
}
