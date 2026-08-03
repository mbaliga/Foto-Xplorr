package com.fotoxplorr.app.experience

import com.fotoxplorr.app.media.MediaAsset
import kotlin.math.max
import kotlin.math.min

data class SceneVector3(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class PhotoSceneCard(
    val asset: MediaAsset,
    val position: SceneVector3,
    val width: Float,
    val height: Float,
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val opacity: Float = 1f,
)

enum class PhotoSceneMode {
    WALL,
    SPATIAL,
}

object PhotoWallLayout {
    /**
     * Builds a long GPU corridor with mixed aspect-ratio cards. All cards have stable positions,
     * but the renderer only uploads textures for the visible depth window.
     */
    fun build(assets: List<MediaAsset>): List<PhotoSceneCard> = assets.mapIndexed { index, asset ->
        val lane = index % LANES
        val depthIndex = index / LANES
        val wall = depthIndex % 3
        val aspect = asset.aspectRatio.coerceIn(MIN_ASPECT, MAX_ASPECT)
        val baseHeight = BASE_HEIGHT * sizeVariation(asset.id.value)
        val width = if (aspect >= 1f) baseHeight * aspect else baseHeight * 0.92f
        val height = if (aspect >= 1f) baseHeight else baseHeight / aspect
        val z = -START_DEPTH - depthIndex * DEPTH_STEP
        val vertical = (lane - (LANES - 1) / 2f) * ROW_GAP

        when (wall) {
            0 -> PhotoSceneCard(
                asset = asset,
                position = SceneVector3(-LEFT_WALL_X, vertical, z),
                width = width,
                height = height,
                yawDegrees = LEFT_WALL_YAW,
            )
            1 -> PhotoSceneCard(
                asset = asset,
                position = SceneVector3(RIGHT_WALL_X, vertical, z - DEPTH_STEP * 0.34f),
                width = width,
                height = height,
                yawDegrees = RIGHT_WALL_YAW,
            )
            else -> PhotoSceneCard(
                asset = asset,
                position = SceneVector3(
                    x = ((lane % 2) * 2 - 1) * CENTER_OFFSET,
                    y = vertical * 0.82f,
                    z = z - DEPTH_STEP * 0.68f,
                ),
                width = width * 0.94f,
                height = height * 0.94f,
                yawDegrees = if (lane % 2 == 0) 8f else -8f,
            )
        }
    }

    private fun sizeVariation(id: Long): Float {
        val mixed = (id xor (id ushr 21) xor (id shl 7)).toInt()
        val normalized = (mixed and 0x7fffffff) % 1000 / 999f
        return min(MAX_SCALE, max(MIN_SCALE, MIN_SCALE + normalized * (MAX_SCALE - MIN_SCALE)))
    }

    private const val LANES = 4
    private const val BASE_HEIGHT = 1.65f
    private const val ROW_GAP = 2.4f
    private const val START_DEPTH = 5.5f
    private const val DEPTH_STEP = 2.85f
    private const val LEFT_WALL_X = 4.35f
    private const val RIGHT_WALL_X = 4.35f
    private const val CENTER_OFFSET = 1.55f
    private const val LEFT_WALL_YAW = 67f
    private const val RIGHT_WALL_YAW = -67f
    private const val MIN_ASPECT = 0.52f
    private const val MAX_ASPECT = 2.05f
    private const val MIN_SCALE = 0.78f
    private const val MAX_SCALE = 1.36f
}
