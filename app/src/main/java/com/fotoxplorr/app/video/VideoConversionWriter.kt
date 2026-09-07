package com.fotoxplorr.app.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inserts a new MediaStore video row and hands [VideoTranscoder] the resulting file descriptor to
 * write into — the video counterpart to [com.fotoxplorr.app.editor.EditedCopyWriter]'s MediaStore
 * dance for photos, shaped around [android.media.MediaMuxer]'s own file-descriptor API rather than
 * an `OutputStream`.
 *
 * Always writes a NEW file beside the source, exactly like [EditedCopyWriter]'s photo Save (never
 * Save As's overwrite counterpart): a video conversion changes the codec and container, which —
 * same reasoning as [overwriteFormatFor] for photos — cannot be done in place without the file's
 * own extension becoming a lie about its contents.
 */
class VideoConversionWriter(context: Context) {
    private val appContext = context.applicationContext
    private val transcoder = VideoTranscoder(appContext)

    suspend fun convertToH264Mp4(source: MediaAsset): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val name = convertedName(source.displayName)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, TARGET_MIME_TYPE)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1_000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Alongside the source rather than in a folder of our own — same reasoning as
                    // EditedCopyWriter's identical choice for edited photos: a conversion is a
                    // version of this video, not a new thing living somewhere else.
                    source.relativePath?.takeIf { it.isNotBlank() }?.let {
                        put(MediaStore.Video.Media.RELATIVE_PATH, it)
                    }
                    // Keeps the half-written file out of every other gallery on the device until
                    // the transcode actually finishes — see EditedCopyWriter's identical use for
                    // why this matters even more here, given how much longer a video encode runs.
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Android would not create a new video file")

            try {
                // "rw", not "w": MediaMuxer needs a genuinely seekable descriptor, since it comes
                // back and rewrites the container's index once every sample has been written
                // rather than streaming a self-contained format start to finish.
                resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                    transcoder.transcode(source, descriptor.fileDescriptor).getOrThrow()
                } ?: error("Could not open the new file for writing")
            } catch (t: Throwable) {
                // A half-written or invalid row left behind is worse than nothing: it would show
                // up as a broken video in every other gallery on the device.
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        }
    }

    private companion object {
        const val TARGET_MIME_TYPE = "video/mp4"
    }
}

/**
 * The name a converted copy is saved under: the source's stem, then a marker, then `.mp4` —
 * mirrors [com.fotoxplorr.app.editor.editedName]'s exact shape (and the same re-conversion and
 * awkward-filename cases it handles) for the same reason: a converted video is a version of the
 * source, not a new thing, and shares the naming convention every other "version of this file"
 * feature in this app already uses.
 */
internal fun convertedName(displayName: String, marker: String = "converted"): String {
    val trimmed = displayName.trim().ifBlank { "video" }
    val dot = trimmed.lastIndexOf('.')
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    val base = stem.removeSuffix("-$marker")
    return "$base-$marker.mp4"
}
