package com.fotoxplorr.app.wall

import kotlin.math.hypot

/** A thing of known size the person can mark on the wall photo. Sizes in cm. */
internal enum class ReferenceObject(val cm: Float) {
    A4_LONG(29.7f),
    A4_SHORT(21.0f),
    CARD(8.56f),

    /** A common interior door, about 80 in. Doors vary; the person can type their own. */
    DOOR(203f),
}

/**
 * How big the wall in a photo really is. The whole calibration is ONE number, the width of the
 * wall photo in cm ([wallWidthCm]); a picture's size on the photo is then its cm size over that,
 * whatever resolution the photo is drawn at.
 *
 * Both ways to get it assume the wall is flat and square to the camera. A reference marked well
 * off the plane of the picture's place, or a photo taken at an angle, gives an honest-looking
 * number that is off by the perspective: the screen says to mark it level with where the picture
 * will hang. Nothing here has been checked against a real wall photo.
 */
internal object WallScale {
    /** Diagonal of a 36 x 24 mm frame, the yardstick a "35 mm equivalent" focal length is quoted against. */
    const val FULL_FRAME_DIAGONAL_MM = 43.267

    /** Used when the photo carries no focal length: a typical phone's main camera. The screen says it guessed. */
    const val GUESSED_FOCAL_35MM = 26f

    const val MIN_WALL_CM = 20f
    const val MAX_WALL_CM = 2000f

    fun lengthPx(x1: Float, y1: Float, x2: Float, y2: Float): Float = hypot(x2 - x1, y2 - y1)

    /** From a reference of [referenceCm] that spans [referencePx] of an image [imageWidthPx] wide; null if unusable. */
    fun fromReference(referenceCm: Float, referencePx: Float, imageWidthPx: Int): Float? {
        if (referenceCm <= 0f || referencePx < 2f || imageWidthPx <= 0) return null
        return (referenceCm * imageWidthPx / referencePx).takeIf { it in MIN_WALL_CM..MAX_WALL_CM }
    }

    /**
     * From the camera's distance to the wall and its 35 mm equivalent focal length. The scene width
     * at [distanceCm] across the full diagonal is `distance * 43.27 / focal`; the image's width is
     * its share of that diagonal. Null for a distance or focal that is not positive.
     */
    fun fromDistance(distanceCm: Float, focal35mm: Float, imageWidthPx: Int, imageHeightPx: Int): Float? {
        if (distanceCm <= 0f || focal35mm <= 0f || imageWidthPx <= 0 || imageHeightPx <= 0) return null
        val diagonalShare = imageWidthPx / hypot(imageWidthPx.toDouble(), imageHeightPx.toDouble())
        val wall = distanceCm * FULL_FRAME_DIAGONAL_MM / focal35mm * diagonalShare
        return wall.toFloat().takeIf { it in MIN_WALL_CM..MAX_WALL_CM }
    }

    /** Image pixels per centimetre of wall; 0 when not calibrated. */
    fun pxPerCm(imageWidthPx: Int, wallWidthCm: Float): Float =
        if (wallWidthCm <= 0f) 0f else imageWidthPx / wallWidthCm
}
