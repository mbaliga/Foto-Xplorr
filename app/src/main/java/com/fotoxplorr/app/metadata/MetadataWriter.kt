package com.fotoxplorr.app.metadata

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.uriForLocationRead
import com.fotoxplorr.core.metadata.XmpPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes professional metadata -- caption, creator, copyright, rating, keywords, GPS -- into a
 * photo's own file, not just this app's database. That distinction is the entire point of this
 * class: a caption typed into `com.fotoxplorr.app.organize.LibraryStore` stays legible to this
 * app alone, where a caption written here travels with the file the moment it is copied, shared,
 * or opened in literally any other tool -- which is what "photographer-grade metadata" means in
 * practice and the reason this exists as a second write path rather than an extension of that
 * store.
 *
 * ## EXIF and XMP, written together, on purpose
 * Every field that has BOTH an EXIF and an XMP home gets written to both (see [applyToXmp] for
 * the XMP half, [applyMetadataToExif] for the other). That is deliberate duplication, not an
 * oversight: some readers check only EXIF, others only XMP, and a professional workflow cannot
 * afford to guess which. Two fields exist ONLY in XMP -- rating and keywords -- because EXIF has
 * no tag for either; see the class doc history for how that was confirmed against the real
 * `androidx.exifinterface` artifact rather than assumed.
 *
 * ## What this class refuses to do to metadata it does not understand
 * [XmpPacket]'s whole design is "never destroy a property this app has no model for" -- see its
 * own class doc. This file extends that same caution one level up: a file whose EXISTING XMP
 * fails to parse gets its EXIF fields updated and its XMP left byte-for-byte alone, rather than
 * inventing a fresh, empty packet that would discard everything already there. See [readXmpAttribute].
 *
 * ## What this class does NOT attempt
 * Camera-proprietary RAW files Android cannot decode (CR2, NEF, ARW and the rest --
 * see [com.fotoxplorr.app.formats.RawVariant]) are not writable through `ExifInterface` at all;
 * [write] fails informatively for one rather than silently corrupting a camera-original file.
 * The professional-standard answer for those -- an `.xmp` sidecar file beside the RAW, never
 * touching the RAW itself -- is real, common workflow and deliberately not built into this first
 * pass; it is tracked as its own follow-up rather than rushed into this one.
 *
 * ## Write permission
 * This class does exactly what [com.fotoxplorr.app.fileops.MediaFileOperations.rename] already
 * does and no more: it attempts the write and lets a `RecoverableSecurityException` (API 29) or
 * plain `SecurityException` (API 30+, when consent was never requested) surface through the
 * returned [Result]. It is the CALLER's job -- an `Activity`, which alone can launch a system
 * consent screen -- to catch that and retry, exactly as `FotoXplorrActivity` already does for
 * rename. A third copy of that dance existing here, instead of this class reaching for its own
 * launcher, is what keeps consent-handling in the one layer of the app that can actually show a
 * system UI for it.
 *
 * ## Original access, staging, and verification (P0-08)
 * [write] never opens [MediaAsset.contentUri] directly for read-modify-write the way it once did.
 * Two real risks that shape: opening a REDACTED view for a "rw" edit means `ExifInterface`'s own
 * read-modify-write cycle (it reads every existing attribute into memory before
 * [ExifInterface.saveAttributes] rewrites the whole segment) would silently erase GPS tags this
 * edit never meant to touch, the moment they read back absent -- confirmed as a real platform
 * mechanism, not assumed, via `RedactingFileDescriptor`'s own javadoc (see Decisions in
 * `docs/handoff/PHASE-0-PROGRESS.md`); and a bare in-place rewrite leaves nothing to recover from
 * if the process dies mid-write. So: on API 29+, a write refuses outright unless
 * `ACCESS_MEDIA_LOCATION` is granted (this app cannot safely edit a file it cannot read
 * unredacted); otherwise it reads through [com.fotoxplorr.app.media.uriForLocationRead] into a
 * temp file under `cacheDir/metadata-staging/`, edits and verifies THAT copy (same bounds before
 * and after, and a fresh, independent [ExifInterface] re-read confirms every field [edit] asked
 * for actually landed), and only then streams the verified bytes into the original with `"wt"`.
 * The one risk this does NOT close: an interruption during that final stream-in still leaves the
 * original file truncated or half-written, exactly as a direct in-place edit would have -- staging
 * moves where corruption CAN happen away from "while reading and editing", not away from "while
 * the final bytes are landing", which has no read-then-verify equivalent for the original file
 * itself without a second, redundant round trip this task's own scope does not ask for.
 */
class MetadataWriter(context: Context) {
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /**
     * Applies [edit] to [asset]'s own file. A no-op edit ([MetadataEdit.isEmpty]) is a no-op
     * write too -- opening a file for "rw" access and re-saving it unchanged is not free (it is
     * still a full container rewrite on some formats) and buys nothing for a caller that, say,
     * always calls this from a save button regardless of whether anything was actually typed.
     */
    suspend fun write(asset: MediaAsset, edit: MetadataEdit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (edit.isEmpty) return@runCatching

            val requested = appContext.uriForLocationRead(asset.contentUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !requested.original) {
                error("Allow access to photo locations to edit metadata safely")
            }

            val tempFile = stageOriginalBytes(requested.uri, asset)
            try {
                val boundsBefore = decodeBounds(tempFile)
                applyMetadataEdit(ExifInterface(tempFile.absolutePath), edit)
                verifyStagedEdit(tempFile, edit, boundsBefore)

                resolver.openFileDescriptor(asset.contentUri, "wt")?.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        tempFile.inputStream().use { it.copyTo(output) }
                    }
                } ?: throw IOException("Could not open ${asset.displayName} for writing")
            } finally {
                tempFile.delete()
            }
        }
    }

    /** Copies [readUri]'s bytes -- the original, unredacted view [write] already confirmed it can
     *  read -- into a fresh file under this app's own cache, never the asset's own storage. */
    private fun stageOriginalBytes(readUri: android.net.Uri, asset: MediaAsset): File {
        val stagingDir = File(appContext.cacheDir, "metadata-staging").apply { mkdirs() }
        val tempFile = File.createTempFile("metadata-", ".tmp", stagingDir)
        resolver.openInputStream(readUri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: run {
            tempFile.delete()
            throw IOException("Could not read ${asset.displayName} to prepare a metadata edit")
        }
        return tempFile
    }

    private fun decodeBounds(file: File): android.graphics.Point {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return android.graphics.Point(options.outWidth, options.outHeight)
    }

    /**
     * The two checks the brief names: the temp file still decodes to the same pixel size it had
     * before [edit] touched only its metadata, and a FRESH, independently-opened [ExifInterface]
     * (never the same instance [applyMetadataEdit] just wrote through, which would only prove the
     * in-memory write succeeded, not that it reached disk) reads back every field [edit] asked
     * for. A verification failure throws, which [write]'s own `runCatching` turns into a
     * [Result.failure] -- the temp file is discarded ([write]'s `finally`) and the original is
     * never touched.
     */
    private fun verifyStagedEdit(file: File, edit: MetadataEdit, boundsBefore: android.graphics.Point) {
        val boundsAfter = decodeBounds(file)
        check(boundsBefore == boundsAfter) {
            "Editing metadata changed this photo's own pixel dimensions from $boundsBefore to $boundsAfter"
        }

        val fresh = ExifInterface(file.absolutePath)
        val xmp = readXmpAttribute(fresh)
        val current = currentMetadataFrom(
            xmp = xmp,
            exifImageDescription = fresh.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
            exifArtist = fresh.getAttribute(ExifInterface.TAG_ARTIST),
            exifCopyright = fresh.getAttribute(ExifInterface.TAG_COPYRIGHT),
        )
        edit.caption?.let { check(fieldVerifies(it, current.caption)) { "Caption did not verify after writing" } }
        edit.creator?.let { check(fieldVerifies(it, current.creator)) { "Creator did not verify after writing" } }
        edit.copyright?.let { check(fieldVerifies(it, current.copyright)) { "Copyright did not verify after writing" } }
        edit.rating?.let { requested ->
            val expected = requested.coerceIn(0, 5).takeIf { it != 0 }
            check(current.rating == expected) { "Rating did not verify after writing" }
        }
        if (edit.keywordsToAdd.isNotEmpty()) {
            check(current.keywords.containsAll(edit.keywordsToAdd)) { "Keywords did not verify after writing" }
        }
        if (edit.setLocation != null) {
            val latLong = FloatArray(2)
            check(fresh.getLatLong(latLong)) { "Location did not verify after writing" }
            check(
                kotlin.math.abs(latLong[0] - edit.setLocation.latitude) < 0.0001 &&
                    kotlin.math.abs(latLong[1] - edit.setLocation.longitude) < 0.0001,
            ) { "Location did not verify after writing" }
        } else if (edit.clearLocation) {
            check(fresh.latLong == null) { "Location did not verify as cleared after writing" }
        }
    }

    /** `null`/blank in [edit] means "clear" ([MetadataEdit]'s own convention), so the field
     *  verifies as long as the fresh read back is also blank/absent -- not necessarily null, since
     *  a value EXIF could not represent at all ([setAsciiAttributeOrLeave]) legitimately reads
     *  back as null from EXIF while XMP (which [currentMetadataFrom] prefers) still has it. */
    private fun fieldVerifies(edited: String, actual: String?): Boolean =
        if (edited.isBlank()) actual.isNullOrBlank() else actual == edited

    /**
     * Applies [edit] across every asset in [assets], in order, collecting a per-asset outcome
     * rather than failing the whole batch on the first photo Android declines to write --
     * exactly [com.fotoxplorr.app.fileops.MediaFileOperations.renameBatch]'s own reasoning: "38
     * of 40 photos got the new copyright line" is the honest answer a batch caption/rating/
     * keyword edit can actually promise, not all-or-nothing.
     */
    suspend fun writeBatch(
        assets: List<MediaAsset>,
        edit: MetadataEdit,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): BulkMetadataOutcome = withContext(Dispatchers.IO) {
        if (assets.isEmpty() || edit.isEmpty) return@withContext BulkMetadataOutcome(emptyList(), emptyList())

        val succeeded = mutableListOf<MediaAsset>()
        val failed = mutableListOf<Pair<MediaAsset, Throwable>>()
        assets.forEachIndexed { index, asset ->
            write(asset, edit)
                .onSuccess { succeeded += asset }
                .onFailure { failed += asset to it }
            onProgress(index + 1, assets.size)
        }
        BulkMetadataOutcome(succeeded, failed)
    }
}

/**
 * The result of one [MetadataWriter.writeBatch] call, asset by asset -- same shape as
 * [com.fotoxplorr.app.fileops.BulkRenameOutcome] and the same reasoning: a `Result<Unit>` for the
 * whole batch would have to either fail all forty photos over the one Android declined, or
 * swallow that one failure into a silent success. Reporting both lists lets a caller say exactly
 * which photos need a second look.
 */
data class BulkMetadataOutcome(
    val succeeded: List<MediaAsset>,
    val failed: List<Pair<MediaAsset, Throwable>>,
) {
    val attempted: Int get() = succeeded.size + failed.size
    val allSucceeded: Boolean get() = failed.isEmpty() && succeeded.isNotEmpty()
}

/**
 * The actual read-modify-write logic, as a top-level function rather than a [MetadataWriter]
 * method: it touches only the [ExifInterface] instance it is handed, never
 * [MetadataWriter]'s `ContentResolver`, so giving it a place on that class would only have meant
 * a receiver nothing inside the function uses. That is also what makes it callable straight from
 * [MetadataWriterTest] against a real file, with no [android.content.Context] anywhere in sight --
 * the same shape [XmpPacketTest] already exercises [XmpPacket] with.
 */
internal fun applyMetadataEdit(exif: ExifInterface, edit: MetadataEdit) {
    // [MetadataWriter.write] also checks this, to skip opening the file descriptor at all for a
    // no-op edit -- a different, complementary saving (that open is the permission-gated,
    // possibly-consent-prompting part). This check is the one that actually matters for
    // correctness: nothing here should touch bytes it has nothing to change.
    if (edit.isEmpty) return
    applyMetadataToExif(exif, edit)

    // Only when there is an existing packet worth preserving OR nothing at all -- see
    // readXmpAttribute's own doc for why a packet that failed to parse is neither of those and
    // must not reach applyToXmp, which would otherwise "successfully" overwrite it with a
    // packet missing everything this app has no model for.
    readXmpAttribute(exif)?.let { packet ->
        applyToXmp(packet, edit)
        exif.setAttribute(ExifInterface.TAG_XMP, packet.serialize())
    }

    exif.saveAttributes()
}

private fun applyMetadataToExif(exif: ExifInterface, edit: MetadataEdit) {
    edit.caption?.let { setAsciiAttributeOrLeave(exif, ExifInterface.TAG_IMAGE_DESCRIPTION, it) }
    edit.creator?.let { setAsciiAttributeOrLeave(exif, ExifInterface.TAG_ARTIST, it) }
    edit.copyright?.let { setAsciiAttributeOrLeave(exif, ExifInterface.TAG_COPYRIGHT, it) }

    edit.setLocation?.let { exif.setLatLong(it.latitude, it.longitude) }
    // setLocation wins over clearLocation if a caller somehow asks for both in one edit -- see
    // MetadataEdit's own doc on why the two are separate, mutually exclusive actions rather than
    // one nullable coordinate; "set" is the more specific, more recent intent.
    if (edit.clearLocation && edit.setLocation == null) clearGpsTags(exif)
}

/**
 * Writes [value] to an EXIF string tag, transliterating what it safely can and refusing to write
 * the rest -- caught by a real test, not spotted by inspection: `ExifInterface.setAttribute` on
 * these tags silently mangled "© 2026 Jane Doe" into "? 2026 Jane Doe". EXIF's classic string
 * tags are TIFF type-2 ASCII, a 7-bit format with no way to represent "©", accented names, or an
 * em dash at all -- there is no encoding fix for that, only a choice about what to do when a
 * professional's actual text does not fit it.
 *
 * The choice: common typography gets transliterated to its ASCII equivalent (© to "(c)", curly
 * quotes to straight ones) since that is a faithful, readable substitution, not a loss. Anything
 * still non-ASCII after that -- a name like "José García", a "→" -- is left OUT of this EXIF tag
 * entirely rather than written as "Jos? Garc?a". A silently corrupted copy sitting next to the
 * correct one in the same file is worse than an absent one: XMP (`applyToXmp`) carries the exact,
 * un-mangled Unicode text regardless, which is the field a reader should trust when the two ever
 * disagree -- see [currentMetadataFrom]'s own doc for why reading already prefers XMP for exactly
 * this reason.
 */
private fun setAsciiAttributeOrLeave(exif: ExifInterface, tag: String, value: String) {
    if (value.isBlank()) {
        exif.setAttribute(tag, null)
        return
    }
    val transliterated = transliterateToAscii(value)
    if (transliterated.all { it.code < 128 }) exif.setAttribute(tag, transliterated)
}

private val ASCII_SUBSTITUTIONS = mapOf(
    '©' to "(c)", '®' to "(r)", '™' to "(tm)",
    '‘' to "'", '’' to "'", // curly single quotes
    '“' to "\"", '”' to "\"", // curly double quotes
    '–' to "-", '—' to "-", // en dash, em dash
    '…' to "...", // ellipsis
)

private fun transliterateToAscii(text: String): String = buildString {
    text.forEach { char -> append(ASCII_SUBSTITUTIONS[char] ?: char) }
}

/** The exact GPS tag set `com.fotoxplorr.app.share.SharePreparer.stripCommonExif` already strips
 *  for share-cache copies -- reused here rather than a second, possibly-incomplete list, since
 *  both call sites mean the identical thing: this file's GPS trail, gone. */
private fun clearGpsTags(exif: ExifInterface) {
    listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    ).forEach { exif.setAttribute(it, null) }
}
