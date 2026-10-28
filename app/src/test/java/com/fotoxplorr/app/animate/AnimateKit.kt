package com.fotoxplorr.app.animate

import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.File
import java.nio.file.Files
import java.util.Collections

/**
 * Procedural stand-ins for a phone and its photo library, for the animate Android-seam tests: a
 * library whose pictures are drawn from their id and index, a sink that keeps what it was asked to
 * publish, and a video maker that writes a few bytes. There are no real photographs, videos or
 * encoders in any of it; passing on these is necessary, not sufficient.
 */
internal object AnimateKit {

    fun asset(
        id: Long,
        name: String = "IMG_%04d.jpg".format(id),
        mime: String = "image/jpeg",
        width: Int = 640,
        height: Int = 480,
        taken: Long = 1_700_000_000_000L + id * 1000,
    ) = MediaAsset(
        id = MediaId(id), contentUriString = "content://fake/$id", displayName = name, mimeType = mime,
        bucketName = null, dateTakenMillis = taken, dateModifiedSeconds = 1, width = width, height = height, sizeBytes = 1000,
        relativePath = null, isFavorite = false, isTrashed = false,
    )

    fun video(id: Long, name: String = "VID_%04d.mp4".format(id), width: Int = 640, height: Int = 480, durationMs: Long = 10_000) =
        asset(id, name, "video/mp4", width, height).copy(durationMillis = durationMs)

    /** A picture that is different for every [seed] and shows its place: a diagonal gradient with the seed in the colour. */
    fun picture(seed: Int, w: Int, h: Int, alpha: Int = 255): RawFrame = RawFrame(
        w, h,
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (alpha shl 24) or (((x * 255 / maxOf(1, w - 1) + seed * 53) and 255) shl 16) or (((y * 255 / maxOf(1, h - 1) + seed * 17) and 255) shl 8) or ((seed * 71) and 255)
        },
    )

    fun solid(w: Int, h: Int, argb: Int) = RawFrame(w, h, IntArray(w * h) { argb })

    /**
     * [gif] cut short, as far along as still leaves between 2 and (all - 1) frames to read: a file whose
     * download stopped. Fails the test if no such cut exists.
     */
    fun cutGif(gif: ByteArray): ByteArray {
        val total = GifReader.decodeAll(gif).sequence!!.frames.size
        var cut = gif.size - 1
        while (cut > 20) {
            val r = GifReader.decodeAll(gif.copyOf(cut))
            val n = r.sequence?.frames?.size ?: 0
            if (n in 2 until total && r.problem != DecodeProblem.NONE) return gif.copyOf(cut)
            cut -= maxOf(1, gif.size / 200)
        }
        throw AssertionError("no cut of this GIF leaves some of its frames")
    }

    fun tempDirectory(): File = Files.createTempDirectory("animate-test").toFile().apply { deleteOnExit() }
}

/**
 * An [AnimateHost] that draws. [stillSize] says how big a photo is at full size; a decode at an edge
 * returns that shape no larger than the edge, never enlarged, like the real decoder.
 */
internal class FakeAnimateHost : AnimateHost {
    class StillCall(val id: Long, val edge: Int, val recipe: EditRecipe?)

    val stillCalls: MutableList<StillCall> = Collections.synchronizedList(ArrayList())
    val protectedIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    val unreadable: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    val files: MutableMap<Long, ByteArray> = HashMap()
    val videos: MutableMap<Long, FakeVideoReader> = HashMap()
    var encoder = true
    var fileLimitBytes: Long = Long.MAX_VALUE

    /** Full-size shape of a photo by id. */
    var stillSize: (Long) -> Pair<Int, Int> = { 640 to 480 }

    /** The pixels of a photo at a size; the default is [AnimateKit.picture] seeded by the id. */
    var render: (id: Long, w: Int, h: Int) -> RawFrame = { id, w, h -> AnimateKit.picture(id.toInt(), w, h) }

    /** Run at the start of every [still], with the asset and the edge: the place a test cancels or changes the world. */
    @Volatile var onStill: (MediaAsset, Int) -> Unit = { _, _ -> }

    /** Throw [OutOfMemoryError] from [still] when the edge is above this. */
    @Volatile var oomAboveEdge: Int = Int.MAX_VALUE

    val webpDecodes = Collections.synchronizedList(ArrayList<ByteArray>())
    var webpDecoder: (ByteArray) -> RawFrame? = { null }

    override fun mayExport(asset: MediaAsset): Boolean = asset.id.value !in protectedIds

    override fun mayUseVideo(asset: MediaAsset): Boolean = asset.isVideo && asset.id.value !in protectedIds

    override suspend fun still(asset: MediaAsset, longEdge: Int, recipe: EditRecipe?): RawFrame? {
        stillCalls += StillCall(asset.id.value, longEdge, recipe)
        onStill(asset, longEdge)
        if (longEdge > oomAboveEdge) throw OutOfMemoryError("test: a decode at $longEdge does not fit")
        if (!mayExport(asset) || asset.id.value in unreadable) return null
        val (fw, fh) = stillSize(asset.id.value)
        val s = minOf(1.0, longEdge.toDouble() / maxOf(fw, fh))
        val w = maxOf(1, Math.round(fw * s).toInt())
        val h = maxOf(1, Math.round(fh * s).toInt())
        return render(asset.id.value, w, h)
    }

    override suspend fun bytes(asset: MediaAsset, maxBytes: Long): ByteArray? {
        if (!mayExport(asset)) return null
        val b = files[asset.id.value] ?: return null
        if (b.size > minOf(maxBytes, fileLimitBytes)) throw AnimSourceException(AnimateCopy.FILE_TOO_BIG)
        return b
    }

    override fun decodeImage(bytes: ByteArray): RawFrame? {
        webpDecodes += bytes
        return webpDecoder(bytes)
    }

    override fun openVideo(asset: MediaAsset): VideoReader? = if (mayUseVideo(asset)) videos[asset.id.value] else null

    override fun canEncodeVideo(): Boolean = encoder
}

/** A [VideoReader] that draws frame pictures by time, and can fail at chosen times. */
internal class FakeVideoReader(
    val info: VideoInfo? = VideoInfo(640, 480, 10_000),
    val frames: (timeMs: Long, w: Int, h: Int) -> RawFrame? = { t, w, h -> AnimateKit.picture((t / 10).toInt(), w, h) },
) : VideoReader {
    val times: MutableList<Long> = ArrayList()
    var closed = false
    val missing: MutableSet<Long> = HashSet()
    var maxSeen = 0 to 0

    override fun info(): VideoInfo? = info

    override fun frameAt(timeMs: Long, maxWidth: Int, maxHeight: Int): RawFrame? {
        times += timeMs
        maxSeen = maxWidth to maxHeight
        if (timeMs in missing) return null
        return frames(timeMs, maxWidth, maxHeight)
    }

    override fun close() {
        closed = true
    }
}
