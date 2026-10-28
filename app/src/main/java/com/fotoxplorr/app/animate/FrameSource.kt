package com.fotoxplorr.app.animate

import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.media.MediaAsset

/**
 * Decoded colour pixels: [width] x [height], row-major straight (not premultiplied) ARGB ints. The
 * Android side turns a `Bitmap` into one of these and gives the bitmap back at once, so nothing
 * below this line holds a native allocation.
 */
class RawFrame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "a frame must not be empty" }
        require(argb.size.toLong() == width.toLong() * height) { "argb must hold ${width}x$height pixels" }
    }
}

/** How much of a source's picture is see-through, which decides whether a GIF has to be written whole. */
enum class Transparency {
    /** Every pixel is opaque. */
    NONE,

    /** Some pixels are partly see-through (a soft edge), none is clear enough for a GIF to call clear. */
    PARTIAL,

    /** Some pixel is clear at GIF's one-bit threshold ([AnimSequence.ALPHA_CUTOFF]). */
    CLEAR,
}

/**
 * What a source knows about itself before any frame is drawn.
 *
 * @property width the natural upright size; the planner shrinks from it (never enlarges). For
 *   stills it is the size the first picture was read at (no larger than the source was asked for),
 *   because that is all a source can say without reading everything.
 * @property delaysMs one per source frame, at least one; what the planner picks from.
 * @property name the first file's display name, which the saved file is named after.
 * @property plays how many times the source itself plays (0 for ever), or null when it has no opinion.
 * @property problem why a damaged file was read only in part; [DecodeProblem.NONE] otherwise.
 * @property keepFrames one flag per frame, true for one the planner should drop last ([PlanRequest.keepFrames]); null for none.
 */
class SourceInfo(
    val width: Int,
    val height: Int,
    val delaysMs: IntArray,
    val name: String,
    val dateTakenMillis: Long,
    val kind: ContentKind = ContentKind.PHOTO,
    val changeFraction: Double = 0.5,
    val plays: Int? = null,
    val transparency: Transparency = Transparency.NONE,
    val problem: DecodeProblem = DecodeProblem.NONE,
    val keepFrames: BooleanArray? = null,
) {
    init {
        require(width > 0 && height > 0) { "the source has no size" }
        require(delaysMs.isNotEmpty()) { "the source has no frames" }
    }
}

/**
 * Where an animation's frames come from. The job calls [open] once, then [frame] for the frames
 * the plan picked (in rising order, though a source must survive being asked for an earlier one),
 * then [close]. A source never throws a message the person would read: it throws
 * [AnimSourceException] whose text is already a sentence from [AnimateCopy].
 */
interface FrameSource {
    /** Read enough to describe the source. Suspends; throws [AnimSourceException]. */
    suspend fun open(): SourceInfo

    /**
     * Source frame [index] as exactly [width] x [height] straight ARGB, placed on the canvas by the
     * source's own fit rule. [width] and [height] keep the aspect of [SourceInfo]'s size (rounding
     * aside); the array is the caller's to keep or change. Throws [AnimSourceException].
     */
    suspend fun frame(index: Int, width: Int, height: Int): IntArray

    /** Give back whatever the source holds (a reader, a cache). Safe to call twice. */
    fun close()
}

/** A source could not give what was asked; [message] is the sentence for the person. */
open class AnimSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A source stopped because a photo is protected (or no longer in the library's browsable list). */
class AnimSourceProtectedException : AnimSourceException(AnimateCopy.PROTECTED)

/** One still of a slideshow: the photo, how long it stays up, and optionally the edit to show it with. */
class AnimStill(val asset: MediaAsset, val holdMs: Int, val recipe: EditRecipe? = null) {
    init {
        require(holdMs in 1..MAX_HOLD_MS) { "a still stays up 1 ms to ${MAX_HOLD_MS / 1000} s" }
    }

    companion object {
        const val MAX_HOLD_MS = 60_000
    }
}

/**
 * A cross-fade between consecutive stills: [frames] in-betweens of [frameMs] each, eased, mixed in
 * linear light if [linearLight]. With [loop] the last fades back to the first. Zero [frames] is a
 * slideshow of cuts.
 */
class StillTransition(
    val frames: Int,
    val frameMs: Int = 60,
    val easing: Easing = Easing.EASE_IN_OUT,
    val linearLight: Boolean = false,
    val loop: Boolean = false,
) {
    init {
        require(frames in 0..MAX_FRAMES && frameMs in 1..1000) { "a transition is 0 to $MAX_FRAMES frames of 1 to 1000 ms" }
    }

    companion object {
        const val MAX_FRAMES = 30
    }
}

/** What the animation is made from. */
sealed interface AnimateInput {
    /** The photos whose pixels this reads; each must be exportable at the start and again before the file is made. */
    val photos: List<MediaAsset>

    /** The videos this reads, checked the same way. */
    val videos: List<MediaAsset> get() = emptyList()

    /** Pictures chosen from the library, shown in this order. [transition] is null for plain cuts. */
    class Stills(val items: List<AnimStill>, val transition: StillTransition? = null) : AnimateInput {
        override val photos: List<MediaAsset> get() = items.map { it.asset }.distinctBy { it.id }
    }

    /** The part of [asset] from [startMs] to [endMs], sampled [fps] times a second. */
    class Clip(val asset: MediaAsset, val startMs: Long, val endMs: Long, val fps: Double = 12.0) : AnimateInput {
        init {
            require(startMs >= 0 && endMs > startMs) { "the part of the video must have a length" }
            require(fps in MIN_FPS..MAX_FPS) { "fps is $MIN_FPS to $MAX_FPS" }
        }

        override val photos: List<MediaAsset> get() = emptyList()
        override val videos: List<MediaAsset> get() = listOf(asset)

        companion object {
            const val MIN_FPS = 1.0
            const val MAX_FPS = 60.0
        }
    }

    /** An animated GIF, APNG or WebP already in the library, to be remade (smaller, shorter, another format). */
    class Existing(val asset: MediaAsset) : AnimateInput {
        override val photos: List<MediaAsset> get() = listOf(asset)
    }
}

/**
 * Small pure pixel helpers the sources share.
 */
internal object FrameOps {

    /** Draw [px] over the opaque [background], in place: afterwards every pixel is opaque. */
    fun flatten(px: IntArray, background: Int): IntArray {
        val bg = background or (0xFF shl 24)
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) != 255) px[i] = FrameScaler.over(p, bg)
        }
        return px
    }

    /** [f] placed on a [width] x [height] canvas: a copy when the size already fits, a plain resize when only the size differs, else [FrameScaler.fit]. */
    fun place(f: RawFrame, width: Int, height: Int, mode: FitMode, background: Int): IntArray = when {
        f.width == width && f.height == height -> f.argb.copyOf()
        mode == FitMode.STRETCH || aspectAlmostEqual(f.width, f.height, width, height) -> FrameScaler.scale(f.argb, f.width, f.height, width, height)
        else -> FrameScaler.fit(f.argb, f.width, f.height, width, height, mode, background)
    }

    /** Whether two sizes have the same shape up to a pixel of rounding on the canvas side. */
    fun aspectAlmostEqual(w1: Int, h1: Int, w2: Int, h2: Int): Boolean {
        // |w1/h1 - w2/h2| small enough that rounding w2 or h2 by one pixel explains it.
        val a = w1.toDouble() * h2
        val b = w2.toDouble() * h1
        val slack = maxOf(w1, h1).toDouble() + 1.0
        return kotlin.math.abs(a - b) <= slack * 1.0
    }

    /** [f] turned clockwise by [degrees] (0, 90, 180 or 270). */
    fun rotateClockwise(f: RawFrame, degrees: Int): RawFrame {
        val turns = ((degrees / 90) % 4 + 4) % 4
        require(degrees % 90 == 0) { "only quarter turns" }
        if (turns == 0) return f
        val w = f.width
        val h = f.height
        val src = f.argb
        return when (turns) {
            1 -> {
                // New size h x w; the source's left column becomes the top row, read bottom to top.
                val out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
                RawFrame(h, w, out)
            }
            2 -> {
                val out = IntArray(w * h)
                for (i in out.indices) out[out.size - 1 - i] = src[i]
                RawFrame(w, h, out)
            }
            else -> {
                val out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = src[y * w + x]
                RawFrame(h, w, out)
            }
        }
    }

    /** The least transparency that describes [px]. */
    fun transparencyOf(px: IntArray): Transparency {
        var partial = false
        for (p in px) {
            val a = p ushr 24
            if (a < AnimSequence.ALPHA_CUTOFF) return Transparency.CLEAR
            if (a != 255) partial = true
        }
        return if (partial) Transparency.PARTIAL else Transparency.NONE
    }
}
