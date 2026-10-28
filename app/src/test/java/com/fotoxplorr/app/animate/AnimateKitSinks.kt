package com.fotoxplorr.app.animate

import java.io.File
import java.util.Collections

/** An [AnimateSink] that keeps what it was asked to publish, and can fail or look away on demand. */
internal class FakeAnimateSink(private val directory: File = AnimateKit.tempDirectory()) : AnimateSink {
    class Published(val bytes: ByteArray, val output: AnimOutput, val dateTakenMillis: Long, val sourceName: String)

    val published: MutableList<Published> = Collections.synchronizedList(ArrayList())

    /** Thrown from [publish] instead of saving. */
    @Volatile var failWith: Exception? = null

    /** Run inside [publish], just before the sink's own look at `stillAllowed`. */
    @Volatile var beforeLook: () -> Unit = {}

    /** A careless sink that never asks `stillAllowed`: the job's own look must then be the one that catches it. */
    @Volatile var ignoresLook = false

    override fun stagingDirectory(): File = directory.apply { mkdirs() }

    override suspend fun publish(
        staged: File,
        output: AnimOutput,
        dateTakenMillis: Long,
        sourceName: String,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): String {
        onProgress(0f)
        failWith?.let { throw it }
        beforeLook()
        if (!ignoresLook && !stillAllowed()) throw AnimateProtectedException()
        onProgress(1f)
        published += Published(staged.readBytes(), output, dateTakenMillis, sourceName)
        return "content://fake/published/${published.size}"
    }

    /** Files left in the staging directory, as names. */
    fun leftovers(): List<String> = directory.walkTopDown().filter { it != directory }.map { it.name }.toList()
}

/** A [VideoMaker] that writes a few bytes per picture and a small "video", and remembers what it was asked. */
internal class FakeVideoMaker : VideoMaker {
    class Made(val frames: List<VideoFrameFile>, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    val made: MutableList<Made> = Collections.synchronizedList(ArrayList())
    val written: MutableList<Triple<Int, Int, Int>> = Collections.synchronizedList(ArrayList())
    var failWith: Exception? = null
    var onMake: () -> Unit = {}

    /** The size of the file it writes, so a test can push it over a cap. */
    var fileBytes: (width: Int, height: Int, frames: Int) -> Int = { w, h, n -> 16 + n }

    override fun writeFrame(pixels: IntArray, width: Int, height: Int, file: File) {
        require(pixels.size == width * height)
        written += Triple(width, height, pixels.size)
        file.writeBytes(byteArrayOf(1, 2, 3))
    }

    override suspend fun make(frames: List<VideoFrameFile>, width: Int, height: Int, fps: Int, bitrate: Int, output: File, onProgress: (Float) -> Unit) {
        made += Made(frames, width, height, fps, bitrate)
        onProgress(0.5f)
        onMake()
        failWith?.let { throw it }
        require(frames.all { it.file.exists() }) { "a frame file was missing when the video was made" }
        output.writeBytes(ByteArray(fileBytes(width, height, frames.size)) { 7 })
        onProgress(1f)
    }
}
