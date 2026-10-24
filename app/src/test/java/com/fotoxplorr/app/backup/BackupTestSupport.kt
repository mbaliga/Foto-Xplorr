package com.fotoxplorr.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Shared fixtures for the backup format tests. Keys are derived at the cheapest accepted cost. */
internal object BackupTestSupport {
    const val SEGMENT = 128
    const val PASSPHRASE = "correct horse battery staple"

    val salt: ByteArray = ByteArray(BackupFormat.SALT_SIZE) { (0x30 + it).toByte() }
    val otherSalt: ByteArray = ByteArray(BackupFormat.SALT_SIZE) { (0x70 + it).toByte() }

    /** Shared and never closed by a test; a test that wipes keys derives its own. */
    val keys: BackupKeys by lazy { newKeys() }
    val otherSetKeys: BackupKeys by lazy { newKeys(otherSalt) }
    val otherPassphraseKeys: BackupKeys by lazy { newKeys(passphrase = "a different passphrase entirely") }

    fun newKeys(setSalt: ByteArray = salt, passphrase: String = PASSPHRASE): BackupKeys =
        BackupKeys.derive(passphrase.toCharArray(), setSalt, BackupFormat.MIN_ITERATIONS)

    fun plaintext(size: Int, seed: Int = 1): ByteArray {
        val random = java.util.Random(seed.toLong())
        return ByteArray(size).also { random.nextBytes(it) }
    }

    fun seal(
        plaintext: ByteArray,
        keys: BackupKeys = this.keys,
        kind: BackupKind = BackupKind.PACK,
        segment: Int = SEGMENT,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupStreams.encrypt(out, kind, keys, segment).use { it.write(plaintext) }
        return out.toByteArray()
    }

    fun open(file: ByteArray, keys: BackupKeys = this.keys, kind: BackupKind = BackupKind.PACK): InputStream =
        BackupStreams.open(ByteArrayInputStream(file), keys, kind).plaintext

    /** Reads to the end, handing back what was released before any failure. */
    class Drained(val bytes: ByteArray, val failure: Throwable?)

    fun drain(file: ByteArray, keys: BackupKeys = this.keys, chunk: Int = 64, kind: BackupKind = BackupKind.PACK): Drained {
        val out = ByteArrayOutputStream()
        return try {
            val stream = open(file, keys, kind)
            val buffer = ByteArray(chunk)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            Drained(out.toByteArray(), null)
        } catch (e: Throwable) {
            Drained(out.toByteArray(), e)
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
