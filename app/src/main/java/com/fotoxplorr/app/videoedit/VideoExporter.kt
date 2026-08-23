package com.fotoxplorr.app.videoedit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.fotoxplorr.app.media.MediaAsset
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** One export's observable state. */
sealed interface VideoExportState {
    /** [progressPercent] is null until Transformer can estimate. */
    data class Running(val progressPercent: Int?) : VideoExportState
    data class Done(val uri: Uri) : VideoExportState
    data class Failed(val message: String) : VideoExportState
}

/**
 * Runs a [VideoEditPlan] through Media3 Transformer and lands the result as a NEW video beside
 * the original — the same never-touch-the-source contract `EditedCopyWriter` enforces for
 * photos, for the same reason: a library video may be the only copy that exists.
 *
 * Mechanics: Transformer writes to a private cache file (it wants a seekable plain file, not a
 * `content://` stream), and only a COMPLETE export is copied into MediaStore — `IS_PENDING`
 * until every byte is across — then the cache file is deleted. A cancelled or failed export
 * therefore leaves nothing anywhere the user can see.
 *
 * Threading: Transformer requires create/start/cancel/poll on one Looper thread, so [export]
 * must be called from the main dispatcher (the editor screen's own scope); only the MediaStore
 * copy hops to IO.
 */
@UnstableApi
class VideoExporter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Exports [plan] applied to [source], reporting through [onState]. Suspends until done or
     * failed; cancelling the calling coroutine cancels the transcode and cleans up fully.
     */
    suspend fun export(
        source: MediaAsset,
        plan: VideoEditPlan,
        onState: (VideoExportState) -> Unit,
    ) {
        val cacheFile = File.createTempFile("fotoz-export-", ".mp4", appContext.cacheDir)
        try {
            onState(VideoExportState.Running(progressPercent = null))
            runTransformer(source, plan, cacheFile, onState)
            val uri = withContext(Dispatchers.IO) { publish(source, cacheFile) }
            onState(VideoExportState.Done(uri))
        } catch (e: ExportException) {
            onState(VideoExportState.Failed(exportMessage(e)))
        } finally {
            cacheFile.delete()
        }
    }

    private suspend fun runTransformer(
        source: MediaAsset,
        plan: VideoEditPlan,
        output: File,
        onState: (VideoExportState) -> Unit,
    ): Unit = coroutineScope {
        val transformer = Transformer.Builder(appContext).build()
        // Transformer exposes progress as a poll, not a callback. A sibling job ticks it on
        // this same (main) dispatcher — 4Hz of a lock-free read costs nothing perceptible.
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onState(VideoExportState.Running(holder.progress))
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
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(exportException))
                        }
                    }
                })
                transformer.start(editedItem(source, plan), output.absolutePath)
                continuation.invokeOnCancellation { transformer.cancel() }
            }
        } finally {
            poller.cancel()
        }
    }

    private fun editedItem(source: MediaAsset, plan: VideoEditPlan): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(source.contentUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(plan.trimStartMs)
                    .setEndPositionMs(plan.trimEndMs)
                    .build(),
            )
            .build()

        val videoEffects = buildList {
            if (plan.quarterTurns != 0 || plan.flipHorizontal) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setScale(if (plan.flipHorizontal) -1f else 1f, 1f)
                        // Media3 rotates counter-clockwise for positive degrees; the plan's
                        // quarter turns are clockwise, matching the photo recipe's convention.
                        .setRotationDegrees(((4 - plan.quarterTurns) % 4) * 90f)
                        .build(),
                )
            }
            plan.cropAspect
                ?.ndcCrop(source.width, source.height, plan.quarterTurns)
                ?.let { crop -> add(Crop(crop.left, crop.right, crop.bottom, crop.top)) }
        }

        return EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(plan.muted)
            .setSpeed(plan.speed)
            .setEffects(Effects(/* audioProcessors= */ emptyList(), videoEffects))
            .build()
    }

    /** Copies the finished bytes into MediaStore beside the original; see class doc. */
    private fun publish(source: MediaAsset, finished: File): Uri {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, editedName(source.displayName))
            put(MediaStore.Video.Media.MIME_TYPE, OUTPUT_MIME)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Beside the original, same as an edited photo: an edit is a version of the
                // video, and burying it in a folder of our own makes it feel lost.
                source.relativePath?.takeIf { it.isNotBlank() }?.let {
                    put(MediaStore.Video.Media.RELATIVE_PATH, it)
                }
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
            // Same rule as the photo writer: never leave a pending, empty row behind.
            resolver.delete(uri, null, null)
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

    private fun exportMessage(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        -> "This device cannot decode this video's format."
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        -> "This device cannot encode the edited video."
        else -> e.message ?: "The export failed."
    }

    private fun editedName(original: String): String {
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        return "$stem-edited.mp4"
    }

    private companion object {
        const val OUTPUT_MIME = "video/mp4"
        const val PROGRESS_POLL_MS = 250L
    }
}
