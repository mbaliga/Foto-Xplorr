package com.fotoxplorr.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.graphics.scale
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The bitmaps a widget update embeds. A launcher draws a widget in its own process, so every image
 * travels inside the update as a bitmap (`setImageViewBitmap`), decoded and downscaled here, never as
 * a uri the launcher would have to open.
 *
 * Photos go through [decodeUpright] (TRAPS #34: EXIF orientation, an exact bound, software pixels a
 * RemoteViews can parcel). Videos take one representative frame from [MediaMetadataRetriever], the
 * same API the key-moment sampler uses. A file that cannot be read gives null and the tile shows a
 * plain placeholder; it never fails the whole widget.
 *
 * One instance per update: decoded bitmaps are cached by (item, edge), so the same photo drawn in
 * the small, medium and large layouts is one bitmap, which the platform then counts once.
 */
internal class WidgetThumbnails(private val context: Context) {
    private val cache = HashMap<Pair<Long, Int>, Bitmap?>()

    suspend fun load(asset: MediaAsset, edgePx: Int): Bitmap? {
        val key = asset.id.value to edgePx
        if (cache.containsKey(key)) return cache[key]
        val decoded = runCatching { if (asset.isVideo) videoFrame(asset, edgePx) else photo(asset, edgePx) }
            .getOrNull()
        cache[key] = decoded
        return decoded
    }

    private suspend fun photo(asset: MediaAsset, edgePx: Int): Bitmap? =
        decodeUpright(
            context,
            asset.contentUri,
            // Bounded by pixel count, not long edge, so a tile that crops to fill keeps its short
            // edge: a 4:3 photo lands near edge x 0.87 edge, a panorama stays within the same bytes.
            DecodeLimits(maxLongEdge = edgePx * 3, maxPixels = edgePx.toLong() * edgePx),
        )?.bitmap

    private suspend fun videoFrame(asset: MediaAsset, edgePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, asset.contentUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edgePx, edgePx)
            } else {
                // getScaledFrameAtTime does not exist on API 26; scale the full frame down ourselves.
                retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val scale = edgePx.toFloat() / max(full.width, full.height).coerceAtLeast(1)
                    if (scale >= 1f) {
                        full
                    } else {
                        full.scale(
                            max(1, (full.width * scale).roundToInt()),
                            max(1, (full.height * scale).roundToInt()),
                        ).also { if (it !== full) full.recycle() }
                    }
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }
}
