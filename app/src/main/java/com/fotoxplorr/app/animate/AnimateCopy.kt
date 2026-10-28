package com.fotoxplorr.app.animate

/**
 * The words the animated-picture core owns. One short sentence each. The pure code never says a
 * word to the person; the screens that use it pick from here, so a decode problem or a planner
 * step reads the same wherever it shows up.
 */
internal object AnimateCopy {
    // What the planner gave up to fit (one per [PlanStep]).
    const val SHRUNK = "Made smaller to keep the file light."
    const val TRIMMED = "Cut short to fit the length limit."
    const val RATE_LOWERED = "Fewer frames a second."
    const val FRAMES_THINNED = "Some frames were left out."
    const val SHRUNK_FOR_SIZE = "Made smaller again to fit the size limit."
    const val STILL_TOO_BIG = "Still larger than the limit."

    /** Shown next to any size before the file exists. */
    const val SIZE_IS_A_GUESS = "The size is a guess until it is made."

    // What a damaged file says (one per [DecodeProblem] other than NONE).
    const val NOT_ANIMATED = "This is not an animated picture."
    const val TRUNCATED = "The file ends early, so only part is shown."
    const val CORRUPT = "The file is damaged, so only part is shown."
    const val TOO_LARGE = "This is too large to open."

    fun planStep(step: PlanStep): String = when (step) {
        PlanStep.SHRUNK -> SHRUNK
        PlanStep.TRIMMED -> TRIMMED
        PlanStep.RATE_LOWERED -> RATE_LOWERED
        PlanStep.FRAMES_THINNED -> FRAMES_THINNED
        PlanStep.SHRUNK_FOR_SIZE -> SHRUNK_FOR_SIZE
        PlanStep.STILL_TOO_BIG -> STILL_TOO_BIG
    }

    /** Null when nothing went wrong. */
    fun decodeProblem(problem: DecodeProblem): String? = when (problem) {
        DecodeProblem.NONE -> null
        DecodeProblem.NOT_THIS_FORMAT -> NOT_ANIMATED
        DecodeProblem.TRUNCATED -> TRUNCATED
        DecodeProblem.CORRUPT -> CORRUPT
        DecodeProblem.TOO_LARGE -> TOO_LARGE
    }
}
