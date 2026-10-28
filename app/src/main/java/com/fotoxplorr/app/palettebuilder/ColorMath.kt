package com.fotoxplorr.app.palettebuilder

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** CIE L*a*b* (D65) of one colour. */
data class Lab(val l: Double, val a: Double, val b: Double)

/** The polar form of [Lab]: lightness, chroma and hue in degrees, 0..360. */
data class Lch(val l: Double, val c: Double, val h: Double)

/** Hue in degrees 0..360, saturation and lightness 0..1. */
data class Hsl(val h: Double, val s: Double, val l: Double)

/**
 * The colour arithmetic the palette builder stands on: sRGB, linear light, XYZ, Lab and LCh, HSL, hex.
 *
 * Pure Kotlin on packed ARGB `Int`s (alpha is ignored on the way in and written as 0xFF on the way
 * out: a palette colour is opaque). D65 white, the sRGB primaries, doubles throughout. The JVM's
 * floating point is deterministic from Java 17, so the same colour always gives the same bytes, which is
 * what lets the tests pin exact values. Distances are CIE76 (plain Euclid in Lab): cheap, and good
 * enough for "are these two swatches the same colour" and "which swatch is nearest". It is not the
 * most perceptually uniform formula, and nothing here claims it is.
 */
object ColorMath {

    private val toLinearTable = DoubleArray(256) { v ->
        val c = v / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private const val EPSILON = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0

    /** The linear-light value, 0..1, of an 8-bit sRGB channel. */
    fun channelToLinear(v: Int): Double = toLinearTable[v and 0xFF]

    /** The nearest 8-bit sRGB channel for a linear-light value (clamped to 0..255). */
    fun linearToChannel(x: Double): Int {
        val c = if (x <= 0.0) 0.0 else if (x >= 1.0) 1.0 else x
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** [argb] with its alpha forced to 0xFF. */
    fun opaque(argb: Int): Int = argb or (0xFF shl 24)

    // ---- hex ----

    /** `#RRGGBB`, upper-case. */
    fun hex(argb: Int): String = "#%02X%02X%02X".format(red(argb), green(argb), blue(argb))

    /**
     * An opaque colour from `#RRGGBB`, `RRGGBB`, `#RGB` or `RGB` (any case), or null for anything else.
     * Never throws.
     */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#")
        if (t.any { Character.digit(it, 16) < 0 }) return null
        return when (t.length) {
            6 -> rgb(t.substring(0, 2).toInt(16), t.substring(2, 4).toInt(16), t.substring(4, 6).toInt(16))
            3 -> rgb(t[0].digitToInt(16) * 17, t[1].digitToInt(16) * 17, t[2].digitToInt(16) * 17)
            else -> null
        }
    }

    // ---- Lab / LCh ----

    fun lab(argb: Int): Lab {
        val out = DoubleArray(3)
        labInto(argb, out)
        return Lab(out[0], out[1], out[2])
    }

    /** [lab] without the allocation, for the per-pixel loop: writes L, a, b to `out[0..2]`. */
    fun labInto(argb: Int, out: DoubleArray) {
        val r = toLinearTable[red(argb)]
        val g = toLinearTable[green(argb)]
        val b = toLinearTable[blue(argb)]
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        out[0] = 116.0 * fy - 16.0
        out[1] = 500.0 * (fx - fy)
        out[2] = 200.0 * (fy - fz)
    }

    private fun labF(t: Double): Double = if (t > EPSILON) cbrt(t) else (KAPPA * t + 16.0) / 116.0

    private fun labFInverse(f: Double): Double {
        val cubed = f * f * f
        return if (cubed > EPSILON) cubed else (116.0 * f - 16.0) / KAPPA
    }

    /** Linear sRGB (not clamped, may fall outside 0..1) of a Lab colour, into `out[0..2]`. */
    private fun labToLinear(l: Double, a: Double, b: Double, out: DoubleArray) {
        val fy = (l + 16.0) / 116.0
        val x = labFInverse(fy + a / 500.0) * XN
        val y = labFInverse(fy) * YN
        val z = labFInverse(fy - b / 200.0) * ZN
        out[0] = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        out[1] = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        out[2] = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    }

    private const val GAMUT_SLACK = 1e-6

    private fun inGamut(lin: DoubleArray): Boolean =
        lin.all { it >= -GAMUT_SLACK && it <= 1.0 + GAMUT_SLACK }

    /**
     * The opaque sRGB colour for a Lab value. A colour outside what sRGB can show is brought in by
     * lowering its chroma at the SAME lightness and hue (bisection, 24 steps), not by clamping each
     * channel, which would shift the hue.
     */
    fun fromLab(l: Double, a: Double, b: Double): Int {
        val ll = l.coerceIn(0.0, 100.0)
        val lin = DoubleArray(3)
        labToLinear(ll, a, b, lin)
        if (!inGamut(lin)) {
            var lo = 0.0
            var hi = 1.0
            repeat(24) {
                val mid = (lo + hi) / 2
                labToLinear(ll, a * mid, b * mid, lin)
                if (inGamut(lin)) lo = mid else hi = mid
            }
            labToLinear(ll, a * lo, b * lo, lin)
        }
        return rgb(linearToChannel(lin[0]), linearToChannel(lin[1]), linearToChannel(lin[2]))
    }

    fun lch(argb: Int): Lch {
        val lab = lab(argb)
        val c = hypot(lab.a, lab.b)
        val h = if (c < 1e-9) 0.0 else Math.toDegrees(atan2(lab.b, lab.a)).let { if (it < 0) it + 360.0 else it }
        return Lch(lab.l, c, h)
    }

    fun fromLch(l: Double, c: Double, hDegrees: Double): Int {
        val rad = Math.toRadians(hDegrees)
        val chroma = max(0.0, c)
        return fromLab(l, chroma * cos(rad), chroma * sin(rad))
    }

    /** CIE76: the straight-line distance between two colours in Lab. 0 is the same colour. */
    fun deltaE(a: Lab, b: Lab): Double = hypot(hypot(a.l - b.l, a.a - b.a), a.b - b.b)

    fun deltaE(a: Int, b: Int): Double = deltaE(lab(a), lab(b))

    // ---- HSL ----

    fun hsl(argb: Int): Hsl {
        val r = red(argb) / 255.0
        val g = green(argb) / 255.0
        val b = blue(argb) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val l = (hi + lo) / 2
        val d = hi - lo
        if (d < 1e-12) return Hsl(0.0, 0.0, l)
        val s = d / (1 - abs(2 * l - 1))
        val h = when (hi) {
            r -> ((g - b) / d).mod(6.0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60.0
        return Hsl(h, s.coerceIn(0.0, 1.0), l)
    }

    fun fromHsl(hDegrees: Double, s: Double, l: Double): Int {
        val h = hDegrees.mod(360.0)
        val sat = s.coerceIn(0.0, 1.0)
        val light = l.coerceIn(0.0, 1.0)
        val c = (1 - abs(2 * light - 1)) * sat
        val x = c * (1 - abs((h / 60.0).mod(2.0) - 1))
        val m = light - c / 2
        val (r, g, b) = when ((h / 60.0).toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }
}
