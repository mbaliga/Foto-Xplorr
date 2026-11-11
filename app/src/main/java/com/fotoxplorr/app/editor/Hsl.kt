package com.fotoxplorr.app.editor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * One of the eight hue bands a Lightroom-style HSL panel exposes.
 *
 * Centred at the classic, NON-uniform positions a real colour wheel gives them, not evenly spaced
 * every 45 degrees: the warm end (red/orange/yellow, where skin tones and most food/sunset photos
 * live) is packed closer together for finer control, and the cool end (green/aqua/blue, mostly
 * sky and foliage) is spread wider because a photograph rarely needs to split hairs between two
 * shades of green the way it needs to between a red and an orange cast on a face.
 */
enum class HueBand(val label: String, val centerDegrees: Float) {
    RED("Red", 0f),
    ORANGE("Orange", 30f),
    YELLOW("Yellow", 60f),
    GREEN("Green", 120f),
    AQUA("Aqua", 180f),
    BLUE("Blue", 240f),
    PURPLE("Purple", 270f),
    MAGENTA("Magenta", 300f),
}

/**
 * One band's three sliders, each -1..1 with 0 = untouched — the same signed, zero-is-identity
 * convention as every field in [Adjustments], and for the identical reason: this is persisted,
 * and a neutral value that was not zero would silently change every already-saved edit the day a
 * band's default did.
 */
data class HslBandAdjustment(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) {
    val isIdentity: Boolean get() = hue == 0f && saturation == 0f && luminance == 0f

    // Empty on purpose -- see EditRecipeJson.kt's `HslBandAdjustment.Companion.fromJson`.
    companion object
}

/** All eight bands together. */
data class HslAdjustments(
    val red: HslBandAdjustment = HslBandAdjustment(),
    val orange: HslBandAdjustment = HslBandAdjustment(),
    val yellow: HslBandAdjustment = HslBandAdjustment(),
    val green: HslBandAdjustment = HslBandAdjustment(),
    val aqua: HslBandAdjustment = HslBandAdjustment(),
    val blue: HslBandAdjustment = HslBandAdjustment(),
    val purple: HslBandAdjustment = HslBandAdjustment(),
    val magenta: HslBandAdjustment = HslBandAdjustment(),
) {
    val isIdentity: Boolean
        get() = red.isIdentity && orange.isIdentity && yellow.isIdentity && green.isIdentity &&
            aqua.isIdentity && blue.isIdentity && purple.isIdentity && magenta.isIdentity

    operator fun get(band: HueBand): HslBandAdjustment = when (band) {
        HueBand.RED -> red
        HueBand.ORANGE -> orange
        HueBand.YELLOW -> yellow
        HueBand.GREEN -> green
        HueBand.AQUA -> aqua
        HueBand.BLUE -> blue
        HueBand.PURPLE -> purple
        HueBand.MAGENTA -> magenta
    }

    fun with(band: HueBand, adjustment: HslBandAdjustment): HslAdjustments = when (band) {
        HueBand.RED -> copy(red = adjustment)
        HueBand.ORANGE -> copy(orange = adjustment)
        HueBand.YELLOW -> copy(yellow = adjustment)
        HueBand.GREEN -> copy(green = adjustment)
        HueBand.AQUA -> copy(aqua = adjustment)
        HueBand.BLUE -> copy(blue = adjustment)
        HueBand.PURPLE -> copy(purple = adjustment)
        HueBand.MAGENTA -> copy(magenta = adjustment)
    }

    companion object {
        val NONE = HslAdjustments()
    }
}

/**
 * Applies [hsl] to a packed ARGB array, in place.
 *
 * Every pixel is converted to HSL, tested against all eight [HueBand]s at once (a pixel between
 * two band centres is influenced by both, blended smoothly — see [bandWeight]), moved, and
 * converted back. A pixel with no chroma (`s == 0`, a grey or near-grey pixel) is skipped outright
 * rather than merely producing a no-op through the maths: a grey pixel's hue is arithmetically
 * defined as 0 degrees by convention, which would otherwise land it squarely in the RED band and
 * make the "Red" luminance slider brighten every shadow and every overcast sky in the photograph.
 *
 * Pure over an IntArray, like [applyColour] and [applyVignette] — the whole colour pipeline stays
 * testable on the JVM without a `Bitmap`.
 */
fun applyHsl(pixels: IntArray, hsl: HslAdjustments) {
    if (hsl.isIdentity) return
    val bands = HueBand.entries
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val alpha = pixel ushr 24 and 0xFF
        val r = (pixel ushr 16 and 0xFF) / 255f
        val g = (pixel ushr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val hslValue = rgbToHsl(r, g, b)
        if (hslValue.saturation <= 0f) continue

        var totalWeight = 0f
        var hueShift = 0f
        var satScale = 0f
        var lumShift = 0f
        for (band in bands) {
            // Gated by the pixel's own saturation so a barely-tinted pixel is barely affected,
            // and a fully grey one (caught above already, but this also softens the near-grey
            // case) is affected less than a vivid one at the same hue.
            val weight = bandWeight(hslValue.hue, band) * hslValue.saturation
            if (weight <= 0f) continue
            val adjustment = hsl[band]
            totalWeight += weight
            hueShift += weight * (adjustment.hue * MAX_HUE_SHIFT_DEGREES)
            satScale += weight * adjustment.saturation
            lumShift += weight * (adjustment.luminance * LUMINANCE_RANGE)
        }
        // Skipped rather than merely computed-through-to-a-no-op: a pixel whose only nearby
        // bands are all untouched (0,0,0) would otherwise still take the full HSL round trip and
        // come out perturbed by +/-1 per channel from ordinary float rounding -- correct in
        // spirit (the shift really was zero) but a pixel this feature has no opinion about
        // should stay bit-identical, not merely "close".
        if (totalWeight <= 0f || (hueShift == 0f && satScale == 0f && lumShift == 0f)) continue

        val newHue = (hslValue.hue + hueShift / totalWeight + 360f) % 360f
        val newSaturation = (hslValue.saturation * (1f + satScale / totalWeight)).coerceIn(0f, 1f)
        val newLightness = (hslValue.lightness + lumShift / totalWeight).coerceIn(0f, 1f)

        val rgb = hslToRgb(newHue, newSaturation, newLightness)
        pixels[i] = (alpha shl 24) or
            ((rgb.red * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((rgb.green * 255f).toInt().coerceIn(0, 255) shl 8) or
            (rgb.blue * 255f).toInt().coerceIn(0, 255)
    }
}

/**
 * How strongly [band] applies to a pixel at [hueDegrees], 0..1.
 *
 * A raised-cosine (Hann) window: 1 at the band's own centre, falling smoothly to 0 at
 * [BAND_HALF_WIDTH_DEGREES] away, rather than a hard cutoff that would put a visible seam across
 * a smooth gradient (a sunset's red-to-orange sky, say) the moment it crossed a band boundary.
 * [BAND_HALF_WIDTH_DEGREES] (40°) exceeds every gap between adjacent band centres in [HueBand]
 * (the widest is 60°, so half of that is 30° < 40°), which is what guarantees every hue on the
 * wheel is covered by at least one band with nonzero weight — there is no dead zone a colour
 * could fall into and be affected by nothing at all.
 */
internal fun bandWeight(hueDegrees: Float, band: HueBand): Float {
    val distance = circularHueDistance(hueDegrees, band.centerDegrees)
    if (distance >= BAND_HALF_WIDTH_DEGREES) return 0f
    return 0.5f * (1f + cos((distance / BAND_HALF_WIDTH_DEGREES) * Math.PI.toFloat()))
}

/** The shorter way around the 360-degree hue wheel between two hues. */
internal fun circularHueDistance(a: Float, b: Float): Float {
    val diff = abs(a - b) % 360f
    return min(diff, 360f - diff)
}

private const val BAND_HALF_WIDTH_DEGREES = 40f

/** Full-slider hue rotation within a band, in degrees. Modest on purpose: HSL is for correcting
 *  or nudging a colour, not repainting it -- Photoshop's own Hue/Saturation dialog uses a
 *  comparable range for the same reason. */
private const val MAX_HUE_SHIFT_DEGREES = 30f

/** Full-slider lightness shift within a band, in 0..1 units. */
private const val LUMINANCE_RANGE = 0.3f

// ---------------------------------------------------------------------------
// RGB <-> HSL, the textbook conversion. 0..1 in, 0..1 out (hue in degrees, 0..360).
// ---------------------------------------------------------------------------

internal data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)
internal data class Rgb(val red: Float, val green: Float, val blue: Float)

internal fun rgbToHsl(r: Float, g: Float, b: Float): Hsl {
    val maxChannel = max(r, max(g, b))
    val minChannel = min(r, min(g, b))
    val lightness = (maxChannel + minChannel) / 2f
    if (maxChannel == minChannel) return Hsl(0f, 0f, lightness)

    val delta = maxChannel - minChannel
    val saturation = if (lightness > 0.5f) delta / (2f - maxChannel - minChannel) else delta / (maxChannel + minChannel)
    var hue = when (maxChannel) {
        r -> (g - b) / delta + (if (g < b) 6f else 0f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * 60f
    if (hue < 0f) hue += 360f
    return Hsl(hue, saturation, lightness)
}

internal fun hslToRgb(hue: Float, saturation: Float, lightness: Float): Rgb {
    if (saturation <= 0f) return Rgb(lightness, lightness, lightness)
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val hPrime = (hue % 360f + 360f) % 360f / 60f
    val x = chroma * (1f - abs(hPrime % 2f - 1f))
    val (r1, g1, b1) = when {
        hPrime < 1f -> Triple(chroma, x, 0f)
        hPrime < 2f -> Triple(x, chroma, 0f)
        hPrime < 3f -> Triple(0f, chroma, x)
        hPrime < 4f -> Triple(0f, x, chroma)
        hPrime < 5f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val m = lightness - chroma / 2f
    return Rgb(r1 + m, g1 + m, b1 + m)
}
