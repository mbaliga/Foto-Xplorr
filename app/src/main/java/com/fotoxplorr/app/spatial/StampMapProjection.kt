package com.fotoxplorr.app.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tan

/**
 * Where a photo's coordinates land on the stylized map.
 *
 * The whole reason this map can exist in the **offline** flavour: a photo's location is only a
 * latitude and a longitude, and putting a pin on a canvas is arithmetic. What needs downloading is
 * the *basemap* underneath -- the coastlines and roads -- and the owner chose a stylized field
 * instead of one (2026-08-15: *"Stylized by default"*). No tiles, no MapLibre, no network, and
 * therefore nothing for the offline classpath gate to object to.
 *
 * Web Mercator for the vertical axis rather than plain linear latitude, because linear latitude
 * visibly squashes everything far from the equator -- photos from northern Europe would bunch
 * together while equatorial ones spread out, which reads as a broken map to anyone who has seen
 * one before.
 */
object StampMapProjection {

    /**
     * The extent of a set of photos, as the map's own window.
     *
     * Framing to the photos rather than to the whole world is what makes the map useful for a
     * library that is all one city as well as one spanning continents: a world map with every pin
     * in one pixel tells you nothing.
     */
    data class Bounds(
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double,
    )

    /** Normalised 0..1 position within the current [Bounds]; 0,0 is top-left. */
    data class Point(val x: Float, val y: Float)

    /**
     * Bounding box of [points], padded so no pin sits exactly on an edge.
     *
     * A single photo, or several at the same spot, would otherwise produce a zero-width box and a
     * division by zero downstream; [MIN_SPAN] gives such a set a small sensible window instead of
     * a degenerate one.
     */
    fun boundsOf(points: List<Pair<Double, Double>>): Bounds? {
        if (points.isEmpty()) return null
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        points.forEach { (lat, lon) ->
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }

        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        val latPad = if (latSpan < MIN_SPAN) (MIN_SPAN - latSpan) / 2.0 else latSpan * EDGE_PAD
        val lonPad = if (lonSpan < MIN_SPAN) (MIN_SPAN - lonSpan) / 2.0 else lonSpan * EDGE_PAD

        return Bounds(
            minLatitude = (minLat - latPad).coerceAtLeast(-MAX_MERCATOR_LATITUDE),
            maxLatitude = (maxLat + latPad).coerceAtMost(MAX_MERCATOR_LATITUDE),
            minLongitude = (minLon - lonPad).coerceAtLeast(-180.0),
            maxLongitude = (maxLon + lonPad).coerceAtMost(180.0),
        )
    }

    /**
     * Project one coordinate into the unit square of [bounds].
     *
     * Y is flipped so that north is up: normalised space grows downward, latitude grows upward.
     */
    fun project(latitude: Double, longitude: Double, bounds: Bounds): Point {
        val lonSpan = (bounds.maxLongitude - bounds.minLongitude).takeIf { abs(it) > 1e-9 } ?: 1.0
        val x = (longitude - bounds.minLongitude) / lonSpan

        val yMin = mercatorY(bounds.minLatitude)
        val yMax = mercatorY(bounds.maxLatitude)
        val ySpan = (yMax - yMin).takeIf { abs(it) > 1e-9 } ?: 1.0
        val y = 1.0 - (mercatorY(latitude) - yMin) / ySpan

        return Point(
            x = x.coerceIn(0.0, 1.0).toFloat(),
            y = y.coerceIn(0.0, 1.0).toFloat(),
        )
    }

    /**
     * Keep at most one item per grid cell, so a library with thousands of located photos still
     * draws as a readable scattering of stamps rather than an opaque pile.
     *
     * This is thinning, not clustering: the survivors are real photos at their real positions. A
     * cluster bubble reading "417" would be a different screen than the one the owner asked for --
     * the reference is stamps pinned to a field. Input order decides which photo represents a cell,
     * so the newest photo in a place wins when the caller passes newest-first.
     *
     * Pure and generic so the arithmetic can be tested without a device or a photo.
     */
    fun <T> thin(items: List<T>, cells: Int, position: (T) -> Point): List<T> {
        if (cells <= 0 || items.isEmpty()) return items
        val taken = HashSet<Int>(items.size.coerceAtMost(cells * cells))
        return items.filter { item ->
            val point = position(item)
            val column = (point.x * cells).toInt().coerceIn(0, cells - 1)
            val row = (point.y * cells).toInt().coerceIn(0, cells - 1)
            taken.add(row * cells + column)
        }
    }

    /**
     * The Web Mercator vertical coordinate for a latitude.
     *
     * Clamped to [MAX_MERCATOR_LATITUDE] because the projection is genuinely infinite at the
     * poles -- tan(pi/2) diverges -- and a photo taken at 89.99 degrees would otherwise send the
     * whole map's scale to infinity and collapse every other pin onto one line.
     */
    private fun mercatorY(latitude: Double): Double {
        val clamped = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
        val radians = clamped * PI / 180.0
        return ln(tan(PI / 4.0 + radians / 2.0))
    }

    /**
     * The standard Web Mercator cutoff. Beyond this the projection stretches without bound; every
     * slippy map in existence draws the world clipped to roughly this latitude for the same reason.
     */
    private const val MAX_MERCATOR_LATITUDE = 85.05112878

    /** Smallest degree span a set of photos is given, so a single pin still gets a sane window. */
    private const val MIN_SPAN = 0.01

    /** Breathing room around the outermost pins, as a fraction of the span. */
    private const val EDGE_PAD = 0.12
}
