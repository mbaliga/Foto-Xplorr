package com.fotoxplorr.app.spatial

import android.location.Location
import com.fotoxplorr.app.experience.PhotoSceneCard
import com.fotoxplorr.app.experience.SceneVector3
import com.fotoxplorr.app.media.MediaAsset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class SpatialPhotoPlacement(
    val card: PhotoSceneCard,
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val usedCaptureDirection: Boolean,
)

object SpatialPhotoLayout {
    fun build(
        assets: List<MediaAsset>,
        metadataById: Map<com.fotoxplorr.app.media.MediaId, GeoMetadata>,
        origin: Location,
        maxCards: Int = DEFAULT_MAX_CARDS,
    ): List<SpatialPhotoPlacement> {
        val originAltitude = origin.altitude.takeIf { origin.hasAltitude() }
        return assets.asSequence()
            .mapNotNull { asset ->
                val metadata = metadataById[asset.id] ?: return@mapNotNull null
                val distance = distanceMeters(
                    origin.latitude,
                    origin.longitude,
                    metadata.latitude,
                    metadata.longitude,
                )
                val bearing = initialBearingDegrees(
                    origin.latitude,
                    origin.longitude,
                    metadata.latitude,
                    metadata.longitude,
                )
                val radius = radialDistance(distance)
                val radians = bearing * PI / 180.0
                val altitudeDelta = if (originAltitude != null && metadata.altitudeMeters != null) {
                    ((metadata.altitudeMeters - originAltitude) / ALTITUDE_SCALE_METERS)
                        .coerceIn(-MAX_VERTICAL_OFFSET, MAX_VERTICAL_OFFSET)
                        .toFloat()
                } else {
                    pseudoVertical(asset.id.value)
                }
                val aspect = asset.aspectRatio.coerceIn(MIN_ASPECT, MAX_ASPECT)
                val height = BASE_CARD_HEIGHT * sizeVariation(asset.id.value)
                val width = if (aspect >= 1f) height * aspect else height * 0.92f
                val correctedHeight = if (aspect >= 1f) height else height / aspect
                SpatialPhotoPlacement(
                    card = PhotoSceneCard(
                        asset = asset,
                        position = SceneVector3(
                            x = (sin(radians) * radius).toFloat(),
                            y = altitudeDelta,
                            z = (-cos(radians) * radius).toFloat(),
                        ),
                        width = width,
                        height = correctedHeight,
                        yawDegrees = (-bearing).toFloat(),
                    ),
                    distanceMeters = distance,
                    bearingDegrees = bearing,
                    usedCaptureDirection = metadata.captureDirectionDegrees != null,
                )
            }
            .sortedBy { it.distanceMeters }
            .take(maxCards.coerceIn(1, HARD_MAX_CARDS))
            .toList()
    }

    fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double,
    ): Double {
        val phi1 = latitude1 * PI / 180.0
        val phi2 = latitude2 * PI / 180.0
        val deltaPhi = (latitude2 - latitude1) * PI / 180.0
        val deltaLambda = (longitude2 - longitude1) * PI / 180.0
        val a = sin(deltaPhi / 2.0) * sin(deltaPhi / 2.0) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2.0) * sin(deltaLambda / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun initialBearingDegrees(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double,
    ): Double {
        val phi1 = latitude1 * PI / 180.0
        val phi2 = latitude2 * PI / 180.0
        val deltaLambda = (longitude2 - longitude1) * PI / 180.0
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        return ((atan2(y, x) * 180.0 / PI) + 360.0) % 360.0
    }

    private fun radialDistance(distanceMeters: Double): Double =
        (MIN_SCENE_RADIUS + ln(1.0 + distanceMeters / DISTANCE_LOG_SCALE) * DISTANCE_SCENE_SCALE)
            .coerceIn(MIN_SCENE_RADIUS, MAX_SCENE_RADIUS)

    private fun pseudoVertical(id: Long): Float {
        val mixed = (id xor (id ushr 18) xor (id shl 9)).toInt()
        return (((mixed and 0x7fffffff) % 1000) / 999f - 0.5f) * 2.2f
    }

    private fun sizeVariation(id: Long): Float {
        val mixed = (id xor (id ushr 11) xor (id shl 5)).toInt()
        val normalized = (mixed and 0x7fffffff) % 1000 / 999f
        return min(MAX_SCALE, max(MIN_SCALE, MIN_SCALE + normalized * (MAX_SCALE - MIN_SCALE)))
    }

    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val MIN_SCENE_RADIUS = 6.5
    private const val MAX_SCENE_RADIUS = 46.0
    private const val DISTANCE_LOG_SCALE = 35.0
    private const val DISTANCE_SCENE_SCALE = 5.4
    private const val ALTITUDE_SCALE_METERS = 35.0
    private const val MAX_VERTICAL_OFFSET = 7.0
    private const val BASE_CARD_HEIGHT = 1.35f
    private const val MIN_ASPECT = 0.52f
    private const val MAX_ASPECT = 2.05f
    private const val MIN_SCALE = 0.72f
    private const val MAX_SCALE = 1.24f
    private const val DEFAULT_MAX_CARDS = 800
    private const val HARD_MAX_CARDS = 1_500
}
