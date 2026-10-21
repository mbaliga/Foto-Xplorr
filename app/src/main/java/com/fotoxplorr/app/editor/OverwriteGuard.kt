package com.fotoxplorr.app.editor

import android.content.Context
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.AnimationSniffer
import java.io.File
import java.io.InputStream

/**
 * What [EditedCopyWriter.overwrite] reports when it does not replace the original, as the
 * `Throwable` inside its `Result.failure`.
 *
 * The split is the one the UI needs: [Refused] means the original's bytes were never opened for
 * writing, so "your photo is untouched" is true and "save a copy instead" is the right nudge;
 * [FailedAfterWrite] means the original WAS opened and truncated, so the message must say whether
 * it was put back. Any other failure from `overwrite` is a plain exception thrown before the
 * original was opened (an encode that failed, or the system declining write access when it was
 * asked for): it also left the original untouched, and the caller treats it as it always did.
 *
 * Every [message] is written to be shown to the user as it is.
 */
sealed class OverwriteException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The original was left exactly as it was; [reason] says why replacing it was refused. */
    class Refused(
        val reason: Reason,
        message: String,
        /** The things replacing would have destroyed, when [reason] is [Reason.WOULD_DESTROY_CONTENT]. */
        val hazards: Set<OverwriteHazard> = emptySet(),
        cause: Throwable? = null,
    ) : OverwriteException(message, cause) {
        enum class Reason {
            /** The original's location or camera metadata could not be read or carried over. */
            METADATA_WOULD_BE_LOST,

            /** The file is an animation, an Ultra HDR image or a Motion Photo. */
            WOULD_DESTROY_CONTENT,

            /** The file's format has no in-place path. */
            FORMAT_UNSUPPORTED,

            /** No safety copy of the original could be made first (no room, or it was unreadable). */
            BACKUP_FAILED,
        }
    }

    /**
     * The original was opened for writing and the write (or the check after it) failed.
     * [restored] is true when its previous bytes were put back, false when that failed too; then
     * [recoveryFile] is the app-private safety copy still holding them, when one could be kept.
     */
    class FailedAfterWrite(
        val restored: Boolean,
        val recoveryFile: File?,
        message: String,
        cause: Throwable? = null,
    ) : OverwriteException(message, cause)
}

/** Something in a file that re-encoding its first frame's pixels would destroy. */
enum class OverwriteHazard(val label: String) {
    /** An animated WebP or APNG: only the first frame would survive. */
    ANIMATION("animation"),

    /** An Ultra HDR JPEG: the gain map that carries the HDR would be dropped. */
    ULTRA_HDR("HDR gain map"),

    /** A Motion Photo: the embedded video would be dropped. */
    MOTION_PHOTO("motion video"),
}

/** "this photo's animation and motion video", for a message that says what replacing would lose. */
internal fun Set<OverwriteHazard>.describe(): String {
    val labels = OverwriteHazard.entries.filter { it in this }.map { it.label }
    return when (labels.size) {
        0 -> "nothing"
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }
}

/** The one-line reason shown when replacing the original is declined because of [this]. */
internal fun Set<OverwriteHazard>.refusalMessage(): String =
    "Replacing the original would destroy this photo's ${describe()}. Save a copy to keep the original as it is."

/** [OverwriteException.Refused] for a metadata copy that was not complete, or null when it was. */
internal fun overwriteMetadataRefusal(copy: MetadataCopy): OverwriteException.Refused? = when (copy) {
    // HEIC is never overwritten, so SKIPPED_HEIC cannot reach here; it is not a loss either way.
    MetadataCopy.COPIED, MetadataCopy.SKIPPED_HEIC -> null
    MetadataCopy.REDACTED -> OverwriteException.Refused(
        OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST,
        "Replacing would lose this photo's location and camera details. " +
            "Allow photo-location access in Settings, or save a copy.",
    )
    MetadataCopy.SOURCE_UNREADABLE, MetadataCopy.FAILED -> OverwriteException.Refused(
        OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST,
        "This photo's metadata could not be carried over, so replacing it would lose it. Save a copy instead.",
    )
}

/**
 * The hazards [mime] data hides in [head] (the file's first bytes) and [tail] (its last), as far as
 * they can be told from those windows. Pure and conservative the other way round from a gate: it
 * names only what it positively recognises, so a file it cannot classify is NOT a hazard, which
 * makes the guard fail open for formats nobody taught it. It reduces the risk, it does not close it.
 *
 *  - **Animation**: [AnimationSniffer] on the real bytes (an animated WebP's `VP8X` flag, an APNG's
 *    `acTL` chunk) rather than the possibly stale [com.fotoxplorr.app.formats.AnimationIndex].
 *  - **Ultra HDR** (JPEG): the gain-map XMP namespace, `hdrgm:` properties, or a container item
 *    whose semantic is `GainMap`, all of which live in the XMP near the start of the file.
 *  - **Motion Photo** (JPEG): Google's `GCamera:MotionPhoto` / `GCamera:MicroVideo` XMP flags or a
 *    container item with the `MotionPhoto` semantic in [head]; in [tail], Samsung's
 *    `MotionPhoto_Data` trailer tag (from memory of its layout, not checked against a device) or an
 *    MP4 `ftyp` box with a common video brand.
 */
internal fun overwriteHazards(head: ByteArray, tail: ByteArray, mime: String): Set<OverwriteHazard> {
    val found = mutableSetOf<OverwriteHazard>()
    if (AnimationSniffer.sniff(head, mime) == true) found += OverwriteHazard.ANIMATION
    if (mime.equals("image/jpeg", ignoreCase = true)) {
        if (HDR_GAIN_MAP_MARKERS.any { head.containsAscii(it) }) found += OverwriteHazard.ULTRA_HDR
        // The trailer markers are looked for in the head too: a file shorter than the head
        // window has no separate tail, and a stray match in the head costs nothing.
        val trailer = { needle: String -> tail.containsAscii(needle) || head.containsAscii(needle) }
        if (MOTION_PHOTO_HEAD_MARKERS.any { head.containsAscii(it) } ||
            MOTION_PHOTO_TAIL_MARKERS.any(trailer) ||
            VIDEO_BRANDS.any { trailer("ftyp$it") }
        ) {
            found += OverwriteHazard.MOTION_PHOTO
        }
    }
    return found
}

private val HDR_GAIN_MAP_MARKERS = listOf("ns.adobe.com/hdr-gain-map", "hdrgm:", "Item:Semantic=\"GainMap\"")
private val MOTION_PHOTO_HEAD_MARKERS =
    listOf("GCamera:MotionPhoto", "GCamera:MicroVideo", "Item:Semantic=\"MotionPhoto\"")
private val MOTION_PHOTO_TAIL_MARKERS = listOf("MotionPhoto_Data")
private val VIDEO_BRANDS = listOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "qt  ", "3gp4", "3gp5")

/** How much of the start and end of a file [readOverwriteHazards] looks at. The XMP packet that
 *  names a gain map or a Motion Photo sits right after the first JPEG markers. */
internal const val HAZARD_HEAD_BYTES = 256 * 1024
internal const val HAZARD_TAIL_BYTES = 64 * 1024

/**
 * Reads [asset]'s first [HAZARD_HEAD_BYTES] and last [HAZARD_TAIL_BYTES] through the content
 * resolver and runs [overwriteHazards] on them. Never throws and never blocks the caller's thread
 * longer than one pass over the file: an unreadable asset is reported as no hazards, because the
 * refusal is a safety net on top of the write path's own checks, not a gate that may itself fail
 * the save. Call it off the main thread.
 */
internal fun Context.readOverwriteHazards(asset: MediaAsset): Set<OverwriteHazard> = try {
    val (head, tail) = contentResolver.openInputStream(asset.contentUri)?.use { it.readHeadAndTail() }
        ?: (ByteArray(0) to ByteArray(0))
    overwriteHazards(head, tail, asset.mimeType)
} catch (e: Exception) {
    emptySet()
}

/**
 * The first [HAZARD_HEAD_BYTES] and last [HAZARD_TAIL_BYTES] of this stream, in one sequential
 * pass: the tail is a ring buffer, so a stream that cannot seek and a file whose recorded size is
 * stale are both exact. The tail never overlaps the head's bytes.
 */
internal fun InputStream.readHeadAndTail(
    headBytes: Int = HAZARD_HEAD_BYTES,
    tailBytes: Int = HAZARD_TAIL_BYTES,
): Pair<ByteArray, ByteArray> {
    val head = ByteArray(headBytes)
    var headRead = 0
    while (headRead < headBytes) {
        val n = read(head, headRead, headBytes - headRead)
        if (n < 0) break
        headRead += n
    }
    val headOut = if (headRead == headBytes) head else head.copyOf(headRead)
    if (headRead < headBytes) return headOut to ByteArray(0)

    val ring = ByteArray(tailBytes)
    var written = 0L
    val chunk = ByteArray(8 * 1024)
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        for (i in 0 until n) {
            ring[(written % tailBytes).toInt()] = chunk[i]
            written++
        }
    }
    val tailLength = minOf(written, tailBytes.toLong()).toInt()
    val tailOut = ByteArray(tailLength)
    val start = (written - tailLength)
    for (i in 0 until tailLength) tailOut[i] = ring[((start + i) % tailBytes).toInt()]
    return headOut to tailOut
}

/** Naive byte search for a short ASCII [needle]; the windows are at most a few hundred KiB. */
private fun ByteArray.containsAscii(needle: String): Boolean {
    val n = needle.length
    if (n == 0 || size < n) return false
    val first = needle[0].code.toByte()
    var i = 0
    val last = size - n
    outer@ while (i <= last) {
        if (this[i] == first) {
            for (j in 1 until n) {
                if (this[i + j] != needle[j].code.toByte()) {
                    i++
                    continue@outer
                }
            }
            return true
        }
        i++
    }
    return false
}
