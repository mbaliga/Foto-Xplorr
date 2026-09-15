package com.fotoxplorr.app.moments

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Grabs one frame out of a video as a standalone JPEG the user can share like any photo.
 *
 * Same division of labour as `com.fotoxplorr.app.share.SharePreparer`: no decisions live here,
 * only I/O -- decode one frame, encode it, hand back a `content://` URI a share `Intent` can
 * grant read access to. The FileProvider authority and the "write into our own app-private cache,
 * then hand out a URI" shape both mirror that class exactly (see its KDoc); the export directory
 * is [MomentExportStorage] rather than SharePreparer's, because SharePreparer wipes its directory
 * at the start of every call, and a lingering "save this frame" share sheet must not have its
 * file deleted out from under it by an unrelated ordinary photo share happening at the same time
 * -- see [MomentExportStorage]'s KDoc for the full reasoning.
 *
 * Full resolution, unlike [FrameSampler]'s frames: that class decodes up to forty frames per
 * video just to compare their histograms, so it deliberately asks for tiny scaled-down bitmaps to
 * keep detection fast. This class decodes exactly ONE frame, on demand, because a person tapped
 * "save this frame" and is about to look at, share, or print it -- the cost/quality trade that
 * makes sense forty times over for silent background analysis is the wrong one to make once for
 * something about to fill someone's screen.
 */
class MomentFrameExporter(context: Context) {
    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.files"

    suspend fun exportFrame(asset: MediaAsset, positionMs: Long): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(asset.isVideo) { "${asset.displayName} is not a video" }
                currentCoroutineContext().ensureActive()

                val bitmap = decodeFrame(asset, positionMs)
                    ?: error("Could not decode a frame at ${positionMs}ms from ${asset.displayName}")
                try {
                    val directory = MomentExportStorage.prepare(appContext)
                    val target = File(directory, "${UUID.randomUUID()}.jpg")
                    try {
                        target.outputStream().buffered().use { out ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                                "Could not encode the frame"
                            }
                        }
                        FileProvider.getUriForFile(appContext, authority, target)
                    } catch (error: Throwable) {
                        target.delete()
                        throw error
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

    /**
     * `OPTION_CLOSEST`, matching [FrameSampler]: the cheaper `OPTION_CLOSEST_SYNC` only ever
     * returns an actual keyframe, and on a video with a long GOP the frame exported could
     * visibly differ from the one the user actually picked (by marker or by scrubbing). Decoding
     * forward from the nearest sync frame costs more per call, which is exactly why
     * [FrameSampler] bounds itself to forty calls per video -- this class makes exactly one, so
     * the accuracy is worth paying for here.
     */
    private fun decodeFrame(asset: MediaAsset, positionMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, asset.contentUri)
            retriever.getFrameAtTime(positionMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        /** Matches the quality bar `SharePreparer.JPEG_QUALITY` uses for an ordinary shared
         *  photo -- a frame someone chose to export deserves the same treatment as any other
         *  shared image, not a worse one. */
        const val JPEG_QUALITY = 92
    }
}
