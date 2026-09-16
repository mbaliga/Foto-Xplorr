package com.fotoxplorr.app.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.fotoxplorr.app.video.validatedAudioRelativePath
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
 * Re-encodes a standalone audio file to AAC in an M4A container — Save As for audio, on Media3
 * Transformer (audio-only: no video track, no effects). See
 * `docs/adr/ADR-008-video-transcode-pipeline.md` (revision 2) for why this replaced the
 * hand-written `MediaCodec` buffer-mode pipeline `AudioTranscoder`/`PcmTiming` used to be. The
 * video counterpart is [com.fotoxplorr.app.video.VideoConversionWriter] /
 * [com.fotoxplorr.app.video.VideoExporter]; this class does not share that class's Transformer
 * plumbing (a genuinely different MediaStore collection, `ContentValues` shape and
 * `RELATIVE_PATH` allow-list) but follows the exact same publish/cancel/cleanup contract.
 */
@UnstableApi
class AudioConversionWriter(context: Context) {
    private val appContext = context.applicationContext

    /** Plain AAC conversion at [bitrate] bits/sec (default 128kbps, a common "good enough for
     *  music" constant-quality target every AAC encoder on API 26+ supports). */
    suspend fun convertToAac(source: AudioAsset, bitrate: Int = DEFAULT_BITRATE): Result<Uri> {
        val cacheFile = File.createTempFile("fotoz-audio-export-", ".m4a", appContext.cacheDir)
        return try {
            withContext(Dispatchers.Main.immediate) { runTransformer(source, bitrate, cacheFile) }
            val uri = withContext(Dispatchers.IO) { publish(source, cacheFile) }
            Result.success(uri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // Catches every failure, not just ExportException -- see VideoExporter's identical
            // fix for why a narrower catch here would let a MediaStore publish failure escape
            // uncaught instead of surfacing as a normal Result.failure.
            Result.failure(RuntimeException(exportMessage(t), t))
        } finally {
            cacheFile.delete()
        }
    }

    private suspend fun runTransformer(source: AudioAsset, bitrate: Int, output: File): Unit = coroutineScope {
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(appContext)
            .setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .build()
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                transformer.getProgress(holder)
                delay(PROGRESS_POLL_MS)
            }
        }
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(source.contentUri))
            .setRemoveVideo(true)
            .build()
        try {
            suspendCancellableCoroutine { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                    ) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                transformer.start(editedItem, output.absolutePath)
                continuation.invokeOnCancellation { transformer.cancel() }
            }
        } finally {
            poller.cancel()
        }
    }

    private fun publish(source: AudioAsset, finished: File): Uri {
        val resolver = appContext.contentResolver
        val relativePath = validatedAudioRelativePath(sourceRelativePath(source))
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, convertedAudioName(source.displayName))
            put(MediaStore.Audio.Media.MIME_TYPE, TARGET_MIME_TYPE)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1_000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android would not create a new audio file")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                finished.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open the new file for writing")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    /** [AudioAsset] carries no `RELATIVE_PATH` of its own (see that type's own doc for why it is
     *  a deliberately separate, narrower type than [com.fotoxplorr.app.media.MediaAsset]) -- read
     *  it straight from MediaStore for this one row rather than growing that shared type's
     *  surface for a single, audio-conversion-only need. */
    private fun sourceRelativePath(source: AudioAsset): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH)
        return runCatching {
            appContext.contentResolver.query(source.contentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun exportMessage(t: Throwable): String {
        val e = t as? ExportException ?: return t.message ?: "The export failed."
        return when (e.errorCode) {
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            -> "This device cannot decode this audio file's format."
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            -> "This device cannot encode AAC audio."
            else -> e.message ?: "The export failed."
        }
    }

    private companion object {
        const val TARGET_MIME_TYPE = "audio/mp4"
        const val DEFAULT_BITRATE = 128_000
        const val PROGRESS_POLL_MS = 250L
    }
}

/** Mirrors `com.fotoxplorr.app.video.convertedName`'s exact shape, for an audio conversion. */
internal fun convertedAudioName(displayName: String, marker: String = "converted"): String {
    val trimmed = displayName.trim().ifBlank { "audio" }
    val dot = trimmed.lastIndexOf('.')
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    val base = stem.removeSuffix("-$marker")
    return "$base-$marker.m4a"
}
