package com.fotoxplorr.app.backup

import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The master key of one backup set: `PBKDF2-HMAC-SHA256(NFC passphrase, setSalt, iterations)`,
 * 32 bytes, computed once per run and reused for every file that shares the set salt and the
 * iteration count. The per-file AES key is derived from it inside the stream (ADR-014).
 *
 * The passphrase is never stored. This class holds the master key only for as long as it is open;
 * [close] zeroes it. Zeroing is best effort: the JVM may have copied the bytes (and the
 * normaliser works on immutable strings), and a compromised phone is outside the threat model.
 */
class BackupKeys private constructor(
    setSalt: ByteArray,
    val iterations: Int,
    master: ByteArray,
) : Closeable {
    private val saltBytes = setSalt.copyOf()
    private var masterBytes: ByteArray? = master

    val setSalt: ByteArray get() = saltBytes.copyOf()

    val isWiped: Boolean get() = masterBytes == null

    /** The live key array; the cipher layer copies what it needs. Never log or persist it. */
    internal val master: ByteArray
        get() = masterBytes ?: throw IllegalStateException("These backup keys were wiped")

    /** True when a file's header names the same set and the same iteration count as these keys. */
    fun matches(header: BackupHeader): Boolean =
        header.iterations == iterations && MessageDigest.isEqual(header.setSalt, saltBytes)

    override fun close() {
        masterBytes?.fill(0)
        masterBytes = null
    }

    companion object {
        private const val KEY_BITS = 256

        /**
         * Derives the keys for an existing set (its salt and iteration count come from a header the
         * reader has already validated). The iteration bound is enforced HERE as well, before the
         * KDF runs, because this function is also reachable without a header. Slow by design:
         * call it off the main thread. Zeroes [passphrase] when it returns unless told not to.
         */
        fun derive(
            passphrase: CharArray,
            setSalt: ByteArray,
            iterations: Int,
            wipePassphrase: Boolean = true,
        ): BackupKeys {
            BackupFormat.checkIterations(iterations.toLong())
            require(setSalt.size == BackupFormat.SALT_SIZE) { "The set salt must be ${BackupFormat.SALT_SIZE} bytes" }
            val normalised = normalise(passphrase)
            try {
                require(normalised.isNotEmpty()) { "The passphrase is empty" }
                val spec = PBEKeySpec(normalised, setSalt, iterations, KEY_BITS)
                val master = try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                } finally {
                    spec.clearPassword()
                }
                return BackupKeys(setSalt, iterations, master)
            } finally {
                normalised.fill('\u0000')
                if (wipePassphrase) passphrase.fill('\u0000')
            }
        }

        /** Starts a NEW set: a fresh random 16-byte set salt, then [derive]. */
        fun newSet(
            passphrase: CharArray,
            iterations: Int = BackupFormat.DEFAULT_ITERATIONS,
            random: SecureRandom = SecureRandom(),
        ): BackupKeys {
            val salt = ByteArray(BackupFormat.SALT_SIZE).also { random.nextBytes(it) }
            return derive(passphrase, salt, iterations)
        }

        /**
         * NFC first, so the same passphrase typed with decomposed accents on one keyboard and
         * precomposed on another opens the same backup. The PBKDF2 providers the app runs on (the
         * JDK's and Android's BouncyCastle) encode the characters as UTF-8; the golden vector pins
         * that on the JVM, and [providerMatchesGoldenVector] lets a device prove it.
         */
        internal fun normalise(passphrase: CharArray): CharArray =
            Normalizer.normalize(String(passphrase), Normalizer.Form.NFC).toCharArray()

        /**
         * Runs the published key-derivation vector (also in BackupFormatTest, produced by
         * `golden_vector.py`) through this device's PBKDF2 provider. False means a backup made here
         * could not be opened elsewhere: refuse to start one. Takes one minimum-cost derivation.
         */
        fun providerMatchesGoldenVector(): Boolean {
            val passphrase = GOLDEN_PASSPHRASE.toCharArray()
            val keys = derive(passphrase, GOLDEN_SALT, BackupFormat.MIN_ITERATIONS)
            return keys.use { MessageDigest.isEqual(it.master, GOLDEN_MASTER) }
        }

        // "Cafe" + U+0301, "Zu" + U+0308: decomposed on purpose, plus Japanese.
        private const val GOLDEN_PASSPHRASE = "Café Zürich 日本語 #7"
        private val GOLDEN_SALT = ByteArray(BackupFormat.SALT_SIZE) { (0x10 + it).toByte() }
        private val GOLDEN_MASTER = intArrayOf(
            0x33, 0x5a, 0x45, 0x10, 0x76, 0xe7, 0xc6, 0x4c, 0x9c, 0x56, 0x62, 0xd7, 0x8b, 0xfc, 0x88, 0x1d,
            0x4c, 0x9b, 0x89, 0x49, 0x44, 0xc4, 0x00, 0xdb, 0x6a, 0x08, 0x2a, 0x18, 0x6b, 0xae, 0x23, 0xd8,
        ).let { values -> ByteArray(values.size) { values[it].toByte() } }
    }
}

/**
 * Passphrase policy shared by the dialogs that set one. Length only, no composition rules; there
 * is no recovery, so the dialogs also make the person confirm it twice. Not enforced when OPENING
 * a set: a reader must accept whatever the writer accepted.
 */
object BackupPassphrase {
    const val MIN_CODE_POINTS = 12

    fun isLongEnough(passphrase: CharArray): Boolean {
        val normalised = BackupKeys.normalise(passphrase)
        return try {
            String(normalised).codePointCount(0, normalised.size) >= MIN_CODE_POINTS
        } finally {
            normalised.fill('\u0000')
        }
    }
}
