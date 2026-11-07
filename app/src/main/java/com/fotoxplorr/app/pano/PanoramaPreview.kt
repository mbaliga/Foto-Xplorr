package com.fotoxplorr.app.pano

import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * The preview: the panorama job run as it would be for a save, on a canvas of [PanoramaPreviewRun.PIXELS]
 * pixels and into memory instead of a file. Nothing is written anywhere and no library row is made.
 *
 * It is the SAME job, not a lookalike: the same small reads, the same alignment, layout, exposure and
 * compositor, the same refusals and sentences. So a preview that joins means the save will find the same
 * positions, and a photo the preview cannot join is the photo the save would stop on. What differs is only
 * the size of the canvas the photos are drawn on, which is why the job's two reads of every photo (once
 * small to align, once at the canvas size to draw) make this cost mostly the alignment.
 */
internal class PanoramaPreview(
    /** The cropped, joined picture at preview size. Not recycled by anyone; the garbage collector has it. */
    val bitmap: Bitmap,
    /** About how big a save would be, or null when it could not be worked out. */
    val exportSize: PanoSize?,
    /** The job's own remarks (a guessed lens, drifting photos), as sentences. */
    val notes: List<String>,
)

/** How a preview ended, unless it was cancelled. */
internal sealed interface PreviewResult {
    class Ready(val preview: PanoramaPreview) : PreviewResult

    class Failed(val problem: PanoramaProblem) : PreviewResult
}

internal object PanoramaPreviewRun {
    /** The preview canvas budget: enough to judge the seams on a phone screen, small enough to take a moment. */
    const val PIXELS = 700_000L

    /**
     * Run the job over [assets] (in the order the person arranged them) through [host], reporting 0..1 on
     * [onProgress], and return the joined picture or the reason it could not be made. Throws only
     * `CancellationException`; a cancel recycles the half-made picture.
     */
    suspend fun run(assets: List<MediaAsset>, host: PanoramaHost, onProgress: (Float) -> Unit = {}): PreviewResult {
        val noted = SizeNotingHost(host)
        val sink = PreviewSink()
        var handedOver = false
        try {
            return when (val outcome = PanoramaJob.run(PanoramaRequest(assets, PIXELS), noted, sink, onProgress)) {
                is PanoramaOutcome.Failed -> PreviewResult.Failed(PanoramaProblem.of(outcome.message))
                is PanoramaOutcome.Done -> {
                    val image = sink.image ?: return PreviewResult.Failed(PanoramaProblem.of(PanoCopy.FAILED))
                    val crop = sink.crop
                    val scale = noted.scaleOf(assets.first())
                    val size = if (crop != null && scale != null) {
                        PanoramaEstimate.exportSize(sink.canvasWidth, sink.canvasHeight, crop.width, crop.height, scale)
                    } else {
                        null
                    }
                    // "Saved smaller" would say something about a file; this is not one.
                    val notes = outcome.notes.filter { it != PanoCopy.SAVED_SMALLER }
                    handedOver = true
                    PreviewResult.Ready(PanoramaPreview(image, size, notes))
                }
            }
        } finally {
            if (!handedOver) sink.discard()
        }
    }
}

/**
 * A [PanoramaSink] that keeps the cropped canvas as a Bitmap for the screen to draw. It looks at
 * `stillAllowed` before keeping anything, like the real sink, so a photo protected while the preview ran
 * is never left on screen.
 */
internal class PreviewSink : PanoramaSink {
    @Volatile var canvasWidth: Int = 0
        private set

    @Volatile var canvasHeight: Int = 0
        private set

    @Volatile var image: Bitmap? = null
        private set

    @Volatile var crop: AutoCrop.CropRect? = null
        private set

    override fun newCanvas(width: Int, height: Int): PanoramaCanvas {
        // A retry on a smaller rung starts again: nothing of the larger try is kept.
        discard()
        val canvas = BitmapPanoCanvas(width, height)
        canvasWidth = width
        canvasHeight = height
        return canvas
    }

    override suspend fun publish(
        canvas: PanoramaCanvas,
        crop: AutoCrop.CropRect,
        dateTakenMillis: Long,
        sourceName: String,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): String {
        val bitmap = (canvas as? BitmapPanoCanvas)?.bitmap ?: throw IllegalArgumentException("not a bitmap canvas")
        if (!stillAllowed()) throw PanoramaProtectedException()
        val whole = crop.left == 0 && crop.top == 0 && crop.width == bitmap.width && crop.height == bitmap.height
        // The job releases its canvas, so what is kept must be a copy of it.
        image = if (whole) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        }
        this.crop = crop
        onProgress(1f)
        return PREVIEW_URI
    }

    fun discard() {
        image?.let { if (!it.isRecycled) it.recycle() }
        image = null
        crop = null
    }

    private companion object {
        /** Not a real uri: nothing was saved. */
        const val PREVIEW_URI = "preview:"
    }
}

/**
 * A [PanoramaHost] that notes, for every photo, the upright size of the original and the longest edge the
 * LAST decode asked for. The job's second read of a photo asks for exactly the size the canvas needs, so
 * that edge over the original's is the scale the canvas was drawn at, which is what turns a preview into a
 * size for the save ([PanoramaEstimate]). Everything else is the wrapped host's.
 */
internal class SizeNotingHost(private val inner: PanoramaHost) : PanoramaHost by inner {
    private class Seen(val longEdge: Int, val requested: Int)

    private val seen = ConcurrentHashMap<Long, Seen>()

    override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): PanoDecoded? {
        val decoded = inner.decode(asset, limits)
        if (decoded != null) {
            seen[asset.id.value] = Seen(max(decoded.sourceWidth, decoded.sourceHeight), limits.maxLongEdge)
        }
        return decoded
    }

    /** The scale [asset] was last decoded at, as a share of its own size (at most 1), or null if it never was. */
    fun scaleOf(asset: MediaAsset): Double? =
        seen[asset.id.value]?.let { (it.requested.toDouble() / it.longEdge).coerceAtMost(1.0) }
}
