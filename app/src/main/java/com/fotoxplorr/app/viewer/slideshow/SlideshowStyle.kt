package com.fotoxplorr.app.viewer.slideshow

import kotlin.math.min

/** How one photo gives way to the next in a slideshow. */
enum class SlideshowTransition(val label: String) {
    NONE("Cut"),
    FADE("Fade"),
    PUSH("Slide"),
    ZOOM("Zoom fade");

    /** The next choice in the cycle the view-options row steps through. */
    fun next(): SlideshowTransition = values()[(ordinal + 1) % values().size]
}

/** The slideshow's look, remembered between runs. No music: the product has no audio features. */
data class SlideshowStyle(
    val transition: SlideshowTransition = SlideshowTransition.FADE,
    val kenBurns: Boolean = false,
) {
    /**
     * What actually plays: nothing animated when the system's animations are off (the person asked
     * for no motion; a slideshow honours that by cutting, and a still photo stays still).
     */
    fun effective(animationsEnabled: Boolean): SlideshowStyle =
        if (animationsEnabled) this else SlideshowStyle(SlideshowTransition.NONE, kenBurns = false)

    /** The transition's length: 600 ms, but never more than a quarter of the time a photo is held. */
    fun transitionMs(intervalSeconds: Int): Int =
        if (transition == SlideshowTransition.NONE) 0 else min(MAX_TRANSITION_MS, intervalSeconds.coerceAtLeast(2) * 250)

    companion object {
        const val MAX_TRANSITION_MS = 600
    }

    object Copy {
        const val TRANSITION_ROW = "Slideshow transition"
        const val TRANSITION_CAPTION = "How one photo gives way to the next."
        const val KEN_BURNS_ROW = "Slow pan and zoom"
        const val KEN_BURNS_CAPTION = "Drifts slowly across each photo while it is shown."
    }
}

/**
 * One frame of a transition: how the INCOMING and OUTGOING photos are drawn at progress `t`.
 * Shifts are fractions of the screen width (positive = to the right).
 */
data class TransitionFrame(
    val inAlpha: Float, val inShift: Float, val inScale: Float,
    val outAlpha: Float, val outShift: Float, val outScale: Float,
) {
    /** Whether the outgoing photo is still visible at all. */
    val outgoingVisible: Boolean get() = outAlpha > 0.001f

    companion object {
        val DONE = TransitionFrame(1f, 0f, 1f, 0f, 0f, 1f)
    }
}

/** The motion of each transition as a pure function of progress, so it can be checked without a screen. */
object SlideshowTransitions {
    /** Smoothstep: ease in and out, exact at both ends. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * [t] 0 (the outgoing photo fills the screen) to 1 (the incoming one does). [forward] false
     * mirrors the slide for a step back.
     */
    fun frame(kind: SlideshowTransition, t: Float, forward: Boolean = true): TransitionFrame {
        if (t >= 1f || kind == SlideshowTransition.NONE) return TransitionFrame.DONE
        val e = ease(t)
        val dir = if (forward) 1f else -1f
        return when (kind) {
            SlideshowTransition.NONE -> TransitionFrame.DONE
            // The newcomer is already full under the leaver, which fades away.
            SlideshowTransition.FADE -> TransitionFrame(1f, 0f, 1f, 1f - e, 0f, 1f)
            // Both move together, the newcomer arriving as the leaver goes.
            SlideshowTransition.PUSH -> TransitionFrame(1f, dir * (1f - e), 1f, 1f, -dir * e, 1f)
            // The leaver grows slightly as it fades, the newcomer settles in from a little larger.
            SlideshowTransition.ZOOM -> TransitionFrame(1f, 0f, 1f + ZOOM_DRIFT * (1f - e), 1f - e, 0f, 1f + ZOOM_DRIFT * e)
        }
    }

    private const val ZOOM_DRIFT = 0.06f
}
