package com.fotoxplorr.app.animate

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of a video as a [FrameSource]: [AnimateInput.Clip.fps] ticks a second from the start to
 * the end (or to the video's end, if it is shorter than asked), each tick showing the frame nearest
 * its start ([ClipTimeline]). Frames are read through the host's [VideoReader] at the size the plan
 * wants, so a long 4K clip is never held at full size.
 *
 * A tick with no frame (the reader returns null: a gap near the end, a decoder hiccup) shows the
 * frame before it, up to [MAX_MISSES] times in a row; after that the source gives up with a
 * sentence, because a clip that cannot be read is not one to fill with repeats. When the very first
 * tick has no frame there is nothing to repeat, so the next [MAX_LOOKAHEAD] ticks are tried and the
 * first that has one stands in; if none does, the video is unreadable.
 *
 * Every read asks the host's [AnimateHost.mayUseVideo] first.
 */
internal class VideoFrameSource(
    private val host: AnimateHost,
    private val input: AnimateInput.Clip,
) : FrameSource {

    private var reader: VideoReader? = null
    private var timeline: ClipTimeline? = null
    private var held: IntArray? = null
    private var misses = 0

    override suspend fun open(): SourceInfo {
        val asset = input.asset
        if (!host.mayUseVideo(asset)) throw AnimSourceProtectedException()
        val info = withContext(Dispatchers.IO) {
            val r = host.openVideo(asset) ?: throw AnimSourceException(AnimateCopy.VIDEO_UNREADABLE)
            // Kept at once, inside the block: a cancel that lands as the block returns throws its result away, and close() must still find the reader.
            reader = r
            val i = try {
                r.info()
            } catch (e: RuntimeException) {
                null
            }
            if (i == null) {
                close()
                throw AnimSourceException(AnimateCopy.VIDEO_UNREADABLE)
            }
            i
        }
        // A file that says it is shorter than asked ends where it ends; one that does not say its length is trusted.
        val end = if (info.durationMs > 0) min(input.endMs, info.durationMs) else input.endMs
        if (end <= input.startMs) {
            close()
            throw AnimSourceException(AnimateCopy.CLIP_EMPTY)
        }
        val timeline = ClipTimeline.build(input.startMs, end, input.fps)
        this.timeline = timeline
        return SourceInfo(
            width = info.width,
            height = info.height,
            delaysMs = timeline.delaysMs,
            name = asset.displayName,
            dateTakenMillis = asset.dateTakenMillis,
            kind = ContentKind.PHOTO,
            // A handheld clip changes most of the picture from one tick to the next; a locked-off one far less.
            changeFraction = 0.6,
        )
    }

    override suspend fun frame(index: Int, width: Int, height: Int): IntArray {
        val reader = checkNotNull(reader) { "open() first" }
        val timeline = checkNotNull(timeline) { "open() first" }
        require(index in timeline.delaysMs.indices) { "no frame $index" }
        if (!host.mayUseVideo(input.asset)) throw AnimSourceProtectedException()
        suspend fun read(tick: Int): RawFrame? = withContext(Dispatchers.IO) {
            try {
                reader.frameAt(timeline.timeMs(tick), width, height)
            } catch (e: RuntimeException) {
                null
            }
        }
        var raw = read(index)
        coroutineContext.ensureActive()
        if (raw == null && held == null) {
            // Some files give nothing at the very start (an edit list, a first sync frame after 0): the next ticks stand in.
            var ahead = 1
            while (raw == null && ahead <= MAX_LOOKAHEAD && index + ahead in timeline.delaysMs.indices) {
                raw = read(index + ahead++)
                coroutineContext.ensureActive()
            }
        }
        if (raw == null) {
            val last = held
            if (last == null) throw AnimSourceException(AnimateCopy.VIDEO_UNREADABLE)
            if (++misses > MAX_MISSES || last.size != width * height) throw AnimSourceException(AnimateCopy.VIDEO_STOPS)
            return last.copyOf()
        }
        misses = 0
        val placed = FrameOps.flatten(FrameOps.place(raw, width, height, FitMode.STRETCH, 0), OPAQUE_BLACK)
        held = placed
        return placed.copyOf()
    }

    override fun close() {
        runCatching { reader?.close() }
        reader = null
        held = null
    }

    companion object {
        /** Ticks in a row that may repeat the one before. */
        const val MAX_MISSES = 5

        /** How many ticks past the first the source looks for a frame when the first has none. */
        const val MAX_LOOKAHEAD = 3
        private const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}

/**
 * [VideoReader] over [MediaMetadataRetriever]. Frames are asked for with `OPTION_CLOSEST`, the only
 * option that lands on the requested time rather than the key frame before it (so a sample at 12
 * fps is not twelve copies of the same key frame); it decodes forward from the key frame and is the
 * slow one. How slow on a phone is not measured.
 *
 * ## Rotation
 * The retriever's width and height are the coded ones; [MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION]
 * says how far to turn the picture clockwise to show it upright. Whether the frames it returns are
 * already turned is not something the documentation says, so the first read asks for one UNSCALED
 * frame and compares its shape with the upright shape: if a portrait video's frame comes back
 * landscape, every frame is turned here. (A 180 degree turn and a square video cannot be told apart
 * this way and are taken as already upright.) A scaled request ([Build.VERSION_CODES.O_MR1] and
 * later) is used only once frames are known to come back upright, because the size it is asked for
 * is the upright one.
 *
 * A full-size read that runs out of memory ends the job with the memory sentence at once ([unscaled]); that
 * path is untested, since the shadow cannot run out of memory. Nothing here has run on a device;
 * Robolectric's shadow stands in for the retriever in the tests.
 */
internal class RetrieverVideoReader private constructor(
    private val retriever: MediaMetadataRetriever,
    private val sdk: Int,
) : VideoReader {

    private var info: VideoInfo? = null
    private var rotation = 0

    /** Null until the first frame has been compared; true when frames need turning by [rotation] here. */
    private var turnHere: Boolean? = null

    override fun info(): VideoInfo? {
        info?.let { return it }
        val w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        val h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        if (w <= 0 || h <= 0) return null
        rotation = (((number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 360) + 360) % 360).toInt()
        val quarter = rotation == 90 || rotation == 270
        val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
        return VideoInfo(if (quarter) h else w, if (quarter) w else h, max(0L, duration)).also { info = it }
    }

    // The scaled read is behind [sdk], which is the platform's level handed in so a test can lower it; lint cannot follow that.
    @Suppress("NewApi")
    override fun frameAt(timeMs: Long, maxWidth: Int, maxHeight: Int): RawFrame? {
        val upright = info() ?: return null
        val timeUs = max(0L, timeMs) * 1000L
        if (turnHere == null) {
            // The first read decides, from an unscaled frame, whether frames come back turned.
            val frame = unscaled(timeUs) ?: return null
            turnHere = needsTurn(frame, upright)
            return limit(if (turnHere == true) FrameOps.rotateClockwise(frame, rotation) else frame, maxWidth, maxHeight)
        }
        val here = turnHere == true
        val frame = if (sdk >= Build.VERSION_CODES.O_MR1 && !here) {
            val (w, h) = fitInside(upright.width, upright.height, maxWidth, maxHeight)
            val bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                ?: retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
            bitmap?.let(::rawOf)
        } else {
            unscaled(timeUs)
        } ?: return null
        return limit(if (here) FrameOps.rotateClockwise(frame, rotation) else frame, maxWidth, maxHeight)
    }

    override fun close() {
        runCatching { retriever.release() }
    }

    private fun number(key: Int): Long = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L

    /**
     * The frame at [timeUs] at the video's own size: the nearest one, else the key frame nearest (some files give nothing
     * for a time near 0 with the first). Running out of memory here is the video's size, which a smaller plan does not
     * change, so it is the person's sentence at once and not a reason for the job to step down six times.
     */
    private fun unscaled(timeUs: Long): RawFrame? = try {
        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        bitmap?.let(::rawOf)
    } catch (e: OutOfMemoryError) {
        throw AnimSourceException(AnimateCopy.OUT_OF_MEMORY, e)
    }

    /** The pixels of [bitmap], which is given back. */
    private fun rawOf(bitmap: Bitmap): RawFrame? {
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            return RawFrame(w, h, px)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** [frame] shrunk, in its own shape, to fit inside the limits; never enlarged. */
    private fun limit(frame: RawFrame, maxWidth: Int, maxHeight: Int): RawFrame {
        if (frame.width <= maxWidth && frame.height <= maxHeight) return frame
        val (w, h) = fitInside(frame.width, frame.height, maxWidth, maxHeight)
        return RawFrame(w, h, FrameScaler.scale(frame.argb, frame.width, frame.height, w, h))
    }

    companion object {
        /** Opens [uri], or returns null when the platform cannot read it. */
        fun open(context: Context, uri: Uri, sdk: Int = Build.VERSION.SDK_INT): RetrieverVideoReader? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                RetrieverVideoReader(retriever, sdk)
            } catch (e: RuntimeException) {
                runCatching { retriever.release() }
                null
            }
        }

        /** Whether a frame of [frame]'s shape needs turning to match the upright [upright]: only a landscape/portrait mismatch is visible. */
        internal fun needsTurn(frame: RawFrame, upright: VideoInfo): Boolean {
            val frameWide = frame.width > frame.height
            val frameTall = frame.height > frame.width
            val uprightWide = upright.width > upright.height
            val uprightTall = upright.height > upright.width
            return (frameWide && uprightTall) || (frameTall && uprightWide)
        }

        /** The largest size in the shape of [w] x [h] that fits inside [maxW] x [maxH], never larger than [w] x [h]. */
        internal fun fitInside(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
            val s = min(1.0, min(maxW.toDouble() / w, maxH.toDouble() / h))
            return max(1, Math.round(w * s).toInt()) to max(1, Math.round(h * s).toInt())
        }
    }
}
