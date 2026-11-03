package com.fotoxplorr.app.basemap

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** A geographic position in degrees. */
data class LonLat(val lon: Double, val lat: Double)

/** A position on screen in pixels. */
data class PxPoint(val x: Double, val y: Double)

/** The size of the map's drawing area in pixels. */
data class MapViewport(val widthPx: Double, val heightPx: Double)

/**
 * Where the map is looking: a continuous zoom [level] and the geographic point at the viewport's
 * centre. Level 0 shows the whole world across the viewport's longer side (see [MapMath.worldPx]).
 */
data class MapCamera(val level: Double, val centerLon: Double, val centerLat: Double)

/**
 * Web Mercator maths for the bundled basemap, with no tiles and no Android types. World units run
 * 0..1 with (0, 0) at the north-west corner (lon -180, lat [MAX_LAT]).
 */
object MapMath {
    /** The Web Mercator cutoff; beyond it the projection stretches without bound. */
    const val MAX_LAT = 85.05112878
    const val MIN_LEVEL = 0.0

    /** The bundled data is 1:50m, so past about eight levels it is just bigger polygons. */
    const val MAX_LEVEL = 8.0
    const val TILE_PX = 256

    fun lonToX(lon: Double): Double = (lon + 180.0) / 360.0

    fun xToLon(x: Double): Double = x * 360.0 - 180.0

    fun latToY(lat: Double): Double {
        val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return 0.5 - ln(tan(PI / 4.0 + clamped / 2.0)) / (2.0 * PI)
    }

    fun yToLat(y: Double): Double {
        val n = PI * (1.0 - 2.0 * y.coerceIn(0.0, 1.0))
        return atan(exp(n).let { (it - 1.0 / it) / 2.0 }) * 180.0 / PI
    }

    /** The world's width in pixels at [level]: the viewport's longer side times 2^level. */
    fun worldPx(level: Double, viewport: MapViewport): Double =
        max(viewport.widthPx, viewport.heightPx) * 2.0.pow(level)

    /** The slippy-map zoom whose 256 px tiles match the world's current size. */
    fun tileZoom(level: Double, viewport: MapViewport): Double = log2(worldPx(level, viewport) / TILE_PX)

    fun toScreen(camera: MapCamera, viewport: MapViewport, lon: Double, lat: Double): PxPoint {
        val wp = worldPx(camera.level, viewport)
        return PxPoint(
            viewport.widthPx / 2.0 + (lonToX(lon) - lonToX(camera.centerLon)) * wp,
            viewport.heightPx / 2.0 + (latToY(lat) - latToY(camera.centerLat)) * wp,
        )
    }

    /** The position under a screen point, clamped onto the world (no longitude wrap, no poles). */
    fun fromScreen(camera: MapCamera, viewport: MapViewport, x: Double, y: Double): LonLat {
        val wp = worldPx(camera.level, viewport)
        val wx = lonToX(camera.centerLon) + (x - viewport.widthPx / 2.0) / wp
        val wy = latToY(camera.centerLat) + (y - viewport.heightPx / 2.0) / wp
        return LonLat(xToLon(wx.coerceIn(0.0, 1.0)), yToLat(wy))
    }

    /** Level and centre pulled back into range so the view never shows past the world's edge. */
    fun clamped(camera: MapCamera, viewport: MapViewport, maxLevel: Double = MAX_LEVEL): MapCamera {
        val level = camera.level.coerceIn(MIN_LEVEL, max(MIN_LEVEL, maxLevel))
        val wp = worldPx(level, viewport)
        val halfW = viewport.widthPx / 2.0 / wp
        val halfH = viewport.heightPx / 2.0 / wp
        val cx = if (halfW >= 0.5) 0.5 else lonToX(camera.centerLon).coerceIn(halfW, 1.0 - halfW)
        val cy = if (halfH >= 0.5) 0.5 else latToY(camera.centerLat).coerceIn(halfH, 1.0 - halfH)
        return MapCamera(level, xToLon(cx), yToLat(cy))
    }

    /** Drag the map by [dx], [dy] pixels (the content follows the finger). */
    fun panned(camera: MapCamera, viewport: MapViewport, dx: Double, dy: Double, maxLevel: Double = MAX_LEVEL): MapCamera {
        val wp = worldPx(camera.level, viewport)
        val cx = lonToX(camera.centerLon) - dx / wp
        val cy = latToY(camera.centerLat) - dy / wp
        return clamped(MapCamera(camera.level, xToLon(cx), yToLat(cy)), viewport, maxLevel)
    }

    /** Change the level so the geographic point under ([fx], [fy]) stays under it. */
    fun zoomedAt(
        camera: MapCamera,
        viewport: MapViewport,
        newLevel: Double,
        fx: Double,
        fy: Double,
        maxLevel: Double = MAX_LEVEL,
    ): MapCamera {
        val level = newLevel.coerceIn(MIN_LEVEL, max(MIN_LEVEL, maxLevel))
        val anchor = fromScreen(camera, viewport, fx, fy)
        val wp = worldPx(level, viewport)
        val cx = lonToX(anchor.lon) - (fx - viewport.widthPx / 2.0) / wp
        val cy = latToY(anchor.lat) - (fy - viewport.heightPx / 2.0) / wp
        return clamped(MapCamera(level, xToLon(cx), yToLat(cy)), viewport, maxLevel)
    }

    /** Centre on a point at a level (clamped). */
    fun centeredOn(lon: Double, lat: Double, level: Double, viewport: MapViewport, maxLevel: Double = MAX_LEVEL): MapCamera =
        clamped(MapCamera(level, lon.coerceIn(-180.0, 180.0), lat.coerceIn(-MAX_LAT, MAX_LAT)), viewport, maxLevel)

    /**
     * The camera that shows a box with [padFraction] of breathing room, uniformly scaled (the old
     * stamp field stretched x and y independently, which no map does).
     */
    fun fitted(
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
        viewport: MapViewport,
        padFraction: Double = 0.12,
        maxLevel: Double = MAX_LEVEL,
    ): MapCamera {
        val spanX = max(lonToX(maxLon) - lonToX(minLon), 1e-9)
        val spanY = max(latToY(minLat) - latToY(maxLat), 1e-9)
        val usable = 1.0 - 2.0 * padFraction
        val needX = viewport.widthPx * usable / spanX
        val needY = viewport.heightPx * usable / spanY
        val level = log2(min(needX, needY) / max(viewport.widthPx, viewport.heightPx))
        return centeredOn((minLon + maxLon) / 2.0, yToLat((latToY(minLat) + latToY(maxLat)) / 2.0), level, viewport, maxLevel)
    }
}

/** Turning a tap or a dragged pin into a coordinate. */
object PinMath {
    /** Where a touch at ([x], [y]) puts the pin: on the world, never past a pole or the antimeridian. */
    fun pinAt(camera: MapCamera, viewport: MapViewport, x: Double, y: Double): LonLat {
        val p = MapMath.fromScreen(camera, viewport, x, y)
        return LonLat(p.lon.coerceIn(-180.0, 180.0), p.lat.coerceIn(-MapMath.MAX_LAT, MapMath.MAX_LAT))
    }

    /** True when a touch starts close enough to the pin to grab it. */
    fun grabs(pin: PxPoint, touchX: Double, touchY: Double, radiusPx: Double): Boolean {
        val dx = pin.x - touchX
        val dy = pin.y - touchY
        return dx * dx + dy * dy <= radiusPx * radiusPx
    }
}
