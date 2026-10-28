package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The solver on hand-made patches, where the right answer is arithmetic. */
class FlashSolverTest {

    private fun patch(r: Double, g: Double, b: Double, se: Double = 0.0005) =
        Patch(LinearRgb(r, g, b), LinearRgb(se, se, se), 1000, 0.02, 0.0)

    private fun measured(a: Answer<Measurement>): Measurement = (a as Answer.Value).value
    private fun refusal(a: Answer<Measurement>): Refusal = (a as Answer.Refused).reason

    // A room (0.2, 0.15, 0.1) and a flash (0.4, 0.4, 0.3) on a surface of reflectance (0.5, 0.3, 0.2):
    private val rho = LinearRgb(0.5, 0.3, 0.2)
    private val room = LinearRgb(0.2, 0.15, 0.1)
    private val led = LinearRgb(0.4, 0.4, 0.3)
    private fun surfaceAmbient() = patch(rho.r * room.r, rho.g * room.g, rho.b * room.b)
    private fun surfaceFlash() = patch(rho.r * (room.r + led.r), rho.g * (room.g + led.g), rho.b * (room.b + led.b))
    private val cardRho = 0.8
    private fun cardAmbient() = patch(cardRho * room.r, cardRho * room.g, cardRho * room.b)
    private fun cardFlash() = patch(cardRho * (room.r + led.r), cardRho * (room.g + led.g), cardRho * (room.b + led.b))

    @Test
    fun withACardTheRoomAndTheLedBothDropOut() {
        val m = measured(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), CardPatches(cardAmbient(), cardFlash(), cardRho)))
        assertEquals(rho.r, m.reflectance.r, 1e-9)
        assertEquals(rho.g, m.reflectance.g, 1e-9)
        assertEquals(rho.b, m.reflectance.b, 1e-9)
        assertFalse(m.approximate)
        assertTrue(Caution.SAME_DISTANCE_ASSUMED in m.cautions)
    }

    @Test
    fun theSameCardGivesTheSameAnswerInAnyRoom() {
        for (k in listOf(0.0, 0.5, 3.0)) {
            val r = room.map { it * k }
            val sa = patch(rho.r * r.r, rho.g * r.g, rho.b * r.b)
            val sf = patch(rho.r * (r.r + led.r), rho.g * (r.g + led.g), rho.b * (r.b + led.b))
            val ca = patch(cardRho * r.r, cardRho * r.g, cardRho * r.b)
            val cf = patch(cardRho * (r.r + led.r), cardRho * (r.g + led.g), cardRho * (r.b + led.b))
            val m = measured(FlashSolver.solve(sa, sf, CardPatches(ca, cf, cardRho)))
            assertEquals("room x$k", rho.g, m.reflectance.g, 1e-9)
        }
    }

    @Test
    fun withoutACardANeutralLedGivesTheColourRatiosAndFlagsItself() {
        val neutral = LinearRgb(0.3, 0.3, 0.3)
        val sa = patch(rho.r * room.r, rho.g * room.g, rho.b * room.b)
        val sf = patch(rho.r * (room.r + neutral.r), rho.g * (room.g + neutral.g), rho.b * (room.b + neutral.b))
        val m = measured(FlashSolver.solve(sa, sf))
        assertTrue(m.approximate)
        assertTrue(Caution.LED_ASSUMED_NEUTRAL in m.cautions)
        assertTrue(Caution.LIGHTNESS_NOT_MEASURED in m.cautions)
        // The channel ratios are the surface's (the scale is a fixed level, not a measurement).
        assertEquals(rho.g / rho.r, m.reflectance.g / m.reflectance.r, 1e-9)
        assertEquals(rho.b / rho.r, m.reflectance.b / m.reflectance.r, 1e-9)
    }

    @Test
    fun withoutACardEverySurfaceIsShownAtTheSameFixedLightnessWhateverItsFlashSignal() {
        // A dark and a light surface of the same shade, both exposed to the same flash-frame level by auto
        // exposure: the flash differences are the same, and so are the readings. Even if they were not, the
        // proportions are all that is used, so a ten times stronger signal reads as the same colour.
        val neutral = LinearRgb(0.3, 0.3, 0.3)
        fun read(scale: Double): Measurement {
            val r = rho.map { it * scale }
            val sa = patch(r.r * room.r, r.g * room.g, r.b * room.b)
            val sf = patch(r.r * (room.r + neutral.r), r.g * (room.g + neutral.g), r.b * (room.b + neutral.b))
            return measured(FlashSolver.solve(sa, sf))
        }
        val dark = read(0.1)
        val light = read(1.0)
        assertEquals(FlashSolver.UNMEASURED_LUMINANCE, dark.reflectance.luminance, 1e-9)
        assertEquals(FlashSolver.UNMEASURED_LUMINANCE, light.reflectance.luminance, 1e-9)
        assertEquals(dark.lab.l, light.lab.l, 1e-6)
        assertEquals(dark.reflectance.r, light.reflectance.r, 1e-9)
    }

    @Test
    fun withoutACardADeepBlueIsScaledDownAsAWholeAndKeepsItsHue() {
        // Pure blue cannot reach the fixed luminance inside the gamut; it is scaled to fit, never clipped in one channel.
        val sa = patch(0.001, 0.001, 0.02)
        val sf = patch(0.002, 0.002, 0.42)
        val m = measured(FlashSolver.solve(sa, sf))
        assertEquals(1.0, m.reflectance.b, 1e-9)
        assertEquals(0.001 / 0.40, m.reflectance.r, 1e-9)
        assertTrue(m.reflectance.luminance < FlashSolver.UNMEASURED_LUMINANCE)
    }

    @Test
    fun withoutACardATintedLedTintsTheAnswer() {
        val m = measured(FlashSolver.solve(surfaceAmbient(), surfaceFlash()))
        // The LED is (0.4, 0.4, 0.3): short of blue, so a neutral-LED guess reads the surface as too little blue.
        assertTrue(m.reflectance.b / m.reflectance.r < rho.b / rho.r)
    }

    @Test
    fun anAssumedLedColourIsUndoneWhenItIsRight() {
        val m = measured(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), assumedLed = led))
        assertEquals(rho.g / rho.r, m.reflectance.g / m.reflectance.r, 1e-9)
        assertEquals(rho.b / rho.r, m.reflectance.b / m.reflectance.r, 1e-9)
    }

    @Test
    fun negativeNoiseInAChannelIsClampedToZero() {
        // The blue flash frame reads a hair under the ambient one: noise, not a changed light.
        val a = patch(0.20, 0.20, 0.100)
        val f = patch(0.40, 0.40, 0.0995)
        val m = measured(FlashSolver.solve(a, f))
        assertEquals(0.0, m.reflectance.b, 0.0)
        assertTrue(m.reflectance.r > 0.0)
    }

    @Test
    fun aFlashFrameClearlyDarkerThanTheAmbientOneIsAChangedLight() {
        assertEquals(Refusal.LIGHT_CHANGED, refusal(FlashSolver.solve(patch(0.5, 0.5, 0.5), patch(0.3, 0.3, 0.3))))
    }

    @Test
    fun aFlashThatAddsNothingIsTooFarOrTooBright() {
        // A dim room, flash adds 0.2% of the frame: out of reach.
        assertEquals(Refusal.TOO_FAR, refusal(FlashSolver.solve(patch(0.050, 0.050, 0.050), patch(0.0505, 0.0505, 0.0505))))
        // A bright room, flash adds 0.5% of the frame: lost in the room.
        assertEquals(Refusal.TOO_BRIGHT_ROOM, refusal(FlashSolver.solve(patch(0.600, 0.600, 0.600), patch(0.603, 0.603, 0.603))))
    }

    @Test
    fun theBrightnessOfTheRoomSetsWhereTheLimitIs() {
        // The same flash (adds 0.05) is fine in a dim room and refused in a bright one, because the
        // frame-to-frame jitter is a share of the frame.
        assertTrue(FlashSolver.solve(patch(0.05, 0.05, 0.05), patch(0.10, 0.10, 0.10)) is Answer.Value)
        assertEquals(Refusal.TOO_BRIGHT_ROOM, refusal(FlashSolver.solve(patch(0.75, 0.75, 0.75), patch(0.80, 0.80, 0.80))))
    }

    @Test
    fun aWeakButPassingSignalCarriesACaution() {
        // Flash adds 0.04 over a 0.34 frame with tiny pixel noise: the 1% frame jitter leaves a SNR of about 12.
        val m = measured(FlashSolver.solve(patch(0.30, 0.30, 0.30, 0.0001), patch(0.34, 0.34, 0.34, 0.0001)))
        assertTrue(m.signalToNoise in FlashSolver.MIN_SIGNAL_TO_NOISE..FlashSolver.COMFORTABLE_SIGNAL_TO_NOISE)
        assertTrue(Caution.SMALL_SIGNAL in m.cautions)
    }

    @Test
    fun aCardTheFlashBarelyLightsIsRefused() {
        val dimCard = CardPatches(patch(0.2, 0.2, 0.2), patch(0.2005, 0.2, 0.2), cardRho)
        assertEquals(Refusal.CARD_TOO_DIM, refusal(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), dimCard)))
    }

    @Test
    fun aCardFlashThatIsDarkerThanItsAmbientIsAChangedLight() {
        val bad = CardPatches(patch(0.5, 0.5, 0.5), patch(0.2, 0.2, 0.2), cardRho)
        assertEquals(Refusal.LIGHT_CHANGED, refusal(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), bad)))
    }

    @Test
    fun aSurfaceBrighterThanWhiteIsRefusedAndOneJustAboveIsClamped() {
        // A card whose flash signal is 0.05, a surface whose is 0.065: 1.3 times the card, times 0.85 is 1.105.
        val weakCard = CardPatches(patch(0.1, 0.1, 0.1), patch(0.15, 0.15, 0.15), 0.85)
        val slightly = measured(FlashSolver.solve(patch(0.1, 0.1, 0.1), patch(0.165, 0.165, 0.165), weakCard))
        assertEquals(1.0, slightly.reflectance.g, 1e-9)
        assertTrue(Caution.REFLECTANCE_CLAMPED in slightly.cautions)
        // A surface signal four times the card's (glare, a tilted card) does not add up.
        assertEquals(Refusal.NOT_ADDING_UP, refusal(FlashSolver.solve(patch(0.1, 0.1, 0.1), patch(0.30, 0.30, 0.30), weakCard)))
    }

    // -- distance ---------------------------------------------------------------------------------

    @Test
    fun aSurfaceTwiceAsFarAsTheCardGetsOneQuarterOfTheFlash() {
        // A surface at 1.25x the card's distance receives 1/1.5625 of the flash; the solver must scale it back.
        val ratio = 1.25
        val falloff = 1.0 / (ratio * ratio)
        val sa = surfaceAmbient()
        val sf = patch(rho.r * (room.r + led.r * falloff), rho.g * (room.g + led.g * falloff), rho.b * (room.b + led.b * falloff))
        val card = CardPatches(cardAmbient(), cardFlash(), cardRho, distanceMetres = 0.20)
        val m = measured(FlashSolver.solve(sa, sf, card, surfaceDistanceMetres = 0.20 * ratio))
        assertEquals(rho.r, m.reflectance.r, 1e-9)
        assertEquals(rho.g, m.reflectance.g, 1e-9)
        assertTrue(Caution.DISTANCE_COMPENSATED in m.cautions)
        assertFalse(Caution.SAME_DISTANCE_ASSUMED in m.cautions)
    }

    @Test
    fun distancesWithinFivePercentAreLeftAlone() {
        val card = CardPatches(cardAmbient(), cardFlash(), cardRho, distanceMetres = 0.20)
        val m = measured(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), card, surfaceDistanceMetres = 0.205))
        assertFalse(Caution.DISTANCE_COMPENSATED in m.cautions)
        assertEquals(rho.g, m.reflectance.g, 1e-9)
    }

    @Test
    fun distancesTooFarApartAreRefused() {
        val card = CardPatches(cardAmbient(), cardFlash(), cardRho, distanceMetres = 0.20)
        assertEquals(Refusal.DISTANCE_TOO_DIFFERENT, refusal(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), card, surfaceDistanceMetres = 0.20 * 1.6)))
        assertEquals(Refusal.DISTANCE_TOO_DIFFERENT, refusal(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), card, surfaceDistanceMetres = 0.20 / 1.6)))
    }

    @Test
    fun onlyOneDistanceKnownMeansTheyAreAssumedEqual() {
        val card = CardPatches(cardAmbient(), cardFlash(), cardRho, distanceMetres = null)
        val m = measured(FlashSolver.solve(surfaceAmbient(), surfaceFlash(), card, surfaceDistanceMetres = 0.3))
        assertTrue(Caution.SAME_DISTANCE_ASSUMED in m.cautions)
    }

    @Test
    fun diopterFocusReadingsBecomeMetresOrNothing() {
        assertEquals(0.25, FocusDistance.metresFromDiopters(4f)!!, 1e-9)
        assertEquals(null, FocusDistance.metresFromDiopters(0f))
        assertEquals(null, FocusDistance.metresFromDiopters(-1f))
        assertEquals(null, FocusDistance.metresFromDiopters(Float.NaN))
        assertEquals(null, FocusDistance.metresFromDiopters(null))
        assertEquals(null, FocusDistance.metresFromDiopters(0.2f)) // 5 m: past the usable range
        assertEquals(null, FocusDistance.metresFromDiopters(40f)) // 2.5 cm
    }
}
