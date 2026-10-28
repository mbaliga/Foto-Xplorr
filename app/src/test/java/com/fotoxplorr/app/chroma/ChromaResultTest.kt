package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The result as pure functions: the step, the card's lines, the accuracy words, the comparison. */
class ChromaResultTest {

    private val facts = FakeCameraSource.facts()
    private val noFlash = FakeCameraSource.facts(hasFlash = false)

    private fun saved(argb: Int, kind: ReadingKind = ReadingKind.APPROXIMATE, at: Long = 1L) = SavedReading(argb, kind, at)

    private fun reading(argb: Int, withCard: Boolean, cautions: Set<Caution> = emptySet()): Outcome.Reading {
        val report = ColourReport.of(argb)
        val m = Measurement(LinearRgb(0.4, 0.2, 0.1), report.argb, report.lab, !withCard, cautions, 0.9, 40.0)
        return Outcome.Reading(ChromaReading(m, report), withCard)
    }

    // ---- the step ---------------------------------------------------------------------------------------

    @Test
    fun `without a card the first step is aiming`() {
        assertEquals(ChromaStep.AIM, ChromaStep.of(CaptureState.Ready(facts), useCard = false, cardKept = false, resultShown = false))
    }

    @Test
    fun `with a card wanted and not yet measured the step is the card`() {
        assertEquals(ChromaStep.CARD, ChromaStep.of(CaptureState.Ready(facts), useCard = true, cardKept = false, resultShown = false))
    }

    @Test
    fun `once the card is kept the step is aiming at the surface`() {
        assertEquals(ChromaStep.AIM, ChromaStep.of(CaptureState.Locked(facts), useCard = true, cardKept = true, resultShown = false))
    }

    @Test
    fun `a phone with no flash has no card step`() {
        assertEquals(ChromaStep.AIM, ChromaStep.of(CaptureState.Ready(noFlash), useCard = true, cardKept = false, resultShown = false))
    }

    @Test
    fun `a shown result is the third step whatever else is true`() {
        for (state in listOf(CaptureState.Ready(facts), CaptureState.Locked(facts), CaptureState.Done(facts), CaptureState.Idle)) {
            assertEquals(ChromaStep.RESULT, ChromaStep.of(state, useCard = true, cardKept = false, resultShown = true))
        }
    }

    @Test
    fun `the steps are numbered one to three`() {
        assertEquals(listOf(1, 2, 3), ChromaStep.entries.map { it.number })
        assertEquals("Step 2 of 3", ChromaCopy.step(2))
    }

    // ---- what a finished measurement keeps --------------------------------------------------------------

    @Test
    fun `a reading with a card is kept as calibrated and one without as approximate`() {
        val argb = ChromaTestKit.argb(170, 60, 40)
        val withCard = ReadingView.savedFrom(reading(argb, withCard = true), 5)!!
        val without = ReadingView.savedFrom(reading(argb, withCard = false), 6)!!
        assertEquals(ReadingKind.CALIBRATED, withCard.kind)
        assertEquals(ReadingKind.APPROXIMATE, without.kind)
        assertEquals(ColourReport.of(argb).argb, withCard.argb)
        assertEquals(6L, without.atMillis)
    }

    @Test
    fun `a room only reading is kept as room light`() {
        val report = ColourReport.of(ChromaTestKit.argb(20, 90, 200))
        val s = ReadingView.savedFrom(Outcome.RoomLight(report), 9)!!
        assertEquals(ReadingKind.ROOM, s.kind)
        assertEquals(report.argb, s.argb)
    }

    @Test
    fun `a refusal, a kept card and nothing are not colours`() {
        assertNull(ReadingView.savedFrom(Outcome.Refused(Refusal.CLIPPED), 1))
        assertNull(ReadingView.savedFrom(Outcome.CardKept, 1))
        assertNull(ReadingView.savedFrom(null, 1))
    }

    // ---- the card's lines -------------------------------------------------------------------------------

    @Test
    fun `the card has hex, rgb, lab, lch and three names with their distances`() {
        val v = ReadingView.of(saved(ChromaTestKit.argb(200, 100, 50), ReadingKind.CALIBRATED))
        assertEquals("#C86432", v.hex)
        assertEquals("RGB 200 100 50", v.rgbLine)
        assertTrue(v.labLine.startsWith("Lab "))
        assertEquals(3, v.labLine.removePrefix("Lab ").split(' ').size)
        assertTrue(v.lchLine.startsWith("LCh "))
        assertEquals(3, v.nearestLines.size)
        assertTrue(v.nearestLines.all { "difference" in it })
        assertEquals("${v.report.description}, #C86432", v.swatchDescription)
    }

    @Test
    fun `the lab line is the reports own lab`() {
        val v = ReadingView.of(saved(ChromaTestKit.argb(30, 140, 70), ReadingKind.CALIBRATED))
        val lab = v.report.lab
        assertEquals(ChromaCopy.labLine(lab.l, lab.a, lab.b), v.labLine)
    }

    @Test
    fun `the accuracy line says what each kind is good for`() {
        val argb = ChromaTestKit.argb(120, 120, 120)
        assertEquals("Approximate: good for the shade of colour, not for how light it is.", ReadingView.of(saved(argb, ReadingKind.APPROXIMATE)).accuracy)
        assertEquals("Corrected with your card; off by about 3 to 6 at best.", ReadingView.of(saved(argb, ReadingKind.CALIBRATED)).accuracy)
        assertEquals(ChromaCopy.AMBIENT_NOTE, ReadingView.of(saved(argb, ReadingKind.ROOM)).accuracy)
    }

    @Test
    fun `no claim of an exact or true colour is left in the words`() {
        val words = listOf(
            ChromaCopy.BLURB, ChromaCopy.NO_FLASH, ChromaCopy.AMBIENT_NOTE, ChromaCopy.ACCURACY_APPROXIMATE,
            ChromaCopy.ACCURACY_CALIBRATED, ChromaCopy.KIND_CALIBRATED, ChromaCopy.STEP_CARD,
        )
        for (w in words) {
            assertFalse(w, w.contains("true colour", ignoreCase = true) || w.contains("exact", ignoreCase = true) || w.contains("calibrated", ignoreCase = true))
        }
        assertTrue(ChromaCopy.STEP_CARD.contains("white paper"))
    }

    @Test
    fun `a reading without a lightness says so and shows no lightness word or number`() {
        val argb = ChromaTestKit.argb(190, 80, 50)
        val approx = ReadingView.of(saved(argb, ReadingKind.APPROXIMATE))
        val withCard = ReadingView.of(saved(argb, ReadingKind.CALIBRATED))
        for (v in listOf(approx, ReadingView.of(saved(argb, ReadingKind.ROOM)))) {
            assertTrue(ChromaCopy.LIGHTNESS_NOT_MEASURED in v.notes)
            assertFalse(v.labLine, v.labLine.matches(Regex("""Lab -?\d.*""")))
            assertTrue(v.labLine, v.labLine.startsWith("Lab a "))
            assertTrue(v.lchLine, v.lchLine.startsWith("Hue "))
            for (w in listOf("very dark", "dark", "mid", "light", "very light", "black", "white")) {
                assertFalse("${v.report.description} says $w", v.report.description.split(' ').contains(w))
            }
        }
        // A card-corrected reading keeps all three numbers and its words.
        assertTrue(withCard.notes.isEmpty())
        assertTrue(withCard.lchLine.startsWith("LCh "))
        assertEquals(ColourWords.describe(withCard.report.lab), withCard.report.description)
        // The live cautions follow the lightness note once, not twice.
        val live = reading(argb, withCard = false, cautions = setOf(Caution.LED_ASSUMED_NEUTRAL, Caution.LIGHTNESS_NOT_MEASURED))
        val s = ReadingView.savedFrom(live, 1)!!
        assertEquals(listOf(ChromaCopy.LIGHTNESS_NOT_MEASURED, ChromaCopy.APPROXIMATE), ReadingView.of(s, live).notes)
    }

    @Test
    fun `the names of a reading without a lightness are matched on shade and strength alone`() {
        val dim = ChromaTestKit.argb(60, 20, 10)
        val full = ColourReport.of(dim).nearest.map { it.name }
        val noLightness = ColourReport.of(dim, lightnessMeasured = false)
        // Taken at its own lightness a name is judged by its shade and strength; the distances stay honest numbers.
        assertEquals(3, noLightness.nearest.size)
        assertTrue(noLightness.nearest.zipWithNext().all { (a, b) -> a.deltaE <= b.deltaE })
        assertTrue(noLightness.nearest[0].deltaE <= ColourReport.of(dim).nearest[0].deltaE)
        assertEquals(3, full.size)
    }

    @Test
    fun `the live cautions come with the live outcome and not with a colour shown again`() {
        val argb = ChromaTestKit.argb(170, 60, 40)
        val live = reading(argb, withCard = true, cautions = setOf(Caution.SAME_DISTANCE_ASSUMED))
        val s = ReadingView.savedFrom(live, 1)!!
        assertEquals(listOf(ChromaCopy.SAME_DISTANCE_ASSUMED), ReadingView.of(s, live).notes)
        assertTrue(ReadingView.of(s).notes.isEmpty())
        // an outcome of a different colour is not this colour's
        assertTrue(ReadingView.of(s, reading(ChromaTestKit.argb(1, 2, 3), true, setOf(Caution.SMALL_SIGNAL))).notes.isEmpty())
        assertTrue(ReadingView.of(s, Outcome.Refused(Refusal.CLIPPED)).notes.isEmpty())
    }

    // ---- comparing --------------------------------------------------------------------------------------

    @Test
    fun `two card readings of one colour are too close to call, not the same`() {
        val a = saved(ChromaTestKit.argb(90, 120, 200), ReadingKind.CALIBRATED, 1)
        val b = saved(ChromaTestKit.argb(90, 120, 200), ReadingKind.CALIBRATED, 2)
        val c = CompareView.of(a, b)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, c.verdict.level)
        assertEquals("Difference 0.0", c.differenceLine)
        assertFalse(c.eitherApproximate)
    }

    @Test
    fun `card readings closer than the floor are not called different`() {
        // A difference of about 1 to 2 is a noticeable-by-eye step, but a card reading is only good to 3 to 6.
        val a = saved(ChromaTestKit.argb(120, 120, 120), ReadingKind.CALIBRATED, 1)
        val near = saved(ChromaTestKit.argb(124, 120, 120), ReadingKind.CALIBRATED, 2)
        val v = CompareView.of(a, near).verdict
        assertTrue(v.deltaE < SwatchCompare.CALIBRATED_FLOOR)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, v.level)
        val far = saved(ChromaTestKit.argb(150, 120, 120), ReadingKind.CALIBRATED, 3)
        assertTrue(CompareView.of(a, far).verdict.level != SwatchLevel.TOO_CLOSE_TO_CALL)
    }

    @Test
    fun `readings without a lightness are compared on shade and strength alone`() {
        // The same shade, one dark and one light: with no lightness to compare, they are not different.
        val dark = saved(ChromaTestKit.argb(70, 20, 10), ReadingKind.APPROXIMATE, 1)
        val light = saved(ChromaTestKit.argb(240, 130, 110), ReadingKind.APPROXIMATE, 2)
        val c = CompareView.of(dark, light)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, c.verdict.level)
        assertTrue("never lighter or darker", c.verdict.detail == null)
        // A different shade is still a different shade.
        val blue = saved(ChromaTestKit.argb(20, 60, 200), ReadingKind.APPROXIMATE, 3)
        val d = CompareView.of(dark, blue)
        assertEquals(SwatchLevel.VERY_DIFFERENT, d.verdict.level)
        assertTrue(d.verdict.detail != ChromaCopy.B_LIGHTER && d.verdict.detail != ChromaCopy.B_DARKER)
        // Mixed with a card reading it is the same rule: lightness is left out of the pair.
        val card = saved(ChromaTestKit.argb(240, 130, 110), ReadingKind.CALIBRATED, 4)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, CompareView.of(dark, card).verdict.level)
    }

    @Test
    fun `black and white are very different`() {
        val c = CompareView.of(saved(ChromaTestKit.argb(0, 0, 0), ReadingKind.CALIBRATED), saved(ChromaTestKit.argb(255, 255, 255), ReadingKind.CALIBRATED, 2))
        assertEquals(SwatchLevel.VERY_DIFFERENT, c.verdict.level)
        assertTrue(c.verdict.deltaE > 90.0)
    }

    @Test
    fun `an approximate reading cannot claim a fine difference`() {
        val a = saved(ChromaTestKit.argb(120, 120, 120), ReadingKind.APPROXIMATE, 1)
        val b = saved(ChromaTestKit.argb(135, 120, 120), ReadingKind.CALIBRATED, 2)
        val c = CompareView.of(a, b)
        assertTrue(c.eitherApproximate)
        assertTrue(c.verdict.deltaE in SwatchCompare.CALIBRATED_FLOOR..SwatchCompare.APPROXIMATE_FLOOR)
        assertEquals(SwatchLevel.TOO_CLOSE_TO_CALL, c.verdict.level)
        // the same two colours, both read with a card, get a real verdict: the floor there is lower
        assertTrue(CompareView.of(b, a.copy(kind = ReadingKind.CALIBRATED)).verdict.level != SwatchLevel.TOO_CLOSE_TO_CALL)
    }

    @Test
    fun `a room light reading counts as approximate in a comparison`() {
        val a = saved(ChromaTestKit.argb(120, 120, 120), ReadingKind.ROOM, 1)
        val b = saved(ChromaTestKit.argb(120, 120, 120), ReadingKind.CALIBRATED, 2)
        assertTrue(CompareView.of(a, b).eitherApproximate)
    }

    @Test
    fun `the candidates exclude the reading itself and run newest first`() {
        val a = saved(ChromaTestKit.argb(1, 1, 1), at = 1)
        val b = saved(ChromaTestKit.argb(2, 2, 2), at = 3)
        val c = saved(ChromaTestKit.argb(3, 3, 3), at = 2)
        assertEquals(listOf(b, c), CompareView.candidates(a, listOf(a, b, c)))
        assertTrue(CompareView.candidates(a, listOf(a)).isEmpty())
    }
}
