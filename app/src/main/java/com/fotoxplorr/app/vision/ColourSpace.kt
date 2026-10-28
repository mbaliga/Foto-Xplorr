package com.fotoxplorr.app.vision

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** A CIE L*a*b* colour (D65 white, 2 degree observer). */
internal class Lab(val l: Double, val a: Double, val b: Double) {
    /** Distance from the grey axis; 0 for a neutral colour, about 105 for a saturated red. */
    val chroma: Double get() = sqrt(a * a + b * b)
}

/**
 * sRGB, linear RGB and CIE Lab, pure and allocation-light. Packed colours are Android's `0xAARRGGBB`
 * ints, but nothing here touches the platform. Linear RGB is the sRGB primaries with the transfer
 * curve removed (IEC 61966-2-1), values 0..1.
 */
internal object ColourSpace {
    private val DECODE = DoubleArray(256) { toLinear(it / 255.0) }

    // Linear sRGB -> XYZ (D65), IEC 61966-2-1.
    private const val XR = 0.4124564
    private const val XG = 0.3575761
    private const val XB = 0.1804375
    private const val YR = 0.2126729
    private const val YG = 0.7151522
    private const val YB = 0.0721750
    private const val ZR = 0.0193339
    private const val ZG = 0.1191920
    private const val ZB = 0.9503041

    // The white the matrix above maps (1,1,1) to, so a grey has a* = b* = 0 to rounding.
    private const val WHITE_X = XR + XG + XB
    private const val WHITE_Y = YR + YG + YB
    private const val WHITE_Z = ZR + ZG + ZB

    /** sRGB 0..1 to linear 0..1. */
    fun toLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    /** Linear to sRGB, both 0..1; a value outside 0..1 is clamped first. */
    fun fromLinear(v: Double): Double {
        val c = v.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
    }

    /** One 8-bit sRGB channel to linear. */
    fun decode8(v: Int): Double = DECODE[v and 0xFF]

    /** Linear to one 8-bit sRGB channel, rounded and clamped. */
    fun encode8(v: Double): Int = (fromLinear(v) * 255.0 + 0.5).toInt()

    fun red(argb: Int) = (argb shr 16) and 0xFF
    fun green(argb: Int) = (argb shr 8) and 0xFF
    fun blue(argb: Int) = argb and 0xFF
    fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    fun pack(alpha: Int, r: Int, g: Int, b: Int): Int = (alpha shl 24) or (r shl 16) or (g shl 8) or b

    /** Multiplies [m] (row-major 3x3, linear RGB) into an opaque-or-not colour; alpha is kept. */
    fun applyLinear(m: DoubleArray, argb: Int): Int {
        val r = decode8(red(argb))
        val g = decode8(green(argb))
        val b = decode8(blue(argb))
        return pack(
            alpha(argb),
            encode8(m[0] * r + m[1] * g + m[2] * b),
            encode8(m[3] * r + m[4] * g + m[5] * b),
            encode8(m[6] * r + m[7] * g + m[8] * b),
        )
    }

    fun labOfLinear(r: Double, g: Double, b: Double): Lab {
        val fx = labF((XR * r + XG * g + XB * b) / WHITE_X)
        val fy = labF((YR * r + YG * g + YB * b) / WHITE_Y)
        val fz = labF((ZR * r + ZG * g + ZB * b) / WHITE_Z)
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    fun lab(argb: Int): Lab = labOfLinear(decode8(red(argb)), decode8(green(argb)), decode8(blue(argb)))

    private fun labF(t: Double): Double =
        if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0

    /** CIE76 colour difference, plain Euclidean distance in Lab. About 2.3 is a just-noticeable step side by side. */
    fun deltaE(x: Lab, y: Lab): Double {
        val dl = x.l - y.l
        val da = x.a - y.a
        val db = x.b - y.b
        return sqrt(dl * dl + da * da + db * db)
    }

    fun deltaE(x: Int, y: Int): Double = deltaE(lab(x), lab(y))
}

/** Row-major 3x3 helpers for the colour matrices. */
internal object Mat3 {
    val IDENTITY: DoubleArray get() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun sub(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { a[it] - b[it] }

    fun add(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { a[it] + b[it] }

    fun determinant(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    /** The inverse by cofactors. Throws for a singular matrix; every caller passes a fixed invertible one. */
    fun invert(m: DoubleArray): DoubleArray {
        val det = determinant(m)
        require(kotlin.math.abs(det) > 1e-12) { "singular matrix" }
        val inv = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * inv,
            (m[2] * m[7] - m[1] * m[8]) * inv,
            (m[1] * m[5] - m[2] * m[4]) * inv,
            (m[5] * m[6] - m[3] * m[8]) * inv,
            (m[0] * m[8] - m[2] * m[6]) * inv,
            (m[2] * m[3] - m[0] * m[5]) * inv,
            (m[3] * m[7] - m[4] * m[6]) * inv,
            (m[1] * m[6] - m[0] * m[7]) * inv,
            (m[0] * m[4] - m[1] * m[3]) * inv,
        )
    }
}
