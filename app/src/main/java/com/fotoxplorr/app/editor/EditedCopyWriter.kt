package com.fotoxplorr.app.editor

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
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
 *
 * Both paths also preserve what they can of the source's own EXIF/XMP (camera facts, GPS,
 * caption/keywords already written by [com.fotoxplorr.app.metadata.MetadataWriter]) and reset
 * orientation to upright — see `ExifOrientation.kt`'s own doc for why a naive re-encode would
 * otherwise both lose that metadata and, for a sideways-shot photo, save the wrong pixels.
 */
class EditedCopyWriter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Encode [bitmap] as a new image next to [source], in [format] (defaulting to [source]'s own
     * format via [outputFormatFor]) at [quality] (defaulting to [format]'s own default; only
     * meaningful for JPEG/WebP, PNG's encoder is lossless).
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
            val name = editedName(source.displayName, format)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Alongside the original rather than in a folder of our own: an edit is a
                    // version of a photo, and burying it somewhere else makes it feel lost.
                    // Validated rather than copied blindly -- see safeRelativePath's own doc for
                    // why a source under Download/, Documents/ or a WhatsApp media folder would
                    // otherwise make insert() throw.
                    put(MediaStore.Images.Media.RELATIVE_PATH, safeRelativePath(source.relativePath))
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
                encodeInto(resolver, uri, bitmap, format, quality)
                preserveExifOnto(resolver, source, uri)
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
     * Encodes [bitmap] straight into an already-chosen [destination] — the "Save to…" location a
     * user picked through `ActivityResultContracts.CreateDocument`, hosted inside [EditorScreen],
     * for when the default "beside the original" home is not where they want this edit.
     *
     * Deliberately narrower than [save]: [destination] is a document the picker already created
     * (in whatever tree the user chose, on-device or a cloud-backed provider), not a MediaStore
     * row this app owns, so there is no relative-path validation and no IS_PENDING dance to run —
     * a half-written file behind a SAF Uri is the picker's document to protect, not this app's
     * scan pipeline. Source EXIF/XMP is still preserved [format] permitting, the same as [save].
     */
    suspend fun saveTo(
        destination: Uri,
        source: MediaAsset,
        bitmap: Bitmap,
        format: OutputFormat,
        quality: Int = format.exportQuality(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            encodeInto(resolver, destination, bitmap, format, quality)
            preserveExifOnto(resolver, source, destination)
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
            val resolver = appContext.contentResolver

            // Captured BEFORE the file is truncated below -- there is nothing left to read
            // metadata FROM once "rwt" has run, since that mode empties the file first.
            val preserved = runCatching {
                resolver.openInputStream(source.contentUri)?.use { ExifInterface(it) }
            }.getOrNull()

            resolver.openFileDescriptor(source.contentUri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { stream ->
                    val encoded = compositeOntoWhiteIfNeeded(bitmap, format)
                    val ok = encoded.compress(format.toCompressFormat(), format.exportQuality(), stream)
                    if (encoded !== bitmap) encoded.recycle()
                    check(ok) { "Could not encode the edited photo" }
                }
            } ?: error("Could not open the original photo for writing")

            if (preserved != null) {
                runCatching {
                    resolver.openFileDescriptor(source.contentUri, "rw")?.use { destDescriptor ->
                        copyPreservableExif(preserved, ExifInterface(destDescriptor.fileDescriptor))
                    }
                }
            }
        }
    }

    /** Copies whatever of [source]'s own EXIF/XMP survives into the freshly-written [destination].
     *  Best-effort: see [copyPreservableExif]'s own doc for why a failure here must not fail the
     *  save the pixels already succeeded at. HEIC is skipped -- [ExifInterface] cannot open a
     *  HEIC file [HeifWriter] just wrote the way it can a JPEG/PNG/WebP one, and HEIC's own
     *  container already carries the source's EXIF via [HeifWriter.addExifData] when supplied. */
    private fun preserveExifOnto(resolver: ContentResolver, source: MediaAsset, destination: Uri) {
        runCatching {
            resolver.openInputStream(source.contentUri)?.use { sourceStream ->
                val sourceExif = ExifInterface(sourceStream)
                resolver.openFileDescriptor(destination, "rw")?.use { destDescriptor ->
                    copyPreservableExif(sourceExif, ExifInterface(destDescriptor.fileDescriptor))
                }
            }
        }
    }

    /** Encodes [bitmap] into [uri] in [format], branching to [HeifWriter] for HEIC since there is
     *  no `Bitmap.CompressFormat` for it at all -- see [OutputFormat.HEIC]'s own doc. */
    private fun encodeInto(resolver: ContentResolver, uri: Uri, bitmap: Bitmap, format: OutputFormat, quality: Int) {
        if (format == OutputFormat.HEIC) {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isHeicExportSupported()) {
                "This device cannot encode HEIC"
            }
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                writeHeic(descriptor.fileDescriptor, bitmap, quality)
            } ?: error("Could not open the new file for writing")
            return
        }
        resolver.openOutputStream(uri)?.use { stream ->
            val compressFormat = format.toCompressFormat()
            val encoded = compositeOntoWhiteIfNeeded(bitmap, format)
            val ok = encoded.compress(compressFormat, quality.coerceIn(1, 100), stream)
            if (encoded !== bitmap) encoded.recycle()
            check(ok) { "Could not encode the edited photo" }
        } ?: error("Could not open the new file for writing")
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
 * Encodes [bitmap] as a single-image HEIC container into [descriptor].
 *
 * `INPUT_MODE_BITMAP` is the only mode this app needs: [HeifWriter] also supports raw YUV buffers
 * and a `Surface` for a live encoder feed, both aimed at continuous capture pipelines this editor
 * has no equivalent of. `setMaxImages(1)` matches: this writes one still, never an image sequence.
 */
private fun writeHeic(descriptor: FileDescriptor, bitmap: Bitmap, quality: Int) {
    val writer = HeifWriter.Builder(
        descriptor,
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
 * [EditedCopyWriter]'s `encodeInto`, which branches to [HeifWriter] before this is reached.
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
    OutputFormat.HEIC -> error("HEIC has no Bitmap.CompressFormat; see EditedCopyWriter.encodeInto")
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
 * The primary directory segment `MediaStore.Images.Media` will actually accept for
 * `RELATIVE_PATH` on an insert — only `DCIM/` and `Pictures/` are legal primaries for the Images
 * collection (see the platform's own `MediaStore` documentation on scoped-storage primary
 * directories); anything else — `Download/`, `Documents/`, `Android/media/<package>/...` — makes
 * `insert()` throw `IllegalArgumentException` at the exact moment a save was supposed to happen.
 *
 * Falls back to a dedicated folder rather than refusing the save outright or silently dropping
 * the path (which lets MediaStore pick its own default, historically `Pictures/`, but not
 * reliably across OEMs): "the edit landed one folder away from the original" is a far smaller
 * problem than "the edit was lost".
 */
internal fun safeRelativePath(sourceRelativePath: String?): String {
    val normalized = sourceRelativePath.orEmpty().trim('/')
    val primary = normalized.substringBefore('/', missingDelimiterValue = normalized)
    val allowed = primary.equals("DCIM", ignoreCase = true) || primary.equals("Pictures", ignoreCase = true)
    return if (allowed && normalized.isNotEmpty()) "$normalized/" else FALLBACK_RELATIVE_PATH
}

/** Where an edited copy lands when its source's own folder is not one MediaStore's Images
 *  collection accepts as a primary directory — see [safeRelativePath]. */
internal const val FALLBACK_RELATIVE_PATH = "Pictures/Foto Xplorr/"
