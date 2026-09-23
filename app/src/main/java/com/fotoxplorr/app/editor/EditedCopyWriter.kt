package com.fotoxplorr.app.editor

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaKind
import com.fotoxplorr.app.media.mediaStoreRelativePath
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import com.fotoxplorr.app.metadata.ExifCopier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Writes an edited photo out — as a new file beside the original by default, or in place when the
 * caller has already secured permission to replace it. Both paths keep the source's own EXIF, XMP
 * and capture date (P0-07): an edit is a version of the photo, not a blank slate that resets to
 * "just taken now" the moment it is saved.
 *
 * The two paths are asymmetric on purpose. [save] can create a fresh MediaStore row for ANY
 * [OutputFormat] with no special permission, because inserting a new file this app owns needs
 * none. [overwrite] can only ever re-encode into the file's OWN existing format (see
 * [overwriteFormatFor]'s own doc for why) and requires the caller to already hold write access to
 * a file this app did not create — see [com.fotoxplorr.app.FotoXplorrActivity]'s three-tier
 * consent dance, the same one rename and metadata writes already use, reused rather than
 * reinvented for this fourth call site.
 *
 * ## Metadata copying is best-effort
 * Both [save] and [overwrite] wrap [applySourceMetadata] on its own, separately from the encode
 * and MediaStore steps: a source whose EXIF this app cannot open or parse still gets a saved (or
 * overwritten) copy of the edited pixels, just without the copied metadata — the same "never let a
 * secondary concern sink the primary save" reasoning
 * [com.fotoxplorr.app.share.SharePreparer.restoreOrientationOnly] already uses for its own
 * best-effort EXIF touch-up.
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
            val tempFile = encodeToStagingFile(bitmap, format)
            try {
                applySourceMetadata(source, tempFile)

                val name = editedName(source.displayName, format)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Images.Media.DATE_TAKEN, source.dateTakenMillis)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Alongside the original when its own folder is one MediaProvider still
                        // accepts an insert into, otherwise this app's own fallback folder — see
                        // mediaStoreRelativePath's own doc for why a WhatsApp or Download source
                        // cannot just reuse its source path verbatim here.
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            mediaStoreRelativePath(source.relativePath, MediaKind.IMAGE),
                        )
                        // IS_PENDING keeps the half-written file out of every other gallery on the
                        // device until the bytes are actually there. Without it a scanner can index
                        // a truncated JPEG and show the user a corrupt thumbnail of their own edit.
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Android would not create a new image file")

                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        tempFile.inputStream().use { it.copyTo(output) }
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
                // Clearing IS_PENDING can trigger a rescan that re-derives DATE_TAKEN from the
                // file's own EXIF rather than keeping the value this insert asked for — reassert it
                // once if that happened, rather than trusting the insert's value went untouched.
                reassertDateTakenIfChanged(resolver, uri, source.dateTakenMillis)
                uri
            } finally {
                tempFile.delete()
            }
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
     * The source's metadata is read into memory, and the replacement fully encoded and verified,
     * BEFORE the original is opened for writing at all: nothing about [source]'s existing bytes is
     * touched unless every step up to that point already succeeded. Opened as `"wt"` — write,
     * **truncate**, no read access requested — so a smaller re-encode does not leave a tail of the
     * previous file's bytes behind it.
     */
    suspend fun overwrite(source: MediaAsset, bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val format = overwriteFormatFor(source.mimeType)
                ?: error("${source.mimeType} cannot be replaced in place")
            val tempFile = encodeToStagingFile(bitmap, format)
            try {
                applySourceMetadata(source, tempFile)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
                check(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height) {
                    "Re-encoded file does not match the edited photo's dimensions"
                }

                appContext.contentResolver.openFileDescriptor(source.contentUri, "wt")?.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        tempFile.inputStream().use { it.copyTo(output) }
                        Unit
                    }
                } ?: error("Could not open the original photo for writing")
            } finally {
                tempFile.delete()
            }
        }
    }

    /** Encodes [bitmap] into a fresh file under this app's own cache, never MediaStore or
     *  [source]'s own storage — both [save] and [overwrite] apply metadata here first and only
     *  copy the finished, verified bytes onward from there. */
    private fun encodeToStagingFile(bitmap: Bitmap, format: OutputFormat): File {
        val stagingDir = File(appContext.cacheDir, "edit-staging").apply { mkdirs() }
        val tempFile = File.createTempFile("edit-", ".${format.extension}", stagingDir)
        FileOutputStream(tempFile).use { stream ->
            val ok = bitmap.compress(format.toCompressFormat(), format.exportQuality(), stream)
            check(ok) { "Could not encode the edited photo" }
        }
        return tempFile
    }

    /**
     * Copies [source]'s own EXIF and XMP onto [tempFile] via [ExifCopier.copy] — through
     * [uriForLocationRead] (P0-02) so the user's own edited copy keeps its GPS instead of reading
     * back whatever Android redacted. Any failure (source unreadable, [tempFile]'s format cannot
     * hold EXIF) is swallowed: see the class doc's "metadata copying is best-effort" note.
     */
    private fun applySourceMetadata(source: MediaAsset, tempFile: File) {
        runCatching {
            val requested = appContext.uriForLocationRead(source.contentUri)
            val opened = openForLocationRead(requested, source.contentUri) { uri ->
                appContext.contentResolver.openFileDescriptor(uri, "r")
            } ?: return
            opened.first.use { descriptor ->
                val sourceExif = ExifInterface(descriptor.fileDescriptor)
                val targetExif = ExifInterface(tempFile.absolutePath)
                ExifCopier.copy(sourceExif, targetExif)
                targetExif.saveAttributes()
            }
        }
    }

    private fun reassertDateTakenIfChanged(resolver: ContentResolver, uri: Uri, expected: Long) {
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0) && cursor.getLong(0) != expected) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.DATE_TAKEN, expected) },
                        null,
                        null,
                    )
                }
            }
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
