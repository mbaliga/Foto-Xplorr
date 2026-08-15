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

/**
 * Writes an edited photo out as a **new file**, never over the original.
 *
 * This is the single most important property of the editor and it is enforced here rather than
 * left to a UI flow: there is no code path in this class that opens the source for writing. A
 * photo library is often the only copy of an irreplaceable image, and an editor that can overwrite
 * one is an editor that will eventually destroy one. "Save" therefore means "save a copy", and
 * the copy lands beside the original where the user will actually find it.
 */
class EditedCopyWriter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Encode [bitmap] as a new image next to [source].
     *
     * @return the new file's content Uri.
     */
    suspend fun save(source: MediaAsset, bitmap: Bitmap): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val name = editedName(source.displayName)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, OUTPUT_MIME)
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
                    val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
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

    private companion object {
        const val OUTPUT_MIME = "image/jpeg"

        /**
         * High enough that a re-encode is not visibly worse than the source at normal viewing
         * sizes, short of 100 where JPEG's returns collapse and the file doubles.
         */
        const val JPEG_QUALITY = 95
    }
}

/**
 * The name an edited copy is saved under: the original's stem, then a marker, then `.jpg`.
 *
 * Pure and separate from the writer so the naming can be asserted without a ContentResolver.
 * Handles the cases that actually occur in a real library: names with no extension, names with
 * several dots, and re-editing a file that is already an edit — which must NOT accumulate a chain
 * of suffixes.
 */
internal fun editedName(displayName: String, marker: String = "edited"): String {
    val trimmed = displayName.trim().ifBlank { "photo" }
    val dot = trimmed.lastIndexOf('.')
    // A leading dot is a hidden file, not an extension, so it is not a split point.
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    // Re-editing an edit replaces the marker rather than stacking another one, so a photo edited
    // five times is not called "shot-edited-edited-edited-edited-edited.jpg".
    val base = stem.removeSuffix("-$marker")
    return "$base-$marker.jpg"
}
