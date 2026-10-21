package com.fotoxplorr.app.editor

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
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
 * Writes an edited photo out — as a new file beside the original by default, to a location the
 * user picked, or in place when the caller has already secured permission to replace it. Every
 * path keeps the source's own EXIF, XMP and capture date (P0-07): an edit is a version of the
 * photo, not a blank slate that resets to "just taken now" the moment it is saved. Orientation is
 * reset to upright because the edited pixels already have it baked in ([ExifCopier] does the reset;
 * [com.fotoxplorr.app.media.decodeUpright] bakes the rotation into the pixels), since a naive
 * re-encode would otherwise both lose that metadata and, for a sideways-shot photo, save the wrong
 * pixels.
 *
 * The two kinds of destination are asymmetric on purpose. [save] and [saveTo] can create a fresh
 * file for ANY [OutputFormat] with no special permission, because inserting a new file this app
 * owns (or one the user's own picker just created) needs none. [overwrite] can only ever
 * re-encode into the file's OWN existing format (see [overwriteFormatFor]'s own doc for why) and
 * requires the caller to already hold write access to a file this app did not create — see
 * [com.fotoxplorr.app.FotoXplorrActivity]'s three-tier consent dance, the same one rename and
 * metadata writes already use, reused rather than reinvented for this fourth call site.
 *
 * ## Temp, then verify, then destination
 * Every path encodes into a file under this app's own cache first ([encodeToStagingFile]), applies
 * the source's metadata to THAT file, and only then copies the finished bytes onward
 * (docs/TRAPS.md #35). Nothing about an existing file's bytes is touched unless every earlier step
 * already succeeded, and [overwrite] additionally re-reads the staged file's dimensions before it
 * opens the original at all.
 *
 * ## Metadata copying is best-effort
 * [save], [saveTo] and [overwrite] each wrap [applySourceMetadata] on its own, separately from the
 * encode and destination steps: a source whose EXIF this app cannot open or parse still gets a
 * saved (or overwritten) copy of the edited pixels, just without the copied metadata — the same
 * "never let a secondary concern sink the primary save" reasoning
 * [com.fotoxplorr.app.share.SharePreparer.restoreOrientationOnly] already uses for its own
 * best-effort EXIF touch-up. HEIC output is the one format that gets no copied metadata at all:
 * [ExifInterface] cannot write attributes into a HEIC container, so [applySourceMetadata] skips it
 * rather than letting that failure be swallowed after the fact.
 */
class EditedCopyWriter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Encode [bitmap] as a new image next to [source], in [format] (defaulting to [source]'s own
     * format via [outputFormatFor]) at [quality] (defaulting to [format]'s own default; only
     * meaningful for JPEG/WebP/HEIC, PNG's encoder is lossless).
     *
     * @return the new file's content Uri.
     */
    suspend fun save(
        source: MediaAsset,
        bitmap: Bitmap,
        format: OutputFormat = outputFormatFor(source.mimeType),
        quality: Int = format.exportQuality(),
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = encodeToStagingFile(bitmap, format, quality)
            try {
                applySourceMetadata(source, tempFile, format)

                val name = editedName(source.displayName, format)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Images.Media.DATE_TAKEN, source.dateTakenMillis)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Alongside the original when its own folder is one MediaProvider still
                        // accepts an insert into, otherwise this app's own fallback folder — see
                        // mediaStoreRelativePath's own doc (via safeRelativePath) for why a
                        // WhatsApp or Download source cannot just reuse its source path verbatim
                        // here.
                        put(MediaStore.Images.Media.RELATIVE_PATH, safeRelativePath(source.relativePath))
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
     * Encodes [bitmap] straight into an already-chosen [destination] — the "Save to…" location a
     * user picked through `ActivityResultContracts.CreateDocument`, hosted inside [EditorScreen],
     * for when the default "beside the original" home is not where they want this edit.
     *
     * Deliberately narrower than [save]: [destination] is a document the picker already created
     * (in whatever tree the user chose, on-device or a cloud-backed provider), not a MediaStore
     * row this app owns, so there is no relative-path validation and no IS_PENDING dance to run —
     * a half-written file behind a SAF Uri is the picker's document to protect, not this app's
     * scan pipeline. Source EXIF/XMP is still preserved [format] permitting, the same as [save],
     * and the bytes still reach [destination] only after they have been fully encoded and had
     * their metadata applied in the staging file.
     */
    suspend fun saveTo(
        destination: Uri,
        source: MediaAsset,
        bitmap: Bitmap,
        format: OutputFormat,
        quality: Int = format.exportQuality(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = encodeToStagingFile(bitmap, format, quality)
            try {
                applySourceMetadata(source, tempFile, format)
                // "wt" (write + truncate), not the default "w": a SAF provider is allowed to
                // leave the tail of a previous, longer document in place under plain "w", and a
                // picker can hand back an existing file the user chose to replace.
                appContext.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    tempFile.inputStream().use { it.copyTo(output) }
                } ?: error("Could not open the chosen location for writing")
                Unit
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
     * The source's metadata is read into the staged copy, and the replacement fully encoded and
     * verified, BEFORE the original is opened for writing at all: nothing about [source]'s
     * existing bytes is touched unless every step up to that point already succeeded. Opened as
     * `"wt"` — write, **truncate**, no read access requested — so a smaller re-encode does not
     * leave a tail of the previous file's bytes behind it.
     */
    suspend fun overwrite(source: MediaAsset, bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val format = overwriteFormatFor(source.mimeType)
                ?: error("${source.mimeType} cannot be replaced in place")
            val tempFile = encodeToStagingFile(bitmap, format, format.exportQuality())
            try {
                applySourceMetadata(source, tempFile, format)

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

    /**
     * Encodes [bitmap] into a fresh file under this app's own cache, never MediaStore or the
     * source's own storage — [save], [saveTo] and [overwrite] all apply metadata here first and
     * only copy the finished bytes onward from there.
     *
     * Branches to [HeifWriter] for HEIC since there is no `Bitmap.CompressFormat` for it at all —
     * see [OutputFormat.HEIC]'s own doc — and flattens alpha onto white first for any format that
     * has no alpha channel of its own ([compositeOntoWhiteIfNeeded]).
     */
    private fun encodeToStagingFile(bitmap: Bitmap, format: OutputFormat, quality: Int): File {
        val stagingDir = File(appContext.cacheDir, "edit-staging").apply { mkdirs() }
        val tempFile = File.createTempFile("edit-", ".${format.extension}", stagingDir)
        try {
            if (format == OutputFormat.HEIC) {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isHeicExportSupported()) {
                    "This device cannot encode HEIC"
                }
                writeHeic(tempFile, bitmap, quality)
            } else {
                FileOutputStream(tempFile).use { stream ->
                    val encoded = compositeOntoWhiteIfNeeded(bitmap, format)
                    val ok = try {
                        encoded.compress(format.toCompressFormat(), quality.coerceIn(1, 100), stream)
                    } finally {
                        if (encoded !== bitmap) encoded.recycle()
                    }
                    check(ok) { "Could not encode the edited photo" }
                }
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
        return tempFile
    }

    /**
     * Copies [source]'s own EXIF and XMP onto [tempFile] via [ExifCopier.copy] — through
     * [uriForLocationRead] (P0-02) so the user's own edited copy keeps its GPS instead of reading
     * back whatever Android redacted. The unredacted read only ever feeds the app-private staging
     * file; it is never a share or export path (docs/TRAPS.md #31), and the write into the
     * original never goes through it (#35). Any failure (source unreadable, [tempFile]'s format
     * cannot hold EXIF) is swallowed: see the class doc's "metadata copying is best-effort" note.
     * [OutputFormat.HEIC] is skipped outright — [ExifInterface] cannot save attributes into it.
     */
    private fun applySourceMetadata(source: MediaAsset, tempFile: File, format: OutputFormat) {
        if (format == OutputFormat.HEIC) return
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

// ---------------------------------------------------------------------------
// HEIC export
// ---------------------------------------------------------------------------

/** How long [HeifWriter.stop] blocks waiting for the encoder to flush its last frame. A single
 *  still image never needs anywhere near this; it exists to fail loudly rather than hang forever
 *  if a device's HEVC encoder wedges. */
private const val HEIC_STOP_TIMEOUT_MS = 10_000L

/**
 * Encodes [bitmap] as a single-image HEIC container into [file] (the staging file
 * [EditedCopyWriter.encodeToStagingFile] created -- a path rather than a descriptor, since every
 * destination is written from the finished staging file, never encoded into directly).
 *
 * `INPUT_MODE_BITMAP` is the only mode this app needs: [HeifWriter] also supports raw YUV buffers
 * and a `Surface` for a live encoder feed, both aimed at continuous capture pipelines this editor
 * has no equivalent of. `setMaxImages(1)` matches: this writes one still, never an image sequence.
 */
private fun writeHeic(file: File, bitmap: Bitmap, quality: Int) {
    val writer = HeifWriter.Builder(
        file.absolutePath,
        bitmap.width,
        bitmap.height,
        HeifWriter.INPUT_MODE_BITMAP,
    ).setQuality(quality.coerceIn(1, 100)).setMaxImages(1).build()
    try {
        writer.start()
        writer.addBitmap(bitmap)
        writer.stop(HEIC_STOP_TIMEOUT_MS)
    } finally {
        writer.close()
    }
}

/**
 * The `Bitmap.CompressFormat` [this] maps to. Never called for [OutputFormat.HEIC] — see
 * [EditedCopyWriter]'s `encodeToStagingFile`, which branches to [HeifWriter] before this is reached.
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
    OutputFormat.HEIC -> error("HEIC has no Bitmap.CompressFormat; see EditedCopyWriter.encodeToStagingFile")
}

/**
 * The quality [Bitmap.compress] is called with by default. PNG's encoder is lossless and ignores
 * this value outright, but `compress()`'s signature requires one regardless of format. HEIC's own
 * default mirrors JPEG's — both are lossy formats at a comparable bitrate/quality trade-off.
 *
 * Export controls let a user override this (see `ExportOptions`), clamped to 50..100: below 50 a
 * JPEG/WebP/HEIC re-encode of an already-compressed camera photo visibly blocks, which is a worse
 * outcome than the control simply not going that low.
 */
internal fun OutputFormat.exportQuality(): Int = when (this) {
    // High enough that a re-encode is not visibly worse than the source at normal viewing sizes,
    // short of 100 where each format's returns collapse and the file balloons for no visible gain.
    OutputFormat.JPEG -> 95
    OutputFormat.WEBP -> 90
    OutputFormat.PNG -> 100
    OutputFormat.HEIC -> 90
}

/**
 * Whether [Bitmap.compress] into [this] format keeps an alpha channel at all — used to decide
 * whether [compositeOntoWhiteIfNeeded] needs to run first. JPEG has no alpha channel full stop;
 * lossy WebP (the variant this app's own [toCompressFormat] picks on API 30+) drops it the same
 * way JPEG does, per that constant's own platform documentation.
 */
internal fun OutputFormat.encodesAlpha(): Boolean = when (this) {
    OutputFormat.PNG -> true
    OutputFormat.WEBP -> Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    OutputFormat.JPEG, OutputFormat.HEIC -> false
}

/**
 * Bakes [bitmap]'s alpha onto a white background when [format] has no alpha channel of its own,
 * returning [bitmap] itself unchanged otherwise. Callers must recycle the result only when it is
 * NOT `===` [bitmap] (see both call sites).
 *
 * `Bitmap.compress` silently drops the alpha channel for an alpha-less format without ever
 * refusing or warning, and because `ARGB_8888` stores PREMULTIPLIED colour, a translucent pixel's
 * own RGB is already scaled toward black — an unmodified translucent PNG re-saved as JPEG comes
 * out with its transparent areas rendered as solid BLACK, not the white a viewer expects from "no
 * colour was ever there". Compositing onto an opaque white canvas first is what a "flatten"
 * operation in any real image editor does for exactly this reason.
 */
internal fun compositeOntoWhiteIfNeeded(bitmap: Bitmap, format: OutputFormat): Bitmap {
    if (format.encodesAlpha() || !bitmap.hasAlpha()) return bitmap
    val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Canvas(flattened).apply {
        drawColor(Color.WHITE)
        drawBitmap(bitmap, 0f, 0f, null)
    }
    return flattened
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

/**
 * The `RELATIVE_PATH` an edited copy of a source at [sourceRelativePath] is inserted under in the
 * Images collection: the source's own folder when MediaProvider would accept an insert there
 * (`DCIM/` or `Pictures/`, subfolders kept), otherwise this app's own fallback folder.
 *
 * A source under `Download/`, `Documents/` or a WhatsApp `Android/media/...` folder would make
 * `insert()` throw `IllegalArgumentException` at the exact moment a save was supposed to happen,
 * and "the edit landed one folder away from the original" is a far smaller problem than "the edit
 * was lost". The decision itself lives in [mediaStoreRelativePath] (P0-07's one shared rule, which
 * also knows about the pre-rename `Foto Xplorr` fallback folders); this is only the Images-collection
 * spelling of it, kept as a named function so the editor's tests can pin the editor's own contract.
 */
internal fun safeRelativePath(sourceRelativePath: String?): String =
    mediaStoreRelativePath(sourceRelativePath, MediaKind.IMAGE)

/** Where an edited copy lands when its source's own folder is not one MediaStore's Images
 *  collection accepts as a primary directory — see [safeRelativePath]. */
internal val FALLBACK_RELATIVE_PATH: String get() = mediaStoreRelativePath(null, MediaKind.IMAGE)
