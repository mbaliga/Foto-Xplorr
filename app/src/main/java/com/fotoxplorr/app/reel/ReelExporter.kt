package com.fotoxplorr.app.reel

import android.content.Context
import android.graphics.Matrix
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
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
import com.fotoxplorr.app.video.deviceSupportsH264AacEncoding
import com.fotoxplorr.app.video.hdrModeFor
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A picture the encoder reads for a still or a title card: a complete image file and its pixel size. */
class StagedPicture(val file: File, val width: Int, val height: Int)

/**
 * Where the encoder finds what each item draws, by item id: [pictures] for stills and cards (files in this
 * app's cache that the job made), [clipUris] for clips (the library's own content address; the encoder reads
 * the original, which is never written to).
 */
class ReelStaged(val pictures: Map<String, StagedPicture>, val clipUris: Map<String, String>)

/** An export that failed; [kind] says what the job does about it. */
class ReelEncodeException(val kind: ReelFailureKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The part of the export that needs the phone's codecs. The real one is [TransformerReelEncoder]; the job's
 * tests use a fake that records the plan it was given and writes a file, or throws a chosen failure.
 */
interface ReelEncoder {
    /** Whether this phone can write H.264 video and AAC sound at all. */
    fun canEncode(): Boolean

    /**
     * Render [plan] into [output], reading pictures and clips from [staged]; [onProgress] gets 0..1. Throws
     * [ReelEncodeException] for a failure the job can sort, [OutOfMemoryError] when memory ran out, and
     * [CancellationException] when the coroutine is cancelled (the export is stopped and [output] is left
     * for the caller to delete).
     */
    suspend fun encode(plan: ReelRenderPlan, staged: ReelStaged, output: File, onProgress: (Float) -> Unit)
}

/**
 * A slow move across a still, as a per-frame matrix: the part of the picture [ReelStillMath.cropAt] chooses,
 * stretched to fill the picture's frame (a following stretch to the video's size undoes the shape change; the
 * window already has the video's shape in pixels). The move runs from the first frame this instance sees to
 * the last, so it does not matter whether the pipeline counts a frame's time from the start of the item or of
 * the reel. One instance belongs to one item; it remembers its first frame.
 */
@UnstableApi
class ReelKenBurns(
    private val motion: ReelMotion,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val frameRatio: Float,
    /** The time from the first frame to the last, in microseconds (at least 1). */
    private val spanUs: Long,
) : MatrixTransformation {
    private var firstUs = Long.MIN_VALUE
    private val matrix = Matrix()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val progress = ((presentationTimeUs - firstUs).toDouble() / spanUs.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
        val w = ReelStillMath.cropAt(motion, progress, imageWidth, imageHeight, frameRatio)
        matrix.reset()
        // The same two steps as the library's own Crop: move the window's centre to the middle, then enlarge it to fill the frame.
        matrix.postTranslate(-w.centerX, -w.centerY)
        matrix.postScale(1f / w.halfWidth, 1f / w.halfHeight)
        return matrix
    }
}

/**
 * The compositor's settings: the output is always the plan's frame (the first input may be the 1x1 blank
 * picture the export library puts in an empty stretch), and each input is drawn with the opacity and
 * sideways shift [ReelLayers.stateAt] gives for its layer at that time. The input id is the sequence index,
 * which is the layer (the export library registers the first sequence with video as the one that drives the
 * output and draws it on top, and numbers the inputs by sequence).
 */
@UnstableApi
class ReelCompositorSettings(private val plan: ReelRenderPlan) : VideoCompositorSettings {
    override fun getOutputSize(inputSizes: List<Size>): Size = Size(plan.frame.width, plan.frame.height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val state = ReelLayers.stateAt(plan, inputId, presentationTimeUs)
        // A sideways push of up to a whole frame: the library only takes anchors between -1 and 1, and the picture
        // lands at (background anchor - picture anchor), so half the push goes on each.
        val half = (state.shiftX / 2f).coerceIn(-1f, 1f)
        return StaticOverlaySettings.Builder()
            .setAlphaScale(state.alpha)
            .setBackgroundFrameAnchor(half, 0f)
            .setOverlayFrameAnchor(-half, 0f)
            .build()
    }
}

/** Turns a [ReelRenderPlan] into the export library's Composition. Builds objects only; nothing is decoded or encoded here. */
@UnstableApi
object ReelComposition {

    fun build(plan: ReelRenderPlan, staged: ReelStaged): Composition {
        val trackTypes = if (plan.keepClipSound) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
        val sequences = ArrayList<EditedMediaItemSequence>()
        for (layer in 0 until plan.layerCount) {
            val builder = EditedMediaItemSequence.Builder(trackTypes)
            for (item in plan.itemsOn(layer)) {
                if (item.gapBeforeUs > 0L) builder.addGap(item.gapBeforeUs)
                builder.addItem(editedItem(item, plan, staged))
            }
            sequences += builder.build()
        }
        plan.audio?.let { sequences += soundtrackSequence(it) }
        val composition = Composition.Builder(sequences).setHdrMode(hdrModeFor())
        if (plan.isLayered) composition.setVideoCompositorSettings(ReelCompositorSettings(plan))
        return composition.build()
    }

    private fun editedItem(item: PlannedItem, plan: ReelRenderPlan, staged: ReelStaged): EditedMediaItem = when (item.kind) {
        PlannedKind.CLIP -> {
            val uri = staged.clipUris[item.id] ?: error("no clip address for item ${item.id}")
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(item.inUs / 1000L)
                        .setEndPositionMs(item.outUs / 1000L)
                        .build(),
                )
                .build()
            val layout = if (plan.fit == ReelFit.COVER) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(!plan.keepClipSound)
                .setEffects(Effects(emptyList(), listOf<Effect>(Presentation.createForWidthAndHeight(plan.frame.width, plan.frame.height, layout))))
                .build()
        }
        PlannedKind.STILL, PlannedKind.CARD -> {
            val picture = staged.pictures[item.id] ?: error("no picture for item ${item.id}")
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(picture.file))
                .setImageDurationMs(item.durationUs / 1000L)
                .build()
            val effects = ArrayList<Effect>()
            if (item.kind == PlannedKind.STILL && plan.fit == ReelFit.COVER) {
                val frameUs = 1_000_000L / plan.fps
                // The window already has the video's shape, so the stretch below only scales.
                effects += ReelKenBurns(item.motion, picture.width, picture.height, plan.frame.ratio, item.durationUs - frameUs)
            }
            effects += Presentation.createForWidthAndHeight(plan.frame.width, plan.frame.height, Presentation.LAYOUT_STRETCH_TO_FIT)
            EditedMediaItem.Builder(mediaItem)
                .setFrameRate(plan.fps)
                .setEffects(Effects(emptyList(), effects))
                .build()
        }
    }

    private fun soundtrackSequence(audio: ReelAudioPlan): EditedMediaItemSequence {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(audio.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(audio.startUs / 1000L)
                    .setEndPositionMs(audio.endUs / 1000L)
                    .build(),
            )
            .build()
        val item = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(ReelFadeProcessor(audio)), emptyList()))
            .build()
        return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(item).build()
    }
}

/**
 * [ReelEncoder] over Media3 Transformer, set up the way [com.fotoxplorr.app.video.VideoExporter] sets it up
 * (create, start, poll and cancel on the main thread; H.264 and AAC; an encoder allowed to fall back to a
 * nearby setting; HDR sources tone-mapped to SDR, because the compositor takes SDR only). The differences
 * are the Composition ([ReelComposition]) and the muxer ([PlaceFreeMuxerFactory]), which drops every
 * metadata entry so no place of a source reaches the reel. Nothing here has run on a device.
 */
@UnstableApi
class TransformerReelEncoder(context: Context) : ReelEncoder {
    private val appContext = context.applicationContext

    override fun canEncode(): Boolean = runCatching { deviceSupportsH264AacEncoding() }.getOrDefault(false)

    override suspend fun encode(plan: ReelRenderPlan, staged: ReelStaged, output: File, onProgress: (Float) -> Unit) {
        try {
            withContext(Dispatchers.Main.immediate) { run(plan, staged, output, onProgress) }
        } catch (e: ExportException) {
            throw ReelEncodeException(ReelFailures.classify(e, plan.hasAudio), e.message ?: "The export failed.", e)
        }
    }

    private suspend fun run(plan: ReelRenderPlan, staged: ReelStaged, output: File, onProgress: (Float) -> Unit): Unit = coroutineScope {
        val composition = ReelComposition.build(plan, staged)
        val transformer = buildTransformer(plan)
        // Transformer reports progress as a poll, not a callback; a sibling job reads it on the same (main) thread.
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
                continuation.invokeOnCancellation { transformer.cancel() }
            }
        } finally {
            poller.cancel()
        }
    }

    private fun buildTransformer(plan: ReelRenderPlan): Transformer {
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.attempt.bitrate).build())
            // A bitrate is a request; letting the factory fall back to a nearby supported setting beats failing a rung for it.
            .setEnableFallback(true)
            .build()
        return Transformer.Builder(appContext)
            .setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setMuxerFactory(PlaceFreeMuxerFactory())
            .build()
    }

    private companion object {
        const val PROGRESS_POLL_MS = 250L
    }
}
