package com.fotoxplorr.app.metadata

import android.content.ContentResolver
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.metadata.MetadataStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether [ExifInterface][androidx.exifinterface.media.ExifInterface] can write metadata into
 * [asset]'s own bytes at all -- JPEG, PNG or WebP, the three formats it documents write support
 * for (everything else, HEIC/HEIF/AVIF/DNG/RAW/TIFF chief among them, either throws or silently
 * no-ops on `saveAttributes()`). P0-08's whole point for this function: check this BEFORE opening
 * a system consent screen for a write that would fail anyway, rather than asking for consent and
 * only then failing -- see [MetadataWriter.write]'s own doc for where the actual write-time
 * refusal lives; this is the pre-flight the UI layer (the detail room's edit controls, a bulk
 * metadata edit, "Set/Clear location"'s file variant) calls first.
 *
 * Sniffed by MAGIC BYTES -- reusing [MetadataStripper.isJpeg]/`isPng`/`isWebp`, the exact same
 * detectors an image share already trusts for a related but distinct decision (what this app can
 * strip location from) -- not by [MediaAsset.mimeType] or a file extension, either of which can
 * simply be wrong (a misnamed file, a MIME type MediaProvider inferred incorrectly) in a way the
 * actual bytes on disk cannot. MIME type is used only as a FALLBACK, when reading the header bytes
 * itself fails (a transient I/O error, a revoked grant): a caller still gets an answer rather than
 * a crash, even a slightly less certain one.
 */
suspend fun isMetadataWritable(resolver: ContentResolver, asset: MediaAsset): Boolean = withContext(Dispatchers.IO) {
    val header = runCatching {
        resolver.openInputStream(asset.contentUri)?.use(::readHeaderBytes)
    }.getOrNull()

    if (header != null) {
        MetadataStripper.isJpeg(header) || MetadataStripper.isPng(header) || MetadataStripper.isWebp(header)
    } else {
        asset.mimeType in WRITABLE_MIME_TYPES
    }
}

/** Enough to cover WebP's 12-byte `RIFF....WEBP` signature (the longest of the three) with a
 *  little headroom, without reading more of a potentially large file than this needs to. */
private const val WRITABLE_FORMAT_HEADER_BYTES = 16

private val WRITABLE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

/** [InputStream.readNBytes] needs API 33; this app's minSdk is 26, so a plain loop reads up to
 *  [WRITABLE_FORMAT_HEADER_BYTES], stopping early (and returning a shorter array) at end of
 *  stream -- `read(ByteArray)` is legally allowed to return fewer bytes than asked for even mid-
 *  stream, so a single call is not enough. */
private fun readHeaderBytes(input: java.io.InputStream): ByteArray {
    val buffer = ByteArray(WRITABLE_FORMAT_HEADER_BYTES)
    var total = 0
    while (total < buffer.size) {
        val read = input.read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return if (total == buffer.size) buffer else buffer.copyOf(total)
}
