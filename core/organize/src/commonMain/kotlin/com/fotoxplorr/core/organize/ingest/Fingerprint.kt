package com.fotoxplorr.core.organize.ingest

/**
 * The cheap duplicate key: size + capture time + a hash of the first and of the last [Fingerprints.SAMPLE]
 * bytes. Reading 128 KiB per file is what makes a 500-photo card plan in seconds even over MTP;
 * two files that agree on all four are DUPLICATES ONLY AFTER [Fingerprints.full] agrees as well
 * (the full hash is computed only on such a tie). Not cryptographic: both hashes are plain
 * non-keyed mixers, fine against accidents, not against an adversary, which is why a tie is
 * confirmed by reading everything and verification compares the full hash of source and copy.
 *
 * The [key] text is stable across runs and platforms (no hashCode, no locale).
 */
data class CheapFingerprint(
    val size: Long,
    val captureMillis: Long,
    val headHash: Long,
    val tailHash: Long,
) {
    val key: String get() = "$size-$captureMillis-${hex(headHash)}-${hex(tailHash)}"
}

internal fun hex(v: Long): String = v.toULong().toString(16).padStart(16, '0')

object Fingerprints {
    const val SAMPLE = 64 * 1024
    private const val CHUNK = 64 * 1024

    fun cheap(reader: IngestReader, captureMillis: Long): CheapFingerprint {
        val size = reader.size
        val head = hashRange(reader, 0, minOf(size, SAMPLE.toLong()))
        val tailStart = maxOf(0L, size - SAMPLE)
        val tail = hashRange(reader, tailStart, size - tailStart)
        return CheapFingerprint(size, captureMillis, head.first, tail.first)
    }

    /** 32 hex characters (two independent 64-bit lanes) over every byte, plus the size. */
    fun full(reader: IngestReader): String {
        val (a, b) = hashRange(reader, 0, reader.size)
        return hex(a) + hex(b)
    }

    private fun hashRange(reader: IngestReader, start: Long, length: Long): Pair<Long, Long> {
        var a = 0xcbf29ce484222325uL.toLong()
        var b = 0x9E3779B97F4A7C15uL.toLong()
        val buf = ByteArray(CHUNK)
        var pos = start
        var left = length
        while (left > 0) {
            val n = reader.read(pos, buf, 0, minOf(left, CHUNK.toLong()).toInt())
            if (n <= 0) break
            for (i in 0 until n) {
                val v = buf[i].toLong() and 0xff
                a = (a xor v) * 0x100000001b3L
                b = (b + v + 1) * 0x2545F4914F6CDD1DL
                b = b xor (b ushr 29)
            }
            pos += n
            left -= n
        }
        // Fold the length actually hashed in so a truncated read never equals a full one.
        a = (a xor (length - left)) * 0x100000001b3L
        return a to b
    }
}

/** A library-side candidate for a tie; the full hash is computed only when asked for. */
fun interface FullHashProvider {
    fun fullHash(): String
}

/** What the destination library already holds, by cheap key. Supplied by the app from its catalogue. */
fun interface KnownFingerprints {
    fun candidates(key: CheapFingerprint): List<FullHashProvider>

    companion object {
        val None = KnownFingerprints { emptyList() }
    }
}
