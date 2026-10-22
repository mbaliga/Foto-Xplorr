package com.fotoxplorr.app.spatial.offline

/**
 * A latitude/longitude rectangle for an offline-map region, with the two awkward cases handled once.
 *
 * - **The antimeridian.** [west] greater than [east] means the box runs eastward across the 180th
 *   meridian (170 to -170 is 20 degrees wide, not 340). [split] gives the one or two pieces that
 *   never cross it, which is what tile maths needs.
 * - **The poles.** Web Mercator cannot show the poles, so latitude is clamped to
 *   +/-[OfflineMapLimits.MAX_MERCATOR_LATITUDE]; a box that lies wholly beyond it collapses to a
 *   zero-height strip ([isZeroArea]) instead of throwing.
 *
 * Build one with [clamped] (repairs what a map camera reports) or [orNull] (refuses anything out of
 * range, for a stored record). There is no public constructor, so an invalid box cannot exist.
 * Edges are plain Doubles in degrees; no MapLibre type is involved.
 */
class GeoBox private constructor(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    /** True when the box runs across the 180th meridian ([west] is greater than [east]). */
    val crossesAntimeridian: Boolean get() = west > east

    /** East-west extent in degrees, across the antimeridian when it is crossed. */
    val widthDegrees: Double get() = if (crossesAntimeridian) east - west + 360.0 else east - west

    val heightDegrees: Double get() = north - south

    /** A box with no width or no height. There is no region to download for it. */
    val isZeroArea: Boolean get() = widthDegrees <= 0.0 || heightDegrees <= 0.0

    val centreLatitude: Double get() = (south + north) / 2.0

    val centreLongitude: Double get() = wrapLongitude(west + widthDegrees / 2.0)

    /** The one or two boxes that do not cross the antimeridian, west piece first. */
    fun split(): List<GeoBox> =
        if (crossesAntimeridian) {
            listOf(GeoBox(west, south, 180.0, north), GeoBox(-180.0, south, east, north))
        } else {
            listOf(this)
        }

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude < south || latitude > north) return false
        return if (crossesAntimeridian) longitude >= west || longitude <= east else longitude in west..east
    }

    /** This box grown by [fraction] of its own width and height on every side, clamped to the world. */
    fun pad(fraction: Double): GeoBox {
        val lonPad = widthDegrees * fraction
        val latPad = heightDegrees * fraction
        return clamped(west - lonPad, south - latPad, west + widthDegrees + lonPad, north + latPad) ?: this
    }

    override fun equals(other: Any?): Boolean =
        other is GeoBox && west == other.west && south == other.south && east == other.east && north == other.north

    override fun hashCode(): Int {
        var result = west.hashCode()
        result = 31 * result + south.hashCode()
        result = 31 * result + east.hashCode()
        result = 31 * result + north.hashCode()
        return result
    }

    override fun toString(): String = "GeoBox(west=$west, south=$south, east=$east, north=$north)"

    companion object {
        private const val LIMIT = OfflineMapLimits.MAX_MERCATOR_LATITUDE

        /**
         * A box from what a map camera or a padding step reports. Longitudes may lie outside
         * -180..180 (a map panned across the date line reports 170 to 190) and are wrapped; an
         * east edge below the west edge means a crossing box; a span of 360 degrees or more is the
         * whole world. Latitudes are clamped to the Mercator limit. Null for a non-finite edge or a
         * south edge above the north edge.
         */
        fun clamped(west: Double, south: Double, east: Double, north: Double): GeoBox? {
            if (!west.isFinite() || !south.isFinite() || !east.isFinite() || !north.isFinite()) return null
            if (south > north) return null
            val lowLat = south.coerceIn(-LIMIT, LIMIT)
            val highLat = north.coerceIn(-LIMIT, LIMIT)

            val rawSpan = (east - west).let { if (it < 0.0) it + 360.0 else it }
            if (rawSpan >= 360.0) return GeoBox(-180.0, lowLat, 180.0, highLat)

            val w = wrapLongitude(west)
            val e = (w + rawSpan).let { if (it > 180.0) it - 360.0 else it }
            return GeoBox(w, lowLat, e, highLat)
        }

        /**
         * A box exactly as given, or null when any edge is out of range. For decoding a stored
         * record, where a silently repaired box would download the wrong place. The one repair is
         * an east edge of -180 after a western start, which is the same meridian as 180.
         */
        fun orNull(west: Double, south: Double, east: Double, north: Double): GeoBox? {
            if (!west.isFinite() || !south.isFinite() || !east.isFinite() || !north.isFinite()) return null
            if (south < -LIMIT || north > LIMIT || south > north) return null
            if (west < -180.0 || west >= 180.0 || east < -180.0 || east > 180.0) return null
            val fixedEast = if (east == -180.0 && west > east) 180.0 else east
            return GeoBox(west, south, fixedEast, north)
        }

        /** Brings a longitude into -180 (inclusive) to 180 (exclusive). */
        internal fun wrapLongitude(longitude: Double): Double {
            val shifted = (longitude + 180.0) % 360.0
            return (if (shifted < 0.0) shifted + 360.0 else shifted) - 180.0
        }
    }
}
