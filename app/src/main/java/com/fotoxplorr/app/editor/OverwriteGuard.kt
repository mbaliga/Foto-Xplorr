package com.fotoxplorr.app.editor

import android.content.Context
import android.os.Build
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import com.fotoxplorr.core.formats.AlphaSniffer
import com.fotoxplorr.core.formats.AnimationSniffer
import java.io.File
import java.io.FileOutputStream
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

            /** No safety copy of the original could be made first (no room, or it was unreadable),
             *  or it could not be read to check that replacing it is safe. */
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

    /** A depth map or other embedded picture (a multi-picture JPEG's extra images, a container
     *  item that is not the primary image): re-encoding the first image would drop it. */
    EXTRA_IMAGES("depth map or extra embedded images"),

    /** Transparency the target encoding cannot hold (a WebP rewritten as lossy flattens it onto
     *  white, and that cannot be undone). */
    TRANSPARENCY("transparency"),

    /** A lossless WebP, which the lossy re-encode would degrade for good. */
    LOSSLESS("lossless quality"),
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
    MetadataCopy.PARTIAL -> OverwriteException.Refused(
        OverwriteException.Refused.Reason.METADATA_WOULD_BE_LOST,
        "Some of this photo's embedded details (an XMP packet, IPTC captions or extended XMP) cannot be " +
            "carried over, so replacing it would lose them. Save a copy instead.",
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
 *  - **Transparency** and **lossless** (WebP/PNG): [AlphaSniffer] says the source declares alpha
 *    and the target encoding for [sdkInt] would flatten it ([encodesAlphaOn]); a lossless WebP is
 *    always re-encoded lossy. Both are irreversible once the original is replaced.
 *  - **Ultra HDR** (JPEG): the gain-map XMP namespace, `hdrgm:` properties, a container item whose
 *    semantic is `GainMap`, or Apple's `hdrgainmap` auxiliary-image URN, all of which live in the
 *    XMP near the start of the file.
 *  - **Motion Photo** (JPEG): Google's `GCamera:MotionPhoto` / `GCamera:MicroVideo` XMP flags or a
 *    container item with the `MotionPhoto` semantic in [head]; in [tail], Samsung's
 *    `MotionPhoto_Data` trailer tag (from memory of its layout, not checked against a device) or an
 *    MP4 `ftyp` box with a common video brand.
 *  - **Extra images** (JPEG), when none of the above already explains it: a Multi-Picture Format
 *    block announcing more than one image, a container item with any semantic other than
 *    `Primary`, or a depth map (`GDepth`).
 */
internal fun overwriteHazards(
    head: ByteArray,
    tail: ByteArray,
    mime: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Set<OverwriteHazard> {
    val found = mutableSetOf<OverwriteHazard>()
    if (AnimationSniffer.sniff(head, mime) == true) found += OverwriteHazard.ANIMATION
    val target = overwriteFormatFor(mime)
    if (target != null && !target.encodesAlphaOn(sdkInt) && AlphaSniffer.sniff(head, mime) == true) {
        found += OverwriteHazard.TRANSPARENCY
    }
    if (mime.equals("image/webp", ignoreCase = true) && webpIsLossless(head)) found += OverwriteHazard.LOSSLESS
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
        // Container items are classified by name, so a gain map or motion video a vendor spelled
        // differently from the markers above still lands under its own hazard, and anything else
        // that is not the primary picture is an extra image.
        val semantics = containerSemantics(head)
        if ("gainmap" in semantics) found += OverwriteHazard.ULTRA_HDR
        if ("motionphoto" in semantics) found += OverwriteHazard.MOTION_PHOTO
        val otherItems = semantics.any { it != "primary" && it != "gainmap" && it != "motionphoto" }
        if (otherItems || EXTRA_IMAGE_MARKERS.any { head.containsAscii(it) } ||
            (found.isEmpty() && mpfImageCount(head) > 1)
        ) {
            found += OverwriteHazard.EXTRA_IMAGES
        }
    }
    return found
}

private val HDR_GAIN_MAP_MARKERS = listOf(
    "ns.adobe.com/hdr-gain-map", "hdrgm:", "Item:Semantic=\"GainMap\"", "urn:com:apple:photo:2020:aux:hdrgainmap",
)
private val MOTION_PHOTO_HEAD_MARKERS =
    listOf("GCamera:MotionPhoto", "GCamera:MicroVideo", "Item:Semantic=\"MotionPhoto\"")
private val MOTION_PHOTO_TAIL_MARKERS = listOf("MotionPhoto_Data")
private val VIDEO_BRANDS = listOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "qt  ", "3gp4", "3gp5")
private val EXTRA_IMAGE_MARKERS = listOf("ns.google.com/photos/1.0/depthmap", "GDepth:")
private val ITEM_SEMANTIC = Regex("""Item:Semantic\s*=\s*["']([^"']*)["']""")

/** The lower-cased `Item:Semantic` value of every Container item named in the XMP in [head]. */
private fun containerSemantics(head: ByteArray): Set<String> {
    if (!head.containsAscii("Item:Semantic")) return emptySet()
    return ITEM_SEMANTIC.findAll(String(head, Charsets.ISO_8859_1)).map { it.groupValues[1].trim().lowercase() }.toSet()
}

/** The image count a JPEG's Multi-Picture Format block (APP2 `MPF\0`) announces, or 0 when there is
 *  none or it cannot be read from [head]. The count is TIFF tag 0xB001 of the block's first IFD. */
private fun mpfImageCount(head: ByteArray): Int {
    val mpf = head.indexOfAscii("MPF\u0000")
    if (mpf < 0) return 0
    val tiff = mpf + 4
    if (tiff + 8 > head.size) return 0
    val little = head[tiff] == 'I'.code.toByte() && head[tiff + 1] == 'I'.code.toByte()
    val big = head[tiff] == 'M'.code.toByte() && head[tiff + 1] == 'M'.code.toByte()
    if (!little && !big) return 0
    fun u16(at: Int): Int = if (little) {
        (head[at].toInt() and 0xFF) or ((head[at + 1].toInt() and 0xFF) shl 8)
    } else {
        ((head[at].toInt() and 0xFF) shl 8) or (head[at + 1].toInt() and 0xFF)
    }
    fun u32(at: Int): Long = if (little) {
        (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
    } else {
        (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    }
    val ifd = tiff + u32(tiff + 4).coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
    if (ifd < 0 || ifd + 2 > head.size) return 0
    val entries = u16(ifd)
    for (i in 0 until entries) {
        val entry = ifd + 2 + i * 12
        if (entry + 12 > head.size) return 0
        if (u16(entry) == MPF_NUMBER_OF_IMAGES_TAG) return u32(entry + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return 0
}

private const val MPF_NUMBER_OF_IMAGES_TAG = 0xB001

/** Whether the WebP in [head] stores its picture losslessly (`VP8L`), looking past a `VP8X` header. */
private fun webpIsLossless(head: ByteArray): Boolean {
    if (head.size < 20 || head.indexOfAscii("RIFF") != 0 || head.indexOfAscii("WEBP") != 8) return false
    var pos = 12
    while (pos + 8 <= head.size) {
        val type = String(head, pos, 4, Charsets.ISO_8859_1)
        if (type == "VP8L") return true
        if (type == "VP8 " || type == "ANMF") return false
        val size = (head[pos + 4].toInt() and 0xFF) or ((head[pos + 5].toInt() and 0xFF) shl 8) or
            ((head[pos + 6].toInt() and 0xFF) shl 16) or ((head[pos + 7].toInt() and 0xFF) shl 24)
        if (size < 0) return false
        pos += 8 + size + (size and 1)
    }
    return false
}

/** How much of the start and end of a file [readOverwriteHazards] looks at. The XMP packet that
 *  names a gain map or a Motion Photo sits right after the first JPEG markers. */
internal const val HAZARD_HEAD_BYTES = 256 * 1024
internal const val HAZARD_TAIL_BYTES = 64 * 1024

/**
 * What [readOverwriteHazards] found. [unreadable] is its own state, not "no hazards": a file whose
 * start could not be read cannot be vouched for, so it blocks a replace like a hazard does.
 */
internal class HazardScan(val hazards: Set<OverwriteHazard>, val unreadable: Boolean = false) {
    /** True when replacing the original must be refused (or the option not offered). */
    val blocksOverwrite: Boolean get() = unreadable || hazards.isNotEmpty()

    companion object {
        val CLEAR = HazardScan(emptySet())
    }
}

/**
 * Reads [asset]'s first [HAZARD_HEAD_BYTES] (and, for a JPEG only, last [HAZARD_TAIL_BYTES]) and runs
 * [overwriteHazards] on them. The bytes come through the same [uriForLocationRead] /
 * [openForLocationRead] path the backup and the read-back use, so the guard judges the file the
 * write path will actually see. A format with no in-place path is not read at all (it is refused
 * before this matters). Never throws and never blocks the caller's thread longer than one pass over
 * the file; an unreadable asset is reported as [HazardScan.unreadable] rather than as clean. Call it
 * off the main thread.
 */
internal fun Context.readOverwriteHazards(asset: MediaAsset): HazardScan {
    if (overwriteFormatFor(asset.mimeType) == null) return HazardScan.CLEAR
    val isJpeg = asset.mimeType.equals("image/jpeg", ignoreCase = true)
    return try {
        val requested = uriForLocationRead(asset.contentUri)
        val opened = openForLocationRead(requested, asset.contentUri) { uri -> contentResolver.openInputStream(uri) }
            ?: return HazardScan(emptySet(), unreadable = true)
        val (head, tail) = opened.first.use { it.readHeadAndTail(tailBytes = if (isJpeg) HAZARD_TAIL_BYTES else 0) }
        if (head.isEmpty() && asset.sizeBytes > 0) return HazardScan(emptySet(), unreadable = true)
        HazardScan(overwriteHazards(head, tail, asset.mimeType))
    } catch (e: Exception) {
        HazardScan(emptySet(), unreadable = true)
    }
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
    if (headRead < headBytes || tailBytes <= 0) return headOut to ByteArray(0)

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
private fun ByteArray.containsAscii(needle: String): Boolean = indexOfAscii(needle) >= 0

/** Index of the first occurrence of the short ASCII [needle], or -1. */
private fun ByteArray.indexOfAscii(needle: String): Int {
    val n = needle.length
    if (n == 0 || size < n) return -1
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
            return i
        }
        i++
    }
    return -1
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

    /** The original's size on disk right now, asked of the file itself rather than a recorded
     *  value that may be stale; -1 when it cannot be told. */
    fun currentSizeBytes(): Long = -1L
}

/** Room that must remain free beyond the backup itself, so a nearly full disk does not make the
 *  safety copy the thing that fills it. */
internal const val BACKUP_SLACK_BYTES = 1L * 1024 * 1024

/**
 * Where the safety copies live: under the app's private `filesDir`, not the cache, because the
 * system clears the cache exactly when storage is tight and a copy of the user's only original
 * must outlive that. A copy lands here as `backup-*.bin` while a replace is in flight and is
 * deleted when the replace verifies; one that survives (the process died, or the file could not
 * be put back) is the only intact copy of a truncated original, so it is kept for
 * [ORPHAN_MAX_AGE_MS] by [sweepOrphans] before it is let go.
 */
object OverwriteRecovery {
    private const val DIR_NAME = "overwrite-recovery"

    /** How long an orphaned in-flight safety copy is kept before [sweepOrphans] deletes it. */
    const val ORPHAN_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * Deletes the in-flight safety copies in [dir] that are older than [maxAgeMillis] and returns
     * every file that remains there, so the caller can tell the user a copy of a photo is still
     * kept. Call it once at app start, off the main thread. Cheap and bounded: one non-recursive
     * listing, at most [MAX_SWEPT_FILES] entries acted on, nothing read, and it never throws. The
     * `.bak` files a failed restore leaves are never deleted here: the user is told about them.
     */
    fun sweepOrphans(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = ORPHAN_MAX_AGE_MS,
    ): List<File> = sweepOrphanBackups(dir(context), nowMillis, maxAgeMillis)
}

internal const val MAX_SWEPT_FILES = 500

internal fun sweepOrphanBackups(dir: File, nowMillis: Long, maxAgeMillis: Long): List<File> = try {
    val remaining = mutableListOf<File>()
    dir.listFiles().orEmpty().forEachIndexed { index, file ->
        // Past the bound a file is left alone (and reported as remaining) for a later start.
        val orphan = index < MAX_SWEPT_FILES && file.isFile &&
            file.name.startsWith("backup-") && file.name.endsWith(".bin")
        val old = nowMillis - file.lastModified() > maxAgeMillis
        if (!(orphan && old && file.delete())) remaining += file
    }
    remaining
} catch (e: Exception) {
    emptyList()
}

/**
 * Writes [staged] over [original] with a way back.
 *
 * MediaStore has no atomic replace for an existing row, so the write has to truncate the file in
 * place, and any failure after that point (disk full, the process interrupted, the provider
 * erroring, a result that does not read back as written) would otherwise leave a damaged original
 * that the old code then reported as "Android did not allow replacing". So, in order:
 *
 *  1. Copy the original's current bytes into [backupDir] and flush them to storage (refusing with
 *     [OverwriteException.Refused] and the original untouched if there is no room for that, it
 *     cannot be read as it is, or the copy is not the size the original is right now).
 *  2. Open it for writing and copy [staged] in, then flush that to storage. A failure to OPEN is
 *     rethrown as it is: nothing changed, and the caller treats a refused open as the system
 *     declining write access.
 *  3. Run [verifyWritten] (which throws if what is now on disk is not [staged]).
 *  4. On any failure in 2 or 3, write the backup back, and throw [OverwriteException.FailedAfterWrite]
 *     saying whether that worked. If it did not, the backup is moved to [recoveryDir] and kept.
 *  5. Only after 3 passed and the write reached storage, delete the backup. If the flush could not
 *     be confirmed the backup stays for [OverwriteRecovery.sweepOrphans] to age out.
 *
 * This is best effort, not atomic: a crash between the truncate and the restore still leaves the
 * damaged file, with the backup left in [backupDir] for [OverwriteRecovery.sweepOrphans] to age out.
 * [backupDir] and [recoveryDir] should be on persistent storage ([OverwriteRecovery.dir]).
 *
 * @param originalSizeBytes the original's recorded size, used for the room check and the empty-copy
 *   check only when [OriginalFile.currentSizeBytes] cannot say; 0 skips them and leaves a full disk
 *   to fail the backup copy itself.
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
    val liveSize = try { original.currentSizeBytes() } catch (e: Exception) { -1L }
    val expectedSize = if (liveSize >= 0) liveSize else originalSizeBytes
    if (expectedSize > 0 && usableSpace() < expectedSize + BACKUP_SLACK_BYTES) {
        throw OverwriteException.Refused(
            OverwriteException.Refused.Reason.BACKUP_FAILED,
            "There is not enough free space to keep a safety copy of the original while replacing it, " +
                "so it was left as it is. Free some space, or save a copy.",
        )
    }
    val backup = File.createTempFile("backup-", ".bin", backupDir)
    try {
        val input = original.openForRead() ?: throw IOException("The original could not be read")
        input.use { stream ->
            FileOutputStream(backup).use { out ->
                stream.copyTo(out)
                out.flush()
                // Before the original is truncated: a copy still in the page cache is no copy if
                // the device dies a moment later.
                out.fd.sync()
            }
        }
        if (liveSize >= 0 && backup.length() != liveSize) {
            throw IOException("The safety copy is ${backup.length()} bytes but the original is $liveSize")
        }
        if (liveSize < 0 && originalSizeBytes > 0 && backup.length() == 0L) throw IOException("The safety copy came out empty")
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
    var durable = false
    try {
        output.use { out ->
            staged.inputStream().use { it.copyTo(out) }
            out.flush()
            durable = out.syncToStorage()
        }
        verifyWritten()
    } catch (t: Throwable) {
        val restore = restoreFrom(backup, original)
        val kept = if (restore.written) null else keepForRecovery(backup, recoveryDir, label)
        if (restore.written && restore.durable) backup.delete()
        throw OverwriteException.FailedAfterWrite(
            restored = restore.written,
            recoveryFile = kept,
            message = if (restore.written) {
                "Replacing the photo failed partway, so the original was put back exactly as it was."
            } else {
                "Replacing the photo failed and the original could not be put back. A safety copy of it is " +
                    "kept inside Fotoz: save a copy of your edit now, and do not clear the app's storage."
            },
            cause = t,
        )
    }
    // Verified, and on storage: only now is the safety copy surplus.
    if (durable) backup.delete()
}

/** Flushes this stream's file to storage when it is backed by a file descriptor; false when that
 *  is not possible or the platform declined, in which case nothing is claimed about durability. */
private fun OutputStream.syncToStorage(): Boolean = try {
    (this as? FileOutputStream)?.fd?.sync() != null
} catch (e: IOException) {
    false
}

/** [restoreFrom]'s result: [written] when the bytes were put back and read back at the right size,
 *  [durable] when they were also confirmed on storage. */
private class Restore(val written: Boolean, val durable: Boolean)

/** Writes [backup] back over [original]; [Restore.written] only when it was written and reads back the same size. */
private fun restoreFrom(backup: File, original: OriginalFile): Restore = try {
    val out = original.openForTruncatingWrite()
    if (out == null) {
        Restore(written = false, durable = false)
    } else {
        var durable = false
        out.use { stream ->
            backup.inputStream().use { it.copyTo(stream) }
            stream.flush()
            durable = stream.syncToStorage()
        }
        Restore(original.openForRead()?.use { it.countBytes() } == backup.length(), durable)
    }
} catch (t: Throwable) {
    Restore(written = false, durable = false)
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
