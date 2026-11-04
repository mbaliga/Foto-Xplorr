package com.fotoxplorr.app.viewer.deepzoom

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One tile of the pyramid: [sample] is the power-of-two `inSampleSize`, [col]/[row] its grid cell at that level. */
data class TileKey(val sample: Int, val col: Int, val row: Int)

/** A rectangle in the UPRIGHT source's own pixels, right/bottom exclusive. */
data class SourceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The arithmetic of tiled deep zoom, with no bitmap and no container: which power-of-two level a
 * given magnification needs, which tiles cover a visible window, and what part of the source each
 * tile is. Every tile decodes to at most [TILE_PX] square whatever its level (a tile at sample `s`
 * spans `TILE_PX * s` source pixels), so one tile costs the same memory at every depth and the
 * cache can be budgeted in tiles.
 *
 * Coordinates are the upright photo's (EXIF orientation already applied); [RegionRects] converts
 * to the file's own grid for the decoder.
 */
object TilePyramid {
    const val TILE_PX = 512

    /** More than this many tiles for one window means the level is wrong or the window nonsense. */
    const val MAX_TILES_PER_VIEW = 48

    /**
     * The coarsest level that is still sharp: the largest power of two `s` with `1/s >= pixelsPerSourcePx`,
     * i.e. one decoded pixel is never stretched over more than one screen pixel. 1 at 1:1 and
     * beyond (past 1:1 the extra zoom is magnification, not detail, so no level finer than the file
     * exists), capped at [maxSample]. A non-finite or non-positive input is the coarsest level.
     */
    fun sampleFor(pixelsPerSourcePx: Float, maxSample: Int = MAX_SAMPLE): Int {
        if (!pixelsPerSourcePx.isFinite() || pixelsPerSourcePx <= 0f) return maxSample.coerceAtLeast(1)
        var s = 1
        while (s * 2 <= maxSample && 1f / (s * 2) >= pixelsPerSourcePx) s *= 2
        return s
    }

    /** Source pixels one tile at [sample] spans on each axis. */
    fun span(sample: Int): Int = TILE_PX * sample.coerceAtLeast(1)

    /** The part of the source [key] covers, clipped to the image. Empty (zero area) outside it. */
    fun rectOf(key: TileKey, srcW: Int, srcH: Int): SourceRect {
        val span = span(key.sample)
        val left = (key.col.toLong() * span).coerceIn(0L, srcW.toLong()).toInt()
        val top = (key.row.toLong() * span).coerceIn(0L, srcH.toLong()).toInt()
        val right = ((key.col + 1L) * span).coerceIn(0L, srcW.toLong()).toInt()
        val bottom = ((key.row + 1L) * span).coerceIn(0L, srcH.toLong()).toInt()
        return SourceRect(left, top, max(left, right), max(top, bottom))
    }

    /**
     * The tiles at [sample] that touch the window (fractions 0..1 of the image on each axis),
     * nearest the window's centre first so the middle of the screen sharpens before its corners.
     * [marginTiles] extends the window by that many tiles each side so a pan lands on loaded
     * pixels. Capped at [MAX_TILES_PER_VIEW].
     */
    fun tilesCovering(
        srcW: Int, srcH: Int,
        left: Float, top: Float, right: Float, bottom: Float,
        sample: Int, marginTiles: Int = 0,
    ): List<TileKey> {
        if (srcW <= 0 || srcH <= 0 || sample < 1) return emptyList()
        val span = span(sample).toFloat()
        val cols = ceil(srcW / span).toInt().coerceAtLeast(1)
        val rows = ceil(srcH / span).toInt().coerceAtLeast(1)
        val x0 = left.coerceIn(0f, 1f) * srcW
        val x1 = right.coerceIn(0f, 1f) * srcW
        val y0 = top.coerceIn(0f, 1f) * srcH
        val y1 = bottom.coerceIn(0f, 1f) * srcH
        if (x1 <= x0 || y1 <= y0) return emptyList()
        val c0 = max(0, floor(x0 / span).toInt() - marginTiles)
        val c1 = min(cols - 1, floor((x1 - 0.001f) / span).toInt() + marginTiles)
        val r0 = max(0, floor(y0 / span).toInt() - marginTiles)
        val r1 = min(rows - 1, floor((y1 - 0.001f) / span).toInt() + marginTiles)
        val cx = (x0 + x1) / 2f / span
        val cy = (y0 + y1) / 2f / span
        val out = ArrayList<TileKey>()
        for (r in r0..r1) for (c in c0..c1) out += TileKey(sample, c, r)
        out.sortBy { k ->
            val dx = k.col + 0.5f - cx
            val dy = k.row + 0.5f - cy
            dx * dx + dy * dy
        }
        return if (out.size > MAX_TILES_PER_VIEW) out.subList(0, MAX_TILES_PER_VIEW).toList() else out
    }

    /** The deepest level worth having: past this one tile already covers the whole image. */
    const val MAX_SAMPLE = 64
}
