package com.fotoxplorr.app.backup

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream

/** A backup file opened for reading: its validated header and the decrypted body. */
class OpenedBackupFile(val header: BackupHeader, val plaintext: InputStream)

/**
 * The only entry point the planner, the pack writer and restore use to touch a backup file:
 * header, keys and cipher together, so nobody else assembles them (ADR-014).
 */
object BackupStreams {
    /**
     * Writes the header to [sink] and returns the stream the caller writes plaintext to.
     * Closing it writes the final segment and closes [sink]. The set salt and iteration count
     * come from [keys]; a different [kind] or [segmentSize] is a different file, not a different set.
     */
    fun encrypt(
        sink: OutputStream,
        kind: BackupKind,
        keys: BackupKeys,
        segmentSize: Int = BackupFormat.DEFAULT_SEGMENT_SIZE,
        aead: ChunkedAead = TinkChunkedAead,
    ): OutputStream {
        val header = BackupHeader(kind, keys.iterations, keys.setSalt, segmentSize)
        val headerBytes = header.encode()
        sink.write(headerBytes)
        return aead.encrypt(sink, keys.master, segmentSize, headerBytes)
    }

    /** Reads and validates the header only. Derive the keys from it (off the main thread), then [decrypt]. */
    fun readHeader(source: InputStream): BackupHeader = BackupHeader.read(source)

    /**
     * Decrypts the body that follows a header [readHeader] consumed. The caller says which kind
     * of file it is opening ([expectedKind], required on purpose): the kind is authenticated, but
     * a genuine file of another kind from the same set (a pack renamed to a manifest's name)
     * authenticates all the same, so a mismatch is [BackupException.UnexpectedKind], thrown before
     * a byte is decrypted. Returns after the FIRST
     * segment has authenticated, so a wrong passphrase fails here as
     * [BackupException.WrongPassphraseOrDamaged], not on some later read. Later failures are
     * [BackupException.Damaged]; a failure of [source] itself is passed through unchanged.
     */
    fun decrypt(
        header: BackupHeader,
        source: InputStream,
        keys: BackupKeys,
        expectedKind: BackupKind,
        aead: ChunkedAead = TinkChunkedAead,
    ): InputStream {
        if (header.kind != expectedKind) throw BackupException.UnexpectedKind(expectedKind, header.kind)
        if (!keys.matches(header)) throw BackupException.DifferentSet()
        val guard = SourceGuard(source)
        val inner = try {
            aead.decrypt(guard, keys.master, header.segmentSize, header.encode())
        } catch (e: IOException) {
            throw classify(e, guard, firstSegment = true)
        }
        val mapped = MappingInput(inner, guard)
        val first = mapped.read()
        mapped.firstSegment = false
        if (first < 0) return mapped
        return PushbackInputStream(mapped, 1).also { it.unread(first) }
    }

    /** [readHeader] then [decrypt], for callers that already hold the right keys. */
    fun open(source: InputStream, keys: BackupKeys, expectedKind: BackupKind, aead: ChunkedAead = TinkChunkedAead): OpenedBackupFile {
        val header = readHeader(source)
        return OpenedBackupFile(header, decrypt(header, source, keys, expectedKind, aead))
    }

    /** Bytes a whole file occupies for [plaintextBytes] of body: the plaintext header plus the stream. */
    fun fileSize(plaintextBytes: Long, segmentSize: Int, aead: ChunkedAead = TinkChunkedAead): Long =
        BackupFormat.HEADER_SIZE + aead.encryptedSize(plaintextBytes, segmentSize)

    private fun classify(e: IOException, guard: SourceGuard, firstSegment: Boolean): IOException {
        // A read error of the real source (a card pulled out, a revoked permission) is not damage.
        guard.failure?.let { return it }
        if (e is BackupException) return e
        return if (firstSegment) BackupException.WrongPassphraseOrDamaged(e) else BackupException.Damaged(e)
    }

    /** Remembers an IOException thrown by the real source so it is not mistaken for tampering. */
    private class SourceGuard(source: InputStream) : FilterInputStream(source) {
        var failure: IOException? = null
            private set

        override fun read(): Int = try {
            super.read()
        } catch (e: IOException) {
            failure = e
            throw e
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            super.read(b, off, len)
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }

    private class MappingInput(inner: InputStream, private val guard: SourceGuard) : FilterInputStream(inner) {
        var firstSegment = true

        override fun read(): Int = try {
            super.read()
        } catch (e: IOException) {
            throw classify(e, guard, firstSegment)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            super.read(b, off, len)
        } catch (e: IOException) {
            throw classify(e, guard, firstSegment)
        }

        override fun skip(n: Long): Long = try {
            super.skip(n)
        } catch (e: IOException) {
            throw classify(e, guard, firstSegment)
        }
    }
}
