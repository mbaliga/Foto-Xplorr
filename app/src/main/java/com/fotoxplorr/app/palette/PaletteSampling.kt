package com.fotoxplorr.app.palette

import android.content.Context
import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.media.targetSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one way a photo becomes a palette, so the indexer and the viewer agree on the answer.
 *
 * Everything is sampled at [SAMPLE_DIMENSION] on the long edge before it reaches
 * [PaletteExtractor]: a quantiser meant to produce five colours gets the same five from a
 * 100-pixel-wide thumbnail as from the 48-megapixel original, and the thumbnail costs microseconds
 * to walk where the original would cost seconds. The indexer already holds a 640px analysis
 * bitmap and hands it to [fromBitmap], which downscales once more; the palette-only backfill and
 * the viewer decode straight to the sample size with [read]. Both land on the same pixel budget, so
 * a swatch shown in the details room and the one stored for search come from the same arithmetic.
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
     * colours; an EMPTY list for a photo that decoded but had nothing visible (or for a video,
     * whose frames this never decodes -- the details room says the same); and NULL when the file
     * could not be decoded at all, which is a failure to record and retry a bounded number of
     * times rather than a fact about the photo to store.
     */
    suspend fun read(
        context: Context,
        asset: MediaAsset,
        maxColors: Int = PaletteExtractor.DEFAULT_MAX_COLORS,
    ): List<PaletteSwatch>? {
        if (asset.isVideo) return emptyList()
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
}
