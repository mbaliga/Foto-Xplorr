package com.fotoxplorr.app.spatial.offline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The ground a flat map view covers, from where the camera is and how big the view is.
 *
 * The map library's own visible-region bounds are not used: on the pitched map the far edge of the
 * screen is hundreds of kilometres away, so the bounds of a tilted view would almost always be "too
 * large" even when the person is looking at one street. This is the area the view would show with
 * the camera looking straight down, the same centre and zoom, which is what "this view" means to
 * the person and what a saved region (always flat tiles) can honestly offer.
 *
 * The camera's bearing is honoured: on a map turned by a bearing the screen is a rotated rectangle
 * on the ground, whose corners an axis-aligned box of the unrotated size would miss, so the box is
 * made the rotated rectangle's bounding box ([rotatedExtent]), which holds every corner of the
 * screen. At a quarter turn that is the north-up box with its width and height swapped. Pitch is
 * still ignored, as above.
 *
 * Pure arithmetic; no map type. The map library's zoom counts 512 logical pixels per tile, so
 * [widthDp] and [heightDp] are the view's size in density-independent pixels, not physical ones.
 * Estimate, replace after the device spike: compare against the library's visible region at tilt 0.
 */
object ViewArea {

    /** Logical pixels in one tile at the map library's zoom scale. Estimate: read from memory of its docs. */
    const val TILE_PX: Double = 512.0

    /**
     * The box under a flat view centred on ([centreLatitude], [centreLongitude]) at fractional
     * [zoom], or null when an input is not finite, a size is not positive, or the zoom is outside
     * 0..[TileMath.MAX_SUPPORTED_ZOOM] or [bearingDegrees] is not finite. A longitude past +/-180
     * (a map panned over the date line) is wrapped, and a view wider than the world is the whole
     * world; latitude stops at the Web Mercator limit. [bearingDegrees] is the map's rotation in
     * degrees (any value; 0 is north up).
     */
    fun boxOf(
        centreLatitude: Double,
        centreLongitude: Double,
        zoom: Double,
        widthDp: Double,
        heightDp: Double,
        bearingDegrees: Double = 0.0,
    ): GeoBox? {
        if (!centreLatitude.isFinite() || !centreLongitude.isFinite() || !zoom.isFinite()) return null
        if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0.0 || heightDp <= 0.0) return null
        if (!bearingDegrees.isFinite()) return null
        if (zoom < 0.0 || zoom > TileMath.MAX_SUPPORTED_ZOOM) return null

        val (turnedWidthDp, turnedHeightDp) = rotatedExtent(widthDp, heightDp, bearingDegrees)
        val tilesAcross = 2.0.pow(zoom)
        val halfLongitude = turnedWidthDp / (TILE_PX * tilesAcross) * 360.0 / 2.0

        val limit = OfflineMapLimits.MAX_MERCATOR_LATITUDE
        val centreY = latitudeToY(centreLatitude.coerceIn(-limit, limit), tilesAcross)
        val halfHeight = turnedHeightDp / TILE_PX / 2.0
        val north = yToLatitude((centreY - halfHeight).coerceAtLeast(0.0), tilesAcross)
        val south = yToLatitude((centreY + halfHeight).coerceAtMost(tilesAcross), tilesAcross)

        return GeoBox.clamped(centreLongitude - halfLongitude, south, centreLongitude + halfLongitude, north)
    }

    /**
     * The width and height of the axis-aligned rectangle that just holds a [widthDp] by [heightDp]
     * rectangle turned by [bearingDegrees]: width |cos| + height |sin| across, width |sin| + height
     * |cos| down. Equal to the input at 0 and 180 degrees, swapped at 90.
     */
    fun rotatedExtent(widthDp: Double, heightDp: Double, bearingDegrees: Double): Pair<Double, Double> {
        val turn = Math.toRadians(bearingDegrees)
        val along = abs(cos(turn))
        val across = abs(sin(turn))
        return (widthDp * along + heightDp * across) to (widthDp * across + heightDp * along)
    }

    /** Row of [latitude] in tiles from the top edge of the world, at a scale of [tilesAcross] tiles. */
    private fun latitudeToY(latitude: Double, tilesAcross: Double): Double {
        val radians = latitude * PI / 180.0
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * tilesAcross
    }

    private fun yToLatitude(y: Double, tilesAcross: Double): Double =
        atan(sinh(PI * (1.0 - 2.0 * y / tilesAcross))) * 180.0 / PI
}
