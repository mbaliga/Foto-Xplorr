package com.fotoxplorr.core.organize.ingest

enum class Disposition {
    /** Will be copied to every destination. */
    COPY,

    /** Same size, time, ends and full hash as an earlier item of this very card. */
    SKIP_DUPLICATE_IN_CARD,

    /** The library already holds a file with this fingerprint. */
    SKIP_ALREADY_IN_LIBRARY,

    /** 9999 names were all taken; nothing is written, the item is reported. */
    SKIP_NO_FREE_NAME,
}

/**
 * One planned action. [relPath] is the file's path below each destination root (empty for skips).
 * [duplicateOf] names the card item (for in-card duplicates) the skip refers to.
 */
data class PlannedFile(
    val item: IngestItem,
    val disposition: Disposition,
    val relPath: String,
    val conflictRenamed: Boolean,
    val duplicateOf: String?,
    val fingerprint: CheapFingerprint,
) {
    val isRaw: Boolean get() = IngestPlanner.isRaw(item.extension)
}

/** A RAW+JPEG pair (or a lone file): everything in one group lands in the same folder under one stem. */
data class PlannedGroup(val files: List<PlannedFile>, val isPair: Boolean)

data class IngestPlan(
    val groups: List<PlannedGroup>,
    val destinationCount: Int,
) {
    val files: List<PlannedFile> get() = groups.flatMap { it.files }
    val toCopy: List<PlannedFile> get() = files.filter { it.disposition == Disposition.COPY }

    /** Bytes that will be read from the card once per destination copy. */
    val bytesToCopy: Long get() = toCopy.sumOf { it.item.sizeBytes }

    /** Bytes written across all destinations (a backup-on-ingest second target doubles it). */
    val bytesToWrite: Long get() = bytesToCopy * destinationCount
    val bytesSkipped: Long get() = files.filter { it.disposition != Disposition.COPY }.sumOf { it.item.sizeBytes }
    val pairCount: Int get() = groups.count { it.isPair }
}
