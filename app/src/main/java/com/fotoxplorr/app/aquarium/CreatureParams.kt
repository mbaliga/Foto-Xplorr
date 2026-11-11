package com.fotoxplorr.app.aquarium

/**
 * How a creature looks and moves in the tank, derived from nothing but the shape of its drawing, so
 * a stored creature needs only its aspect (and which way its head points) to be brought back.
 *
 * Tank units: the tank is 1.0 tall and [TankSimulation.aspect] wide; sizes and speeds are in tank
 * heights (per second). The numbers are starting values chosen by arithmetic, NOT tuned on a device.
 */
data class CreatureParams(
    /** Sprite width / height, kept inside [MIN_ASPECT]..[MAX_ASPECT]. */
    val aspect: Float,
    /** True when the drawing's head is on the left of the bitmap (the person flipped it). */
    val headLeft: Boolean,
    /** Cruise speed at depth scale 1, tank heights per second. */
    val speed: Float,
    /** The sprite's long edge at depth scale 1, tank heights. */
    val length: Float,
    val swim: SwimParams,
) {
    /** Sprite size at depth scale 1, tank heights; the long edge is always [length]. */
    val width: Float get() = if (aspect >= 1f) length else length * aspect
    val height: Float get() = width / aspect

    companion object {
        const val MIN_ASPECT = 0.05f
        const val MAX_ASPECT = 20f
        const val LENGTH = 0.16f
        const val MIN_SPEED = 0.07f
        const val MAX_SPEED = 0.15f

        /**
         * Longer, thinner drawings cruise a bit faster, sway a bit more and undulate more slowly, with
         * more wavelengths along the body; round ones the opposite. A non-finite or non-positive
         * [aspect] is treated as a round creature.
         */
        fun forAspect(aspect: Float, headLeft: Boolean = false): CreatureParams {
            val a = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(MIN_ASPECT, MAX_ASPECT) else 1f
            // The behaviour reads a bounded shape, so a hairline or a sliver does not go to extremes.
            val t = ((a.coerceIn(0.5f, 3f) - 0.5f) / 2.5f)
            return CreatureParams(
                aspect = a,
                headLeft = headLeft,
                speed = (0.075f + 0.02f * (0.5f + 2.5f * t)).coerceIn(MIN_SPEED, MAX_SPEED),
                length = LENGTH,
                swim = SwimParams(
                    waveHz = 1.9f - 0.7f * t,
                    wavelengths = 0.9f + 0.5f * t,
                    amplitude = 0.05f + 0.04f * t,
                ),
            )
        }
    }
}
