package com.fotoxplorr.app.backup

import java.nio.ByteBuffer

/**
 * How big a pack may get. A pack is closed before the file that would take it past
 * [targetBytes]; a file that alone is larger than the target gets a pack to itself, as long as
 * that pack still fits [hardCapBytes], which stays under 4 GiB so a card formatted FAT32 or
 * exFAT can hold it. A file whose pack could not fit the hard cap is reported and skipped, never
 * split: the format has no multi-pack files, and a split would cost every reader a new rule.
 */
data class PackLimits(
    val targetBytes: Long = DEFAULT_TARGET_BYTES,
    val hardCapBytes: Long = DEFAULT_HARD_CAP_BYTES,
    val segmentSize: Int = BackupFormat.DEFAULT_SEGMENT_SIZE,
) {
    init {
        require(targetBytes > 0) { "A pack target must be positive" }
        require(hardCapBytes >= targetBytes) { "The hard cap must not be below the target" }
        BackupFormat.checkSegmentSize(segmentSize.toLong())
    }

    /** The size on disk of a pack whose plaintext is [plaintextBytes] long, header included. */
    fun fileBytes(plaintextBytes: Long, aead: ChunkedAead = TinkChunkedAead): Long =
        BackupFormat.HEADER_SIZE + aead.encryptedSize(plaintextBytes, segmentSize)

    companion object {
        const val DEFAULT_TARGET_BYTES: Long = 1L shl 30

        /** FAT32 holds files up to 4 GiB minus one byte; the margin is slack for the cipher's own framing. */
        const val DEFAULT_HARD_CAP_BYTES: Long = (4L shl 30) - (16L shl 20)
    }
}

/**
 * The plaintext of a pack is a run of records: `u64 entryId`, `u64 size`, the file's [size]
 * bytes, then the SHA-256 of those bytes. Both big-endian. The writer (BackupJob) and the reader
 * (verify, restore) must use these numbers, because [PackPlanner] sizes packs with them.
 */
object PackRecord {
    const val HEADER_BYTES = 16
    const val TRAILER_BYTES = 32
    const val OVERHEAD = HEADER_BYTES + TRAILER_BYTES

    fun bytesFor(fileSize: Long): Long = OVERHEAD + fileSize

    fun encodeHeader(entryId: Long, size: Long): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).putLong(entryId).putLong(size).array()

    /** Null if [bytes] is not a whole header or names a negative id or size. */
    fun decodeHeader(bytes: ByteArray): Pair<Long, Long>? {
        if (bytes.size != HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        val entryId = buffer.long
        val size = buffer.long
        return if (entryId < 0 || size < 0) null else entryId to size
    }
}

/**
 * Cuts a list of files into packs. Pure arithmetic over sizes: deterministic, in the order
 * given (nothing is reordered to pack tighter, so a re-run plans the same packs), and generic so
 * the backup planner and tests can use it with their own item types.
 */
object PackPlanner {
    class Bin<T>(
        val number: Int,
        val items: List<T>,
        val plaintextBytes: Long,
        val fileBytes: Long,
    ) {
        val name: String get() = BackupLayout.packName(number)
    }

    /** [oversize] could not fit even a pack of its own; the caller reports and skips them. */
    class Layout<T>(val packs: List<Bin<T>>, val oversize: List<T>)

    fun <T> layout(
        items: List<T>,
        sizeOf: (T) -> Long,
        firstPackNumber: Int,
        limits: PackLimits = PackLimits(),
        aead: ChunkedAead = TinkChunkedAead,
    ): Layout<T> {
        require(firstPackNumber >= 1) { "Pack numbers start at 1" }
        val packs = ArrayList<Bin<T>>()
        val oversize = ArrayList<T>()
        var current = ArrayList<T>()
        var currentBytes = 0L
        var number = firstPackNumber

        fun close() {
            if (current.isEmpty()) return
            packs += Bin(number, current, currentBytes, limits.fileBytes(currentBytes, aead))
            number++
            current = ArrayList()
            currentBytes = 0L
        }

        for (item in items) {
            val size = sizeOf(item)
            require(size >= 0) { "A file cannot have a negative size" }
            val record = PackRecord.bytesFor(size)
            if (limits.fileBytes(record, aead) > limits.hardCapBytes) {
                oversize += item
                continue
            }
            if (current.isNotEmpty() && limits.fileBytes(currentBytes + record, aead) > limits.targetBytes) close()
            current += item
            currentBytes += record
        }
        close()
        return Layout(packs, oversize)
    }
}
