package com.fotoxplorr.app.viewer.deepzoom

import com.fotoxplorr.app.viewer.ZoomViewport
import kotlin.math.max

/**
 * The zoom decisions that depend on how big the file really is: how far a pinch may go, what the
 * double-tap does, and whether tiles are worth decoding at all. Pure, so the "1:1 is reachable on a
 * 100 MP photo, and not on a thumbnail-sized one" claims are tests rather than hopes.
 */
object DeepZoomPolicy {
    /** The viewer's historical ceiling, still the floor of the allowed range. */
    const val BASE_MAX_SCALE = 6f

    /** Never zoom past this, however big the file: beyond it a pinch is a lottery. */
    const val ABSOLUTE_MAX_SCALE = 64f

    /** The double-tap's zoom for a photo smaller than the screen (1:1 would be a shrink). */
    const val SMALL_PHOTO_TAP_SCALE = 2.5f

    /** Below this ratio of source to screen pixels there is nothing more to see at 1:1. */
    private const val MIN_DEEP_RATIO = 1.25f

    /** Formats `BitmapRegionDecoder` reads: JPEG, PNG, WebP, and HEIF/HEIC where the platform does. */
    private val REGION_MIME = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif")

    /**
     * The layout scale at which one source pixel is one screen pixel: the fitted image is
     * `fittedWidth` px wide and the file `srcW` px, so 1:1 is `srcW / fittedWidth`. Below 1 for a
     * photo smaller than the screen (1:1 is then smaller than fit). 1 when the size is unknown.
     */
    fun oneToOneScale(srcW: Int, srcH: Int, containerW: Float, containerH: Float): Float {
        if (srcW <= 0 || srcH <= 0 || containerW <= 0f || containerH <= 0f) return 1f
        val aspect = ZoomViewport.aspect(srcW, srcH)
        val containerAspect = containerW / containerH
        val fittedW = if (aspect > containerAspect) containerW else containerH * aspect
        return srcW / fittedW
    }

    /** At least 2x 1:1 (so 1:1 is not the end of the pinch), never under [BASE_MAX_SCALE] or over [ABSOLUTE_MAX_SCALE]. */
    fun maxScale(srcW: Int, srcH: Int, containerW: Float, containerH: Float): Float =
        max(BASE_MAX_SCALE, 2f * oneToOneScale(srcW, srcH, containerW, containerH)).coerceAtMost(ABSOLUTE_MAX_SCALE)

    /**
     * Where a double-tap goes: from anywhere zoomed or turned, back to fit (1); from fit, to 1:1 when
     * the file has real detail beyond the screen, else the old fixed zoom. The cycle is fit -> 1:1 -> fit.
     */
    fun doubleTapTarget(currentScale: Float, rotated: Boolean, oneToOne: Float): Float =
        when {
            currentScale > 1.05f || rotated -> 1f
            oneToOne >= MIN_DEEP_RATIO -> oneToOne.coerceAtMost(ABSOLUTE_MAX_SCALE)
            else -> SMALL_PHOTO_TAP_SCALE
        }

    /** [maxScale] when tiles run, else the old ceiling (a picture the tiles cannot sharpen gains nothing past it). */
    fun maxScaleFor(tilesOn: Boolean, srcW: Int, srcH: Int, containerW: Float, containerH: Float): Float =
        if (tilesOn) maxScale(srcW, srcH, containerW, containerH) else BASE_MAX_SCALE

    /** The 1:1 the double-tap may use: [oneToOne] when tiles run, else 1 (so the fixed small-photo zoom applies). */
    fun oneToOneFor(tilesOn: Boolean, oneToOne: Float): Float = if (tilesOn) oneToOne else 1f

    /** Whether this file's format can be read by region at all. */
    fun regionDecodable(mimeType: String?): Boolean = mimeType?.lowercase() in REGION_MIME

    /**
     * Whether tiles are worth running: the format allows region reads, the file has more pixels
     * than the screen can show at fit ([oneToOne] clearly above 1), the photo is not animated,
     * and no colour filter is changing the pixels the tiles would show unfiltered.
     */
    fun tilesWorthRunning(mimeType: String?, oneToOne: Float, animated: Boolean, colourFiltered: Boolean): Boolean =
        regionDecodable(mimeType) && !animated && !colourFiltered && oneToOne >= MIN_DEEP_RATIO

    /**
     * Whether tiles are LIVE: wanted ([tilesWorthRunning]) AND the region decoder really opened and
     * its tiles may be drawn ([tileDrawable]). The pinch ceiling and the 1:1 double-tap follow this,
     * not the wish, so a file the platform will not region-read (a HEIC on API 26/27, a JPEG it
     * rejects) or a translucent one keeps the old ceiling instead of magnifying the soft picture.
     */
    fun tilesLive(tilesWanted: Boolean, decoderOpen: Boolean): Boolean = tilesWanted && decoderOpen

    /** Formats whose region decode is always opaque, so a tile can be laid over the base picture as is. */
    private val OPAQUE_MIME = setOf("image/jpeg", "image/jpg")

    /**
     * Whether a decoded tile may be drawn OVER the base picture of the same photo. The base keeps
     * drawing beneath, so a translucent pixel would be composited twice (alpha 0.5 shows as 0.75)
     * and a seam would show between tiled and untiled areas. JPEG is always opaque; any other
     * format is drawn only while its tiles decode without alpha.
     */
    fun tileDrawable(mimeType: String?, tileHasAlpha: Boolean): Boolean =
        mimeType?.lowercase() in OPAQUE_MIME || !tileHasAlpha

    /**
     * Whether a probe region must be decoded when the decoder opens, before tiles are reported live:
     * every format but JPEG may carry alpha, and finding it only from the first real tile would raise
     * the ceiling and the 1:1 double-tap and then snap the zoom back down.
     */
    fun needsAlphaProbe(mimeType: String?): Boolean = mimeType?.lowercase() !in OPAQUE_MIME

    /**
     * When the ceiling drops under the current zoom (tiles turned off by a rotation or a colour
     * filter, or found unusable), the zoom and its pan are pulled down to [maxScale] together, so
     * the same point stays at the centre. Unchanged when already within it.
     */
    fun clampToCeiling(scale: Float, offsetX: Float, offsetY: Float, maxScale: Float): Triple<Float, Float, Float> {
        if (scale <= maxScale || scale <= 0f) return Triple(scale, offsetX, offsetY)
        val k = maxScale / scale
        return Triple(maxScale, offsetX * k, offsetY * k)
    }

    /**
     * The pixels per source pixel at layout [scale]: `scale / oneToOne`. This is the [TilePyramid.sampleFor] input.
     */
    fun pixelsPerSourcePx(scale: Float, oneToOne: Float): Float = if (oneToOne > 0f) scale / oneToOne else scale
}
