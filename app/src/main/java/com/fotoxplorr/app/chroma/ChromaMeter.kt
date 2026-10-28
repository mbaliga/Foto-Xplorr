package com.fotoxplorr.app.chroma

/** The ambient and flash frames of one spot, and which part of them to read. */
internal class FlashPair(
    val ambient: ChromaFrame,
    val flash: ChromaFrame,
    val region: PatchRegion = PatchRegion.CENTRE,
    /** The flash-to-spot distance in metres (see [FocusDistance]), or null when not known. */
    val distanceMetres: Double? = null,
)

/** A white or grey card shot the same two ways. */
internal class CardPair(
    val pair: FlashPair,
    val reflectance: Double = CardPatches.DEFAULT_REFLECTANCE,
)

/** A finished reading: the measurement and everything the tool shows about the colour. */
internal class ChromaReading(val measurement: Measurement, val report: ColourReport)

/**
 * The one entry the app's Chroma ID screen calls with decoded frames: sample the patches
 * ([PatchSampler]), solve flash minus ambient ([FlashSolver]), then name the colour
 * ([ColourReport]). Any step that cannot give an honest answer returns its [Refusal].
 *
 * The two frames of a pair must be the same size (one is the other with the flash fired; the
 * caller is responsible for locking exposure, ISO and white balance between them, which is
 * what makes the subtraction mean anything). Decoding the person's shots, and the camera and
 * flash control, belong to the Android seam, not here. Protected photos must not be fed in from
 * the library at all; the shots are the camera's own.
 */
internal object ChromaMeter {
    fun measure(surface: FlashPair, card: CardPair? = null): Answer<ChromaReading> {
        val s = samplePair(surface) ?: return Answer.Refused(Refusal.SIZE_MISMATCH)
        val surfaceAmbient = s.first.orNull() ?: return (s.first as Answer.Refused)
        val surfaceFlash = s.second.orNull() ?: return (s.second as Answer.Refused)

        var cardPatches: CardPatches? = null
        if (card != null) {
            val c = samplePair(card.pair) ?: return Answer.Refused(Refusal.SIZE_MISMATCH)
            val ca = c.first.orNull() ?: return (c.first as Answer.Refused)
            val cf = c.second.orNull() ?: return (c.second as Answer.Refused)
            cardPatches = CardPatches(ca, cf, card.reflectance, card.pair.distanceMetres)
        }
        return when (val r = FlashSolver.solve(surfaceAmbient, surfaceFlash, cardPatches, surface.distanceMetres)) {
            is Answer.Refused -> r
            is Answer.Value -> Answer.Value(ChromaReading(r.value, ColourReport.of(r.value.lab)))
        }
    }

    /**
     * The card's two patches, so its frames can be dropped at once. A card is good only for the
     * exposure it was shot under: the caller keeps the numbers while that lock is held and no longer.
     */
    fun sampleCard(card: CardPair): Answer<CardPatches> {
        val c = samplePair(card.pair) ?: return Answer.Refused(Refusal.SIZE_MISMATCH)
        val ca = c.first.orNull() ?: return (c.first as Answer.Refused)
        val cf = c.second.orNull() ?: return (c.second as Answer.Refused)
        return Answer.Value(CardPatches(ca, cf, card.reflectance, card.pair.distanceMetres))
    }

    /** [measure] with a card already reduced to its patches by [sampleCard]. */
    fun measureAgainst(surface: FlashPair, card: CardPatches?): Answer<ChromaReading> {
        val s = samplePair(surface) ?: return Answer.Refused(Refusal.SIZE_MISMATCH)
        val surfaceAmbient = s.first.orNull() ?: return (s.first as Answer.Refused)
        val surfaceFlash = s.second.orNull() ?: return (s.second as Answer.Refused)
        return when (val r = FlashSolver.solve(surfaceAmbient, surfaceFlash, card, surface.distanceMetres)) {
            is Answer.Refused -> r
            is Answer.Value -> Answer.Value(ChromaReading(r.value, ColourReport.of(r.value.lab)))
        }
    }

    private fun samplePair(p: FlashPair): Pair<Answer<Patch>, Answer<Patch>>? {
        if (p.ambient.width != p.flash.width || p.ambient.height != p.flash.height) return null
        return PatchSampler.sample(p.ambient, p.region, keepDark = true) to PatchSampler.sample(p.flash, p.region)
    }
}
