package com.fotoxplorr.app.animate

/**
 * Every word the "Make a GIF or video" screen shows, apart from the sentences the job itself reports
 * ([AnimateCopy]). One short sentence each (owner feedback #6). Times and sizes are written by hand
 * from whole numbers, so the same figure reads the same in every language setting and a test can
 * pin it.
 */
internal object AnimateScreenCopy {
    // The entry's card.
    const val TITLE = "Make a GIF or video"
    const val BLURB = "Turn photos or a video clip into a moving picture."

    const val BACK = "Back"
    const val NEXT = "Next"
    const val LEAVE_FORGETS = "Leaving forgets what you picked. Back again to leave."

    // The three steps.
    const val STEP_SOURCE = "Choose"
    const val STEP_ARRANGE = "Arrange"
    const val STEP_OUTPUT = "Make"

    fun stepTitle(step: Int, name: String): String = "Step $step of 3: $name"

    // Step 1: where the frames come from.
    const val SRC_PHOTOS = "Photos"
    const val SRC_CLIP = "A video clip"
    const val SRC_FILE = "A GIF"
    const val SRC_DESCRIPTION = "Make it from"
    const val PHOTOS_HINT = "Tick two or more photos, in the order you want."
    const val CLIP_HINT = "Pick one video."
    const val FILE_HINT = "Pick a GIF or other moving picture."
    const val NO_PHOTOS = "There are no photos to pick."
    const val NO_VIDEOS = "There are no videos here."
    const val NO_FILES = "There are no GIFs here."
    const val NEED_TWO = "Pick at least two photos."
    const val KEEP_TWO = "Keep at least two."
    const val ROW_PICKED = "Picked"

    fun chosen(count: Int): String = if (count == 1) "1 photo chosen" else "$count photos chosen"

    fun clipRow(name: String, durationMs: Long): String =
        if (durationMs > 0) "$name, ${seconds(durationMs)}" else name

    // Step 2: arrange.
    const val STRIP = "Your photos, in order"
    const val MOVE_LEFT = "Move left"
    const val MOVE_RIGHT = "Move right"
    const val REMOVE = "Remove"
    const val HOLD = "Time on screen"
    const val SAME_FOR_ALL = "Same for all"
    const val LOOP = "Repeat for ever"
    const val SPEED = "Speed"
    const val SLOWER = "Slower"
    const val FASTER = "Faster"
    const val FADE = "Fade between photos"
    const val USE_EDITS = "Use my edits"
    const val KEEP_SEE_THROUGH = "Keep see-through parts"
    const val ON = "On"
    const val OFF = "Off"

    fun frameTitle(position: Int, count: Int): String = "Photo $position of $count"

    fun frameDescription(position: Int, count: Int, name: String, holdMs: Int): String =
        "Photo $position of $count, $name, ${seconds(holdMs.toLong())}"

    const val SELECTED = "Selected"
    const val NOT_SELECTED = "Not selected"

    fun speedValue(percent: Int): String = "$percent%"

    // The stepper buttons: the short text is on the button, the long one is for a screen reader.
    fun shorterText(ms: Int): String = "-${tenths(ms)}"
    fun longerText(ms: Int): String = "+${tenths(ms)}"
    fun shorterWords(ms: Int): String = "${tenthsWords(ms)} shorter"
    fun longerWords(ms: Int): String = "${tenthsWords(ms)} longer"

    // A part of a video.
    const val CLIP_START = "Start"
    const val CLIP_END = "End"
    const val CLIP_FPS = "Frames a second"
    const val CLIP_LENGTH_LABEL = "Length"

    fun clipLength(ms: Long): String = "Length ${seconds(ms)}"
    fun clipFrames(count: Int): String = if (count == 1) "About 1 frame." else "About $count frames."
    fun fpsValue(fps: Int): String = "$fps a second"
    fun earlierText(ms: Long): String = "-${secondsShort(ms)}"
    fun laterText(ms: Long): String = "+${secondsShort(ms)}"
    fun earlierWords(label: String, ms: Long): String = "$label ${secondsShort(ms)} earlier"
    fun laterWords(label: String, ms: Long): String = "$label ${secondsShort(ms)} later"
    fun fewerWords(n: Int): String = "$n fewer frames a second"
    fun moreWords(n: Int): String = "$n more frames a second"
    const val FILE_PICKED = "This one will be made again."

    // Step 3: what to make.
    const val OUT_DESCRIPTION = "Save as"
    const val OUT_GIF = "GIF"
    const val OUT_VIDEO = "Video (MP4)"
    const val OUT_APNG = "APNG"
    const val OUT_GIF_NOTE = "Plays almost everywhere."
    const val OUT_VIDEO_NOTE = "The smallest file, with no sound."
    const val OUT_APNG_NOTE = "Full colour, in a PNG file."
    const val VIDEO_PLAYS_ONCE = "A video plays once; your player can repeat it."

    fun estimate(bytes: Long): String = "About ${megabytes(bytes)}."
    fun estimateRange(low: Long, high: Long): String = "Could be ${megabytes(low)} to ${megabytes(high)}."
    fun playInfo(frames: Int, width: Int, height: Int, playMs: Long): String =
        "${if (frames == 1) "1 frame" else "$frames frames"}, $width x $height, ${seconds(playMs)}."

    fun sourceNow(bytes: Long): String = "The file is now ${megabytes(bytes)}."
    const val SIZE_UNKNOWN = "The size is known once it is made."
    const val BIG_GIF = "A GIF this long can be large; a video is much smaller."
    const val ESTIMATE_HEADING = "Size"

    // The preview.
    const val PREVIEW_HEADING = "Preview"
    const val PREVIEW_MAKE = "Show a preview"
    const val PREVIEW_REMAKE = "Show it again"
    const val PREVIEW_WORKING = "Making the preview"
    const val PREVIEW_PLAY = "Play"
    const val PREVIEW_PAUSE = "Pause"
    const val PREVIEW_DESCRIPTION = "Preview of the animation"
    const val PREVIEW_ROUGH = "Small and rough; the saved file is sharper."
    const val PREVIEW_MOTION_OFF = "Paused, because animations are turned off."
    const val PREVIEW_FAILED = "The preview could not be made."

    fun previewFrame(position: Int, count: Int): String = "Frame $position of $count"

    // Saving.
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val MAKING = "Making the file"
    const val LEAVING_CANCELS = "Leaving stops it."
    const val OPEN = "Open"
    const val NOT_LISTED = "It will show in your library soon."
    const val CANCELLED = "Stopped. Nothing was saved."
    const val SAVED_NOTE = "Your photos are not changed."

    fun saved(frames: Int, width: Int, height: Int, playMs: Long, bytes: Long): String =
        "${playInfo(frames, width, height, playMs).trimEnd('.')}, ${megabytes(bytes)}."

    // ---- formatting ---------------------------------------------------------------------------------

    /** [ms] as seconds with one decimal and a unit: "1.5 s". */
    fun seconds(ms: Long): String {
        val t = (ms.coerceAtLeast(0L) + 50L) / 100L
        return "${t / 10}.${t % 10} s"
    }

    /** [ms] as seconds with one decimal and no unit, for a button that sits beside a value that has one: "0.5". */
    private fun tenths(ms: Int): String {
        val t = (ms.coerceAtLeast(0) + 50) / 100
        return "${t / 10}.${t % 10}"
    }

    private fun tenthsWords(ms: Int): String {
        val t = (ms.coerceAtLeast(0) + 50) / 100
        val whole = "${t / 10}.${t % 10}"
        return if (whole == "1.0") "1 second" else "$whole seconds"
    }

    /** A step of a clip's start or end: "1 s" for whole seconds, "0.1 s" otherwise. */
    private fun secondsShort(ms: Long): String =
        if (ms % 1000L == 0L) "${ms / 1000L} s" else seconds(ms)

    /** [bytes] as megabytes with one decimal, or kilobytes below 1 MB: "2.4 MB", "640 KB". */
    fun megabytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        if (b < 1_000_000L) return "${maxOf(1L, (b + 500L) / 1000L)} KB"
        val t = (b + 50_000L) / 100_000L
        return "${t / 10}.${t % 10} MB"
    }
}
