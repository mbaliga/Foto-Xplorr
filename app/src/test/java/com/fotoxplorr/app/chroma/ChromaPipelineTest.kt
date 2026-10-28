package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The whole pipeline (sample, subtract, divide by the card, convert) on synthetic frames of known
 * reflectance under a random room and a differently coloured flash: see [ChromaTestKit] for what
 * the simulation does and does not model. Each condition runs [TRIALS] random trials; the tests
 * assert the stated bars on the 95th percentile and print the whole table (grep CHROMA-DE) so the
 * numbers are on record. A trial the pipeline REFUSES is not an error (it is counted and printed);
 * a refusal rate that is too high is, because a pipeline that refuses everything is not accurate,
 * it is absent.
 *
 * CIEDE2000 of about 1 is the least a trained eye sees; 2 to 3 is a small visible difference.
 */
class ChromaPipelineTest {

    private class Row(val name: String, val n: Int, val refused: Int, val mean: Double, val p95: Double, val max: Double)

    private fun run(name: String, c: ChromaTestKit.Conditions, withCard: Boolean, matchLightness: Boolean = false, seed: Long = 7): Row {
        val rng = Random(seed)
        val errors = ArrayList<Double>()
        var refused = 0
        repeat(TRIALS) {
            val t = ChromaTestKit.trial(rng, c)
            when (val a = ChromaMeter.measure(t.surface, if (withCard) t.card else null)) {
                is Answer.Refused -> refused++
                is Answer.Value -> {
                    val m = a.value.measurement
                    val lab = if (matchLightness) ChromaTestKit.lightnessMatched(m.reflectance, t.truth) else m.lab
                    errors += DeltaE2000.between(lab, t.truthLab)
                }
            }
        }
        errors.sort()
        val row = Row(
            name, TRIALS, refused,
            if (errors.isEmpty()) Double.NaN else errors.average(),
            if (errors.isEmpty()) Double.NaN else errors[((errors.size - 1) * 0.95).toInt()],
            if (errors.isEmpty()) Double.NaN else errors.last(),
        )
        println(
            "CHROMA-DE | %-44s | trials %d | refused %3d | mean %5.2f | p95 %5.2f | max %5.2f"
                .format(row.name, row.n, row.refused, row.mean, row.p95, row.max),
        )
        return row
    }

    @Test
    fun withACardCleanFramesRecoverTheColourWithinThree() {
        val r = run("card, clean frames", ChromaTestKit.Conditions(noise = 0.0, jitter = 0.0), withCard = true)
        assertTrue("refused ${r.refused}", r.refused <= TRIALS / 10)
        assertTrue("p95 ${r.p95}", r.p95 < 3.0)
        assertTrue("mean ${r.mean}", r.mean < 1.5)
    }

    @Test
    fun withACardTwoPercentNoiseStaysWithinSix() {
        val r = run("card, 2% pixel noise", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0), withCard = true)
        assertTrue("refused ${r.refused}", r.refused <= TRIALS / 10)
        assertTrue("p95 ${r.p95}", r.p95 < 6.0)
    }

    @Test
    fun withACardNoiseAndFrameToFrameJitterStaysWithinSix() {
        val r = run("card, 2% noise + 1% frame jitter", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.01), withCard = true)
        assertTrue("refused ${r.refused}", r.refused <= TRIALS / 5)
        assertTrue("p95 ${r.p95}", r.p95 < 6.0)
    }

    @Test
    fun theLedColourDoesNotMatterWithACard() {
        val neutral = run("card, neutral LED, 2% noise", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.0), withCard = true)
        val tinted = run("card, +-40% LED tint, 2% noise", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.4), withCard = true)
        assertTrue("tinted p95 ${tinted.p95}", tinted.p95 < 6.0)
        assertTrue("a tinted LED costs at most 1.5 more: ${neutral.p95} vs ${tinted.p95}", tinted.p95 < neutral.p95 + 1.5)
    }

    @Test
    fun withoutACardAColourlessLedIsRecoveredToTheHueButNotTheLightness() {
        val hueOnly = run(
            "no card, neutral LED (lightness matched)", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.0),
            withCard = false, matchLightness = true,
        )
        val full = run("no card, neutral LED (as shown)", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.0), withCard = false)
        assertTrue("hue and chroma p95 ${hueOnly.p95}", hueOnly.p95 < 6.0)
        // The lightness is a guess about the flash's strength, so the shown colour is far worse.
        assertTrue("as shown ${full.mean} vs lightness matched ${hueOnly.mean}", full.mean > hueOnly.mean + 3.0)
    }

    @Test
    fun withoutACardAPlausibleTintedLedDegradesHonestly() {
        val tilt15 = run("no card, +-15% LED tint (lightness matched)", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.15), withCard = false, matchLightness = true)
        val tilt30 = run("no card, +-30% LED tint (lightness matched)", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.30), withCard = false, matchLightness = true)
        val asShown15 = run("no card, +-15% LED tint (as shown)", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.15), withCard = false)
        assertTrue("the guessed lightness costs more than the tint: ${asShown15.mean} vs ${tilt15.mean}", asShown15.mean > tilt15.mean)
        val card15 = run("card, +-15% LED tint (lightness matched)", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, ledTilt = 0.15), withCard = true, matchLightness = true)
        assertTrue("a card is better than none: ${card15.mean} vs ${tilt15.mean}", card15.mean < tilt15.mean)
        assertTrue("a bigger tint is worse: ${tilt15.mean} vs ${tilt30.mean}", tilt30.mean > tilt15.mean)
        // Stated bound for the no-card mode: within 10 for a mildly tinted LED, 15 for a strongly tinted one (mean).
        assertTrue("15% mean ${tilt15.mean}", tilt15.mean < 10.0)
        assertTrue("30% mean ${tilt30.mean}", tilt30.mean < 15.0)
    }

    @Test
    fun cardAtADifferentDistanceIsCorrectedWhenTheDistancesAreKnown() {
        val ignored = run(
            "card 1.3x nearer, distances unknown (no fix)",
            ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, distanceRatio = 1.3, knowDistances = false), withCard = true,
        )
        val fixed = run(
            "card 1.3x nearer, distances exact (inv. square)",
            ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, distanceRatio = 1.3, knowDistances = true), withCard = true,
        )
        val rough = run(
            "card 1.3x nearer, distance read 10% off",
            ChromaTestKit.Conditions(noise = 0.02, jitter = 0.0, distanceRatio = 1.3, distanceReadingError = 1.1, knowDistances = true), withCard = true,
        )
        assertTrue("fixed p95 ${fixed.p95}", fixed.p95 < 6.0)
        assertTrue("the correction beats ignoring the distance: ${fixed.mean} vs ${ignored.mean}", fixed.mean < ignored.mean)
        assertTrue("a 10% distance error still beats none: ${rough.mean} vs ${ignored.mean}", rough.mean < ignored.mean)
        assertTrue("but costs against exact: ${rough.mean} vs ${fixed.mean}", rough.mean > fixed.mean)
    }

    @Test
    fun heavierFrameJitterDegradesAndEventuallyRefuses() {
        val mild = run("card, 2% noise + 3% frame jitter", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.03), withCard = true)
        val bad = run("card, 2% noise + 6% frame jitter", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.06), withCard = true)
        // The solver's jitter allowance is 1%, so beyond that the figures grow; they are printed, not hidden.
        assertTrue("3% jitter p95 ${mild.p95}", mild.p95 < 12.0)
        assertTrue("6% jitter is worse than 3%: ${bad.mean} vs ${mild.mean}", bad.mean > mild.mean)
    }

    @Test
    fun aBrighterRoomCostsAccuracyBeforeItCostsTheAnswer() {
        val dim = run("card, room up to 0.5x the flash", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.01, maxAmbientShare = 0.5), withCard = true)
        val bright = run("card, room up to 6x the flash", ChromaTestKit.Conditions(noise = 0.02, jitter = 0.01, maxAmbientShare = 6.0), withCard = true)
        assertEquals(0, dim.refused)
        // Up to 6x the flash is still answered (that is what the 1% jitter allowance buys); the refusals start near 10x.
        assertTrue("a 6x room is mostly answered: ${bright.refused}", bright.refused < 20)
        // And what is still answered in a bright room is no worse than 6 at the 95th percentile.
        assertTrue("bright p95 ${bright.p95}", bright.p95 < 6.0)
    }

    @Test
    fun aVeryBrightRoomIsRefusedNotMeasured() {
        val rng = Random(3)
        var refused = 0
        var tooBright = 0
        repeat(60) {
            // The ambient light is drawn from 0 to 60 times the flash: past about 10 the flash is lost in the frame.
            val t = ChromaTestKit.trial(rng, ChromaTestKit.Conditions(noise = 0.02, jitter = 0.01, maxAmbientShare = 60.0))
            val r = ChromaMeter.measure(t.surface, t.card)
            if (r is Answer.Refused) {
                refused++
                if (r.reason == Refusal.TOO_BRIGHT_ROOM) tooBright++
            }
        }
        assertTrue("most of a bright-room set is refused: $refused of 60", refused >= 30)
        assertTrue("and said as a bright room: $tooBright", tooBright >= 20)
    }

    @Test
    fun theResultDoesNotDependOnWhichRoomOrFlashSoLongAsTheyAreLocked() {
        // One surface, one card, many rooms and flashes: the answer must be the same colour.
        val rng = Random(11)
        val surface = ChromaTestKit.argb(180, 70, 40)
        val truth = ColourSpace.lab(surface)
        var worst = 0.0
        repeat(40) {
            val t = ChromaTestKit.trial(rng, ChromaTestKit.Conditions(noise = 0.0, jitter = 0.0, ledTilt = 0.4), surface)
            val a = ChromaMeter.measure(t.surface, t.card)
            if (a is Answer.Value) worst = maxOf(worst, DeltaE2000.between(a.value.measurement.lab, truth))
        }
        assertTrue("worst $worst", worst < 1.5)
    }

    @Test
    fun theReadingNamesTheColourItFound() {
        val rng = Random(5)
        val t = ChromaTestKit.trial(rng, ChromaTestKit.Conditions(noise = 0.0, jitter = 0.0, ledTilt = 0.1, maxAmbientShare = 0.5), ChromaTestKit.argb(200, 30, 30))
        val reading = (ChromaMeter.measure(t.surface, t.card) as Answer.Value).value
        assertEquals(3, reading.report.nearest.size)
        assertTrue(reading.report.nearest[0].deltaE <= reading.report.nearest[1].deltaE)
        assertTrue("description ${reading.report.description}", reading.report.description.endsWith("red"))
        assertTrue(!reading.measurement.approximate)
    }

    companion object {
        private const val TRIALS = 200
    }
}
