package com.fotoxplorr.app.sync

import com.fotoxplorr.app.backup.BackupFormat
import com.fotoxplorr.app.backup.BackupKeys
import com.fotoxplorr.app.backup.BackupKind
import com.fotoxplorr.app.backup.BackupStreams
import com.fotoxplorr.app.backup.ChunkedAead
import com.fotoxplorr.app.backup.TinkChunkedAead
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Encrypt-before-upload, REUSING the encrypted backup's stream code (ADR-014): the same header,
 * the same PBKDF2-from-passphrase keys ([BackupKeys]), the same chunked AEAD ([ChunkedAead]). Each
 * file becomes one self-contained file of kind [BackupKind.SYNC_FILE] named `<name>.fzs`; any one
 * of them opens with the passphrase alone (its header carries the set salt and iteration count),
 * with [BackupStreams.open], on any device that has this code.
 *
 * The reuse was clean: nothing in `backup/` changed except one new [BackupKind]. What does NOT carry
 * over is the backup's pack/manifest/resume machinery: sync files are independent, so a sync set has
 * no manifest of its own at the destination (the local [SyncManifest] is the only index) and file
 * NAMES are not hidden (only the bytes are). Both are stated to the person in [SyncCopy].
 *
 * The passphrase is never stored. A set's salt and iteration count are kept in the destination's
 * (non-secret) settings so that later runs derive the same key from the same passphrase; the
 * passphrase is asked for at the start of each run.
 */
class SyncEncryption(
    private val keys: BackupKeys,
    private val aead: ChunkedAead = TinkChunkedAead,
    private val segmentSize: Int = BackupFormat.DEFAULT_SEGMENT_SIZE,
) {
    /** Bytes the destination will hold for [plainBytes] of photo. */
    fun remoteSize(plainBytes: Long): Long = BackupStreams.fileSize(plainBytes, segmentSize, aead)

    /** A stream of the encrypted file, produced as [plain] is read. Closing it closes [plain]. */
    fun wrap(plain: InputStream): InputStream = EncryptingInputStream(plain, keys, aead, segmentSize)

    fun close() = keys.close()

    companion object {
        const val SUFFIX = ".fzs"

        /** A value that proves a passphrase is the one a set was started with, without revealing the key (SHA-256 over a label and the master key). */
        fun keyCheck(keys: BackupKeys): String =
            hex(MessageDigest.getInstance("SHA-256").digest("fotoz-sync-key-check".toByteArray(Charsets.UTF_8) + keys.master))

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

        fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Pull-style encryption: the cipher is push-style, so each read feeds it a slice and serves what came out. */
internal class EncryptingInputStream(
    private val plain: InputStream,
    keys: BackupKeys,
    aead: ChunkedAead,
    segmentSize: Int,
) : InputStream() {
    private val sink = ByteArrayOutputStream(HEADER_AND_SEGMENT_HINT)
    private val encrypting: OutputStream = BackupStreams.encrypt(sink, BackupKind.SYNC_FILE, keys, segmentSize, aead)
    private val slice = ByteArray(SLICE)
    private var pending = ByteArray(0)
    private var position = 0
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (position >= pending.size) {
            if (finished) return -1
            refill()
        }
        val n = minOf(len, pending.size - position)
        System.arraycopy(pending, position, b, off, n)
        position += n
        return n
    }

    private fun refill() {
        val read = plain.read(slice)
        if (read < 0) {
            encrypting.close()
            finished = true
        } else if (read > 0) {
            encrypting.write(slice, 0, read)
        }
        pending = sink.toByteArray()
        sink.reset()
        position = 0
    }

    override fun close() {
        plain.close()
    }

    private companion object {
        const val SLICE = 64 * 1024
        const val HEADER_AND_SEGMENT_HINT = 2 * SLICE
    }
}
