package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What the palette builder reads photos through. The experiment's is [ExperimentPaletteHost] (over
 * [ExperimentEnv], so the browsable list and the live "may this still be exported" check apply, TRAPS #33);
 * tests use a fake.
 */
internal interface PaletteHost {
    /** Whether [asset]'s pixels may be read and copied right now. Asked before a read and again before anything is published. */
    fun mayExport(asset: MediaAsset): Boolean

    /** [asset] upright and within [limits] (TRAPS #34), or null when [mayExport] says no or the file cannot be read. */
    suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap?
}

/** [PaletteHost] over the Experimental host's environment: the only way this package reaches a photo. */
internal class ExperimentPaletteHost(private val env: ExperimentEnv) : PaletteHost {
    override fun mayExport(asset: MediaAsset): Boolean = env.mayExport(asset)

    override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? = env.decode(asset, limits)
}

/** How reading colours from photos ended. */
internal sealed interface PhotoPaletteResult {
    /** [palette] was made from [sources] (their ids, in the order given). */
    class Ready(val palette: Palette, val sources: List<Long>) : PhotoPaletteResult

    class Failed(val message: String) : PhotoPaletteResult
}

/**
 * Turns one to six photos into a [Palette]: each is decoded small (about [SAMPLE_EDGE] px on its long edge,
 * through the host), its pixels go to [PaletteMerge.extractMerged], and the merged swatches become the palette.
 * One photo is just that photo's colours with near-duplicates folded; several are pooled, weighted equally.
 *
 * - **Protected photos.** Each photo is asked [PaletteHost.mayExport] before it is read and again after the
 *   last one, because a long read can outlive the reason it was allowed. The answer is a sentence, never an
 *   exception.
 * - **No names.** The palette gets the default name, not a photo's file name, so nothing from a photo's name
 *   reaches an export.
 * - **Cancel.** Stops between photos and rethrows the cancellation; nothing is left behind (no file is written).
 *
 * Which colours a real photograph gives, and whether the merge threshold is right on real sets, is unchecked.
 */
internal object PhotoPalettes {
    const val SAMPLE_EDGE = 160
    const val COLOURS_ONE_PHOTO = 6
    const val COLOURS_SEVERAL = 8

    private val LIMITS = DecodeLimits(maxLongEdge = SAMPLE_EDGE, maxPixels = SAMPLE_EDGE.toLong() * SAMPLE_EDGE)

    suspend fun build(assets: List<MediaAsset>, host: PaletteHost, onProgress: (Float) -> Unit = {}): PhotoPaletteResult {
        if (assets.isEmpty() || assets.size > PaletteBuilderCopy.MAX_PHOTOS) return PhotoPaletteResult.Failed(PaletteBuilderCopy.READ_FAILED)
        if (assets.any { it.isVideo || !host.mayExport(it) }) return PhotoPaletteResult.Failed(PaletteBuilderCopy.PHOTO_PROTECTED)
        try {
            val photos = ArrayList<WeightedPixels>(assets.size)
            assets.forEachIndexed { index, asset ->
                currentCoroutineContext().ensureActive()
                onProgress(index.toFloat() / assets.size)
                val decoded = host.decode(asset, LIMITS) ?: return PhotoPaletteResult.Failed(PaletteBuilderCopy.PHOTO_UNREADABLE)
                try {
                    photos += WeightedPixels(pixelsOf(decoded.bitmap))
                } finally {
                    decoded.bitmap.recycle()
                }
            }
            currentCoroutineContext().ensureActive()
            // Ask once more before the colours are used: a photo locked during the read is refused.
            if (assets.any { !host.mayExport(it) }) return PhotoPaletteResult.Failed(PaletteBuilderCopy.PHOTO_PROTECTED)
            val wanted = if (assets.size == 1) COLOURS_ONE_PHOTO else COLOURS_SEVERAL
            val swatches = PaletteMerge.extractMerged(photos, wanted)
            val palette = PaletteMerge.toPalette(PaletteBuilderCopy.DEFAULT_NAME, swatches, wanted)
                ?: return PhotoPaletteResult.Failed(PaletteBuilderCopy.TOO_FEW_COLOURS)
            onProgress(1f)
            return PhotoPaletteResult.Ready(palette, assets.map { it.id.value })
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            return PhotoPaletteResult.Failed(PaletteBuilderCopy.READ_FAILED)
        } catch (e: Exception) {
            return PhotoPaletteResult.Failed(PaletteBuilderCopy.READ_FAILED)
        }
    }

    /** The bitmap's pixels as packed ARGB, read through a software copy when it is a hardware one (which cannot be read). */
    internal fun pixelsOf(bitmap: Bitmap): IntArray {
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        try {
            val out = IntArray(readable.width * readable.height)
            readable.getPixels(out, 0, readable.width, 0, 0, readable.width, readable.height)
            return out
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }
}
