package com.fotoxplorr.app.vision

import kotlin.math.pow

/** Shared helpers for the colour-vision tests; independent of the code under test where it matters. */
internal object VisionTestKit {
    fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    /** A second, separate sRGB decode (IEC 61966-2-1), so tests do not lean on [ColourSpace]. */
    fun decode(v: Int): Double {
        val x = v / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    fun encode(l: Double): Int {
        val c = l.coerceIn(0.0, 1.0)
        val x = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        return Math.round(x * 255.0).toInt()
    }

    /** 9 levels per channel: 729 colours from black to white, the grid the KDoc numbers were measured on. */
    val GRID9: List<Int> = buildList {
        val levels = IntArray(9) { minOf(it * 32, 255) }
        for (r in levels) for (g in levels) for (b in levels) add(argb(r, g, b))
    }

    fun maxChannelDiff(x: Int, y: Int): Int = maxOf(
        kotlin.math.abs(r(x) - r(y)),
        kotlin.math.abs(g(x) - g(y)),
        kotlin.math.abs(b(x) - b(y)),
    )

    /** An sRGB colour from a linear-light triple (clamped). */
    fun fromLinear(rgb: DoubleArray): Int = argb(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]))
}
