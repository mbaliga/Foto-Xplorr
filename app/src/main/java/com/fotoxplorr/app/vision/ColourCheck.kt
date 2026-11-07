package com.fotoxplorr.app.vision

import kotlin.random.Random

/** What a run of the check says, before it is put into words. */
internal sealed interface CheckVerdict {
    /** The control picture was missed, so nothing else can be read from this run. */
    data object Unreliable : CheckVerdict

    /** Every number was seen. */
    data object Typical : CheckVerdict

    /** Some numbers were missed, but no pair of pictures was missed together. */
    data object Mixed : CheckVerdict

    /** Both pictures of a kind were missed: [redGreen] (either red-green kind), [blueYellow]. */
    data class Hard(val redGreen: Boolean, val blueYellow: Boolean) : CheckVerdict
}

/**
 * The plan and the reading of the colour-vision check: one easy control picture, then two pictures
 * for each of the three axes ([TestPlates]), in a seeded order the person cannot guess. Pure.
 *
 * It is a screening game, not a test. The pictures are our own and have not been tried on people; a
 * miss can mean a dim, tinted or night-mode screen, bad light or a tired eye, and a pass does not
 * rule out a mild difference. So the wording is "this looks like it may be", it never names a
 * condition, and it does not tell a red-weak person from a green-weak one (two pictures cannot).
 */
internal object ColourCheck {
    /** Test pictures per axis. */
    const val PER_AXIS = 2

    /** The control plus the test pictures. */
    const val PLATE_COUNT = 1 + 3 * PER_AXIS

    private val AXES = listOf(Deficiency.PROTAN, Deficiency.DEUTAN, Deficiency.TRITAN)

    /**
     * The pictures of a run: the control first, then the six test pictures in a seeded order, all
     * with different digits. The same [seed] always gives the same run.
     */
    fun plates(seed: Long): List<Plate> {
        val rng = Random(seed)
        val digits = (0..9).shuffled(rng)
        val control = TestPlates.generate(digits[0], Deficiency.DEUTAN, seed, control = true)
        val tests = AXES.flatMap { axis -> List(PER_AXIS) { axis } }.shuffled(rng).mapIndexed { i, axis ->
            TestPlates.generate(digits[1 + i], axis, seed + 1 + i, control = false)
        }
        return listOf(control) + tests
    }

    /**
     * Reads [answers] (one per picture, null for "I see nothing"; a missing one is not seen). The
     * control missed gives [CheckVerdict.Unreliable]. An axis counts as hard when BOTH of its pictures
     * were missed: one miss in two is as likely a slip. A number read as a lookalike of the right one
     * ([TestPlates.looksLike]: a 3 read as a 9, a 5 as a 6) is a slip of a typical eye on a small
     * digit, so it counts as a miss for the run (the verdict is at most mixed) but not toward an axis;
     * "I see nothing" and any other wrong number count toward it. Either red-green axis counts as red-green.
     */
    fun verdict(plates: List<Plate>, answers: List<Int?>): CheckVerdict {
        val score = TestPlates.score(plates, answers)
        if (!score.controlSeen) return CheckVerdict.Unreliable
        val missedByAxis = HashMap<Deficiency, Int>()
        var missed = 0
        plates.forEachIndexed { i, plate ->
            if (plate.control) return@forEachIndexed
            val answer = answers.getOrNull(i)
            if (answer != plate.digit) {
                missed++
                val slip = answer != null && TestPlates.looksLike(answer, plate.digit)
                if (!slip) missedByAxis[plate.axis] = (missedByAxis[plate.axis] ?: 0) + 1
            }
        }
        val redGreen = (missedByAxis[Deficiency.PROTAN] ?: 0) >= PER_AXIS || (missedByAxis[Deficiency.DEUTAN] ?: 0) >= PER_AXIS
        val blueYellow = (missedByAxis[Deficiency.TRITAN] ?: 0) >= PER_AXIS
        return when {
            redGreen || blueYellow -> CheckVerdict.Hard(redGreen, blueYellow)
            missed > 0 -> CheckVerdict.Mixed
            else -> CheckVerdict.Typical
        }
    }
}

/**
 * Where a person is in a run, as a value that can be saved: the [seed] (which fixes the pictures),
 * the [stage] and the answers so far. Stage 0 is the introduction, 1 to [ColourCheck.PLATE_COUNT]
 * the pictures, and one past that the result. Saving the seed and the answers is all it takes to
 * bring the same run back after the process was killed.
 */
internal data class CheckRun(val seed: Long, val stage: Int = 0, val answers: List<Int?> = emptyList()) {
    val isIntro: Boolean get() = stage <= 0
    val isResult: Boolean get() = stage > ColourCheck.PLATE_COUNT

    /** The picture on screen, 0-based, or null at the introduction and the result. */
    val plateIndex: Int? get() = if (isIntro || isResult) null else stage - 1

    /** Whether Back has a step of its own to take (everything but the introduction). */
    val hasInnerStep: Boolean get() = stage > 0

    fun start(): CheckRun = copy(stage = 1, answers = emptyList())

    /** Records [value] (null for "I see nothing") for the picture on screen and moves on. Does nothing off a picture. */
    fun answer(value: Int?): CheckRun {
        val index = plateIndex ?: return this
        val next = answers.toMutableList()
        while (next.size <= index) next += null
        next[index] = value
        return copy(stage = stage + 1, answers = next)
    }

    /** One step back: from the result to the last picture, from a picture to the one before, from the first to the introduction. */
    fun back(): CheckRun = if (stage <= 0) this else copy(stage = stage - 1)

    /** A new run with new pictures. */
    fun again(newSeed: Long): CheckRun = CheckRun(newSeed)

    /** The answers as a list of exactly [ColourCheck.PLATE_COUNT], a picture not reached being "not seen". */
    fun padded(): List<Int?> = List(ColourCheck.PLATE_COUNT) { answers.getOrNull(it) }

    /** One line for a saver ([decode] is the inverse and never throws): `seed;stage;a,b,c` with `-` for "nothing". */
    fun encode(): String = "$seed;$stage;" + answers.joinToString(",") { it?.toString() ?: "-" }

    companion object {
        fun decode(text: String?, fallbackSeed: Long): CheckRun {
            val parts = text?.split(';') ?: return CheckRun(fallbackSeed)
            if (parts.size != 3) return CheckRun(fallbackSeed)
            val seed = parts[0].toLongOrNull() ?: return CheckRun(fallbackSeed)
            val stage = parts[1].toIntOrNull()?.coerceIn(0, ColourCheck.PLATE_COUNT + 1) ?: return CheckRun(fallbackSeed)
            val answers = if (parts[2].isEmpty()) {
                emptyList()
            } else {
                parts[2].split(',').map { token ->
                    if (token == "-") null else (token.toIntOrNull()?.takeIf { it in 0..9 } ?: return CheckRun(fallbackSeed))
                }
            }
            if (answers.size > ColourCheck.PLATE_COUNT) return CheckRun(fallbackSeed)
            return CheckRun(seed, stage, answers)
        }
    }
}
