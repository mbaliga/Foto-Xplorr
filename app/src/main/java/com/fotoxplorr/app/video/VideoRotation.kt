package com.fotoxplorr.app.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * The container-level rotation for [source], read via a throwaway [MediaMetadataRetriever] rather
 * than anything off [android.media.MediaExtractor]'s per-track `MediaFormat` -- rotation lives in
 * the container's `tkhd` display matrix, not in any one track's own format. Moved out of
 * `moments/ClipExporter.kt` (P0-05) so [com.fotoxplorr.app.video.StreamCopyRemuxer] can share it
 * rather than each caller reading this tag its own way.
 *
 * Any failure here (source unreadable, tag absent) just means "assume no rotation": an un-rotated
 * export is a cosmetic problem, not a reason to fail an otherwise-successful one.
 */
fun findRotationDegrees(context: Context, source: Uri): Int {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, source)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    } catch (error: Throwable) {
        0
    } finally {
        runCatching { retriever.release() }
    }
}
