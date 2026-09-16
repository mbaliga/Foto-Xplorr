package com.fotoxplorr.app.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
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
import com.fotoxplorr.app.media.MediaAsset
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Runs a [VideoExportOptions] edit through Media3 Transformer and lands the result as a NEW video
 * beside the original — the same never-touch-the-source contract `EditedCopyWriter` enforces for
 * photos, for the same reason: a library video may be the only copy that exists. Used directly by
 * [VideoConversionWriter] (plain format conversion) and by
 * [com.fotoxplorr.app.videoeditor.VideoEditPlan]'s export path (trim/speed/rotate/crop/filters),
 * so every hardening fix here (see the "known weaknesses" list in
 * `docs/adr/ADR-008-video-transcode-pipeline.md`) benefits both callers at once.
 *
 * Mechanics: Transformer writes to a private cache file (it wants a seekable plain file, not a
 * `content://` stream); only a COMPLETE export is copied into MediaStore (`IS_PENDING` until every
 * byte is across), then the cache file is deleted regardless of outcome. A cancelled or failed
 * export leaves nothing anywhere the user can see.
 *
 * Threading: Transformer requires create/start/cancel/poll on one Looper thread. [export] hops to
 * [Dispatchers.Main] for that part itself, so callers do not need to know or care which dispatcher
 * they were called from -- the ORIGINAL `origin/claude/fotoz-continue` prototype pushed that
 * requirement onto its caller instead, which is fine for a screen's own `rememberCoroutineScope`
 * but wrong for `VideoConversionWriter`, which historically ran on `Dispatchers.IO`.
 */
@UnstableApi
class VideoExporter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Exports [options] applied to [source]. [onProgress] is polled roughly every 250ms while
     * Transformer can estimate progress and reports a value in 0f..1f. Cancelling the calling
     * coroutine cancels the transcode and deletes the temp file; nothing is left behind either
     * way.
     */
    suspend fun export(
        source: MediaAsset,
        options: VideoExportOptions,
        onProgress: (Float) -> Unit = {},
        /** Distinguishes a plain format conversion ("clip-converted.mp4") from an edited export
         *  ("clip-edited.mp4") in the published file's name -- see [convertedName]. */
        nameMarker: String = "edited",
    ): Result<Uri> {
        if (!deviceSupportsVideoEncoder(options.codec)) {
            return Result.failure(IllegalStateException("This device has no ${options.codec.name} encoder."))
        }
        val cacheFile = File.createTempFile("fotoz-export-", ".mp4", appContext.cacheDir)
        return try {
            withContext(Dispatchers.Main.immediate) {
                runTransformer(source, options, cacheFile, onProgress)
            }
            val uri = withContext(Dispatchers.IO) { publish(source, cacheFile, nameMarker) }
            Result.success(uri)
        } catch (cancellation: CancellationException) {
            // Rethrow rather than reporting as a Result.failure: this IS the cooperative-cancel
            // path (see the class doc), and swallowing cancellation into a normal failure would
            // break structured concurrency for whatever scope cancelled this coroutine.
            throw cancellation
        } catch (t: Throwable) {
            // The known bug this fixes: the prototype this was ported from caught only
            // ExportException here, so a failure in publish() (a MediaStore insert failing, the
            // resolver refusing to open the new row, disk full mid-copy) escaped uncaught and
            // crashed the caller instead of surfacing as a normal export failure.
            Result.failure(RuntimeException(exportMessage(t), t))
        } finally {
            cacheFile.delete()
        }
    }

    private suspend fun runTransformer(
        source: MediaAsset,
        options: VideoExportOptions,
        output: File,
        onProgress: (Float) -> Unit,
    ): Unit = coroutineScope {
        val transformer = buildTransformer(options)
        // Transformer exposes progress as a poll, not a callback. A sibling job ticks it on this
        // same (main) dispatcher -- 4Hz of a lock-free read costs nothing perceptible.
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(PROGRESS_POLL_MS)
            }
        }
        try {
            suspendCancellableCoroutine { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                transformer.start(buildComposition(source, options), output.absolutePath)
                continuation.invokeOnCancellation { transformer.cancel() }
            }
        } finally {
            poller.cancel()
        }
    }

    private fun buildTransformer(options: VideoExportOptions): Transformer {
        val builder = Transformer.Builder(appContext)
            .setVideoMimeType(options.codec.mimeType)
            .setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_AAC)
        targetBitrateForQuality(options.quality)?.let { bitrate ->
            val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                // The requested bitrate/profile is a target, not a guarantee -- letting the
                // factory fall back to a supported nearby setting is the difference between "this
                // device encodes at a slightly different bitrate than asked" and "this quality
                // preset hard-fails on some devices for no reason a user could act on".
                .setEnableFallback(true)
                .build()
            builder.setEncoderFactory(encoderFactory)
        }
        return builder.build()
    }

    private fun buildComposition(source: MediaAsset, options: VideoExportOptions): Composition {
        val plan = resolveExportPlan(options, source.width, source.height, source.durationMillis)
        val mediaItem = MediaItem.Builder()
            .setUri(source.contentUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(options.trimStartMs)
                    .setEndPositionMs(options.trimEndMs ?: source.durationMillis)
                    .build(),
            )
            .build()

        val videoEffects = buildList<Effect> {
            if (options.rotationDegrees != 0 || options.mirror) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setScale(plan.scaleX, 1f)
                        .setRotationDegrees(plan.media3RotationDegrees)
                        .build(),
                )
            }
            plan.crop?.let { add(Crop(it.left, it.right, it.bottom, it.top)) }
            if (options.brightness != 0f) add(Brightness(options.brightness))
            if (options.contrast != 0f) add(Contrast(options.contrast))
            if (options.saturation != 0f) {
                add(HslAdjustment.Builder().adjustSaturation(options.saturation * 100f).build())
            }
            plan.targetShortSide?.let { add(Presentation.createForShortSide(it)) }
            options.textOverlay?.let { add(OverlayEffect(listOf(buildTextOverlay(it)))) }
        }

        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(options.muted)
            // Sonic-backed and pitch-preserving by construction (SpeedChangingAudioProcessor
            // wraps a SynchronizedSonicAudioProcessor) -- unlike the hand-written pipeline's
            // "relabel the sample rate" trick, this needs no separate "keep pitch" branch and
            // never asks an AAC encoder for an invalid sample rate.
            .setSpeed(constantSpeedProvider(options.speedFactor))
            .setEffects(Effects(/* audioProcessors= */ emptyList(), videoEffects))
            .build()

        return Composition.Builder(EditedMediaItemSequence.Builder(editedItem).build())
            .setHdrMode(hdrModeFor())
            .build()
    }

    /**
     * Always requests HDR-to-SDR tone-mapping: the OpenGL path is a no-op for a source that is
     * already SDR, and every codec this exporter targets ([VideoCodec]) is SDR-only, so keeping
     * HDR ([Composition.HDR_MODE_KEEP_HDR]) would silently misinterpret an HLG/PQ source's
     * transfer function against an SDR encoder -- the exact "washed out" bug ADR-008 revision 2
     * names. If a device cannot actually tone-map (pre-API-29 GLES, or a vendor gap),
     * Transformer fails the export and [exportMessage] turns that into a clear, specific error
     * rather than a silently wrong-looking file.
     */
    private fun hdrModeFor(): Int = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL

    private fun constantSpeedProvider(speed: Float): SpeedProvider = object : SpeedProvider {
        override fun getSpeed(presentationTimeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    /** Copies the finished bytes into MediaStore beside the original; see class doc. */
    private fun publish(source: MediaAsset, finished: File, nameMarker: String): Uri {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, convertedName(source.displayName, marker = nameMarker))
            put(MediaStore.Video.Media.MIME_TYPE, OUTPUT_MIME)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, validatedVideoRelativePath(source.relativePath))
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android would not create a new video file")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                finished.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open the new file for writing")
        } catch (t: Throwable) {
            // Never leave a pending, empty row behind -- it would show up as a broken video in
            // every other gallery on the device.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    private fun exportMessage(t: Throwable): String {
        val e = t as? ExportException ?: return t.message ?: "The export failed."
        return when (e.errorCode) {
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            -> "This device cannot decode this video's format."
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            -> "This device cannot encode the exported video."
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
                "This device could not process this video (it may be HDR content this device cannot tone-map)."
            else -> e.message ?: "The export failed."
        }
    }

    private companion object {
        const val OUTPUT_MIME = "video/mp4"
        const val PROGRESS_POLL_MS = 250L
    }
}
