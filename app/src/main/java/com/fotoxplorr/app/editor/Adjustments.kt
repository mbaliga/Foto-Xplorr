package com.fotoxplorr.app.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Everything that can be done to a photograph's colour, as data.
 *
 * The editor is non-destructive by construction: this object holds no pixels and mutates nothing.
 * Pixels are produced from it when something needs drawing or saving, at whatever resolution that
 * consumer needs — which is what lets the preview run on a downscaled bitmap while export runs on
 * the full-resolution one, from the same description and the same code.
 *
 * Owner, 2026-08-18: *"I want a rich editor, something at least GIMP-grade."* Answered as an
 * adjustment stack first, structured so a layer stack can sit on top of it later without this
 * being rewritten (owner chose *"Both, adjustments first"*). What that structuring means concretely:
 * this type describes operations on ONE image, and knows nothing about where that image came from.
 * A layer is an image plus its own [Adjustments] plus a blend mode, so layers compose these rather
 * than replace them.
 *
 * Every field is a signed amount where **0 means untouched**, so the identity is the zero value and
 * a new adjustment cannot change what existing photos look like. That is not tidiness: the edit
 * stack is persisted, and a field whose neutral value was anything else would silently re-render
 * every previously saved edit the day it was added.
 *
 * Pure Kotlin, no Android imports, so all of it is testable on the JVM.
 */
data class Adjustments(
    // ---- tone, in the order a photographer works ----
    /** Stops of exposure. Applied in LINEAR light, which is the only place it means anything. */
    val exposure: Float = 0f,
    /** -1..1. An S-curve pivoting on mid-grey, so more contrast does not also mean darker. */
    val contrast: Float = 0f,
    /** -1..1. Recovers or opens the brightest quarter, leaving mid-tones alone. */
    val highlights: Float = 0f,
    /** -1..1. Lifts or deepens the darkest quarter. */
    val shadows: Float = 0f,
    /** -1..1. Moves the white point. */
    val whites: Float = 0f,
    /** -1..1. Moves the black point. */
    val blacks: Float = 0f,

    // ---- colour ----
    /** -1..1, negative cools towards blue and positive warms towards amber. */
    val temperature: Float = 0f,
    /** -1..1, negative towards green and positive towards magenta. */
    val tint: Float = 0f,
    /** -1..1, a flat multiplier on chroma. -1 is fully greyscale. */
    val saturation: Float = 0f,
    /** -1..1, saturation weighted towards the pixels that have least, so skin survives it. */
    val vibrance: Float = 0f,

    // ---- curves ----
    val rgbCurve: ToneCurve = ToneCurve.IDENTITY,
    val redCurve: ToneCurve = ToneCurve.IDENTITY,
    val greenCurve: ToneCurve = ToneCurve.IDENTITY,
    val blueCurve: ToneCurve = ToneCurve.IDENTITY,

    // ---- spatial, applied as their own passes rather than through the LUT ----
    /** 0..1. Unsharp mask amount. */
    val sharpen: Float = 0f,
    /** 0..1. Edge-preserving smoothing. */
    val denoise: Float = 0f,
    /** -1..1. Negative darkens the corners, positive lightens them. */
    val vignette: Float = 0f,
    /** 0..1. Local contrast — an unsharp mask at a large radius. */
    val clarity: Float = 0f,
) {

    /** True when rendering this would produce the input unchanged, so a save can be refused. */
    val isIdentity: Boolean
        get() = tonalIsIdentity && spatialIsIdentity

    /** True when nothing here needs a per-pixel colour pass. */
    val tonalIsIdentity: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && temperature == 0f && tint == 0f &&
            saturation == 0f && vibrance == 0f &&
            rgbCurve.isIdentity && redCurve.isIdentity &&
            greenCurve.isIdentity && blueCurve.isIdentity

    /** True when nothing here needs a neighbourhood pass, which are the expensive ones. */
    val spatialIsIdentity: Boolean
        get() = sharpen == 0f && denoise == 0f && vignette == 0f && clarity == 0f

    /**
     * The three 256-entry lookup tables this adjustment's *tonal* half compiles to.
     *
     * Everything that depends only on a channel's own value collapses into a table: exposure,
     * contrast, the tone regions, the white and black points, the per-channel curves, and the
     * channel gains that temperature and tint amount to. That is the difference between doing this
     * arithmetic 24 million times and doing it 768 times.
     *
     * Saturation and vibrance are NOT here and cannot be: they depend on a pixel's three channels
     * together, so they are applied per pixel afterwards.
     */
    fun toChannelLuts(): ChannelLuts {
        val gains = channelGains()
        return ChannelLuts(
            red = buildLut(gains.red, redCurve),
            green = buildLut(gains.green, greenCurve),
            blue = buildLut(gains.blue, blueCurve),
        )
    }

    private fun buildLut(gain: Float, channelCurve: ToneCurve): IntArray {
        val rgb = rgbCurve.toLut()
        val perChannel = channelCurve.toLut()
        return IntArray(256) { input ->
            var value = input / 255f

            // Exposure first, and in linear light. Doing it on the gamma-encoded value is the
            // classic mistake: +1 stop would brighten the shadows far more than the highlights and
            // the picture would go milky instead of brighter.
            if (exposure != 0f) {
                value = encode(decode(value) * 2f.pow(exposure))
            }

            // Channel gain — temperature and tint, which are a white-balance move and therefore a
            // multiply, not an offset. An offset lifts black to a colour, which is a colour cast
            // rather than a temperature change.
            if (gain != 1f) value = (value * gain).coerceIn(0f, 1f)

            // Black and white points, before the tone regions so those work on the stretched range.
            if (blacks != 0f || whites != 0f) {
                val black = (-blacks * POINT_RANGE).coerceIn(-0.4f, 0.4f)
                val white = 1f + (whites * POINT_RANGE).coerceIn(-0.4f, 0.4f)
                val span = (white - black)
                value = if (span <= 1e-4f) value else ((value - black) / span).coerceIn(0f, 1f)
            }

            if (highlights != 0f) value = applyRegion(value, highlights, highlightWeight(value))
            if (shadows != 0f) value = applyRegion(value, shadows, shadowWeight(value))

            if (contrast != 0f) {
                // A smoothstep S-curve rather than a straight-line scale. A linear contrast slider
                // clips: push it and the brightest and darkest quarters flatten to pure white and
                // pure black. The S-curve steepens the middle and eases into both ends instead.
                val s = smoothstep(value)
                value = value + (s - value) * contrast.coerceIn(-1f, 1f)
                value = value.coerceIn(0f, 1f)
            }

            var index = (value * 255f).toInt().coerceIn(0, 255)
            index = rgb[index]
            index = perChannel[index]
            index
        }
    }

    /**
     * Per-channel multipliers for temperature and tint.
     *
     * Green is left alone by temperature because green carries most of perceived luminance — moving
     * it would change how bright the photo looks while claiming to change only its colour.
     */
    internal fun channelGains(): ChannelGains {
        val warm = temperature * TEMPERATURE_RANGE
        val green = tint * TINT_RANGE
        return ChannelGains(
            red = (1f + warm).coerceAtLeast(0f),
            green = (1f - green).coerceAtLeast(0f),
            blue = (1f - warm).coerceAtLeast(0f),
        )
    }

    private fun applyRegion(value: Float, amount: Float, weight: Float): Float {
        if (weight <= 0f) return value
        val push = amount * REGION_RANGE * weight
        return (value + push).coerceIn(0f, 1f)
    }

    companion object {
        val NONE = Adjustments()

        /** How far a full highlights or shadows slider moves its region, in 0..1 units. */
        const val REGION_RANGE = 0.35f

        /** How far a full whites or blacks slider moves its point. */
        const val POINT_RANGE = 0.25f

        /** Full-slider channel gain for temperature. */
        const val TEMPERATURE_RANGE = 0.22f

        /** Full-slider channel gain for tint. */
        const val TINT_RANGE = 0.18f
    }
}

/** Per-channel multipliers. */
internal data class ChannelGains(val red: Float, val green: Float, val blue: Float)

/** The compiled tonal half of an adjustment: one 256-entry table per channel. */
data class ChannelLuts(val red: IntArray, val green: IntArray, val blue: IntArray) {
    // Arrays do not have value equality, and a data class holding them silently gets identity
    // comparison — which would make two identical LUTs unequal and defeat any caching keyed on them.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelLuts) return false
        return red.contentEquals(other.red) &&
            green.contentEquals(other.green) &&
            blue.contentEquals(other.blue)
    }

    override fun hashCode(): Int =
        31 * (31 * red.contentHashCode() + green.contentHashCode()) + blue.contentHashCode()
}

/**
 * How much a value belongs to the highlights, 0..1.
 *
 * Ramped rather than switched. A hard cutoff at, say, 0.75 puts a visible edge across every smooth
 * gradient in the picture — a sky becomes two skies with a seam between them.
 */
internal fun highlightWeight(value: Float): Float =
    (((value - 0.5f) / 0.5f).coerceIn(0f, 1f)).let { it * it }

/** How much a value belongs to the shadows, 0..1. Mirror of [highlightWeight]. */
internal fun shadowWeight(value: Float): Float =
    (((0.5f - value) / 0.5f).coerceIn(0f, 1f)).let { it * it }

/** The classic smoothstep, used as the contrast S-curve's target shape. */
internal fun smoothstep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** sRGB gamma-encoded value to linear light. */
internal fun decode(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}

/** Linear light back to an sRGB gamma-encoded value. */
internal fun encode(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
}

/**
 * Apply the per-pixel colour half of an adjustment to a packed ARGB array, in place.
 *
 * Pure over an IntArray rather than over a Bitmap, so the entire colour pipeline can be tested on
 * the JVM. The Android side hands it `Bitmap.getPixels` output and takes it straight back.
 *
 * Saturation and vibrance live here rather than in the LUT because they need all three channels at
 * once — a table indexed by one channel cannot know how colourful the pixel it came from was.
 */
fun applyColour(pixels: IntArray, luts: ChannelLuts, saturation: Float, vibrance: Float) {
    val flatten = saturation != 0f
    val weighted = vibrance != 0f
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val alpha = pixel ushr 24 and 0xFF
        var r = luts.red[pixel ushr 16 and 0xFF]
        var g = luts.green[pixel ushr 8 and 0xFF]
        var b = luts.blue[pixel and 0xFF]

        if (flatten || weighted) {
            // Rec. 709 luma. The perceptual weighting matters: an unweighted average desaturates
            // reds and blues to a grey far darker than the eye expects.
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            var amount = saturation
            if (weighted) {
                // Vibrance leans on the pixels that have least chroma, so a face at moderate
                // saturation moves far less than a flat blue sky does.
                val maxChannel = max(r, max(g, b))
                val minChannel = min(r, min(g, b))
                val chroma = (maxChannel - minChannel) / 255f
                amount += vibrance * (1f - chroma)
            }
            val scale = 1f + amount
            r = (luma + (r - luma) * scale).toInt()
            g = (luma + (g - luma) * scale).toInt()
            b = (luma + (b - luma) * scale).toInt()
        }

        pixels[i] = (alpha shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
    }
}

/**
 * Darken or lighten towards the corners, in place.
 *
 * Distance is measured against the image's own half-diagonal, so the falloff is a circle on a
 * square photo and an ellipse on a wide one — which is what a lens actually does, and what stops a
 * panorama from getting a vignette that only touches its left and right ends.
 */
fun applyVignette(pixels: IntArray, width: Int, height: Int, amount: Float) {
    if (amount == 0f || width <= 0 || height <= 0) return
    val cx = width / 2f
    val cy = height / 2f
    val maxDistance = kotlin.math.sqrt(cx * cx + cy * cy)
    if (maxDistance <= 0f) return

    for (y in 0 until height) {
        // Pixel CENTRES, not indices. A pixel covers [i, i+1), so its centre is at i + 0.5, and
        // measuring from i puts the falloff half a pixel off-centre — which on an odd-sized image
        // means the middle pixel is not actually the middle of the vignette.
        val dy = y + 0.5f - cy
        val rowStart = y * width
        for (x in 0 until width) {
            val dx = x + 0.5f - cx
            val distance = kotlin.math.sqrt(dx * dx + dy * dy) / maxDistance
            // Squared falloff, so the centre stays untouched and the effect gathers at the edge.
            val falloff = (distance * distance).coerceIn(0f, 1f)
            val factor = 1f + amount * falloff
            if (abs(factor - 1f) < 1e-4f) continue

            val index = rowStart + x
            val pixel = pixels[index]
            val alpha = pixel ushr 24 and 0xFF
            val r = ((pixel ushr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = ((pixel ushr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((pixel and 0xFF) * factor).toInt().coerceIn(0, 255)
            pixels[index] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
