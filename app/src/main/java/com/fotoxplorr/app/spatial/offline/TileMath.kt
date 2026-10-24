package com.fotoxplorr.app.spatial.offline

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/** A rectangle of tiles at one zoom, edges inclusive. */
data class TileRange(val zoom: Int, val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
    val count: Long get() = (xMax - xMin + 1).toLong() * (yMax - yMin + 1).toLong()
}

/**
 * Slippy-map tile arithmetic: which tiles cover a [GeoBox] at each zoom.
 *
 * Web Mercator, tile (0,0) at the top left, 2^zoom tiles each way. This is how the app counts what
 * a region would need; the downloader does its own count, and the two are compared on a device
 * (the downloader may include tiles this one leaves out on a boundary, so treat the figure as an
 * estimate, never as the number that was fetched).
 */
object TileMath {

    /** Highest zoom the maths accepts. 24 keeps every count inside an Int axis and a Long total. */
    const val MAX_SUPPORTED_ZOOM: Int = 24

    /** Tile column of [longitude] at [zoom], as a fraction: 1.0 is the left edge of column 1. */
    fun lonToTileX(longitude: Double, zoom: Int): Double = (longitude + 180.0) / 360.0 * (1L shl zoom)

    /** Tile row of [latitude] at [zoom], as a fraction: 0.0 is the top edge of row 0. */
    fun latToTileY(latitude: Double, zoom: Int): Double {
        val radians = latitude * PI / 180.0
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * (1L shl zoom)
    }

    /**
     * The tile ranges that cover [box] at [zoom]: one range, or two when the box crosses the
     * antimeridian (west piece first). A box that only touches the edge of a tile does not take it.
     */
    fun tileRanges(box: GeoBox, zoom: Int): List<TileRange> {
        require(zoom in 0..MAX_SUPPORTED_ZOOM) { "zoom $zoom is outside 0..$MAX_SUPPORTED_ZOOM" }
        return box.split().map { rangeOf(it, zoom) }
    }

    /** Tiles needed for [box] over every zoom from [minZoom] to [maxZoom] inclusive; 0 when empty. */
    fun tileCount(box: GeoBox, minZoom: Int, maxZoom: Int): Long {
        require(minZoom >= 0 && maxZoom <= MAX_SUPPORTED_ZOOM) { "zoom range $minZoom..$maxZoom is outside 0..$MAX_SUPPORTED_ZOOM" }
        var total = 0L
        for (zoom in minZoom..maxZoom) total += tileRanges(box, zoom).sumOf { it.count }
        return total
    }

    private fun rangeOf(piece: GeoBox, zoom: Int): TileRange {
        val last = ((1L shl zoom) - 1).toInt()
        val xMin = floor(lonToTileX(piece.west, zoom)).toInt().coerceIn(0, last)
        val xMax = max(xMin, ceil(lonToTileX(piece.east, zoom)).toInt() - 1).coerceIn(xMin, last)
        val yMin = floor(latToTileY(piece.north, zoom)).toInt().coerceIn(0, last)
        val yMax = max(yMin, ceil(latToTileY(piece.south, zoom)).toInt() - 1).coerceIn(yMin, last)
        return TileRange(zoom, xMin, xMax, yMin, yMax)
    }
}
