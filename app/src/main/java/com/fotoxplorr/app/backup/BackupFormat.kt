package com.fotoxplorr.app.backup

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Wire format v1 of an encrypted backup file (docs/adr/ADR-014-encrypted-backup.md). Pure Kotlin:
 * no Android imports in this layer, so the format is testable on the JVM and portable to the
 * shared core later.
 *
 * Every file is a 36-byte plaintext [BackupHeader] followed by one authenticated stream (see
 * [ChunkedAead]). The header is not secret; it is authenticated, because its bytes are the
 * associated data of the stream. The reader validates it completely BEFORE any key is derived,
 * so a hostile file cannot make the phone run a huge KDF.
 */
object BackupFormat {
    /** "FOTZBKP" and a zero byte. */
    val MAGIC: ByteArray = byteArrayOf(0x46, 0x4F, 0x54, 0x5A, 0x42, 0x4B, 0x50, 0x00)
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1

    const val SALT_SIZE = 16
    const val HEADER_SIZE = 36

    /** Accepted PBKDF2 iteration counts. The default is what a new set is written with. */
    const val MIN_ITERATIONS = 100_000
    const val DEFAULT_ITERATIONS = 600_000
    const val MAX_ITERATIONS = 10_000_000

    /** Ciphertext bytes per segment. The small minimum exists for tests and tiny golden vectors. */
    const val MIN_SEGMENT_SIZE = 128
    const val DEFAULT_SEGMENT_SIZE = 1 shl 20
    const val MAX_SEGMENT_SIZE = 8 shl 20

    fun checkIterations(iterations: Long) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw BackupException.KdfOutOfRange(iterations)
        }
    }

    fun checkSegmentSize(segmentSize: Long) {
        if (segmentSize < MIN_SEGMENT_SIZE || segmentSize > MAX_SEGMENT_SIZE) {
            throw BackupException.BadSegmentSize(segmentSize)
        }
    }
}

/** What a file holds. The code is part of the authenticated header, so it cannot be changed unnoticed; the reader must still check it ([BackupStreams.decrypt]). */
enum class BackupKind(val code: Int) {
    PACK(1),
    MANIFEST(2),

    /** The small encrypted index written beside a closed pack; resume reads it, not a local journal. */
    PACK_INDEX(3),
    ;

    companion object {
        fun fromCode(code: Int): BackupKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Everything the cipher layer can say about a file it will not open. All are [IOException]s so
 * they can be thrown from `read()` of the decrypting stream.
 */
sealed class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Too short for a header, or the magic is wrong. */
    class NotABackup : BackupException("This is not a Fotoz backup file")

    class UnsupportedVersion(val version: Int) : BackupException("Backup format version $version is not supported")

    class UnsupportedKdf(val kdfId: Int) : BackupException("Backup key derivation $kdfId is not supported")

    class UnsupportedKind(val code: Int) : BackupException("Backup file kind $code is not known")

    /** The header asks for an iteration count outside the accepted range; the KDF was NOT run. */
    class KdfOutOfRange(val iterations: Long) : BackupException("Backup asks for $iterations key-derivation rounds")

    class BadSegmentSize(val segmentSize: Long) : BackupException("Backup segment size $segmentSize is outside the accepted range")

    /**
     * The file is a genuine backup file of this set but not the kind the caller is opening (a
     * pack under a manifest's name). The kind is authenticated, so this is the reader's check,
     * made before anything is decrypted.
     */
    class UnexpectedKind(val expected: BackupKind, val actual: BackupKind) :
        BackupException("Expected a backup ${expected.name.lowercase()} file but this is a ${actual.name.lowercase()} file")

    /** The keys in hand were derived for another backup set (or another iteration count). */
    class DifferentSet : BackupException("This file belongs to a different backup")

    /**
     * The first segment did not authenticate. GCM cannot tell a wrong passphrase from a damaged
     * file, and this type does not pretend to: say "wrong passphrase, or the backup is damaged".
     */
    class WrongPassphraseOrDamaged(cause: Throwable? = null) :
        BackupException("Wrong passphrase, or the backup is damaged", cause)

    /** A later segment failed: damage, truncation, appended or reordered data. */
    class Damaged(cause: Throwable? = null) : BackupException("The backup file is damaged or incomplete", cause)
}

/**
 * The 36-byte plaintext header. [encode] is canonical: parsing is strict, so
 * `parse(h.encode()).encode()` equals `h.encode()`, and these exact bytes are the associated
 * data of the stream that follows.
 */
class BackupHeader(
    val kind: BackupKind,
    val iterations: Int,
    setSalt: ByteArray,
    val segmentSize: Int,
) {
    private val saltBytes: ByteArray = setSalt.copyOf()

    init {
        require(saltBytes.size == BackupFormat.SALT_SIZE) { "The set salt must be ${BackupFormat.SALT_SIZE} bytes" }
        BackupFormat.checkIterations(iterations.toLong())
        BackupFormat.checkSegmentSize(segmentSize.toLong())
    }

    val setSalt: ByteArray get() = saltBytes.copyOf()

    fun encode(): ByteArray =
        ByteBuffer.allocate(BackupFormat.HEADER_SIZE)
            .put(BackupFormat.MAGIC)
            .putShort(BackupFormat.VERSION.toShort())
            .put(kind.code.toByte())
            .put(BackupFormat.KDF_PBKDF2_SHA256.toByte())
            .putInt(iterations)
            .put(saltBytes)
            .putInt(segmentSize)
            .array()

    companion object {
        /** Reads exactly [BackupFormat.HEADER_SIZE] bytes, validates them, and leaves the stream at the body. */
        fun read(source: InputStream): BackupHeader {
            val bytes = ByteArray(BackupFormat.HEADER_SIZE)
            var filled = 0
            while (filled < bytes.size) {
                val n = source.read(bytes, filled, bytes.size - filled)
                if (n < 0) throw BackupException.NotABackup()
                filled += n
            }
            return parse(bytes)
        }

        fun parse(bytes: ByteArray): BackupHeader {
            if (bytes.size < BackupFormat.HEADER_SIZE) throw BackupException.NotABackup()
            val buffer = ByteBuffer.wrap(bytes, 0, BackupFormat.HEADER_SIZE)
            val magic = ByteArray(BackupFormat.MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(BackupFormat.MAGIC)) throw BackupException.NotABackup()
            val version = buffer.short.toInt() and 0xFFFF
            if (version != BackupFormat.VERSION) throw BackupException.UnsupportedVersion(version)
            val kindCode = buffer.get().toInt() and 0xFF
            val kind = BackupKind.fromCode(kindCode) ?: throw BackupException.UnsupportedKind(kindCode)
            val kdfId = buffer.get().toInt() and 0xFF
            if (kdfId != BackupFormat.KDF_PBKDF2_SHA256) throw BackupException.UnsupportedKdf(kdfId)
            val iterations = buffer.int.toLong() and 0xFFFF_FFFFL
            BackupFormat.checkIterations(iterations)
            val salt = ByteArray(BackupFormat.SALT_SIZE).also { buffer.get(it) }
            val segmentSize = buffer.int.toLong() and 0xFFFF_FFFFL
            BackupFormat.checkSegmentSize(segmentSize)
            return BackupHeader(kind, iterations.toInt(), salt, segmentSize.toInt())
        }
    }
}
