package com.fotoxplorr.app.backup

import java.security.MessageDigest

/**
 * A photo or video on the phone, as the backup planner needs to see it. Built from the library's
 * own catalogue (never a rescan of storage) plus the folder-lock, sensitive and archive state.
 * [quickHash] is empty unless the caller computed one for this file ([QuickHash]).
 */
data class BackupCandidate(
    val mediaId: Long,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val dateModifiedSeconds: Long,
    val isTrashed: Boolean = false,
    val isProtected: Boolean = false,
    val isSensitive: Boolean = false,
    val isArchived: Boolean = false,
    val quickHash: String? = null,
) {
    val fileKey: String get() = ManifestEntry.fileKeyOf(relativePath, displayName)
}

/** Protected and sensitive photos are in by default (the owner's decision); trashed ones never are. */
data class BackupOptions(
    val includeProtected: Boolean = true,
    val includeSensitive: Boolean = true,
)

enum class SkipReason {
    TRASHED,
    EMPTY_FILE,
    PROTECTED_EXCLUDED,
    SENSITIVE_EXCLUDED,

    /** Even a pack of its own would not fit the hard cap ([PackLimits.hardCapBytes]). */
    TOO_LARGE_FOR_PACK,
}

class SkippedFile(val candidate: BackupCandidate, val reason: SkipReason)

/** A file to write, and the pack it goes into. [entryId] is final: the writer records it. */
class PlannedCopy(val entryId: Long, val candidate: BackupCandidate, val packName: String) {
    /** The manifest line for this file once its bytes are in the pack and its hash is known. */
    fun toEntry(sha256: String, originalLocationIncluded: Boolean, quickHash: String? = candidate.quickHash): ManifestEntry =
        ManifestEntry(
            entryId = entryId,
            mediaId = candidate.mediaId,
            displayName = candidate.displayName,
            relativePath = candidate.relativePath.orEmpty(),
            mimeType = candidate.mimeType,
            sizeBytes = candidate.sizeBytes,
            dateTakenMillis = candidate.dateTakenMillis,
            dateModifiedSeconds = candidate.dateModifiedSeconds,
            sha256 = sha256,
            quickHash = quickHash,
            packName = packName,
            flags = candidate.flags(originalLocationIncluded),
        )
}

/** One pack the run will write. */
class PlannedPack(val name: String, val copies: List<PlannedCopy>, val plaintextBytes: Long, val fileBytes: Long)

/**
 * What a run will do. The next manifest is [kept] followed by one entry per planned copy;
 * nothing in the destination is deleted, so a file that left the phone stays in [kept].
 *
 * - [unchanged]: on the phone and already backed up (record refreshed: id hint, flags).
 * - [moved]: on the phone under a new folder, recognised by name, size, date taken and quick hash.
 * - [retained]: no longer on the phone (or no longer eligible); kept as it was.
 * - [lost]: the previous manifest names a pack the destination no longer has; dropped from the
 *   snapshot, and copied again if the file is still on the phone.
 */
class BackupPlan(
    val packs: List<PlannedPack>,
    val unchanged: List<ManifestEntry>,
    val moved: List<ManifestEntry>,
    val retained: List<ManifestEntry>,
    val lost: List<ManifestEntry>,
    val skipped: List<SkippedFile>,
    val bytesOnPhone: Long,
) {
    val copies: List<PlannedCopy> get() = packs.flatMap { it.copies }
    val kept: List<ManifestEntry> get() = unchanged + moved + retained
    val bytesToCopy: Long get() = packs.sumOf { pack -> pack.copies.sumOf { it.candidate.sizeBytes } }
    val isUpToDate: Boolean get() = packs.isEmpty()
    fun skippedFor(reason: SkipReason): List<SkippedFile> = skipped.filter { it.reason == reason }
}

/**
 * A cheap fingerprint to tell two files apart without reading them whole: SHA-256 over the file
 * length, its first and its last [SAMPLE] bytes (the whole file when it is shorter than two
 * samples). It decides nothing alone; the planner uses it next to size and dates.
 */
object QuickHash {
    const val SAMPLE = 64 * 1024

    fun headLength(size: Long): Int = minOf(size, SAMPLE.toLong()).toInt()

    /** Where the tail starts; equal to [headLength] when the file is too short to need a separate tail. */
    fun tailOffset(size: Long): Long = maxOf(headLength(size).toLong(), size - SAMPLE)

    fun tailLength(size: Long): Int = (size - tailOffset(size)).toInt()

    fun of(size: Long, head: ByteArray, tail: ByteArray): String {
        require(head.size == headLength(size)) { "The head must be ${headLength(size)} bytes for a file of $size" }
        require(tail.size == tailLength(size)) { "The tail must be ${tailLength(size)} bytes for a file of $size" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(java.nio.ByteBuffer.allocate(8).putLong(size).array())
        digest.update(head)
        digest.update(tail)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/**
 * Decides what a backup run copies. Incremental by the usual quick check: a file is already
 * backed up when its folder, name, size and modified time match the record, and, if both sides
 * carry a [QuickHash], that agrees too. The SHA-256 of the contents is not needed to decide; it
 * is computed for free while the bytes stream into the pack.
 *
 * The planner is told what the destination holds ([existingPacks]) rather than trusting the
 * manifest, so a pack the person deleted is noticed and its files are copied again. Pass the
 * entries of the newest manifest AND any recovered from the encrypted index beside a pack that
 * no manifest mentions yet, and a run interrupted after some packs resumes from there.
 */
object BackupPlanner {
    fun plan(
        known: Collection<ManifestEntry>,
        candidates: Collection<BackupCandidate>,
        options: BackupOptions = BackupOptions(),
        existingPacks: Set<String>,
        limits: PackLimits = PackLimits(),
        aead: ChunkedAead = TinkChunkedAead,
    ): BackupPlan {
        val skipped = ArrayList<SkippedFile>()
        val matching = match(known, candidates, options, existingPacks, skipped)

        var nextEntryId = (known.maxOfOrNull { it.entryId } ?: -1L) + 1
        val firstPack = (existingPacks.mapNotNull(BackupLayout::packNumber) + known.mapNotNull { BackupLayout.packNumber(it.packName) })
            .maxOrNull()?.plus(1) ?: 1
        val layout = PackPlanner.layout(matching.toCopy, { it.sizeBytes }, firstPack, limits, aead)
        layout.oversize.forEach { skipped += SkippedFile(it, SkipReason.TOO_LARGE_FOR_PACK) }

        val packs = layout.packs.map { bin ->
            val copies = bin.items.map { PlannedCopy(nextEntryId++, it, bin.name) }
            PlannedPack(bin.name, copies, bin.plaintextBytes, bin.fileBytes)
        }
        val eligibleBytes = matching.eligible.sumOf { it.sizeBytes } - layout.oversize.sumOf { it.sizeBytes }
        return BackupPlan(
            packs = packs,
            unchanged = matching.unchanged,
            moved = matching.moved,
            retained = matching.retained,
            lost = matching.lost,
            skipped = skipped,
            bytesOnPhone = eligibleBytes,
        )
    }

    /**
     * The candidates for which a [QuickHash] would change the plan: a file with no record at its
     * path whose name and size match a record that vanished from the phone, i.e. a probable move.
     * Reading 128 KiB for each of these is far cheaper than copying a moved album again.
     */
    fun candidatesNeedingQuickHash(
        known: Collection<ManifestEntry>,
        candidates: Collection<BackupCandidate>,
        options: BackupOptions = BackupOptions(),
        existingPacks: Set<String>,
    ): List<BackupCandidate> {
        val matching = match(known, candidates, options, existingPacks, ArrayList())
        val vanishedBySize = matching.vanished.filter { it.quickHash != null }.map { it.displayName to it.sizeBytes }.toSet()
        return matching.toCopy.filter { it.quickHash == null && (it.displayName to it.sizeBytes) in vanishedBySize }
    }

    private class Matching(
        val eligible: List<BackupCandidate>,
        val toCopy: List<BackupCandidate>,
        val unchanged: List<ManifestEntry>,
        val moved: List<ManifestEntry>,
        val retained: List<ManifestEntry>,
        val lost: List<ManifestEntry>,
        /** Records still usable whose file is gone from the phone: move sources, or retained. */
        val vanished: List<ManifestEntry>,
    )

    private fun match(
        known: Collection<ManifestEntry>,
        candidates: Collection<BackupCandidate>,
        options: BackupOptions,
        existingPacks: Set<String>,
        skipped: MutableList<SkippedFile>,
    ): Matching {
        val eligible = ArrayList<BackupCandidate>()
        for (c in candidates.sortedWith(compareBy({ it.relativePath.orEmpty() }, { it.displayName }, { it.mediaId }))) {
            val reason = when {
                c.isTrashed -> SkipReason.TRASHED
                c.sizeBytes <= 0 -> SkipReason.EMPTY_FILE
                c.isProtected && !options.includeProtected -> SkipReason.PROTECTED_EXCLUDED
                c.isSensitive && !options.includeSensitive -> SkipReason.SENSITIVE_EXCLUDED
                else -> null
            }
            if (reason != null) skipped += SkippedFile(c, reason) else eligible += c
        }

        val (usable, lost) = known.sortedBy { it.entryId }.partition { it.packName in existingPacks }
        val byKey = HashMap<String, MutableList<ManifestEntry>>()
        usable.forEach { byKey.getOrPut(it.fileKey) { ArrayList() } += it }

        val claimed = HashSet<Long>()
        val unchanged = ArrayList<ManifestEntry>()
        val unmatched = ArrayList<BackupCandidate>()
        for (c in eligible) {
            val record = byKey[c.fileKey]?.firstOrNull { it.entryId !in claimed && sameBytesProbably(it, c) }
            if (record != null) {
                claimed += record.entryId
                unchanged += refreshed(record, c, c.relativePath.orEmpty())
            } else {
                unmatched += c
            }
        }

        // A record whose path no phone file has any more is either a move source or retained. A
        // record whose path IS on the phone but did not match is a changed file: the new copy
        // supersedes it, so it leaves the snapshot instead of lingering as a second version.
        val phoneKeys = eligible.mapTo(HashSet()) { it.fileKey }
        val vanished = usable.filter { it.entryId !in claimed && it.fileKey !in phoneKeys }
        val movable = HashMap<Triple<String, Long, Long>, MutableList<ManifestEntry>>()
        vanished.filter { it.quickHash != null }.forEach {
            movable.getOrPut(Triple(it.displayName, it.sizeBytes, it.dateTakenMillis)) { ArrayList() } += it
        }
        val moved = ArrayList<ManifestEntry>()
        val toCopy = ArrayList<BackupCandidate>()
        for (c in unmatched) {
            val source = c.quickHash?.let { hash ->
                movable[Triple(c.displayName, c.sizeBytes, c.dateTakenMillis)]
                    ?.firstOrNull { it.entryId !in claimed && it.quickHash == hash }
            }
            if (source != null) {
                claimed += source.entryId
                moved += refreshed(source, c, c.relativePath.orEmpty())
            } else {
                toCopy += c
            }
        }
        val retained = vanished.filter { it.entryId !in claimed }
        return Matching(eligible, toCopy, unchanged, moved, retained, lost, vanished)
    }

    private fun sameBytesProbably(record: ManifestEntry, c: BackupCandidate): Boolean =
        record.sizeBytes == c.sizeBytes &&
            record.dateModifiedSeconds == c.dateModifiedSeconds &&
            (record.quickHash == null || c.quickHash == null || record.quickHash == c.quickHash)

    /** The record as it stands now: where the file lives, its id hint and its lock/sensitive/archive state. */
    private fun refreshed(record: ManifestEntry, c: BackupCandidate, relativePath: String): ManifestEntry =
        record.copy(
            mediaId = c.mediaId,
            displayName = c.displayName,
            relativePath = relativePath,
            dateTakenMillis = c.dateTakenMillis,
            dateModifiedSeconds = c.dateModifiedSeconds,
            quickHash = c.quickHash ?: record.quickHash,
            flags = c.flags(record.flags.contains(EntryFlag.ORIGINAL_LOCATION)),
        )
}

private fun BackupCandidate.flags(originalLocation: Boolean): Set<EntryFlag> = buildSet {
    if (originalLocation) add(EntryFlag.ORIGINAL_LOCATION)
    if (isProtected) add(EntryFlag.WAS_PROTECTED)
    if (isSensitive) add(EntryFlag.WAS_SENSITIVE)
    if (isArchived) add(EntryFlag.WAS_ARCHIVED)
}
