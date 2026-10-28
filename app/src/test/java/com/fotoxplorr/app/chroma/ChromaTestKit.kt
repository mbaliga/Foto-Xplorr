package com.fotoxplorr.app.chroma

import java.util.Random

/**
 * Synthetic frames that obey the physics `FlashSolver` assumes, so the tests can say how well the
 * pipeline recovers a KNOWN reflectance. A pixel's linear value is
 *
 *     exposure * rho * (ambient + flash)          (flash term present only in the flash frame)
 *
 * times a per-frame gain wobble ("jitter": flicker, an exposure that did not quite lock) and a
 * per-pixel multiplicative noise, then clipped and rounded to an sRGB 8-bit code. A ring round the
 * outside is a different colour, so a test that reads outside the middle gets the wrong answer.
 * Exposure is locked on the card by default; `autoExposureOnSurface` locks it on the surface instead, which is
 * what a reading made without a card really does. What this does NOT model: a phone's tone curve, local contrast, lens shading, glare, hand
 * movement between frames, fluorescence. It proves the algebra and the gates, not a camera.
 */
internal object ChromaTestKit {
    const val SIZE = 48

    fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    fun frame(
        rho: LinearRgb,
        light: LinearRgb,
        exposure: Double,
        noise: Double,
        jitter: Double,
        rng: Random,
        ring: LinearRgb = LinearRgb(0.5, 0.1, 0.1),
        size: Int = SIZE,
    ): ChromaFrame {
        val gain = 1.0 + jitter * rng.nextGaussian()
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val inRing = x < size / 5 || x >= size - size / 5 || y < size / 5 || y >= size - size / 5
            val surface = if (inRing) ring else rho
            fun ch(c: Int): Int {
                val v = exposure * gain * surface[c] * light[c] * (1.0 + noise * rng.nextGaussian())
                return ColourSpace.encode(v)
            }
            px[y * size + x] = argb(ch(0), ch(1), ch(2))
        }
        return ChromaFrame(size, size, px)
    }

    /** A flat frame of one sRGB code per channel. */
    fun flat(r: Int, g: Int, b: Int, size: Int = SIZE): ChromaFrame =
        ChromaFrame(size, size, IntArray(size * size) { argb(r, g, b) })

    /** How the lights in one trial are drawn. */
    class Conditions(
        val noise: Double,
        val jitter: Double,
        /** Flash-LED colour multipliers are drawn from 1 +/- this (0 is a perfectly neutral LED). */
        val ledTilt: Double = 0.15,
        /** The ambient share (ambient light over flash light on the card) is drawn from 0..this. */
        val maxAmbientShare: Double = 2.0,
        /** Surface distance over card distance (flash light on the surface falls as its square). */
        val distanceRatio: Double = 1.0,
        /** The distance the solver is TOLD, as a multiple of the true ratio (1.0 is a perfect reading). */
        val distanceReadingError: Double = 1.0,
        val knowDistances: Boolean = false,
        /**
         * Auto exposure settled on the SURFACE (what the no-card job does: it lets the exposure settle on
         * whatever fills the view with the torch on, and holds it) instead of the card. The surface's flash
         * frame then always lands at the same level, whether the surface is black cloth or white paper.
         */
        val autoExposureOnSurface: Boolean = false,
    )

    class Trial(
        val surface: FlashPair,
        val card: CardPair,
        val truth: LinearRgb,
        val truthLab: Lab,
    )

    /** One random room, one random flash, one random surface of known reflectance. */
    fun trial(rng: Random, c: Conditions, surfaceArgb: Int? = null): Trial {
        val code = surfaceArgb ?: argb(20 + rng.nextInt(221), 20 + rng.nextInt(221), 20 + rng.nextInt(221))
        val rho = ColourSpace.linearOf(code)
        val cardRho = LinearRgb(CARD, CARD, CARD)

        // Ambient colour: strongly tinted, unit luminance; flash LED: mildly tinted, unit luminance.
        val ambientTint = normalised(LinearRgb(0.6 + 0.8 * rng.nextDouble(), 0.6 + 0.8 * rng.nextDouble(), 0.6 + 0.8 * rng.nextDouble()))
        val ledTint = normalised(
            LinearRgb(
                1.0 + c.ledTilt * (2 * rng.nextDouble() - 1),
                1.0 + c.ledTilt * (2 * rng.nextDouble() - 1),
                1.0 + c.ledTilt * (2 * rng.nextDouble() - 1),
            ),
        )
        val share = c.maxAmbientShare * rng.nextDouble()
        val flashAtCard = ledTint // unit luminance
        val ambientLight = ambientTint.map { it * share }
        // Flash on the surface follows the inverse-square law relative to the card.
        val falloff = 1.0 / (c.distanceRatio * c.distanceRatio)
        val flashAtSurface = flashAtCard.map { it * falloff }

        // Exposure locked on the card: its brightest flash-frame channel lands at 0.7 (or, with
        // autoExposureOnSurface, the surface's does).
        var peak = 0.0
        for (ch in 0..2) {
            peak = if (c.autoExposureOnSurface) {
                maxOf(peak, rho[ch] * (ambientLight[ch] + flashAtSurface[ch]))
            } else {
                maxOf(peak, cardRho[ch] * (ambientLight[ch] + flashAtCard[ch]))
            }
        }
        val exposure = 0.7 / peak

        val ambientOnly = ambientLight
        val withFlashCard = LinearRgb.of { ambientLight[it] + flashAtCard[it] }
        val withFlashSurface = LinearRgb.of { ambientLight[it] + flashAtSurface[it] }

        val surface = FlashPair(
            ambient = frame(rho, ambientOnly, exposure, c.noise, c.jitter, rng),
            flash = frame(rho, withFlashSurface, exposure, c.noise, c.jitter, rng),
            distanceMetres = if (c.knowDistances) 0.20 * c.distanceRatio * c.distanceReadingError else null,
        )
        val card = CardPair(
            FlashPair(
                ambient = frame(cardRho, ambientOnly, exposure, c.noise, c.jitter, rng),
                flash = frame(cardRho, withFlashCard, exposure, c.noise, c.jitter, rng),
                distanceMetres = if (c.knowDistances) 0.20 else null,
            ),
            reflectance = CARD,
        )
        return Trial(surface, card, rho, ColourSpace.lab(rho))
    }

    /** The card in the simulation reflects 85% of every channel (what the solver defaults to). */
    const val CARD = CardPatches.DEFAULT_REFLECTANCE

    private fun normalised(c: LinearRgb): LinearRgb {
        val y = c.luminance
        return c.map { it / y }
    }

    /** [measured] rescaled so its luminance matches [truth]'s: what is left is hue and chroma error. */
    fun lightnessMatched(measured: LinearRgb, truth: LinearRgb): Lab {
        val k = truth.luminance / measured.luminance
        return ColourSpace.lab(measured.map { (it * k).coerceIn(0.0, 1.0) })
    }
}
