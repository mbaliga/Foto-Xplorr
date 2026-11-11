package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException

/** What a preview was made from; it is shown only while the screen still matches all of it. */
internal data class PreviewKey(val assetId: Long, val colours: List<Int>, val settings: RecolourSettings)

/** How making a preview ended. */
internal sealed interface PreviewResult {
    class Ready(val bitmap: Bitmap) : PreviewResult

    class Failed(val message: String) : PreviewResult
}

/**
 * A small recoloured picture of a photo, so the person sees what a copy will look like before one is saved. It is
 * the same maths as [RecolourJob] ([PaletteRecolour] over [RecolourJob.recolourBitmap]) on a decode of about
 * [EDGE] px, so the look matches the saved copy; the saved copy's size and its sharpness are not shown.
 *
 * Reads the photo only through the host and asks [PaletteHost.mayExport] before the read and again before the
 * picture is handed back, so a photo locked while the preview ran is refused and never left on screen. Writes no
 * file. The caller owns the returned bitmap.
 */
internal object RecolourPreview {
    const val EDGE = 480

    private val LIMITS = DecodeLimits(maxLongEdge = EDGE, maxPixels = EDGE.toLong() * EDGE)

    suspend fun make(asset: MediaAsset, colours: List<Int>, settings: RecolourSettings, host: PaletteHost): PreviewResult {
        if (asset.isVideo || !host.mayExport(asset)) return PreviewResult.Failed(PaletteBuilderCopy.COPY_PROTECTED)
        if (colours.size < Palette.MIN_COLORS) return PreviewResult.Failed(PaletteBuilderCopy.COPY_FAILED)
        return try {
            val decoded = host.decode(asset, LIMITS) ?: return PreviewResult.Failed(PaletteBuilderCopy.COPY_UNREADABLE)
            val painted = try {
                RecolourJob.recolourBitmap(decoded.bitmap, PaletteRecolour(colours, amount = settings.amount, keepLightness = settings.keepLightness))
            } finally {
                decoded.bitmap.recycle()
            }
            if (!host.mayExport(asset)) {
                painted.recycle()
                PreviewResult.Failed(PaletteBuilderCopy.COPY_PROTECTED)
            } else {
                PreviewResult.Ready(painted)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            PreviewResult.Failed(PaletteBuilderCopy.COPY_OUT_OF_MEMORY)
        } catch (e: Exception) {
            PreviewResult.Failed(PaletteBuilderCopy.COPY_FAILED)
        }
    }
}
