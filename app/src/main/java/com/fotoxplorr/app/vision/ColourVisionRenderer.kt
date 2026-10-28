package com.fotoxplorr.app.vision

import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Android seam of the colour-vision viewer: applies the pure filters to a decoded [Bitmap] and
 * cuts one down to the small picture the pattern planner works on. Everything it computes comes from
 * [Simulation], [Daltonizer], [ColourIndex] and [HatchPlanner]; it only moves pixels in and out of
 * bitmaps, a band of rows at a time, and stops between bands when the coroutine is cancelled.
 *
 * The filter is the EXACT per-pixel path (sRGB decoded, matrix in linear light, encoded again), not
 * the colour-matrix export of [GpuFilter]. It never touches the source bitmap, and the new one is a
 * fresh `ARGB_8888` bitmap; the file the picture came from is not involved at all.
 */
internal object ColourVisionRenderer {
    /** The picture the viewer filters and reads names from: about a screen's worth, not the file's full size. */
    val VIEW_LIMITS = DecodeLimits(maxLongEdge = 2048, maxPixels = 3_000_000L)

    /** The long edge of the picture the pattern planner quantises. */
    const val INDEX_EDGE = 256

    /** Pixels converted between two checks for cancellation. */
    private const val BAND_PIXELS = 1 shl 18

    /** The seed of the colour index, fixed so a photo always gets the same patterns. */
    private const val INDEX_SEED = 7L

    /**
     * A new bitmap holding [src] seen through [key]. Throws a cancellation if the coroutine is
     * cancelled (the half-made bitmap is recycled first). [onProgress] gets 0..1 after each band.
     */
    suspend fun filter(src: Bitmap, key: FilterKey, onProgress: (Float) -> Unit = {}): Bitmap {
        val width = src.width
        val height = src.height
        val matrix = key.matrix()
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var finished = false
        try {
            val rows = max(1, BAND_PIXELS / max(1, width))
            val band = IntArray(rows * width)
            var y = 0
            while (y < height) {
                currentCoroutineContext().ensureActive()
                val n = min(rows, height - y)
                src.getPixels(band, 0, width, 0, y, width, n)
                for (i in 0 until n * width) band[i] = ColourSpace.applyLinear(matrix, band[i])
                out.setPixels(band, 0, width, 0, y, width, n)
                y += n
                onProgress(y.toFloat() / height)
            }
            finished = true
            return out
        } finally {
            if (!finished) out.recycle()
        }
    }

    /** All the pixels of [src] as packed colours, row-major. */
    fun pixels(src: Bitmap): IntArray {
        val out = IntArray(src.width * src.height)
        src.getPixels(out, 0, src.width, 0, 0, src.width, src.height)
        return out
    }

    /**
     * [src] cut to at most [ColourIndex.MAX_K] colours on a copy of at most [INDEX_EDGE] pixels on
     * the long side. Alpha is ignored by the quantiser, so a see-through photo's hidden colours count.
     */
    suspend fun index(src: Bitmap): ColourIndexImage {
        val longEdge = max(src.width, src.height)
        val small = if (longEdge <= INDEX_EDGE) {
            src
        } else {
            val factor = INDEX_EDGE.toDouble() / longEdge
            Bitmap.createScaledBitmap(
                src,
                max(1, (src.width * factor).roundToInt()),
                max(1, (src.height * factor).roundToInt()),
                true,
            )
        }
        try {
            currentCoroutineContext().ensureActive()
            return ColourIndex.quantize(pixels(small), small.width, small.height, ColourIndex.MAX_K, INDEX_SEED)
        } finally {
            if (small !== src) small.recycle()
        }
    }

    /** The pattern layer for [image] under [key]: the plan for its type and strength, drawn near screen size. */
    fun hatch(image: ColourIndexImage, key: FilterKey): HatchLayer =
        HatchOverlay.render(image, HatchPlanner.plan(image.centres, key.deficiency, key.severity))
}
