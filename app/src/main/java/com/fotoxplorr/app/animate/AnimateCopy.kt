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

    // The name of the running job in the activity shade and the notification.
    const val JOB_TITLE = "Making an animation"

    // What the job reports when it works.
    const val SAVED = "Animation saved."
    const val MEMORY_STEP = "Made smaller to fit the memory."
    const val SIZE_STEP = "Made smaller to fit the size limit."

    // What the job reports when it cannot.
    const val NEED_PICTURES = "Pick at least two pictures."
    const val CLIP_EMPTY = "That part of the video has no length."
    const val PROTECTED = "A protected photo cannot be used."
    const val NO_VIDEO_ENCODER = "This phone cannot make video files."
    const val VIDEO_UNREADABLE = "The video could not be read."
    const val VIDEO_STOPS = "The video stops being readable partway."
    const val FILE_UNREADABLE = "The file could not be read."
    const val FILE_TOO_BIG = "This file is too large to open."
    const val STILL_OVER_LIMIT = "It is still too large; try fewer pictures or a smaller size."
    const val OUT_OF_MEMORY = "There is not enough memory to make this."
    const val NO_SPACE = "There is not enough space on the phone."
    const val SAVE_FAILED = "The animation could not be saved."
    const val VIDEO_FAILED = "The video could not be made."
    const val FAILED = "The animation could not be made."

    fun cannotRead(picture: Int): String = "Picture $picture could not be read."

    /** A picture that was fine at the start and then could not be used: locked, trashed or deleted. */
    fun noLongerAvailable(picture: Int): String = "Picture $picture is no longer available."

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
