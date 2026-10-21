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
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaKind
import com.fotoxplorr.app.media.mediaStoreRelativePath
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import com.fotoxplorr.app.metadata.ExifCopier
import com.fotoxplorr.app.metadata.ExifCopyReport
import com.fotoxplorr.app.metadata.UNCARRIED_SCAN_BYTES
import com.fotoxplorr.app.metadata.UncarriedMetadata
import com.fotoxplorr.app.metadata.uncarriedMetadataIn
import com.fotoxplorr.app.metadata.xmpAttributeUtf8
import com.fotoxplorr.app.metadata.xmpCarriesLocation
import com.fotoxplorr.core.metadata.XmpPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

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
 * opens the original at all. The one step that cannot be made safe by ordering is the truncating
 * write into the original itself: [replaceWithRestore] copies the original aside first, checks the
 * result reads back as written, and puts the old bytes back if either the write or that check
 * fails. That is best effort, not atomic — MediaStore has no replace-in-place.
 *
 * ## Metadata copying is best-effort for a copy, a gate for a replace
 * [save], [saveTo] and [overwrite] each wrap [applySourceMetadata] on its own, separately from the
 * encode and destination steps: a source whose EXIF this app cannot open or parse still gets a
 * saved copy of the edited pixels, just without the copied metadata (and [save] says so) — the same
 * "never let a secondary concern sink the primary save" reasoning
 * [com.fotoxplorr.app.share.SharePreparer.restoreOrientationOnly] already uses for its own
 * best-effort EXIF touch-up. [overwrite] is the exception: replacing the original cannot be
 * undone, so it refuses unless the metadata this app knows how to carry made it across
 * ([overwriteMetadataRefusal]): the EXIF tags, and an XMP packet that was copied and reads back from
 * the staged file. It also refuses a source holding IPTC-IIM or extended XMP, which [ExifInterface]
 * cannot carry ([uncarriedMetadataIn]). Two losses are accepted and NOT gated, because nearly every
 * camera file has them: `MakerNote` (its offsets are invalid in a new file) and the embedded
 * thumbnail ([com.fotoxplorr.app.metadata.EXCLUDED_TAGS]). [saveTo] strips location on purpose, see
 * its own doc.
 * HEIC output is the one format that gets no copied metadata at all:
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
     * [onMetadata] is told how much of [source]'s metadata made it onto the new file, so the caller
     * can say so when it did not (a copy is still worth saving without it). [onSteppedDown] is told
     * the size actually written when encoding [bitmap] ran out of memory and a smaller rung of
     * [exportEdgeLadder] had to be used instead (a copy is still worth saving smaller).
     *
     * @return the new file's content Uri.
     */
    suspend fun save(
        source: MediaAsset,
        bitmap: Bitmap,
        format: OutputFormat = outputFormatFor(source.mimeType),
        quality: Int = format.exportQuality(),
        onMetadata: (MetadataCopy) -> Unit = {},
        onSteppedDown: (width: Int, height: Int) -> Unit = { _, _ -> },
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = encodeSteppingDown(bitmap, format, quality)
            val tempFile = staged.file
            try {
                if (staged.steppedDown) onSteppedDown(staged.width, staged.height)
                onMetadata(applySourceMetadata(source, tempFile, format))

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
     *
     * **Location is stripped.** [destination] can be a cloud-backed provider or a tree some other
     * app reads, so unlike [save] (a row in this device's own library) it is an export: no `GPS*`
     * EXIF tag and no XMP location property reaches it, whatever the source carried, and the
     * staged file is re-read to prove it before a single byte is written out (docs/TRAPS.md #31).
     * If the proof fails the save fails rather than leaking the location.
     */
    suspend fun saveTo(
        destination: Uri,
        source: MediaAsset,
        bitmap: Bitmap,
        format: OutputFormat,
        quality: Int = format.exportQuality(),
        onSteppedDown: (width: Int, height: Int) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = encodeSteppingDown(bitmap, format, quality)
            val tempFile = staged.file
            try {
                if (staged.steppedDown) onSteppedDown(staged.width, staged.height)
                applySourceMetadata(source, tempFile, format, keepLocation = false)
                check(format == OutputFormat.HEIC || !carriesLocation(tempFile)) {
                    "Could not remove the photo's location from the copy, so nothing was saved"
                }
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
     *
     * Refuses, with a typed [OverwriteException.Refused] and the original untouched, when the
     * file's format has no in-place path, when it is an animation, Ultra HDR image or Motion Photo
     * ([overwriteHazards]), or when its metadata could not be carried over in full
     * ([overwriteMetadataRefusal]).
     */
    suspend fun overwrite(source: MediaAsset, bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val format = overwriteFormatFor(source.mimeType)
                ?: throw OverwriteException.Refused(
                    OverwriteException.Refused.Reason.FORMAT_UNSUPPORTED,
                    "${source.mimeType} cannot be replaced in place. Save a copy instead.",
                )
            // Before anything is encoded: re-encoding the first frame's pixels would destroy an
            // animation, an HDR gain map or a motion video, none of which a copy of the pixels
            // can carry.
            val scan = appContext.readOverwriteHazards(source)
            if (scan.unreadable) {
                throw OverwriteException.Refused(
                    OverwriteException.Refused.Reason.BACKUP_FAILED,
                    "This photo could not be read to check that replacing it is safe, so it was left as it is. " +
                        "Save a copy instead.",
                )
            }
            if (scan.hazards.isNotEmpty()) {
                throw OverwriteException.Refused(
                    OverwriteException.Refused.Reason.WOULD_DESTROY_CONTENT,
                    scan.hazards.refusalMessage(),
                    scan.hazards,
                )
            }
            // One rung, never stepped down: a smaller re-encode of the ORIGINAL would destroy
            // pixels (docs/TRAPS.md #17), so an out-of-memory here fails the overwrite instead.
            val tempFile = encodeToStagingFile(bitmap, format, format.exportQuality())
            try {
                // The metadata gate. Unlike a copy, this is irreversible: if the staged file does
                // not hold everything the original's EXIF and XMP held (location redacted by
                // Android, source unreadable, the copy failed) then writing it over the original
                // would silently strip it, so refuse and leave the original alone.
                overwriteMetadataRefusal(applySourceMetadata(source, tempFile, format))?.let { throw it }

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
                check(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height) {
                    "Re-encoded file does not match the edited photo's dimensions"
                }

                val original = ResolverOriginal(source)
                // The safety copy lives in the app's persistent files, not the cache the system
                // clears when storage is tight; it is deleted once the replace verifies.
                val recoveryDir = OverwriteRecovery.dir(appContext)
                replaceWithRestore(
                    original = original,
                    staged = tempFile,
                    backupDir = recoveryDir,
                    recoveryDir = recoveryDir,
                    label = source.displayName,
                    originalSizeBytes = source.sizeBytes,
                ) {
                    // What reads back must be exactly the staged bytes, and still a photo of the
                    // edited size, or the original is put back.
                    val identical = original.openForRead()?.use { written ->
                        tempFile.inputStream().use { staged -> sameBytes(written, staged) }
                    } ?: false
                    check(identical) { "The replaced file does not read back as written" }
                    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    original.openForRead()?.use { BitmapFactory.decodeStream(it, null, dimensions) }
                    check(dimensions.outWidth == bitmap.width && dimensions.outHeight == bitmap.height) {
                        "The replaced file does not decode to the edited photo's dimensions"
                    }
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * [source]'s file as [replaceWithRestore] reads and rewrites it. Reads go through
     * [uriForLocationRead] so a backup or a read-back is the real bytes, and a view Android had
     * redacted is refused (null) rather than returned: restoring a redacted backup would itself
     * strip the location.
     */
    private inner class ResolverOriginal(private val source: MediaAsset) : OriginalFile {
        override fun openForRead(): InputStream? {
            val requested = appContext.uriForLocationRead(source.contentUri)
            val (stream, unredacted) = openForLocationRead(requested, source.contentUri) { uri ->
                appContext.contentResolver.openInputStream(uri)
            } ?: return null
            if (isRedactedView(Build.VERSION.SDK_INT, source.contentUri, readUnredacted = unredacted)) {
                stream.close()
                return null
            }
            return stream
        }

        override fun openForTruncatingWrite(): OutputStream? =
            appContext.contentResolver.openFileDescriptor(source.contentUri, "wt")
                ?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }

        // Asked of the file itself: MediaStore's recorded size can lag behind another app's edit.
        override fun currentSizeBytes(): Long =
            appContext.contentResolver.openFileDescriptor(source.contentUri, "r")?.use { it.statSize } ?: -1L
    }

    /**
     * [encodeToStagingFile] for a COPY: if the encode itself runs out of memory (the JPEG/WebP/HEIC
     * encoders and the flatten allocate on top of the bitmap), tries again one rung down
     * [exportEdgeLadder] on a scaled copy of [bitmap] instead of failing a save that a smaller file
     * would have satisfied. Throws an [OutOfMemoryError] with a readable message when every rung
     * fails. [overwrite] never comes through here: it does not step down (docs/TRAPS.md #17).
     */
    private suspend fun encodeSteppingDown(bitmap: Bitmap, format: OutputFormat, quality: Int): StagedEncode =
        encodeDownTheLadder(bitmap) { encodeToStagingFile(it, format, quality) }

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
     * back whatever Android redacted. The unredacted read only feeds the app-private staging file
     * and, from there, a copy that stays in this device's own library ([save]) or replaces the
     * original ([overwrite]); an export to a location the user picked ([saveTo]) passes
     * `keepLocation = false` and never carries the location out (docs/TRAPS.md #31), and the
     * write into the original never goes through the unredacted read (#35). Any failure (source
     * unreadable, [tempFile]'s format cannot hold EXIF) is swallowed and reported as the returned
     * [MetadataCopy] instead of thrown: see the class doc's "metadata copying is best-effort"
     * note. [OutputFormat.HEIC] is skipped outright — [ExifInterface] cannot save attributes into
     * it.
     *
     * The source is read through a stream, not a file descriptor: [ExifInterface] buffers what it
     * needs either way, and a stream does not need a seekable descriptor from the provider.
     */
    private fun applySourceMetadata(
        source: MediaAsset,
        tempFile: File,
        format: OutputFormat,
        keepLocation: Boolean = true,
    ): MetadataCopy {
        if (format == OutputFormat.HEIC) return MetadataCopy.SKIPPED_HEIC
        return try {
            val requested = appContext.uriForLocationRead(source.contentUri)
            val opened = openForLocationRead(requested, source.contentUri) { uri ->
                appContext.contentResolver.openInputStream(uri)
            } ?: return MetadataCopy.SOURCE_UNREADABLE
            var report = ExifCopyReport(xmpSkipped = false)
            var sourceHadXmp = false
            var uncarried = emptySet<UncarriedMetadata>()
            opened.first.use { stream ->
                // The head is read first and handed back to ExifInterface in front of the rest, so
                // the same single pass both parses the EXIF and lets uncarriedMetadataIn see the
                // segments ExifInterface cannot (IPTC-IIM, extended XMP).
                val head = stream.readUpTo(UNCARRIED_SCAN_BYTES)
                uncarried = uncarriedMetadataIn(head)
                val sourceExif = ExifInterface(SequenceInputStream(ByteArrayInputStream(head), stream))
                val targetExif = ExifInterface(tempFile.absolutePath)
                report = ExifCopier.copy(sourceExif, targetExif, keepLocation)
                sourceHadXmp = sourceExif.xmpAttributeUtf8() != null
                targetExif.saveAttributes()
            }
            // A fresh read of what reached disk, not the instance that wrote it: the packet is the
            // one thing a silent skip would leave out, and the report above is only the copier's
            // own account of it.
            val stagedHasXmp = !sourceHadXmp || ExifInterface(tempFile.absolutePath).xmpAttributeUtf8() != null
            metadataCopyOutcome(
                redacted = isRedactedView(Build.VERSION.SDK_INT, source.contentUri, readUnredacted = opened.second),
                xmpSkipped = report.xmpSkipped,
                stagedLostXmp = !stagedHasXmp,
                uncarriedFound = uncarried.isNotEmpty(),
            )
        } catch (e: Exception) {
            MetadataCopy.FAILED
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
internal fun OutputFormat.encodesAlpha(): Boolean = encodesAlphaOn(Build.VERSION.SDK_INT)

/** [encodesAlpha] for a given platform version, so the answer can be asked about another device. */
internal fun OutputFormat.encodesAlphaOn(sdkInt: Int): Boolean = when (this) {
    OutputFormat.PNG -> true
    OutputFormat.WEBP -> sdkInt < Build.VERSION_CODES.R
    OutputFormat.JPEG, OutputFormat.HEIC -> false
}

/**
 * Runs [encode] on [bitmap] and, each time it throws [OutOfMemoryError], again on a scaled copy one
 * rung down [exportEdgeLadder]. The scaled copy is recycled as soon as its encode is done. Throws an
 * [OutOfMemoryError] with a readable message when every rung fails. Separate from the writer so the
 * stepping can be driven with a fake [encode].
 */
internal suspend fun encodeDownTheLadder(bitmap: Bitmap, encode: (Bitmap) -> File): StagedEncode {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    val outcome = renderDownTheLadder(exportEdgeLadder(longEdge)) { edge ->
        if (edge >= longEdge) {
            StagedEncode(encode(bitmap), bitmap.width, bitmap.height, steppedDown = false)
        } else {
            val (width, height) = scaledSizeFor(bitmap.width, bitmap.height, edge)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            try {
                StagedEncode(encode(scaled), width, height, steppedDown = true)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }
    return outcome?.value ?: throw OutOfMemoryError("There is not enough memory to save this photo")
}

/** An encoded staging file and the size of the bitmap it holds; [steppedDown] when that is smaller
 *  than the one asked for. */
internal class StagedEncode(val file: File, val width: Int, val height: Int, val steppedDown: Boolean)

/** [width] x [height] scaled so the longer edge is [longEdge], never below 1 px on either side. */
internal fun scaledSizeFor(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
    val scale = longEdge.toDouble() / maxOf(width, height)
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
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
    // Opaque by construction now: lets a second pass (the render's flatten, then the writer's) see
    // there is nothing left to do instead of allocating another full-size copy.
    flattened.setHasAlpha(false)
    return flattened
}

/** What [EditedCopyWriter.applySourceMetadata] managed to carry from the source onto the staged
 *  file. Only [COPIED] means the staged file holds everything the source's metadata did. */
enum class MetadataCopy {
    /** Source read in full, target written. */
    COPIED,

    /** HEIC output: [ExifInterface] cannot write into that container, nothing was attempted. */
    SKIPPED_HEIC,

    /** The source could not be opened at all. */
    SOURCE_UNREADABLE,

    /** Copied, but from a view Android had stripped location (and possibly more) from: see
     *  [isRedactedView]. The staged file is missing whatever was redacted. */
    REDACTED,

    /** Reading or writing the EXIF threw. */
    FAILED,

    /** Copied, but not all of it: an XMP packet this app could not safely rewrite was left out, or
     *  the source holds IPTC-IIM or extended XMP, which [ExifInterface] cannot carry. */
    PARTIAL,
}

/**
 * Folds what [EditedCopyWriter.applySourceMetadata] observed into one [MetadataCopy]. A redacted
 * read outranks the rest (the user's fix is permission, whatever else is missing); otherwise any
 * of [xmpSkipped] (the copier said it skipped a packet), [stagedLostXmp] (the source had XMP and
 * the staged file read back none) or [uncarriedFound] is [MetadataCopy.PARTIAL]. Pure, so the
 * mapping is testable without a file.
 */
internal fun metadataCopyOutcome(
    redacted: Boolean,
    xmpSkipped: Boolean,
    stagedLostXmp: Boolean,
    uncarriedFound: Boolean,
): MetadataCopy = when {
    redacted -> MetadataCopy.REDACTED
    xmpSkipped || stagedLostXmp || uncarriedFound -> MetadataCopy.PARTIAL
    else -> MetadataCopy.COPIED
}

/** Up to [limit] bytes from the start of this stream (fewer at its end). */
private fun InputStream.readUpTo(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < limit) {
        val n = read(buffer, total, limit - total)
        if (n < 0) break
        total += n
    }
    return if (total == limit) buffer else buffer.copyOf(total)
}

/** The clause appended to a "saved" message when the copy is missing some of the source's metadata,
 *  or empty when nothing the user would miss was lost. */
internal fun MetadataCopy.savedNote(): String = when (this) {
    MetadataCopy.REDACTED -> " (location not copied: allow photo-location access to keep it)"
    MetadataCopy.SOURCE_UNREADABLE, MetadataCopy.FAILED -> " (photo details could not be copied)"
    MetadataCopy.PARTIAL -> " (some photo details could not be copied)"
    MetadataCopy.COPIED, MetadataCopy.SKIPPED_HEIC -> ""
}

/**
 * Whether a read of [uri] saw Android's redacted view of the file rather than its real metadata.
 * True on API 29+ for a MediaStore uri that was NOT read with `MediaStore.setRequireOriginal`
 * ([readUnredacted] false: no `ACCESS_MEDIA_LOCATION`, or the platform refused the original and
 * the plain-uri retry took over). Any other uri (a file the app owns, a document provider) and any
 * earlier platform is never redacted. The same condition [com.fotoxplorr.app.metadata.MetadataWriter.write]
 * refuses on.
 */
internal fun isRedactedView(sdkInt: Int, uri: Uri, readUnredacted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY && !readUnredacted

/**
 * Whether [file] still carries a location a reader could use: a decodable GPS coordinate, any
 * `GPS*` EXIF tag, or in its XMP a GPS property, an IPTC location structure or a place name
 * ([xmpCarriesLocation]: matched by namespace in every Description and nested structure, so
 * neither a second Description, a duplicated element nor an unusual prefix hides one). The proof
 * [EditedCopyWriter.saveTo] demands of its staged file; a fresh [ExifInterface], not the one that
 * did the writing, so it checks what reached disk. An XMP packet that does not parse is judged by a
 * prefix-agnostic text match instead: it was skipped on the way in, so finding a marker means a
 * packet got through that should not have.
 */
internal fun carriesLocation(file: File): Boolean {
    val exif = ExifInterface(file.absolutePath)
    if (exif.latLong != null) return true
    if (ExifCopier.GPS_TAGS.any { exif.getAttribute(it) != null }) return true
    val xmp = exif.xmpAttributeUtf8() ?: return false
    val packet = XmpPacket.parse(xmp) ?: return XMP_LOCATION_MARKER.containsMatchIn(xmp)
    return xmpCarriesLocation(packet)
}

private val XMP_LOCATION_MARKER =
    Regex("[\\w.-]+:(GPS[A-Z]|City\\b|State\\b|Country\\b|CountryCode\\b|Location\\b)|LocationCreated|LocationShown")

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
