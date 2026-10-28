package com.fotoxplorr.app.board

import com.fotoxplorr.app.editor.ExportOptions
import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.editor.exportEdgeLadder
import com.fotoxplorr.app.editor.renderEdge
import com.fotoxplorr.app.media.DecodeLimits
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The pixel size a board is drawn at, and the scale that gives it: [pxPerUnit] pixels for every canvas unit. */
data class RenderSize(val width: Int, val height: Int, val pxPerUnit: Float)

/** The size of the bitmap a masked or grouped layer is drawn into before it meets the canvas. */
data class GroupSize(val width: Int, val height: Int)

/**
 * The numbers the renderer works from, without touching Android: how big the picture is, how sharp
 * each photo has to be, and what size to try next when memory runs out. It reads the editor's own
 * limits ([MAX_EXPORT_EDGE], [MAX_EXPORT_PIXELS], [ExportOptions], [exportEdgeLadder]) so the board
 * exports under the same ceilings as an edited copy.
 */
object BoardRenderPlan {

    /**
     * The most pixels a layer's own bitmap may have while it is masked or grouped. A layer larger
     * than this on the canvas (zoomed far in) is drawn at a lower resolution and stretched, which
     * only costs sharpness; one with no mask is drawn straight from its photo and never hits it.
     */
    const val MAX_GROUP_PIXELS = 16_000_000L

    /** The size whose long side is [longEdgePx], kept within the editor's pixel budget. */
    fun sizeForEdge(canvas: CanvasSize, longEdgePx: Int): RenderSize {
        val edge = longEdgePx.coerceAtLeast(1)
        var k = edge.toDouble() / canvas.longEdge
        val units = canvas.width.toLong() * canvas.height
        if (units * k * k > MAX_EXPORT_PIXELS) k = sqrt(MAX_EXPORT_PIXELS.toDouble() / units)
        return RenderSize(
            width = max(1, (canvas.width * k).roundToInt()),
            height = max(1, (canvas.height * k).roundToInt()),
            pxPerUnit = k.toFloat(),
        )
    }

    /**
     * The long edge at which the sharpest photo on the board shows one photo pixel per output pixel,
     * never below the canvas's own unit size and never above [MAX_EXPORT_EDGE]. This is what
     * "Original" means for a board, which has no original size of its own. Photos with no known size
     * and layers off the canvas do not count.
     */
    fun nativeEdge(board: Board): Int {
        var ppu = 1.0
        for (layer in board.layers) {
            val ref = (layer.content as? LayerContent.Photo)?.ref ?: continue
            if (ref.widthPx <= 0 || ref.heightPx <= 0 || !BoardGeometry.isOnCanvas(layer, board.canvas)) continue
            val dw = layer.drawnWidth.toDouble()
            val dh = layer.drawnHeight.toDouble()
            if (dw <= 0.0 || dh <= 0.0) continue
            ppu = max(ppu, max(ref.widthPx * layer.crop.width / dw, ref.heightPx * layer.crop.height / dh))
        }
        val edge = ceil(board.canvas.longEdge * ppu)
        return min(edge, MAX_EXPORT_EDGE.toDouble()).toInt().coerceAtLeast(board.canvas.longEdge.coerceAtMost(MAX_EXPORT_EDGE))
    }

    /** The long edge an export under [options] is first tried at: the native edge, held to the person's own cap. */
    fun exportEdge(board: Board, options: ExportOptions): Int = min(nativeEdge(board), options.renderEdge()).coerceAtLeast(1)

    /** The edges to try in turn, largest first; the next only after the last ran out of memory. */
    fun exportLadder(board: Board, options: ExportOptions): List<Int> = exportEdgeLadder(exportEdge(board, options))

    /**
     * How large to decode [layer]'s photo so the part that shows has one photo pixel per output
     * pixel at [pxPerUnit], never above the photo's own size and never above the editor's decode
     * ceiling. Null for a layer that is not a photo. With no known photo size the long edge is
     * taken from the drawn size alone.
     */
    fun decodeLimits(layer: Layer, pxPerUnit: Float): DecodeLimits? {
        val ref = (layer.content as? LayerContent.Photo)?.ref ?: return null
        val drawW = layer.drawnWidth.toDouble() * pxPerUnit
        val drawH = layer.drawnHeight.toDouble() * pxPerUnit
        val needW = drawW / layer.crop.width
        val needH = drawH / layer.crop.height
        if (ref.widthPx <= 0 || ref.heightPx <= 0) {
            val long = ceilPx(max(needW, needH)).coerceIn(MIN_DECODE_EDGE.toLong(), MAX_EXPORT_EDGE.toLong())
            return DecodeLimits(long.toInt(), min(long * long, MAX_EXPORT_PIXELS))
        }
        val f = max(needW / ref.widthPx, needH / ref.heightPx).coerceIn(0.0, 1.0)
        val w = ceilPx(ref.widthPx * f).coerceAtLeast(1)
        val h = ceilPx(ref.heightPx * f).coerceAtLeast(1)
        val long = max(w, h).coerceIn(MIN_DECODE_EDGE.toLong(), MAX_EXPORT_EDGE.toLong())
        return DecodeLimits(long.toInt(), min((w + 1) * (h + 1), MAX_EXPORT_PIXELS).coerceAtLeast(long * 2))
    }

    private const val MIN_DECODE_EDGE = 8

    /** Rounds up, but not for the last bit of floating-point noise: 300.00000000000006 is 300 pixels. */
    private fun ceilPx(v: Double): Long = ceil(v - 1e-6).toLong()

    /** The bitmap size for a layer drawn [drawnWidthPx] x [drawnHeightPx] on the canvas, held to [MAX_GROUP_PIXELS]. */
    fun groupSize(drawnWidthPx: Float, drawnHeightPx: Float): GroupSize {
        var w = max(1, finite(drawnWidthPx, 1f).roundToInt()).toLong()
        var h = max(1, finite(drawnHeightPx, 1f).roundToInt()).toLong()
        if (w * h > MAX_GROUP_PIXELS) {
            val k = sqrt(MAX_GROUP_PIXELS.toDouble() / (w * h))
            w = max(1L, (w * k).toLong())
            h = max(1L, (h * k).toLong())
        }
        return GroupSize(w.toInt(), h.toInt())
    }
}
