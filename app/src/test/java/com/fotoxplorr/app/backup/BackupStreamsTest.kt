package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.PASSPHRASE
import com.fotoxplorr.app.backup.BackupTestSupport.SEGMENT
import com.fotoxplorr.app.backup.BackupTestSupport.drain
import com.fotoxplorr.app.backup.BackupTestSupport.keys
import com.fotoxplorr.app.backup.BackupTestSupport.open
import com.fotoxplorr.app.backup.BackupTestSupport.otherPassphraseKeys
import com.fotoxplorr.app.backup.BackupTestSupport.otherSetKeys
import com.fotoxplorr.app.backup.BackupTestSupport.plaintext
import com.fotoxplorr.app.backup.BackupTestSupport.seal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Round trips and every way a file can be wrong. With a 128-byte segment the first segment holds
 * 72 plaintext bytes (the 40-byte stream header sits inside it) and every later one 112, and the
 * file is 36 header bytes then segments of 128 ciphertext bytes.
 */
class BackupStreamsTest {
    private val headerSize = BackupFormat.HEADER_SIZE
    private val firstCapacity = SEGMENT - 40 - 16
    private val laterCapacity = SEGMENT - 16

    /** Start of segment [k]'s ciphertext in a file. */
    private fun segmentStart(k: Int) = headerSize + k * SEGMENT

    /** Plaintext offset where segment [k] begins. */
    private fun plaintextStart(k: Int) = if (k == 0) 0 else firstCapacity + (k - 1) * laterCapacity

    private val body = plaintext(300) // 72 + 112 + 112 + 4: four segments
    private val sealed: ByteArray by lazy { seal(body) }

    private fun flip(file: ByteArray, at: Int, mask: Int = 1): ByteArray =
        file.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }

    // ---- round trips ----

    @Test
    fun `bodies of every awkward size come back identical`() {
        val sizes = listOf(
            0, 1, firstCapacity - 1, firstCapacity, firstCapacity + 1,
            firstCapacity + laterCapacity - 1, firstCapacity + laterCapacity, firstCapacity + laterCapacity + 1,
            firstCapacity + 2 * laterCapacity, 3 * SEGMENT, 3 * SEGMENT + 1, 1000,
        )
        for (size in sizes) {
            val data = plaintext(size, seed = size)
            val file = seal(data)
            assertArrayEquals("size $size", data, open(file).readBytes())
            assertEquals(
                "size $size",
                file.size.toLong(),
                BackupStreams.fileSize(size.toLong(), SEGMENT),
            )
        }
    }

    @Test
    fun `an empty body is one final segment`() {
        val file = seal(ByteArray(0))
        assertEquals(36 + 40 + 16, file.size)
        assertEquals(-1, open(file).read())
    }

    @Test
    fun `ten megabytes round trip at the default segment size`() {
        val data = plaintext(10 * 1024 * 1024 + 123, seed = 99)
        val file = seal(data, segment = BackupFormat.DEFAULT_SEGMENT_SIZE)
        assertEquals(
            BackupStreams.fileSize(data.size.toLong(), BackupFormat.DEFAULT_SEGMENT_SIZE),
            file.size.toLong(),
        )
        assertArrayEquals(data, open(file).readBytes())
    }

    @Test
    fun `the reader works with single byte and odd sized reads`() {
        val one = open(sealed)
        val collected = ByteArrayOutputStream()
        while (true) {
            val b = one.read()
            if (b < 0) break
            collected.write(b)
        }
        assertArrayEquals(body, collected.toByteArray())
        for (chunk in listOf(7, 111, 112, 113, 1000)) {
            assertArrayEquals("chunk $chunk", body, drain(sealed, chunk = chunk).bytes)
        }
    }

    @Test
    fun `writes in odd pieces give the same plaintext`() {
        val out = ByteArrayOutputStream()
        BackupStreams.encrypt(out, BackupKind.MANIFEST, keys, SEGMENT).use { sink ->
            var at = 0
            for (piece in listOf(1, 5, 70, 0, 111, 113)) {
                sink.write(body, at, piece)
                at += piece
            }
            sink.write(body, at, body.size - at)
        }
        val opened = BackupStreams.open(ByteArrayInputStream(out.toByteArray()), keys)
        assertEquals(BackupKind.MANIFEST, opened.header.kind)
        assertArrayEquals(body, opened.plaintext.readBytes())
    }

    @Test
    fun `closing the writer twice is harmless`() {
        val out = ByteArrayOutputStream()
        val sink = BackupStreams.encrypt(out, BackupKind.PACK, keys, SEGMENT)
        sink.write(body)
        sink.close()
        sink.close()
        assertArrayEquals(body, open(out.toByteArray()).readBytes())
    }

    @Test
    fun `encrypted size matches the real output for many lengths`() {
        for (size in 0..700) {
            val out = ByteArrayOutputStream()
            BackupStreams.encrypt(out, BackupKind.PACK, keys, SEGMENT).use { it.write(ByteArray(size)) }
            assertEquals("size $size", out.size().toLong(), BackupStreams.fileSize(size.toLong(), SEGMENT))
        }
    }

    @Test
    fun `every file kind round trips and keeps its kind`() {
        for (kind in BackupKind.entries) {
            val opened = BackupStreams.open(ByteArrayInputStream(seal(body, kind = kind)), keys)
            assertEquals(kind, opened.header.kind)
            assertArrayEquals(body, opened.plaintext.readBytes())
        }
    }

    // ---- nonce and key hygiene ----

    @Test
    fun `two files from the same keys share no stream salt or nonce prefix`() {
        val a = seal(body)
        val b = seal(body)
        val saltA = a.copyOfRange(headerSize + 1, headerSize + 33)
        val saltB = b.copyOfRange(headerSize + 1, headerSize + 33)
        val prefixA = a.copyOfRange(headerSize + 33, headerSize + 40)
        val prefixB = b.copyOfRange(headerSize + 33, headerSize + 40)
        assertFalse(saltA.contentEquals(saltB))
        assertFalse(prefixA.contentEquals(prefixB))
        assertFalse(a.copyOfRange(headerSize + 40, a.size).contentEquals(b.copyOfRange(headerSize + 40, b.size)))
        assertEquals(40, a[headerSize].toInt())
    }

    @Test
    fun `identical segments inside one file do not repeat on the wire`() {
        val repeated = seal(ByteArray(firstCapacity + 3 * laterCapacity))
        val one = repeated.copyOfRange(segmentStart(1), segmentStart(2))
        val two = repeated.copyOfRange(segmentStart(2), segmentStart(3))
        assertFalse(one.contentEquals(two))
    }

    // ---- headers ----

    @Test
    fun `flipping any bit of any header byte never opens the file`() {
        for (at in 0 until headerSize) {
            for (mask in listOf(0x01, 0x80)) {
                val tampered = flip(sealed, at, mask)
                val failure = drain(tampered).failure
                assertNotNull("byte $at mask $mask", failure)
                assertTrue("byte $at mask $mask: $failure", failure is BackupException)
            }
        }
    }

    @Test
    fun `specific header damage gives the specific error`() {
        assertTrue(drain(flip(sealed, 0)).failure is BackupException.NotABackup)
        assertTrue(drain(flip(sealed, 9)).failure is BackupException.UnsupportedVersion)
        assertTrue(drain(flip(sealed, 10, mask = 3)).failure is BackupException.WrongPassphraseOrDamaged) // PACK to MANIFEST
        assertTrue(drain(flip(sealed, 10)).failure is BackupException.UnsupportedKind)
        assertTrue(drain(flip(sealed, 11)).failure is BackupException.UnsupportedKdf)
        assertTrue(drain(flip(sealed, 16)).failure is BackupException.DifferentSet) // set salt
        assertTrue(drain(flip(sealed, 35)).failure is BackupException.WrongPassphraseOrDamaged) // segment size 128 to 129
    }

    @Test
    fun `a kind swap fails authentication`() {
        val manifest = seal(body, kind = BackupKind.MANIFEST)
        val asPack = manifest.copyOf().also { it[10] = BackupKind.PACK.code.toByte() }
        assertTrue(drain(asPack).failure is BackupException.WrongPassphraseOrDamaged)
    }

    // ---- passphrase and set ----

    @Test
    fun `a wrong passphrase looks exactly like a damaged first segment`() {
        val wrong = drain(sealed, otherPassphraseKeys).failure
        val damaged = drain(flip(sealed, segmentStart(0) + 60)).failure
        assertTrue(wrong is BackupException.WrongPassphraseOrDamaged)
        assertTrue(damaged is BackupException.WrongPassphraseOrDamaged)
        assertEquals(wrong!!.message, damaged!!.message)
        assertEquals(0, drain(sealed, otherPassphraseKeys).bytes.size)
    }

    @Test
    fun `a file from another set does not open even with the same passphrase`() {
        val fromOtherSet = seal(body, otherSetKeys, BackupKind.MANIFEST)
        assertTrue(drain(fromOtherSet, keys).failure is BackupException.DifferentSet)
        assertArrayEquals(body, drain(fromOtherSet, otherSetKeys).bytes)
    }

    @Test
    fun `the set salt is bound into the key and into the associated data`() {
        val header = BackupHeader.parse(sealed)
        val stream = sealed.copyOfRange(headerSize, sealed.size)
        // The right key and the right header decrypt.
        TinkChunkedAead.decrypt(ByteArrayInputStream(stream), keys.master, SEGMENT, header.encode()).readBytes()
        // The same header with another set salt does not, though the key is untouched.
        val foreign = BackupHeader(header.kind, header.iterations, BackupTestSupport.otherSalt, header.segmentSize)
        assertThrows(IOException::class.java) {
            TinkChunkedAead.decrypt(ByteArrayInputStream(stream), keys.master, SEGMENT, foreign.encode()).readBytes()
        }
        // Another set's master key, the same passphrase: no.
        assertThrows(IOException::class.java) {
            TinkChunkedAead.decrypt(ByteArrayInputStream(stream), otherSetKeys.master, SEGMENT, header.encode()).readBytes()
        }
        assertEquals(BackupTestSupport.PASSPHRASE, PASSPHRASE)
    }

    // ---- damage inside the body ----

    @Test
    fun `a flipped bit in the first middle and last byte of every segment is caught`() {
        val segments = 4
        for (k in 0 until segments) {
            val start = segmentStart(k)
            val end = minOf(segmentStart(k + 1), sealed.size) - 1
            for (at in listOf(start, (start + end) / 2, end)) {
                val drained = drain(flip(sealed, at))
                assertNotNull("segment $k byte $at", drained.failure)
                assertTrue(
                    "segment $k byte $at: ${drained.failure}",
                    if (k == 0) drained.failure is BackupException.WrongPassphraseOrDamaged
                    else drained.failure is BackupException.Damaged,
                )
                // Nothing of the damaged segment, and nothing after it, is ever released.
                assertTrue(
                    "segment $k released ${drained.bytes.size} bytes",
                    drained.bytes.size <= plaintextStart(k),
                )
                assertArrayEquals(body.copyOf(drained.bytes.size), drained.bytes)
            }
        }
    }

    @Test
    fun `truncation at a segment boundary is caught`() {
        for (k in 1..3) {
            val drained = drain(sealed.copyOf(segmentStart(k)))
            assertTrue("boundary $k: ${drained.failure}", drained.failure is BackupException)
        }
    }

    @Test
    fun `truncation inside a segment or inside the header is caught`() {
        for (length in listOf(sealed.size - 1, sealed.size - 16, segmentStart(2) + 5, segmentStart(1) - 1, headerSize + 10, headerSize + 1, headerSize)) {
            val drained = drain(sealed.copyOf(length))
            assertTrue("length $length: ${drained.failure}", drained.failure is BackupException)
        }
        for (length in listOf(0, 1, headerSize - 1)) {
            assertTrue(drain(sealed.copyOf(length)).failure is BackupException.NotABackup)
        }
    }

    @Test
    fun `appended bytes and appended segments are caught`() {
        assertTrue(drain(sealed + byteArrayOf(0)).failure is BackupException.Damaged)
        assertTrue(drain(sealed + sealed.copyOfRange(segmentStart(1), segmentStart(2))).failure is BackupException.Damaged)
        assertTrue(drain(sealed + ByteArray(SEGMENT)).failure is BackupException.Damaged)
    }

    @Test
    fun `swapped segments are caught`() {
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, segmentStart(1), swapped, segmentStart(2), SEGMENT)
        System.arraycopy(sealed, segmentStart(2), swapped, segmentStart(1), SEGMENT)
        val drained = drain(swapped)
        assertTrue(drained.failure is BackupException.Damaged)
        assertTrue(drained.bytes.size <= plaintextStart(1))
    }

    @Test
    fun `a segment spliced in from another file of the same set is caught`() {
        val other = seal(plaintext(300, seed = 7))
        val spliced = sealed.copyOf()
        System.arraycopy(other, segmentStart(1), spliced, segmentStart(1), SEGMENT)
        assertTrue(drain(spliced).failure is BackupException.Damaged)
        val firstSpliced = sealed.copyOf()
        System.arraycopy(other, segmentStart(0), firstSpliced, segmentStart(0), SEGMENT)
        // Another file's whole first segment is a valid first segment of its own (it carries its own
        // stream header), so it opens; the second segment, sealed under the other stream's key, does not.
        val drained = drain(firstSpliced)
        assertTrue(drained.failure is BackupException.Damaged)
        assertTrue(drained.bytes.size <= firstCapacity)
    }

    @Test
    fun `a dropped middle segment is caught`() {
        val dropped = sealed.copyOfRange(0, segmentStart(1)) + sealed.copyOfRange(segmentStart(2), sealed.size)
        assertTrue(drain(dropped).failure is BackupException.Damaged)
    }

    // ---- failures of the real source ----

    @Test
    fun `a failing source is reported as itself and not as tampering`() {
        val boom = IOException("card removed")
        val failing = object : InputStream() {
            var served = 0
            override fun read(): Int {
                if (served >= segmentStart(2)) throw boom
                return sealed[served++].toInt() and 0xFF
            }
        }
        val opened = BackupStreams.open(failing, keys)
        val thrown = assertThrows(IOException::class.java) { opened.plaintext.readBytes() }
        assertSame(boom, thrown)
    }

    @Test
    fun `a source that fails at once is reported as itself`() {
        val boom = IOException("permission revoked")
        val header = BackupHeader.parse(sealed)
        val failing = object : InputStream() {
            override fun read(): Int = throw boom
        }
        val thrown = assertThrows(IOException::class.java) { BackupStreams.decrypt(header, failing, keys) }
        assertSame(boom, thrown)
    }

    @Test
    fun `wiped keys are refused`() {
        val own = BackupTestSupport.newKeys()
        own.close()
        assertThrows(IllegalStateException::class.java) { seal(body, own) }
    }
}
