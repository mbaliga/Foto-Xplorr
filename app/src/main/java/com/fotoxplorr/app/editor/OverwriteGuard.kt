package com.fotoxplorr.app.editor

import android.content.Context
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.AnimationSniffer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

// ---------------------------------------------------------------------------
// Backup and restore around the truncating write
// ---------------------------------------------------------------------------

/**
 * The original file as [replaceWithRestore] needs to see it. A seam rather than a content resolver
 * so the failure paths can be driven from a plain unit test.
 */
internal interface OriginalFile {
    /**
     * The original's CURRENT bytes exactly as they are on disk, never a redacted view: what a
     * restore has to be able to put back, and what the written result is checked against. Null
     * (or a throw) when they cannot be read that way.
     */
    fun openForRead(): InputStream?

    /** Opens the original for writing, truncating it. Once this returns a stream the original's
     *  old bytes are gone; null or a throw means it was NOT opened and nothing changed. */
    fun openForTruncatingWrite(): OutputStream?
}

/** Room that must remain free beyond the backup itself, so a nearly full cache does not make the
 *  safety copy the thing that fills the disk. */
internal const val BACKUP_SLACK_BYTES = 1L * 1024 * 1024

/**
 * Writes [staged] over [original] with a way back.
 *
 * MediaStore has no atomic replace for an existing row, so the write has to truncate the file in
 * place, and any failure after that point (disk full, the process interrupted, the provider
 * erroring, a result that does not read back as written) would otherwise leave a damaged original
 * that the old code then reported as "Android did not allow replacing". So, in order:
 *
 *  1. Copy the original's current bytes into [backupDir] (refusing with
 *     [OverwriteException.Refused] and the original untouched if there is no room for that, or it
 *     cannot be read as it is).
 *  2. Open it for writing and copy [staged] in. A failure to OPEN is rethrown as it is: nothing
 *     changed, and the caller treats a refused open as the system declining write access.
 *  3. Run [verifyWritten] (which throws if what is now on disk is not [staged]).
 *  4. On any failure in 2 or 3, write the backup back, and throw [OverwriteException.FailedAfterWrite]
 *     saying whether that worked. If it did not, the backup is moved to [recoveryDir] and kept.
 *  5. On success, delete the backup.
 *
 * This is best effort, not atomic: a crash between the truncate and the restore still leaves the
 * damaged file, with the backup left in [backupDir] (a cache the system may clear).
 *
 * @param originalSizeBytes the original's recorded size, for the room check; 0 skips the check
 *   and leaves a full disk to fail the backup copy itself.
 */
internal fun replaceWithRestore(
    original: OriginalFile,
    staged: File,
    backupDir: File,
    recoveryDir: File,
    label: String,
    originalSizeBytes: Long = 0L,
    usableSpace: () -> Long = { backupDir.usableSpace },
    verifyWritten: () -> Unit = {},
) {
    backupDir.mkdirs()
    if (originalSizeBytes > 0 && usableSpace() < originalSizeBytes + BACKUP_SLACK_BYTES) {
        throw OverwriteException.Refused(
            OverwriteException.Refused.Reason.BACKUP_FAILED,
            "There is not enough free space to keep a safety copy of the original while replacing it, " +
                "so it was left as it is. Free some space, or save a copy.",
        )
    }
    val backup = File.createTempFile("backup-", ".bin", backupDir)
    try {
        val input = original.openForRead() ?: throw IOException("The original could not be read")
        input.use { stream -> backup.outputStream().use { stream.copyTo(it) } }
        if (originalSizeBytes > 0 && backup.length() == 0L) throw IOException("The safety copy came out empty")
    } catch (e: Exception) {
        backup.delete()
        throw OverwriteException.Refused(
            OverwriteException.Refused.Reason.BACKUP_FAILED,
            "A safety copy of the original could not be made first, so it was left as it is. Save a copy instead.",
            cause = e,
        )
    }

    val output = try {
        original.openForTruncatingWrite() ?: throw IOException("Could not open the original photo for writing")
    } catch (t: Throwable) {
        backup.delete()
        throw t
    }

    // From here the original's old bytes are gone.
    try {
        output.use { out -> staged.inputStream().use { it.copyTo(out) } }
        verifyWritten()
    } catch (t: Throwable) {
        val restored = restoreFrom(backup, original)
        val kept = if (restored) null else keepForRecovery(backup, recoveryDir, label)
        if (restored) backup.delete()
        throw OverwriteException.FailedAfterWrite(
            restored = restored,
            recoveryFile = kept,
            message = if (restored) {
                "Replacing the photo failed partway, so the original was put back exactly as it was."
            } else {
                "Replacing the photo failed and the original could not be put back. A safety copy of it is " +
                    "kept inside Fotoz: save a copy of your edit now, and do not clear the app's storage."
            },
            cause = t,
        )
    }
    backup.delete()
}

/** Writes [backup] back over [original]; true only when it was written and reads back the same size. */
private fun restoreFrom(backup: File, original: OriginalFile): Boolean = try {
    val out = original.openForTruncatingWrite()
    if (out == null) {
        false
    } else {
        out.use { stream -> backup.inputStream().use { it.copyTo(stream) } }
        original.openForRead()?.use { it.countBytes() } == backup.length()
    }
} catch (t: Throwable) {
    false
}

/** Moves [backup] under [recoveryDir] (copying if a rename across filesystems fails) and returns
 *  where it is now, or [backup] itself if even that failed. */
private fun keepForRecovery(backup: File, recoveryDir: File, label: String): File {
    val safeLabel = label.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(60).ifEmpty { "photo" }
    return try {
        recoveryDir.mkdirs()
        val kept = File(recoveryDir, "$safeLabel.${System.currentTimeMillis()}.bak")
        if (!backup.renameTo(kept)) {
            backup.copyTo(kept, overwrite = true)
            backup.delete()
        }
        kept
    } catch (t: Throwable) {
        backup
    }
}

private fun InputStream.countBytes(): Long {
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return total
        total += n
    }
}

/** True when [a] and [b] hold exactly the same bytes. */
internal fun sameBytes(a: InputStream, b: InputStream): Boolean {
    val bufferA = ByteArray(8 * 1024)
    val bufferB = ByteArray(8 * 1024)
    while (true) {
        val readA = a.readFully(bufferA)
        val readB = b.readFully(bufferB)
        if (readA != readB) return false
        if (readA == 0) return true
        if (!bufferA.contentEquals(bufferB, readA)) return false
    }
}

/** Fills [buffer] as far as the stream allows and returns how many bytes it holds (0 at the end). */
private fun InputStream.readFully(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

private fun ByteArray.contentEquals(other: ByteArray, length: Int): Boolean {
    for (i in 0 until length) if (this[i] != other[i]) return false
    return true
}
