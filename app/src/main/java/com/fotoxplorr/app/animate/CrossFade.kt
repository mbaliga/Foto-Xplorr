package com.fotoxplorr.app.animate

import kotlin.math.pow

/** How a transition's progress runs from 0 to 1 over its frames. */
enum class Easing {
    LINEAR,

    /** Slow start, quick finish. */
    EASE_IN,

    /** Quick start, slow finish. */
    EASE_OUT,

    /** Slow at both ends (smoothstep). */
    EASE_IN_OUT,

    /** Slower at both ends than [EASE_IN_OUT] (smootherstep). */
    SMOOTHER,
    ;

    /** Progress for a linear [t] in 0..1, also in 0..1, with 0 and 1 mapping to themselves. */
    fun apply(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> x
            EASE_IN -> x * x
            EASE_OUT -> 1.0 - (1.0 - x) * (1.0 - x)
            EASE_IN_OUT -> x * x * (3.0 - 2.0 * x)
            SMOOTHER -> x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
        }
    }
}

/**
 * Cross-fades between stills, so a set of pictures (a photo and its edits, or a few different
 * photos) can run as a smooth animation instead of a slideshow of cuts.
 *
 * A fade mixes colour in the gamma-encoded values by default, which is what a plain image
 * editor's "opacity" does and why the middle of a black-to-white fade looks like mid-grey (value
 * 128). Mixing in linear light ([linearLight]) is physically what two lamps would do: the middle
 * is then 188, brighter, and dark-to-light fades hold their brightness longer. Neither is "right".
 * Alpha is mixed as alpha, and colour is weighted by alpha, so a fade between a picture and
 * nothing does not darken the picture's edge.
 */
object CrossFade {

    /** One in-between picture: [from] and [to] mixed with weight [t] (0 gives [from], 1 gives [to]). */
    fun blend(from: IntArray, to: IntArray, t: Double, linearLight: Boolean = false): IntArray {
        require(from.size == to.size) { "Both pictures must be the same size" }
        val out = IntArray(from.size)
        val k = t.coerceIn(0.0, 1.0)
        if (k == 0.0) return from.copyOf()
        if (k == 1.0) return to.copyOf()
        val wTo = (k * 256.0).toInt().coerceIn(0, 256)
        val wFrom = 256 - wTo
        for (i in from.indices) {
            val a = from[i]
            val b = to[i]
            if (a == b) {
                out[i] = a
                continue
            }
            val aa = a ushr 24
            val ba = b ushr 24
            val alpha = (aa * wFrom + ba * wTo + 128) shr 8
            if (alpha == 0) {
                out[i] = 0
                continue
            }
            // Colour weighted by (alpha x mix weight), so clear pixels contribute nothing.
            val fa = aa * wFrom
            val fb = ba * wTo
            val denom = fa + fb
            fun ch(shift: Int): Int {
                val ca = (a shr shift) and 255
                val cb = (b shr shift) and 255
                return if (linearLight) {
                    val la = TO_LINEAR[ca]
                    val lb = TO_LINEAR[cb]
                    toSrgb((la * fa + lb * fb) / denom)
                } else {
                    (ca * fa + cb * fb + denom / 2) / denom
                }
            }
            out[i] = (alpha.coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }

    /**
     * [count] in-between pictures strictly between [from] and [to] (neither end included), their
     * weights following [easing]. Frame `i` of `count` sits at progress `(i + 1) / (count + 1)`.
     */
    fun between(from: IntArray, to: IntArray, count: Int, easing: Easing = Easing.EASE_IN_OUT, linearLight: Boolean = false): List<IntArray> {
        require(count >= 0)
        return List(count) { i -> blend(from, to, easing.apply((i + 1.0) / (count + 1.0)), linearLight) }
    }

    /**
     * A looping-friendly animation through [stills]: each is held for [holdMs], then [transitionFrames]
     * in-betweens of [frameMs] each carry it to the next. With [loop] the last fades back to the first, so
     * the join is as smooth as every other. A still is held as ONE frame with a long delay (not
     * repeated), which costs a GIF nothing.
     *
     * All stills must be the same size (see [FrameScaler.fit]).
     */
    fun morph(
        width: Int,
        height: Int,
        stills: List<IntArray>,
        holdMs: Int,
        transitionFrames: Int,
        frameMs: Int,
        easing: Easing = Easing.EASE_IN_OUT,
        loop: Boolean = true,
        linearLight: Boolean = false,
    ): AnimSequence {
        require(stills.isNotEmpty()) { "Nothing to show" }
        require(holdMs >= 0 && transitionFrames >= 0 && frameMs >= 0)
        val frames = ArrayList<AnimFrame>()
        for ((i, s) in stills.withIndex()) {
            require(s.size == width * height) { "Still ${i + 1} is not $width x $height" }
            frames.add(AnimFrame(s, holdMs))
            val last = i == stills.size - 1
            if (last && !(loop && stills.size > 1)) break
            val nextStill = stills[(i + 1) % stills.size]
            for (mix in between(s, nextStill, transitionFrames, easing, linearLight)) frames.add(AnimFrame(mix, frameMs))
        }
        return AnimSequence(width, height, frames, plays = 0)
    }

    private val TO_LINEAR = DoubleArray(256) { v ->
        val c = v / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb(lin: Double): Int {
        val c = if (lin <= 0.0031308) lin * 12.92 else 1.055 * lin.pow(1.0 / 2.4) - 0.055
        return (c * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }
}
