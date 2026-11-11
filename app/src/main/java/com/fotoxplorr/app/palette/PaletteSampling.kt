package com.fotoxplorr.app.palette

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.media.targetSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The one way a photo becomes a palette, so the indexer and the viewer agree on the answer.
 *
 * Everything is sampled at [SAMPLE_DIMENSION] on the long edge before it reaches
 * [PaletteExtractor]: a quantiser meant to produce five colours gets the same five from a
 * 100-pixel-wide thumbnail as from the 48-megapixel original, and the thumbnail costs microseconds
 * to walk where the original would cost seconds. The indexer already holds a 640px analysis
 * bitmap and hands it to [fromBitmap], which downscales once more; the palette-only backfill and
 * the viewer decode straight to the sample size with [read] (a video through one frame). Both land
 * on the same pixel budget, so a swatch shown in the details room and the one stored for search
 * come from the same arithmetic.
 */
object PaletteSampling {

    /**
     * Long-edge size of the pixel sample the quantiser sees. A segmented bar shows at most
     * [PaletteExtractor.DEFAULT_MAX_COLORS] colours; 100px is already several thousand samples for
     * them, and small enough that the extra work per indexed photo is a rounding error next to the
     * three detectors that already ran on it.
     */
    const val SAMPLE_DIMENSION = 100

    val DECODE_LIMITS = DecodeLimits(
        maxLongEdge = SAMPLE_DIMENSION,
        maxPixels = SAMPLE_DIMENSION.toLong() * SAMPLE_DIMENSION,
    )

    /**
     * The palette of [bitmap], sampled at no more than [SAMPLE_DIMENSION] on its long edge.
     *
     * Never quantises the bitmap at its own size: anything larger than the sample is scaled down
     * first (filtered, so colours average rather than alias), and the scaled copy is recycled
     * before returning. [bitmap] itself is left exactly as it was -- the caller still owns it.
     * Empty for a recycled or degenerate bitmap, or one with nothing visible in it.
     */
    fun fromBitmap(bitmap: Bitmap, maxColors: Int = PaletteExtractor.DEFAULT_MAX_COLORS): List<PaletteSwatch> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        // getPixels throws on a hardware bitmap; read through a software copy when handed one.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        try {
            val (width, height) = targetSize(readable.width, readable.height, DECODE_LIMITS)
            val sampleWidth = width.coerceAtLeast(1)
            val sampleHeight = height.coerceAtLeast(1)
            val sample = if (sampleWidth == readable.width && sampleHeight == readable.height) {
                readable
            } else {
                Bitmap.createScaledBitmap(readable, sampleWidth, sampleHeight, true)
            }
            try {
                val pixels = IntArray(sampleWidth * sampleHeight)
                sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
                return PaletteExtractor.extract(pixels, maxColors)
            } finally {
                if (sample !== readable) sample.recycle()
            }
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /**
     * Decodes [asset] at the sample size and extracts its palette, off the calling thread.
     *
     * Three outcomes, kept distinct because the backfill treats them differently: a list of
     * colours; an EMPTY list for a photo that decoded but had nothing visible; and NULL when the
     * file could not be decoded at all, which is a failure to record and retry a bounded number of
     * times rather than a fact about the photo to store. A video goes through [readVideo].
     */
    suspend fun read(
        context: Context,
        asset: MediaAsset,
        maxColors: Int = PaletteExtractor.DEFAULT_MAX_COLORS,
    ): List<PaletteSwatch>? {
        if (asset.isVideo) return readVideo(context, asset, maxColors)
        return withContext(Dispatchers.IO) {
            val decoded = decodeUpright(context, asset.contentUri, DECODE_LIMITS) ?: return@withContext null
            val bitmap = decoded.bitmap
            try {
                fromBitmap(bitmap, maxColors)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * [read] as the details room's four-way [PaletteState] (never [PaletteState.Loading]): a read
     * that threw is [PaletteState.Failed], not an empty palette. Cancellation is rethrown, not
     * swallowed into a failure (TRAPS 49).
     */
    suspend fun readState(
        context: Context,
        asset: MediaAsset,
        maxColors: Int = PaletteExtractor.DEFAULT_MAX_COLORS,
    ): PaletteState = try {
        PaletteState.of(read(context, asset, maxColors))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        PaletteState.Failed
    }

    /**
     * A video's palette, from one frame near the start: [MediaMetadataRetriever] hands back a
     * frame and [fromBitmap] does the rest, the same route the moments sampler takes -- no
     * BitmapFactory or ImageDecoder on a video (TRAPS 34). Same three outcomes as [read]; null
     * covers both a file the retriever cannot open and one that opened but gave no frame.
     *
     * The retriever is released in `finally` (`release()`, not `close()`, which is API 29+).
     */
    private suspend fun readVideo(context: Context, asset: MediaAsset, maxColors: Int): List<PaletteSwatch>? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, asset.contentUri)
                currentCoroutineContext().ensureActive()
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: asset.durationMillis
                val frame = videoFrame(retriever, videoFrameTimeUs(durationMs)) ?: return@withContext null
                try {
                    fromBitmap(frame, maxColors)
                } finally {
                    frame.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // setDataSource refuses a corrupt or unsupported container with a runtime exception.
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    /**
     * Where in a video the palette frame comes from: one second in, or the middle of a clip
     * shorter than two seconds. Not frame zero, which is often a fade-in from black, and never
     * past the end. In microseconds, what the retriever takes; an unknown duration reads frame zero.
     */
    internal fun videoFrameTimeUs(durationMs: Long): Long =
        if (durationMs <= 0L) 0L else minOf(FRAME_AT_MS, durationMs / 2L) * 1_000L

    /**
     * The nearest sync frame at [timeUs]. Square on purpose: the palette only needs proportions of
     * colour, which a stretched frame keeps; getScaledFrameAtTime does not exist below API 27, and
     * there the full frame is decoded once and [fromBitmap] shrinks it.
     */
    private fun videoFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                SAMPLE_DIMENSION,
                SAMPLE_DIMENSION,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

    private const val FRAME_AT_MS = 1_000L
}
