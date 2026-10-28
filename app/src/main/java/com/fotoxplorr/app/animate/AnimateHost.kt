package com.fotoxplorr.app.animate

import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.media.MediaAsset

/**
 * What the animation job needs from the phone's photo library and codecs, and nothing more. The real
 * one is [ExperimentAnimateHost] (every pixel read goes through the Experimental host's
 * `mayExport` and `decode`, TRAPS #33 and #34); the tests supply fakes that draw procedural pictures.
 *
 * None of these throws for a missing or protected file: they answer null or false, and the source
 * that asked turns that into a sentence.
 */
interface AnimateHost {
    /** Whether the photo's (or animated file's) pixels may be read and exported right now. Asked often; cheap. */
    fun mayExport(asset: MediaAsset): Boolean

    /** Whether the video's frames may be read and exported right now. A video is not a photo to the host's own check. */
    fun mayUseVideo(asset: MediaAsset): Boolean

    /**
     * The photo upright, EXIF turned, no larger than [longEdge] on its long side and never enlarged,
     * with [recipe] (a saved edit) applied when given. Null when it may not be read or cannot be.
     * An edit that crops can leave a result smaller than [longEdge]; the caller asked for enough
     * extra to allow for that.
     */
    suspend fun still(asset: MediaAsset, longEdge: Int, recipe: EditRecipe?): RawFrame?

    /**
     * The whole file of an animated picture, or null when it may not be read or cannot be. Throws
     * [AnimSourceException] with the "too large" sentence when it is over [maxBytes].
     */
    suspend fun bytes(asset: MediaAsset, maxBytes: Long): ByteArray?

    /** One frame image of an animated WebP (a stand-alone still WebP file) decoded, or null. */
    fun decodeImage(bytes: ByteArray): RawFrame?

    /** A reader over a video, or null when it may not be read or cannot be opened. The caller closes it. */
    fun openVideo(asset: MediaAsset): VideoReader?

    /** Whether this phone can write an H.264 video file. */
    fun canEncodeVideo(): Boolean
}

/** A video's upright size and length. */
class VideoInfo(val width: Int, val height: Int, val durationMs: Long) {
    init {
        require(width > 0 && height > 0)
    }
}

/**
 * Frames out of a video file. Blocking, one caller at a time; the source calls it off the main
 * thread. The pixels are upright, whatever rotation the file records.
 */
interface VideoReader : AutoCloseable {
    /** Null when the file's size or length cannot be read. */
    fun info(): VideoInfo?

    /**
     * The frame at or nearest [timeMs], no larger than [maxWidth] x [maxHeight] (it may come back
     * smaller, in the same shape), or null when there is none there.
     */
    fun frameAt(timeMs: Long, maxWidth: Int, maxHeight: Int): RawFrame?
}
