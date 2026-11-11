package com.fotoxplorr.app.viewer.slideshow

import kotlin.math.abs

/**
 * The slow pan-and-zoom of a still, as a straight-line path from one framing to another. Pan is a
 * fraction of the CONTAINER's width and height (so it needs no image size), and the plan keeps
 * every framing inside the photo: at scale `s` the picture overhangs the container by `(s - 1) / 2`
 * on each side, and the pan never exceeds that, so no edge or letterbox bar slides into view.
 * (That holds for photos that fill the container on at least one axis, i.e. every `ContentScale.Fit`
 * photo on the axis it fills; on the other axis the fitted photo has bars anyway and a pan there
 * would show them, so [plan] keeps the pan on the filled axis only via [filledX]/[filledY].)
 */
data class KenBurnsPath(
    val fromScale: Float, val toScale: Float,
    val fromPanX: Float, val toPanX: Float,
    val fromPanY: Float, val toPanY: Float,
) {
    data class Frame(val scale: Float, val panX: Float, val panY: Float) {
        companion object { val REST = Frame(1f, 0f, 0f) }
    }

    /** The framing at progress [t] (0..1), linear in time: Ken Burns moves at a constant, slow rate. */
    fun at(t: Float): Frame {
        val x = t.coerceIn(0f, 1f)
        return Frame(
            fromScale + (toScale - fromScale) * x,
            fromPanX + (toPanX - fromPanX) * x,
            fromPanY + (toPanY - fromPanY) * x,
        )
    }
}

object KenBurns {
    const val MAX_ZOOM = 1.16f
    private const val MIN_ZOOM = 1.0f

    /**
     * A deterministic path for the photo with [seed] (its id): the same photo always drifts the
     * same way, successive photos differ, and zooming in alternates with zooming out. [filledX] /
     * [filledY] say whether the fitted photo fills the container on that axis (otherwise its pan is 0).
     */
    fun plan(seed: Long, filledX: Boolean = true, filledY: Boolean = true): KenBurnsPath {
        val h = mix(seed)
        val zoomIn = (h and 1L) == 0L
        val big = MIN_ZOOM + (MAX_ZOOM - MIN_ZOOM) * (0.6f + 0.4f * unit(h, 8))
        val small = MIN_ZOOM + 0.02f
        val fromScale = if (zoomIn) small else big
        val toScale = if (zoomIn) big else small
        val fromBound = (fromScale - 1f) / 2f
        val toBound = (toScale - 1f) / 2f
        // Pan endpoints: a signed fraction (-0.85..0.85) of each framing's own bound.
        fun frac(shift: Int) = (unit(h, shift) * 2f - 1f) * 0.85f
        return KenBurnsPath(
            fromScale, toScale,
            if (filledX) frac(16) * fromBound else 0f, if (filledX) frac(24) * toBound else 0f,
            if (filledY) frac(32) * fromBound else 0f, if (filledY) frac(40) * toBound else 0f,
        )
    }

    /** True when every framing along [path] stays inside the photo (within [eps]). */
    fun staysInside(path: KenBurnsPath, steps: Int = 20, eps: Float = 1e-5f): Boolean =
        (0..steps).all { i ->
            val f = path.at(i / steps.toFloat())
            val bound = (f.scale - 1f) / 2f + eps
            f.scale >= 1f && abs(f.panX) <= bound && abs(f.panY) <= bound
        }

    /** SplitMix64 finaliser: spreads a sequential id into well-mixed bits. */
    private fun mix(seed: Long): Long {
        var z = seed + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun unit(h: Long, shift: Int): Float = ((h ushr shift) and 0xFFL) / 255f
}
