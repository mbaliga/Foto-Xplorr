package com.fotoxplorr.app.chroma

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CIEDE2000 colour difference, written from the formulae in Sharma, Wu and Dalal, "The CIEDE2000
 * Color-Difference Formula: Implementation Notes, Supplementary Test Data, and Mathematical
 * Observations" (2005), with kL = kC = kH = 1. `DeltaE2000Test` runs all 34 of that paper's
 * published pairs.
 *
 * Roughly: 1 is about the least a trained eye sees side by side; 2 to 3 is a visible but small
 * difference; 5 or more is "a different shade". The formula is symmetric in its two colours.
 */
internal object DeltaE2000 {

    /**
     * The difference split into its three perceptual terms. Each of [lightness], [chroma] and [hue]
     * is SIGNED (second colour minus first: positive means the second is lighter, more vivid, or
     * further round the hue circle) and already divided by its weighting function, so their sizes
     * can be compared with one another; [total] is the full CIEDE2000 value, which also includes the
     * blue-region rotation term.
     */
    class Parts(val total: Double, val lightness: Double, val chroma: Double, val hue: Double)

    fun between(a: Lab, b: Lab): Double = parts(a, b).total

    fun parts(a: Lab, b: Lab): Parts {
        val c1 = hypot(a.a, a.b)
        val c2 = hypot(b.a, b.b)
        val cBar = (c1 + c2) / 2.0
        val cBar7 = cBar.pow(7)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + POW25_7)))
        val a1p = (1.0 + g) * a.a
        val a2p = (1.0 + g) * b.a
        val c1p = hypot(a1p, a.b)
        val c2p = hypot(a2p, b.b)
        val h1p = hueDegrees(a.b, a1p)
        val h2p = hueDegrees(b.b, a2p)

        val dLp = b.l - a.l
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p - h1p > 180.0 -> h2p - h1p - 360.0
            else -> h2p - h1p + 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))

        val lBarp = (a.l + b.l) / 2.0
        val cBarp = (c1p + c2p) / 2.0
        val hBarp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }
        val t = 1.0 -
            0.17 * cos(Math.toRadians(hBarp - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hBarp)) +
            0.32 * cos(Math.toRadians(3.0 * hBarp + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hBarp - 63.0))
        val dTheta = 30.0 * exp(-(((hBarp - 275.0) / 25.0).pow(2)))
        val cBarp7 = cBarp.pow(7)
        val rc = 2.0 * sqrt(cBarp7 / (cBarp7 + POW25_7))
        val lm50sq = (lBarp - 50.0).pow(2)
        val sl = 1.0 + 0.015 * lm50sq / sqrt(20.0 + lm50sq)
        val sc = 1.0 + 0.045 * cBarp
        val sh = 1.0 + 0.015 * cBarp * t
        val rt = -sin(Math.toRadians(2.0 * dTheta)) * rc

        val tl = dLp / sl
        val tc = dCp / sc
        val th = dHp / sh
        val total = sqrt(tl * tl + tc * tc + th * th + rt * tc * th)
        return Parts(total, tl, tc, th)
    }

    private fun hueDegrees(b: Double, ap: Double): Double =
        if (b == 0.0 && ap == 0.0) 0.0 else (Math.toDegrees(atan2(b, ap)) + 360.0) % 360.0

    private val POW25_7 = 25.0.pow(7)
}
