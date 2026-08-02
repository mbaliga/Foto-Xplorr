package io.github.mbaliga.fotoxlorr.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object BearingMath {
    fun initialBearingDegrees(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val fromLat = Math.toRadians(fromLatitude)
        val toLat = Math.toRadians(toLatitude)
        val longitudeDelta = Math.toRadians(toLongitude - fromLongitude)
        val y = sin(longitudeDelta) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) -
            sin(fromLat) * cos(toLat) * cos(longitudeDelta)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    fun relativeBearingDegrees(targetBearing: Double, deviceAzimuth: Double): Double {
        val delta = normalizeDegrees(targetBearing - deviceAzimuth)
        return if (delta > 180.0) delta - 360.0 else delta
    }

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
}
