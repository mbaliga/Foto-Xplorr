package com.fotoxplorr.app.composite

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.composite.EmbeddedRange
import com.fotoxplorr.core.formats.composite.MotionPhoto
import com.fotoxplorr.core.formats.composite.MotionPhotoStill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The embedded clip of a motion photo as a file a player can open: the MP4 byte range copied into
 * this app's cache (`cacheDir/motion-clips`), never next to the photo. The newest [KEEP] clips are
 * kept so paging back and forth does not copy again; a clip over [MAX_CLIP_BYTES] is refused.
 *
 * The copies are a convenience of one viewer session, never a record: the viewer [clear]s them when
 * it closes, and the first copy of each process clears what an earlier process left behind (it may
 * have been killed with the viewer open). A photo locked, hidden or trashed later therefore leaves
 * no readable clip behind once the viewer is gone.
 */
internal object MotionClipCache {
    const val KEEP = 6
    const val MAX_CLIP_BYTES = 256L shl 20
    private const val DIRECTORY = "motion-clips"

    @Volatile
    private var sweptThisProcess = false

    /** Deletes every cached clip. Cheap (at most [KEEP] files and a partial one); safe on any thread. */
    fun clear(context: Context) {
        sweptThisProcess = true
        File(context.cacheDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    /** The clip's cache file, copied on first use; null when it cannot be read in full. Blocking IO. */
    fun clipFile(context: Context, asset: MediaAsset, motion: MotionPhoto): File? {
        if (motion.video.length > MAX_CLIP_BYTES) return null
        if (!sweptThisProcess) clear(context)
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val name = clipName(asset.contentUriString, asset.dateModifiedSeconds, motion.video)
        val target = File(directory, name)
        if (target.isFile && target.length() == motion.video.length) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val partial = File(directory, "$name.part")
        val ok = runCatching {
            partial.outputStream().buffered().use { out ->
                CompositeProbe.copyRange(context, asset.contentUri, motion.video.offset, motion.video.length) { b, n -> out.write(b, 0, n) }
            }
        }.getOrDefault(false)
        if (!ok || !partial.renameTo(target)) {
            partial.delete()
            return null
        }
        prune(directory)
        return target
    }

    /** A stable file name for one clip of one version of one file. */
    internal fun clipName(uri: String, modified: Long, range: EmbeddedRange): String =
        "clip-" + "$uri@$modified#${range.offset}+${range.length}".hashCode().toUInt().toString(16) + ".mp4"

    private fun prune(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}

/** What saving part of a composite photo produced. */
internal sealed interface CompositeExportResult {
    data class Saved(val uri: Uri) : CompositeExportResult
    data object Refused : CompositeExportResult
    data object Failed : CompositeExportResult
}

/**
 * Saves the still or the clip of a motion photo as a NEW file in this app's own folders
 * (`Pictures/Fotoz/`, `Movies/Fotoz/`), the way the panorama writer does: a pending row, the bytes,
 * then the row made visible; any failure deletes the row. The original is never opened for writing.
 *
 * - The still is the primary image's bytes, unchanged except that the motion flags are turned off
 *   ([MotionPhotoStill.disarm]), so it keeps its Exif and XMP and is no longer called a motion photo.
 * - The clip is the embedded MP4's bytes, unchanged. Its sound track, if it has one, is kept in the
 *   file: the app never plays it (no audio features), but deleting it would be an edit of the
 *   user's video nobody asked for.
 *
 * [mayExport] is asked right before the row is made (TRAPS #33): a photo marked sensitive or put in
 * a locked folder meanwhile is refused. Nothing here has run on a device.
 */
internal class CompositeExporter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun exportStill(asset: MediaAsset, motion: MotionPhoto, mayExport: () -> Boolean): CompositeExportResult =
        export(asset, motion.still, video = false, mayExport)

    suspend fun exportVideo(asset: MediaAsset, motion: MotionPhoto, mayExport: () -> Boolean): CompositeExportResult =
        export(asset, motion.video, video = true, mayExport)

    private suspend fun export(
        asset: MediaAsset,
        range: EmbeddedRange,
        video: Boolean,
        mayExport: () -> Boolean,
    ): CompositeExportResult = withContext(Dispatchers.IO) {
        if (!mayExport()) return@withContext CompositeExportResult.Refused
        // The primary of a motion photo may be a JPEG or a HEIF: the new file is named and typed
        // after what its bytes are, never assumed to be a JPEG. The head (where the XMP is) is read
        // whole before any row is made, patched, written, then the rest streams through.
        val headLength = if (video) 0L else minOf(range.length, HEAD_BYTES)
        val head = if (headLength == 0L) {
            ByteArray(0)
        } else {
            val buffer = java.io.ByteArrayOutputStream(headLength.toInt())
            val read = CompositeProbe.copyRange(appContext, asset.contentUri, range.offset, headLength) { b, n -> buffer.write(b, 0, n) }
            if (!read) return@withContext CompositeExportResult.Failed
            buffer.toByteArray()
        }
        val format = if (video) ExportFormat.MP4 else stillFormat(head) ?: return@withContext CompositeExportResult.Failed
        if (!mayExport()) return@withContext CompositeExportResult.Refused
        val resolver = appContext.contentResolver
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, exportName(asset.displayName, video, format.extension))
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            if (asset.dateTakenMillis > 0) put(MediaStore.MediaColumns.DATE_TAKEN, asset.dateTakenMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Fotoz/" else "Pictures/Fotoz/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext CompositeExportResult.Failed
        try {
            val copied = resolver.openOutputStream(uri)?.use { out ->
                // A clip streams through untouched; a still's head goes out with its flags off.
                if (head.isNotEmpty()) {
                    MotionPhotoStill.disarm(head)
                    out.write(head)
                }
                (range.length == headLength ||
                    CompositeProbe.copyRange(appContext, asset.contentUri, range.offset + headLength, range.length - headLength) { b, n ->
                        out.write(b, 0, n)
                    })
            } ?: false
            if (!copied) throw IOException("the range could not be read in full")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val shown = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, shown, null, null) < 1) throw IOException("the new file could not be finished")
            }
            CompositeExportResult.Saved(uri)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            if (t is kotlinx.coroutines.CancellationException) throw t
            CompositeExportResult.Failed
        }
    }

    companion object {
        private const val HEAD_BYTES = 512L * 1024

        /** `PXL_1.MP.jpg` -> `PXL_1.MP-still.jpg` / `PXL_1.MP-motion.mp4`; MediaStore adds a number on a clash. */
        internal fun exportName(displayName: String, video: Boolean, extension: String = if (video) "mp4" else "jpg"): String {
            val dot = displayName.lastIndexOf('.')
            val stem = (if (dot > 0) displayName.substring(0, dot) else displayName)
                .replace('/', '_').replace('\\', '_').ifBlank { "photo" }.take(80)
            return if (video) "$stem-motion.$extension" else "$stem-still.$extension"
        }

        /**
         * What a still's first bytes say it is: a JPEG (`FF D8`) or an ISO-BMFF HEIF (`ftyp` with a
         * HEIF brand). Null for anything else, which is then not exported rather than mislabelled.
         */
        internal fun stillFormat(head: ByteArray): ExportFormat? {
            if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return ExportFormat.JPEG
            if (head.size < 12 || String(head, 4, 4, Charsets.US_ASCII) != "ftyp") return null
            return when (String(head, 8, 4, Charsets.US_ASCII).lowercase()) {
                "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs" -> ExportFormat.HEIC
                "mif1", "msf1" -> ExportFormat.HEIF
                else -> null
            }
        }
    }

    internal enum class ExportFormat(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpg"),
        HEIC("image/heic", "heic"),
        HEIF("image/heif", "heif"),
        MP4("video/mp4", "mp4"),
    }
}
