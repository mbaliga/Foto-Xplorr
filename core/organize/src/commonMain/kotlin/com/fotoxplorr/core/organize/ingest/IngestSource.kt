package com.fotoxplorr.core.organize.ingest

/**
 * One file on a camera or card, as the planner sees it. No bytes, no platform handle: [id] is the
 * source's own stable key (an MTP object handle rendered as text, or an SAF document id) and is
 * what the resume journal is keyed on, so it must not change between two listings of the same card.
 *
 * @property folder the card-side folder (`DCIM/100CANON`). Part of pairing: `IMG_0001` exists in
 *   every numbered folder of a real card and two of them are NOT a RAW+JPEG pair.
 * @property captureMillis capture time as epoch milliseconds, the caller's best value (EXIF for a
 *   file, the object's date-created for MTP). Feeds the destination template and the fingerprint.
 * @property camera model name for `{camera}`; blank when unknown.
 */
data class IngestItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val captureMillis: Long,
    val camera: String = "",
    val folder: String = "",
) {
    /** Extension without its dot, original case; empty when the name has none. */
    val extension: String get() = if ('.' in name) name.substringAfterLast('.') else ""

    /** [name] without its extension. */
    val stem: String get() = if (extension.isEmpty()) name else name.dropLast(extension.length + 1)
}

/** Random-access read of one source file. Single threaded; the caller closes it. */
interface IngestReader {
    val size: Long

    /** Reads up to [length] bytes at [position]; returns the count, or -1 at or past the end. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/**
 * A camera, memory card or any folder that photos are imported FROM.
 *
 * The planner and runner only talk to this interface, so every rule in this package is provable on
 * the JVM. The real implementations are device-only and NOT verified by this package's tests:
 * a card mounted as a storage volume is simply an SAF tree (list its documents, open their
 * streams); a camera in PTP/MTP mode is `android.mtp.MtpDevice` (object handles for [IngestItem.id],
 * `getObjectInfo` for sizes and dates, `importFile` or a pipe for [open]). Both must honour:
 * [list] is stable and complete or throws (a half listing would plan a half import); [open] gives
 * the CURRENT bytes of that item; nothing here ever writes to the source except [delete], which
 * is only reached through [IngestRunner.deleteFromCard] after every copy verified.
 */
interface IngestSource {
    fun list(): List<IngestItem>

    fun open(item: IngestItem): IngestReader

    /** True when [delete] really removes files (an MTP camera may refuse, a read-only card may too). */
    val canDelete: Boolean get() = false

    /** Removes the item from the card. Only ever called after all its copies verified. */
    fun delete(item: IngestItem): Boolean = false
}

/**
 * One destination (the library folder, or the backup-on-ingest second one). Paths are relative,
 * `/`-separated and already sanitised by [NameSanitizer]; the target maps them onto a File or a
 * SAF tree. The step that wires this to `fileops` (temp-then-rename, SAF) comes with U7.
 */
interface IngestTarget {
    /** True when something already sits at [relPath] (case-insensitively where the volume is). */
    fun exists(relPath: String): Boolean

    /**
     * Writes the bytes of [reader] to [relPath] creating folders; MUST be temp-then-rename and MUST
     * NOT overwrite (throw if [relPath] exists). Returns the bytes written.
     */
    fun write(relPath: String, reader: IngestReader): Long

    /** Opens what was written, for verification; null when absent. */
    fun open(relPath: String): IngestReader?

    /**
     * Removes a copy THIS run just wrote and that failed verification, so a retry is not blocked by
     * its own bad file. Never called on a file the run did not create.
     */
    fun discardOwnUnverified(relPath: String)
}
