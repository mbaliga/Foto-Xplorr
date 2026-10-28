package com.fotoxplorr.app.animate

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One picture of a video-to-be: the file it was written to and how many ticks of the video's rate it stays up. */
class VideoFrameFile(val file: File, val ticks: Int) {
    init {
        require(ticks >= 1) { "a frame lasts at least one tick" }
    }
}

/** The video could not be made; [message] is the sentence for the person. */
class VideoMakeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Makes a silent H.264 MP4 from a list of pictures. The real one is [Media3VideoMaker]; the job
 * tests use a fake that writes a few bytes.
 */
interface VideoMaker {
    /** Write [pixels] (`width` x `height`, opaque) as a picture file the maker can read back. Blocking. */
    fun writeFrame(pixels: IntArray, width: Int, height: Int, file: File)

    /**
     * Encode [frames], in order, as one [width] x [height] video of [fps] whole frames a second and
     * about [bitrate] bits a second, into [output]. Reports 0..1. Throws [VideoMakeException]; a
     * cancelled caller stops the encode.
     */
    suspend fun make(frames: List<VideoFrameFile>, width: Int, height: Int, fps: Int, bitrate: Int, output: File, onProgress: (Float) -> Unit)
}

/**
 * [VideoMaker] on Media3 Transformer, set up the way [com.fotoxplorr.app.video.VideoExporter] sets
 * it up (a private cache file, a poll for progress, the Looper thread for everything Transformer is
 * touched by), with no new dependency.
 *
 * Each picture is one image item whose duration is a whole number of ticks and whose frame rate is
 * the video's, so Transformer makes exactly that many frames from it; a still held for two seconds
 * costs two seconds of identical frames, which H.264 stores in a few bytes. The sequence is built
 * for video only ([C.TRACK_TYPE_VIDEO]), so the file has no audio track and nothing is played or
 * recorded. Every item is scaled to the same size with [Presentation], so the sides are even and
 * equal whatever a picture file says.
 *
 * ## What is not known
 * Nothing here has run on a device. Not measured: how long a few hundred image items take to encode,
 * whether a phone's encoder accepts the size and bitrate asked for (the encoder factory may fall
 * back to a nearby setting, [DefaultEncoderFactory.Builder.setEnableFallback]), and how Transformer
 * reports progress for an all-image input.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3VideoMaker(context: Context) : VideoMaker {
    private val appContext = context.applicationContext

    override fun writeFrame(pixels: IntArray, width: Int, height: Int, file: File) {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).buffered().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, out)) throw IOException("Could not write a frame")
            }
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun make(frames: List<VideoFrameFile>, width: Int, height: Int, fps: Int, bitrate: Int, output: File, onProgress: (Float) -> Unit) {
        require(frames.isNotEmpty()) { "A video needs at least one frame" }
        try {
            // Transformer must be created, started, polled and cancelled on one Looper thread.
            withContext(Dispatchers.Main.immediate) { run(frames, width, height, fps, bitrate, output, onProgress) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExportException) {
            val encoder = e.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ||
                e.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
            throw VideoMakeException(if (encoder) AnimateCopy.NO_VIDEO_ENCODER else AnimateCopy.VIDEO_FAILED, e)
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw VideoMakeException(AnimateCopy.VIDEO_FAILED, e)
        }
    }

    private suspend fun run(
        frames: List<VideoFrameFile>,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        output: File,
        onProgress: (Float) -> Unit,
    ): Unit = coroutineScope {
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            // The bitrate is a target; a nearby setting the encoder supports beats a hard failure the person cannot act on.
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(appContext)
            .setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .setEncoderFactory(encoderFactory)
            .build()
        val fit = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        val items = frames.map { f ->
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f.file)))
                .setDurationUs(VideoTimeline.durationUs(f.ticks, fps))
                .setFrameRate(fps)
                .setEffects(Effects(emptyList(), listOf(fit)))
                .build()
        }
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItems(items).build()
        val composition = Composition.Builder(sequence).build()
        // Progress is a poll, not a callback; a sibling job ticks it on this same (main) thread.
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(PROGRESS_POLL_MS)
            }
        }
        try {
            suspendCancellableCoroutine { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                transformer.start(composition, output.absolutePath)
            }
        } finally {
            poller.cancel()
            // Back on the Looper thread here, which is where cancel() must be called; a no-op once finished.
            transformer.cancel()
        }
    }

    private companion object {
        const val FRAME_QUALITY = 95
        const val PROGRESS_POLL_MS = 250L
    }
}
