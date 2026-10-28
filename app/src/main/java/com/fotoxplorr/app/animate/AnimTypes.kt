package com.fotoxplorr.app.animate

/**
 * One picture of an animation: the whole canvas as straight (not premultiplied) ARGB, row by row,
 * and how long it stays up. Treat [pixels] as read-only once a frame is handed to anything; the
 * encoders keep a reference and the decoders hand out fresh arrays.
 *
 * A delay of 0 is legal here (a source may say it) and each encoder clamps it to what its format
 * and the viewers of that format can honour.
 */
class AnimFrame(val pixels: IntArray, val delayMs: Int) {
    init {
        require(delayMs >= 0) { "A frame cannot last a negative time" }
    }
}

/**
 * A whole animation, every frame the same size as the canvas (the decoders composite for you; the
 * encoders crop to what changed on their own). Pure data: nothing here knows about Android, files
 * or formats.
 *
 * [plays] is how many times the whole animation runs, 0 meaning for ever. That is APNG's meaning;
 * GIF stores "repeats after the first play" and the codec converts, so no caller needs to know.
 */
class AnimSequence(
    val width: Int,
    val height: Int,
    val frames: List<AnimFrame>,
    val plays: Int = 0,
) {
    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Canvas size $width x $height is not usable" }
        require(plays >= 0) { "plays cannot be negative" }
        val n = width.toLong() * height
        for (f in frames) require(f.pixels.size.toLong() == n) { "A frame is not $width x $height" }
    }

    /** Length of one play, in milliseconds. */
    val durationMs: Long get() = frames.sumOf { it.delayMs.toLong() }

    /** True when some pixel of some frame is see-through enough for GIF to call it transparent. */
    val hasTransparency: Boolean
        get() = frames.any { f -> f.pixels.any { (it ushr 24) < ALPHA_CUTOFF } }

    companion object {
        /** GIF stores sizes in 16 bits; PNG could go further but nothing here needs more. */
        const val MAX_SIDE = 65_535

        /**
         * GIF has one bit of alpha. A pixel below this alpha is transparent, one at or above it is
         * opaque and keeps its colour as it is (it is NOT flattened onto a background first).
         */
        const val ALPHA_CUTOFF = 128
    }
}

/** What went wrong, if anything, while reading an animation. The frames read before it are kept. */
enum class DecodeProblem {
    /** The whole file was read. */
    NONE,

    /** Not this format at all (no signature). Nothing was read. */
    NOT_THIS_FORMAT,

    /** The bytes ran out in the middle of something. */
    TRUNCATED,

    /** The bytes were there but made no sense (a bad code, a bad checksum, a frame outside the canvas). */
    CORRUPT,

    /** Reading stopped at one of the [DecodeLimits] to protect memory. */
    TOO_LARGE,
}

/**
 * What [GifReader] and [ApngReader] give back. A damaged file is not an exception: [sequence] holds
 * every frame that could be read in full (null only when not even the canvas size was readable),
 * and [problem] says why reading stopped. [detail] is for logs, never for a person; the words a
 * person reads are in [AnimateCopy].
 */
class AnimDecodeResult(
    val sequence: AnimSequence?,
    val problem: DecodeProblem,
    val detail: String = "",
) {
    val complete: Boolean get() = problem == DecodeProblem.NONE
}

/**
 * Ceilings that keep a hostile or enormous file from eating the heap. Defaults suit a phone: a
 * canvas up to 16 megapixels, and about 48 million pixels (192 MB of ints) over all frames when the
 * whole animation is held at once. A streaming caller that reads frame by frame ignores the total.
 */
class DecodeLimits(
    val maxCanvasPixels: Long = 16_000_000L,
    val maxFrames: Int = 4_096,
    val maxTotalPixels: Long = 48_000_000L,
)
