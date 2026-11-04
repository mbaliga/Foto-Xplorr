package com.fotoxplorr.app.viewer.sphere

import kotlin.math.abs

/**
 * What a photo sphere's GPano block says about how the picture sits on the sphere, reduced to
 * the angles the viewer needs. A full sphere is 360 x 180 degrees; a partial one (a wide panorama,
 * a dome) covers a window of it, and the viewer must not let the camera look outside that window.
 *
 * Pixel sizes in GPano refer to the ORIGINAL file; a decoded copy may be smaller, so only the
 * fractions matter: [lonMin]..[lonMax] and [latMin]..[latMax] are derived from them.
 */
data class SphereMeta(
    val fullWidth: Int,
    val fullHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val croppedLeft: Int,
    val croppedTop: Int,
    /** The author's suggested first view (degrees): heading clockwise from the picture's centre column, pitch up, vertical field of view. */
    val initialHeading: Float = 0f,
    val initialPitch: Float = 0f,
    val initialFov: Float? = null,
) {
    /** Longitude of the picture's left and right edges, degrees, -180 at the full sphere's left edge. */
    val lonMin: Float get() = -180f + 360f * croppedLeft / fullWidth
    val lonMax: Float get() = lonMin + 360f * croppedWidth / fullWidth

    /** Latitude of the picture's top and bottom edges, degrees, +90 at the zenith. */
    val latMax: Float get() = 90f - 180f * croppedTop / fullHeight
    val latMin: Float get() = latMax - 180f * croppedHeight / fullHeight

    /** Whether the picture wraps all the way round horizontally. */
    val wrapsHorizontally: Boolean get() = croppedWidth >= fullWidth

    val lonSpan: Float get() = lonMax - lonMin
    val latSpan: Float get() = latMax - latMin

    companion object {
        /**
         * Reads GPano properties through [get] (property local name -> text, e.g. `"ProjectionType"`).
         * Null unless the projection is equirectangular and the sizes are sane. Missing cropped-area
         * fields mean "the picture is the whole thing", with the decoded [imageWidth] x [imageHeight]
         * standing in for the sizes (so a 2:1 equirectangular file with only ProjectionType still
         * works as a full sphere).
         */
        fun parse(get: (String) -> String?, imageWidth: Int, imageHeight: Int): SphereMeta? {
            if (get("ProjectionType")?.trim()?.equals("equirectangular", ignoreCase = true) != true) return null
            fun int(name: String) = get(name)?.trim()?.toIntOrNull()
            fun float(name: String) = get(name)?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
            val croppedW = int("CroppedAreaImageWidthPixels") ?: imageWidth
            val croppedH = int("CroppedAreaImageHeightPixels") ?: imageHeight
            val fullW = int("FullPanoWidthPixels") ?: croppedW
            val fullH = int("FullPanoHeightPixels") ?: croppedH
            if (fullW <= 0 || fullH <= 0 || croppedW <= 0 || croppedH <= 0) return null
            if (croppedW > fullW || croppedH > fullH) return null
            val left = (int("CroppedAreaLeftPixels") ?: ((fullW - croppedW) / 2)).coerceIn(0, fullW - croppedW)
            val top = (int("CroppedAreaTopPixels") ?: ((fullH - croppedH) / 2)).coerceIn(0, fullH - croppedH)
            return SphereMeta(
                fullW, fullH, croppedW, croppedH, left, top,
                initialHeading = float("InitialViewHeadingDegrees") ?: 0f,
                initialPitch = float("InitialViewPitchDegrees") ?: 0f,
                initialFov = float("InitialVerticalFOVDegrees")?.takeIf { it in 10f..140f },
            )
        }
    }
}

/** True when the metadata's aspect says it is a whole sphere, to the 1% a stitcher's rounding allows. */
fun SphereMeta.isWholeSphere(): Boolean =
    wrapsHorizontally && croppedHeight >= fullHeight && abs(fullWidth.toFloat() / fullHeight - 2f) < 0.02f
