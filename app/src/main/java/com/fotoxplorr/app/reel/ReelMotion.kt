package com.fotoxplorr.app.reel

import kotlin.math.max
import kotlin.math.min

/** A point on a picture, each side 0..1 from the top left. */
data class NormPoint(val x: Float, val y: Float) {
    val isInside: Boolean get() = x in 0f..1f && y in 0f..1f

    companion object {
        val CENTRE = NormPoint(0.5f, 0.5f)
    }
}

/** A rectangle on a picture, each side 0..1 from the top left. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f

    /** Whether it lies inside the picture and is big enough to look at. */
    val isUsable: Boolean
        get() = left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            width >= ReelMotion.MIN_WINDOW_SIDE && height >= ReelMotion.MIN_WINDOW_SIDE

    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * How a still picture moves while it is shown (the Ken Burns look). It is only ever a SLOW move; no
 * motion turns, tilts or speeds up.
 *
 * - [None]: the picture is fitted to the frame and stays.
 * - [ZoomIn] / [ZoomOut]: the frame goes from the whole picture to a closer view of [focus] (or the
 *   other way). The focus is where the eye should end up; with no better idea the centre.
 * - [Pan]: the frame goes from the window [start] to the window [end], each a rectangle on the picture
 *   (0..1 of its width and height). A rectangle whose shape is not the frame's is grown on its short
 *   side about its centre and kept inside the picture, so a person may draw it loosely.
 *
 * [windowAt] is the one place the maths lives, so the preview and the renderer cannot disagree. It
 * is pure; whether the move looks smooth on a phone has not been seen.
 */
sealed interface ReelMotion {
    data object None : ReelMotion
    data class ZoomIn(val focus: NormPoint = NormPoint.CENTRE) : ReelMotion
    data class ZoomOut(val focus: NormPoint = NormPoint.CENTRE) : ReelMotion
    data class Pan(val start: NormRect, val end: NormRect) : ReelMotion

    companion object {
        /** How much closer a zoom ends than it starts; 1.2 is a fifth closer, which stays slow. */
        const val ZOOM_SCALE = 1.2f

        /** The smallest side of a window or a drawn rectangle, as a share of the picture. */
        const val MIN_WINDOW_SIDE = 0.05f
    }
}

/** The maths of what part of a picture a motion shows. */
object ReelGeometry {

    /**
     * The part of the picture to show at [progress] (0 at the start of the item, 1 at its end; ease it
     * first if you want ease), as a rectangle on the picture. It always has the frame's shape
     * ([frameAspect], width / height; a picture of [imageWidth] x [imageHeight] that is 0 or less in a
     * side is taken to have the frame's shape) and always lies inside the picture.
     *
     * With [ReelMotion.None] it is the whole picture covered by the frame (the largest window of that
     * shape, centred), which crops the picture when the shapes differ. The renderer draws that window
     * into the frame; nothing here letterboxes.
     */
    fun windowAt(
        motion: ReelMotion,
        progress: Float,
        imageWidth: Int,
        imageHeight: Int,
        frameAspect: Float,
    ): NormRect {
        val t = progress.coerceIn(0f, 1f)
        val imageAspect = if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight else frameAspect
        val (startWindow, endWindow) = endpoints(motion, imageAspect, frameAspect)
        return lerp(startWindow, endWindow, t)
    }

    /** The windows at progress 0 and 1; see [windowAt]. */
    fun endpoints(motion: ReelMotion, imageAspect: Float, frameAspect: Float): Pair<NormRect, NormRect> {
        val cover = window(imageAspect, frameAspect, 1f, 0.5f, 0.5f)
        return when (motion) {
            ReelMotion.None -> cover to cover
            is ReelMotion.ZoomIn -> window(imageAspect, frameAspect, 1f, motion.focus.x, motion.focus.y) to
                window(imageAspect, frameAspect, ReelMotion.ZOOM_SCALE, motion.focus.x, motion.focus.y)
            is ReelMotion.ZoomOut -> window(imageAspect, frameAspect, ReelMotion.ZOOM_SCALE, motion.focus.x, motion.focus.y) to
                window(imageAspect, frameAspect, 1f, motion.focus.x, motion.focus.y)
            is ReelMotion.Pan -> fit(motion.start, imageAspect, frameAspect) to fit(motion.end, imageAspect, frameAspect)
        }
    }

    /**
     * The window of the frame's shape that covers the picture, [scale] times closer, centred as near
     * ([cx], [cy]) as the picture allows.
     */
    fun window(imageAspect: Float, frameAspect: Float, scale: Float, cx: Float, cy: Float): NormRect {
        val (coverW, coverH) = coverSize(imageAspect, frameAspect)
        val s = scale.coerceAtLeast(1f)
        return placed(coverW / s, coverH / s, cx, cy)
    }

    /** [rect] reshaped to the frame's shape (see [ReelMotion.Pan]) and kept inside the picture. */
    fun fit(rect: NormRect, imageAspect: Float, frameAspect: Float): NormRect {
        val w = max(rect.width, ReelMotion.MIN_WINDOW_SIDE)
        val h = max(rect.height, ReelMotion.MIN_WINDOW_SIDE)
        // The rectangle's real shape, width / height in pixels: a normalised side is a share of its own axis.
        val shape = (w * imageAspect) / h
        var fw = w
        var fh = h
        if (shape > frameAspect) fh = (w * imageAspect) / frameAspect else fw = (h * frameAspect) / imageAspect
        val (coverW, coverH) = coverSize(imageAspect, frameAspect)
        // Too big to fit the picture: the covering window, which is that shape's largest.
        if (fw > coverW || fh > coverH) {
            fw = coverW
            fh = coverH
        }
        return placed(fw, fh, rect.centreX, rect.centreY)
    }

    /** The largest window of the frame's shape inside the picture, as shares of its width and height. */
    fun coverSize(imageAspect: Float, frameAspect: Float): Pair<Float, Float> {
        val a = if (imageAspect > 0f) imageAspect else frameAspect
        val f = if (frameAspect > 0f) frameAspect else a
        return if (a > f) (f / a) to 1f else 1f to (a / f)
    }

    private fun placed(w: Float, h: Float, cx: Float, cy: Float): NormRect {
        val width = min(w, 1f)
        val height = min(h, 1f)
        val left = (cx - width / 2f).coerceIn(0f, 1f - width)
        val top = (cy - height / 2f).coerceIn(0f, 1f - height)
        return NormRect(left, top, left + width, top + height)
    }

    private fun lerp(a: NormRect, b: NormRect, t: Float): NormRect = NormRect(
        left = a.left + (b.left - a.left) * t,
        top = a.top + (b.top - a.top) * t,
        right = a.right + (b.right - a.right) * t,
        bottom = a.bottom + (b.bottom - a.bottom) * t,
    )

    /** Smooth start and stop, for a caller that wants a gentle move: 0 at 0, 1 at 1, slow at both ends. */
    fun ease(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
