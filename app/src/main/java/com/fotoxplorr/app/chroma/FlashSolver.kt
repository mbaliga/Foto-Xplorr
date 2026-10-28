package com.fotoxplorr.app.chroma

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Things a reading admits, shown beside the colour. */
internal enum class Caution(val message: String) {
    /** No card: the flash is taken to be neutral white, so a warm or cool LED tints the answer. */
    LED_ASSUMED_NEUTRAL(ChromaCopy.APPROXIMATE),

    /** No card: how light or dark the surface is was not measured; it is shown at a fixed middle level. */
    LIGHTNESS_NOT_MEASURED(ChromaCopy.LIGHTNESS_NOT_MEASURED),

    /** A card was used but the two distances were not both known, so they were taken as equal. */
    SAME_DISTANCE_ASSUMED(ChromaCopy.SAME_DISTANCE_ASSUMED),

    /** The inverse-square correction for a different card distance was applied (a limited-accuracy fix). */
    DISTANCE_COMPENSATED(ChromaCopy.DISTANCE_COMPENSATED),

    /** The surface came out a little lighter than the card, which is noise or glare; clipped to white. */
    REFLECTANCE_CLAMPED(ChromaCopy.CLAMPED),

    /** The flash signal was close to the refusal limit. */
    SMALL_SIGNAL(ChromaCopy.SMALL_SIGNAL),
}

/** A white or grey reference card shot in the same two ways, and how light it is. */
internal class CardPatches(
    val ambient: Patch,
    val flash: Patch,
    /** The card's own reflectance, 0..1: paper is about 0.8 to 0.9, a photographic grey card 0.18. */
    val reflectance: Double = DEFAULT_REFLECTANCE,
    /** The flash-to-card distance in metres, or null when not known. */
    val distanceMetres: Double? = null,
) {
    init {
        require(reflectance > 0.0 && reflectance <= 1.0) { "reflectance out of range" }
        require(distanceMetres == null || distanceMetres > 0.0) { "distance must be positive" }
    }

    companion object {
        /** Plain white printer paper, to within a few percent for the chroma and about 10% for lightness. */
        const val DEFAULT_REFLECTANCE = 0.85
    }
}

/**
 * The surface's colour as REFLECTANCE: what share of white light it throws back, per channel. As a
 * colour it is the surface seen under an even white light (D65, white = 1): the stable colour of the
 * thing, with the room's and the flash's colour taken out.
 *
 * @property reflectance per channel, 0..1.
 * @property argb the opaque sRGB colour for showing it.
 * @property approximate true without a card; the colour is a best guess, its lightness is a fixed level
 *   and not a measurement, and every caution says why.
 * @property flashShare the share of the surface's brightness in the flash frame that came from the
 *   flash (the rest is the room); near 1 is a clean signal, below about 0.2 a thin one.
 * @property signalToNoise the flash signal over its noise in the strongest channel.
 */
internal class Measurement(
    val reflectance: LinearRgb,
    val argb: Int,
    val lab: Lab,
    val approximate: Boolean,
    val cautions: Set<Caution>,
    val flashShare: Double,
    val signalToNoise: Double,
)

/**
 * Flash / no-flash colour measurement.
 *
 * ## The physics
 * Take an AMBIENT frame (flash off) and a FLASH frame (flash on) with exposure, ISO and white balance
 * LOCKED, from the same spot. A matte surface of reflectance rho under room light A and flash light
 * E (the LED's colour and strength, as it arrives at the surface) reads, in linear light,
 *
 *     ambient frame  = rho * A
 *     flash frame    = rho * A + rho * E
 *
 * so their difference is `rho * E`: the room drops out entirely, whatever its colour or brightness,
 * leaving only the phone's own light reflected by the surface. That removes the unknown ROOM, but
 * `E` is still unknown (LED colour, strength, falloff with distance). Two ways to finish:
 *
 *  - WITH A CARD. Measure a white or grey card the same way, at the same distance: `rho_c * E`.
 *    The ratio of the two differences is `rho / rho_c` in every channel, with E gone. Multiplying by
 *    the card's known reflectance gives the surface's reflectance (colour). If the card is at a
 *    different distance, the flash light follows an inverse-square law, so the surface figure is
 *    multiplied by `(d_surface / d_card)^2`; see [DISTANCE_LIMIT] for why this is limited.
 *  - WITHOUT A CARD. Assume the LED is neutral white in the camera's locked white balance
 *    ([assumedLed]). Real phone LEDs are warm-to-cool white (about 4000 to 6000 K), and their colour
 *    is not known to this code, so the answer is tinted by the LED's departure from neutral; the
 *    tests measure that (see `ChromaPipelineTest`: a few to a dozen CIEDE2000 units for a plausible
 *    LED). The lightness is NOT recovered at all: with no card the exposure is whatever auto exposure
 *    settled on for the torch-lit surface, and auto exposure lights a black cloth and a white sheet
 *    to about the same level, so the flash difference carries almost nothing about how light the
 *    surface is. Only the proportions of the three channels are used, and the colour is shown at a
 *    fixed middle luminance ([UNMEASURED_LUMINANCE]). The result is marked `approximate`, with
 *    [Caution.LED_ASSUMED_NEUTRAL] and [Caution.LIGHTNESS_NOT_MEASURED].
 *
 * A distance difference between the AMBIENT and the FLASH frames does not matter for a matte
 * surface: room light on it does not change as the camera moves (a surface seen from further away
 * is no darker, it just fills fewer pixels). What matters is the flash frame's distance, compared
 * with the card's.
 *
 * ## What it refuses
 * A flash that adds too little to be measured against the frame-to-frame noise
 * ([MIN_SIGNAL_TO_NOISE]); a flash frame darker than the ambient one ([Refusal.LIGHT_CHANGED]); a
 * card whose flash signal is thin in any channel; card and surface distances beyond [DISTANCE_LIMIT]
 * of one another; a result more than [MAX_REFLECTANCE] times white.
 *
 * ## What it is not
 * Not a spectrometer: three camera channels, so two surfaces that differ only in a way the camera
 * cannot see (metamers) read the same. Not for shiny surfaces (the flash glare is the LED's colour,
 * not the surface's), not for surfaces that fluoresce, and it assumes the frames are registered (a
 * steady hand or a stand) and that the phone's tone curve is the standard sRGB one. Nothing here has
 * been run on a real phone: the accuracy figures are for synthetic frames that obey this model.
 *
 * Pure and deterministic.
 */
internal object FlashSolver {
    /** The flash signal must be at least this many times its noise (strongest channel). */
    const val MIN_SIGNAL_TO_NOISE = 8.0

    /** Below this signal-to-noise a passing reading carries [Caution.SMALL_SIGNAL]. */
    const val COMFORTABLE_SIGNAL_TO_NOISE = 20.0

    /**
     * Frame-to-frame instability of the exposure (lamp flicker, a hand moving, an exposure that did
     * not quite lock) as a share of the frame's level. It is part of the noise the signal is judged
     * against, which is what makes a bright room (small flash share) fail.
     */
    const val FRAME_JITTER = 0.01

    /** A channel of the flash frame this many noise-widths below the ambient one is a changed light. */
    private const val CHANGED_SIGMAS = 4.0
    private const val CHANGED_MIN = 0.01

    /**
     * A thin flash signal is "too bright in the room" when the ambient light is at least this share
     * of the flash frame's brightness AND the ambient patch itself is at least [BRIGHT_ROOM_LEVEL]
     * (linear, about sRGB code 108: with the exposure locked a lit room reads well above this); in
     * a dim room the same thin signal means the surface is out of the flash's reach.
     */
    private const val BRIGHT_ROOM_SHARE = 0.5
    private const val BRIGHT_ROOM_LEVEL = 0.15

    /**
     * The card's and the surface's flash distances must be within this ratio of each other
     * (1.5 = one may be up to 50% further). The inverse-square correction doubles an error in the
     * distance: a 10% error in a focus-distance reading is a 20% error in brightness, and the
     * LED sits to one side of the lens, so very close up the light is not a point source on the lens
     * axis at all. A phone's reported focus distance is itself coarse (not checked on a device).
     */
    const val DISTANCE_LIMIT = 1.5

    /** Distances this close (within 5%) are treated as equal and not corrected. */
    private const val SAME_DISTANCE_TOLERANCE = 1.05

    /** A reflectance above this multiple of the card's is refused; up to [CLAMP_TOLERANCE] is clamped. */
    const val MAX_REFLECTANCE = 1.3
    private const val CLAMP_TOLERANCE = 1.02

    /**
     * Without a card: the linear luminance every reading is shown at (0.2 is about CIE L 52, a middle
     * grey). The lightness of the surface is not known, so none is claimed; this only fixes the level
     * the measured colour proportions are drawn at.
     */
    const val UNMEASURED_LUMINANCE = 0.2

    /** A flash frame's difference from its ambient frame, with the noise it is judged against. */
    internal class Difference(
        val value: LinearRgb,
        val sigma: LinearRgb,
        val share: Double,
        val ambientShare: Double,
        val ambientLevel: Double,
    )

    /**
     * Flash minus ambient for one spot, negative noise clamped to zero. Refuses a changed light.
     */
    internal fun difference(ambient: Patch, flash: Patch): Answer<Difference> {
        val d = DoubleArray(3)
        val s = DoubleArray(3)
        for (c in 0..2) {
            val a = ambient.linear[c]
            val f = flash.linear[c]
            val sa = ambient.standardError[c]
            val sf = flash.standardError[c]
            s[c] = sqrt(sa * sa + sf * sf + (FRAME_JITTER * f).pow(2))
            val raw = f - a
            if (raw < -max(CHANGED_SIGMAS * s[c], CHANGED_MIN)) return Answer.Refused(Refusal.LIGHT_CHANGED)
            d[c] = max(raw, 0.0)
        }
        val value = LinearRgb(d[0], d[1], d[2])
        val flashLum = flash.linear.luminance
        val share = if (flashLum > 0.0) (value.luminance / flashLum).coerceIn(0.0, 1.0) else 0.0
        val ambientShare = if (flashLum > 0.0) (ambient.linear.luminance / flashLum).coerceIn(0.0, 1.0) else 1.0
        return Answer.Value(Difference(value, LinearRgb(s[0], s[1], s[2]), share, ambientShare, ambient.linear.luminance))
    }

    private fun snr(d: Difference): Double {
        var best = 0.0
        for (c in 0..2) best = max(best, d.value[c] / d.sigma[c])
        return best
    }

    /**
     * @param surfaceDistanceMetres the flash-to-surface distance when known, used only with a card.
     * @param assumedLed the LED colour assumed WITHOUT a card, in the camera's locked white balance;
     *   only its proportions matter. Neutral by default.
     */
    fun solve(
        ambient: Patch,
        flash: Patch,
        card: CardPatches? = null,
        surfaceDistanceMetres: Double? = null,
        assumedLed: LinearRgb = LinearRgb(1.0, 1.0, 1.0),
    ): Answer<Measurement> {
        val surface = when (val r = difference(ambient, flash)) {
            is Answer.Refused -> return r
            is Answer.Value -> r.value
        }
        val signal = snr(surface)
        if (signal < MIN_SIGNAL_TO_NOISE) {
            val bright = surface.ambientShare >= BRIGHT_ROOM_SHARE && surface.ambientLevel >= BRIGHT_ROOM_LEVEL
            return Answer.Refused(if (bright) Refusal.TOO_BRIGHT_ROOM else Refusal.TOO_FAR)
        }
        val cautions = linkedSetOf<Caution>()
        if (signal < COMFORTABLE_SIGNAL_TO_NOISE) cautions += Caution.SMALL_SIGNAL

        val rho: DoubleArray
        if (card != null) {
            val cardDiff = when (val r = difference(card.ambient, card.flash)) {
                is Answer.Refused -> return r
                is Answer.Value -> r.value
            }
            for (c in 0..2) {
                if (cardDiff.value[c] / cardDiff.sigma[c] < MIN_SIGNAL_TO_NOISE) return Answer.Refused(Refusal.CARD_TOO_DIM)
            }
            var distanceFactor = 1.0
            val ds = surfaceDistanceMetres
            val dc = card.distanceMetres
            if (ds != null && dc != null) {
                val ratio = ds / dc
                if (ratio > DISTANCE_LIMIT || ratio < 1.0 / DISTANCE_LIMIT) return Answer.Refused(Refusal.DISTANCE_TOO_DIFFERENT)
                if (ratio > SAME_DISTANCE_TOLERANCE || ratio < 1.0 / SAME_DISTANCE_TOLERANCE) {
                    distanceFactor = ratio * ratio
                    cautions += Caution.DISTANCE_COMPENSATED
                }
            } else {
                cautions += Caution.SAME_DISTANCE_ASSUMED
            }
            rho = DoubleArray(3) { c -> surface.value[c] / cardDiff.value[c] * distanceFactor * card.reflectance }
            if (rho.any { it > MAX_REFLECTANCE }) return Answer.Refused(Refusal.NOT_ADDING_UP)
            if (rho.any { it > 1.0 }) {
                if (rho.any { it > CLAMP_TOLERANCE }) cautions += Caution.REFLECTANCE_CLAMPED
                for (c in 0..2) rho[c] = rho[c].coerceAtMost(1.0)
            }
        } else {
            cautions += Caution.LED_ASSUMED_NEUTRAL
            cautions += Caution.LIGHTNESS_NOT_MEASURED
            val ledLum = assumedLed.luminance
            val raw = DoubleArray(3) { c -> surface.value[c] / (assumedLed[c] / ledLum).coerceAtLeast(1e-6) }
            // Only the proportions are known: put them at the fixed luminance. A colour that cannot reach
            // it inside the gamut (a deep blue) is scaled down as a whole, never clipped in one channel
            // (that would change the hue).
            val lum = LinearRgb(raw[0], raw[1], raw[2]).luminance
            val atFixed = if (lum > 0.0) UNMEASURED_LUMINANCE / lum else 0.0
            val top = raw.max() * atFixed
            val k = atFixed * (if (top > 1.0) 1.0 / top else 1.0)
            rho = DoubleArray(3) { raw[it] * k }
        }

        val reflectance = LinearRgb(rho[0], rho[1], rho[2])
        return Answer.Value(
            Measurement(
                reflectance = reflectance,
                argb = ColourSpace.argbOf(reflectance),
                lab = ColourSpace.lab(reflectance),
                approximate = card == null,
                cautions = cautions,
                flashShare = surface.share,
                signalToNoise = signal,
            ),
        )
    }
}
