package com.fotoxplorr.app.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ReelHost] over the Experimental host's [ExperimentEnv]: every pixel of a photo is read through
 * [ExperimentEnv.decode] (which is `decodeUpright` behind the [ExperimentEnv.mayExport] check, TRAPS #33
 * and #34), and photos come only from [ExperimentEnv.assets] (the browsable list).
 *
 * ## Videos
 * [ExperimentEnv.mayExport] is false for every video, by design (an experiment that exports pixels exports
 * photos). A clip still must not be read once its folder is locked, so [mayUseVideo] asks the same live
 * question the host asks for photos, about the same id: it is `mayExport` of a copy of the asset that says
 * it is an image. That reads the host's CURRENT browsable list (not the snapshot in [ExperimentEnv.assets])
 * and needs no change to the host, exactly as the animated-picture maker does; if the host's rule for photos
 * ever looks at more than the id, this needs the same change. The export library reads a clip itself (its
 * decoder, not ours), so for a clip the check can only be made before and after, never during.
 *
 * ## Files it writes
 * Photos as JPEG (quality [JPEG_QUALITY], the editor's default) and title cards as PNG, in the cache
 * directory the job gives. A photo has no EXIF in the copy (`Bitmap.compress` writes none), so no place or
 * camera detail is in what the encoder reads either.
 */
class ReelEnvHost(private val env: ExperimentEnv) : ReelHost {
    private val byId by lazy { env.assets.associateBy { it.id.value } }

    override fun asset(mediaId: Long): MediaAsset? = byId[mediaId]

    override fun mayUsePhoto(asset: MediaAsset): Boolean = env.mayExport(asset)

    override fun mayUseVideo(asset: MediaAsset): Boolean = asset.isVideo && env.mayExport(asset.copy(mimeType = AS_IMAGE))

    override suspend fun shapeOf(asset: MediaAsset): Float? = if (asset.isVideo) {
        withContext(Dispatchers.IO) { videoShape(asset) }
    } else {
        val decoded = decodeOrNull(asset, SHAPE_EDGE) ?: return null
        val bitmap = decoded.bitmap
        try {
            if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override suspend fun stagePhoto(asset: MediaAsset, spec: StageSpec, into: File): StagedPicture? {
        val motion = if (spec.fit == ReelFit.CONTAIN) ReelMotion.None else spec.motion
        // The upright shape from a tiny decode makes the size exact; the listed size may be the file's before its turn.
        val shape = if (motion == ReelMotion.None) null else shapeOf(asset)
        val edge = if (shape != null) ReelStillMath.stagedLongEdgeFor(motion, shape, spec.frame) else ReelStillMath.stagedLongEdge(motion, asset.width, asset.height, spec.frame)
        val decoded = decodeOrNull(asset, edge) ?: return null
        val source = decoded.bitmap
        var placed: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                val out = if (spec.fit == ReelFit.CONTAIN) containedOn(source, spec.frame).also { placed = it } else source
                writeImage(out, into, Bitmap.CompressFormat.JPEG)
                StagedPicture(into, out.width, out.height)
            }
        } finally {
            placed?.let { if (!it.isRecycled) it.recycle() }
            if (!source.isRecycled) source.recycle()
        }
    }

    override suspend fun stageCard(text: String, backgroundArgb: Int, frame: ReelFrame, into: File): StagedPicture =
        withContext(Dispatchers.IO) {
            val card = ReelTitleCard.render(text, backgroundArgb, frame.width, frame.height)
            try {
                writeImage(card, into, Bitmap.CompressFormat.PNG)
                StagedPicture(into, card.width, card.height)
            } finally {
                card.recycle()
            }
        }

    override suspend fun probeSoundtrack(uri: String): SoundtrackProbe = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(env.context, Uri.parse(uri), null)
            var probe: SoundtrackProbe = SoundtrackProbe.Unusable
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) == null) continue
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                probe = SoundtrackProbe.Usable(durationUs / 1000L)
                break
            }
            probe
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A file that vanished, a right that was withdrawn, a format the extractor does not know: all "cannot use".
            SoundtrackProbe.Unusable
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeOrNull(asset: MediaAsset, edge: Int) = try {
        env.decode(asset, DecodeLimits(maxLongEdge = edge, maxPixels = edge.toLong() * edge))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A file that vanished or cannot be opened throws from the decoder; to the job that is "cannot be read".
        null
    }

    private fun videoShape(asset: MediaAsset): Float? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(env.context, asset.contentUri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val turn = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) null else if (turn % 180 != 0) h.toFloat() / w else w.toFloat() / h
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** [source] placed whole in the middle of a black bitmap of [frame]'s shape, at the largest size that fits. */
    private fun containedOn(source: Bitmap, frame: ReelFrame): Bitmap {
        val canvasBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(frame.width.toFloat() / source.width, frame.height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = (frame.width - w) / 2f
        val top = (frame.height - h) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return canvasBitmap
    }

    private fun writeImage(bitmap: Bitmap, into: File, format: Bitmap.CompressFormat) {
        FileOutputStream(into).buffered().use { out ->
            if (!bitmap.compress(format, JPEG_QUALITY, out)) throw IOException("Could not write a picture for the reel")
        }
    }

    companion object {
        /** The editor's own default JPEG quality for a new file. */
        const val JPEG_QUALITY = 92

        /** A tiny decode is enough to learn a photo's upright shape. */
        private const val SHAPE_EDGE = 96
        private const val AS_IMAGE = "image/jpeg"
    }
}
