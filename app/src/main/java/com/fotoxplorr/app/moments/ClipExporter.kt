package com.fotoxplorr.app.moments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.video.RemuxResult
import com.fotoxplorr.app.video.findRotationDegrees
import com.fotoxplorr.app.video.remux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Cuts `[startMs, endMs)` out of a video into its own playable file, WITHOUT re-encoding.
 *
 * The actual stream copy -- track selection, the keyframe/GOP-safe seek, buffer sizing -- lives in
 * [com.fotoxplorr.app.video.remux] (P0-05: pulled out so a location-stripping video *share*, which
 * needs the identical no-re-encode copy over a whole video rather than a clip of it, does not
 * duplicate this logic). See that function's own doc for why it is a stream copy rather than a
 * re-encode, the keyframe/GOP constraint the seek calls exist for, and why tracks are copied one
 * at a time rather than interleaved by timestamp.
 */
class ClipExporter(context: Context) {
    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.files"

    suspend fun exportClip(asset: MediaAsset, startMs: Long, endMs: Long): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(asset.isVideo) { "${asset.displayName} is not a video" }
                require(endMs > startMs) { "Clip end ($endMs) must be after its start ($startMs)" }
                val start = startMs.coerceAtLeast(0L)
                val end = endMs.coerceAtLeast(start + 1)

                val directory = MomentExportStorage.prepare(appContext)
                // Named without an extension until remux() reports which container it actually
                // wrote -- almost always MP4 for a phone-recorded clip, matching this class's own
                // prior hardcoded ".mp4", but no longer assumed.
                var target = File(directory, "${UUID.randomUUID()}.tmp")
                try {
                    val rotation = findRotationDegrees(appContext, asset.contentUri)
                    val rangeUs = (start * 1_000L)..(end * 1_000L)
                    when (val result = remux(appContext, asset.contentUri, target, rangeUs, rotation)) {
                        is RemuxResult.Unsupported -> error(result.reason)
                        is RemuxResult.Remuxed -> {
                            val renamed = File(directory, "${target.nameWithoutExtension}.${result.container.extension}")
                            check(target.renameTo(renamed)) { "Could not finish exporting ${asset.displayName}" }
                            target = renamed
                        }
                    }
                    FileProvider.getUriForFile(appContext, authority, target)
                } catch (error: Throwable) {
                    // A half-written or invalid clip left in the export cache is worse than
                    // nothing: it would sit there as a file some later share picks up.
                    target.delete()
                    throw error
                }
            }
        }
}
