package com.fotoxplorr.app.animate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import com.fotoxplorr.app.media.MediaAsset

/** The animated picture formats this app can read frame by frame. */
internal enum class AnimatedFormat {
    GIF,
    APNG,
    WEBP,
    ;

    companion object {
        /** What [data] is by its first bytes, or null. A plain PNG or still WebP is named here and refused later, when it turns out to have one frame. */
        fun sniff(data: ByteArray): AnimatedFormat? = when {
            data.size >= 6 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() &&
                data[3] == '8'.code.toByte() && (data[4] == '7'.code.toByte() || data[4] == '9'.code.toByte()) && data[5] == 'a'.code.toByte() -> GIF
            data.size >= 8 && PngIo.SIGNATURE.indices.all { data[it] == PngIo.SIGNATURE[it] } -> APNG
            data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() && data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte() -> WEBP
            else -> null
        }
    }
}

/**
 * An animated GIF, APNG or WebP already in the library as a [FrameSource], to be remade smaller,
 * shorter, at another rate or in another format.
 *
 * The decoders are this package's own, on every API level: [GifReader], [ApngReader] and
 * [WebpReader]. `ImageDecoder` is not used, because it hands an animation over only as a drawable
 * that runs on its own clock (see [WebpReader]); a job that picks frames by index cannot use that.
 *
 * ## Two passes, one frame in memory
 * [open] reads the whole file once to learn the frame count, the delays and whether anything is
 * see-through (the planner needs them before it can pick), keeping nothing. [frame] then reads
 * forward again to the frame asked for, so only the file's bytes and one canvas are held. Asking for
 * an earlier frame than the last one starts the reader over. At most [MAX_FRAMES] frames are read;
 * past that the rest is left out and the info says [DecodeProblem.TOO_LARGE].
 *
 * A damaged file is used as far as it goes ([SourceInfo.problem] says why it stopped). A file with
 * fewer than two frames is refused: it is not an animation.
 */
internal class AnimatedFileSource(
    private val host: AnimateHost,
    private val asset: MediaAsset,
) : FrameSource {

    /** The three readers behind one shape. */
    private class Reading(
        val width: () -> Int,
        val height: () -> Int,
        val plays: () -> Int,
        val problem: () -> DecodeProblem,
        val next: () -> AnimFrame?,
    )

    private var data: ByteArray? = null
    private var format: AnimatedFormat? = null
    private var reader: Reading? = null
    private var position = 0
    private var lastIndex = -1
    private var last: AnimFrame? = null
    private var frames = 0
    private var canvasW = 0
    private var canvasH = 0

    override suspend fun open(): SourceInfo {
        val bytes = host.bytes(asset, MAX_FILE_BYTES)
            ?: throw if (!host.mayExport(asset)) AnimSourceProtectedException() else AnimSourceException(AnimateCopy.FILE_UNREADABLE)
        val format = AnimatedFormat.sniff(bytes) ?: throw AnimSourceException(AnimateCopy.NOT_ANIMATED)
        data = bytes
        this.format = format
        return withContext(Dispatchers.Default) { scan(bytes, format) }
    }

    private suspend fun scan(bytes: ByteArray, format: AnimatedFormat): SourceInfo {
        val r = readerFor(bytes, format)
        val delays = ArrayList<Int>()
        var transparency = Transparency.NONE
        var plays = 1
        var capped = false
        while (true) {
            coroutineContext.ensureActive()
            val f = r.next() ?: break
            if (delays.size >= MAX_FRAMES) {
                capped = true
                break
            }
            if (delays.isEmpty()) plays = r.plays()
            delays.add(if (f.delayMs <= BROWSER_MIN_MS) BROWSER_DEFAULT_MS else f.delayMs)
            if (transparency != Transparency.CLEAR) {
                val t = FrameOps.transparencyOf(f.pixels)
                if (t.ordinal > transparency.ordinal) transparency = t
            }
        }
        val problem = if (capped) DecodeProblem.TOO_LARGE else r.problem()
        if (r.width() == 0 || delays.isEmpty()) {
            throw AnimSourceException(AnimateCopy.decodeProblem(problem) ?: AnimateCopy.FILE_UNREADABLE)
        }
        if (delays.size < 2) {
            throw AnimSourceException(AnimateCopy.decodeProblem(problem) ?: AnimateCopy.NOT_ANIMATED)
        }
        frames = delays.size
        canvasW = r.width()
        canvasH = r.height()
        return SourceInfo(
            width = canvasW,
            height = canvasH,
            delaysMs = delays.toIntArray(),
            name = asset.displayName,
            dateTakenMillis = asset.dateTakenMillis,
            kind = ContentKind.PHOTO,
            changeFraction = 0.5,
            plays = plays,
            transparency = transparency,
            problem = problem,
        )
    }

    override suspend fun frame(index: Int, width: Int, height: Int): IntArray {
        require(index in 0 until frames) { "no frame $index" }
        val bytes = checkNotNull(data) { "open() first" }
        val format = checkNotNull(format)
        if (!host.mayExport(asset)) throw AnimSourceProtectedException()
        val picture = withContext(Dispatchers.Default) {
            if (index == lastIndex) return@withContext last!!
            if (reader == null || index < position) {
                reader = readerFor(bytes, format)
                position = 0
            }
            val r = reader!!
            var f: AnimFrame? = null
            while (position <= index) {
                coroutineContext.ensureActive()
                f = r.next() ?: throw AnimSourceException(AnimateCopy.decodeProblem(r.problem()) ?: AnimateCopy.CORRUPT)
                position++
            }
            last = f
            lastIndex = index
            f!!
        }
        return FrameOps.place(RawFrame(canvasW, canvasH, picture.pixels), width, height, FitMode.STRETCH, 0)
    }

    override fun close() {
        reader = null
        data = null
        last = null
    }

    private fun readerFor(bytes: ByteArray, format: AnimatedFormat): Reading {
        val limits = DecodeLimits(maxCanvasPixels = MAX_CANVAS_PIXELS, maxFrames = MAX_FRAMES)
        return when (format) {
            AnimatedFormat.GIF -> GifReader(bytes, limits).let { g -> Reading({ g.width }, { g.height }, { g.plays }, { g.problem }, { g.next() }) }
            AnimatedFormat.APNG -> ApngReader(bytes, limits).let { a -> Reading({ a.width }, { a.height }, { a.plays }, { a.problem }, { a.next() }) }
            AnimatedFormat.WEBP -> WebpReader(bytes, limits) { host.decodeImage(it) }.let { w -> Reading({ w.width }, { w.height }, { w.plays }, { w.problem }, { w.next() }) }
        }
    }

    companion object {
        /** A larger file is not opened. */
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        /** Frames read from one file; a canvas this size is 16 MB a frame in memory. */
        const val MAX_FRAMES = 2_000

        const val MAX_CANVAS_PIXELS = 4_000_000L

        private const val BROWSER_MIN_MS = 10
        private const val BROWSER_DEFAULT_MS = 100
    }
}
