package com.fotoxplorr.app.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/**
 * Writes an edited photo out — as a new file beside the original by default, or in place when the
 * caller has already secured permission to replace it.
 *
 * The two paths are asymmetric on purpose. [save] can create a fresh MediaStore row for ANY
 * [OutputFormat] with no special permission, because inserting a new file this app owns needs
 * none. [overwrite] can only ever re-encode into the file's OWN existing format (see
 * [overwriteFormatFor]'s own doc for why) and requires the caller to already hold write access to
 * a file this app did not create — see [com.fotoxplorr.app.FotoXplorrActivity]'s three-tier
 * consent dance, the same one rename and metadata writes already use, reused rather than
 * reinvented for this fourth call site.
 */
class EditedCopyWriter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Encode [bitmap] as a new image next to [source], in [format] (defaulting to [source]'s own
     * format via [outputFormatFor]).
     *
     * @return the new file's content Uri.
     */
    suspend fun save(
        source: MediaAsset,
        bitmap: Bitmap,
        format: OutputFormat = outputFormatFor(source.mimeType),
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val name = editedName(source.displayName, format)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Alongside the original rather than in a folder of our own: an edit is a
                    // version of a photo, and burying it somewhere else makes it feel lost.
                    source.relativePath?.takeIf { it.isNotBlank() }?.let {
                        put(MediaStore.Images.Media.RELATIVE_PATH, it)
                    }
                    // IS_PENDING keeps the half-written file out of every other gallery on the
                    // device until the bytes are actually there. Without it a scanner can index a
                    // truncated JPEG and show the user a corrupt thumbnail of their own edit.
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Android would not create a new image file")

            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    val ok = bitmap.compress(format.toCompressFormat(), format.exportQuality(), stream)
                    check(ok) { "Could not encode the edited photo" }
                } ?: error("Could not open the new file for writing")
            } catch (t: Throwable) {
                // Do not leave a pending, empty row behind for the user to find later.
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        }
    }

    /**
     * Re-encodes [bitmap] directly into [source]'s own file, replacing its bytes in place.
     *
     * Requires the caller to already hold (or have just been granted) write access to [source]'s
     * Uri — this function makes no permission request of its own, exactly like
     * [com.fotoxplorr.app.metadata.MetadataWriter.write] makes none for the same reason: only an
     * `Activity` can launch the system consent screen a per-file grant needs.
     *
     * Opened as `"rwt"` — read-write-**truncate** — so a smaller re-encode does not leave a tail
     * of the previous file's bytes behind it, which a plain `"rw"` open would.
     */
    suspend fun overwrite(source: MediaAsset, bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val format = overwriteFormatFor(source.mimeType)
                ?: error("${source.mimeType} cannot be replaced in place")
            appContext.contentResolver.openFileDescriptor(source.contentUri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { stream ->
                    val ok = bitmap.compress(format.toCompressFormat(), format.exportQuality(), stream)
                    check(ok) { "Could not encode the edited photo" }
                }
            } ?: error("Could not open the original photo for writing")
        }
    }
}

/**
 * The `Bitmap.CompressFormat` [this] maps to.
 *
 * `WEBP_LOSSY`/`WEBP_LOSSLESS` only exist from API 30 — below that, the single, since-deprecated
 * `WEBP` constant is the only encoder available, and it is genuinely fine to use: it is not gone,
 * only superseded, and this app's minSdk (26) needs it for four platform versions.
 */
private fun OutputFormat.toCompressFormat(): Bitmap.CompressFormat = when (this) {
    OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
    OutputFormat.PNG -> Bitmap.CompressFormat.PNG
    OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
}

/**
 * The quality [Bitmap.compress] is called with. PNG's encoder is lossless and ignores this value
 * outright, but `compress()`'s signature requires one regardless of format.
 */
private fun OutputFormat.exportQuality(): Int = when (this) {
    // High enough that a re-encode is not visibly worse than the source at normal viewing sizes,
    // short of 100 where each format's returns collapse and the file balloons for no visible gain.
    OutputFormat.JPEG -> 95
    OutputFormat.WEBP -> 90
    OutputFormat.PNG -> 100
}

/**
 * The name an edited copy is saved under: the original's stem, then a marker, then [format]'s own
 * extension.
 *
 * Pure and separate from the writer so the naming can be asserted without a ContentResolver.
 * Handles the cases that actually occur in a real library: names with no extension, names with
 * several dots, and re-editing a file that is already an edit — which must NOT accumulate a chain
 * of suffixes.
 */
internal fun editedName(displayName: String, format: OutputFormat = OutputFormat.JPEG, marker: String = "edited"): String {
    val trimmed = displayName.trim().ifBlank { "photo" }
    val dot = trimmed.lastIndexOf('.')
    // A leading dot is a hidden file, not an extension, so it is not a split point.
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    // Re-editing an edit replaces the marker rather than stacking another one, so a photo edited
    // five times is not called "shot-edited-edited-edited-edited-edited.jpg".
    val base = stem.removeSuffix("-$marker")
    return "$base-$marker.${format.extension}"
}
