package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.hex
import com.fotoxplorr.app.backup.BackupTestSupport.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The header, the key derivation and the golden vector. The vector was produced by
 * `src/test/resources/backup/golden_vector.py` (Python hashlib and cryptography, written from the
 * format in ADR-014, not from this code), so passing proves the format can be implemented outside
 * this codebase.
 */
class BackupFormatTest {
    // Typed decomposed on purpose: "Cafe" + U+0301 and "Zu" + U+0308.
    private val goldenPassphrase = "Café Zürich 日本語 #7"

    private val goldenPassphraseUtf8 = "436166c3a9205ac3bc7269636820e697a5e69cace8aa9e202337"
    private val goldenMaster = "335a451076e7c64c9c5662d78bfc881d4c9b894944c400db6a082a186bae23d8"
    private val goldenHeader = "464f545a424b500000010101000186a0101112131415161718191a1b1c1d1e1f00000080"
    private val goldenFile = goldenHeader +
        "28a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfb0b1b2b3b4b5b6c1790c3236087f9d84f3bef629737b69b9ffc" +
        "6293af6967f84d46a986e0773b856145266f63504e237ad23a7adfafdacd23b285befd7cd7c0bef9131e6faed8f46236a5e7fed848159b4bd01d" +
        "61fe4d45f90098cd7f502ed1081917fd967418f2a9f0f92503135cc5940584142150478074f720edffb3c46a26c01879c548fe9022c770adec5" +
        "a32f7a453fb6caa1bba6a72233add8f83dd2eca177fa885281147e6dedc299202fd80010e954e21c46689458322f11a18873f95163f771dc698" +
        "6ae7da2e6b9bb0bcaf6df9e0b8ce7dc4d2b35b83860025ba7c83203636612d4d79c115d67b93ca37d8d2b05d5d7ca19d738ba2aaa426cc970"

    private val goldenPlaintext = ByteArray(200) { ((it * 13 + 5) and 0xFF).toByte() }

    @Test
    fun `the passphrase is normalised to NFC and encoded as UTF-8`() {
        val normalised = BackupKeys.normalise(goldenPassphrase.toCharArray())
        assertEquals(goldenPassphraseUtf8, hex(String(normalised).toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `decomposed and precomposed spellings of a passphrase open the same backup`() {
        val decomposed = BackupKeys.derive("Café Zürich 12345".toCharArray(), BackupTestSupport.salt, BackupFormat.MIN_ITERATIONS)
        val precomposed = BackupKeys.derive("Café Zürich 12345".toCharArray(), BackupTestSupport.salt, BackupFormat.MIN_ITERATIONS)
        assertArrayEquals(decomposed.master, precomposed.master)
        val file = BackupTestSupport.seal(BackupTestSupport.plaintext(150), decomposed)
        assertArrayEquals(BackupTestSupport.plaintext(150), BackupTestSupport.open(file, precomposed).readBytes())
    }

    @Test
    fun `the key derivation matches the independent vector`() {
        val keys = BackupKeys.derive(goldenPassphrase.toCharArray(), unhex(goldenHeader).copyOfRange(16, 32), BackupFormat.MIN_ITERATIONS)
        assertEquals(goldenMaster, hex(keys.master))
    }

    @Test
    fun `this provider reproduces the published key derivation`() {
        // No production caller yet: the backup UI's self-test and Check backup call it (the
        // crypto is otherwise unreferenced outside this package, so R8 would remove it).
        assertTrue(BackupKeys.providerMatchesGoldenVector())
    }

    @Test
    fun `the golden header encodes byte for byte`() {
        val header = BackupHeader(BackupKind.PACK, BackupFormat.MIN_ITERATIONS, unhex(goldenHeader).copyOfRange(16, 32), 128)
        assertEquals(goldenHeader, hex(header.encode()))
        assertEquals(BackupFormat.HEADER_SIZE, header.encode().size)
    }

    @Test
    fun `a file written by the independent implementation decrypts`() {
        val file = unhex(goldenFile)
        assertEquals(324, file.size)
        val header = BackupHeader.parse(file)
        val keys = BackupKeys.derive(goldenPassphrase.toCharArray(), header.setSalt, header.iterations)
        val opened = BackupStreams.open(ByteArrayInputStream(file), keys, BackupKind.PACK)
        assertEquals(BackupKind.PACK, opened.header.kind)
        assertArrayEquals(goldenPlaintext, opened.plaintext.readBytes())
    }

    @Test
    fun `the golden file fails under another passphrase and under changed bytes`() {
        val file = unhex(goldenFile)
        assertThrows(BackupException.WrongPassphraseOrDamaged::class.java) {
            BackupTestSupport.open(file, BackupTestSupport.newKeys(unhex(goldenHeader).copyOfRange(16, 32)))
        }
        val flipped = file.copyOf().also { it[file.size - 1] = (it[file.size - 1].toInt() xor 1).toByte() }
        val keys = BackupKeys.derive(goldenPassphrase.toCharArray(), unhex(goldenHeader).copyOfRange(16, 32), BackupFormat.MIN_ITERATIONS)
        val drained = BackupTestSupport.drain(flipped, keys)
        assertTrue(drained.failure is BackupException.Damaged)
    }

    @Test
    fun `a header parses back to the same bytes`() {
        val header = BackupHeader(BackupKind.MANIFEST, 600_000, BackupTestSupport.salt, 1 shl 20)
        val again = BackupHeader.parse(header.encode())
        assertArrayEquals(header.encode(), again.encode())
        assertEquals(BackupKind.MANIFEST, again.kind)
        assertEquals(600_000, again.iterations)
        assertEquals(1 shl 20, again.segmentSize)
        assertArrayEquals(BackupTestSupport.salt, again.setSalt)
    }

    @Test
    fun `reading a header consumes exactly its 36 bytes`() {
        val bytes = unhex(goldenFile)
        val input = ByteArrayInputStream(bytes)
        BackupHeader.read(input)
        assertEquals(bytes.size - BackupFormat.HEADER_SIZE, input.available())
    }

    @Test
    fun `short input and a wrong magic are not a backup`() {
        assertThrows(BackupException.NotABackup::class.java) { BackupHeader.parse(ByteArray(10)) }
        assertThrows(BackupException.NotABackup::class.java) { BackupHeader.read(ByteArrayInputStream(ByteArray(35))) }
        val wrong = unhex(goldenHeader).also { it[3] = 0 }
        assertThrows(BackupException.NotABackup::class.java) { BackupHeader.parse(wrong) }
    }

    @Test
    fun `other versions are refused before any key is derived`() {
        for (version in listOf(0, 2, 0x0100, 0xFFFF)) {
            val bytes = unhex(goldenHeader)
            bytes[8] = (version shr 8).toByte()
            bytes[9] = version.toByte()
            val e = assertThrows(BackupException.UnsupportedVersion::class.java) { BackupHeader.parse(bytes) }
            assertEquals(version, e.version)
        }
    }

    @Test
    fun `an unknown kind or KDF is refused`() {
        val kind = unhex(goldenHeader).also { it[10] = 9 }
        assertThrows(BackupException.UnsupportedKind::class.java) { BackupHeader.parse(kind) }
        val kdf = unhex(goldenHeader).also { it[11] = 2 }
        assertThrows(BackupException.UnsupportedKdf::class.java) { BackupHeader.parse(kdf) }
    }

    @Test
    fun `iteration counts outside the range are refused without running the KDF`() {
        for (iterations in listOf(0L, 1L, 99_999L, 10_000_001L, 4_000_000_000L)) {
            val bytes = unhex(goldenHeader)
            for (i in 0..3) bytes[12 + i] = (iterations shr (8 * (3 - i))).toByte()
            val e = assertThrows(BackupException.KdfOutOfRange::class.java) { BackupHeader.parse(bytes) }
            assertEquals(iterations, e.iterations)
        }
        // Int.MAX_VALUE rounds would take hours; the bound check must answer at once.
        val started = System.nanoTime()
        assertThrows(BackupException.KdfOutOfRange::class.java) {
            BackupKeys.derive("long enough passphrase".toCharArray(), BackupTestSupport.salt, Int.MAX_VALUE)
        }
        assertThrows(BackupException.KdfOutOfRange::class.java) {
            BackupKeys.derive("long enough passphrase".toCharArray(), BackupTestSupport.salt, 99_999)
        }
        assertTrue((System.nanoTime() - started) < 2_000_000_000L)
        BackupFormat.checkIterations(BackupFormat.MIN_ITERATIONS.toLong())
        BackupFormat.checkIterations(BackupFormat.MAX_ITERATIONS.toLong())
    }

    @Test
    fun `segment sizes outside the range are refused`() {
        for (size in listOf(0L, 127L, (8L shl 20) + 1, 0xFFFF_FFFFL)) {
            val bytes = unhex(goldenHeader)
            for (i in 0..3) bytes[32 + i] = (size shr (8 * (3 - i))).toByte()
            val e = assertThrows(BackupException.BadSegmentSize::class.java) { BackupHeader.parse(bytes) }
            assertEquals(size, e.segmentSize)
        }
        BackupFormat.checkSegmentSize(128)
        BackupFormat.checkSegmentSize(8L shl 20)
    }

    @Test
    fun `keys match a header only for the same set and iteration count`() {
        val keys = BackupTestSupport.keys
        val same = BackupHeader(BackupKind.PACK, BackupFormat.MIN_ITERATIONS, BackupTestSupport.salt, 128)
        assertTrue(keys.matches(same))
        assertFalse(keys.matches(BackupHeader(BackupKind.PACK, BackupFormat.MIN_ITERATIONS, BackupTestSupport.otherSalt, 128)))
        assertFalse(keys.matches(BackupHeader(BackupKind.PACK, BackupFormat.MIN_ITERATIONS + 1, BackupTestSupport.salt, 128)))
    }

    @Test
    fun `a new set gets a fresh salt every time`() {
        val a = BackupKeys.newSet("one passphrase of length".toCharArray(), BackupFormat.MIN_ITERATIONS)
        val b = BackupKeys.newSet("one passphrase of length".toCharArray(), BackupFormat.MIN_ITERATIONS)
        assertEquals(BackupFormat.SALT_SIZE, a.setSalt.size)
        assertFalse(a.setSalt.contentEquals(b.setSalt))
        assertFalse(a.master.contentEquals(b.master))
    }

    @Test
    fun `the passphrase array is zeroed unless the caller keeps it`() {
        val wiped = "wipe me after use".toCharArray()
        BackupKeys.derive(wiped, BackupTestSupport.salt, BackupFormat.MIN_ITERATIONS)
        assertTrue(wiped.all { it == '\u0000' })
        val kept = "keep me after use".toCharArray()
        BackupKeys.derive(kept, BackupTestSupport.salt, BackupFormat.MIN_ITERATIONS, wipePassphrase = false)
        assertEquals("keep me after use", String(kept))
    }

    @Test
    fun `closing the keys zeroes the master key`() {
        val keys = BackupTestSupport.newKeys()
        val live = keys.master
        assertFalse(live.all { it == 0.toByte() })
        keys.close()
        assertTrue(live.all { it == 0.toByte() })
        assertTrue(keys.isWiped)
        assertThrows(IllegalStateException::class.java) { keys.master }
        keys.close()
    }

    @Test
    fun `an empty passphrase and a wrong salt length are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeys.derive(CharArray(0), BackupTestSupport.salt, BackupFormat.MIN_ITERATIONS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeys.derive("long enough passphrase".toCharArray(), ByteArray(15), BackupFormat.MIN_ITERATIONS)
        }
    }

    @Test
    fun `the minimum passphrase length counts characters after normalisation`() {
        assertFalse(BackupPassphrase.isLongEnough("short one".toCharArray()))
        assertTrue(BackupPassphrase.isLongEnough("twelve chars".toCharArray()))
        // Twelve decomposed accents are twelve characters, not twenty-four.
        assertFalse(BackupPassphrase.isLongEnough("ééééééééééé".toCharArray()))
        assertTrue(BackupPassphrase.isLongEnough("éééééééééééé".toCharArray()))
        // A surrogate pair is one character.
        assertFalse(BackupPassphrase.isLongEnough("😀".repeat(11).toCharArray()))
        assertTrue(BackupPassphrase.isLongEnough("😀".repeat(12).toCharArray()))
    }

    @Test
    fun `a hostile header cannot select a smaller cost than the keys were derived with`() {
        val keys = BackupTestSupport.keys
        val file = BackupTestSupport.seal(BackupTestSupport.plaintext(10))
        val lowered = file.copyOf()
        for (i in 0..3) lowered[12 + i] = (99_999 shr (8 * (3 - i))).toByte()
        assertThrows(BackupException.KdfOutOfRange::class.java) { BackupTestSupport.open(lowered, keys) }
        assertNotEquals(hex(file), hex(lowered))
    }

    @Test
    fun `the vector the app carries for its self-check is this published one`() {
        assertEquals(goldenPassphrase, BackupGoldenVector.PASSPHRASE)
        assertArrayEquals(unhex(goldenFile), BackupGoldenVector.FILE)
        assertArrayEquals(goldenPlaintext, BackupGoldenVector.PLAINTEXT)
        assertArrayEquals(unhex(goldenMaster), BackupGoldenVector.MASTER)
        assertArrayEquals(unhex(goldenHeader).copyOfRange(16, 32), BackupGoldenVector.SALT)
    }
}
