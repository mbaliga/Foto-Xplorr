package com.fotoxplorr.app.metadata

import android.content.ContentResolver
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * inventing a fresh, empty packet that would discard everything already there. See [readExistingXmp].
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
 */
class MetadataWriter(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * Applies [edit] to [asset]'s own file. A no-op edit ([MetadataEdit.isEmpty]) is a no-op
     * write too -- opening a file for "rw" access and re-saving it unchanged is not free (it is
     * still a full container rewrite on some formats) and buys nothing for a caller that, say,
     * always calls this from a save button regardless of whether anything was actually typed.
     */
    suspend fun write(asset: MediaAsset, edit: MetadataEdit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (edit.isEmpty) return@runCatching
            resolver.openFileDescriptor(asset.contentUri, "rw")?.use { descriptor ->
                applyMetadataEdit(ExifInterface(descriptor.fileDescriptor), edit)
            } ?: throw IOException("Could not open ${asset.displayName} for writing")
        }
    }

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
    // readExistingXmp's own doc for why a packet that failed to parse is neither of those and
    // must not reach applyToXmp, which would otherwise "successfully" overwrite it with a
    // packet missing everything this app has no model for.
    readExistingXmp(exif)?.let { packet ->
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

/**
 * The file's existing XMP, ready to be mutated -- or null when it is not safe to touch.
 *
 * "Not safe to touch" covers two different states on purpose:
 *  - no XMP at all ([ExifInterface.TAG_XMP] blank or absent), which is NOT this: a blank slate is
 *    exactly what [XmpPacket.empty] is for, and this returns that fresh packet for it.
 *  - existing XMP this app's parser cannot understand, which very much IS this: [XmpPacket.parse]
 *    returning null is a deliberate signal (see its own doc) to leave those bytes exactly as they
 *    are rather than let [applyMetadataEdit] "successfully" replace them with a packet this app
 *    understands but that has lost everything it did not.
 */
private fun readExistingXmp(exif: ExifInterface): XmpPacket? {
    val existing = exif.getAttribute(ExifInterface.TAG_XMP)
    return if (existing.isNullOrBlank()) XmpPacket.empty() else XmpPacket.parse(existing)
}
