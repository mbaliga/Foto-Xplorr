package com.fotoxplorr.app.viewer

import kotlin.math.abs
import kotlin.math.cos

/**
 * The filmstrip's geometry, as pure functions: the loupe curve, how the strip bulges while it is
 * being dragged, and which thumbnail is "the one under the finger". Kept out of the composable
 * for the same reason [PlaceMorph] is: a strip that chooses the wrong photo, or bulges with a
 * kink, is the kind of thing nobody files a bug about -- they just find the app annoying -- so
 * each judgement is pinned by a test instead.
 *
 * Every number here is dp, measured off the owner's reference (5.png at 1 px = 1 dp): a resting
 * band of [REST_BAND_DP] holding [THUMB_HEIGHT_DP]-tall thumbnails [THUMB_WIDTH_DP] wide with a
 * [THUMB_GAP_DP] gap, and [BOTTOM_PAD_DP] of black under them.
 */
object FilmstripGeometry {

    /** One width for every thumbnail; the current one is NOT laid out larger (see the scrubber). */
    const val THUMB_WIDTH_DP = 53
    const val THUMB_HEIGHT_DP = 58
    const val THUMB_GAP_DP = 1
    const val BOTTOM_PAD_DP = 16

    /** The band at rest: thumbnails plus the black strip under them. 74 dp in the reference. */
    const val REST_BAND_DP = THUMB_HEIGHT_DP + BOTTOM_PAD_DP

    /**
     * The band while the strip is being dragged. Tall enough that a thumbnail at
     * [BULGE_PEAK_SCALE] fits inside it with its bottom edge where it was: the LazyRow grows
     * upward and the magnified thumbnails grow into the headroom, so nothing has to draw outside
     * its own bounds (where a scrollable container would clip it).
     */
    const val BULGE_BAND_DP = 116

    /** Scale of the thumbnail exactly under the centre at full bulge. 1x at rest. */
    const val BULGE_PEAK_SCALE = 1.7f

    /**
     * Distance from centre at which magnification has fully eased back to 1x, in dp. About a
     * thumbnail and a half either side, so the loupe reads as "the one under the frame, and a
     * hint of its immediate neighbours" rather than rippling down the whole visible strip.
     */
    const val MAGNIFY_RADIUS_DP = 82f

    /** The band's height for a bulge of [bulge] (0 = resting, 1 = fully bulged). */
    fun bandHeightDp(bulge: Float): Float {
        val t = bulge.coerceIn(0f, 1f)
        return REST_BAND_DP + (BULGE_BAND_DP - REST_BAND_DP) * t
    }

    /** The loupe's peak scale for a bulge of [bulge]: exactly 1x at rest, so the resting strip is flat. */
    fun peakScale(bulge: Float): Float = 1f + (BULGE_PEAK_SCALE - 1f) * bulge.coerceIn(0f, 1f)

    /**
     * The loupe curve: how much a thumbnail scales up, given its pixel distance from the strip's
     * fixed centre.
     *
     * A raised cosine (a Hann window), not linear or a hard cutoff: it starts and ends with ZERO
     * slope, so a thumbnail eases into and back out of magnification instead of visibly switching
     * on the instant it crosses [radiusPx] -- a kink there reads as a seam under a slow drag,
     * which a smooth falloff does not have.
     */
    fun magnification(distancePx: Float, radiusPx: Float, peakScale: Float): Float {
        if (radiusPx <= 0f) return if (distancePx == 0f) peakScale else 1f
        val t = (abs(distancePx) / radiusPx).coerceIn(0f, 1f)
        val falloff = (cos(t * Math.PI.toFloat()) + 1f) / 2f
        return 1f + (peakScale - 1f) * falloff
    }

    /**
     * Which item sits nearest the viewport's centre, given each visible item's (index, offset,
     * size) along the scroll axis, or null when nothing is visible. Ties go to the earlier item,
     * which keeps the answer stable as two thumbnails straddle the centre on a slow drag.
     */
    fun nearestToCentre(
        viewportStart: Int,
        viewportEnd: Int,
        visible: List<VisibleItem>,
    ): Int? = nearestToCentre(
        viewportStart = viewportStart,
        viewportEnd = viewportEnd,
        count = visible.size,
        indexAt = { visible[it].index },
        offsetAt = { visible[it].offset },
        sizeAt = { visible[it].size },
    )

    /**
     * The same answer as the list form, over accessors -- inline, so the strip can ask it of the
     * list's own layout info on every scroll frame without building a list to do so.
     */
    inline fun nearestToCentre(
        viewportStart: Int,
        viewportEnd: Int,
        count: Int,
        indexAt: (position: Int) -> Int,
        offsetAt: (position: Int) -> Int,
        sizeAt: (position: Int) -> Int,
    ): Int? {
        if (count <= 0) return null
        val centre = (viewportStart + viewportEnd) / 2f
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        for (position in 0 until count) {
            val distance = abs(offsetAt(position) + sizeAt(position) / 2f - centre)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = indexAt(position)
            }
        }
        return if (bestIndex < 0) null else bestIndex
    }

    /** One visible item of the strip, in the scroll axis's own pixels. */
    data class VisibleItem(val index: Int, val offset: Int, val size: Int)
}

/**
 * Kept as a top-level alias so the existing test and the scrubber's older callers keep their
 * name; the curve itself now lives on [FilmstripGeometry] beside the rest of the strip's maths.
 */
internal fun filmstripMagnification(distancePx: Float, radiusPx: Float, peakScale: Float): Float =
    FilmstripGeometry.magnification(distancePx, radiusPx, peakScale)
