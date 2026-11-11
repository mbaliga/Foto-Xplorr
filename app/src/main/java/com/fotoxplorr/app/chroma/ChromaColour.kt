package com.fotoxplorr.app.chroma

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/** A colour in LINEAR light with the sRGB primaries, one number per channel, nominally 0..1 (not clamped). */
internal data class LinearRgb(val r: Double, val g: Double, val b: Double) {
    operator fun get(channel: Int): Double = when (channel) {
        0 -> r
        1 -> g
        else -> b
    }

    /** Relative luminance (Y) of this colour, with white = 1. */
    val luminance: Double get() = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b

    inline fun map(f: (Double) -> Double): LinearRgb = LinearRgb(f(r), f(g), f(b))

    companion object {
        fun of(f: (Int) -> Double): LinearRgb = LinearRgb(f(0), f(1), f(2))
    }
}

/** CIE L*a*b* under D65. [chroma] and [hueDegrees] are the cylindrical form. */
internal data class Lab(val l: Double, val a: Double, val b: Double) {
    val chroma: Double get() = hypot(a, b)

    /** 0 up to (excluding) 360; 0 for a colour with no chroma. */
    val hueDegrees: Double
        get() = if (a == 0.0 && b == 0.0) 0.0 else (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0
}

/** CIE LCh(ab): lightness, chroma and hue angle in degrees. */
internal data class Lch(val l: Double, val c: Double, val h: Double)

/**
 * sRGB <-> linear light <-> XYZ (D65) <-> L*a*b*, the one place the chroma package does its colour
 * arithmetic. A pixel is an Android ARGB int whose R, G, B are sRGB-encoded; everything the flash
 * solver does happens on [LinearRgb], because adding and subtracting light only works in linear
 * units (a difference of gamma-encoded numbers is not a difference of light).
 *
 * Honest limit: this is the sRGB standard curve. A phone's JPEG applies its own tone curve on top
 * (and some phones apply local tone mapping), so a decoded JPEG is only approximately sRGB-of-linear;
 * the flash differencing assumes it is, and the measured accuracy in the tests is for frames that
 * obey it.
 */
internal object ColourSpace {
    private const val EPSILON = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0

    // The D65 white of the sRGB matrix (its rows sum to these).
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    private val toLinearTable = DoubleArray(256) { decodeUnit(it / 255.0) }

    private fun decodeUnit(v: Double): Double =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun encodeUnit(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    /** The linear light of one sRGB channel code 0..255. */
    fun decode(code: Int): Double = toLinearTable[code and 0xFF]

    /** The nearest sRGB channel code for [linear], which is clamped to 0..1 first. */
    fun encode(linear: Double): Int = Math.round(encodeUnit(linear) * 255.0).toInt()

    /** Linear light per code step at [code] (the slope of the curve), the size of one quantisation step. */
    fun stepAt(code: Int): Double {
        val c = code.coerceIn(0, 254)
        return toLinearTable[c + 1] - toLinearTable[c]
    }

    fun linearOf(argb: Int): LinearRgb =
        LinearRgb(decode(argb shr 16), decode(argb shr 8), decode(argb))

    /** The opaque ARGB int nearest to [c]; each channel is clamped to 0..1 first. */
    fun argbOf(c: LinearRgb): Int =
        (0xFF shl 24) or (encode(c.r) shl 16) or (encode(c.g) shl 8) or encode(c.b)

    fun lab(c: LinearRgb): Lab {
        val x = (0.4124564 * c.r + 0.3575761 * c.g + 0.1804375 * c.b) / XN
        val y = (0.2126729 * c.r + 0.7151522 * c.g + 0.0721750 * c.b) / YN
        val z = (0.0193339 * c.r + 0.1191920 * c.g + 0.9503041 * c.b) / ZN
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    fun lab(argb: Int): Lab = lab(linearOf(argb))

    /** The linear colour of [lab], NOT clamped: a Lab outside the sRGB gamut gives channels outside 0..1. */
    fun linear(lab: Lab): LinearRgb {
        val fy = (lab.l + 16.0) / 116.0
        val fx = fy + lab.a / 500.0
        val fz = fy - lab.b / 200.0
        val x = labFInverse(fx) * XN
        val y = (if (lab.l > KAPPA * EPSILON) fy * fy * fy else lab.l / KAPPA) * YN
        val z = labFInverse(fz) * ZN
        return LinearRgb(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
        )
    }

    fun argbOf(lab: Lab): Int = argbOf(linear(lab))

    /**
     * [lab] with its light scaled to the relative luminance [y], keeping its chromaticity (its shade). A
     * colour's a* and b* grow with its luminance, so two readings of one surface at different luminances
     * differ in a* and b* too; this is how to compare them by shade alone. Black has no shade, so it
     * comes back as the neutral at [y]. Quantised to 8 bits a very dark colour has a coarse shade, so the
     * result for one is only as good as that.
     */
    fun atLuminance(lab: Lab, y: Double): Lab {
        val c = linear(lab)
        val now = c.luminance
        if (now < 1e-6) return lab(LinearRgb(y, y, y))
        return lab(c.map { it * y / now })
    }

    fun lch(lab: Lab): Lch = Lch(lab.l, lab.chroma, lab.hueDegrees)

    /** "#RRGGBB" in capitals. */
    fun hex(argb: Int): String = "#%02X%02X%02X".format((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /** The ARGB int of "#RRGGBB" / "RRGGBB", or null when it is not six hex digits. */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#")
        if (t.length != 6) return null
        val v = t.toIntOrNull(16) ?: return null
        return (0xFF shl 24) or v
    }

    private fun labF(t: Double): Double = if (t > EPSILON) cbrt(t) else (KAPPA * t + 16.0) / 116.0

    private fun labFInverse(f: Double): Double {
        val cubed = f * f * f
        return if (cubed > EPSILON) cubed else (116.0 * f - 16.0) / KAPPA
    }
}
