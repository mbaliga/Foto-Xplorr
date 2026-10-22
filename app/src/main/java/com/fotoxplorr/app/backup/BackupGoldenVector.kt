package com.fotoxplorr.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The published test vector of the backup format, carried in the app so a phone can prove its own
 * crypto before it writes a backup (ADR-014). The same bytes are in `BackupFormatTest`, produced by
 * `src/test/resources/backup/golden_vector.py` from a second implementation; a test pins that the
 * two copies agree.
 *
 * The key derivation was always checked on the device. This also runs the other half, which the JVM
 * unit tests cover but a release build (shrunk, optimised, with its own AES-GCM provider) may not
 * behave like: opening a known encrypted file, sealing and reopening a fresh buffer, and refusing a
 * buffer with one bit flipped. A broken decrypt side would otherwise show as "wrong passphrase, or
 * the backup is damaged" only after a whole backup had been written. One minimum-cost derivation in
 * all; the derived key serves all three.
 */
internal object BackupGoldenVector {
    enum class Result {
        PASSED,

        /** PBKDF2 gave another key than the vector's: a backup made here would not open elsewhere. */
        KEY_DERIVATION,

        /** The known encrypted file did not open to its known plaintext. */
        DECRYPT,

        /** A buffer sealed here did not open again, or a damaged one opened anyway. */
        ROUND_TRIP,
    }

    // "Cafe" + U+0301, "Zu" + U+0308: decomposed on purpose, plus Japanese.
    const val PASSPHRASE = "Cafe\u0301 Zu\u0308rich \u65e5\u672c\u8a9e #7"

    val SALT: ByteArray = ByteArray(BackupFormat.SALT_SIZE) { (0x10 + it).toByte() }

    val MASTER: ByteArray = intArrayOf(
        0x33, 0x5a, 0x45, 0x10, 0x76, 0xe7, 0xc6, 0x4c, 0x9c, 0x56, 0x62, 0xd7, 0x8b, 0xfc, 0x88, 0x1d,
        0x4c, 0x9b, 0x89, 0x49, 0x44, 0xc4, 0x00, 0xdb, 0x6a, 0x08, 0x2a, 0x18, 0x6b, 0xae, 0x23, 0xd8,
    ).let { values -> ByteArray(values.size) { values[it].toByte() } }

    /** A pack of 200 bytes of [PLAINTEXT]: header, then the stream in 128-byte segments. */
    private const val FILE_HEX =
        "464f545a424b500000010101000186a0101112131415161718191a1b1c1d1e1f00000080" +
            "28a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfb0b1b2b3b4b5b6c1790c3236087f9d84f3bef629737b69b9ffc" +
            "6293af6967f84d46a986e0773b856145266f63504e237ad23a7adfafdacd23b285befd7cd7c0bef9131e6faed8f46236a5e7fed848159b4bd01d" +
            "61fe4d45f90098cd7f502ed1081917fd967418f2a9f0f92503135cc5940584142150478074f720edffb3c46a26c01879c548fe9022c770adec5" +
            "a32f7a453fb6caa1bba6a72233add8f83dd2eca177fa885281147e6dedc299202fd80010e954e21c46689458322f11a18873f95163f771dc698" +
            "6ae7da2e6b9bb0bcaf6df9e0b8ce7dc4d2b35b83860025ba7c83203636612d4d79c115d67b93ca37d8d2b05d5d7ca19d738ba2aaa426cc970"

    val FILE: ByteArray get() = ByteArray(FILE_HEX.length / 2) { FILE_HEX.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    val PLAINTEXT: ByteArray get() = ByteArray(200) { ((it * 13 + 5) and 0xFF).toByte() }

    /** Runs the whole check. Never throws: a provider or class that fails to load is a failed check. */
    fun run(aead: ChunkedAead = TinkChunkedAead): Result {
        val keys = try {
            BackupKeys.derive(PASSPHRASE.toCharArray(), SALT, BackupFormat.MIN_ITERATIONS)
        } catch (t: Throwable) {
            return Result.KEY_DERIVATION
        }
        return keys.use { checked(it, aead) }
    }

    internal fun checked(keys: BackupKeys, aead: ChunkedAead = TinkChunkedAead): Result {
        if (!MessageDigest.isEqual(keys.master, MASTER)) return Result.KEY_DERIVATION
        if (!opensKnownFile(keys, aead)) return Result.DECRYPT
        if (!roundTrips(keys, aead)) return Result.ROUND_TRIP
        return Result.PASSED
    }

    private fun opensKnownFile(keys: BackupKeys, aead: ChunkedAead): Boolean = try {
        val opened = BackupStreams.open(ByteArrayInputStream(FILE), keys, BackupKind.PACK, aead)
        opened.plaintext.use { it.readBytes() }.contentEquals(PLAINTEXT)
    } catch (t: Throwable) {
        false
    }

    private fun roundTrips(keys: BackupKeys, aead: ChunkedAead): Boolean = try {
        val sample = ByteArray(700) { (it * 7 + 3).toByte() }
        val sealed = ByteArrayOutputStream()
        BackupStreams.encrypt(sealed, BackupKind.PACK, keys, BackupFormat.MIN_SEGMENT_SIZE, aead).use { it.write(sample) }
        val file = sealed.toByteArray()
        val again = BackupStreams.open(ByteArrayInputStream(file), keys, BackupKind.PACK, aead).plaintext.use { it.readBytes() }
        val damaged = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        sample.contentEquals(again) && !readsToTheEnd(damaged, keys, aead)
    } catch (t: Throwable) {
        false
    }

    /** True when [file] opened and read in full without an error, which a damaged file must never do. */
    private fun readsToTheEnd(file: ByteArray, keys: BackupKeys, aead: ChunkedAead): Boolean = try {
        BackupStreams.open(ByteArrayInputStream(file), keys, BackupKind.PACK, aead).plaintext.use { it.readBytes() }
        true
    } catch (e: IOException) {
        false
    }
}
