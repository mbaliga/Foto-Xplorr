package com.fotoxplorr.app.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Walks the records of one decrypted pack (the `u64 id, u64 size, bytes, sha256` run
 * [PackRecord] describes), one at a time, for a restore. [input] is the stream
 * [BackupStreams.open] returned, so every byte read has already passed the cipher's check; what
 * this adds is the per-file SHA-256 that was written after the bytes.
 *
 * The contract is: [next] reads a header, [consume] reads that record's bytes (into an output, or
 * nowhere) and its trailer. A record must be consumed before the next is asked for. Nothing is
 * buffered beyond [BUFFER] bytes, so a 3 GiB video costs the same memory as a thumbnail.
 *
 * A header or body cut short, or a header that makes no sense, is [BackupException.Damaged]; a
 * failure of the underlying source is whatever it threw. A failure of the OUTPUT passed to
 * [consume] is also whatever it threw: the caller wraps its output when it needs to tell the two
 * apart.
 */
class PackReader(private val input: InputStream) {
    class Header(val entryId: Long, val size: Long)

    /** [sha256] is the hex digest of the bytes just read; [trailerMatches] is whether the pack stored the same one. */
    class Digest(val sha256: String, val trailerMatches: Boolean)

    private val header = ByteArray(PackRecord.HEADER_BYTES)
    private val trailer = ByteArray(PackRecord.TRAILER_BYTES)
    private val buffer = ByteArray(BUFFER)
    private var current: Header? = null

    /** The next record's header, or null at a clean end of the pack. */
    fun next(): Header? {
        check(current == null) { "The previous record was not consumed" }
        val first = input.read(header, 0, 1)
        if (first < 0) return null
        fill(header, 1)
        val decoded = PackRecord.decodeHeader(header)
            ?: throw BackupException.Damaged(IOException("A pack record has an impossible header"))
        return Header(decoded.first, decoded.second).also { current = it }
    }

    /**
     * Reads the current record's bytes, writing them to [out] when it is not null, then its trailer.
     * Cooperates with cancellation between buffers.
     */
    suspend fun consume(out: OutputStream?): Digest {
        val record = checkNotNull(current) { "No record is waiting to be consumed" }
        current = null
        val digest = MessageDigest.getInstance("SHA-256")
        var left = record.size
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw BackupException.Damaged(IOException("A pack record is cut short"))
            digest.update(buffer, 0, n)
            out?.write(buffer, 0, n)
            left -= n
        }
        fill(trailer, 0)
        val computed = digest.digest()
        return Digest(hex(computed), MessageDigest.isEqual(computed, trailer))
    }

    private fun fill(into: ByteArray, from: Int) {
        var filled = from
        while (filled < into.size) {
            val n = input.read(into, filled, into.size - filled)
            if (n < 0) throw BackupException.Damaged(IOException("A pack record is cut short"))
            filled += n
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val BUFFER = 256 * 1024
    }
}
