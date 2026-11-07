package com.fotoxplorr.core.formats.raw

/**
 * Random-access, read-only bytes: the only way [RawPreviewExtractor] touches a file. A 60 MB RAW is
 * never loaded; the extractor asks for a few hundred small ranges (headers, IFDs, JPEG markers).
 * The Android seam backs this with a `FileChannel` positional read; tests use [ByteArraySource].
 */
interface ByteSource {
    /** Total size in bytes. */
    val length: Long

    /**
     * Reads up to [count] bytes at [position] into [buffer] starting at [offset]. Returns the number
     * of bytes read (at least 1), or -1 when [position] is at or past the end or the read failed.
     * A short read is allowed; the caller loops.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int
}

/** An in-memory [ByteSource], for tests and for tiny embedded payloads. */
class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val length: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        if (position < 0 || position >= bytes.size || count <= 0) return -1
        val n = minOf(count.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
        return n
    }
}

/** Thrown inside the extractor when a hostile file exhausts the read budget; never escapes it. */
internal class ExtractionLimitException : RuntimeException("raw preview extraction budget exhausted")

/**
 * The bounds guard in front of a [ByteSource]: every read is range-checked against the source
 * length (an out-of-range request returns null and is never forwarded), single reads are capped,
 * and the total bytes and read count are budgeted so a corrupt file cannot make the walk run long.
 */
internal class Reader(private val source: ByteSource) {
    val length: Long = maxOf(0L, source.length)
    private var budget = MAX_TOTAL_BYTES
    private var reads = 0

    /** Exactly [n] bytes at [pos], or null when any of them lies outside the source. */
    fun bytes(pos: Long, n: Int): ByteArray? {
        if (n < 0 || n > MAX_READ || pos < 0 || pos > length - n) return null
        budget -= n
        if (budget < 0 || ++reads > MAX_READS) throw ExtractionLimitException()
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = source.read(pos + got, out, got, n - got)
            if (r <= 0) return null
            got += r
        }
        return out
    }

    companion object {
        const val MAX_READ = 1 shl 20
        const val MAX_TOTAL_BYTES = 8L shl 20
        const val MAX_READS = 20_000
    }
}

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF

internal fun ByteArray.u16(o: Int, le: Boolean): Int =
    if (le) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)

internal fun ByteArray.u32(o: Int, le: Boolean): Long =
    if (le) {
        u8(o).toLong() or (u8(o + 1).toLong() shl 8) or (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24)
    } else {
        (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
    }
