package com.fotoxplorr.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check's plan and its reading, with synthetic answers: no screen, no person. */
class ColourCheckTest {

    private val plates = ColourCheck.plates(42L)

    /** The right answer for every plate. */
    private fun all(): MutableList<Int?> = plates.map<Plate, Int?> { it.digit }.toMutableList()

    /** [all] with the plates of [axis] (the first [count] of them) answered "nothing". */
    private fun missing(axis: Deficiency, count: Int = ColourCheck.PER_AXIS): MutableList<Int?> {
        val answers = all()
        var left = count
        plates.forEachIndexed { i, plate ->
            if (!plate.control && plate.axis == axis && left > 0) {
                answers[i] = null
                left--
            }
        }
        return answers
    }

    @Test
    fun `a run is a control and two pictures for each axis, with seven different digits`() {
        assertEquals(ColourCheck.PLATE_COUNT, plates.size)
        assertEquals(7, plates.size)
        assertTrue(plates[0].control)
        assertEquals(1, plates.count { it.control })
        for (axis in listOf(Deficiency.PROTAN, Deficiency.DEUTAN, Deficiency.TRITAN)) {
            assertEquals(ColourCheck.PER_AXIS, plates.count { !it.control && it.axis == axis })
        }
        assertEquals(7, plates.map { it.digit }.toSet().size)
        assertTrue(plates.all { it.digit in 0..9 })
    }

    @Test
    fun `the same seed gives the same run and another seed another`() {
        val again = ColourCheck.plates(42L)
        assertEquals(plates.map { it.digit }, again.map { it.digit })
        assertEquals(plates.map { it.axis }, again.map { it.axis })
        assertEquals(plates[1].dots.map { it.argb }, again[1].dots.map { it.argb })
        val other = ColourCheck.plates(43L)
        assertNotEquals(plates.map { it.digit to it.axis }, other.map { it.digit to it.axis })
    }

    @Test
    fun `the order of the axes is not the same for every seed`() {
        val orders = (1L..12L).map { seed -> ColourCheck.plates(seed).drop(1).map { it.axis } }.toSet()
        assertTrue("12 seeds gave only ${orders.size} orders", orders.size > 3)
    }

    @Test
    fun `every plate has a good number of dots and a figure`() {
        for (plate in plates) {
            assertTrue(plate.dots.size > 300)
            assertTrue(plate.dots.any { it.inFigure })
        }
    }

    // ---- reading ----

    @Test
    fun `every number seen is typical`() {
        assertEquals(CheckVerdict.Typical, ColourCheck.verdict(plates, all()))
    }

    @Test
    fun `missing the control makes the run unreliable whatever else was seen`() {
        val answers = all()
        answers[0] = null
        assertEquals(CheckVerdict.Unreliable, ColourCheck.verdict(plates, answers))
        answers[0] = (plates[0].digit + 1) % 10
        assertEquals(CheckVerdict.Unreliable, ColourCheck.verdict(plates, answers))
    }

    @Test
    fun `both pictures of a red-green axis missed says red-green only`() {
        for (axis in listOf(Deficiency.PROTAN, Deficiency.DEUTAN)) {
            assertEquals(CheckVerdict.Hard(redGreen = true, blueYellow = false), ColourCheck.verdict(plates, missing(axis)))
        }
    }

    @Test
    fun `both blue-yellow pictures missed says blue-yellow only`() {
        assertEquals(CheckVerdict.Hard(redGreen = false, blueYellow = true), ColourCheck.verdict(plates, missing(Deficiency.TRITAN)))
    }

    @Test
    fun `missing both kinds says both`() {
        val answers = missing(Deficiency.DEUTAN)
        plates.forEachIndexed { i, p -> if (!p.control && p.axis == Deficiency.TRITAN) answers[i] = null }
        assertEquals(CheckVerdict.Hard(redGreen = true, blueYellow = true), ColourCheck.verdict(plates, answers))
    }

    @Test
    fun `one miss in a pair is a slip, not a finding`() {
        for (axis in listOf(Deficiency.PROTAN, Deficiency.DEUTAN, Deficiency.TRITAN)) {
            assertEquals(CheckVerdict.Mixed, ColourCheck.verdict(plates, missing(axis, count = 1)))
        }
    }

    @Test
    fun `a wrong number counts as a miss like nothing does`() {
        val answers = missing(Deficiency.DEUTAN, count = 0)
        var changed = 0
        plates.forEachIndexed { i, p ->
            if (!p.control && p.axis == Deficiency.DEUTAN) {
                answers[i] = (p.digit + 3) % 10
                changed++
            }
        }
        assertEquals(2, changed)
        assertEquals(CheckVerdict.Hard(redGreen = true, blueYellow = false), ColourCheck.verdict(plates, answers))
    }

    // ---- a typical eye misreading a seven-segment digit ----

    /** A run with chosen digits, so the lookalikes are known: control 1, deutan 3 and 5, protan 8 and 0, tritan 2 and 4. */
    private val fixed = listOf(
        Plate(1, Deficiency.DEUTAN, true, emptyList()),
        Plate(3, Deficiency.DEUTAN, false, emptyList()),
        Plate(5, Deficiency.DEUTAN, false, emptyList()),
        Plate(8, Deficiency.PROTAN, false, emptyList()),
        Plate(0, Deficiency.PROTAN, false, emptyList()),
        Plate(2, Deficiency.TRITAN, false, emptyList()),
        Plate(4, Deficiency.TRITAN, false, emptyList()),
    )

    @Test
    fun `digits one stroke apart look alike, and others do not`() {
        val pairs = listOf(3 to 9, 5 to 9, 5 to 6, 6 to 8, 8 to 9, 0 to 8, 1 to 7)
        for ((a, b) in pairs) {
            assertTrue("$a and $b", TestPlates.looksLike(a, b))
            assertTrue("$b and $a", TestPlates.looksLike(b, a))
        }
        assertFalse(TestPlates.looksLike(3, 6))
        assertFalse(TestPlates.looksLike(2, 5))
        assertFalse(TestPlates.looksLike(4, 7))
        assertFalse("a digit is not a lookalike of itself", TestPlates.looksLike(3, 3))
        assertFalse(TestPlates.looksLike(-1, 3))
    }

    @Test
    fun `reading a digit as its lookalike on both pictures of an axis is a slip, not a finding`() {
        // 3 read as 9 and 5 read as 6: the usual misreads of a typical eye on one axis.
        val answers = listOf<Int?>(1, 9, 6, 8, 0, 2, 4)
        assertEquals(CheckVerdict.Mixed, ColourCheck.verdict(fixed, answers))
    }

    @Test
    fun `seeing nothing on both pictures of an axis is still a finding`() {
        val answers = listOf<Int?>(1, null, null, 8, 0, 2, 4)
        assertEquals(CheckVerdict.Hard(redGreen = true, blueYellow = false), ColourCheck.verdict(fixed, answers))
    }

    @Test
    fun `a number that is not a lookalike counts as a miss, as before`() {
        // 3 read as 6 and 5 read as 2: nothing a one-stroke slip explains.
        val answers = listOf<Int?>(1, 6, 2, 8, 0, 2, 4)
        assertEquals(CheckVerdict.Hard(redGreen = true, blueYellow = false), ColourCheck.verdict(fixed, answers))
    }

    @Test
    fun `one lookalike misread and one nothing on an axis is a slip and a miss, so mixed`() {
        val answers = listOf<Int?>(1, 9, null, 8, 0, 2, 4)
        assertEquals(CheckVerdict.Mixed, ColourCheck.verdict(fixed, answers))
    }

    @Test
    fun `a short list of answers counts the rest as not seen`() {
        assertEquals(CheckVerdict.Unreliable, ColourCheck.verdict(plates, emptyList()))
        val onlyControl = listOf<Int?>(plates[0].digit)
        assertEquals(CheckVerdict.Hard(redGreen = true, blueYellow = true), ColourCheck.verdict(plates, onlyControl))
    }

    // ---- where a person is ----

    @Test
    fun `a run starts at the introduction with nothing answered`() {
        val run = CheckRun(5L)
        assertTrue(run.isIntro)
        assertFalse(run.hasInnerStep)
        assertEquals(null, run.plateIndex)
        assertFalse(run.isResult)
    }

    @Test
    fun `start and answer walk through every picture to the result`() {
        var run = CheckRun(5L).start()
        assertEquals(0, run.plateIndex)
        assertTrue(run.hasInnerStep)
        for (i in 0 until ColourCheck.PLATE_COUNT) {
            assertEquals(i, run.plateIndex)
            run = run.answer(i)
        }
        assertTrue(run.isResult)
        assertEquals(null, run.plateIndex)
        assertEquals((0 until ColourCheck.PLATE_COUNT).toList(), run.answers)
        // An answer at the result changes nothing.
        assertEquals(run, run.answer(3))
    }

    @Test
    fun `back steps one picture and keeps the answers, and re-answering replaces one`() {
        var run = CheckRun(5L).start().answer(1).answer(2).answer(3)
        assertEquals(3, run.plateIndex)
        run = run.back()
        assertEquals(2, run.plateIndex)
        assertEquals(listOf<Int?>(1, 2, 3), run.answers)
        run = run.answer(9)
        assertEquals(listOf<Int?>(1, 2, 9), run.answers)
        assertEquals(3, run.plateIndex)
    }

    @Test
    fun `back from the first picture is the introduction and from the result is the last picture`() {
        assertTrue(CheckRun(5L).start().back().isIntro)
        assertEquals(CheckRun(5L), CheckRun(5L).back())
        var run = CheckRun(5L).start()
        repeat(ColourCheck.PLATE_COUNT) { run = run.answer(null) }
        assertTrue(run.isResult)
        assertEquals(ColourCheck.PLATE_COUNT - 1, run.back().plateIndex)
    }

    @Test
    fun `nothing is a real answer and is kept as one`() {
        val run = CheckRun(5L).start().answer(null).answer(4)
        assertEquals(listOf<Int?>(null, 4), run.answers)
        assertEquals(listOf<Int?>(null, 4, null, null, null, null, null), run.padded())
    }

    @Test
    fun `a new run has a new seed and no answers`() {
        val run = CheckRun(5L).start().answer(1).again(6L)
        assertEquals(CheckRun(6L), run)
    }

    @Test
    fun `a run round trips through its saved line`() {
        var run = CheckRun(-123456789L).start().answer(3).answer(null).answer(0)
        assertEquals(run, CheckRun.decode(run.encode(), 1L))
        run = CheckRun(77L)
        assertEquals(run, CheckRun.decode(run.encode(), 1L))
        repeat(1) { }
        var done = CheckRun(9L).start()
        repeat(ColourCheck.PLATE_COUNT) { done = done.answer(it) }
        assertEquals(done, CheckRun.decode(done.encode(), 1L))
    }

    @Test
    fun `a bad saved line gives a fresh run and never throws`() {
        val fresh = CheckRun(11L)
        for (text in listOf(null, "", "x", "1;2", "a;1;", "1;b;", "1;1;11", "1;1;a", "1;1;1,2,3,4,5,6,7,8", "1;1;-1")) {
            assertEquals("for \"$text\"", fresh, CheckRun.decode(text, 11L))
        }
        // A stage out of range is pulled in rather than trusted.
        assertEquals(ColourCheck.PLATE_COUNT + 1, CheckRun.decode("5;99;", 11L).stage)
        assertEquals(0, CheckRun.decode("5;-3;", 11L).stage)
    }

    // ---- the words ----

    @Test
    fun `no result ever names a condition or claims a diagnosis`() {
        val verdicts = listOf(
            CheckVerdict.Unreliable, CheckVerdict.Typical, CheckVerdict.Mixed,
            CheckVerdict.Hard(true, false), CheckVerdict.Hard(false, true), CheckVerdict.Hard(true, true),
        )
        val banned = listOf("protan", "deutan", "tritan", "daltonism", "colour blind", "color blind", "colourblind", "you have", "deficien")
        for (v in verdicts) {
            val lines = VisionCopy.resultLines(v)
            val text = lines.joinToString(" ").lowercase()
            for (word in banned) assertFalse("\"$word\" in $v", text.contains(word))
            assertTrue("the screening-not-diagnosis line is always there", lines.contains(VisionCopy.PLATE_DISCLAIMER))
            assertTrue(lines.contains(VisionCopy.RESULT_DOCTOR))
        }
    }

    @Test
    fun `a finding is worded as a maybe`() {
        val lines = VisionCopy.resultLines(CheckVerdict.Hard(true, true))
        assertTrue(lines[0].startsWith("This looks like"))
        assertTrue(lines[0].contains("may"))
        assertTrue(lines[1].startsWith("This looks like"))
        assertTrue(lines[1].contains("may"))
        assertEquals(VisionCopy.RESULT_RED_GREEN, lines[0])
        assertEquals(VisionCopy.RESULT_BLUE_YELLOW, lines[1])
    }

    @Test
    fun `a typical result is not a clearance - it says a pass leaves a mild difference open`() {
        val lines = VisionCopy.resultLines(CheckVerdict.Typical)
        assertEquals(VisionCopy.RESULT_TYPICAL, lines[0])
        assertTrue(lines.contains(VisionCopy.RESULT_PASS_NOTE))
        assertTrue(VisionCopy.RESULT_PASS_NOTE.contains("mild difference"))
        assertFalse("the headline does not call the vision typical", VisionCopy.RESULT_TYPICAL.lowercase().contains("typical"))
        assertFalse(VisionCopy.resultLines(CheckVerdict.Mixed).contains(VisionCopy.RESULT_PASS_NOTE))
    }

    @Test
    fun `an unreliable run says to check the screen and nothing else about colours`() {
        val lines = VisionCopy.resultLines(CheckVerdict.Unreliable)
        assertEquals(VisionCopy.PLATE_CHECK_SCREEN, lines[0])
        assertFalse(lines.any { it == VisionCopy.RESULT_RED_GREEN || it == VisionCopy.RESULT_BLUE_YELLOW || it == VisionCopy.RESULT_TYPICAL })
    }
}
